import Foundation
import UIKit
import SwiftUI
import AVFoundation

struct TrialSummary: Codable, Identifiable {
    var id: String
    var date: Date
    var configuration: TrialConfiguration
    var startHostTime: Double
    var stoppedEarly: Bool
    var results: [EyeAnalysis]
    var frameRecords: Int
    var algorithmVersion: String = "corneal-tracking-7-iris80-temporal"
    var eligibilityNotes: [String] = []
    var eyeMediaFiles: [String]? = nil
    var nativeSnapshots: Bool? = nil
    var planOrder: Int? = nil
    var planSeed: Int? = nil
    var statusTitle: String {
        if stoppedEarly { return "提前停止，不计完整对照" }
        if results.contains(where: { $0.tracking != nil }) {
            if configuration.mode.isPattern { return "\(results.reduce(0) { $0+($1.tracking?.patternMatchedFrames ?? 0) }) 个眼区帧支持图案结构" }
            if configuration.mode.usesTemporalCode { return "\(results.filter { $0.tracking?.codeVerified == true }.count) 眼区通过编码时序核查" }
            return "已生成逐帧轨迹与质量记录"
        }
        if configuration.mode.isScreen { return "\(results.filter { $0.screenResult?.structuralEvidence == true }.count) 眼区通过历史空间模板检查" }
        return "\(results.filter(\.codedEvidence).count) 眼区通过历史编码验证"
    }
}

final class LabModel: ObservableObject {
    enum Phase { case setup, warming, recording, analyzing }
    @Published var configuration = TrialConfiguration.patternDefault
    @Published var phase = Phase.setup
    @Published var measuredDistanceCM: Double?
    @Published var measuredIrisPixels: Double?
    @Published var cameraStatus = "先准备相机，检查双眼是否清晰"
    @Published var cameraReady = false
    @Published var error: String?
    @Published var saved: [TrialSummary] = []
    @Published var selected: TrialSummary?
    @Published var shareURLs: [URL] = []
    @Published var showingShare = false
    @Published var exporting = false
    @Published var reanalyzing = false
    let driver = StimulusDriver()
    private let camera = LabCamera()
    private var warmupTask: DispatchWorkItem?
    private var originalBrightness: CGFloat?
    private var activeConfig: TrialConfiguration?
    private var directory: URL?
    private var startTime = 0.0
    @Published var experimentPlan: ExperimentPlan?
    @Published var planIndex = 0
    private var preparedConfig: TrialConfiguration?
    private var preparing = false
    private var generation = 0
    private var previousIdleTimerDisabled = false
    private var distanceTimestamp = -Double.infinity
    var captureSession: AVCaptureSession { camera.captureSession }

    var root: URL { FileManager.default.urls(for: .documentDirectory,in: .userDomainMask)[0].appendingPathComponent("CornealCodeLab",isDirectory: true) }
    init() {
        camera.onPreview = { [weak self] status,ready,distance,time,iris in DispatchQueue.main.async {
            self?.cameraStatus = status; self?.cameraReady = ready; self?.measuredDistanceCM = distance; self?.distanceTimestamp = time; self?.measuredIrisPixels = iris
        } }
        camera.onError = { [weak self] error in DispatchQueue.main.async { self?.fail(error) } }
        driver.onEnd = { [weak self] in self?.stop(early: false) }
        loadSaved(); restorePlan()
    }
    private var planURL: URL { root.appendingPathComponent("active-plan.json") }
    private struct PlanProgress: Codable { var plan: ExperimentPlan; var index: Int }
    func importPlan(_ url: URL) {
        guard phase == .setup else { return }
        let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }
        do {
            let data = try Data(contentsOf: url); guard data.count<=2_000_000 else { throw ExperimentPlan.PlanError.invalid }
            let plan = try JSONDecoder().decode(ExperimentPlan.self,from: data); try plan.validate()
            guard plan.rounds.allSatisfy({ TrialMode.available.contains($0.configuration.mode) }) else { throw ExperimentPlan.PlanError.invalid }
            experimentPlan = plan; planIndex = 0; applyPlanRound(); try savePlan()
        } catch { self.error = "实验清单无法读取或参数无效："+error.localizedDescription }
    }
    private func applyPlanRound() {
        if let plan = experimentPlan,planIndex<plan.rounds.count { configuration = plan.rounds[planIndex].configuration; cameraReady = false; preparedConfig = nil }
    }
    private func savePlan() throws {
        guard let plan = experimentPlan else { return }
        try FileManager.default.createDirectory(at: root,withIntermediateDirectories: true)
        try JSONEncoder().encode(PlanProgress(plan: plan,index: planIndex)).write(to: planURL,options: .atomic)
    }
    private func restorePlan() {
        guard let data = try? Data(contentsOf: planURL),let progress = try? JSONDecoder().decode(PlanProgress.self,from: data),
              (try? progress.plan.validate()) != nil,progress.plan.rounds.allSatisfy({ TrialMode.available.contains($0.configuration.mode) }),progress.index>=0,progress.index<=progress.plan.rounds.count else { return }
        experimentPlan = progress.plan; planIndex = progress.index; applyPlanRound()
    }
    func leavePlan() { experimentPlan = nil; planIndex = 0; try? FileManager.default.removeItem(at: planURL) }

    func prepare() { guard phase == .setup,!reanalyzing else { return }; error = nil; cameraReady = false; preparedConfig = configuration; camera.prepare(reuseExposure: configuration.reuseExposure,configuration: configuration) }

    func begin() {
        guard phase == .setup, cameraReady,!reanalyzing else { return }
        guard TrialMode.available.contains(configuration.mode) else { error = "该模式已移除，仅可查看历史记录"; return }
        guard let measuredDistance = measuredDistanceCM,labHostTime()-distanceTimestamp<0.6 else { error = "自动测距尚未稳定，请正对相机并重新检查双眼"; return }
        if let plan = experimentPlan,planIndex>=plan.rounds.count { error = "实验清单已完成"; return }
        if let issue = configuration.validationError { error = issue; return }
        guard let prepared = preparedConfig,prepared.prefer4K == configuration.prefer4K,prepared.captureFPS == configuration.captureFPS,
              prepared.exposureMS == configuration.exposureMS,prepared.exposureISO == configuration.exposureISO else { error = "采集参数已改变，请重新准备相机"; return }
        error = nil; selected = nil
        generation += 1
        let currentGeneration = generation
        var config = configuration
        config.automaticDistance = true; config.distanceCM = measuredDistance
        config.displayWidth = Double(UIScreen.main.bounds.width); config.displayHeight = Double(UIScreen.main.bounds.height)
        let screen = DeviceOptics.screen(UIScreen.main)
        config.screenWidthMM = screen.0; config.screenHeightMM = screen.1; config.screenSizeSource = screen.2
        activeConfig = config
        originalBrightness = UIScreen.main.brightness
        UIScreen.main.brightness = config.screenBrightness
        previousIdleTimerDisabled = UIApplication.shared.isIdleTimerDisabled
        UIApplication.shared.isIdleTimerDisabled = true
        phase = .warming; preparing = true
        camera.applyExposurePolicy(reuse: config.reuseExposure,configuration: config)
        driver.warmup(config)
        let task = DispatchWorkItem { [weak self] in
            guard let self = self, self.phase == .warming,self.generation == currentGeneration else { return }
            self.camera.lockExposure { result in DispatchQueue.main.async {
                guard self.phase == .warming,self.generation == currentGeneration else { return }
                switch result {
                case .failure(let error): self.fail(error.localizedDescription)
                case .success:
                    do {
                        let id = ISO8601DateFormatter().string(from: Date()).replacingOccurrences(of: ":",with: "-")+"-"+UUID().uuidString.prefix(6)
                        let directory = self.root.appendingPathComponent(id,isDirectory: true)
                        try FileManager.default.createDirectory(at: directory,withIntermediateDirectories: true)
                        self.directory = directory
                        let start = labHostTime()+0.3
                        self.startTime = start
                        self.camera.beginRecording(directory: directory,start: start,captureColor: config.mode == .motion) { recordingResult in DispatchQueue.main.async {
                            guard self.phase == .warming,self.generation == currentGeneration else { return }
                            switch recordingResult {
                            case .failure(let error): self.fail(error.localizedDescription)
                            case .success(let actualStart):
                                self.preparing = false; self.phase = .recording
                                self.startTime = actualStart; self.driver.begin(at: actualStart)
                            }
                        } }
                    } catch { self.fail(error.localizedDescription) }
                }
            } }
        }
        warmupTask = task; DispatchQueue.main.asyncAfter(deadline: .now()+2.5,execute: task)
    }

    func stop(early: Bool = true) {
        guard phase == .warming || phase == .recording else { return }
        generation += 1
        warmupTask?.cancel(); driver.stop(); restoreScreen()
        if preparing { preparing = false; phase = .setup; camera.stop(); cameraReady = false; return }
        phase = .analyzing
        let events = driver.events, config = activeConfig ?? configuration, start = startTime
        let planOrder = experimentPlan == nil ? nil : planIndex+1,planSeed = experimentPlan?.seed
        let deviceInfo: [String: Any] = ["device": UIDevice.current.model,"systemVersion": UIDevice.current.systemVersion,
            "screenNativeWidth": UIScreen.main.nativeBounds.width,"screenNativeHeight": UIScreen.main.nativeBounds.height,
            "screenMaxFPS": UIScreen.main.maximumFramesPerSecond]
        guard let directory = directory else { phase = .setup; return }
        camera.finish { [weak self] samples,records,data,rgb,images,info in
            self?.camera.stop()
            DispatchQueue.global(qos: .userInitiated).async {
                do {
                    let raw = try Data(contentsOf: directory.appendingPathComponent("temporary_eye_pixels.bgra"),options: .mappedIfSafe)
                    let tracks = config.mode.isScreen ? [] : NativeGlintTracking.track(records: records,pixels: raw,bytesPerPixel: 4,configuration: config,events: events,start: start)
                    let results = ["image_left","image_right"].map { eye -> EyeAnalysis in
                        var result = CornealAnalyzer.analyze(eye: eye,samples: samples,events: events,start: start,configuration: config)
                        let analyzed = records.filter { $0.status != "not_analyzed" && ($0.eye == nil || $0.eye == eye) }
                        let delivered = Set(analyzed.map(\.index)).count
                        result.validFrameCoverage = Double(result.samples)/Double(max(1,delivered))
                        result.rejectionCounts = Dictionary(grouping: analyzed.filter { $0.status != "valid" },by: \.status).mapValues(\.count)
                        result.tracking = tracks.first { $0.eye == eye }
                        if let reference = result.tracking?.frames.filter({ $0.source == .observed }).max(by: { $0.confidence<$1.confidence }) { result.referenceHostTime = reference.hostTime }
                        result.opticalPrediction = OpticalPrediction.build(eye: eye,records: records,configuration: config,result: result)
                        return result
                    }
                    var finalConfig = config
                    if let medianDistance = OpticalPrediction.median(records.filter { $0.status == "valid" }.compactMap(\.distanceCM)) { finalConfig.distanceCM = medianDistance }
                    var summary = TrialSummary(id: directory.lastPathComponent,date: Date(),configuration: config,
                        startHostTime: start,stoppedEarly: early,results: results,frameRecords: records.count)
                    summary.planOrder = planOrder; summary.planSeed = planSeed
                    summary.configuration = finalConfig
                    if early { summary.eligibilityNotes.append("提前停止，不能作为完整轮次") }
                    if Set(events.map(\.chip)).count != config.chipCount { summary.eligibilityNotes.append("显示码元日志不完整") }
                    if (info["videoStatus"] as? Int) != AVAssetWriter.Status.completed.rawValue { summary.eligibilityNotes.append("原始视频未成功完成写入") }
                    if results.contains(where: { $0.samples < 60 }) { summary.eligibilityNotes.append("至少一个眼区有效帧不足") }
                    if results.contains(where: { ($0.validFrameCoverage ?? 0)<0.6 }) { summary.eligibilityNotes.append("至少一个眼区的有效帧不足已分析帧的60%，单列低覆盖轮次") }
                    if results.contains(where: { Double($0.opticalPrediction?.distanceSamples ?? 0)<Double(max(1,$0.samples))*0.6 }) { summary.eligibilityNotes.append("至少一个眼区自动测距覆盖不足60%，光学预算仅供有限参考") }
                    let brightness = events.map(\.screenBrightness)
                    if (brightness.max() ?? 0)-(brightness.min() ?? 0)>0.03 { summary.eligibilityNotes.append("采集期间系统屏幕亮度有变化") }
                    let encoder = JSONEncoder(); encoder.outputFormatting = [.prettyPrinted,.sortedKeys]; encoder.dateEncodingStrategy = .iso8601
                    try encoder.encode(events).write(to: directory.appendingPathComponent("display_events.json"))
                    if !tracks.isEmpty { try encoder.encode(tracks).write(to: directory.appendingPathComponent("glint_tracks.json")) }
                    let rowEncoder = JSONEncoder()
                    let rows = try records.map { record -> String in
                        var row = record; row.nativeOffset = nil
                        return String(decoding: try rowEncoder.encode(row),as: UTF8.self)
                    }.joined(separator: "\n")+"\n"
                    try rows.write(to: directory.appendingPathComponent("frames.jsonl"),atomically: true,encoding: .utf8)
                    try data.write(to: directory.appendingPathComponent("patches.f32"))
                    if !rgb.isEmpty { try rgb.write(to: directory.appendingPathComponent("patches_rgb.u8")) }
                    var metadata = info
                    metadata.merge(deviceInfo) { _,new in new }
                    let times = Array(Set(records.map(\.hostTime))).sorted()
                    let elapsed = (times.last ?? start)-(times.first ?? start)
                    metadata["observedCameraFPS"] = elapsed>0 ? Double(max(0,times.count-1))/elapsed : 0
                    metadata["recordedSpanSeconds"] = elapsed
                    if elapsed<config.duration-0.5 { summary.eligibilityNotes.append("相机记录未覆盖完整实验时长") }
                    let exposures = records.map(\.exposureSeconds),isoValues = records.map { Double($0.iso) }
                    if let lo = exposures.min(),let hi = exposures.max(),hi-lo>max(0.0001,lo*0.03) { summary.eligibilityNotes.append("采集期间曝光时间发生变化") }
                    if let lo = isoValues.min(),let hi = isoValues.max(),hi-lo>max(1,lo*0.03) { summary.eligibilityNotes.append("采集期间 ISO 发生变化") }
                    metadata["geometryVersion"] = "80%虹膜半径与眼睑交集 / 原生像素 / 双向时序关联 v7"
                    metadata["trackingPixelSource"] = "相机原生BGRA眼区，录像结束后分析，不占采集回调"
                    metadata["clock"] = "camera PTS converted from session masterClock to host clock; CADisplayLink targetTimestamp + host offset"
                    metadata["timingLimit"] = "No photodiode measurement; lag includes display/camera/ISP timing and rolling shutter."
                    metadata["detector"] = "MediaPipeTasksVision 1.0.0 / 主App相同模型、EyeCamera配置、LandmarkGeometry与EyeOpticalAlgorithm"
                    metadata["patchWidth"] = CornealAnalyzer.width; metadata["patchHeight"] = CornealAnalyzer.height
                    metadata["rgbPatchFormat"] = "interleaved UInt8 R,G,B; byte offsets rgbPatchOffset in frames.jsonl; all trial modes"
                    metadata["distanceMethod"] = "主App共用DepthFromIris，帧内参优先、视场角回退；未跨App读取个人校正系数"
                    metadata["patchFormat"] = "little-endian Float32, 128 columns x 64 rows; native 1:1 when ROI=128x64, otherwise uniformly sampled from expanded ROI; gray=0.2126R+0.7152G+0.0722B; native scale in each frame ROI; byte offsets in frames.jsonl"
                    try JSONSerialization.data(withJSONObject: metadata,options: [.prettyPrinted,.sortedKeys]).write(to: directory.appendingPathComponent("camera.json"))
                    for result in results {
                        var image = images[result.eye]
                        if let time = result.referenceHostTime, let sample = samples.filter({ $0.eye == result.eye }).min(by: { abs($0.hostTime-time)<abs($1.hostTime-time) }) {
                            image = LabCamera.patchImage(sample.pixels)
                        }
                        try image?.pngData()?.write(to: directory.appendingPathComponent(result.eye+".png"))
                    }
                    do {
                        summary.eyeMediaFiles = try EyeMediaExporter.export(directory: directory,records: records,results: results,start: start,videoStartPTS: info["videoStartPTS"] as? Double ?? records.first?.pts ?? 0)
                    } catch { summary.eligibilityNotes.append("彩色眼区资料生成失败："+error.localizedDescription+"。原始全画面视频仍保留。") }
                    do {
                        summary.nativeSnapshots = try NativeEyeSnapshots.write(directory: directory,records: records,results: results,start: start)>0
                    } catch {
                        summary.nativeSnapshots = false
                        summary.eligibilityNotes.append("原生彩色PNG保存失败，图像为视频帧裁剪："+error.localizedDescription)
                    }
                    try encoder.encode(summary).write(to: directory.appendingPathComponent("summary.json"))
                    DispatchQueue.main.async {
                        self?.phase = .setup; self?.cameraReady = false; self?.selected = summary; self?.loadSaved()
                        if !early && summary.eligibilityNotes.isEmpty && planOrder != nil { self?.planIndex += 1; self?.applyPlanRound(); try? self?.savePlan() }
                        if (info["videoWriteDroppedFrames"] as? Int ?? 0)>0 { self?.error = "视频有写入丢帧，请在 camera.json 查看数量；用时间日志核对完整性。" }
                    }
                } catch { DispatchQueue.main.async { self?.phase = .setup; self?.error = "保存或分析失败：\(error.localizedDescription)" } }
            }
        }
    }
    func interrupted() { if phase == .warming || phase == .recording { error = "本轮因切到后台而提前停止，不作为完整对照。"; stop() } else { camera.stop(); cameraReady = false } }
    private func fail(_ message: String) { error = message; if phase == .warming || phase == .recording { stop() }; cameraReady = false }
    private func restoreScreen() {
        if let brightness = originalBrightness { UIScreen.main.brightness = brightness }; originalBrightness = nil
        UIApplication.shared.isIdleTimerDisabled = previousIdleTimerDisabled
    }
    func loadSaved() {
        let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
        let folders = (try? FileManager.default.contentsOfDirectory(at: root,includingPropertiesForKeys: nil)) ?? []
        saved = folders.compactMap { folder in
            guard let data = try? Data(contentsOf: folder.appendingPathComponent("summary.json")) else { return nil }
            return try? decoder.decode(TrialSummary.self,from: data)
        }.sorted { $0.date > $1.date }
    }
    func share(_ trial: TrialSummary) {
        let directory = root.appendingPathComponent(trial.id)
        shareURLs = (try? FileManager.default.contentsOfDirectory(at: directory,includingPropertiesForKeys: nil)) ?? []
        showingShare = true
    }
    func reanalyze(_ trial: TrialSummary) {
        guard !reanalyzing,phase == .setup,!trial.configuration.mode.isScreen else { return }
        reanalyzing = true; error = nil
        let directory = root.appendingPathComponent(trial.id)
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let stage = directory.appendingPathComponent("tracking-"+UUID().uuidString,isDirectory: true)
            defer { try? FileManager.default.removeItem(at: stage) }
            do {
                try FileManager.default.createDirectory(at: stage,withIntermediateDirectories: false)
                let decoder = JSONDecoder()
                let records = try String(contentsOf: directory.appendingPathComponent("frames.jsonl"),encoding: .utf8).split(whereSeparator: \.isNewline).map { try decoder.decode(FrameRecord.self,from: Data($0.utf8)) }
                let events = try decoder.decode([DisplayEvent].self,from: Data(contentsOf: directory.appendingPathComponent("display_events.json")))
                let camera = try JSONSerialization.jsonObject(with: Data(contentsOf: directory.appendingPathComponent("camera.json"))) as? [String:Any]
                let videoStart = camera?["videoStartPTS"] as? Double ?? records.first?.pts ?? 0
                let pixelsURL = stage.appendingPathComponent("eyes.u8")
                let nativeRows = try VideoGlintTracking.prepare(directory: directory,records: records,videoStartPTS: videoStart,output: pixelsURL)
                let pixels = try Data(contentsOf: pixelsURL,options: .mappedIfSafe)
                let tracks = NativeGlintTracking.track(records: nativeRows,pixels: pixels,bytesPerPixel: 1,configuration: trial.configuration,events: events,start: trial.startHostTime)
                var updated = trial
                for i in updated.results.indices {
                    updated.results[i].tracking = tracks.first { $0.eye == updated.results[i].eye }
                    updated.results[i].opticalPrediction = OpticalPrediction.build(eye: updated.results[i].eye,records: records,configuration: trial.configuration,result: updated.results[i])
                }
                var files = try EyeMediaExporter.export(directory: directory,records: records,results: updated.results,start: trial.startHostTime,videoStartPTS: videoStart,trackingOnly: true,outputDirectory: stage)
                files += try NativeEyeSnapshots.annotateExisting(directory: directory,records: records,results: updated.results,start: trial.startHostTime,output: stage)
                let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]; encoder.dateEncodingStrategy = .iso8601
                try encoder.encode(tracks).write(to: stage.appendingPathComponent("glint_tracks.json"))
                let provenance: [String:Any] = ["source":"camera.mov原生眼区解码，含原有H.264压缩","searchRadiusFraction":0.8,"algorithm":"corneal-tracking-7-iris80-temporal","说明":"原始帧日志与旧检测字段保留；时序结果单列，不能用逐帧坐标覆盖率代替检出率。"]
                try JSONSerialization.data(withJSONObject: provenance,options: [.sortedKeys]).write(to: stage.appendingPathComponent("tracking_source.json"))
                if !FileManager.default.fileExists(atPath: directory.appendingPathComponent("summary_before_tracking.json").path) {
                    try FileManager.default.copyItem(at: directory.appendingPathComponent("summary.json"),to: directory.appendingPathComponent("summary_before_tracking.json"))
                }
                for name in files+["glint_tracks.json","tracking_source.json","tracking_media.json"] {
                    let target = directory.appendingPathComponent(name)
                    if FileManager.default.fileExists(atPath: target.path) { try FileManager.default.removeItem(at: target) }
                    try FileManager.default.moveItem(at: stage.appendingPathComponent(name),to: target)
                }
                updated.algorithmVersion = "corneal-tracking-7-iris80-temporal"
                updated.eyeMediaFiles = Array(Set((updated.eyeMediaFiles ?? [])+files)).sorted()
                try encoder.encode(updated).write(to: directory.appendingPathComponent("summary.json"),options: .atomic)
                DispatchQueue.main.async { self?.reanalyzing = false; self?.selected = updated; self?.loadSaved() }
            } catch { DispatchQueue.main.async { self?.reanalyzing = false; self?.error = "逐帧复算失败："+error.localizedDescription } }
        }
    }
    func shareFile(_ trial: TrialSummary,name: String) {
        let file = root.appendingPathComponent(trial.id).appendingPathComponent(name)
        guard FileManager.default.fileExists(atPath: file.path) else { error = "本轮没有生成该文件"; return }
        shareURLs = [file]; showingShare = true
    }
    func shareData(_ trial: TrialSummary,includePixels: Bool) {
        guard !exporting else { return }
        exporting = true
        let directory = root.appendingPathComponent(trial.id)
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            do {
                let file = try TrialDataExport.write(directory: directory,includePixels: includePixels)
                DispatchQueue.main.async { self?.exporting = false; self?.shareURLs = [file]; self?.showingShare = true }
            } catch { DispatchQueue.main.async { self?.exporting = false; self?.error = "数据合并失败："+error.localizedDescription } }
        }
    }
}

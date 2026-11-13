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
    var algorithmVersion: String = "corneal-feasibility-2"
    var eligibilityNotes: [String] = []
}

final class LabModel: ObservableObject {
    enum Phase { case setup, warming, recording, analyzing }
    @Published var configuration = TrialConfiguration()
    @Published var phase = Phase.setup
    @Published var preview: UIImage?
    @Published var cameraStatus = "先准备相机，检查双眼是否清晰"
    @Published var cameraReady = false
    @Published var error: String?
    @Published var saved: [TrialSummary] = []
    @Published var selected: TrialSummary?
    @Published var shareURLs: [URL] = []
    @Published var showingShare = false
    let driver = StimulusDriver()
    private let camera = LabCamera()
    private var warmupTask: DispatchWorkItem?
    private var originalBrightness: CGFloat?
    private var activeConfig: TrialConfiguration?
    private var directory: URL?
    private var startTime = 0.0
    private var preparing = false

    var root: URL { FileManager.default.urls(for: .documentDirectory,in: .userDomainMask)[0].appendingPathComponent("CornealCodeLab",isDirectory: true) }
    init() {
        camera.onPreview = { [weak self] image,status,ready in DispatchQueue.main.async {
            self?.preview = image; self?.cameraStatus = status; self?.cameraReady = ready
        } }
        camera.onError = { [weak self] error in DispatchQueue.main.async { self?.fail(error) } }
        driver.onEnd = { [weak self] in self?.stop(early: false) }
        loadSaved()
    }
    func prepare() { error = nil; camera.prepare(reuseExposure: configuration.reuseExposure) }

    func begin() {
        guard phase == .setup, cameraReady else { return }
        error = nil; selected = nil
        let config = configuration
        activeConfig = config
        originalBrightness = UIScreen.main.brightness
        UIScreen.main.brightness = config.screenBrightness
        UIApplication.shared.isIdleTimerDisabled = true
        phase = .warming; preparing = true
        driver.warmup(config)
        let task = DispatchWorkItem { [weak self] in
            guard let self = self, self.phase == .warming else { return }
            self.camera.lockExposure { result in DispatchQueue.main.async {
                guard self.phase == .warming else { return }
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
                            guard self.phase == .warming else { return }
                            switch recordingResult {
                            case .failure(let error): self.fail(error.localizedDescription)
                            case .success:
                                self.preparing = false; self.phase = .recording
                                self.driver.begin(at: start)
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
        warmupTask?.cancel(); driver.stop(); restoreScreen()
        if preparing { preparing = false; phase = .setup; camera.stop(); cameraReady = false; return }
        phase = .analyzing
        let events = driver.events, config = activeConfig ?? configuration, start = startTime
        let deviceInfo: [String: Any] = ["device": UIDevice.current.model,"systemVersion": UIDevice.current.systemVersion,
            "screenNativeWidth": UIScreen.main.nativeBounds.width,"screenNativeHeight": UIScreen.main.nativeBounds.height,
            "screenMaxFPS": UIScreen.main.maximumFramesPerSecond]
        guard let directory = directory else { phase = .setup; return }
        camera.finish { [weak self] samples,records,data,rgb,images,info in
            self?.camera.stop()
            DispatchQueue.global(qos: .userInitiated).async {
                do {
                    let results = ["image_left","image_right"].map { CornealAnalyzer.analyze(eye: $0,samples: samples,events: events,start: start,configuration: config) }
                    var summary = TrialSummary(id: directory.lastPathComponent,date: Date(),configuration: config,
                        startHostTime: start,stoppedEarly: early,results: results,frameRecords: records.count)
                    if early { summary.eligibilityNotes.append("提前停止，不能作为完整轮次") }
                    if Set(events.map(\.chip)).count != StimulusCode.bits.count { summary.eligibilityNotes.append("显示码元日志不完整") }
                    if (info["videoStatus"] as? Int) != AVAssetWriter.Status.completed.rawValue { summary.eligibilityNotes.append("原始视频未成功完成写入") }
                    if results.contains(where: { $0.samples < 60 }) { summary.eligibilityNotes.append("至少一个眼区有效帧不足") }
                    let brightness = events.map(\.screenBrightness)
                    if (brightness.max() ?? 0)-(brightness.min() ?? 0)>0.03 { summary.eligibilityNotes.append("采集期间系统屏幕亮度有变化") }
                    let encoder = JSONEncoder(); encoder.outputFormatting = [.prettyPrinted,.sortedKeys]; encoder.dateEncodingStrategy = .iso8601
                    try encoder.encode(events).write(to: directory.appendingPathComponent("display_events.json"))
                    let rowEncoder = JSONEncoder()
                    let rows = try records.map { String(decoding: try rowEncoder.encode($0),as: UTF8.self) }.joined(separator: "\n")+"\n"
                    try rows.write(to: directory.appendingPathComponent("frames.jsonl"),atomically: true,encoding: .utf8)
                    try data.write(to: directory.appendingPathComponent("patches.f32"))
                    if !rgb.isEmpty { try rgb.write(to: directory.appendingPathComponent("patches_rgb.u8")) }
                    var metadata = info
                    metadata.merge(deviceInfo) { _,new in new }
                    metadata["observedCameraFPS"] = Double(Set(records.map(\.index)).count)/config.duration
                    metadata["clock"] = "camera PTS converted from session masterClock to host clock; CADisplayLink targetTimestamp + host offset"
                    metadata["timingLimit"] = "No photodiode measurement; lag includes display/camera/ISP timing and rolling shutter."
                    metadata["patchWidth"] = CornealAnalyzer.width; metadata["patchHeight"] = CornealAnalyzer.height
                    metadata["rgbPatchFormat"] = "interleaved UInt8 R,G,B; byte offsets rgbPatchOffset in frames.jsonl; motion trials only"
                    metadata["patchFormat"] = "little-endian Float32, 128 columns x 64 rows, bilinear sampled, pupil-centered eye ROI, gray=0.2126R+0.7152G+0.0722B; byte offsets in frames.jsonl"
                    try JSONSerialization.data(withJSONObject: metadata,options: [.prettyPrinted,.sortedKeys]).write(to: directory.appendingPathComponent("camera.json"))
                    for result in results {
                        var image = images[result.eye]
                        if let time = result.referenceHostTime, let sample = samples.filter({ $0.eye == result.eye }).min(by: { abs($0.hostTime-time)<abs($1.hostTime-time) }) {
                            image = LabCamera.patchImage(sample.pixels)
                        }
                        try image?.pngData()?.write(to: directory.appendingPathComponent(result.eye+".png"))
                    }
                    try encoder.encode(summary).write(to: directory.appendingPathComponent("summary.json"))
                    DispatchQueue.main.async {
                        self?.phase = .setup; self?.cameraReady = false; self?.selected = summary; self?.loadSaved()
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
        UIApplication.shared.isIdleTimerDisabled = false
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
}

import AVFoundation
import EyeScreening
import Foundation
import Photos
import SwiftUI
import UIKit

enum LabDestination {
    case console, capture, dashboard, history, datasetSetup, datasetCapture
}

enum DatasetCapturePhase: Equatable {
    case arming, recording, saving, completed
}

enum LabExportFormat: String, CaseIterable, Identifiable {
    case annotatedVideo, originalVideo, summaryJson, framesJsonl, displayEventsJsonl, framesCsv
    var id: String { rawValue }
    var label: String {
        switch self {
        case .annotatedVideo: return "四联科研拼接视频 (MP4)"
        case .originalVideo: return "原始高清录制视频 (MP4)"
        case .summaryJson: return "完整科研报告 (JSON)"
        case .framesJsonl: return "逐帧明细日志 (JSONL)"
        case .displayEventsJsonl: return "显示事件时间线 (JSONL)"
        case .framesCsv: return "逐帧科研表格 (CSV)"
        }
    }
    var fileName: String {
        switch self {
        case .annotatedVideo: return "annotated.mp4"
        case .originalVideo: return "original.mp4"
        case .summaryJson: return "session_summary.json"
        case .framesJsonl: return "frames.jsonl"
        case .displayEventsJsonl: return "display_events.jsonl"
        case .framesCsv: return "frames.csv"
        }
    }
}

struct StoredSession: Identifiable {
    let id: String
    let directory: URL
    let report: LabSessionReport
    let hasAnnotatedVideo: Bool
    let hasOriginalVideo: Bool
}

@MainActor
final class StrabismusLabStore: ObservableObject {
    @Published var destination: LabDestination = .console
    @Published var config: LabProtocolConfig = LabProtocolConfig(
        subjectId: "SUBJ-001",
        glassesState: "裸眼",
        lightingCondition: "室内自然光",
        clinicalNote: "",
        codingScheme: .mSequence,
        searchRadiusFraction: 0.80,
        chipDurationMs: 250,
        stimulusDiameterMm: 22.0,
        nearDurationSec: 16,
        farDurationSec: 12,
        pursuitDurationSec: 18,
        includeNear: true,
        includeFar: true,
        includeCover: true,
        includePursuit: true,
        lightOffsetXMm: 0.0,
        lightOffsetYMm: 22.0
    )
    @Published var live: LabLiveState = LabLiveState(
        stage: .idle, stageIndex: 0, totalStages: 4, remainingSec: 0, stageProgress: 0,
        stageWaitingConfirm: false, farWaitingConfirm: false,
        stageBriefingTitle: "", stageBriefingBody: "",
        prompt: "保持正面平视，距离约 33 cm", warning: nil,
        faceDetected: false, distanceCm: 0, distanceOk: false, fps: 0,
        yawDeg: nil, pitchDeg: nil, rollDeg: nil, sourceWidth: 0, sourceHeight: 0,
        leftIrisCenter: nil, rightIrisCenter: nil, leftIrisDiameterPx: 0, rightIrisDiameterPx: 0,
        leftVisibilityContrast: nil, rightVisibilityContrast: nil,
        leftCandidates: [], rightCandidates: [],
        stimulusIntensity: 1, stimulusCode: 1, pursuitTargetX: 0.5, pursuitTargetY: 0.5,
        coverStepIndex: 0, coverStepTotal: 7, coverInstruction: "", coverHolding: false,
        capturedFrames: 0, validFrames: 0, searchRadiusFraction: 0.80
    )
    @Published var showPreviewOverlay = true
    @Published var recordVideo = true
    @Published var cameraAuthorized = false
    @Published var cameraError: String?
    @Published var analyzing = false
    @Published var renderingVideo = false
    @Published var videoRenderProgress: Double = 0.0
    @Published var annotatedVideoURL: URL?
    @Published var originalVideoURL: URL?
    @Published var photoSaveBanner: String?
    @Published var activeReport: LabSessionReport?
    @Published var history: [StoredSession] = []
    @Published var renderedIntensity: Float = 1.0

    // MARK: - 快捷数据集采集状态（仅受试者 ID / 25cm·33cm / 裸眼·戴镜）
    @Published var datasetSubjectId: String = "SUBJ-001"
    @Published var datasetTargetDistance: DatasetTargetDistance = .cm33
    @Published var datasetGlasses: DatasetGlassesOption = .bare
    @Published var datasetPhase: DatasetCapturePhase = .arming
    @Published var datasetLiveDistanceCm: Float? = nil
    @Published var datasetDistanceInRange: Bool = false
    @Published var datasetHoldProgress: Double = 0.0
    @Published var datasetHint: String = "请将双眼对准前置镜头"
    @Published var datasetRestartCount: Int = 0
    @Published var datasetLastAbortReason: String? = nil
    @Published var datasetCompletedSample: StoredDatasetSample? = nil
    @Published var datasetSamples: [StoredDatasetSample] = []
    @Published var datasetExportingZip: Bool = false
    @Published var datasetExportError: String? = nil

    static let datasetDurationSec: Int32 = 8
    static let datasetChipDurationMs: Int64 = 125
    static let datasetDistanceToleranceCm: Float = 2.0

    let camera = StrabismusLabCamera()
    let feedback = StrabismusLabFeedback()
    private let videoRecorder = StrabismusLabVideoRecorder()
    private let engine: StrabismusLabEngine
    private var savedBrightness: CGFloat?
    private var activeSessionDir: URL?

    // 快捷数据集内部运行时上下文
    private var datasetSupervisor = DatasetDistanceSupervisor(targetCm: 33.0, toleranceCm: 2.0)
    private var datasetActiveSampleId: String = ""
    private var datasetActiveSampleDir: URL?
    private var datasetDistanceSamples: [Double] = []
    private var datasetLastIntrinsics: LabCameraIntrinsics?
    private var datasetLastIntrinsicsSource: String = "UNKNOWN"
    private var datasetLastSourceSize: (Int, Int) = (0, 0)
    private let hardwareModelId: String
    private let platformDescription: String

    // 语音与音效状态跃迁追踪（防重复播报）
    private var lastAnnouncedStage: LabStage = .idle
    private var lastWaitingConfirm = false
    private var lastRemainingSec: Int32 = -1
    private var lastCoverHolding = false
    private var lastCoverStepIndex: Int32 = -1
    private var lastWarningSpokenMs: Int64 = 0

    init() {
        let model = Self.hardwareModelIdentifier()
        let ver = UIDevice.current.systemVersion
        self.hardwareModelId = model
        self.platformDescription = "iOS \(ver)"
        self.engine = StrabismusLabEngine(platformName: self.platformDescription, deviceModel: model)
        let shortVer = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.9.5"
        let buildNum = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "0"
        self.engine.configureAuditContext(
            appVersion: "\(shortVer) (\(buildNum))",
            gitCommit: "ios-bundle",
            clockDomain: "ProcessInfo.systemUptime",
            displayClockDomain: "ProcessInfo.systemUptime"
        )
        self.camera.onFrame = { [weak self] frame, intrinsics, nowMs, captureMeta, pixelBuffer in
            Task { @MainActor in
                self?.handleCameraFrame(frame, intrinsics: intrinsics, nowMs: nowMs, captureMeta: captureMeta, pixelBuffer: pixelBuffer)
            }
        }
        self.camera.onError = { [weak self] msg in
            Task { @MainActor in self?.cameraError = msg }
        }
        refreshHistory()
        refreshDatasetSamples()
        checkPermission()
    }

    func checkPermission() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            cameraAuthorized = true
            camera.start(searchRadius: config.searchRadiusFraction)
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                Task { @MainActor in
                    self?.cameraAuthorized = granted
                    if granted { self?.camera.start(searchRadius: self?.config.searchRadiusFraction ?? 0.80) }
                }
            }
        default:
            cameraAuthorized = false
        }
    }

    func updateConfig(_ mutator: (LabProtocolConfig) -> LabProtocolConfig) {
        config = mutator(config)
        engine.updatePreviewConfig(newConfig: config)
        camera.setSearchRadius(config.searchRadiusFraction)
        live = engine.live.value as! LabLiveState
    }

    func startCapture() {
        guard !config.activeStages.isEmpty else { return }
        feedback.activateSession()
        savedBrightness = UIScreen.main.brightness
        UIScreen.main.brightness = 1.0
        UIApplication.shared.isIdleTimerDisabled = true
        resetFeedbackTrackers()

        let sessionId = engine.startSession(newConfig: config, nowMs: uptimeMs())
        let dir = sessionsRoot().appendingPathComponent(sanitize(sessionId), isDirectory: true)
        activeSessionDir = dir
        annotatedVideoURL = nil
        originalVideoURL = nil
        photoSaveBanner = nil
        if recordVideo {
            videoRecorder.startSession(sessionId: sessionId, directory: dir)
        }
        let nextLive = engine.live.value as! LabLiveState
        live = nextLive
        destination = .capture
        announceTransition(nextLive, nowMs: uptimeMs())
    }

    func onDisplayTick() {
        guard destination == .capture || (destination == .datasetCapture && datasetPhase == .recording) else { return }
        let pair = engine.recordDisplayTick(hostTimeMs: uptimeMs(), screenBrightness: Double(UIScreen.main.brightness))
        renderedIntensity = pair.first?.floatValue ?? 1.0
    }

    func confirmStageReady() {
        let now = uptimeMs()
        engine.confirmStageReady(nowMs: now)
        let nextLive = engine.live.value as! LabLiveState
        live = nextLive
        feedback.play(.start)
        lastWaitingConfirm = false
        lastRemainingSec = -1
        lastCoverHolding = false
        lastCoverStepIndex = -1
        switch nextLive.stage {
        case .nearFixation:
            camera.setExposureLocked(true)
            feedback.speak("开始看近采集，请注视顶部白点中央，保持不动。")
        case .farFixation:
            camera.setExposureLocked(true)
            feedback.speak("开始看远采集，请持续注视六米外远处目标。")
        case .coverTest:
            camera.setExposureLocked(true)
            feedback.speak("开始动态遮盖。\(nextLive.coverInstruction)")
            lastCoverStepIndex = nextLive.coverStepIndex
        case .pursuit:
            camera.setExposureLocked(false)
            feedback.speak("开始平滑追踪，头部不动，双眼跟随橙色圆点。")
        default:
            camera.setExposureLocked(false)
        }
    }

    func manualAdvanceCover() {
        feedback.play(.confirm)
        engine.manualAdvanceCover(nowMs: uptimeMs())
        let nextLive = engine.live.value as! LabLiveState
        live = nextLive
        if nextLive.stage == .completed {
            finishEarlyAndAnalyze()
        } else {
            announceTransition(nextLive, nowMs: uptimeMs())
        }
    }

    func skipStage() {
        feedback.tap()
        engine.skipCurrentStage(nowMs: uptimeMs())
        let nextLive = engine.live.value as! LabLiveState
        live = nextLive
        if nextLive.stage == .completed {
            finishEarlyAndAnalyze()
        } else {
            announceTransition(nextLive, nowMs: uptimeMs())
        }
    }

    func finishEarlyAndAnalyze() {
        guard !analyzing else { return }
        feedback.tap()
        analyzing = true
        let report = engine.stopAndBuildReport(nowMs: uptimeMs())
        finishWithReport(report)
    }

    func cancelCapture() {
        feedback.tap()
        feedback.deactivateSession()
        camera.setExposureLocked(false)
        restoreBrightness()
        videoRecorder.cancelSession()
        if let cancelled = engine.cancelAndBuildReport(nowMs: uptimeMs(), reason: "USER_CANCELLED") {
            ensureSaved(cancelled)
            refreshHistory()
        }
        live = engine.live.value as! LabLiveState
        destination = .console
    }

    func backToConsole() {
        feedback.tap()
        feedback.stopSpeech()
        camera.setExposureLocked(false)
        restoreBrightness()
        engine.resetToIdle()
        live = engine.live.value as! LabLiveState
        destination = .console
    }

    func openHistory() {
        feedback.tap()
        refreshHistory()
        destination = .history
    }

    // MARK: - 快捷数据集采集流程（8s 完整 m-序列 + ±2 cm 严格距离监督 + 中断自动重来）

    func openDatasetSetup() {
        feedback.tap()
        feedback.stopSpeech()
        camera.setExposureLocked(false)
        restoreBrightness()
        engine.resetToIdle()
        live = engine.live.value as! LabLiveState
        datasetExportError = nil
        refreshDatasetSamples()
        destination = .datasetSetup
    }

    func incrementDatasetSubjectId() {
        feedback.toggle()
        let trimmed = datasetSubjectId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            datasetSubjectId = "SUBJ-001"
            return
        }
        if let range = trimmed.range(of: #"\d+$"#, options: .regularExpression),
           let num = Int(trimmed[range]) {
            let digits = trimmed.distance(from: range.lowerBound, to: range.upperBound)
            let prefix = String(trimmed[..<range.lowerBound])
            datasetSubjectId = prefix + String(format: "%0\(digits)d", num + 1)
        } else {
            datasetSubjectId = "\(trimmed)-001"
        }
    }

    func startDatasetArming() {
        guard cameraAuthorized else { return }
        feedback.activateSession()
        feedback.tap()
        if savedBrightness == nil {
            savedBrightness = UIScreen.main.brightness
        }
        UIScreen.main.brightness = 1.0
        UIApplication.shared.isIdleTimerDisabled = true
        camera.setExposureLocked(false)
        engine.resetToIdle()
        live = engine.live.value as! LabLiveState

        datasetSupervisor = DatasetDistanceSupervisor(
            targetCm: datasetTargetDistance.cm,
            toleranceCm: Self.datasetDistanceToleranceCm
        )
        datasetPhase = .arming
        datasetLiveDistanceCm = nil
        datasetDistanceInRange = false
        datasetHoldProgress = 0.0
        datasetHint = "请保持在 \(datasetTargetDistance.rawValue)±2 cm，到位自动开始"
        datasetRestartCount = 0
        datasetLastAbortReason = nil
        datasetCompletedSample = nil
        datasetDistanceSamples.removeAll(keepingCapacity: true)
        datasetActiveSampleId = ""
        datasetActiveSampleDir = nil
        datasetLastIntrinsics = nil
        datasetLastIntrinsicsSource = "UNKNOWN"
        lastRemainingSec = -1
        destination = .datasetCapture
        feedback.speak("请将手机保持在 \(datasetTargetDistance.rawValue) 厘米，到位后自动开始 8 秒采集", interrupt: true)
    }

    func finishDatasetAndReturnToSetup() {
        feedback.tap()
        feedback.stopSpeech()
        camera.setExposureLocked(false)
        restoreBrightness()
        engine.resetToIdle()
        live = engine.live.value as! LabLiveState
        refreshDatasetSamples()
        destination = .datasetSetup
    }

    func cancelDatasetCapture() {
        feedback.tap()
        feedback.stopSpeech()
        camera.setExposureLocked(false)
        restoreBrightness()
        videoRecorder.cancelSession(removeDirectoryIfIncomplete: true)
        engine.resetToIdle()
        live = engine.live.value as! LabLiveState
        datasetActiveSampleDir = nil
        datasetActiveSampleId = ""
        datasetPhase = .arming
        refreshDatasetSamples()
        destination = .datasetSetup
    }

    func deleteDatasetSample(_ sampleId: String) {
        feedback.tap()
        let dir = datasetRoot().appendingPathComponent(sanitize(sampleId), isDirectory: true)
        try? FileManager.default.removeItem(at: dir)
        refreshDatasetSamples()
    }

    func refreshDatasetSamples() {
        let root = datasetRoot()
        let fm = FileManager.default
        let dirs = (try? fm.contentsOfDirectory(at: root, includingPropertiesForKeys: [.isDirectoryKey])) ?? []
        let decoder = JSONDecoder()
        var items: [StoredDatasetSample] = []
        for dir in dirs {
            let metaURL = dir.appendingPathComponent("dataset_meta.json")
            guard let data = try? Data(contentsOf: metaURL),
                  let meta = try? decoder.decode(DatasetSampleMeta.self, from: data) else { continue }
            let hasVideo = fm.fileExists(atPath: dir.appendingPathComponent("original.mp4").path)
            let hasVF = fm.fileExists(atPath: dir.appendingPathComponent("video_frames.jsonl").path)
            let hasDE = fm.fileExists(atPath: dir.appendingPathComponent("display_events.jsonl").path)
            let bytes = Self.directoryByteSize(dir)
            items.append(StoredDatasetSample(
                id: meta.sampleId,
                directory: dir,
                meta: meta,
                hasVideo: hasVideo,
                hasVideoFrames: hasVF,
                hasDisplayEvents: hasDE,
                totalBytes: bytes
            ))
        }
        datasetSamples = items.sorted { $0.meta.createdAtMs > $1.meta.createdAtMs }
    }

    func exportAllDatasetZip(completion: @escaping (URL?) -> Void) {
        guard !datasetSamples.isEmpty, !datasetExportingZip else {
            completion(nil)
            return
        }
        feedback.tap()
        datasetExportingZip = true
        datasetExportError = nil
        let dirs = datasetSamples.map(\.directory)
        let stamp = Self.fileTimestampSlug()
        let outURL = FileManager.default.temporaryDirectory.appendingPathComponent("StrabismusDataset_\(stamp).zip")
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                let entries = StrabismusDatasetArchive.collectEntries(from: dirs, rootFolderName: "StrabismusDataset")
                try StrabismusDatasetArchive.writeZip(entries: entries, to: outURL)
                DispatchQueue.main.async { [weak self] in
                    self?.datasetExportingZip = false
                    self?.feedback.success()
                    completion(outURL)
                }
            } catch {
                DispatchQueue.main.async { [weak self] in
                    self?.datasetExportingZip = false
                    self?.datasetExportError = "打包失败：\(error.localizedDescription)"
                    completion(nil)
                }
            }
        }
    }

    func exportSingleDatasetZip(_ sample: StoredDatasetSample, completion: @escaping (URL?) -> Void) {
        guard !datasetExportingZip else {
            completion(nil)
            return
        }
        feedback.tap()
        datasetExportingZip = true
        datasetExportError = nil
        let dir = sample.directory
        let outURL = FileManager.default.temporaryDirectory.appendingPathComponent("\(sanitize(sample.id)).zip")
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                let entries = StrabismusDatasetArchive.collectEntries(from: [dir], rootFolderName: "")
                try StrabismusDatasetArchive.writeZip(entries: entries, to: outURL)
                DispatchQueue.main.async { [weak self] in
                    self?.datasetExportingZip = false
                    self?.feedback.success()
                    completion(outURL)
                }
            } catch {
                DispatchQueue.main.async { [weak self] in
                    self?.datasetExportingZip = false
                    self?.datasetExportError = "打包失败：\(error.localizedDescription)"
                    completion(nil)
                }
            }
        }
    }

    private func beginDatasetRecording(nowMs: Int64) {
        let cleanSubject = datasetSubjectId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? "SUBJ-001"
            : datasetSubjectId.trimmingCharacters(in: .whitespacesAndNewlines)
        let sampleId = "\(sanitize(cleanSubject))_\(datasetTargetDistance.folderTag)_\(datasetGlasses.rawValue)_\(Self.fileTimestampSlug())"
        let dir = datasetRoot().appendingPathComponent(sampleId, isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)

        let dsConfig = LabProtocolConfig(
            subjectId: cleanSubject,
            glassesState: datasetGlasses.label,
            lightingCondition: "",
            clinicalNote: "",
            codingScheme: .mSequence,
            searchRadiusFraction: config.searchRadiusFraction,
            chipDurationMs: Self.datasetChipDurationMs,
            stimulusDiameterMm: config.stimulusDiameterMm,
            nearDurationSec: Self.datasetDurationSec,
            farDurationSec: 0,
            pursuitDurationSec: 0,
            includeNear: true,
            includeFar: false,
            includeCover: false,
            includePursuit: false,
            lightOffsetXMm: config.lightOffsetXMm,
            lightOffsetYMm: config.lightOffsetYMm
        )

        datasetActiveSampleId = sampleId
        datasetActiveSampleDir = dir
        datasetDistanceSamples.removeAll(keepingCapacity: true)
        datasetLastAbortReason = nil
        lastRemainingSec = -1

        _ = engine.startSession(newConfig: dsConfig, nowMs: nowMs)
        engine.confirmStageReady(nowMs: nowMs)
        camera.setExposureLocked(true)
        videoRecorder.startSession(sessionId: sampleId, directory: dir, highQualityDatasetMode: true)
        datasetSupervisor.beginRecordingWindow()
        datasetPhase = .recording
        if let state = engine.live.value as? LabLiveState {
            live = state
        }
        feedback.stopSpeech()
        feedback.play(.start)
    }

    private func abortAndRestartDatasetCapture(reason: String, speech: String) {
        camera.setExposureLocked(false)
        videoRecorder.cancelSession(removeDirectoryIfIncomplete: true)
        engine.resetToIdle()
        if let state = engine.live.value as? LabLiveState {
            live = state
        }
        datasetActiveSampleDir = nil
        datasetActiveSampleId = ""
        datasetDistanceSamples.removeAll(keepingCapacity: true)
        datasetRestartCount += 1
        datasetLastAbortReason = reason
        datasetSupervisor.reset()
        datasetPhase = .arming
        datasetHoldProgress = 0.0
        datasetDistanceInRange = false
        datasetHint = "请重新调整至 \(datasetTargetDistance.rawValue)±2 cm"
        lastRemainingSec = -1
        feedback.play(.warning)
        feedback.speak(speech, interrupt: true)
    }

    private func finishDatasetCapture(report: LabSessionReport) {
        guard let dir = datasetActiveSampleDir else {
            cancelDatasetCapture()
            return
        }
        datasetPhase = .saving
        camera.setExposureLocked(false)
        restoreBrightness()
        feedback.play(.complete)
        feedback.speak("采集完成", interrupt: true)

        let sampleId = datasetActiveSampleId
        let subjectId = report.config.subjectId
        let targetDist = datasetTargetDistance
        let glassesOpt = datasetGlasses
        let restarts = datasetRestartCount
        let distList = datasetDistanceSamples
        let intr = report.cameraIntrinsics ?? datasetLastIntrinsics ?? LabCameraIntrinsics(
            fx: 0, fy: 0, cx: 0, cy: 0,
            lightOffsetXMm: config.lightOffsetXMm,
            lightOffsetYMm: config.lightOffsetYMm
        )
        let intrSource = report.auditMeta.intrinsicsSource != "UNKNOWN"
            ? report.auditMeta.intrinsicsSource
            : datasetLastIntrinsicsSource
        let srcW = report.sourceWidth > 0 ? Int(report.sourceWidth) : datasetLastSourceSize.0
        let srcH = report.sourceHeight > 0 ? Int(report.sourceHeight) : datasetLastSourceSize.1
        let displayCount = report.displayEvents.count
        let hwModel = hardwareModelId
        let plat = platformDescription

        // 同步写入 KMP 引擎基线产物，便于电脑端离线脚本直接做基线对比
        try? report.toJson(pretty: true).write(to: dir.appendingPathComponent("session_summary.json"), atomically: true, encoding: .utf8)
        try? report.toFramesJsonl().write(to: dir.appendingPathComponent("frames.jsonl"), atomically: true, encoding: .utf8)
        try? report.toDisplayEventsJsonl().write(to: dir.appendingPathComponent("display_events.jsonl"), atomically: true, encoding: .utf8)

        videoRecorder.finishRawDataset(directory: dir) { [weak self] rawURL, videoFrameCount, videoWidth, videoHeight in
            Task { @MainActor [weak self] in
                guard let self = self else { return }
                let targetD = Double(targetDist.rawValue)
                let minD = distList.min() ?? targetD
                let maxD = distList.max() ?? targetD
                let meanD = distList.isEmpty ? targetD : (distList.reduce(0, +) / Double(distList.count))
                let maxErr = distList.map { abs($0 - targetD) }.max() ?? 0.0
                let createdMs = Int64(Date().timeIntervalSince1970 * 1000)
                let iso = ISO8601DateFormatter().string(from: Date())

                let meta = DatasetSampleMeta(
                    schemaVersion: "dataset-v1",
                    sampleId: sampleId,
                    subjectId: subjectId,
                    targetDistanceCm: targetDist.rawValue,
                    distanceToleranceCm: Double(Self.datasetDistanceToleranceCm),
                    glasses: glassesOpt.rawValue,
                    glassesLabel: glassesOpt.label,
                    durationSec: Int(Self.datasetDurationSec),
                    codingScheme: report.config.codingScheme.name,
                    chipDurationMs: report.config.chipDurationMs,
                    stimulusDiameterMm: report.config.stimulusDiameterMm,
                    searchRadiusFraction: Double(report.config.searchRadiusFraction),
                    deviceModel: hwModel,
                    platformName: plat,
                    clockDomain: "ProcessInfo.systemUptime",
                    sourceWidth: srcW,
                    sourceHeight: srcH,
                    videoWidth: videoWidth,
                    videoHeight: videoHeight,
                    cameraIntrinsics: .init(
                        fx: intr.fx,
                        fy: intr.fy,
                        cx: intr.cx,
                        cy: intr.cy,
                        lightOffsetXMm: intr.lightOffsetXMm,
                        lightOffsetYMm: intr.lightOffsetYMm,
                        source: intrSource
                    ),
                    distanceStats: .init(
                        minCm: minD,
                        maxCm: maxD,
                        meanCm: meanD,
                        maxAbsErrorCm: maxErr
                    ),
                    videoFrameCount: videoFrameCount,
                    displayEventCount: displayCount,
                    restartCount: restarts,
                    createdAtMs: createdMs,
                    createdAtIso: iso
                )

                let encoder = JSONEncoder()
                encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
                if let jsonData = try? encoder.encode(meta) {
                    try? jsonData.write(to: dir.appendingPathComponent("dataset_meta.json"), options: .atomic)
                }

                let bytes = Self.directoryByteSize(dir)
                let stored = StoredDatasetSample(
                    id: sampleId,
                    directory: dir,
                    meta: meta,
                    hasVideo: rawURL != nil,
                    hasVideoFrames: FileManager.default.fileExists(atPath: dir.appendingPathComponent("video_frames.jsonl").path),
                    hasDisplayEvents: FileManager.default.fileExists(atPath: dir.appendingPathComponent("display_events.jsonl").path),
                    totalBytes: bytes
                )
                self.datasetCompletedSample = stored
                self.datasetActiveSampleDir = nil
                self.datasetActiveSampleId = ""
                self.datasetPhase = .completed
                self.refreshDatasetSamples()
                self.feedback.success()
            }
        }
    }

    private func computeInstantDistanceCm(frame: FaceLandmarkFrame, intrinsics: LabCameraIntrinsics) -> Float? {
        guard let face = frame.faces.first else { return nil }
        var diameters: [Float] = []
        if let ld = face.leftEye?.irisDiameterPx, ld > 0 { diameters.append(ld) }
        if let rd = face.rightEye?.irisDiameterPx, rd > 0 { diameters.append(rd) }
        guard !diameters.isEmpty, intrinsics.isValid else { return nil }
        let avgPx = diameters.reduce(0, +) / Float(diameters.count)
        let focalPx = Float((intrinsics.fx + intrinsics.fy) * 0.5)
        let dist = DepthFromIris.shared.distanceCm(irisDiameterPx: avgPx, focalLengthPx: focalPx)
        return (dist.isFinite && dist > 8 && dist < 120) ? dist : nil
    }

    private func datasetRoot() -> URL {
        let base = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let root = base.appendingPathComponent("StrabismusDataset", isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        return root
    }

    private static func fileTimestampSlug() -> String {
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.dateFormat = "yyyyMMdd_HHmmss"
        return df.string(from: Date())
    }

    private static func directoryByteSize(_ dir: URL) -> Int64 {
        let fm = FileManager.default
        guard let files = try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.fileSizeKey]) else { return 0 }
        var total: Int64 = 0
        for f in files {
            if let size = try? f.resourceValues(forKeys: [.fileSizeKey]).fileSize {
                total += Int64(size)
            }
        }
        return total
    }

    func openReport(_ report: LabSessionReport) {
        feedback.tap()
        let dir = sessionsRoot().appendingPathComponent(sanitize(report.sessionId), isDirectory: true)
        let ann = dir.appendingPathComponent("annotated.mp4")
        let orig = dir.appendingPathComponent("original.mp4")
        annotatedVideoURL = FileManager.default.fileExists(atPath: ann.path) ? ann : nil
        originalVideoURL = FileManager.default.fileExists(atPath: orig.path) ? orig : nil
        photoSaveBanner = nil
        activeReport = report
        destination = .dashboard
    }

    func deleteSession(_ id: String) {
        feedback.tap()
        let dir = sessionsRoot().appendingPathComponent(sanitize(id), isDirectory: true)
        try? FileManager.default.removeItem(at: dir)
        refreshHistory()
    }

    func exportURL(for report: LabSessionReport, format: LabExportFormat) -> URL? {
        let dir = ensureSaved(report)
        let url = dir.appendingPathComponent(format.fileName)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    func saveVideoToPhotos(_ url: URL) {
        feedback.tap()
        PHPhotoLibrary.requestAuthorization(for: .addOnly) { [weak self] status in
            guard status == .authorized || status == .limited else {
                Task { @MainActor in self?.photoSaveBanner = "请在系统设置中允许写入相册权限" }
                return
            }
            PHPhotoLibrary.shared().performChanges({
                PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: url)
            }) { success, error in
                Task { @MainActor in
                    if success {
                        self?.feedback.success()
                        self?.photoSaveBanner = "已保存诊断视频到系统相册"
                    } else {
                        self?.photoSaveBanner = "保存失败：\(error?.localizedDescription ?? "未知错误")"
                    }
                }
            }
        }
    }

    private func handleCameraFrame(
        _ frame: FaceLandmarkFrame,
        intrinsics: LabCameraIntrinsics,
        nowMs: Int64,
        captureMeta: LabFrameCaptureMeta,
        pixelBuffer: CVPixelBuffer
    ) {
        if destination == .datasetCapture {
            handleDatasetCameraFrame(frame, intrinsics: intrinsics, nowMs: nowMs, captureMeta: captureMeta, pixelBuffer: pixelBuffer)
            return
        }

        let prevIndex = engine.lastLoggedFrameIndex
        let completed = engine.onFrameWithAudit(frame: frame, intrinsics: intrinsics, nowMs: nowMs, captureMeta: captureMeta)
        let newIndex = engine.lastLoggedFrameIndex
        if recordVideo && destination == .capture && newIndex > prevIndex {
            let face = frame.faces.first
            let lCands = (face?.leftOptical?.reflexCandidates ?? []).map { ($0.point.x, $0.point.y) }
            let rCands = (face?.rightOptical?.reflexCandidates ?? []).map { ($0.point.x, $0.point.y) }
            videoRecorder.appendFrame(
                pixelBuffer,
                frameIndex: newIndex,
                timeMs: nowMs,
                leftCandidates: lCands,
                rightCandidates: rCands
            )
        }
        if let completed = completed {
            finishWithReport(completed)
        } else if let state = engine.live.value as? LabLiveState {
            live = state
            if destination == .datasetSetup {
                datasetLiveDistanceCm = computeInstantDistanceCm(frame: frame, intrinsics: intrinsics) ?? (state.distanceCm > 0 ? state.distanceCm : nil)
            }
            if destination == .capture {
                announceTransition(state, nowMs: nowMs)
            }
        }
    }

    private func handleDatasetCameraFrame(
        _ frame: FaceLandmarkFrame,
        intrinsics: LabCameraIntrinsics,
        nowMs: Int64,
        captureMeta: LabFrameCaptureMeta,
        pixelBuffer: CVPixelBuffer
    ) {
        let face = frame.faces.first
        let hasBinocularFace = (face?.leftEye != nil && face?.rightEye != nil)
        let rawDistCm = computeInstantDistanceCm(frame: frame, intrinsics: intrinsics)
        if intrinsics.isValid {
            datasetLastIntrinsics = intrinsics
            if !captureMeta.intrinsicsSource.isEmpty && captureMeta.intrinsicsSource != "UNKNOWN" {
                datasetLastIntrinsicsSource = captureMeta.intrinsicsSource
            }
        }
        datasetLastSourceSize = (Int(frame.sourceWidth), Int(frame.sourceHeight))

        switch datasetPhase {
        case .arming:
            _ = engine.onFrameWithAudit(frame: frame, intrinsics: intrinsics, nowMs: nowMs, captureMeta: captureMeta)
            if let state = engine.live.value as? LabLiveState { live = state }
            let update = datasetSupervisor.evaluateArming(
                faceDetected: hasBinocularFace,
                rawDistanceCm: rawDistCm,
                nowMs: nowMs
            )
            switch update {
            case .waiting(let dist, let inRange, let progress, let hint):
                datasetLiveDistanceCm = dist
                datasetDistanceInRange = inRange
                datasetHoldProgress = progress
                datasetHint = hint
            case .readyToStart(let dist):
                datasetLiveDistanceCm = dist
                datasetDistanceInRange = true
                datasetHoldProgress = 1.0
                beginDatasetRecording(nowMs: nowMs)
            }

        case .recording:
            let check = datasetSupervisor.evaluateRecording(faceDetected: hasBinocularFace, rawDistanceCm: rawDistCm)
            switch check {
            case .abort(let reason, let speech):
                abortAndRestartDatasetCapture(reason: reason, speech: speech)
                return
            case .ok(let filteredDist, _):
                datasetLiveDistanceCm = filteredDist
                datasetDistanceInRange = abs(filteredDist - datasetTargetDistance.cm) <= Self.datasetDistanceToleranceCm
                datasetDistanceSamples.append(Double(filteredDist))

                let prevIndex = engine.lastLoggedFrameIndex
                let completed = engine.onFrameWithAudit(frame: frame, intrinsics: intrinsics, nowMs: nowMs, captureMeta: captureMeta)
                let newIndex = engine.lastLoggedFrameIndex
                let state = engine.live.value as? LabLiveState

                if newIndex > prevIndex {
                    let lCands = (face?.leftOptical?.reflexCandidates ?? []).map { ($0.point.x, $0.point.y) }
                    let rCands = (face?.rightOptical?.reflexCandidates ?? []).map { ($0.point.x, $0.point.y) }
                    let lCenter = face?.leftEye?.irisPoints.first
                    let rCenter = face?.rightEye?.irisPoints.first
                    let telemetry = StrabismusLabVideoRecorder.DatasetFrameTelemetry(
                        hostTimeMs: nowMs,
                        sourceTimestampMs: captureMeta.sourceTimestampMs?.int64Value,
                        exposureSec: captureMeta.exposureSec?.doubleValue,
                        exposureLocked: captureMeta.exposureLocked,
                        distanceCm: filteredDist,
                        rawDistanceCm: rawDistCm,
                        fps: frame.fps,
                        yawDeg: face?.headPose?.yawDeg,
                        pitchDeg: face?.headPose?.pitchDeg,
                        rollDeg: face?.headPose?.rollDeg,
                        leftIrisX: lCenter?.x,
                        leftIrisY: lCenter?.y,
                        leftIrisDiameterPx: face?.leftEye?.irisDiameterPx,
                        rightIrisX: rCenter?.x,
                        rightIrisY: rCenter?.y,
                        rightIrisDiameterPx: face?.rightEye?.irisDiameterPx,
                        stimulusIntensity: state?.stimulusIntensity ?? renderedIntensity,
                        stimulusCode: state?.stimulusCode ?? 0.0
                    )
                    videoRecorder.appendFrame(
                        pixelBuffer,
                        frameIndex: newIndex,
                        timeMs: nowMs,
                        leftCandidates: lCands,
                        rightCandidates: rCands,
                        telemetry: telemetry
                    )
                }

                if let completed = completed {
                    finishDatasetCapture(report: completed)
                } else if let state = state {
                    live = state
                    if state.remainingSec > 0 && state.remainingSec <= 3 && state.remainingSec != lastRemainingSec {
                        lastRemainingSec = state.remainingSec
                        feedback.play(.count)
                    }
                }
            }

        case .saving, .completed:
            break
        }
    }

    private func resetFeedbackTrackers() {
        lastAnnouncedStage = .idle
        lastWaitingConfirm = false
        lastRemainingSec = -1
        lastCoverHolding = false
        lastCoverStepIndex = -1
        lastWarningSpokenMs = 0
    }

    /// 统一驱动各阶段语音播报、倒计时音效、遮盖识别反馈与异常提醒（带防刷屏冷却）。
    private func announceTransition(_ state: LabLiveState, nowMs: Int64) {
        // 1) 进入新阶段准备确认页
        if state.stage != lastAnnouncedStage {
            lastAnnouncedStage = state.stage
            lastWaitingConfirm = state.stageWaitingConfirm
            lastRemainingSec = -1
            lastCoverHolding = false
            lastCoverStepIndex = -1
            if state.stageWaitingConfirm {
                // 切入新阶段准备页时先解锁 AE/AWB，让测光适应新姿态，点击开始后再锁定
                camera.setExposureLocked(false)
                feedback.play(.phase)
                feedback.speak("第 \(state.stageIndex) 阶段，\(state.stage.title)。\(state.stageBriefingBody)")
            }
            return
        }
        guard !state.stageWaitingConfirm else { return }

        // 2) 定时阶段最后 3、2、1 秒倒计时节拍
        if state.remainingSec > 0 && state.remainingSec <= 3 && state.remainingSec != lastRemainingSec {
            lastRemainingSec = state.remainingSec
            feedback.play(.count)
        }

        // 3) 动态交替遮盖动作识别确认与下一步指令播报
        if state.stage == .coverTest {
            if state.coverStepIndex != lastCoverStepIndex && !state.coverInstruction.isEmpty {
                lastCoverStepIndex = state.coverStepIndex
                lastCoverHolding = state.coverHolding
                feedback.play(.tick)
                feedback.speak(state.coverInstruction, interrupt: true)
            } else if state.coverHolding && !lastCoverHolding {
                lastCoverHolding = true
                feedback.play(.confirm)
                feedback.speak("已识别，请保持", interrupt: true)
            } else if !state.coverHolding && lastCoverHolding {
                lastCoverHolding = false
            }
        }

        // 4) 距离或头姿异常提醒（至少间隔 4.5 秒，避免吵闹）
        if let warn = state.warning, nowMs - lastWarningSpokenMs >= 4_500 {
            lastWarningSpokenMs = nowMs
            feedback.play(.warning)
            if warn.contains("距离") {
                let target: Float = state.stage == .pursuit ? 25 : 33
                let cue = state.distanceCm < target ? "距离偏近，请稍拿远一点" : "距离偏远，请稍拿近一点"
                feedback.speak(cue, interrupt: false)
            } else if warn.contains("头姿") {
                feedback.speak("请摆正头部，正对屏幕", interrupt: false)
            } else if warn.contains("人脸") {
                feedback.speak("未检测到人脸，请居中对准镜头", interrupt: false)
            }
        }
    }

    private func finishWithReport(_ report: LabSessionReport) {
        camera.setExposureLocked(false)
        restoreBrightness()
        feedback.play(.complete)
        feedback.speak("采集完成，正在生成科研报告和四联诊断视频。", interrupt: true)
        let dir = ensureSaved(report)
        activeReport = report
        analyzing = false
        destination = .dashboard

        if recordVideo {
            renderingVideo = true
            videoRenderProgress = 0.0
            videoRecorder.finishAndRenderAnnotated(
                report: report,
                directory: dir,
                onProgress: { [weak self] p in
                    Task { @MainActor in self?.videoRenderProgress = p }
                },
                completion: { [weak self] origURL, annURL in
                    Task { @MainActor in
                        self?.originalVideoURL = origURL
                        self?.annotatedVideoURL = annURL
                        self?.renderingVideo = false
                        self?.videoRenderProgress = 1.0
                        self?.feedback.success()
                        self?.refreshHistory()
                    }
                }
            )
        } else {
            refreshHistory()
        }
    }

    private func restoreBrightness() {
        UIApplication.shared.isIdleTimerDisabled = false
        if let b = savedBrightness {
            UIScreen.main.brightness = b
            savedBrightness = nil
        }
    }

    private func sessionsRoot() -> URL {
        let base = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let root = base.appendingPathComponent("StrabismusLab", isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        return root
    }

    @discardableResult
    private func ensureSaved(_ report: LabSessionReport) -> URL {
        let dir = sessionsRoot().appendingPathComponent(sanitize(report.sessionId), isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try? report.toJson(pretty: true).write(to: dir.appendingPathComponent("session_summary.json"), atomically: true, encoding: .utf8)
        try? report.toFramesJsonl().write(to: dir.appendingPathComponent("frames.jsonl"), atomically: true, encoding: .utf8)
        try? report.toDisplayEventsJsonl().write(to: dir.appendingPathComponent("display_events.jsonl"), atomically: true, encoding: .utf8)
        try? report.toFramesCsv().write(to: dir.appendingPathComponent("frames.csv"), atomically: true, encoding: .utf8)
        return dir
    }

    func refreshHistory() {
        let root = sessionsRoot()
        let dirs = (try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? []
        var items: [StoredSession] = []
        for dir in dirs {
            let file = dir.appendingPathComponent("session_summary.json")
            guard let text = try? String(contentsOf: file, encoding: .utf8) else { continue }
            let report = LabSessionReport.companion.fromJson(json: text)
            let hasAnn = FileManager.default.fileExists(atPath: dir.appendingPathComponent("annotated.mp4").path)
            let hasOrig = FileManager.default.fileExists(atPath: dir.appendingPathComponent("original.mp4").path)
            items.append(StoredSession(
                id: report.sessionId,
                directory: dir,
                report: report,
                hasAnnotatedVideo: hasAnn,
                hasOriginalVideo: hasOrig
            ))
        }
        history = items.sorted { $0.report.createdAtMs > $1.report.createdAtMs }
    }

    private func uptimeMs() -> Int64 { Int64(ProcessInfo.processInfo.systemUptime * 1000) }
    private func sanitize(_ id: String) -> String {
        id.replacingOccurrences(of: "[^a-zA-Z0-9._-]", with: "_", options: .regularExpression)
    }

    /// 读取真实硬件机型标识（如 iPhone16,2），便于多机型物理几何参数与误差分层溯源。
    private static func hardwareModelIdentifier() -> String {
        var sysInfo = utsname()
        uname(&sysInfo)
        let mirror = Mirror(reflecting: sysInfo.machine)
        let id = mirror.children.reduce(into: "") { acc, elem in
            guard let val = elem.value as? Int8, val != 0 else { return }
            acc.append(Character(UnicodeScalar(UInt8(val))))
        }
        return id.isEmpty ? UIDevice.current.model : id
    }
}

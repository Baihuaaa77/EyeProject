import AVFoundation
import EyeScreening
import Foundation
import Photos
import SwiftUI
import UIKit

enum LabDestination {
    case console, capture, dashboard, history
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

    let camera = StrabismusLabCamera()
    let feedback = StrabismusLabFeedback()
    private let videoRecorder = StrabismusLabVideoRecorder()
    private let engine: StrabismusLabEngine
    private var savedBrightness: CGFloat?
    private var activeSessionDir: URL?

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
        self.engine = StrabismusLabEngine(platformName: "iOS \(ver)", deviceModel: model)
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
        guard destination == .capture else { return }
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
            if destination == .capture {
                announceTransition(state, nowMs: nowMs)
            }
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

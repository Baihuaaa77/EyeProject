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
    case annotatedVideo, originalVideo, summaryJson, framesJsonl, framesCsv
    var id: String { rawValue }
    var label: String {
        switch self {
        case .annotatedVideo: return "叠加可视化诊断视频 (MP4)"
        case .originalVideo: return "原始高清录制视频 (MP4)"
        case .summaryJson: return "完整科研报告 (JSON)"
        case .framesJsonl: return "逐帧明细日志 (JSONL)"
        case .framesCsv: return "逐帧科研表格 (CSV)"
        }
    }
    var fileName: String {
        switch self {
        case .annotatedVideo: return "annotated.mp4"
        case .originalVideo: return "original.mp4"
        case .summaryJson: return "session_summary.json"
        case .framesJsonl: return "frames.jsonl"
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
        stimulusDiameterMm: 34.0,
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
        farWaitingConfirm: false, prompt: "保持正面平视，距离约 33 cm", warning: nil,
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
    private let videoRecorder = StrabismusLabVideoRecorder()
    private let engine: StrabismusLabEngine
    private var savedBrightness: CGFloat?
    private var activeSessionDir: URL?

    init() {
        let model = UIDevice.current.model
        let ver = UIDevice.current.systemVersion
        self.engine = StrabismusLabEngine(platformName: "iOS \(ver)", deviceModel: model)
        self.camera.onFrame = { [weak self] frame, intrinsics, nowMs, pixelBuffer in
            Task { @MainActor in
                self?.handleCameraFrame(frame, intrinsics: intrinsics, nowMs: nowMs, pixelBuffer: pixelBuffer)
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
        savedBrightness = UIScreen.main.brightness
        UIScreen.main.brightness = 1.0
        UIApplication.shared.isIdleTimerDisabled = true
        let sessionId = engine.startSession(newConfig: config, nowMs: uptimeMs())
        let dir = sessionsRoot().appendingPathComponent(sanitize(sessionId), isDirectory: true)
        activeSessionDir = dir
        annotatedVideoURL = nil
        originalVideoURL = nil
        photoSaveBanner = nil
        if recordVideo {
            videoRecorder.startSession(sessionId: sessionId, directory: dir)
        }
        live = engine.live.value as! LabLiveState
        destination = .capture
    }

    func onDisplayTick() {
        guard destination == .capture else { return }
        let pair = engine.recordDisplayTick(hostTimeMs: uptimeMs(), screenBrightness: Double(UIScreen.main.brightness))
        renderedIntensity = pair.first?.floatValue ?? 1.0
    }

    func confirmFarReady() {
        engine.confirmFarReady(nowMs: uptimeMs())
        live = engine.live.value as! LabLiveState
    }

    func manualAdvanceCover() {
        engine.manualAdvanceCover(nowMs: uptimeMs())
        live = engine.live.value as! LabLiveState
    }

    func skipStage() {
        engine.skipCurrentStage(nowMs: uptimeMs())
        live = engine.live.value as! LabLiveState
    }

    func finishEarlyAndAnalyze() {
        guard !analyzing else { return }
        analyzing = true
        let report = engine.stopAndBuildReport(nowMs: uptimeMs())
        finishWithReport(report)
    }

    func cancelCapture() {
        restoreBrightness()
        videoRecorder.cancelSession()
        engine.resetToIdle()
        live = engine.live.value as! LabLiveState
        destination = .console
    }

    func backToConsole() {
        restoreBrightness()
        engine.resetToIdle()
        live = engine.live.value as! LabLiveState
        destination = .console
    }

    func openHistory() {
        refreshHistory()
        destination = .history
    }

    func openReport(_ report: LabSessionReport) {
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
        PHPhotoLibrary.requestAuthorization(for: .addOnly) { [weak self] status in
            guard status == .authorized || status == .limited else {
                Task { @MainActor in self?.photoSaveBanner = "请在系统设置中允许写入相册权限" }
                return
            }
            PHPhotoLibrary.shared().performChanges({
                PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: url)
            }) { success, error in
                Task { @MainActor in
                    self?.photoSaveBanner = success ? "已保存诊断视频到系统相册" : "保存失败：\(error?.localizedDescription ?? "未知错误")"
                }
            }
        }
    }

    private func handleCameraFrame(
        _ frame: FaceLandmarkFrame,
        intrinsics: LabCameraIntrinsics,
        nowMs: Int64,
        pixelBuffer: CVPixelBuffer
    ) {
        let prevIndex = engine.lastLoggedFrameIndex
        let completed = engine.onFrameWithIntrinsics(frame: frame, intrinsics: intrinsics, nowMs: nowMs)
        let newIndex = engine.lastLoggedFrameIndex
        if recordVideo && destination == .capture && newIndex > prevIndex {
            videoRecorder.appendFrame(pixelBuffer, frameIndex: newIndex, timeMs: nowMs)
        }
        if let completed = completed {
            finishWithReport(completed)
        } else if let state = engine.live.value as? LabLiveState {
            live = state
        }
    }

    private func finishWithReport(_ report: LabSessionReport) {
        restoreBrightness()
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
}

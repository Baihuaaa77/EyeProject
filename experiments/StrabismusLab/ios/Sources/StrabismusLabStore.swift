import AVFoundation
import EyeScreening
import Foundation
import SwiftUI
import UIKit

enum LabDestination {
    case console, capture, dashboard, history
}

enum LabExportFormat: String, CaseIterable, Identifiable {
    case summaryJson, framesJsonl, framesCsv
    var id: String { rawValue }
    var label: String {
        switch self {
        case .summaryJson: return "完整科研报告 (JSON)"
        case .framesJsonl: return "逐帧明细日志 (JSONL)"
        case .framesCsv: return "逐帧科研表格 (CSV)"
        }
    }
    var fileName: String {
        switch self {
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
        includePursuit: true
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
        coverStepIndex: 0, coverStepTotal: 6, coverInstruction: "", coverHolding: false,
        capturedFrames: 0, validFrames: 0, searchRadiusFraction: 0.80
    )
    @Published var showPreviewOverlay = true
    @Published var cameraAuthorized = false
    @Published var cameraError: String?
    @Published var analyzing = false
    @Published var activeReport: LabSessionReport?
    @Published var history: [StoredSession] = []
    @Published var renderedIntensity: Float = 1.0

    let camera = StrabismusLabCamera()
    private let engine: StrabismusLabEngine
    private var savedBrightness: CGFloat?

    init() {
        let model = UIDevice.current.model
        let ver = UIDevice.current.systemVersion
        self.engine = StrabismusLabEngine(platformName: "iOS \(ver)", deviceModel: model)
        self.camera.onFrame = { [weak self] frame, focal, nowMs in
            Task { @MainActor in
                self?.handleCameraFrame(frame, focal: focal, nowMs: nowMs)
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
        _ = engine.startSession(newConfig: config, nowMs: uptimeMs())
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
        activeReport = report
        destination = .dashboard
    }

    func deleteSession(_ id: String) {
        let dir = sessionsRoot().appendingPathComponent(sanitize(id), isDirectory: true)
        try? FileManager.default.removeItem(at: dir)
        refreshHistory()
    }

    func exportURL(for report: LabSessionReport, format: LabExportFormat) -> URL {
        let dir = ensureSaved(report)
        return dir.appendingPathComponent(format.fileName)
    }

    private func handleCameraFrame(_ frame: FaceLandmarkFrame, focal: Float, nowMs: Int64) {
        if let completed = engine.onFrame(frame: frame, focalLengthPx: focal, nowMs: nowMs) {
            finishWithReport(completed)
        } else if let state = engine.live.value as? LabLiveState {
            live = state
        }
    }

    private func finishWithReport(_ report: LabSessionReport) {
        restoreBrightness()
        _ = ensureSaved(report)
        refreshHistory()
        activeReport = report
        analyzing = false
        destination = .dashboard
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
            items.append(StoredSession(id: report.sessionId, directory: dir, report: report))
        }
        history = items.sorted { $0.report.createdAtMs > $1.report.createdAtMs }
    }

    private func uptimeMs() -> Int64 { Int64(ProcessInfo.processInfo.systemUptime * 1000) }
    private func sanitize(_ id: String) -> String {
        id.replacingOccurrences(of: "[^a-zA-Z0-9._-]", with: "_", options: .regularExpression)
    }
}

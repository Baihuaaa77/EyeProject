import AVFoundation
import Combine
import SwiftUI
import UIKit

@main
struct GazeLabApp: App {
    var body: some Scene {
        WindowGroup { GazeLabScreen().preferredColorScheme(.dark) }
    }
}

private enum LabPalette {
    static let bgTop = Color(red: 11/255, green: 16/255, blue: 32/255)
    static let bgBottom = Color(red: 16/255, green: 21/255, blue: 34/255)
    static let card = Color(red: 22/255, green: 29/255, blue: 48/255).opacity(0.88)
    static let cardBorder = Color.white.opacity(0.09)
    static let accent = Color(red: 56/255, green: 189/255, blue: 248/255)
    static let emerald = Color(red: 52/255, green: 211/255, blue: 153/255)
    static let amber = Color(red: 251/255, green: 191/255, blue: 36/255)
    static let coral = Color(red: 251/255, green: 146/255, blue: 60/255)
    static let violet = Color(red: 192/255, green: 132/255, blue: 252/255)
    static let rose = Color(red: 251/255, green: 113/255, blue: 133/255)
}

@MainActor
private final class GazeLabModel: ObservableObject {
    @Published private(set) var identities: [GazeModelIdentity] = []
    @Published private(set) var modelIssues: [String] = []
    @Published private(set) var status = "正在准备前置相机"
    @Published private(set) var needsPermission = AVCaptureDevice.authorizationStatus(for: .video) != .authorized
    @Published private(set) var cameraFailed = false
    @Published private(set) var activeRun: GazeRun?
    @Published private(set) var targetIndex = 0
    @Published private(set) var points: [GazeMethod: GazePoint] = [:]
    @Published private(set) var latenciesMs: [GazeMethod: Double] = [:]
    @Published private(set) var report: GazeRun?
    @Published private(set) var archives: [URL] = GazeArchive.records()
    @Published private var calibrations: [String: GazeCalibration] = [:]
    @Published private var mgazeRidgeCalibration: MGazeRidgeCalibration?
    @Published private var webEyeTrackCalibration: WebEyeTrackPersonalization?
    @Published private(set) var enabledMethods = Set(GazeMethod.allCases)
    @Published private(set) var liveFps: Double = 0
    @Published private(set) var headAngles: [Double]?
    @Published private(set) var eyeDistancePx: Double?
    @Published private(set) var previewBoxes: [[Double]] = []
    @Published private(set) var eyesOpen = true
    @Published private(set) var poseDrifted = false
    @Published private(set) var targetProgress: Double = 0
    @Published private(set) var targetSampling = false
    @Published private(set) var currentTargetFrames = 0
    @Published var showCameraPreview = true
    @Published var useWebEyeTrackFilter = true

    private let camera = GazeCamera()
    private var webEyeTrackBaseHead: WebEyeTrackHead?
    private var webEyeTrackCalibrationIssue: String?
    private var activeSince: Double?
    private var calibrationIDs: [String: UUID] = [:]
    private var selectionRevision = 0
    private var webPreprocessorGeneration = 0
    private var webFilterGeneration = 0
    private var pendingRecord: GazeRun?
    private var lastFrame: GazeCameraFrame?
    private var recentFrameTimes: [Double] = []
    private var canvasSize = CGSize.zero
    private var scale = 1.0
    private var capturedTargets: [GazeTargetSamples] = []
    private var targetOnset: Double?
    private var drainingUntil: Double?
    private var baselineAngles: [Double]?
    private var baselineEyeDistance: Double?
    private var now: Double { ProcessInfo.processInfo.systemUptime }

    let settleSeconds = 0.65
    let sampleSeconds = 1.80
    private let drainSeconds = 0.35

    var captureSession: AVCaptureSession { camera.captureSession }
    private var enabledIdentities: [GazeModelIdentity] { identities.filter { enabledMethods.contains($0.method) } }
    private var hasEnabledCalibration: Bool { enabledMethods.contains { isCalibrated($0) } }
    var hasAnyCalibration: Bool { !calibrations.isEmpty || mgazeRidgeCalibration != nil || webEyeTrackCalibration != nil }
    var target: GazeTargetSamples? {
        guard let run = activeRun, targetIndex < run.targets.count else { return nil }
        return run.targets[targetIndex]
    }
    var canCalibrate: Bool { activeSince != nil && lastFrame?.faceBox != nil && !enabledIdentities.isEmpty && pendingRecord == nil }
    var canValidate: Bool { activeSince != nil && hasEnabledCalibration && pendingRecord == nil }
    var hasPendingRecord: Bool { pendingRecord != nil }
    var showsLivePoints: Bool { activeRun?.kind != .calibration }
    var canvasDiagonalPx: Double { hypot(Double(canvasSize.width) * scale, Double(canvasSize.height) * scale) }

    init() {
        camera.onReading = { [weak self] reading, time, revision in
            DispatchQueue.main.async { self?.receive(reading, time: time, revision: revision) }
        }
        camera.onFrame = { [weak self] frame, revision in
            DispatchQueue.main.async { self?.receive(frame, revision: revision) }
        }
        camera.onReady = { [weak self] identities, issues, webEyeTrackHead in
            DispatchQueue.main.async {
                guard let self, self.activeSince != nil else { return }
                self.identities = identities
                self.modelIssues = issues
                self.webEyeTrackBaseHead = webEyeTrackHead
                self.updateSelection()
                if identities.isEmpty { self.status = "没有可用模型" }
                else if self.enabledIdentities.isEmpty { self.status = "请至少启用一个模型" }
            }
        }
        camera.onError = { [weak self] message in
            DispatchQueue.main.async {
                guard let self, self.activeSince != nil else { return }
                self.pause("相机中断")
                self.cameraFailed = true
                self.status = message
            }
        }
    }

    private func updateSelection() {
        let methods = activeSince == nil ? [] : Set(enabledIdentities.map(\.method))
        let collecting = activeRun?.kind == .calibration
        selectionRevision = camera.select(
            methods,
            streamResults: showsLivePoints,
            mgazeCalibration: collecting ? nil : mgazeRidgeCalibration,
            webCalibration: collecting ? nil : webEyeTrackCalibration,
            webPreprocessorGeneration: webPreprocessorGeneration,
            webFilterGeneration: webFilterGeneration,
            collectSamples: collecting
        )
    }

    func isCalibrated(_ method: GazeMethod) -> Bool {
        switch method {
        case .webEyeTrack: webEyeTrackCalibration != nil
        case .mgaze: mgazeRidgeCalibration != nil || calibrations[method.rawValue] != nil
        default: calibrations[method.rawValue] != nil
        }
    }

    func calibrationModeLabel(_ method: GazeMethod) -> String {
        if method == .mgaze {
            return mgazeRidgeCalibration != nil ? "258D Ridge" : (calibrations[method.rawValue] != nil ? "2D 仿射" : "258D Ridge")
        }
        return method.calibrationTag
    }

    func clearCalibrations() {
        guard activeRun == nil else { return }
        calibrations = [:]
        mgazeRidgeCalibration = nil
        webEyeTrackCalibration = nil
        webEyeTrackCalibrationIssue = nil
        calibrationIDs = [:]
        report = nil
        points = [:]
        webPreprocessorGeneration += 1
        webFilterGeneration += 1
        updateSelection()
        status = "已清除标定，可重新开始"
    }

    func setEnabled(_ enabled: Bool, for method: GazeMethod) {
        guard activeRun == nil, enabledMethods.contains(method) != enabled else { return }
        if enabled {
            enabledMethods.insert(method)
        } else {
            enabledMethods.remove(method)
            points.removeValue(forKey: method)
            latenciesMs.removeValue(forKey: method)
        }
        updateSelection()
        if enabledIdentities.isEmpty {
            lastFrame = nil
            status = "请至少启用一个模型"
        } else if lastFrame == nil {
            status = "等待相机画面"
        }
    }

    func resumeIfAuthorized() {
        needsPermission = AVCaptureDevice.authorizationStatus(for: .video) != .authorized
        if !needsPermission { startCamera() }
    }

    func requestCamera() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: startCamera()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    if granted { self?.startCamera() }
                    else { self?.status = "请在系统设置中允许访问相机" }
                }
            }
        default:
            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
        }
    }

    private func startCamera() {
        guard activeSince == nil else { return }
        activeSince = now
        needsPermission = false
        cameraFailed = false
        status = "正在打开相机并加载模型"
        updateSelection()
        camera.start()
    }

    func pause(_ reason: String = "切到后台") {
        guard activeSince != nil else { return }
        activeSince = nil
        camera.stop()
        lastFrame = nil
        recentFrameTimes = []
        liveFps = 0
        points = [:]
        previewBoxes = []
        if activeRun != nil { finish(reason) }
        else { updateSelection() }
    }

    func resize(_ size: CGSize, scale: Double) {
        guard size != canvasSize || scale != self.scale else { return }
        if activeRun != nil { finish("画布尺寸变化，实验中断") }
        canvasSize = size
        self.scale = scale
        calibrations = [:]
        mgazeRidgeCalibration = nil
        webEyeTrackCalibration = nil
        webEyeTrackCalibrationIssue = nil
        calibrationIDs = [:]
        points = [:]
        updateSelection()
    }

    func start(_ kind: GazeRun.Kind) {
        guard activeRun == nil, canvasSize.width > 0, canvasSize.height > 0,
              kind == .calibration ? canCalibrate : canValidate else { return }
        // 标定 9 点覆盖更大有效屏区，验证 5 点覆盖四角边缘与偏心中心。
        let coordinates: [(Double, Double)] = kind == .calibration
            ? [(0.50, 0.51), (0.10, 0.16), (0.90, 0.86), (0.90, 0.16), (0.10, 0.86),
               (0.50, 0.16), (0.90, 0.51), (0.50, 0.86), (0.10, 0.51)]
            : [(0.20, 0.24), (0.80, 0.78), (0.80, 0.24), (0.20, 0.78), (0.45, 0.54)]
        capturedTargets = coordinates.enumerated().map {
            GazeTargetSamples(id: $0.offset, expected: GazePoint(x: $0.element.0, y: $0.element.1))
        }
        targetIndex = 0
        targetOnset = nil
        drainingUntil = nil
        targetProgress = 0
        targetSampling = false
        currentTargetFrames = 0
        points = [:]
        poseDrifted = false
        baselineAngles = lastFrame?.headAnglesDegrees
        baselineEyeDistance = lastFrame?.eyeDistancePx

        if kind == .calibration {
            // 新一轮标定重置 WebEyeTrack 缓存的人脸物理宽度与深度初值。
            webPreprocessorGeneration += 1
        }
        // 新一轮实验均重置 Kalman 滤波器。
        webFilterGeneration += 1

        var info = utsname()
        uname(&info)
        let device = withUnsafePointer(to: &info.machine) {
            $0.withMemoryRebound(to: CChar.self, capacity: 256) { String(cString: $0) }
        }
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
        let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? ""
        let methods = enabledIdentities
        let calibrated = kind == .validation ? Set(methods.map(\.method).filter { isCalibrated($0) }) : []
        let keys = Set(calibrated.map(\.rawValue))
        let maps = calibrations.filter { keys.contains($0.key) }
        let sources = calibrationIDs.filter { keys.contains($0.key) }
        let sourceIDs = Set(sources.values)
        activeRun = GazeRun(
            kind: kind, calibrationID: sourceIDs.count == 1 ? sourceIDs.first : nil,
            widthPx: Double(canvasSize.width) * scale, heightPx: Double(canvasSize.height) * scale,
            displayScale: scale, device: device, osVersion: UIDevice.current.systemVersion,
            appBuild: "\(version) (\(build))", models: methods,
            settleSeconds: settleSeconds, sampleSeconds: sampleSeconds, targets: capturedTargets,
            calibrations: maps, calibrationIDs: sources,
            mgazeRidgeCalibration: calibrated.contains(.mgaze) ? mgazeRidgeCalibration : nil,
            webEyeTrackCalibration: calibrated.contains(.webEyeTrack) ? webEyeTrackCalibration : nil
        )
        updateSelection()
        status = "保持头部稳定，仅转动眼球盯住白点"
    }

    func markShown(_ index: Int, runID: UUID) {
        guard activeRun?.id == runID, targetIndex == index, targetOnset == nil else { return }
        let time = now
        targetOnset = time
        capturedTargets[index].onset = time
        targetProgress = 0
        targetSampling = false
        currentTargetFrames = 0
    }

    func tick() {
        let current = now
        if let drain = drainingUntil {
            if current >= drain {
                drainingUntil = nil
                finish(nil)
            }
            return
        }
        if let onset = targetOnset, let run = activeRun {
            let elapsed = current - onset
            if elapsed < settleSeconds {
                targetSampling = false
                targetProgress = max(0, min(1, elapsed / settleSeconds))
            } else {
                targetSampling = true
                targetProgress = max(0, min(1, (elapsed - settleSeconds) / sampleSeconds))
            }
            if elapsed >= settleSeconds + sampleSeconds {
                if targetIndex + 1 == run.targets.count {
                    // 等待在途的最后一帧推理落地再收尾，防止末点丢尾帧。
                    targetOnset = nil
                    drainingUntil = current + drainSeconds
                    status = "正在收尾计算..."
                } else {
                    targetOnset = nil
                    targetIndex += 1
                    targetProgress = 0
                    targetSampling = false
                    currentTargetFrames = 0
                    if run.kind == .validation {
                        // 验证切点时立即重置 WebEyeTrack Kalman，消除低帧率跨点拖尾。
                        webFilterGeneration += 1
                        camera.bumpWebFilterGeneration(webFilterGeneration)
                    }
                }
            }
        }
        // 允许低帧率场景下单帧最长 1.5s 再判定断流，避免五模型串行时误触发清空。
        if let frame = lastFrame, current - frame.time > 1.5 {
            lastFrame = nil
            points = [:]
            previewBoxes = []
            liveFps = 0
            if activeRun == nil, activeSince != nil, pendingRecord == nil {
                status = currentWebEyeTrackCalibrationIssue ?? "等待相机画面"
            }
        }
    }

    private func isPoseDrifted(_ frame: GazeCameraFrame) -> Bool {
        if let baseAngles = baselineAngles, let angles = frame.headAnglesDegrees,
           baseAngles.count == 3, angles.count == 3 {
            let maxDiff = zip(baseAngles, angles).map { abs($0 - $1) }.max() ?? 0
            if maxDiff > 12 { return true }
        }
        if let baseEye = baselineEyeDistance, let eye = frame.eyeDistancePx, baseEye > 10 {
            if abs(eye - baseEye) / baseEye > 0.20 { return true }
        }
        return false
    }

    private func screenOutput(for reading: GazeReading) -> GazePoint? {
        switch reading.method {
        case .webEyeTrack:
            return useWebEyeTrackFilter ? reading.webEyeTrackPoint : reading.webEyeTrackUnfiltered
        case .mgaze:
            if let pt = reading.mgazeCalibratedPoint { return pt }
            return reading.raw.flatMap { calibrations[GazeMethod.mgaze.rawValue]?.apply($0) }
        default:
            return reading.raw.flatMap { calibrations[reading.method.rawValue]?.apply($0) }
        }
    }

    private func receive(_ reading: GazeReading, time: Double, revision: Int) {
        guard revision == selectionRevision, let started = activeSince, time >= started,
              showsLivePoints, pendingRecord == nil, enabledMethods.contains(reading.method) else { return }
        if reading.latencyMs > 0 { latenciesMs[reading.method] = reading.latencyMs }
        if let point = screenOutput(for: reading), point.finite {
            if points[reading.method] != point { points[reading.method] = point }
        }
    }

    private func receive(_ incoming: GazeCameraFrame, revision: Int) {
        guard revision == selectionRevision, let started = activeSince, incoming.time >= started else { return }
        var frame = incoming
        lastFrame = frame
        headAngles = frame.headAnglesDegrees
        eyeDistancePx = frame.eyeDistancePx
        previewBoxes = frame.previewBoxes ?? []
        eyesOpen = !frame.readings.contains { $0.issue == "闭眼或眼部遮挡" }

        recentFrameTimes.append(frame.time)
        recentFrameTimes.removeAll { frame.time - $0 > 2.0 }
        if recentFrameTimes.count >= 2, let first = recentFrameTimes.first, let last = recentFrameTimes.last, last > first {
            liveFps = Double(recentFrameTimes.count - 1) / (last - first)
        }
        for r in frame.readings where r.latencyMs > 0 {
            latenciesMs[r.method] = r.latencyMs
        }

        // 实时对比与五点验证均及时清除本帧失效模型的旧落点，不再粘屏。
        if showsLivePoints {
            for reading in frame.readings {
                let valid = screenOutput(for: reading)?.finite == true
                if !valid, points[reading.method] != nil {
                    points.removeValue(forKey: reading.method)
                }
            }
        }

        if let run = activeRun {
            if baselineAngles == nil { baselineAngles = frame.headAnglesDegrees }
            if baselineEyeDistance == nil { baselineEyeDistance = frame.eyeDistancePx }
            let drifted = isPoseDrifted(frame)
            frame.poseDrifted = drifted
            poseDrifted = drifted
            // 按帧采集时间戳匹配对应目标采样窗口，避免切点时丢失在途尾帧。
            for idx in capturedTargets.indices {
                guard let onset = capturedTargets[idx].onset else { continue }
                if frame.time >= onset + settleSeconds && frame.time < onset + settleSeconds + sampleSeconds {
                    capturedTargets[idx].frames.append(frame)
                    if idx == targetIndex { currentTargetFrames = capturedTargets[idx].frames.count }
                    break
                }
            }
            if run.kind == .calibration || run.kind == .validation {
                let hint = drifted ? "头部偏移稍大，请尽量保持姿势" : "保持头部稳定，仅转动眼球盯住白点"
                if drainingUntil == nil && status != hint { status = hint }
            }
            return
        }

        guard pendingRecord == nil else { return }
        let nextStatus: String
        if let issue = currentWebEyeTrackCalibrationIssue { nextStatus = issue }
        else if frame.faceBox == nil { nextStatus = "请让前置相机拍到完整人脸" }
        else if !eyesOpen { nextStatus = "请睁开双眼并避免反光遮挡" }
        else if !hasEnabledCalibration { nextStatus = "人脸就绪，点击「九点标定」开始" }
        else { nextStatus = points.isEmpty ? "等待有效落点" : "实时多模型追踪中" }
        if status != nextStatus { status = nextStatus }
    }

    func cancel() {
        drainingUntil = nil
        finish("用户取消")
    }

    private var currentWebEyeTrackCalibrationIssue: String? {
        enabledMethods.contains(.webEyeTrack) ? webEyeTrackCalibrationIssue : nil
    }

    private func finish(_ reason: String?) {
        guard var run = activeRun else { return }
        run.targets = capturedTargets
        run.finished = Date()
        capturedTargets = []
        targetOnset = nil
        drainingUntil = nil
        targetProgress = 0
        targetSampling = false
        currentTargetFrames = 0
        poseDrifted = false
        baselineAngles = nil
        baselineEyeDistance = nil
        activeRun = nil

        if run.kind == .calibration, reason == nil {
            // 1. MGazeNet 优先拟合原生 258 维 Multivariate Ridge；若不足则回退 2D 仿射。
            if run.models.contains(where: { $0.method == .mgaze }) {
                run.fitMGazeRidge()
                if let ridge = run.mgazeRidgeCalibration {
                    mgazeRidgeCalibration = ridge
                    calibrations.removeValue(forKey: GazeMethod.mgaze.rawValue)
                    calibrationIDs[GazeMethod.mgaze.rawValue] = run.id
                }
            }
            // 2. AFF-Net / iTracker / L2CS-Net（以及 MGazeNet 兜底）使用逐目标稳定帧中位数 + 低正则 0.002 二维仿射。
            for method in run.models.map(\.method) where method != .webEyeTrack {
                if method == .mgaze && run.mgazeRidgeCalibration != nil { continue }
                let pairs = run.targets.compactMap { target -> (input: GazePoint, target: GazePoint)? in
                    let stable = target.frames.filter { $0.poseDrifted != true }
                    let pool = stable.count >= 3 ? stable : target.frames
                    let values = pool.compactMap { $0.readings.first(where: { $0.method == method })?.raw }
                    guard values.count >= 3 else { return nil }
                    return (GazePoint.median(values), target.expected)
                }
                if let mapping = GazeCalibration.fit(pairs) { run.calibrations[method.rawValue] = mapping }
            }
            for (method, mapping) in run.calibrations {
                calibrations[method] = mapping
                calibrationIDs[method] = run.id
                if method == GazeMethod.mgaze.rawValue { mgazeRidgeCalibration = nil }
            }
            // 3. WebEyeTrack 少样本个性化
            if run.models.contains(where: { $0.method == .webEyeTrack }) {
                run.fitWebEyeTrack(baseHead: webEyeTrackBaseHead)
                webEyeTrackCalibrationIssue = run.webEyeTrackCalibrationIssue
                if let profile = run.webEyeTrackCalibration {
                    webEyeTrackCalibration = profile
                    calibrationIDs[GazeMethod.webEyeTrack.rawValue] = run.id
                }
            }
        }
        webFilterGeneration += 1
        updateSelection()
        run.status = reason ?? (run.kind == .calibration && run.calibratedMethodCount == 0 ? "有效标定样本不足" : "完成")
        run.summarize()
        if run.kind == .validation { report = run }
        pendingRecord = run
        savePending()
    }

    func savePending() {
        guard let run = pendingRecord else { return }
        do {
            _ = try GazeArchive.save(run)
            archives = GazeArchive.records()
            pendingRecord = nil
            if run.kind == .calibration {
                status = run.webEyeTrackCalibrationIssue ?? (run.calibratedMethodCount == 0
                    ? (run.status ?? "标定未完成") : "已标定 \(run.calibratedMethodCount) 个模型，可进行五点验证")
            } else { status = run.status == "完成" ? "五点验证完成，已保存记录" : (run.status ?? "已保存") }
        } catch {
            status = "保存失败：\(error.localizedDescription)"
        }
    }

    func deleteArchive(at offsets: IndexSet) {
        for idx in offsets where idx < archives.count {
            GazeArchive.delete(archives[idx])
        }
        archives = GazeArchive.records()
    }
}

private struct CameraPreviewLayerView: UIViewRepresentable {
    let session: AVCaptureSession

    final class PreviewHost: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }

    func makeUIView(context: Context) -> PreviewHost {
        let view = PreviewHost()
        view.backgroundColor = .black
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        if let conn = view.previewLayer.connection {
            if conn.isVideoOrientationSupported { conn.videoOrientation = .portrait }
            if conn.isVideoMirroringSupported {
                conn.automaticallyAdjustsVideoMirroring = false
                conn.isVideoMirrored = true
            }
        }
        return view
    }

    func updateUIView(_ uiView: PreviewHost, context: Context) {
        if uiView.previewLayer.session !== session { uiView.previewLayer.session = session }
    }
}

private struct GazeLabScreen: View {
    @StateObject private var model = GazeLabModel()
    @Environment(\.scenePhase) private var phase
    @Environment(\.displayScale) private var displayScale
    @State private var showingRecords = false
    private let clock = Timer.publish(every: 0.05, on: .main, in: .common).autoconnect()

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                LinearGradient(colors: [LabPalette.bgTop, LabPalette.bgBottom],
                               startPoint: .topLeading, endPoint: .bottomTrailing)
                    .ignoresSafeArea()

                if let run = model.activeRun, let target = model.target {
                    activeHeader(run).frame(maxHeight: .infinity, alignment: .top)
                    targetReticle(target: target, run: run)
                        .position(position(target.expected, geometry.size))
                        .accessibilityLabel("当前注视目标")
                        .task(id: "\(run.id)-\(target.id)") { model.markShown(target.id, runID: run.id) }
                } else {
                    controls
                }

                if model.showsLivePoints {
                    ForEach(GazeMethod.allCases, id: \.self) { method in
                        if let point = model.points[method], point.onScreen {
                            Image(systemName: symbol(method, filled: model.activeRun == nil))
                                .font(.system(size: 18, weight: .semibold))
                                .foregroundStyle(color(method))
                                .shadow(color: color(method).opacity(0.65), radius: 5)
                                .position(position(point, geometry.size))
                                .allowsHitTesting(false)
                                .accessibilityHidden(true)
                        }
                    }
                }
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
            .clipped()
            .onAppear { model.resize(geometry.size, scale: displayScale); model.resumeIfAuthorized() }
            .onChange(of: geometry.size) { newSize in model.resize(newSize, scale: displayScale) }
        }
        .foregroundStyle(.white)
        .tint(LabPalette.accent)
        .onReceive(clock) { _ in model.tick() }
        .onChange(of: phase) { newPhase in
            if newPhase == .background {
                model.pause()
                UIApplication.shared.isIdleTimerDisabled = false
            } else if newPhase == .active {
                model.resumeIfAuthorized()
                UIApplication.shared.isIdleTimerDisabled = true
            }
        }
        .onAppear { UIApplication.shared.isIdleTimerDisabled = true }
        .onDisappear { model.pause(); UIApplication.shared.isIdleTimerDisabled = false }
        .sheet(isPresented: $showingRecords) { RecordsSheet(model: model, dismiss: { showingRecords = false }) }
    }

    private func targetReticle(target: GazeTargetSamples, run: GazeRun) -> some View {
        let ringColor = model.targetSampling ? LabPalette.emerald : LabPalette.amber
        let outerSize: CGFloat = model.targetSampling ? 44 : (54 - 10 * model.targetProgress)
        return ZStack {
            Circle()
                .stroke(Color.white.opacity(0.18), lineWidth: 2.5)
                .frame(width: outerSize, height: outerSize)
            if model.targetSampling {
                Circle()
                    .trim(from: 0, to: model.targetProgress)
                    .stroke(ringColor, style: StrokeStyle(lineWidth: 3.5, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .frame(width: outerSize, height: outerSize)
            } else {
                Circle()
                    .stroke(ringColor.opacity(0.85), lineWidth: 2)
                    .frame(width: outerSize, height: outerSize)
            }
            Circle().fill(.white).frame(width: 12, height: 12)
            Circle().fill(ringColor).frame(width: 4, height: 4)
        }
        .animation(.linear(duration: 0.05), value: model.targetProgress)
    }

    private func activeHeader(_ run: GazeRun) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 8) {
                    Text("\(run.kind == .calibration ? "九点标定" : "五点验证") \(model.targetIndex + 1)/\(run.targets.count)")
                        .font(.headline)
                    Text(model.targetSampling ? "采集中 · \(model.currentTargetFrames)帧" : "准备对准")
                        .font(.caption.monospacedDigit())
                        .padding(.horizontal, 8).padding(.vertical, 3)
                        .background((model.targetSampling ? LabPalette.emerald : LabPalette.amber).opacity(0.22),
                                    in: Capsule())
                        .foregroundStyle(model.targetSampling ? LabPalette.emerald : LabPalette.amber)
                }
                Text(model.status)
                    .font(.caption)
                    .foregroundStyle(model.poseDrifted ? LabPalette.amber : Color.white.opacity(0.75))
            }
            Spacer()
            Button("结束并保存", action: model.cancel)
                .buttonStyle(.bordered)
                .tint(.white.opacity(0.85))
                .frame(minHeight: 40)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(Color.black.opacity(0.45), in: RoundedRectangle(cornerRadius: 14))
        .padding(.horizontal, 12)
        .padding(.top, 4)
    }

    private var controls: some View {
        VStack(spacing: 10) {
            // 顶部标题与工具栏
            HStack(spacing: 10) {
                HStack(spacing: 8) {
                    Image(systemName: "eye.circle.fill")
                        .font(.title2)
                        .foregroundStyle(LabPalette.accent)
                    VStack(alignment: .leading, spacing: 1) {
                        Text("GazeLab").font(.title3.bold())
                        Text(model.liveFps > 0 ? String(format: "%.1f FPS", model.liveFps) : "待机中")
                            .font(.caption2.monospacedDigit())
                            .foregroundStyle(model.liveFps > 0 ? LabPalette.emerald : .secondary)
                    }
                }
                Spacer()
                Button {
                    withAnimation(.easeInOut(duration: 0.2)) { model.showCameraPreview.toggle() }
                } label: {
                    Image(systemName: model.showCameraPreview ? "video.fill" : "video.slash")
                        .frame(width: 38, height: 38)
                        .background(LabPalette.card, in: Circle())
                        .overlay(Circle().stroke(LabPalette.cardBorder, lineWidth: 1))
                }
                .accessibilityLabel("切换相机预览")

                Button { showingRecords = true } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "chart.bar.doc.horizontal")
                        if !model.archives.isEmpty {
                            Text("\(model.archives.count)").font(.caption2.monospacedDigit())
                        }
                    }
                    .padding(.horizontal, 11).frame(height: 38)
                    .background(LabPalette.card, in: Capsule())
                    .overlay(Capsule().stroke(LabPalette.cardBorder, lineWidth: 1))
                }
                .accessibilityLabel("实验记录")
            }

            // 可折叠实时相机预览 + 姿态状态栏
            if model.showCameraPreview && !model.needsPermission && !model.cameraFailed {
                cameraTelemetryCard
            }

            // 模型卡片列表
            ScrollView(showsIndicators: false) {
                VStack(spacing: 8) {
                    ForEach(model.identities.map(\.method), id: \.self) { method in
                        modelRow(method)
                    }
                    if model.isCalibrated(.webEyeTrack) && model.enabledMethods.contains(.webEyeTrack) {
                        HStack {
                            Image(systemName: "waveform.path.ecg")
                                .foregroundStyle(LabPalette.violet)
                                .font(.caption)
                            Text("WebEyeTrack 实时点显示 Kalman 滤波")
                                .font(.caption)
                                .foregroundStyle(.white.opacity(0.8))
                            Spacer()
                            Toggle("", isOn: $model.useWebEyeTrackFilter)
                                .labelsHidden()
                                .scaleEffect(0.85)
                        }
                        .padding(.horizontal, 12).padding(.vertical, 6)
                        .background(LabPalette.card.opacity(0.7), in: RoundedRectangle(cornerRadius: 12))
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(LabPalette.cardBorder, lineWidth: 1))
                    }
                }
            }

            // 底部控制面板
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 6) {
                    Circle()
                        .fill(model.canCalibrate ? LabPalette.emerald : LabPalette.amber)
                        .frame(width: 7, height: 7)
                    Text(model.needsPermission ? "允许访问前置相机后开始标定" : model.status)
                        .font(.footnote)
                        .foregroundStyle(.white.opacity(0.92))
                        .lineLimit(2)
                }
                ForEach(model.modelIssues, id: \.self) { issue in
                    Text(issue).font(.caption2).foregroundStyle(LabPalette.rose)
                }
                if model.hasPendingRecord {
                    Button("重试保存记录", action: model.savePending)
                        .buttonStyle(.bordered).controlSize(.small)
                }
                if model.needsPermission || model.cameraFailed {
                    Button(model.needsPermission ? "开启相机权限" : "重试相机", action: model.requestCamera)
                        .buttonStyle(.borderedProminent).controlSize(.large).frame(maxWidth: .infinity)
                } else {
                    HStack(spacing: 8) {
                        Button {
                            model.start(.calibration)
                        } label: {
                            Label("九点标定", systemImage: "scope").frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(!model.canCalibrate)

                        Button {
                            model.start(.validation)
                        } label: {
                            Label("五点验证", systemImage: "checkmark.circle").frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                        .disabled(!model.canValidate)

                        if model.hasAnyCalibration {
                            Button(role: .destructive) {
                                model.clearCalibrations()
                            } label: {
                                Image(systemName: "arrow.counterclockwise")
                                    .frame(width: 40, height: 38)
                            }
                            .buttonStyle(.bordered)
                            .accessibilityLabel("清除标定")
                        }
                    }
                    .controlSize(.regular)
                }
            }
            .padding(12)
            .background(LabPalette.card, in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(LabPalette.cardBorder, lineWidth: 1))
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
    }

    private var cameraTelemetryCard: some View {
        HStack(spacing: 12) {
            ZStack {
                CameraPreviewLayerView(session: model.captureSession)
                // 在镜像预览上叠加人脸框（绿色）与双眼框（青色）
                GeometryReader { g in
                    ForEach(Array(model.previewBoxes.enumerated()), id: \.offset) { idx, box in
                        if box.count == 4 {
                            let mirroredX = 1.0 - (box[0] + box[2])
                            Rectangle()
                                .stroke(idx == 0 ? LabPalette.emerald : LabPalette.accent,
                                        lineWidth: idx == 0 ? 1.5 : 1.1)
                                .frame(width: box[2] * g.size.width, height: box[3] * g.size.height)
                                .position(x: (mirroredX + box[2] / 2) * g.size.width,
                                          y: (box[1] + box[3] / 2) * g.size.height)
                        }
                    }
                }
                if !model.eyesOpen && !model.previewBoxes.isEmpty {
                    Text("闭眼")
                        .font(.caption2.bold())
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(LabPalette.rose.opacity(0.85), in: Capsule())
                        .frame(maxHeight: .infinity, alignment: .bottom)
                        .padding(.bottom, 4)
                }
            }
            .frame(width: 72, height: 96)
            .clipShape(RoundedRectangle(cornerRadius: 10))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(LabPalette.cardBorder, lineWidth: 1))

            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text("头姿与采集状态").font(.caption.weight(.semibold)).foregroundStyle(.white.opacity(0.85))
                    Spacer()
                    Text(model.previewBoxes.isEmpty ? "未检出人脸" : (model.eyesOpen ? "双眼清晰" : "眼部遮挡"))
                        .font(.caption2)
                        .padding(.horizontal, 7).padding(.vertical, 2)
                        .background((model.previewBoxes.isEmpty ? LabPalette.amber : (model.eyesOpen ? LabPalette.emerald : LabPalette.rose)).opacity(0.2), in: Capsule())
                        .foregroundStyle(model.previewBoxes.isEmpty ? LabPalette.amber : (model.eyesOpen ? LabPalette.emerald : LabPalette.rose))
                }
                if let angles = model.headAngles, angles.count == 3 {
                    HStack(spacing: 10) {
                        telemetryPill("Yaw", String(format: "%+.0f°", angles[0]))
                        telemetryPill("Pitch", String(format: "%+.0f°", angles[1]))
                        telemetryPill("Roll", String(format: "%+.0f°", angles[2]))
                    }
                } else {
                    Text("正对前置相机，保持距离 30–50 cm")
                        .font(.caption2).foregroundStyle(.secondary)
                }
                HStack(spacing: 10) {
                    if let eyeDist = model.eyeDistancePx {
                        Text(String(format: "眼距 %.0f px", eyeDist))
                            .font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
                    }
                    Text(String(format: "画布对角 %.0f px", model.canvasDiagonalPx))
                        .font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
                }
            }
        }
        .padding(10)
        .background(LabPalette.card, in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(LabPalette.cardBorder, lineWidth: 1))
    }

    private func telemetryPill(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(label).font(.system(size: 9)).foregroundStyle(.secondary)
            Text(value).font(.caption.monospacedDigit().weight(.medium))
        }
        .padding(.horizontal, 7).padding(.vertical, 3)
        .background(Color.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 6))
    }

    private func modelRow(_ method: GazeMethod) -> some View {
        let enabled = model.enabledMethods.contains(method)
        let calibrated = model.isCalibrated(method)
        let metric = model.report?.metrics.first(where: { $0.method == method })
        return HStack(spacing: 10) {
            Image(systemName: symbol(method))
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(enabled ? color(method) : .gray)
                .frame(width: 24)

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(method.rawValue)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(enabled ? .white : .secondary)
                    Text(model.calibrationModeLabel(method))
                        .font(.system(size: 10, weight: .medium))
                        .padding(.horizontal, 5).padding(.vertical, 1.5)
                        .background(color(method).opacity(0.16), in: Capsule())
                        .foregroundStyle(color(method))
                }
                HStack(spacing: 8) {
                    if let median = metric?.medianErrorPx, let p90 = metric?.p90ErrorPx {
                        let pct = model.canvasDiagonalPx > 0 ? (median / model.canvasDiagonalPx * 100) : 0
                        Text(String(format: "未滤波 %.0f / %.0f px (%.1f%%)", median, p90, pct))
                            .font(.caption2.monospacedDigit())
                            .foregroundStyle(LabPalette.emerald)
                    } else {
                        Text(calibrated ? "已标定" : "未标定")
                            .font(.caption2)
                            .foregroundStyle(calibrated ? LabPalette.accent : .secondary)
                    }
                    if let ms = model.latenciesMs[method], enabled {
                        Text(String(format: "%.0fms", ms))
                            .font(.caption2.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                }
            }

            Spacer(minLength: 4)

            Toggle("", isOn: Binding(
                get: { enabled },
                set: { model.setEnabled($0, for: method) }
            ))
            .labelsHidden()
            .tint(color(method))
        }
        .padding(.horizontal, 12).padding(.vertical, 9)
        .background(LabPalette.card, in: RoundedRectangle(cornerRadius: 13))
        .overlay(RoundedRectangle(cornerRadius: 13).stroke(enabled ? color(method).opacity(0.28) : LabPalette.cardBorder, lineWidth: 1))
    }

    private func position(_ point: GazePoint, _ size: CGSize) -> CGPoint {
        CGPoint(x: point.x * size.width, y: point.y * size.height)
    }

    private func color(_ method: GazeMethod) -> Color {
        switch method {
        case .mgaze: LabPalette.coral
        case .aff: LabPalette.accent
        case .itracker: LabPalette.emerald
        case .l2cs: LabPalette.amber
        case .webEyeTrack: LabPalette.violet
        }
    }

    private func symbol(_ method: GazeMethod, filled: Bool = true) -> String {
        let shape: String = switch method {
        case .mgaze: "circle"
        case .aff: "diamond"
        case .itracker: "triangle"
        case .l2cs: "square"
        case .webEyeTrack: "star"
        }
        return filled ? shape + ".fill" : shape
    }
}

private struct RecordsSheet: View {
    @ObservedObject var model: GazeLabModel
    let dismiss: () -> Void

    var body: some View {
        NavigationStack {
            List {
                if model.archives.isEmpty {
                    Text("完成一轮标定或验证后，实验 JSON 会自动保存在此，并支持直接查看汇总指标或分享导出。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                ForEach(model.archives, id: \.self) { url in
                    NavigationLink {
                        RecordDetailView(url: url)
                    } label: {
                        let name = url.deletingPathExtension().lastPathComponent
                        let date = (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate)
                        HStack {
                            Image(systemName: name.hasPrefix("验证") ? "chart.bar.fill" : "scope")
                                .foregroundStyle(name.hasPrefix("验证") ? LabPalette.emerald : LabPalette.accent)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(name.hasPrefix("验证") ? "五点验证" : "九点标定").font(.subheadline.weight(.medium))
                                if let date {
                                    Text(date.formatted(date: .abbreviated, time: .standard))
                                        .font(.caption2).foregroundStyle(.secondary)
                                }
                            }
                            Spacer()
                            ShareLink(item: url) { Image(systemName: "square.and.arrow.up") }
                                .buttonStyle(.borderless)
                        }
                    }
                }
                .onDelete(perform: model.deleteArchive)
            }
            .navigationTitle("实验记录")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成", action: dismiss) } }
        }
        .preferredColorScheme(.dark)
    }
}

private struct RecordDetailView: View {
    let url: URL
    @State private var run: GazeRun?

    var body: some View {
        ScrollView {
            if let run {
                VStack(alignment: .leading, spacing: 12) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(run.kind == .validation ? "五点验证汇总" : "九点标定汇总").font(.headline)
                        Text("状态：\(run.status ?? "-") · 机型：\(run.device) · iOS \(run.osVersion)")
                            .font(.caption).foregroundStyle(.secondary)
                        Text(String(format: "画布：%.0f×%.0f px（对角 %.0f px） · 实测帧率：%.1f Hz",
                                    run.widthPx, run.heightPx, run.diagonalPx, run.actualFrameRateHz ?? 0))
                            .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                        Text(run.calibrationMethod)
                            .font(.caption2).foregroundStyle(.secondary)
                    }
                    .padding(12).frame(maxWidth: .infinity, alignment: .leading)
                    .background(LabPalette.card, in: RoundedRectangle(cornerRadius: 12))

                    ForEach(run.metrics) { m in
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text(m.method.rawValue).font(.subheadline.bold())
                                Spacer()
                                Text("有效 \(m.valid)/\(m.total) 帧 · \(m.validTargets)/\(run.targets.count) 点")
                                    .font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
                            }
                            if let med = m.medianErrorPx, let p90 = m.p90ErrorPx {
                                let pct = run.diagonalPx > 0 ? med / run.diagonalPx * 100 : 0
                                Text(String(format: "未滤波误差：中位 %.1f px (%.2f%%) · P90 %.1f px · 抖动 %.1f px",
                                            med, pct, p90, m.medianFixationJitterPx ?? 0))
                                    .font(.caption.monospacedDigit()).foregroundStyle(LabPalette.emerald)
                            }
                            if let fMed = m.filteredMedianErrorPx, let fP90 = m.filteredP90ErrorPx {
                                let fPct = run.diagonalPx > 0 ? fMed / run.diagonalPx * 100 : 0
                                Text(String(format: "滤波+限幅对照：中位 %.1f px (%.2f%%) · P90 %.1f px · 抖动 %.1f px",
                                            fMed, fPct, fP90, m.filteredFixationJitterPx ?? 0))
                                    .font(.caption.monospacedDigit()).foregroundStyle(LabPalette.violet)
                            }
                            HStack(spacing: 12) {
                                if let paired = m.pairedMedianErrorPx {
                                    Text(String(format: "成对中位 %.1f px (%d帧)", paired, m.pairedFrames))
                                }
                                if let ms = m.medianInferenceMs {
                                    Text(String(format: "耗时中位 %.1f ms (P90 %.1f ms)", ms, m.p90InferenceMs ?? 0))
                                }
                            }
                            .font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
                        }
                        .padding(12).frame(maxWidth: .infinity, alignment: .leading)
                        .background(LabPalette.card, in: RoundedRectangle(cornerRadius: 12))
                    }
                }
                .padding(14)
            } else {
                ProgressView("正在解析 JSON...").padding(40)
            }
        }
        .navigationTitle("记录详情")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                ShareLink(item: url) { Image(systemName: "square.and.arrow.up") }
            }
        }
        .task { run = GazeArchive.load(url) }
    }
}

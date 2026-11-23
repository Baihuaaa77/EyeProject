import AVFoundation
import Combine
import SwiftUI

@main
struct GazeLabApp: App {
    var body: some Scene {
        WindowGroup { GazeLabScreen().preferredColorScheme(.dark) }
    }
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
    @Published private(set) var report: GazeRun?
    @Published private(set) var archives: [URL] = GazeArchive.records()
    @Published private(set) var calibrations: [String: GazeCalibration] = [:]
    @Published var visible = Set(GazeMethod.allCases)
    @Published var showRaw = false

    private let camera = GazeCamera()
    private var activeSince: Double?
    private var calibrationID: UUID?
    private var pendingRecord: GazeRun?
    private var lastFrame: GazeCameraFrame?
    private var canvasSize = CGSize.zero
    private var scale = 1.0
    private var capturedTargets: [GazeTargetSamples] = []
    private var targetOnset: Double?
    private var now: Double { ProcessInfo.processInfo.systemUptime }

    let settleSeconds = 0.65
    let sampleSeconds = 1.8

    var target: GazeTargetSamples? { activeRun?.targets[targetIndex] }
    var canCalibrate: Bool { activeSince != nil && lastFrame?.faceBox != nil && !identities.isEmpty && pendingRecord == nil }
    var canValidate: Bool { activeSince != nil && !calibrations.isEmpty && pendingRecord == nil }
    var hasPendingRecord: Bool { pendingRecord != nil }

    init() {
        camera.onFrame = { [weak self] frame in
            DispatchQueue.main.async { self?.receive(frame) }
        }
        camera.onReady = { [weak self] identities, issues in
            DispatchQueue.main.async {
                guard let self, self.activeSince != nil else { return }
                self.identities = identities
                self.modelIssues = issues
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
                    else { self?.status = "请在设置中允许访问相机" }
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
        camera.start()
    }

    func pause(_ reason: String = "切到后台") {
        guard activeSince != nil else { return }
        activeSince = nil
        camera.stop()
        lastFrame = nil
        points = [:]
        if activeRun != nil { finish(reason) }
    }

    func resize(_ size: CGSize, scale: Double) {
        guard size != canvasSize || scale != self.scale else { return }
        if activeRun != nil { finish("画布尺寸变化，实验中断") }
        canvasSize = size
        self.scale = scale
        calibrations = [:]
        calibrationID = nil
        points = [:]
    }

    func start(_ kind: GazeRun.Kind) {
        guard activeRun == nil, canvasSize.width > 0, canvasSize.height > 0,
              kind == .calibration ? canCalibrate : canValidate else { return }
        let coordinates: [(Double, Double)] = kind == .calibration
            ? [(0.50, 0.50), (0.14, 0.20), (0.86, 0.80), (0.86, 0.20), (0.14, 0.80),
               (0.50, 0.20), (0.86, 0.50), (0.50, 0.80), (0.14, 0.50)]
            : [(0.28, 0.33), (0.72, 0.67), (0.72, 0.33), (0.28, 0.67), (0.43, 0.56)]
        capturedTargets = coordinates.enumerated().map {
            GazeTargetSamples(id: $0.offset, expected: GazePoint(x: $0.element.0, y: $0.element.1))
        }
        targetIndex = 0
        targetOnset = nil
        points = [:]
        var info = utsname()
        uname(&info)
        let device = withUnsafePointer(to: &info.machine) {
            $0.withMemoryRebound(to: CChar.self, capacity: 256) { String(cString: $0) }
        }
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
        let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? ""
        activeRun = GazeRun(kind: kind, calibrationID: kind == .validation ? calibrationID : nil,
                           widthPx: Double(canvasSize.width) * scale, heightPx: Double(canvasSize.height) * scale,
                           displayScale: scale, device: device, osVersion: UIDevice.current.systemVersion,
                           appBuild: "\(version) (\(build))", models: identities, expectedRateHz: camera.rate,
                           settleSeconds: settleSeconds, sampleSeconds: sampleSeconds, targets: capturedTargets,
                           calibrations: kind == .validation ? calibrations : [:])
        status = "盯住白点，直到它移动"
    }

    func markShown(_ index: Int, runID: UUID) {
        guard activeRun?.id == runID, targetIndex == index, targetOnset == nil else { return }
        let time = now
        targetOnset = time
        capturedTargets[index].onset = time
    }

    func tick() {
        if let onset = targetOnset, now >= onset + settleSeconds + sampleSeconds, let run = activeRun {
            if targetIndex + 1 == run.targets.count { finish(nil) }
            else {
                targetOnset = nil
                targetIndex += 1
            }
        }
        if let frame = lastFrame, now - frame.time > 0.9 {
            lastFrame = nil
            points = [:]
            if activeRun == nil, activeSince != nil { status = "等待相机画面" }
        }
    }

    private func receive(_ frame: GazeCameraFrame) {
        guard let started = activeSince, frame.time >= started else { return }
        lastFrame = frame
        if activeRun != nil {
            if let onset = targetOnset, frame.time >= onset + settleSeconds,
               frame.time < onset + settleSeconds + sampleSeconds {
                capturedTargets[targetIndex].frames.append(frame)
            }
            return
        }
        guard frame.faceBox != nil else { points = [:]; status = "请让前置相机拍到完整人脸"; return }
        if pendingRecord != nil { return }
        if identities.isEmpty { status = "没有可用模型"; return }
        if calibrations.isEmpty { status = "准备就绪，可以开始标定"; return }
        points = Dictionary(uniqueKeysWithValues: frame.readings.compactMap { reading in
            guard let raw = reading.raw, let map = calibrations[reading.method.rawValue] else { return nil }
            let point = map.apply(raw)
            return point.finite ? (reading.method, point) : nil
        })
        status = points.isEmpty ? "当前帧没有有效落点" : "实时对比"
    }

    func cancel() { finish("用户取消") }

    private func finish(_ reason: String?) {
        guard var run = activeRun else { return }
        run.targets = capturedTargets
        run.finished = Date()
        capturedTargets = []
        targetOnset = nil
        activeRun = nil

        if run.kind == .calibration, reason == nil {
            for method in run.models.map(\.method) {
                let pairs = run.targets.compactMap { target -> (input: GazePoint, target: GazePoint)? in
                    let values = target.frames.compactMap { $0.readings.first(where: { $0.method == method })?.raw }
                    guard values.count >= 4 else { return nil }
                    return (GazePoint.median(values), target.expected)
                }
                if let mapping = GazeCalibration.fit(pairs) { run.calibrations[method.rawValue] = mapping }
            }
            if !run.calibrations.isEmpty {
                calibrations = run.calibrations
                calibrationID = run.id
            }
        }
        run.status = reason ?? (run.kind == .calibration && run.calibrations.isEmpty ? "有效标定样本不足" : "完成")
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
                status = run.calibrations.isEmpty ? (run.status ?? "标定未完成") : "已标定 \(run.calibrations.count) 个模型"
            } else { status = run.status == "完成" ? "已保存验证结果" : (run.status ?? "已保存") }
        } catch {
            status = "保存失败：\(error.localizedDescription)"
        }
    }
}

private struct GazeLabScreen: View {
    @StateObject private var model = GazeLabModel()
    @Environment(\.scenePhase) private var phase
    @Environment(\.displayScale) private var displayScale
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var showingRecords = false
    private let clock = Timer.publish(every: 0.05, on: .main, in: .common).autoconnect()

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                if let run = model.activeRun, let target = model.target {
                    activeHeader(run).frame(maxHeight: .infinity, alignment: .top)
                    Circle().fill(LabStyle.text).frame(width: 17, height: 17)
                        .position(position(target.expected, geometry.size))
                        .accessibilityLabel("当前注视目标")
                        .task(id: "\(run.id)-\(target.id)") { model.markShown(target.id, runID: run.id) }
                } else {
                    controls
                    ForEach(GazeMethod.allCases, id: \.self) { method in
                        if let point = model.points[method], point.onScreen, model.visible.contains(method) {
                            Image(systemName: symbol(method)).font(LabStyle.heading)
                                .foregroundStyle(color(method))
                                .position(position(point, geometry.size))
                                .animation(model.showRaw || reduceMotion ? nil : .linear(duration: 1.0 / 6),
                                           value: point)
                                .allowsHitTesting(false)
                                .accessibilityHidden(true)
                        }
                    }
                }
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
            .clipped()
            .onAppear { model.resize(geometry.size, scale: displayScale); model.resumeIfAuthorized() }
            .onChange(of: geometry.size) { model.resize($0, scale: displayScale) }
        }
        .background(LabStyle.background.ignoresSafeArea())
        .foregroundStyle(LabStyle.text)
        .tint(LabStyle.primary)
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
        .sheet(isPresented: $showingRecords) { records }
    }

    private func activeHeader(_ run: GazeRun) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 3) {
                Text("\(run.kind == .calibration ? "标定" : "验证") \(model.targetIndex + 1)/\(run.targets.count)")
                    .font(LabStyle.heading)
                Text(model.status).font(LabStyle.detail)
            }
            Spacer()
            Button("结束并保存", action: model.cancel).buttonStyle(.bordered).frame(minHeight: 44)
        }
        .padding(.horizontal, 16)
    }

    private var controls: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("屏幕注视追踪").font(LabStyle.title).accessibilityAddTraits(.isHeader)
                Spacer()
                Button { showingRecords = true } label: { Image(systemName: "folder") }
                    .frame(minWidth: 44, minHeight: 44).accessibilityLabel("实验记录")
            }

            VStack(alignment: .leading, spacing: 0) {
                ForEach(model.identities.map(\.method), id: \.self) { method in
                    Button {
                        if model.visible.contains(method) { model.visible.remove(method) }
                        else { model.visible.insert(method) }
                    } label: {
                        HStack(spacing: 8) {
                            Image(systemName: symbol(method)).foregroundStyle(color(method))
                            Text(method.rawValue).font(LabStyle.subheading)
                            Spacer()
                            if let metric = model.report?.metrics.first(where: { $0.method == method }),
                               let median = metric.medianErrorPx, let p90 = metric.p90ErrorPx {
                                Text("\(Int(median.rounded())) / \(Int(p90.rounded())) px")
                                    .font(LabStyle.code)
                                    .accessibilityLabel("误差中位数 \(Int(median.rounded())) 像素，90 分位 \(Int(p90.rounded())) 像素")
                            } else if model.calibrations[method.rawValue] == nil {
                                Text("未标定").font(LabStyle.detail)
                            }
                            Image(systemName: model.visible.contains(method) ? "eye" : "eye.slash")
                                .accessibilityHidden(true)
                        }
                        .frame(minHeight: 44)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityHint(model.visible.contains(method) ? "隐藏此方法的落点" : "显示此方法的落点")
                }
            }
            Spacer(minLength: 0)
            VStack(alignment: .leading, spacing: 8) {
                Text(model.needsPermission ? "允许访问前置相机后开始标定" : model.status).font(LabStyle.body)
                ForEach(model.modelIssues, id: \.self) { Text($0).font(LabStyle.detail) }
                if model.hasPendingRecord { Button("重试保存", action: model.savePending).frame(minHeight: 44) }
                if model.needsPermission || model.cameraFailed {
                    Button(model.needsPermission ? "开启相机" : "重试相机", action: model.requestCamera)
                        .buttonStyle(.borderedProminent).controlSize(.large)
                } else {
                    HStack(spacing: 8) {
                        Button("九点标定") { model.start(.calibration) }.buttonStyle(.borderedProminent)
                            .disabled(!model.canCalibrate)
                        Button("五点验证") { model.start(.validation) }.buttonStyle(.bordered)
                            .disabled(!model.canValidate)
                        Spacer(minLength: 0)
                        Button(model.showRaw ? "原始点" : "插值") { model.showRaw.toggle() }
                            .buttonStyle(.bordered)
                            .accessibilityHint("切换实时落点的显示方式")
                    }
                    .controlSize(.large)
                }
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: LabStyle.radius))
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
    }

    private var records: some View {
        NavigationStack {
            List {
                if model.archives.isEmpty { Text("完成一轮标定或验证后，实验会自动保存。") }
                ForEach(model.archives, id: \.self) { url in
                    ShareLink(item: url) {
                        let name = url.deletingPathExtension().lastPathComponent
                        let date = (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate)
                        VStack(alignment: .leading) {
                            Text(String(name.prefix(2)))
                            if let date { Text(date.formatted(date: .abbreviated, time: .standard)).font(LabStyle.detail) }
                        }
                    }
                }
            }
            .navigationTitle("实验记录")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { showingRecords = false } } }
        }
    }

    private func position(_ point: GazePoint, _ size: CGSize) -> CGPoint {
        CGPoint(x: point.x * size.width, y: point.y * size.height)
    }

    private func color(_ method: GazeMethod) -> Color {
        switch method {
        case .mgaze: LabStyle.primary
        case .aff: LabStyle.landmark
        case .itracker: LabStyle.coded
        case .l2cs: LabStyle.baseline
        }
    }

    private func symbol(_ method: GazeMethod) -> String {
        switch method {
        case .mgaze: "circle.fill"
        case .aff: "diamond.fill"
        case .itracker: "triangle.fill"
        case .l2cs: "square.fill"
        }
    }
}
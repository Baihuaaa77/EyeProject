import AVFoundation
import Combine
import EyeScreening
import SwiftUI
import UIKit

@main
struct GazeLabApp: App {
    var body: some Scene {
        WindowGroup { GazeLabScreen().preferredColorScheme(.dark) }
    }
}

@MainActor
private final class GazeLabModel: ObservableObject {
    private struct Frame {
        let face: FaceMetrics?
        let width: Int32
        let height: Int32
        let time: Int64
    }

    @Published private(set) var state: ScreenGazeState
    @Published private(set) var needsPermission = AVCaptureDevice.authorizationStatus(for: .video) != .authorized
    private let engine = ScreenGazeSession()
    private let camera = EyeCamera()
    private var activeSince: Int64?
    private lazy var mailbox = FrameMailbox<Frame>(limit: 1) { [weak self] frames in
        guard let self, let frame = frames.last, let started = self.activeSince, frame.time >= started else { return }
        self.engine.consume(face: frame.face, sourceWidth: frame.width, sourceHeight: frame.height,
                            time: frame.time, now: self.now)
        self.publish()
    }

    init() {
        state = engine.state
        camera.measureOptics = false
        let frames = mailbox
        camera.onFrame = { face, width, height, _, time, _ in
            frames.post(Frame(face: face, width: width, height: height, time: time))
        }
        camera.onError = { [weak self] message in
            let occurred = Int64(ProcessInfo.processInfo.systemUptime * 1000)
            DispatchQueue.main.async {
                guard let self, let started = self.activeSince, occurred >= started else { return }
                self.pause()
                self.needsPermission = AVCaptureDevice.authorizationStatus(for: .video) != .authorized
                self.engine.cameraError(message: message)
                self.publish()
            }
        }
    }

    private var now: Int64 { Int64(ProcessInfo.processInfo.systemUptime * 1000) }

    func resumeIfAuthorized() {
        needsPermission = AVCaptureDevice.authorizationStatus(for: .video) != .authorized
        if !needsPermission { start() }
    }

    func requestCamera() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized, .notDetermined: start()
        default:
            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
        }
    }

    private func start() {
        guard activeSince == nil else { return }
        activeSince = now
        needsPermission = false
        updateOrientation()
        engine.beginCamera()
        camera.start()
        publish()
    }

    func pause() {
        guard activeSince != nil else { return }
        activeSince = nil
        mailbox.clear()
        camera.stop()
        engine.stopCamera()
        publish()
    }

    func resize(_ size: CGSize, scale: CGFloat) {
        updateOrientation()
        engine.setCanvasSize(newWidth: Int32((size.width * scale).rounded()),
                             newHeight: Int32((size.height * scale).rounded()))
        publish()
    }

    private func updateOrientation() {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        let current = scene?.interfaceOrientation
        let orientation: AVCaptureVideoOrientation
        switch current {
        case .landscapeLeft: orientation = .landscapeLeft
        case .landscapeRight: orientation = .landscapeRight
        default: orientation = .portrait
        }
        camera.setVideoOrientation(orientation)
    }

    func tick() {
        engine.tick(now: now)
        publish()
    }

    func startTargets(validation: Bool) {
        engine.startTargets(validation: validation)
        publish()
    }

    func markShown(_ target: ScreenGazeTarget) {
        engine.markTargetShown(session: target.session, index: target.index, now: now)
    }

    func cancel() {
        engine.cancelRun(message: nil)
        publish()
    }

    private func publish() {
        let next = engine.state
        if state !== next { state = next }
    }
}

private struct GazeLabScreen: View {
    @StateObject private var model = GazeLabModel()
    @Environment(\.scenePhase) private var phase
    @Environment(\.displayScale) private var displayScale
    private let clock = Timer.publish(every: 0.12, on: .main, in: .common).autoconnect()

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                if let target = model.state.target {
                    targetHeader(target).frame(maxHeight: .infinity, alignment: .top)
                    marker(target.point, size: geometry.size, isTarget: true)
                        .task(id: "\(target.session)-\(target.index)") { model.markShown(target) }
                } else {
                    trackingControls
                    if let point = model.state.point {
                        marker(point, size: geometry.size, isTarget: false)
                    }
                }
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
            .clipped()
            .onAppear {
                model.resize(geometry.size, scale: displayScale)
                model.resumeIfAuthorized()
            }
            .onChange(of: geometry.size) { model.resize($0, scale: displayScale) }
        }
        .background(LabStyle.background.ignoresSafeArea())
        .foregroundStyle(LabStyle.text)
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
        .onDisappear {
            model.pause()
            UIApplication.shared.isIdleTimerDisabled = false
        }
    }

    private func targetHeader(_ target: ScreenGazeTarget) -> some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                let title = target.validation ? "误差验证" : "标定"
                Text("\(title) \(target.index + 1)/\(target.total)").font(LabStyle.heading)
                Text(model.state.status).font(LabStyle.detail)
            }
            Spacer()
            Button("取消", action: model.cancel)
                .buttonStyle(.bordered)
                .frame(minHeight: 44)
        }
        .padding(.horizontal, 20)
    }

    private var trackingControls: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("屏幕注视追踪").font(LabStyle.title).accessibilityAddTraits(.isHeader)
            if model.state.profileCount > 0 {
                Text("本次已标定 \(model.state.profileCount) 种姿势").font(LabStyle.detail)
            }
            Spacer(minLength: 16)
            VStack(alignment: .leading, spacing: 12) {
                Text(model.needsPermission ? "允许使用前置相机后即可标定" : model.state.status)
                    .font(LabStyle.body)
                if let report = model.state.report { Text(report).font(LabStyle.detail) }
                if model.needsPermission || model.state.cameraError {
                    Button(model.needsPermission ? "开启相机" : "重试相机", action: model.requestCamera)
                        .buttonStyle(.borderedProminent).controlSize(.large)
                } else {
                    HStack(spacing: 8) {
                        Button(model.state.profileCount == 0 ? "开始标定" : "标定当前姿势") {
                            model.startTargets(validation: false)
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(!model.state.canCalibrate)
                        Button("验证误差") { model.startTargets(validation: true) }
                            .buttonStyle(.bordered)
                            .disabled(!model.state.canValidate)
                    }
                    .controlSize(.large)
                }
            }
            .padding(16)
            .frame(maxWidth: 500, alignment: .leading)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: LabStyle.radius))
            .frame(maxWidth: .infinity)
        }
        .tint(LabStyle.primary)
        .padding(20)
    }

    private func marker(_ point: ScreenGazePoint, size: CGSize, isTarget: Bool) -> some View {
        ZStack {
            Circle().strokeBorder(isTarget ? LabStyle.primary : LabStyle.text, lineWidth: 2)
                .frame(width: isTarget ? 29 : 21, height: isTarget ? 29 : 21)
            Circle().fill(LabStyle.primary)
                .frame(width: isTarget ? 15 : 8, height: isTarget ? 15 : 8)
        }
        .position(x: CGFloat(point.x) * size.width, y: CGFloat(point.y) * size.height)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

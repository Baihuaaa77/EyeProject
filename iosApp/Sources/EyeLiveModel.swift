import AVFoundation
import Foundation
import UIKit
import EyeScreening

/// 首页「实时眼部检测」：前置相机 → MediaPipe 关键点 → 共用 LiveEyeMonitor（眨眼判据与干眼测试同一份）。
/// 相机队列只把每帧几何喂给监视器；界面每 80 ms 取一次快照刷新，不逐帧重绘整块卡片。与安卓首页同一份逻辑。
///
/// 退出检测偶发整页卡死的修正：相机、检测器与预览层改为应用内常驻（[shared]），开关检测只在相机队列上启停会话。
/// 以前每次离开首页都会在主线程析构预览层与会话，析构要等相机队列上的 stopRunning，而 stopRunning 有时要等主线程，
/// 两边互等即卡死。现在预览层在会话从未运行时一次性挂好，此后主线程不再调用任何会话接口。
final class EyeLiveModel: ObservableObject {
    static let shared = EyeLiveModel()

    @Published private(set) var snapshot: LiveEyeMonitor.Snapshot?
    @Published private(set) var running = false
    @Published private(set) var error: String?
    /// 相机因发热降帧或可能暂停时的说明
    @Published private(set) var cameraNote: String?
    /// 用户在本次使用中开启了检测：默认关闭，打开后切换页面再回来仍保持开启，重启应用后恢复关闭
    @Published var wanted = false

    let camera = EyeCamera()
    /// 常驻的预览视图：离开首页只从界面摘下，不析构
    let previewView = LivePreviewView()
    private let monitor = LiveEyeMonitor(windowMs: 8_000)
    private var timer: Timer?

    private init() {
        // 只看关键点、眨眼与测距，不做瞳孔 / 角膜反光测量
        camera.measureOptics = false
        // 会话此时从未运行：挂预览层不会与相机队列争抢会话
        previewView.previewLayer.session = camera.captureSession
        previewView.previewLayer.videoGravity = .resizeAspectFill
        camera.onFrame = { [weak self] face, width, height, focal, time, _ in
            self?.monitor.update(face: face, imageWidth: width, imageHeight: height, timeMs: time, focalLengthPx: focal)
        }
        camera.onError = { [weak self] message in DispatchQueue.main.async { self?.error = message } }
        camera.onPressure = { [weak self] note in DispatchQueue.main.async { self?.cameraNote = note } }
    }

    func start() {
        guard !running else { return }
        error = nil
        monitor.reset()
        camera.start()
        running = true
        let tick = Timer(timeInterval: 0.08, repeats: true) { [weak self] _ in self?.publish() }
        timer = tick
        RunLoop.main.add(tick, forMode: .common)
        publish()
    }

    /// 停止采集（相机队列上异步停止，主线程不等待），本次的眨眼、距离与姿态统计记入主动护眼账本。
    func stop() {
        guard running else { return }
        camera.stop()
        timer?.invalidate(); timer = nil
        running = false
        if let session = monitor.session() { EyeCareStore.shared.addSession(session) }
    }

    private func publish() {
        snapshot = monitor.snapshot(nowMs: Int64(ProcessInfo.processInfo.systemUptime * 1000))
    }
}

/// 预览视图：图层即 AVCaptureVideoPreviewLayer（系统自动镜像，呈自拍视角）。
final class LivePreviewView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    override init(frame: CGRect) { super.init(frame: frame); backgroundColor = UIColor(EyeStyle.ink) }
    required init?(coder: NSCoder) { super.init(coder: coder) }
}

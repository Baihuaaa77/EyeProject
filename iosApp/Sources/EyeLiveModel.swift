import CoreVideo
import Foundation
import EyeScreening

/// 首页「实时眼部检测」：前置相机 → MediaPipe 关键点 → 共用 LiveEyeMonitor（眨眼判据与干眼测试同一份）。
/// 相机队列只把每帧几何喂给监视器；界面每 80 ms 取一次快照刷新，不逐帧重绘整块卡片。与安卓首页同一份逻辑。
final class EyeLiveModel: ObservableObject {
    @Published private(set) var snapshot: LiveEyeMonitor.Snapshot?
    @Published private(set) var running = false
    @Published private(set) var error: String?
    /// 相机因发热降帧或可能暂停时的说明
    @Published private(set) var cameraNote: String?

    let camera = EyeCamera()
    private let monitor = LiveEyeMonitor(windowMs: 8_000)
    private var timer: Timer?

    init() {
        // 只看关键点与眨眼，不做瞳孔 / 角膜反光测量
        camera.measureOptics = false
        camera.onAnalyzedFrame = { [weak self] buffer, face, time in
            self?.monitor.update(face: face, imageWidth: Int32(CVPixelBufferGetWidth(buffer)),
                                 imageHeight: Int32(CVPixelBufferGetHeight(buffer)), timeMs: time)
        }
        camera.onError = { [weak self] message in DispatchQueue.main.async { self?.error = message } }
        camera.onPressure = { [weak self] note in DispatchQueue.main.async { self?.cameraNote = note } }
    }

    deinit { timer?.invalidate() }

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

    func stop() {
        guard running else { return }
        camera.stop()
        timer?.invalidate(); timer = nil
        running = false
    }

    private func publish() {
        snapshot = monitor.snapshot(nowMs: Int64(ProcessInfo.processInfo.systemUptime * 1000))
    }
}

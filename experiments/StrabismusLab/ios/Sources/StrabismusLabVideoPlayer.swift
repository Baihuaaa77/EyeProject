import AVFoundation
import SwiftUI
import UIKit

/// 交互式科研视频审阅播放器：
/// - 支持 1×–6× 双指捏合无级缩放、放大后单指拖拽平移、双击切换 1×/3×；
/// - 全部控制按钮与进度条位于视频画面之外，绝不遮挡画面像素；
/// - 支持逐帧前后步进（-1帧 / +1帧）与慢放倍率（0.25× / 0.5× / 1×），便于逐帧审阅光码亮灭与去遮盖扫视。
struct LabZoomableVideoPlayer: View {
    let url: URL
    let aspectRatio: CGFloat

    @State private var player: AVPlayer
    @State private var zoom: CGFloat = 1.0
    @State private var currentTime: Double = 0.0
    @State private var duration: Double = 0.0
    @State private var isPlaying: Bool = false
    @State private var rate: Float = 1.0
    @State private var timeObserver: Any?

    init(url: URL, aspectRatio: CGFloat) {
        self.url = url
        self.aspectRatio = aspectRatio
        _player = State(initialValue: AVPlayer(url: url))
    }

    var body: some View {
        VStack(spacing: 10) {
            // 可缩放视频视口（顶部角标不遮挡内容，仅保留清晰边框）
            LabVideoZoomSurface(player: player, zoom: $zoom)
                .aspectRatio(aspectRatio, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(LabPalette.hairlineStrong, lineWidth: 1))

            // 精密进度条 + 时间与缩放倍率
            VStack(spacing: 4) {
                Slider(
                    value: Binding(
                        get: { min(currentTime, max(duration, 0.001)) },
                        set: { sec in
                            currentTime = sec
                            player.seek(to: CMTime(seconds: sec, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
                        }
                    ),
                    in: 0...max(duration, 0.001)
                )
                .tint(LabPalette.accent)

                HStack {
                    Text("\(fmtClock(currentTime)) / \(fmtClock(duration))")
                        .font(LabFont.microMono)
                        .foregroundColor(LabPalette.inkSecondary)
                    Spacer()
                    Text(String(format: "缩放 %.1f× · 双指捏合/双击", zoom))
                        .font(LabFont.microMono)
                        .foregroundColor(zoom > 1.05 ? LabPalette.accent : LabPalette.inkTertiary)
                }
            }

            // 播放、逐帧步进与慢放控制行
            HStack(spacing: 8) {
                controlBtn(isPlaying ? "pause.fill" : "play.fill", title: isPlaying ? "暂停" : "播放", active: isPlaying) {
                    togglePlay()
                }
                controlBtn("backward.frame.fill", title: "-1帧", active: false) {
                    stepFrame(-1)
                }
                controlBtn("forward.frame.fill", title: "+1帧", active: false) {
                    stepFrame(1)
                }
                Button {
                    cycleRate()
                } label: {
                    Text(rateLabel)
                        .font(LabFont.microMono)
                        .foregroundColor(rate < 0.99 ? LabPalette.rightEye : LabPalette.inkPrimary)
                        .padding(.horizontal, 10).padding(.vertical, 8)
                        .background(LabPalette.surfaceElevated)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                        .overlay(RoundedRectangle(cornerRadius: 8).stroke(rate < 0.99 ? LabPalette.rightEye : LabPalette.hairline, lineWidth: 1))
                }
            }

            // 缩放按钮行（单手也能精准放大看眼区）
            HStack(spacing: 8) {
                zoomBtn("缩小", disabled: zoom <= 1.01) { zoom = max(1.0, zoom / 1.5) }
                zoomBtn("放大", disabled: zoom >= 5.95) { zoom = min(6.0, zoom * 1.5) }
                zoomBtn("3× 眼区", disabled: false) { zoom = abs(zoom - 3.0) < 0.2 ? 1.0 : 3.0 }
                zoomBtn("复位 1×", disabled: zoom <= 1.01) { zoom = 1.0 }
            }
        }
        .onAppear { attachObserver() }
        .onDisappear { detachObserver() }
    }

    private var rateLabel: String {
        if rate <= 0.3 { return "0.25×" }
        if rate <= 0.6 { return "0.5×" }
        return "1.0×"
    }

    private func togglePlay() {
        if isPlaying {
            player.pause()
            isPlaying = false
        } else {
            if duration > 0 && currentTime >= duration - 0.08 {
                player.seek(to: .zero)
            }
            player.playImmediately(atRate: rate)
            isPlaying = true
        }
    }

    private func stepFrame(_ delta: Int) {
        player.pause()
        isPlaying = false
        let next = max(0.0, min(duration, currentTime + Double(delta) / 30.0))
        currentTime = next
        player.seek(to: CMTime(seconds: next, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
    }

    private func cycleRate() {
        if rate > 0.75 { rate = 0.5 }
        else if rate > 0.35 { rate = 0.25 }
        else { rate = 1.0 }
        if isPlaying { player.rate = rate }
    }

    private func attachObserver() {
        guard timeObserver == nil else { return }
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(value: 1, timescale: 15), queue: .main) { stamp in
            if stamp.seconds.isFinite { currentTime = max(0, stamp.seconds) }
            if let total = player.currentItem?.duration.seconds, total.isFinite, total > 0 {
                duration = total
            }
            isPlaying = player.rate > 0.01
        }
    }

    private func detachObserver() {
        player.pause()
        isPlaying = false
        if let obs = timeObserver {
            player.removeTimeObserver(obs)
            timeObserver = nil
        }
    }

    private func fmtClock(_ s: Double) -> String {
        let total = max(0, s)
        let m = Int(total) / 60
        let sec = total - Double(m * 60)
        return String(format: "%02d:%05.2f", m, sec)
    }

    private func controlBtn(_ icon: String, title: String, active: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 4) {
                Image(systemName: icon).font(.system(size: 12))
                Text(title).font(LabFont.caption)
            }
            .frame(maxWidth: .infinity, minHeight: 36)
            .background(active ? LabPalette.accentSoft : LabPalette.surfaceElevated)
            .foregroundColor(active ? LabPalette.accent : LabPalette.inkPrimary)
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(active ? LabPalette.accent : LabPalette.hairline, lineWidth: 1))
        }
    }

    private func zoomBtn(_ title: String, disabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(LabFont.microMono)
                .frame(maxWidth: .infinity, minHeight: 34)
                .background(LabPalette.surface)
                .foregroundColor(disabled ? LabPalette.inkTertiary : LabPalette.inkPrimary)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(LabPalette.hairline, lineWidth: 1))
        }
        .disabled(disabled)
    }
}

/// 基于 UIScrollView + AVPlayerLayer 的无失真 1×–6× 缩放/拖拽画布。
private struct LabVideoZoomSurface: UIViewRepresentable {
    let player: AVPlayer
    @Binding var zoom: CGFloat

    func makeUIView(context: Context) -> ZoomScrollView {
        let view = ZoomScrollView()
        view.canvas.playerLayer.player = player
        return view
    }

    func updateUIView(_ view: ZoomScrollView, context: Context) {
        if view.canvas.playerLayer.player !== player {
            view.canvas.playerLayer.player = player
        }
        let binding = $zoom
        view.onZoomChanged = { value in
            DispatchQueue.main.async {
                if abs(binding.wrappedValue - value) > 0.01 {
                    binding.wrappedValue = value
                }
            }
        }
        if !view.isZooming, abs(view.zoomScale - zoom) > 0.01 {
            view.setZoomScale(zoom, animated: true)
            view.panGestureRecognizer.isEnabled = zoom > 1.01
        }
    }

    final class PlayerCanvas: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }

    final class ZoomScrollView: UIScrollView, UIScrollViewDelegate {
        let canvas = PlayerCanvas()
        var onZoomChanged: ((CGFloat) -> Void)?
        private var lastSize = CGSize.zero

        init() {
            super.init(frame: .zero)
            delegate = self
            minimumZoomScale = 1.0
            maximumZoomScale = 6.0
            bouncesZoom = false
            showsHorizontalScrollIndicator = false
            showsVerticalScrollIndicator = false
            contentInsetAdjustmentBehavior = .never
            backgroundColor = .black
            canvas.playerLayer.videoGravity = .resizeAspect
            addSubview(canvas)
            // 未放大时不截获外层 ScrollView 的竖向滑动
            panGestureRecognizer.isEnabled = false

            let doubleTap = UITapGestureRecognizer(target: self, action: #selector(handleDoubleTap(_:)))
            doubleTap.numberOfTapsRequired = 2
            addGestureRecognizer(doubleTap)
        }

        required init?(coder: NSCoder) { fatalError("unused") }

        override func layoutSubviews() {
            super.layoutSubviews()
            guard lastSize != bounds.size, bounds.width > 0, bounds.height > 0 else { return }
            lastSize = bounds.size
            let prevZoom = zoomScale
            setZoomScale(1.0, animated: false)
            canvas.frame = CGRect(origin: .zero, size: lastSize)
            contentSize = lastSize
            setZoomScale(prevZoom, animated: false)
        }

        @objc private func handleDoubleTap(_ recognizer: UITapGestureRecognizer) {
            if zoomScale > 1.2 {
                setZoomScale(1.0, animated: true)
            } else {
                let pt = recognizer.location(in: canvas)
                let targetScale: CGFloat = 3.0
                let w = bounds.width / targetScale
                let h = bounds.height / targetScale
                let rect = CGRect(x: pt.x - w * 0.5, y: pt.y - h * 0.5, width: w, height: h)
                zoom(to: rect, animated: true)
            }
        }

        func viewForZooming(in scrollView: UIScrollView) -> UIView? { canvas }

        func scrollViewDidZoom(_ scrollView: UIScrollView) {
            panGestureRecognizer.isEnabled = zoomScale > 1.01
            onZoomChanged?(zoomScale)
        }
    }
}

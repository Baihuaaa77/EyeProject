import AVFoundation
import SwiftUI
import UIKit
import EyeScreening

/// 首页「实时眼部检测」视窗：镜像预览叠加双眼关键点（眼睑 6 点 + 虹膜圈），旁边是眨眼次数与当前状态，
/// 下方是最近 8 秒的 EAR 曲线，每次眨眼画一条绿线。一眼看出相机、关键点和眨眼检测都在正常工作。
/// 已授权相机时进入首页自动开启；离开首页、退到后台即关闭相机。与安卓 LiveEyeCard 同一排版。
struct EyeLiveCard: View {
    @StateObject private var model = EyeLiveModel()
    @Environment(\.scenePhase) private var scenePhase
    /// 用户希望开启：已授权时默认开启，点「暂停」后关闭，直到再次点「开始检测」
    @State private var wanted = AVCaptureDevice.authorizationStatus(for: .video) == .authorized

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text("实时眼部检测").font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text)
                Spacer()
                statusChip
            }
            if model.running { live } else { idle }
            if let message = model.error ?? model.cameraNote {
                Text(message).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.warning)
            }
        }.padding(16).frame(maxWidth: .infinity, alignment: .leading)
            .background(EyeStyle.panel, in: RoundedRectangle(cornerRadius: EyeStyle.radius))
            .onAppear { if wanted { model.start() } }
            .onDisappear { model.stop() }
            .onChange(of: scenePhase) { phase in
                if phase == .background { model.stop() } else if phase == .active, wanted { model.start() }
            }
    }

    private var idle: some View {
        VStack(spacing: 14) {
            HStack(spacing: 14) {
                Image(systemName: "eye").font(.system(size: 26, weight: .medium)).foregroundStyle(EyeStyle.primary)
                    .frame(width: 52, height: 52).background(EyeStyle.primarySoft, in: Circle())
                Text("打开前置摄像头，实时查看眼部关键点和眨眼检测").font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            Button("开始检测") { EyeHaptics.tap(); wanted = true; model.start() }.buttonStyle(EyeButtonStyle())
        }
    }

    private var live: some View {
        let s = model.snapshot
        let aspect: CGFloat = s.map { $0.imageWidth > 0 && $0.imageHeight > 0 ? CGFloat($0.imageWidth) / CGFloat($0.imageHeight) : 9.0 / 16 } ?? 9.0 / 16
        return VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 14) {
                ZStack {
                    LivePreview(session: model.camera.captureSession)
                    EyeLandmarkOverlay(snapshot: s)
                }.aspectRatio(aspect, contentMode: .fit).frame(width: 128)
                    .clipShape(RoundedRectangle(cornerRadius: EyeStyle.radiusMedium))
                VStack(alignment: .leading, spacing: 10) {
                    HStack(alignment: .firstTextBaseline, spacing: 4) {
                        Text("\(s?.blinkCount ?? 0)").font(.system(size: 44, weight: .semibold, design: .rounded))
                            .foregroundStyle(EyeStyle.text).monospacedDigit()
                        Text("次眨眼").font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
                    }
                    Text("近 1 分钟 \(s?.blinksLastMinute ?? 0) 次").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                    eyeState(s)
                    Text(String(format: "EAR %@ · 阈值 %.2f", s?.ear.map { String(format: "%.2f", $0.floatValue) } ?? "—", s?.threshold ?? 0))
                        .font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary).monospacedDigit()
                    Text(String(format: "%.0f 帧/秒 · %@", s?.fps ?? 0, model.camera.stats.backend))
                        .font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary).monospacedDigit()
                    Spacer(minLength: 0)
                    Button("暂停") { EyeHaptics.tap(); wanted = false; model.stop() }
                        .font(EyeStyle.button).foregroundStyle(EyeStyle.primary).frame(minHeight: 44)
                }.frame(maxWidth: .infinity, alignment: .leading)
            }
            EarTraceChart(snapshot: s).frame(height: 116)
            Text("画面只在本机实时处理，不保存、不上传").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
        }
    }

    private func eyeState(_ s: LiveEyeMonitor.Snapshot?) -> some View {
        let (text, color): (String, Color) = {
            guard let s = s, s.eyesFound, !s.stale else { return ("无读数", EyeStyle.secondary) }
            if !s.ready { return ("建立基线", EyeStyle.warning) }
            return s.closed ? ("闭眼", EyeStyle.danger) : ("睁眼", EyeStyle.success)
        }()
        return Text(text).font(EyeStyle.chip).foregroundStyle(EyeStyle.onStatus)
            .padding(.horizontal, 10).padding(.vertical, 4).background(color, in: Capsule())
    }

    private var statusChip: some View {
        let s = model.snapshot
        let (text, color): (String, Color) = {
            guard model.running else { return ("已暂停", EyeStyle.secondary) }
            guard let s = s, !s.waiting else { return ("启动中", EyeStyle.secondary) }
            if s.stale { return ("画面中断", EyeStyle.warning) }
            if !s.faceFound { return ("未检测到人脸", EyeStyle.warning) }
            if !s.eyesFound { return ("未检测到双眼", EyeStyle.warning) }
            return ("运行正常", EyeStyle.success)
        }()
        return HStack(spacing: 6) {
            Circle().fill(color).frame(width: 7, height: 7)
            Text(text).font(EyeStyle.chip)
        }.foregroundStyle(color).padding(.horizontal, 10).padding(.vertical, 5)
            .background(color.opacity(0.12), in: Capsule())
            .animation(.easeOut(duration: 0.2), value: text)
    }
}

/// 前置相机预览（系统自动镜像，呈自拍视角）。
private struct LivePreview: UIViewRepresentable {
    let session: AVCaptureSession
    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        view.backgroundColor = UIColor(EyeStyle.ink)
        return view
    }
    func updateUIView(_ uiView: PreviewView, context: Context) {}
    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }
}

/// 预览上叠加双眼关键点：眼睑 6 点连线 + 虹膜圈；闭眼时变红。预览与原图同宽高比，只需水平镜像。
private struct EyeLandmarkOverlay: View {
    let snapshot: LiveEyeMonitor.Snapshot?
    var body: some View {
        Canvas { context, size in
            guard let s = snapshot, !s.stale, s.imageWidth > 0, s.imageHeight > 0 else { return }
            let width = CGFloat(s.imageWidth), height = CGFloat(s.imageHeight)
            func toView(_ p: Point2f) -> CGPoint {
                CGPoint(x: (1 - CGFloat(p.x) / width) * size.width, y: CGFloat(p.y) / height * size.height)
            }
            let color = s.closed ? EyeStyle.danger : EyeStyle.brandStart
            for eye in [s.leftEye, s.rightEye].compactMap({ $0 }) {
                let points = eye.earPoints.map(toView)
                if points.count == 6 {
                    // 6 点顺序 p1 外/内眦 → 上睑 → 另一眦 → 下睑，连成闭合轮廓
                    var outline = Path()
                    outline.move(to: points[0])
                    for i in [1, 2, 3, 4, 5] { outline.addLine(to: points[i]) }
                    outline.closeSubpath()
                    context.stroke(outline, with: .color(color), lineWidth: 1.2)
                }
                for p in points { context.fill(Path(ellipseIn: CGRect(x: p.x - 1.8, y: p.y - 1.8, width: 3.6, height: 3.6)), with: .color(color)) }
                if let center = eye.irisPoints.first {
                    let c = toView(center)
                    let r = CGFloat(eye.irisDiameterPx) / 2 / width * size.width
                    context.stroke(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2)),
                                   with: .color(EyeStyle.accent), lineWidth: 1.2)
                }
            }
        }.allowsHitTesting(false).accessibilityHidden(true)
    }
}

/// 最近 8 秒 EAR 曲线：虚线为当帧自适应阈值，低于阈值的时段打底色，每次眨眼画一条绿线。
private struct EarTraceChart: View {
    let snapshot: LiveEyeMonitor.Snapshot?
    var body: some View {
        Canvas { context, size in
            let lane = CGRect(origin: .zero, size: size)
            context.fill(Path(roundedRect: lane, cornerRadius: 12), with: .color(EyeStyle.background))
            guard let s = snapshot, let now = s.samples.last?.timeMs else {
                context.draw(Text("等待相机画面…").font(EyeStyle.bodySecondary).foregroundColor(EyeStyle.secondary),
                             at: CGPoint(x: size.width / 2, y: size.height / 2))
                return
            }
            let window: CGFloat = 8_000, top: CGFloat = 0.45
            func x(_ t: Int64) -> CGFloat { size.width * (1 - CGFloat(now - t) / window) }
            func y(_ v: Float) -> CGFloat { size.height - 6 - min(max(CGFloat(v), 0), top) / top * (size.height - 12) }
            // 每秒一条竖格线
            var second = now - now % 1_000
            while CGFloat(now - second) < window {
                context.stroke(Path { p in p.move(to: CGPoint(x: x(second), y: 0)); p.addLine(to: CGPoint(x: x(second), y: size.height)) },
                               with: .color(EyeStyle.borderSubtle), lineWidth: 1)
                second -= 1_000
            }
            let samples = s.samples
            for (i, f) in samples.enumerated() where f.closed {
                let end = i + 1 < samples.count ? samples[i + 1].timeMs : f.timeMs + 33
                context.fill(Path(CGRect(x: x(f.timeMs), y: 0, width: max(x(end) - x(f.timeMs), 1), height: size.height)),
                             with: .color(EyeStyle.dangerSoft))
            }
            var threshold = Path(), line = Path(), open = false
            for (i, f) in samples.enumerated() {
                let tp = CGPoint(x: x(f.timeMs), y: y(f.threshold))
                if i == 0 { threshold.move(to: tp) } else { threshold.addLine(to: tp) }
                guard let v = f.ear?.floatValue else { open = false; continue }
                let point = CGPoint(x: x(f.timeMs), y: y(v))
                if open { line.addLine(to: point) } else { line.move(to: point); open = true }
            }
            context.stroke(threshold, with: .color(EyeStyle.secondary), style: StrokeStyle(lineWidth: 1, dash: [4, 3]))
            context.stroke(line, with: .color(EyeStyle.primary), lineWidth: 2)
            for t in s.blinkTimesMs {
                let ex = x(t.int64Value)
                context.stroke(Path { p in p.move(to: CGPoint(x: ex, y: 4)); p.addLine(to: CGPoint(x: ex, y: size.height - 4)) },
                               with: .color(EyeStyle.success), lineWidth: 2.5)
                context.fill(Path(ellipseIn: CGRect(x: ex - 4, y: 4, width: 8, height: 8)), with: .color(EyeStyle.success))
            }
            context.draw(Text("EAR · 近 8 秒").font(EyeStyle.chip).foregroundColor(EyeStyle.secondary),
                         at: CGPoint(x: 10, y: 8), anchor: .topLeading)
        }.accessibilityLabel("最近 8 秒睁眼程度曲线，绿线为判到的眨眼")
    }
}

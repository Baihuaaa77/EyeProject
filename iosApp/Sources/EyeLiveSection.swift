import AVFoundation
import SwiftUI
import UIKit
import EyeScreening

/// 首页最后一张卡「实时检测」：始终展开，整宽正方形取景框 + 「开始检测 / 停止检测」按钮。未检测时取景框是浅主色底 +
/// 人头形虚线框占位；检测中显示镜像预览（裁成正方形），下方是眨眼次数、近 1 分钟眨眼、观看距离与睁闭眼状态，
/// 「显示眼部关键点」开关（默认关闭、持久保存）决定是否叠加双眼关键点（眼睑 6 点 + 虹膜圈），只影响画面；
/// 再下方是最近 8 秒的 EAR 曲线，每次眨眼画一条绿线。
/// 离开首页、退到后台即关闭相机；关闭时本次的眨眼频率与观看距离记入主动护眼账本。与安卓 LiveEyeSection 同一排版。
/// [startRequests] 每增加一次（「为你推荐」里点了「去检测」）就打开一次。
struct LiveEyeSection: View {
    @ObservedObject private var model = EyeLiveModel.shared
    @Environment(\.scenePhase) private var scenePhase
    var startRequests = 0

    static let showLandmarks = "显示眼部关键点"
    static let help = "用前置摄像头实时检测双眼关键点：数眨眼次数、估计眼睛到屏幕的距离和头部姿态，结果计入「视觉行为」。「显示眼部关键点」只决定预览上是否画出眼睑轮廓和虹膜圈，不影响检测。画面只在本机实时处理，不保存、不上传；点「停止检测」或离开首页即停止相机。"

    var body: some View {
        Group {
            if model.running { expanded } else { idle }
        }
        .onAppear { if model.wanted { model.start() } }
        .onDisappear { model.stop() }
        .onChange(of: scenePhase) { phase in
            if phase == .background { model.stop() } else if phase == .active, model.wanted { model.start() }
        }
        .onChange(of: startRequests) { _ in model.wanted = true; model.start() }
    }

    /// 未开始：一行（青色圆形图标 + 名称与一句说明 +「开始」），开始后才展开取景框。与安卓 LiveEyeContent 一致。
    private var idle: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 14) {
                EyeGlyphView(glyph: .camera, color: EyeStyle.iceInk).frame(width: 22, height: 22)
                    .frame(width: 44, height: 44).background(EyeStyle.iceSoft, in: Circle())
                VStack(alignment: .leading, spacing: 0) {
                    HStack(spacing: 0) {
                        Text("实时检测").font(EyeStyle.body.weight(.semibold)).foregroundStyle(EyeStyle.text)
                        HelpButton(title: "实时检测", text: Self.help)
                    }
                    Text("眨眼与距离，仅在本机处理").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                }.frame(maxWidth: .infinity, alignment: .leading)
                PillButton(label: "开始") { model.wanted = true; model.start() }
            }
            if let message = model.error ?? model.cameraNote {
                Text(message).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.readableText(EyeStyle.warning))
            }
        }
    }

    private var expanded: some View {
        VStack(alignment: .leading, spacing: 12) {
            // 标题行：图标 + 标题 + 问号紧挨着，状态标签靠右
            HStack(spacing: 0) {
                EyeGlyphView(glyph: .camera, color: EyeStyle.iceInk).frame(width: 20, height: 20).padding(.trailing, 8)
                Text("实时检测").font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text)
                HelpButton(title: "实时检测", text: Self.help)
                Spacer(minLength: 8)
                if model.running { statusChip }
            }.frame(minHeight: 40)
            Viewfinder(running: model.running) {
                ZStack {
                    LivePreview(view: model.previewView)
                    if model.showLandmarks { EyeLandmarkOverlay(snapshot: model.snapshot) }
                }
            }
            if model.running { live }
            Button(model.running ? "停止检测" : "开始检测") {
                EyeHaptics.toggle()
                let on = !model.running
                model.wanted = on
                if on { model.start() } else { model.stop() }
            }.buttonStyle(EyeButtonStyle(role: model.running ? .secondary : .primary))
            if let message = model.error ?? model.cameraNote {
                Text(message).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.readableText(EyeStyle.warning))
            }
        }
    }

    private var live: some View {
        let s = model.snapshot
        return VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 0) {
                MetricView(label: "眨眼", value: "\(s?.blinkCount ?? 0)", unit: "次")
                VDivider()
                MetricView(label: "近 1 分钟", value: "\(s?.blinksLastMinute ?? 0)", unit: "次")
                VDivider()
                MetricView(label: "距离", value: s?.distanceCm.map { String(format: "%.0f", $0.floatValue) } ?? "—", unit: "厘米")
            }.fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 8) {
                eyeState(s)
                Text(String(format: "EAR %@ · %.0f 帧/秒", s?.ear.map { String(format: "%.2f", $0.floatValue) } ?? "—", s?.fps ?? 0))
                    .font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary).monospacedDigit()
            }
            Toggle(isOn: Binding(get: { model.showLandmarks }, set: { on in
                EyeHaptics.toggle()
                model.showLandmarks = on
            })) {
                Text(Self.showLandmarks).font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
            }.tint(EyeStyle.primary).frame(minHeight: 40)
            EarTraceChart(snapshot: s).frame(height: 116)
        }
    }

    private func eyeState(_ s: LiveEyeMonitor.Snapshot?) -> some View {
        let (text, color): (String, Color) = {
            guard let s = s, s.eyesFound, !s.stale else { return ("无读数", EyeStyle.secondary) }
            if !s.ready { return ("建立基线", EyeStyle.warning) }
            return s.closed ? ("闭眼", EyeStyle.danger) : ("睁眼", EyeStyle.success)
        }()
        return StatusChip(text: text, color: color)
    }

    private var statusChip: some View {
        let s = model.snapshot
        let (text, color): (String, Color) = {
            guard let s = s, !s.waiting else { return ("启动中", EyeStyle.secondary) }
            if s.stale { return ("画面中断", EyeStyle.warning) }
            if !s.faceFound { return ("未检测到人脸", EyeStyle.warning) }
            if !s.eyesFound { return ("未检测到双眼", EyeStyle.warning) }
            return ("运行正常", EyeStyle.success)
        }()
        return HStack(spacing: 6) {
            Circle().fill(color).frame(width: 7, height: 7)
            Text(text).font(EyeStyle.chip)
        }.foregroundStyle(EyeStyle.text).padding(.horizontal, 10).padding(.vertical, 5)
            .background(color.opacity(0.12), in: EyeStyle.chipShape)
            .animation(.easeOut(duration: 0.2), value: text)
    }
}

/// 正方形取景框：四角取景角标（检测中白色、未检测主色）。检测中铺满相机画面，未检测时浅主色底 + 人头肩形虚线框占位。与安卓 Viewfinder 一致。
private struct Viewfinder<Live: View>: View {
    let running: Bool
    @ViewBuilder let live: Live
    var body: some View {
        let shape = RoundedRectangle(cornerRadius: EyeStyle.radiusHero, style: .continuous)
        Color.clear.aspectRatio(1, contentMode: .fit).frame(maxWidth: .infinity)
            .overlay {
                if running { live } else {
                    Canvas { context, size in
                        let w = size.width, h = size.height
                        let dash = StrokeStyle(lineWidth: 2.5, dash: [10, 7])
                        let ink = EyeStyle.primary.opacity(0.55)
                        context.stroke(Path(ellipseIn: CGRect(x: w * 0.32, y: h * 0.2, width: w * 0.36, height: h * 0.44)), with: .color(ink), style: dash)
                        var shoulders = Path()
                        shoulders.move(to: CGPoint(x: w * 0.12, y: h))
                        shoulders.addCurve(to: CGPoint(x: w * 0.42, y: h * 0.7), control1: CGPoint(x: w * 0.12, y: h * 0.8), control2: CGPoint(x: w * 0.3, y: h * 0.72))
                        shoulders.move(to: CGPoint(x: w * 0.58, y: h * 0.7))
                        shoulders.addCurve(to: CGPoint(x: w * 0.88, y: h), control1: CGPoint(x: w * 0.7, y: h * 0.72), control2: CGPoint(x: w * 0.88, y: h * 0.8))
                        context.stroke(shoulders, with: .color(ink), style: dash)
                    }.accessibilityHidden(true)
                }
            }
            .overlay {
                Canvas { context, size in
                    let inset: CGFloat = 18, arm: CGFloat = 26
                    let color = running ? Color.white.opacity(0.9) : EyeStyle.primary
                    let corners: [(CGPoint, CGPoint)] = [
                        (CGPoint(x: inset, y: inset), CGPoint(x: 1, y: 1)),
                        (CGPoint(x: size.width - inset, y: inset), CGPoint(x: -1, y: 1)),
                        (CGPoint(x: inset, y: size.height - inset), CGPoint(x: 1, y: -1)),
                        (CGPoint(x: size.width - inset, y: size.height - inset), CGPoint(x: -1, y: -1)),
                    ]
                    for (o, d) in corners {
                        var corner = Path()
                        corner.move(to: CGPoint(x: o.x + d.x * arm, y: o.y)); corner.addLine(to: o); corner.addLine(to: CGPoint(x: o.x, y: o.y + d.y * arm))
                        context.stroke(corner, with: .color(color), style: StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round))
                    }
                }.allowsHitTesting(false).accessibilityHidden(true)
            }
            .background(running ? EyeStyle.ink : EyeStyle.primarySoft)
            .clipShape(shape)
    }
}

/// 前置相机预览：把常驻的预览视图（EyeLiveModel.previewView）放进容器。离开界面时只把它从容器里摘下，
/// 不析构预览层、不碰会话——主线程析构预览层要等相机队列上的 stopRunning，是退出检测偶发卡死的原因。
private struct LivePreview: UIViewRepresentable {
    let view: LivePreviewView
    func makeUIView(context: Context) -> UIView {
        let container = UIView()
        container.backgroundColor = UIColor(EyeStyle.ink)
        container.clipsToBounds = true
        attach(to: container)
        return container
    }
    func updateUIView(_ uiView: UIView, context: Context) {
        if view.superview !== uiView { attach(to: uiView) }
    }
    static func dismantleUIView(_ uiView: UIView, coordinator: ()) {
        // 预览视图可能已被新的容器接走，这里只摘下仍挂在本容器上的
        for sub in uiView.subviews { sub.removeFromSuperview() }
    }
    private func attach(to container: UIView) {
        view.removeFromSuperview()
        view.frame = container.bounds
        view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        container.addSubview(view)
    }
}

/// 预览上叠加双眼关键点：眼睑 6 点连线 + 虹膜圈；闭眼时变红。预览按 aspectFill 把原图裁进正方形，坐标同样居中放大后水平镜像。
private struct EyeLandmarkOverlay: View {
    let snapshot: LiveEyeMonitor.Snapshot?
    var body: some View {
        Canvas { context, size in
            guard let s = snapshot, !s.stale, s.imageWidth > 0, s.imageHeight > 0 else { return }
            let width = CGFloat(s.imageWidth), height = CGFloat(s.imageHeight)
            let scale = max(size.width / width, size.height / height)
            let shown = CGSize(width: width * scale, height: height * scale)
            let origin = CGPoint(x: (size.width - shown.width) / 2, y: (size.height - shown.height) / 2)
            func toView(_ p: Point2f) -> CGPoint {
                CGPoint(x: origin.x + (1 - CGFloat(p.x) / width) * shown.width, y: origin.y + CGFloat(p.y) / height * shown.height)
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
                    let r = CGFloat(eye.irisDiameterPx) / 2 * scale
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
            context.fill(Path(roundedRect: lane, cornerRadius: EyeStyle.radiusMedium), with: .color(EyeStyle.background))
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

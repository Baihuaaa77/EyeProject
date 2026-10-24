import SwiftUI

/// 测试前的方法演示：逐项移植安卓 TestGuide.kt（12 个场景、320×200 舞台单位、与动画同步高亮的分镜、暂停 / 继续）。
/// 只出现在准备阶段，不参与正式刺激呈现或计时；系统减弱动态效果时停在关键帧并展示全部步骤。
enum GuideMotion { case posture, acuity, fva, blink, open, near, move, curve, flip, fixate, cover, follow
    /// 与安卓 guideMotion(step) 相同的映射。
    static func forStep(_ step: String) -> GuideMotion {
        switch step {
        case "NEAR_VISION": .near
        case "PUSH_UP": .move
        case "DISTANCE_CURVE": .curve
        case "FLIPPER": .flip
        case "FIXATION", "GAZE_CALIBRATION": .fixate
        case "COVER": .cover
        case "PURSUIT": .follow
        default: .posture
        }
    }
}

struct TestGuideView: View {
    let motion: GuideMotion
    let title: String
    let cue: String
    let readyTitle: String
    let onReady: (() -> Void)?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var playing = true
    @State private var frozen: Double?
    @State private var start = Date()

    init(motion: GuideMotion, title: String, cue: String, readyTitle: String = "准备好了", onReady: (() -> Void)? = nil) {
        self.motion = motion; self.title = title; self.cue = cue; self.readyTitle = readyTitle; self.onReady = onReady
    }

    var body: some View {
        let scene = GuideScene.scene(motion)
        TimelineView(.animation(paused: reduceMotion || !playing)) { timeline in
            content(scene, phase: phase(scene, at: timeline.date))
        }
        .padding(16).frame(maxWidth: .infinity)
        .background(EyeStyle.panel, in: RoundedRectangle(cornerRadius: 22))
        .overlay { RoundedRectangle(cornerRadius: 22).stroke(EyeStyle.borderSubtle, lineWidth: 1) }
        .onChange(of: motion) { _ in start = Date(); frozen = nil; playing = true }
    }

    private func phase(_ scene: GuideScene, at date: Date) -> Double {
        if reduceMotion { return scene.keyframe }
        return playing ? progress(scene, at: date) : (frozen ?? scene.keyframe)
    }

    private func toggle(_ scene: GuideScene) {
        if playing { frozen = progress(scene, at: Date()) }
        else { start = Date().addingTimeInterval(-(frozen ?? 0) * scene.cycle) }
        playing.toggle()
    }

    private func content(_ scene: GuideScene, phase: Double) -> some View {
        let active = scene.beats.lastIndex(where: { phase >= $0.start }) ?? 0
        let chip: String = reduceMotion ? "示意" : (playing ? "暂停演示" : "继续演示")
        return VStack(spacing: 14) {
            HStack {
                Text(title).font(EyeStyle.title).foregroundStyle(EyeStyle.text).frame(maxWidth: .infinity, alignment: .leading)
                Button(chip) { toggle(scene) }
                    .font(EyeStyle.chip).foregroundStyle(EyeStyle.primary)
                    .padding(.horizontal, 12).padding(.vertical, 8).background(EyeStyle.primarySoft, in: Capsule())
                    .disabled(reduceMotion)
            }
            GuideStage(scene: scene, phase: phase).accessibilityLabel(cue)
            VStack(spacing: 6) {
                ForEach(Array(scene.beats.enumerated()), id: \.offset) { index, beat in
                    GuideBeatRow(label: beat.label, active: reduceMotion || index == active, done: !reduceMotion && index < active)
                }
            }
            Text(cue).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary).multilineTextAlignment(.center)
            if let onReady { Button(readyTitle, action: onReady).buttonStyle(EyeButtonStyle()) }
        }
    }

    private func progress(_ scene: GuideScene, at date: Date) -> Double {
        (date.timeIntervalSince(start) / scene.cycle).truncatingRemainder(dividingBy: 1)
    }
}

/// 演示舞台：浅主色圆角底，场景按 320×200 等比缩放居中。
private struct GuideStage: View {
    let scene: GuideScene
    let phase: Double
    var body: some View {
        Canvas { context, size in
            let unit = min(size.width / 320, size.height / 200)
            let origin = CGPoint(x: (size.width - 320 * unit) / 2, y: (size.height - 200 * unit) / 2)
            scene.draw(GuidePainter(ctx: context, unit: unit, origin: origin), phase)
        }.frame(height: 200).frame(maxWidth: .infinity)
            .background(EyeStyle.primarySoft, in: RoundedRectangle(cornerRadius: EyeStyle.radiusLarge))
    }
}

/// 由外部给进度的无文字演示舞台（服务页轮播示范用）。
struct GuideFrame: View {
    let motion: GuideMotion
    let phase: Double
    var body: some View { GuideStage(scene: GuideScene.scene(motion), phase: phase) }
}

/// 分镜行：当前步骤主色高亮，已完成步骤弱化。
private struct GuideBeatRow: View {
    let label: String
    let active: Bool
    let done: Bool
    var body: some View {
        Text(label).font(EyeStyle.body)
            .foregroundStyle(active ? EyeStyle.primary : EyeStyle.secondary.opacity(done ? 1 : 0.7))
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 12).padding(.vertical, 8)
            .background(active ? EyeStyle.primarySoft : EyeStyle.panel, in: RoundedRectangle(cornerRadius: 12))
            .animation(.easeInOut(duration: 0.25), value: active)
            .accessibilityValue(active ? "当前步骤" : "")
    }
}

/// 场景绘制上下文：舞台单位换算与常用画法。
struct GuidePainter {
    let ctx: GraphicsContext
    let unit: CGFloat
    let origin: CGPoint
    func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: origin.x + x * unit, y: origin.y + y * unit) }
    func u(_ v: CGFloat) -> CGFloat { v * unit }

    func label(_ text: String, _ x: CGFloat, _ y: CGFloat, _ color: Color, alpha: Double = 1) {
        var c = ctx; c.opacity = alpha
        c.draw(Text(text).font(EyeStyle.chip).foregroundColor(color), at: p(x, y), anchor: .center)
    }
    func line(_ a: CGPoint, _ b: CGPoint, _ color: Color, _ width: CGFloat, alpha: Double = 1, dash: [CGFloat] = [], dashPhase: CGFloat = 0) {
        var path = Path(); path.move(to: a); path.addLine(to: b)
        var c = ctx; c.opacity = alpha
        c.stroke(path, with: .color(color), style: StrokeStyle(lineWidth: width, lineCap: .round, dash: dash, dashPhase: dashPhase))
    }
    func circle(_ center: CGPoint, _ r: CGFloat, _ color: Color, alpha: Double = 1, stroke: CGFloat? = nil) {
        let path = Path(ellipseIn: CGRect(x: center.x - r, y: center.y - r, width: r * 2, height: r * 2))
        var c = ctx; c.opacity = alpha
        if let stroke { c.stroke(path, with: .color(color), lineWidth: stroke) } else { c.fill(path, with: .color(color)) }
    }
    func pill(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat, _ color: Color, alpha: Double = 1) {
        var c = ctx; c.opacity = alpha
        c.fill(Path(roundedRect: CGRect(origin: p(x, y), size: CGSize(width: u(w), height: u(h))), cornerRadius: u(h / 2)), with: .color(color))
    }
    /// 圆弧：从 12 点方向顺时针扫过 [fraction]。
    func arc(_ center: CGPoint, _ r: CGFloat, _ fraction: Double, _ color: Color, _ width: CGFloat) {
        guard fraction > 0 else { return }
        var path = Path()
        path.addArc(center: center, radius: r, startAngle: .degrees(-90), endAngle: .degrees(-90 + 360 * fraction), clockwise: false)
        ctx.stroke(path, with: .color(color), style: StrokeStyle(lineWidth: width, lineCap: .round))
    }
    func floorShadow(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat) {
        ctx.fill(Path(ellipseIn: CGRect(origin: p(x - w / 2, y - 6), size: CGSize(width: u(w), height: u(12)))), with: .color(EyeStyle.primary.opacity(0.12)))
    }
    /// 头部固定的四角取景框：表达「头保持不动」。
    func headLock(_ cx: CGFloat, _ cy: CGFloat, _ half: CGFloat) {
        for sx: CGFloat in [-1, 1] { for sy: CGFloat in [-1, 1] {
            let corner = p(cx + sx * half, cy + sy * half)
            line(corner, CGPoint(x: corner.x - sx * u(14), y: corner.y), EyeStyle.primary, u(3))
            line(corner, CGPoint(x: corner.x, y: corner.y - sy * u(14)), EyeStyle.primary, u(3))
        }}
    }
    func ruler(_ fromX: CGFloat, _ toX: CGFloat, _ y: CGFloat) {
        line(p(fromX, y), p(toX, y), EyeStyle.primary.opacity(0.5), u(2.5), dash: [u(6), u(6)])
    }
    func distanceBadge(_ x: CGFloat, _ cm: CGFloat) {
        pill(x - 30, 12, 60, 24, EyeStyle.panel)
        label("\(Int(cm)) cm", x, 24, EyeStyle.text)
    }
    func verdict(_ x: CGFloat, _ y: CGFloat, clear: Bool, alpha: Double) {
        guard alpha > 0 else { return }
        let tint = clear ? EyeStyle.success : EyeStyle.warning
        pill(x - 26, y - 12, 52, 24, tint.opacity(0.18), alpha: alpha)
        label(clear ? "清晰" : "模糊", x, y, tint, alpha: alpha)
    }
    /// 视标四周的箭头按键；[highlight] 为被点选的方向（−1 无），[tap] 0…1 为点按涟漪进度。
    func arrowPad(_ tl: CGPoint, _ screen: CGSize, highlight: Int, tap: Double) {
        let c = CGPoint(x: tl.x + screen.width / 2, y: tl.y + screen.height * 0.3)
        let d = screen.width * 0.35
        let dirs = [CGPoint(x: 1, y: 0), CGPoint(x: 0, y: 1), CGPoint(x: -1, y: 0), CGPoint(x: 0, y: -1)]
        for (i, v) in dirs.enumerated() {
            let at = CGPoint(x: c.x + v.x * d, y: c.y + v.y * d)
            let on = i == highlight
            circle(at, screen.width * 0.11, on ? EyeStyle.primary : EyeStyle.borderSubtle)
            if on && tap > 0 { circle(at, screen.width * (0.11 + 0.12 * tap), EyeStyle.primary, alpha: (1 - tap) * 0.4) }
            let ink = on ? Color.white : EyeStyle.ink
            let length = screen.width * 0.055
            let tip = CGPoint(x: at.x + v.x * length, y: at.y + v.y * length)
            let normal = CGPoint(x: -v.y * length, y: v.x * length)
            line(CGPoint(x: at.x - v.x * length, y: at.y - v.y * length), tip, ink, u(1.5))
            line(tip, CGPoint(x: at.x + normal.x, y: at.y + normal.y), ink, u(1.5))
            line(tip, CGPoint(x: at.x - normal.x, y: at.y - normal.y), ink, u(1.5))
        }
        ctx.fill(Path(roundedRect: CGRect(x: tl.x + screen.width * 0.12, y: tl.y + screen.height * 0.67,
            width: screen.width * 0.76, height: screen.height * 0.13), cornerRadius: screen.width * 0.06), with: .color(EyeStyle.borderSubtle))
    }
    func mascot(_ x: CGFloat, _ y: CGFloat, _ size: CGFloat, gaze: CGPoint = .zero, lid: CGFloat = 0, wide: CGFloat = 1) {
        Cartoon.mascot(ctx, center: p(x, y), size: u(size), gaze: gaze, lid: lid, wide: wide)
    }
    func phone(_ x: CGFloat, _ y: CGFloat, _ height: CGFloat) -> (CGPoint, CGSize) { Cartoon.phone(ctx, center: p(x, y), height: u(height)) }
    func optotype(_ center: CGPoint, _ edge: CGFloat, _ direction: Int, alpha: Double = 1) {
        Cartoon.tumblingE(ctx, color: EyeStyle.ink, center: center, edge: edge, direction: direction, alpha: alpha)
    }
}

// MARK: 时间工具（与安卓相同的曲线）

private func clamp01(_ t: Double) -> Double { min(1, max(0, t)) }
/// EyeMotion.Gentle：三次贝塞尔 (0.45, 0, 0.55, 1)。
private func ease(_ t: Double) -> Double {
    let x = clamp01(t)
    func bez(_ a: Double, _ b: Double, _ s: Double) -> Double { 3 * a * s * (1 - s) * (1 - s) + 3 * b * s * s * (1 - s) + s * s * s }
    var lo = 0.0, hi = 1.0
    for _ in 0..<24 { let mid = (lo + hi) / 2; if bez(0.45, 0.55, mid) < x { lo = mid } else { hi = mid } }
    return bez(0, 1, (lo + hi) / 2)
}
private func seg(_ p: Double, _ a: Double, _ b: Double) -> Double { ease((p - a) / (b - a)) }
private func lerp(_ a: Double, _ b: Double, _ t: Double) -> Double { a + (b - a) * t }
/// 三角脉冲：在 [at] 处达到 1，前后 [half] 内线性回落到 0。
private func tri(_ p: Double, _ at: Double, _ half: Double) -> Double { clamp01(1 - abs(p - at) / half) }
/// 关键帧插值：相邻关键帧之间柔和过渡，相同取值即为停留。
private func keyframes(_ p: Double, _ frames: [(Double, Double)]) -> Double {
    if p <= frames[0].0 { return frames[0].1 }
    for i in 1..<frames.count where p <= frames[i].0 {
        return lerp(frames[i - 1].1, frames[i].1, seg(p, frames[i - 1].0, frames[i].0))
    }
    return frames[frames.count - 1].1
}
/// 手机水平位置（舞台 x）换算为示意距离：280 → 40 cm，140 → 15 cm。
private func cmAt(_ x: CGFloat) -> CGFloat { 15 + (x - 140) / 140 * 25 }

// MARK: 场景

struct GuideScene {
    struct Beat { let start: Double; let label: String }
    let cycle: Double
    let beats: [Beat]
    let keyframe: Double
    let draw: (GuidePainter, Double) -> Void

    static func scene(_ motion: GuideMotion) -> GuideScene {
        switch motion {
        case .posture: posture
        case .acuity: acuity
        case .fva: fva
        case .blink: blink
        case .open: open
        case .near: near
        case .move: move
        case .curve: curve
        case .flip: flip
        case .fixate: fixate
        case .cover: cover
        case .follow: follow
        }
    }

    /// 准备姿势：手机在小瞳面前前后微调到合适距离后出现对勾；左上角灯光示意光线均匀。
    static let posture = GuideScene(cycle: 5.2, beats: [.init(start: 0, label: "① 正对屏幕，露出双眼"),
        .init(start: 0.25, label: "② 前后微调到提示的距离"), .init(start: 0.6, label: "③ 光线均匀，避免逆光")], keyframe: 0.7) { g, phase in
        g.floorShadow(82, 182, 100)
        g.mascot(82, 112, 100, gaze: CGPoint(x: 0.9, y: 0))
        let x = CGFloat(keyframes(phase, [(0, 290), (0.15, 290), (0.3, 215), (0.4, 250), (0.5, 238), (1, 238)]))
        let (tl, screen) = g.phone(x, 104, 128)
        let ok = seg(phase, 0.5, 0.56)
        let c = CGPoint(x: tl.x + screen.width / 2, y: tl.y + screen.height / 2)
        let r = screen.width * 0.32
        g.circle(c, r, EyeStyle.borderSubtle, alpha: 1 - ok, stroke: g.u(3))
        g.circle(c, r, EyeStyle.success, alpha: ok, stroke: g.u(3))
        if ok > 0 {
            g.line(CGPoint(x: c.x - r * 0.45, y: c.y), CGPoint(x: c.x - r * 0.1, y: c.y + r * 0.35), EyeStyle.success, g.u(3.5), alpha: ok)
            g.line(CGPoint(x: c.x - r * 0.1, y: c.y + r * 0.35), CGPoint(x: c.x + r * 0.5, y: c.y - r * 0.35), EyeStyle.success, g.u(3.5), alpha: ok)
        }
        g.ruler(140, x - 24, 186)
        let sun = g.p(160, 34)
        let bright = 0.6 + 0.4 * seg(phase, 0.6, 0.7)
        for i in 0..<8 {
            let a = (Double(i) / 8 + phase * 0.25) * 2 * .pi
            g.line(CGPoint(x: sun.x + cos(a) * g.u(14), y: sun.y + sin(a) * g.u(14)),
                   CGPoint(x: sun.x + cos(a) * g.u(20), y: sun.y + sin(a) * g.u(20)), EyeStyle.accent, g.u(3), alpha: bright)
        }
        g.circle(sun, g.u(9), EyeStyle.accent, alpha: bright)
    }

    static let blinkTimes = [0.2, 0.5, 0.8]

    static let blink = GuideScene(cycle: 4.2, beats: [.init(start: 0, label: "① 正对屏幕，保持放松"),
        .init(start: 0.15, label: "② 像平时一样自然眨眼"), .init(start: 0.55, label: "③ 应用自动记录眨眼")], keyframe: 0.6) { g, phase in
        g.floorShadow(160, 178, 120)
        let lid = GuideScene.blinkTimes.map { tri(phase, $0, 0.035) }.max() ?? 0
        g.mascot(160, 108, 118, lid: CGFloat(lid))
        for t in GuideScene.blinkTimes {
            let k = (phase - t) / 0.22
            if (0...1).contains(k) { g.label("+1", 232, 72 - 30 * CGFloat(k), EyeStyle.primary, alpha: 1 - k) }
        }
        let count = GuideScene.blinkTimes.filter { phase > $0 }.count
        g.pill(222, 150, 80, 30, EyeStyle.panel)
        g.label("眨眼 ×\(count)", 262, 165, EyeStyle.text)
    }

    static let open = GuideScene(cycle: 4.8, beats: [.init(start: 0, label: "① 轻松睁开双眼"),
        .init(start: 0.15, label: "② 尽量保持睁眼，看着屏幕"), .init(start: 0.86, label: "③ 不舒服就眨眼，随时可停止")], keyframe: 0.5) { g, phase in
        g.floorShadow(130, 178, 120)
        let rest = tri(phase, 0.92, 0.04)
        let wide = 1 + 0.22 * seg(phase, 0.02, 0.15) * (1 - seg(phase, 0.86, 0.9))
        g.mascot(130, 108, 118, lid: CGFloat(rest), wide: CGFloat(wide))
        if (0.15...0.86).contains(phase) {
            let twinkle = (sin(phase * 40) + 1) / 2
            for side: CGFloat in [-1, 1] {
                let eye = Cartoon.mascotEyeCenter(g.p(130, 108), g.u(118), side)
                let c = CGPoint(x: eye.x + side * g.u(26), y: eye.y - g.u(22))
                let r = g.u(3 + 3 * CGFloat(twinkle))
                g.line(CGPoint(x: c.x - r, y: c.y), CGPoint(x: c.x + r, y: c.y), EyeStyle.accent, g.u(2.5))
                g.line(CGPoint(x: c.x, y: c.y - r), CGPoint(x: c.x, y: c.y + r), EyeStyle.accent, g.u(2.5))
            }
        }
        let center = g.p(255, 92), r = g.u(34)
        let progress = seg(phase, 0.1, 0.86)
        g.circle(center, r + g.u(6), EyeStyle.panel)
        g.circle(center, r, EyeStyle.borderSubtle, stroke: g.u(6))
        g.arc(center, r, progress, EyeStyle.primary, g.u(6))
        g.label("\(max(0, 20 - Int(progress * 20)))s", 255, 92, EyeStyle.text)
    }

    static let nearDirections = [0, 3, 2, 1]

    static let near = GuideScene(cycle: 5.6, beats: [.init(start: 0, label: "① 用手掌遮住左眼（不压眼球）"),
        .init(start: 0.2, label: "② 用右眼看 E 的开口方向"), .init(start: 0.28, label: "③ 点选对应方向；之后换眼再做")], keyframe: 0.35) { g, phase in
        g.floorShadow(95, 182, 110)
        let mascot = g.p(95, 110)
        g.mascot(95, 110, 108, gaze: CGPoint(x: 0.8, y: 0))
        // 手掌从下方抬起，遮住小瞳的左眼（画面右侧那只）
        let eye = Cartoon.mascotEyeCenter(mascot, g.u(108), 1)
        let rise = CGFloat(seg(phase, 0.02, 0.16))
        let hand = CGPoint(x: eye.x + g.u(2), y: eye.y + g.u(90) + (g.u(3) - g.u(90)) * rise)
        Cartoon.mitten(g.ctx, center: hand, size: g.u(46), angle: -12, armFrom: CGPoint(x: hand.x + g.u(18), y: g.p(0, 210).y))
        let (tl, screen) = g.phone(242, 100, 160)
        let segment = min(3, max(0, Int((phase - 0.2) / 0.2)))
        let dir = GuideScene.nearDirections[segment]
        g.optotype(CGPoint(x: tl.x + screen.width / 2, y: tl.y + screen.height * 0.3), screen.width * 0.36, dir)
        let tap = phase >= 0.2 ? clamp01((phase - 0.2 - Double(segment) * 0.2 - 0.08) / 0.1) : 0
        g.arrowPad(tl, screen, highlight: phase >= 0.28 ? dir : -1, tap: tap)
    }

    static let move = GuideScene(cycle: 7.6, beats: [.init(start: 0, label: "① 从 45 cm 外开始，看着 E"),
        .init(start: 0.15, label: "② 慢慢移近（每秒不超过 5 cm）"), .init(start: 0.6, label: "③ E 一开始持续模糊就点按，记录近点")], keyframe: 0.66) { g, phase in
        g.floorShadow(62, 182, 96)
        g.mascot(62, 112, 96, gaze: CGPoint(x: 1, y: 0))
        g.ruler(120, 300, 188)
        let x = CGFloat(keyframes(phase, [(0, 290), (0.15, 290), (0.68, 150), (0.76, 150), (0.9, 290), (1, 290)]))
        let (tl, screen) = g.phone(x, 104, 118)
        let e = CGPoint(x: tl.x + screen.width / 2, y: tl.y + screen.height / 2)
        let blur = seg(phase, 0.58, 0.68) * (1 - seg(phase, 0.78, 0.84))
        if blur > 0.05 {
            for dx: CGFloat in [-2.5, 0, 2.5] { g.optotype(CGPoint(x: e.x + g.u(dx * CGFloat(blur)), y: e.y), screen.width * 0.42, 0, alpha: 1 - 0.65 * blur) }
        } else { g.optotype(e, screen.width * 0.42, 0) }
        g.distanceBadge(x, cmAt(x))
        if (0.15...0.68).contains(phase) { g.label("约 3 cm/秒", 215, 172, EyeStyle.primary) }
        let tap = tri(phase, 0.69, 0.04)
        if tap > 0 { g.circle(CGPoint(x: e.x, y: tl.y + screen.height + g.u(14)), g.u(10 + 16 * CGFloat(1 - tap)), EyeStyle.primary, alpha: tap * 0.5) }
        g.verdict(x, 176, clear: false, alpha: tri(phase, 0.72, 0.05))
    }

    static let flip = GuideScene(cycle: 6.4, beats: [.init(start: 0, label: "① 远处停稳，点选 E 的开口"),
        .init(start: 0.15, label: "② 移近停稳，再点选一次"), .init(start: 0.55, label: "③ 移远，远近交替进行")], keyframe: 0.45) { g, phase in
        g.floorShadow(62, 182, 96)
        g.mascot(62, 112, 96, gaze: CGPoint(x: 1, y: 0))
        g.ruler(120, 300, 188)
        let x = CGFloat(keyframes(phase, [(0, 268), (0.15, 268), (0.3, 170), (0.55, 170), (0.7, 268), (1, 268)]))
        let (tl, screen) = g.phone(x, 102, 150)
        let dir = phase < 0.3 ? 0 : phase < 0.7 ? 3 : 2
        g.optotype(CGPoint(x: tl.x + screen.width / 2, y: tl.y + screen.height * 0.3), screen.width * 0.34, dir)
        let tap = max(tri(phase, 0.1, 0.05), tri(phase, 0.45, 0.05), tri(phase, 0.85, 0.05))
        g.arrowPad(tl, screen, highlight: tap > 0 ? dir : -1, tap: 1 - tap)
        g.distanceBadge(x, cmAt(x))
        if (0.15...0.3).contains(phase) { g.label("← 移近", 220, 172, EyeStyle.primary) }
        else if (0.55...0.7).contains(phase) { g.label("移远 →", 220, 172, EyeStyle.primary) }
    }

    static let fixate = GuideScene(cycle: 4.0, beats: [.init(start: 0, label: "① 看着屏幕中央闪烁的小瞳"),
        .init(start: 0.45, label: "② 头和手机都保持不动")], keyframe: 0.5) { g, phase in
        g.floorShadow(95, 182, 110)
        let mascot = g.p(95, 110)
        g.mascot(95, 110, 108, gaze: CGPoint(x: 0.9, y: -0.1))
        if phase >= 0.45 { g.headLock(95, 106, 66) }
        let (tl, screen) = g.phone(242, 100, 160)
        let dot = CGPoint(x: tl.x + screen.width / 2, y: tl.y + screen.height / 2)
        let pulse = (sin(phase * 2 * .pi * 2) + 1) / 2
        var flash = g.ctx; flash.opacity = pulse > 0.5 ? 1 : 0.3
        flash.drawLayer { layer in Cartoon.mascot(layer, center: dot, size: g.u(22)) }
        let eye = Cartoon.mascotEyeCenter(mascot, g.u(108), 1)
        g.line(CGPoint(x: eye.x + g.u(16), y: eye.y), CGPoint(x: dot.x - g.u(14), y: dot.y), EyeStyle.primary.opacity(0.45), g.u(2),
               dash: [g.u(5), g.u(6)], dashPhase: -CGFloat(phase) * g.u(110))
    }

    static let cover = GuideScene(cycle: 9.6, beats: [.init(start: 0, label: "① 遮右眼 2 秒 → 移开，双眼注视 2 秒"),
        .init(start: 0.36, label: "② 遮左眼 2 秒 → 移开，双眼注视 2 秒"), .init(start: 0.7, label: "③ 右眼、左眼各遮 1 秒，快速交替两轮")], keyframe: 0.12) { g, phase in
        g.floorShadow(160, 184, 120)
        let mascot = g.p(160, 112)
        g.mascot(160, 112, 118)
        // 小瞳面向我们：它的右眼在画面左侧，左眼在画面右侧
        let rEye = Double(Cartoon.mascotEyeCenter(mascot, g.u(118), -1).x)
        let lEye = Double(Cartoon.mascotEyeCenter(mascot, g.u(118), 1).x)
        let away = lEye + Double(g.u(150))
        let hx = keyframes(phase, [(0, away), (0.05, rEye), (0.2, rEye), (0.25, away), (0.36, away),
            (0.42, lEye), (0.56, lEye), (0.61, away), (0.7, away),
            (0.74, rEye), (0.78, rEye), (0.81, lEye), (0.85, lEye),
            (0.88, rEye), (0.91, rEye), (0.94, lEye), (0.97, lEye), (1, away)])
        let eyeY = Cartoon.mascotEyeCenter(mascot, g.u(118), 1).y
        Cartoon.mitten(g.ctx, center: CGPoint(x: hx, y: eyeY + g.u(3)), size: g.u(52), angle: -14, armFrom: g.p(340, 150))
        let near = Double(g.u(6))
        let text = abs(hx - rEye) < near ? "遮右眼" : abs(hx - lEye) < near ? "遮左眼" : hx > lEye + Double(g.u(100)) ? "移开 · 双眼注视" : "换眼"
        g.pill(12, 12, 112, 28, EyeStyle.panel)
        g.label(text, 68, 26, text.hasPrefix("遮") ? EyeStyle.primary : EyeStyle.secondary)
    }

    /// 水平正弦 → 垂直正弦 → 45° 斜向直线往复，与正式追踪任务的顺序一致。
    static let follow = GuideScene(cycle: 7.2, beats: [.init(start: 0, label: "① 水平：只用眼睛跟随小瞳"),
        .init(start: 0.36, label: "② 垂直：头保持不动"), .init(start: 0.72, label: "③ 斜向：继续跟随")], keyframe: 0.2) { g, phase in
        let cx: CGFloat = 214, cy: CGFloat = 92
        let dot: CGPoint
        if phase < 0.36 { dot = CGPoint(x: cx + 72 * CGFloat(sin(phase / 0.36 * 2 * .pi)), y: cy) }
        else if phase < 0.72 { dot = CGPoint(x: cx, y: cy + 62 * CGFloat(sin((phase - 0.36) / 0.36 * 2 * .pi))) }
        else {
            let t = (phase - 0.72) / 0.28
            let wave = CGFloat(t < 0.5 ? t * 4 - 1 : 3 - t * 4)
            dot = CGPoint(x: cx + 52 * wave, y: cy - 52 * wave)
        }
        let text = phase < 0.36 ? "水平" : phase < 0.72 ? "垂直" : "斜向"
        g.pill(150, 18, 128, 150, EyeStyle.panel, alpha: 0.7)
        g.floorShadow(72, 186, 90)
        g.mascot(72, 130, 86, gaze: CGPoint(x: min(1, max(-1, (dot.x - 72) / 140)), y: min(1, max(-1, (dot.y - 130) / 90))))
        g.headLock(72, 126, 54)
        g.mascot(dot.x, dot.y, 9 / 0.65)
        g.pill(12, 12, 64, 28, EyeStyle.panel)
        g.label(text, 44, 26, EyeStyle.primary)
    }

    /// 视标练习：每题 2 秒限时，连对两次变小、超时或答错变大。
    static let acuity = GuideScene(cycle: 6.0, beats: [.init(start: 0, label: "① 看 E 的开口方向"),
        .init(start: 0.1, label: "② 2 秒内点选对应方向"), .init(start: 0.4, label: "③ 连对两次变小，答错或超时变大")], keyframe: 0.12) { g, phase in
        g.floorShadow(66, 184, 84)
        g.mascot(66, 118, 84, gaze: CGPoint(x: 1, y: -0.2))
        let segment = min(4, max(0, Int(phase / 0.2)))
        let t = (phase - Double(segment) * 0.2) / 0.2
        let sizes: [CGFloat] = [1, 1, 0.78, 1, 1]
        let dirs = [0, 3, 2, 1, 0]
        let timeout = segment == 2
        let answerAt = timeout ? 1.0 : 0.5
        let (tl, screen) = g.phone(200, 100, 170)
        let e = CGPoint(x: tl.x + screen.width / 2, y: tl.y + screen.height * 0.3)
        if t < answerAt { g.optotype(e, screen.width * 0.34 * sizes[segment], dirs[segment]) }
        g.arrowPad(tl, screen, highlight: !timeout && t >= 0.45 ? dirs[segment] : -1, tap: !timeout ? clamp01((t - 0.45) / 0.2) : 0)
        let ring = g.p(272, 40), r = g.u(16)
        g.circle(ring, r + g.u(5), EyeStyle.panel)
        g.arc(ring, r, 1 - min(1, t / answerAt), EyeStyle.accent, g.u(4))
        g.label("2s", 272, 40, EyeStyle.text)
        if t >= answerAt {
            if timeout { g.label("超时 → 变大", 272, 170, EyeStyle.warning) } else { g.label("正确", 272, 170, EyeStyle.success) }
        }
    }

    /// 功能性连续视力：自然眨眼时持续辨认视标，之后不停顿转入保持睁眼。
    static let fva = GuideScene(cycle: 7.0, beats: [.init(start: 0, label: "① 全程看 E，点选开口方向"),
        .init(start: 0.12, label: "② 前一段像平时一样自然眨眼"), .init(start: 0.68, label: "③ 不停顿，转入尽量保持睁眼")], keyframe: 0.3) { g, phase in
        g.floorShadow(84, 184, 100)
        let opened = phase >= 0.68
        let lid = opened ? 0 : max(tri(phase, 0.2, 0.03), tri(phase, 0.45, 0.03))
        g.mascot(84, 112, 100, gaze: CGPoint(x: 1, y: 0), lid: CGFloat(lid), wide: opened ? 1.22 : 1)
        let (tl, screen) = g.phone(240, 100, 160)
        let segment = min(7, max(0, Int(phase / 0.14)))
        let dir = [0, 3, 2, 1, 0, 1, 3, 2][segment]
        g.optotype(CGPoint(x: tl.x + screen.width / 2, y: tl.y + screen.height * 0.3), screen.width * 0.32, dir)
        let local = (phase - Double(segment) * 0.14) / 0.14
        g.arrowPad(tl, screen, highlight: local > 0.5 ? dir : -1, tap: clamp01((local - 0.5) / 0.3))
        g.pill(12, 12, 96, 28, EyeStyle.panel)
        g.label(opened ? "持续睁眼" : "自然眨眼", 60, 26, opened ? EyeStyle.warning : EyeStyle.primary)
    }

    /// 多距离视觉表现：在 60 / 33 / 25 cm 依次停稳并辨认视标。
    static let curve = GuideScene(cycle: 6.6, beats: [.init(start: 0, label: "① 移到提示的距离并停稳"),
        .init(start: 0.12, label: "② 点选 E 的开口方向"), .init(start: 0.36, label: "③ 依次换到下一个距离")], keyframe: 0.2) { g, phase in
        g.floorShadow(62, 182, 96)
        g.mascot(62, 112, 96, gaze: CGPoint(x: 1, y: 0))
        g.ruler(120, 300, 188)
        let x = CGFloat(keyframes(phase, [(0, 290), (0.3, 290), (0.38, 200), (0.64, 200), (0.72, 165), (1, 165)]))
        let (tl, screen) = g.phone(x, 102, 140)
        let dir = phase < 0.34 ? 0 : phase < 0.68 ? 3 : 2
        g.optotype(CGPoint(x: tl.x + screen.width / 2, y: tl.y + screen.height * 0.3), screen.width * 0.3, dir)
        let tap = max(tri(phase, 0.18, 0.05), tri(phase, 0.52, 0.05), tri(phase, 0.86, 0.05))
        g.arrowPad(tl, screen, highlight: tap > 0 ? dir : -1, tap: 1 - tap)
        let cm = phase < 0.34 ? 60 : phase < 0.68 ? 33 : 25
        g.pill(x - 30, 12, 60, 24, EyeStyle.panel)
        g.label("\(cm) cm", x, 24, EyeStyle.text)
    }
}

import AVFoundation
import SwiftUI

/// 小瞳的心情：开心 / 思考（看向一侧、头上冒问号）/ 担心（睁大眼、波浪嘴、冒汗）/ 犯困（眯眼打盹、飘 Z）/
/// 疲惫（用眼负荷偏高：眼皮耷拉、黑眼圈、冒汗、嫩芽打蔫）。首页按用眼负荷、结果页按测试等级选择；与安卓 MascotMood 同一套。
enum MascotMood: String, CaseIterable {
    case happy = "HAPPY", thinking = "THINKING", concerned = "CONCERNED", sleepy = "SLEEPY", tired = "TIRED"
    /// 共用核心给出的神情代码（ResultReport / EyeCareReport 的 mood）。
    init(code: String?) { self = MascotMood(rawValue: code ?? "") ?? .happy }

    /// 心情对应的嫩芽打蔫程度（调用方可按负荷分另行指定）。
    var defaultWilt: CGFloat {
        switch self {
        case .tired: return 1
        case .concerned: return 0.4
        case .sleepy: return 0.2
        default: return 0
        }
    }
}

/// 小瞳的反应（动作 + 音效 + 时长）：点一下随机一种；长按是比心；连点五下会转晕；待机时也会自己做几个小动作（无声）。
enum MascotReaction {
    case hop, wink, giggle, surprise, shy, spin, heart, dizzy, yawn, perk
    var sound: String {
        switch self {
        case .hop: return "eye_hop"
        case .giggle: return "eye_giggle"
        case .surprise: return "eye_wow"
        case .wink, .shy: return "eye_boop"
        case .spin: return "eye_spin"
        case .heart: return "eye_heart"
        case .dizzy: return "eye_dizzy"
        case .yawn: return "eye_yawn"
        case .perk: return "eye_sigh"
        }
    }
    /// 动作时长（秒）。
    var duration: Double {
        switch self {
        case .hop, .wink, .giggle, .surprise, .shy: return 0.9
        case .spin: return 1.0
        case .heart, .yawn, .perk: return 1.4
        case .dizzy: return 1.6
        }
    }
    static func choices(_ mood: MascotMood) -> [MascotReaction] {
        switch mood {
        case .happy: return [.hop, .wink, .giggle, .shy, .surprise, .spin, .heart]
        case .thinking: return [.surprise, .wink, .giggle, .hop, .spin]
        case .concerned: return [.shy, .wink, .hop, .heart]
        case .sleepy: return [.yawn, .surprise, .giggle]
        case .tired: return [.perk, .yawn, .shy]
        }
    }
    /// 待机小动作：每 [fidgetCycle] 秒在 30% 与 75% 处各做一个（nil 为不做）。
    static func fidgets(_ mood: MascotMood) -> (MascotReaction?, MascotReaction?) {
        switch mood {
        case .happy: return (.hop, .giggle)
        case .thinking: return (.wink, .hop)
        case .concerned: return (.shy, nil)
        case .sleepy, .tired: return (.yawn, nil)
        }
    }
    static let fidgetCycle: Double = 14
}

/// 会动、能互动的小瞳（安卓 XiaoTong 逐项移植）。待机：呼吸起伏、眨眼、张望，隔一会儿自己蹦一下 / 打个哈欠，
/// 按心情加上闪光 / 问号 / 汗滴 / Z / 黑眼圈；[interactive] 时点一下随机换一种反应（动作 + 音效 + 轻触觉），长按比心，
/// 连点五下转晕；[greet] 出现时蹦一下打招呼（无音效）；[wilt] 为嫩芽打蔫程度（默认按心情）。
/// 系统「减弱动态效果」时只换表情、不做位移。
struct MascotStage: View {
    let mood: MascotMood
    var interactive = true
    var greet = false
    var headScale: CGFloat = 0.7
    var wilt: CGFloat? = nil
    var onTap: (() -> Void)? = nil
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var reaction: MascotReaction?
    @State private var reactionStart = Date.distantPast
    @State private var tapTimes: [Date] = []

    var body: some View {
        TimelineView(.animation(minimumInterval: nil, paused: reduceMotion && reaction == nil)) { timeline in
            Canvas { context, size in
                let now = timeline.date
                let clock = now.timeIntervalSinceReferenceDate
                let phase = reduceMotion ? 0 : clock.truncatingRemainder(dividingBy: 6) / 6
                var current: MascotReaction? = nil
                var t: Double = 1
                var amp: CGFloat = 1
                if let reaction, now.timeIntervalSince(reactionStart) < reaction.duration {
                    current = reaction; t = now.timeIntervalSince(reactionStart) / reaction.duration
                } else if !reduceMotion {
                    let f = clock.truncatingRemainder(dividingBy: MascotReaction.fidgetCycle) / MascotReaction.fidgetCycle
                    let (a, b) = MascotReaction.fidgets(mood)
                    for (move, at) in [(a, 0.3), (b, 0.75)] {
                        guard let move else { continue }
                        let span = move.duration / MascotReaction.fidgetCycle
                        if f >= at && f <= at + span { current = move; t = (f - at) / span; amp = 0.6 }
                    }
                }
                MascotPainter.draw(context, size: size, mood: mood, p: CGFloat(phase), reaction: current,
                    t: CGFloat(min(max(t, 0), 1)), amp: amp, scale: headScale, wilt: wilt ?? mood.defaultWilt, still: reduceMotion)
            }
        }
        .contentShape(Rectangle())
        .onTapGesture {
            guard interactive else { return }
            let now = Date()
            // 转晕的时候先晕完，不被接下来的点按打断
            if reaction == .dizzy && now.timeIntervalSince(reactionStart) < MascotReaction.dizzy.duration { return }
            tapTimes = Array((tapTimes + [now]).filter { now.timeIntervalSince($0) <= 2.5 }.suffix(5))
            let next: MascotReaction
            if tapTimes.count >= 5 { tapTimes = []; next = .dizzy }
            else { next = MascotReaction.choices(mood).filter { $0 != reaction }.randomElement() ?? .hop }
            react(next)
            MascotSound.shared.play(next.sound)
            onTap?()
        }
        .onLongPressGesture(minimumDuration: 0.45) {
            guard interactive else { return }
            react(.heart)
            MascotSound.shared.play(MascotReaction.heart.sound)
            onTap?()
        }
        .onAppear { if greet { react(.hop) } }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(interactive ? "小瞳，点一下和它互动，长按摸摸头" : "小瞳")
        .accessibilityAddTraits(interactive ? .isButton : [])
    }

    private func react(_ next: MascotReaction) {
        reaction = next
        reactionStart = Date()
        let started = reactionStart
        DispatchQueue.main.asyncAfter(deadline: .now() + next.duration + 0.05) { if reactionStart == started { reaction = nil } }
    }
}

/// 小瞳的逐帧绘制（与安卓 drawXiaoTong 同一组参数与时间点）。
enum MascotPainter {
    static func draw(_ ctx: GraphicsContext, size: CGSize, mood: MascotMood, p: CGFloat, reaction: MascotReaction?, t: CGFloat,
                     amp: CGFloat = 1, scale: CGFloat, wilt wiltLevel: CGFloat = 0, still: Bool) {
        let s = min(size.width, size.height) * scale
        let baseY = size.height * 0.58
        let tau = CGFloat.pi * 2
        var cx = size.width / 2
        var cy = baseY + (still ? 0 : sin(tau * p * 3) * s * 0.012)
        var rotation: Double = 0
        var pivot: CGPoint? = nil
        var squashY: CGFloat = 1
        var lid = max(max(1 - abs(p - 0.3) / 0.02, 1 - abs(p - 0.36) / 0.02), max(1 - abs(p - 0.82) / 0.02, 0))
        var gaze = CGPoint.zero
        var wide: CGFloat = 1
        var eyes = MascotEyes.dot
        var mouth = MascotMouth.smile
        var blush: CGFloat = 0.75
        var leaf: Double = still ? 0 : Double(sin(tau * p * 2) * 4)
        var wilt = wiltLevel

        switch mood {
        case .happy: gaze = CGPoint(x: lookAround(p), y: 0)
        case .thinking:
            rotation = still ? 4 : Double(sin(tau * p * 2) * 6); gaze = CGPoint(x: 0.6, y: -0.6); mouth = .round
        case .concerned:
            wide = 1.12; mouth = .wobbly; gaze = CGPoint(x: still ? 0 : sin(tau * p) * 0.3, y: 0)
            if !still && p >= 0.5 && p <= 0.58 { cx += sin(tau * p * 60) * s * 0.008 }
        case .sleepy:
            mouth = .round
            let nodding = !still && p >= 0.55 && p <= 0.7
            lid = still ? 0.7 : nodding ? 1 : 0.62 + 0.18 * (0.5 + 0.5 * sin(tau * p * 2))
            if nodding { cy += s * 0.03 }
        case .tired:
            // 眼皮耷拉、慢慢眨，身体微微塌下去，嫩芽随呼吸轻轻晃
            mouth = .flat; gaze = CGPoint(x: 0, y: 0.4); blush = 0.35
            lid = still ? 0.5 : max(0.45 + 0.06 * sin(tau * p), max(1 - abs(p - 0.6) / 0.05, 0))
            squashY = 0.96
            cy = baseY + s * 0.02 + (still ? 0 : sin(tau * p * 2) * s * 0.01)
            leaf = still ? 0 : Double(sin(tau * p) * 2)
            wilt = wiltLevel * (still ? 1 : 0.94 + 0.06 * sin(tau * p * 2))
        }

        if let reaction {
            let bump = sin(CGFloat.pi * t)
            let move: CGFloat = still ? 0 : amp
            switch reaction {
            case .hop:
                cy -= bump * s * 0.22 * move
                let edge = max(0, 1 - t / 0.12) + max(0, (t - 0.88) / 0.12)
                squashY = 1 + (0.06 * bump - 0.14 * edge) * move
                eyes = .happy; mouth = .grin; lid = 0
            case .wink: eyes = .wink; mouth = .smile; rotation = Double(bump * 8 * move); blush = 0.9; lid = 0
            case .giggle:
                eyes = .happy; mouth = .grin; lid = 0
                rotation = Double(sin(tau * t * 4) * 7 * (1 - t) * move); leaf = Double(sin(tau * t * 3) * 25 * move)
                cy -= abs(sin(tau * t * 3)) * s * 0.04 * move
            case .surprise: lid = 0; wide = 1.3; mouth = .round; gaze = .zero; cy -= bump * s * 0.08 * move
            case .shy: eyes = .happy; mouth = .smile; blush = 1; rotation = Double(-bump * 10 * move); lid = 0
            case .spin:
                eyes = .happy; mouth = .grin; lid = 0
                rotation = Double(360 * standardEase(t) * move); pivot = CGPoint(x: cx, y: cy + s * 0.05)
                cy -= bump * s * 0.12 * move; leaf = Double(bump * 30 * move)
            case .heart:
                eyes = .happy; mouth = .smile; blush = 1; lid = 0
                rotation = Double(sin(tau * t * 2) * 6 * move); squashY = 1 - 0.04 * bump * move
            case .dizzy:
                eyes = .spiral; mouth = .wobbly; lid = 0
                rotation = Double(sin(tau * t * 3) * 12 * (1 - t) * move); cx += sin(tau * t * 2) * s * 0.04 * (1 - t) * move
            case .yawn:
                lid = 1; mouth = .yawn; squashY = 1 + 0.08 * bump * move
                rotation = Double(-bump * 5 * move); leaf = Double(bump * 12 * move)
            case .perk:
                // 被戳一下打起精神：嫩芽立起来、眼睛睁大，过一会儿又蔫下去
                let up = t < 0.6 ? standardEase(t / 0.6) : 1 - standardEase((t - 0.6) / 0.4)
                wilt = wiltLevel * (1 - up); lid = 0.45 * (1 - up); wide = 1 + 0.15 * up
                mouth = up > 0.5 ? .smile : .flat; cy -= up * s * 0.06 * move; squashY = 1
                leaf = Double(sin(tau * t * 3) * 10 * up * move)
            }
        }

        // 地面投影：跳起时变淡变小
        let lift = max(0, baseY - cy) / s
        let shadowWidth = s * 0.64 * (1 - lift)
        ctx.fill(Path(ellipseIn: CGRect(x: size.width / 2 - shadowWidth / 2, y: baseY + s * 0.43, width: shadowWidth, height: s * 0.07)),
            with: .color(EyeStyle.ink.opacity(Double(min(0.1, max(0.03, 0.1 - lift * 0.25))))))

        var body = ctx
        let turn = pivot ?? CGPoint(x: cx, y: cy + s * 0.3)
        body.translateBy(x: turn.x, y: turn.y); body.rotate(by: .degrees(rotation)); body.translateBy(x: -turn.x, y: -turn.y)
        let foot = CGPoint(x: cx, y: cy + s * 0.42)
        body.translateBy(x: foot.x, y: foot.y); body.scaleBy(x: 2 - squashY, y: squashY); body.translateBy(x: -foot.x, y: -foot.y)
        Cartoon.mascot(body, center: CGPoint(x: cx, y: cy), size: s, gaze: gaze, lid: lid, wide: wide, eyes: eyes, mouth: mouth,
            blush: blush, leafSway: leaf, wilt: wilt)
        // 疲惫：两道淡淡的黑眼圈
        if mood == .tired && eyes == .dot {
            for side: CGFloat in [-1, 1] {
                let e = Cartoon.mascotEyeCenter(CGPoint(x: cx, y: cy), s, side)
                // 眼下方的一段椭圆弧（20°→160°，取点画，避免两端弧线方向约定不同）
                var bag = Path()
                for i in 0...12 {
                    let a = (20 + 140 * CGFloat(i) / 12) * .pi / 180
                    let q = CGPoint(x: e.x + s * 0.06 * cos(a), y: e.y + s * 0.045 + s * 0.035 * sin(a))
                    if i == 0 { bag.move(to: q) } else { bag.addLine(to: q) }
                }
                body.stroke(bag, with: .color(EyeStyle.ink.opacity(0.22)), style: StrokeStyle(lineWidth: s * 0.014, lineCap: .round))
            }
        }

        // 心情点缀
        switch mood {
        case .happy:
            if reaction == nil && !still {
                Cartoon.sparkle(ctx, color: EyeStyle.accent, center: CGPoint(x: cx + s * 0.58, y: cy - s * 0.32), size: s * 0.07, alpha: twinkle(p, 0.15))
                Cartoon.sparkle(ctx, color: EyeStyle.brandStart, center: CGPoint(x: cx - s * 0.6, y: cy - s * 0.08), size: s * 0.05, alpha: twinkle(p, 0.62))
            }
        case .thinking:
            question(ctx, center: CGPoint(x: cx + s * 0.6, y: cy - s * 0.5 - (still ? 0 : sin(tau * p * 2) * s * 0.03)), height: s * 0.2)
        case .concerned, .tired:
            let slide = still ? 0.25 : (p * (mood == .tired ? 1 : 2)).truncatingRemainder(dividingBy: 1)
            drop(ctx, center: CGPoint(x: cx + s * 0.44, y: cy - s * 0.28 + slide * s * 0.16), radius: s * 0.07,
                 alpha: still ? 0.5 : Double(0.55 * (1 - slide)))
        case .sleepy:
            for i in 0..<2 {
                let z = still ? 0.3 + CGFloat(i) * 0.3 : (p * 2 + CGFloat(i) * 0.5).truncatingRemainder(dividingBy: 1)
                letterZ(ctx, center: CGPoint(x: cx + s * (0.45 + z * 0.12), y: cy - s * (0.32 + z * 0.32)),
                        height: s * (0.1 - CGFloat(i) * 0.03), alpha: Double(min(1, max(0, 1 - z))))
            }
        }
        if still { return }
        switch reaction {
        case .hop, .spin:
            // 蹦跳、转圈时四周迸出闪光
            for i in 0..<4 {
                let a = CGFloat(30 + i * 90) * .pi / 180
                let d = s * (0.55 + 0.25 * t)
                Cartoon.sparkle(ctx, color: i % 2 == 0 ? EyeStyle.accent : EyeStyle.brandStart,
                    center: CGPoint(x: cx + cos(a) * d, y: cy + sin(a) * d * 0.8), size: s * 0.06, alpha: Double((1 - t) * amp))
            }
        case .heart:
            // 比心：三颗小心心往上飘
            for i in 0..<3 {
                let k = min(1, max(0, (t - CGFloat(i) * 0.15) / 0.7))
                if k > 0 {
                    heart(ctx, center: CGPoint(x: cx + s * (-0.35 + CGFloat(i) * 0.35) + sin(tau * k) * s * 0.05, y: cy - s * (0.45 + k * 0.45)),
                          radius: s * (0.07 + 0.02 * CGFloat(i)), alpha: Double((1 - k) * 0.85))
                }
            }
        case .dizzy:
            // 转晕：头顶三颗星星绕圈
            for i in 0..<3 {
                let a = tau * (t * 2 + CGFloat(i) / 3)
                Cartoon.sparkle(ctx, color: EyeStyle.accent, center: CGPoint(x: cx + cos(a) * s * 0.32, y: cy - s * 0.62 + sin(a) * s * 0.08),
                                size: s * 0.06, alpha: Double(1 - max(0, t - 0.8) / 0.2))
            }
        case .yawn:
            // 打哈欠：眼角挤出一滴泪
            drop(ctx, center: CGPoint(x: cx + s * 0.27, y: cy + s * 0.02 + t * s * 0.04), radius: s * 0.035, alpha: Double(0.6 * sin(.pi * t)))
        default: break
        }
    }

    /// 与安卓 EyeMotion.Standard 相同的缓动曲线 cubic-bezier(0.2, 0, 0, 1)。
    private static func standardEase(_ t: CGFloat) -> CGFloat {
        let x = min(1, max(0, t))
        func bez(_ a: CGFloat, _ b: CGFloat, _ u: CGFloat) -> CGFloat { 3 * a * u * (1 - u) * (1 - u) + 3 * b * u * u * (1 - u) + u * u * u }
        var lo: CGFloat = 0, hi: CGFloat = 1
        for _ in 0..<20 { let mid = (lo + hi) / 2; if bez(0.2, 0, mid) < x { lo = mid } else { hi = mid } }
        return bez(0, 1, (lo + hi) / 2)
    }

    /// 心形（比心时往上飘）。
    private static func heart(_ ctx: GraphicsContext, center c: CGPoint, radius r: CGFloat, alpha: Double) {
        var path = Path(); path.move(to: CGPoint(x: c.x, y: c.y + r))
        path.addCurve(to: CGPoint(x: c.x, y: c.y - r * 0.45), control1: CGPoint(x: c.x - r * 1.6, y: c.y - r * 0.2), control2: CGPoint(x: c.x - r * 0.7, y: c.y - r * 1.3))
        path.addCurve(to: CGPoint(x: c.x, y: c.y + r), control1: CGPoint(x: c.x + r * 0.7, y: c.y - r * 1.3), control2: CGPoint(x: c.x + r * 1.6, y: c.y - r * 0.2))
        path.closeSubpath()
        ctx.fill(path, with: .color(EyeStyle.danger.opacity(alpha)))
    }

    /// 待机张望：前半周期看正前方，之后看左、看右再回来。
    private static func lookAround(_ p: CGFloat) -> CGFloat {
        switch p {
        case ..<0.5: return 0
        case ..<0.65: return -(p - 0.5) / 0.15
        case ..<0.8: return -1 + (p - 0.65) / 0.15 * 2
        case ..<0.9: return 1 - (p - 0.8) / 0.1
        default: return 0
        }
    }

    private static func twinkle(_ p: CGFloat, _ at: CGFloat) -> Double { Double(min(1, max(0, 1 - abs(p - at) / 0.08))) }

    /// 问号：上方弯钩（取点画弧）+ 竖笔 + 圆点。
    private static func question(_ ctx: GraphicsContext, center c: CGPoint, height h: CGFloat) {
        let r = h * 0.24
        let hook = CGPoint(x: c.x, y: c.y - h * 0.22)
        var path = Path()
        for i in 0...16 {
            let a = (200 + 250 * Double(i) / 16) * .pi / 180
            let q = CGPoint(x: hook.x + r * CGFloat(cos(a)), y: hook.y + r * CGFloat(sin(a)))
            if i == 0 { path.move(to: q) } else { path.addLine(to: q) }
        }
        path.addLine(to: CGPoint(x: c.x, y: c.y + h * 0.12))
        ctx.stroke(path, with: .color(EyeStyle.primary), style: StrokeStyle(lineWidth: h * 0.12, lineCap: .round))
        let d = h * 0.07
        ctx.fill(Path(ellipseIn: CGRect(x: c.x - d, y: c.y + h * 0.36 - d, width: d * 2, height: d * 2)), with: .color(EyeStyle.primary))
    }

    /// 汗滴：尖头朝上的水滴。
    private static func drop(_ ctx: GraphicsContext, center c: CGPoint, radius r: CGFloat, alpha: Double) {
        var path = Path(); path.move(to: CGPoint(x: c.x, y: c.y - r * 1.6))
        path.addCurve(to: CGPoint(x: c.x + r, y: c.y + r * 0.2), control1: CGPoint(x: c.x + r * 0.4, y: c.y - r * 0.8), control2: CGPoint(x: c.x + r, y: c.y - r * 0.3))
        path.addCurve(to: CGPoint(x: c.x, y: c.y + r * 1.1), control1: CGPoint(x: c.x + r, y: c.y + r * 0.8), control2: CGPoint(x: c.x + r * 0.5, y: c.y + r * 1.1))
        path.addCurve(to: CGPoint(x: c.x - r, y: c.y + r * 0.2), control1: CGPoint(x: c.x - r * 0.5, y: c.y + r * 1.1), control2: CGPoint(x: c.x - r, y: c.y + r * 0.8))
        path.addCurve(to: CGPoint(x: c.x, y: c.y - r * 1.6), control1: CGPoint(x: c.x - r, y: c.y - r * 0.3), control2: CGPoint(x: c.x - r * 0.4, y: c.y - r * 0.8))
        path.closeSubpath()
        ctx.fill(path, with: .color(EyeStyle.brandEnd.opacity(alpha)))
    }

    /// 字母 Z（三笔）。
    private static func letterZ(_ ctx: GraphicsContext, center c: CGPoint, height h: CGFloat, alpha: Double) {
        var path = Path()
        path.move(to: CGPoint(x: c.x - h / 2, y: c.y - h / 2)); path.addLine(to: CGPoint(x: c.x + h / 2, y: c.y - h / 2))
        path.addLine(to: CGPoint(x: c.x - h / 2, y: c.y + h / 2)); path.addLine(to: CGPoint(x: c.x + h / 2, y: c.y + h / 2))
        ctx.stroke(path, with: .color(EyeStyle.primary.opacity(alpha)), style: StrokeStyle(lineWidth: h * 0.2, lineCap: .round, lineJoin: .round))
    }
}

// MARK: - 对话气泡

/// 气泡尾巴在底边的位置：偏左 / 居中 / 偏右，指向下面的小瞳。
enum BubbleTail { case start, center, end }

/// 小瞳说的一句话；[action] 为可执行时气泡末尾的按钮字。
struct MascotLine: Equatable {
    let text: String
    var action: String? = nil
}

/// 气泡外形：圆角矩形 + 底边小尾巴，一笔画成（描边在尾巴根部不会断开）。
struct BubbleShape: Shape {
    var tail: BubbleTail
    func path(in rect: CGRect) -> Path {
        let th: CGFloat = 9, r: CGFloat = 16
        let bottom = rect.height - th
        let x: CGFloat
        switch tail {
        case .start: x = r + th
        case .center: x = rect.width / 2
        case .end: x = rect.width - r - th
        }
        var path = Path()
        path.move(to: CGPoint(x: r, y: 0))
        path.addArc(tangent1End: CGPoint(x: rect.width, y: 0), tangent2End: CGPoint(x: rect.width, y: bottom), radius: r)
        path.addArc(tangent1End: CGPoint(x: rect.width, y: bottom), tangent2End: CGPoint(x: 0, y: bottom), radius: r)
        path.addLine(to: CGPoint(x: x + th * 0.9, y: bottom))
        path.addLine(to: CGPoint(x: x + th * 0.15, y: rect.height))
        path.addLine(to: CGPoint(x: x - th, y: bottom))
        path.addArc(tangent1End: CGPoint(x: 0, y: bottom), tangent2End: CGPoint(x: 0, y: 0), radius: r)
        path.addArc(tangent1End: CGPoint(x: 0, y: 0), tangent2End: CGPoint(x: rect.width, y: 0), radius: r)
        path.closeSubpath()
        return path
    }
}

/// 对话气泡：白底圆角 + 细边框 + 底边小尾巴，正文字号（不朗读）。[onTap] 不为空时整个气泡可点。与安卓 MascotBubble 同一样式。
struct MascotBubble: View {
    let line: MascotLine
    var tail: BubbleTail = .center
    var onTap: (() -> Void)? = nil
    var body: some View {
        if let onTap {
            Button { EyeHaptics.tap(); onTap() } label: { content }.buttonStyle(.plain)
        } else {
            content
        }
    }
    private var content: some View {
        HStack(spacing: 8) {
            Text(line.text).font(EyeStyle.body).foregroundStyle(EyeStyle.text).lineLimit(2).fixedSize(horizontal: false, vertical: true)
            if let action = line.action {
                Text(action + " ›").font(EyeStyle.button).foregroundStyle(EyeStyle.primary).lineLimit(1).fixedSize()
            }
        }
        .padding(.horizontal, 14).padding(.top, 9).padding(.bottom, 18)
        .background(BubbleShape(tail: tail).fill(EyeStyle.panel))
        .overlay(BubbleShape(tail: tail).stroke(EyeStyle.borderSubtle, lineWidth: 1))
        .accessibilityElement(children: .combine)
    }
}

/// 会说话的小瞳（安卓 XiaoTongTalk）：上方气泡区依次弹出 [lines]（每句约 5 秒，隔几秒换下一句），下方是会动、能互动的小瞳。
/// 点一下小瞳立刻换下一句；点可执行的气泡交给 [onLine]。小瞳只占右侧 [mascotFraction] 宽时，气泡仍可用整行宽度，尾巴对准小瞳的头。
/// 系统「减弱动态效果」时气泡常显、不做弹跳。
struct MascotTalk: View {
    let mood: MascotMood
    let lines: [MascotLine]
    var headScale: CGFloat = 0.7
    var wilt: CGFloat? = nil
    var bubbleHeight: CGFloat = 68
    var tail: BubbleTail = .center
    var mascotFraction: CGFloat = 1
    var onLine: (Int) -> Void = { _ in }
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var start = Date()
    @State private var manualIndex = 0
    @State private var manualUntil = Date.distantPast
    private static let cycle: Double = 9

    var body: some View {
        GeometryReader { geo in
            let inset = mascotFraction < 1 ? max(0, geo.size.width * mascotFraction / 2 - 26) : 0
            VStack(spacing: 0) {
                TimelineView(.periodic(from: start, by: 0.25)) { timeline in
                    let (index, shown) = state(at: timeline.date)
                    ZStack(alignment: alignment) {
                        Color.clear
                        if shown, lines.indices.contains(index) {
                            MascotBubble(line: lines[index], tail: tail, onTap: lines[index].action == nil ? nil : { onLine(index) })
                                .id(index)
                                .transition(reduceMotion ? .opacity : .scale(scale: 0.6, anchor: anchor).combined(with: .opacity))
                        }
                    }
                    .animation(reduceMotion ? .easeOut(duration: 0.18) : .spring(response: 0.35, dampingFraction: 0.55), value: shown ? index : -1)
                }
                .frame(height: bubbleHeight).padding(.trailing, inset)
                MascotStage(mood: mood, headScale: headScale, wilt: wilt, onTap: next)
                    .frame(width: geo.size.width * mascotFraction)
                    .frame(maxWidth: .infinity, alignment: .trailing)
            }
        }
    }

    private var alignment: Alignment {
        switch tail { case .start: return .bottomLeading; case .center: return .bottom; case .end: return .bottomTrailing }
    }
    private var anchor: UnitPoint {
        switch tail { case .start: return UnitPoint(x: 0.15, y: 1); case .center: return .bottom; case .end: return UnitPoint(x: 0.85, y: 1) }
    }

    /// 当前该说第几句、是否显示：点过小瞳的 4.5 秒内显示点出来的那句，否则每 9 秒一句、前约 60% 显示。
    private func state(at date: Date) -> (Int, Bool) {
        guard !lines.isEmpty else { return (0, false) }
        if date < manualUntil { return (manualIndex % lines.count, true) }
        let elapsed = max(0, date.timeIntervalSince(start)) / Self.cycle
        let local = elapsed.truncatingRemainder(dividingBy: 1)
        return (Int(elapsed) % lines.count, reduceMotion || (local >= 0.04 && local <= 0.62))
    }

    private func next() {
        guard !lines.isEmpty else { return }
        manualIndex = (state(at: Date()).0 + 1) % lines.count
        manualUntil = Date().addingTimeInterval(4.5)
    }
}

/// 小瞳互动音：与测试音效同一套开关与音量（系统设置的「音效反馈」「音效音量」「震动反馈」）。
/// 不在测试中时用「环境音」类别：跟随静音键、与其他应用的声音混合，不打断音乐。
final class MascotSound {
    static let shared = MascotSound()
    private var players: [String: AVAudioPlayer] = [:]

    func play(_ name: String) {
        let defaults = UserDefaults.standard
        if defaults.object(forKey: "eye.hapticsEnabled") as? Bool ?? true { EyeHaptics.tap() }
        guard defaults.object(forKey: "eye.soundEnabled") as? Bool ?? true else { return }
        let session = AVAudioSession.sharedInstance()
        if session.category == .soloAmbient { try? session.setCategory(.ambient, options: [.mixWithOthers]) }
        guard let player = player(name) else { return }
        player.volume = Float(0.7 * (defaults.object(forKey: "eye.soundVolume") as? Double ?? 1))
        player.currentTime = 0
        player.play()
    }

    private func player(_ name: String) -> AVAudioPlayer? {
        if let cached = players[name] { return cached }
        guard let url = Bundle.main.url(forResource: name, withExtension: "wav"),
              let created = try? AVAudioPlayer(contentsOf: url) else { return nil }
        created.prepareToPlay()
        players[name] = created
        return created
    }
}

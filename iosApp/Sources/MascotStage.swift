import AVFoundation
import SwiftUI

/// 小瞳的心情：开心 / 思考（看向一侧、头上冒问号）/ 担心（睁大眼、波浪嘴、冒汗）/ 犯困（眯眼打盹、飘 Z）。
/// 首页按用眼负荷、结果页按测试等级选择；与安卓 MascotMood 同一套。
enum MascotMood: String, CaseIterable {
    case happy = "HAPPY", thinking = "THINKING", concerned = "CONCERNED", sleepy = "SLEEPY"
    /// 共用核心给出的神情代码（ResultReport / EyeCareReport 的 mood）。
    init(code: String?) { self = MascotMood(rawValue: code ?? "") ?? .happy }
}

/// 点一下小瞳的反应：蹦跳、眨眼、咯咯笑、吓一跳、害羞，各配一个短音效。
enum MascotReaction {
    case hop, wink, giggle, surprise, shy
    var sound: String {
        switch self {
        case .hop: return "eye_hop"
        case .giggle: return "eye_giggle"
        case .surprise: return "eye_wow"
        case .wink, .shy: return "eye_boop"
        }
    }
    static func sequence(_ mood: MascotMood) -> [MascotReaction] {
        switch mood {
        case .happy: return [.hop, .wink, .giggle, .shy, .surprise]
        case .thinking: return [.surprise, .wink, .giggle, .hop]
        case .concerned: return [.shy, .wink, .hop]
        case .sleepy: return [.surprise, .giggle, .hop]
        }
    }
}

/// 会动、能互动的小瞳（安卓 XiaoTong 逐项移植）。待机：呼吸起伏、眨眼、张望，按心情加上闪光 / 问号 / 汗滴 / Z；
/// [interactive] 时点一下按顺序换一种反应（动作 + 音效 + 轻触觉）；[greet] 出现时蹦一下打招呼（无音效）。
/// 系统「减弱动态效果」时只换表情、不做位移。
struct MascotStage: View {
    let mood: MascotMood
    var interactive = true
    var greet = false
    var headScale: CGFloat = 0.7
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var reaction: MascotReaction?
    @State private var reactionStart = Date.distantPast
    @State private var taps = 0

    var body: some View {
        TimelineView(.animation(minimumInterval: nil, paused: reduceMotion && reaction == nil)) { timeline in
            Canvas { context, size in
                let now = timeline.date
                let phase = reduceMotion ? 0 : now.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 6) / 6
                let t = now.timeIntervalSince(reactionStart) / 0.9
                MascotPainter.draw(context, size: size, mood: mood, p: CGFloat(phase), reaction: t < 1 ? reaction : nil,
                    t: CGFloat(min(max(t, 0), 1)), scale: headScale, still: reduceMotion)
            }
        }
        .contentShape(Rectangle())
        .onTapGesture {
            guard interactive else { return }
            let list = MascotReaction.sequence(mood)
            let next = list[taps % list.count]
            taps += 1
            react(next)
            MascotSound.shared.play(next.sound)
        }
        .onAppear { if greet { react(.hop) } }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(interactive ? "小瞳，点一下和它互动" : "小瞳")
        .accessibilityAddTraits(interactive ? .isButton : [])
    }

    private func react(_ next: MascotReaction) {
        reaction = next
        reactionStart = Date()
        let started = reactionStart
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.95) { if reactionStart == started { reaction = nil } }
    }
}

/// 小瞳的逐帧绘制（与安卓 drawXiaoTong 同一组参数与时间点）。
enum MascotPainter {
    static func draw(_ ctx: GraphicsContext, size: CGSize, mood: MascotMood, p: CGFloat, reaction: MascotReaction?, t: CGFloat,
                     scale: CGFloat, still: Bool) {
        let s = min(size.width, size.height) * scale
        let baseY = size.height * 0.58
        let tau = CGFloat.pi * 2
        var cx = size.width / 2
        var cy = baseY + (still ? 0 : sin(tau * p * 3) * s * 0.012)
        var rotation: Double = 0
        var squashY: CGFloat = 1
        var lid = max(max(1 - abs(p - 0.3) / 0.02, 1 - abs(p - 0.36) / 0.02), max(1 - abs(p - 0.82) / 0.02, 0))
        var gaze = CGPoint.zero
        var wide: CGFloat = 1
        var eyes = MascotEyes.dot
        var mouth = MascotMouth.smile
        var blush: CGFloat = 0.75
        var leaf: Double = still ? 0 : Double(sin(tau * p * 2) * 4)

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
        }

        if let reaction {
            let bump = sin(CGFloat.pi * t)
            let move: CGFloat = still ? 0 : 1
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
            }
        }

        // 地面投影：跳起时变淡变小
        let lift = max(0, baseY - cy) / s
        let shadowWidth = s * 0.64 * (1 - lift)
        ctx.fill(Path(ellipseIn: CGRect(x: size.width / 2 - shadowWidth / 2, y: baseY + s * 0.43, width: shadowWidth, height: s * 0.07)),
            with: .color(EyeStyle.ink.opacity(Double(min(0.1, max(0.03, 0.1 - lift * 0.25))))))

        var body = ctx
        let pivot = CGPoint(x: cx, y: cy + s * 0.3)
        body.translateBy(x: pivot.x, y: pivot.y); body.rotate(by: .degrees(rotation)); body.translateBy(x: -pivot.x, y: -pivot.y)
        let foot = CGPoint(x: cx, y: cy + s * 0.42)
        body.translateBy(x: foot.x, y: foot.y); body.scaleBy(x: 2 - squashY, y: squashY); body.translateBy(x: -foot.x, y: -foot.y)
        Cartoon.mascot(body, center: CGPoint(x: cx, y: cy), size: s, gaze: gaze, lid: lid, wide: wide, eyes: eyes, mouth: mouth,
            blush: blush, leafSway: leaf)

        // 心情点缀
        switch mood {
        case .happy:
            if reaction == nil && !still {
                Cartoon.sparkle(ctx, color: EyeStyle.accent, center: CGPoint(x: cx + s * 0.58, y: cy - s * 0.32), size: s * 0.07, alpha: twinkle(p, 0.15))
                Cartoon.sparkle(ctx, color: EyeStyle.brandStart, center: CGPoint(x: cx - s * 0.6, y: cy - s * 0.08), size: s * 0.05, alpha: twinkle(p, 0.62))
            }
        case .thinking:
            question(ctx, center: CGPoint(x: cx + s * 0.6, y: cy - s * 0.5 - (still ? 0 : sin(tau * p * 2) * s * 0.03)), height: s * 0.2)
        case .concerned:
            let slide = still ? 0.25 : (p * 2).truncatingRemainder(dividingBy: 1)
            drop(ctx, center: CGPoint(x: cx + s * 0.44, y: cy - s * 0.28 + slide * s * 0.16), radius: s * 0.07,
                 alpha: still ? 0.5 : Double(0.55 * (1 - slide)))
        case .sleepy:
            for i in 0..<2 {
                let z = still ? 0.3 + CGFloat(i) * 0.3 : (p * 2 + CGFloat(i) * 0.5).truncatingRemainder(dividingBy: 1)
                letterZ(ctx, center: CGPoint(x: cx + s * (0.45 + z * 0.12), y: cy - s * (0.32 + z * 0.32)),
                        height: s * (0.1 - CGFloat(i) * 0.03), alpha: Double(min(1, max(0, 1 - z))))
            }
        }
        // 蹦跳时四周迸出闪光
        if reaction == .hop && !still {
            for i in 0..<4 {
                let a = CGFloat(30 + i * 90) * .pi / 180
                let d = s * (0.55 + 0.25 * t)
                Cartoon.sparkle(ctx, color: i % 2 == 0 ? EyeStyle.accent : EyeStyle.brandStart,
                    center: CGPoint(x: cx + cos(a) * d, y: cy + sin(a) * d * 0.8), size: s * 0.06, alpha: Double(1 - t))
            }
        }
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

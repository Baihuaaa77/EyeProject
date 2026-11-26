import SwiftUI

/// 测试前的方法演示：阳光湖景小芽浇水与月夜森林萤火虫趣味情景动画，和安卓保持一致。
/// 只出现在准备阶段，不参与正式刺激呈现或计时；系统减弱动态效果时停在关键帧并展示全部步骤。
enum GuideMotion { case posture, acuity, fva, blink, open, near, move, curve, flip, fixate, far, cover, coverSelf, follow, rest, lighting
    var isFireflyTheme: Bool {
        [.fixate, .far, .cover, .coverSelf, .follow, .lighting].contains(self)
    }

    static func forHelp(_ motion: String) -> GuideMotion {
        switch motion {
        case "ACUITY": .acuity
        case "FVA": .fva
        case "BLINK": .blink
        case "OPEN": .open
        case "REST": .rest
        case "LIGHTING": .lighting
        case "NEAR": .near
        case "MOVE": .move
        case "CURVE": .curve
        case "SWITCH": .flip
        case "FIXATE": .fixate
        case "FAR": .far
        case "COVER_SELF": .coverSelf
        case "FOLLOW": .follow
        default: .posture
        }
    }

    /// 与安卓 guideMotion(step) 相同的映射。
    static func forStep(_ step: String, selfCover: Bool = false) -> GuideMotion {
        switch step {
        case "NEAR_VISION": .near
        case "PUSH_UP": .move
        case "DISTANCE_CURVE": .curve
        case "FLIPPER": .flip
        case "FIXATION": .fixate
        case "FAR_FIXATION": .far
        case "COVER": .coverSelf
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
    @State private var start = Date()

    init(motion: GuideMotion, title: String, cue: String, readyTitle: String = "准备好了", onReady: (() -> Void)? = nil) {
        self.motion = motion; self.title = title; self.cue = cue; self.readyTitle = readyTitle; self.onReady = onReady
    }

    var body: some View {
        let scene = GuideScene.scene(motion)
        TimelineView(.animation(paused: reduceMotion)) { timeline in
            content(scene, phase: phase(scene, at: timeline.date))
        }
        .padding(16).frame(maxWidth: .infinity)
        .eyeGlass()
        .onChange(of: motion) { _ in start = Date() }
    }

    private func phase(_ scene: GuideScene, at date: Date) -> Double {
        if reduceMotion { return scene.keyframe }
        return progress(scene, at: date)
    }

    private func content(_ scene: GuideScene, phase: Double) -> some View {
        let active = scene.beats.lastIndex(where: { phase >= $0.start }) ?? 0
        return VStack(spacing: 14) {
            Text(title).font(EyeStyle.title).foregroundStyle(EyeStyle.text).frame(maxWidth: .infinity, alignment: .leading)
            GuideStage(scene: scene, phase: phase).accessibilityHidden(true)
            Text(cue).font(EyeStyle.body).foregroundStyle(EyeStyle.text).multilineTextAlignment(.center)
            DisclosureGroup("演示步骤") {
                VStack(spacing: 6) {
                    ForEach(Array(scene.beats.enumerated()), id: \.offset) { index, beat in
                        GuideBeatRow(label: beat.label, active: reduceMotion || index == active, done: !reduceMotion && index < active)
                    }
                }
            }.font(EyeStyle.body).tint(EyeStyle.primary)
            if let onReady { Button(readyTitle, action: onReady).buttonStyle(EyeButtonStyle()) }
        }
    }

    private func progress(_ scene: GuideScene, at date: Date) -> Double {
        (date.timeIntervalSince(start) / scene.cycle).truncatingRemainder(dividingBy: 1)
    }
}

/// 分镜行：当前步骤主色高亮，已完成步骤弱化。
private struct GuideBeatRow: View {
    let label: String
    let active: Bool
    let done: Bool
    var body: some View {
        Text(label).font(EyeStyle.body)
            .foregroundStyle(active ? EyeStyle.text : EyeStyle.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 12).padding(.vertical, 8)
            .background(active ? EyeStyle.primarySoft : EyeStyle.panel, in: RoundedRectangle(cornerRadius: 12))
            .animation(.easeInOut(duration: 0.25), value: active)
            .accessibilityValue(active ? "当前步骤" : "")
    }
}

/// 趣味动画演示节拍；只用于方法教学，不参与正式刺激与计时。
struct GuideScene {
    struct Beat { let start: Double; let label: String }
    let motion: GuideMotion
    let cycle: Double
    let beats: [Beat]
    let keyframe: Double
    static func scene(_ motion: GuideMotion) -> GuideScene {
        switch motion {
        case .lighting: GuideScene(motion: motion, cycle: 5.0, beats: [.init(start: 0, label: "① 手机固定在支架上，正对双眼"), .init(start: 0.35, label: "② 按提示遮眼"), .init(start: 0.7, label: "③ 不要压眼球")], keyframe: 0.5)
        case .rest: GuideScene(motion: motion, cycle: 4.0, beats: [.init(start: 0, label: "① 轻轻闭眼，放松休息"), .init(start: 0.5, label: "② 倒计时结束后再睁眼")], keyframe: 0.5)
        case .posture: GuideScene(motion: motion, cycle: 5.2, beats: [.init(start: 0.0, label: "① 正对屏幕，露出双眼"), .init(start: 0.25, label: "② 前后微调到提示的距离"), .init(start: 0.6, label: "③ 光线均匀，避免逆光")], keyframe: 0.7)
        case .blink: GuideScene(motion: motion, cycle: 4.2, beats: [.init(start: 0.0, label: "① 正对屏幕，保持放松"), .init(start: 0.15, label: "② 像平时一样自然眨眼"), .init(start: 0.55, label: "③ 应用自动记录眨眼")], keyframe: 0.6)
        case .open: GuideScene(motion: motion, cycle: 4.8, beats: [.init(start: 0.0, label: "① 轻松睁开双眼"), .init(start: 0.15, label: "② 尽量保持睁眼，看着屏幕"), .init(start: 0.86, label: "③ 不舒服就眨眼，随时可停止")], keyframe: 0.5)
        case .near: GuideScene(motion: motion, cycle: 5.6, beats: [.init(start: 0.0, label: "① 用手掌遮住左眼（不压眼球）"), .init(start: 0.2, label: "② 用右眼看 E 的开口方向"), .init(start: 0.28, label: "③ 点选对应方向；之后换眼再做")], keyframe: 0.35)
        case .move: GuideScene(motion: motion, cycle: 7.6, beats: [.init(start: 0.0, label: "① 从 45 cm 外开始，看着 E"), .init(start: 0.15, label: "② 慢慢移近（每秒不超过 5 cm）"), .init(start: 0.6, label: "③ E 一开始持续模糊就点按，记录近点")], keyframe: 0.66)
        case .flip: GuideScene(motion: motion, cycle: 6.4, beats: [.init(start: 0.0, label: "① 远处停稳，点选 E 的开口"), .init(start: 0.15, label: "② 移近停稳，再点选一次"), .init(start: 0.55, label: "③ 移远，远近交替进行")], keyframe: 0.45)
        case .fixate: GuideScene(motion: motion, cycle: 4.0, beats: [.init(start: 0.0, label: "① 看着屏幕圆点"), .init(start: 0.45, label: "② 头和手机都保持不动")], keyframe: 0.5)
        case .far: GuideScene(motion: motion, cycle: 5.2, beats: [.init(start: 0.0, label: "① 手机留在眼前约 33 cm，略低于视线"), .init(start: 0.3, label: "② 眼睛越过手机，看 6 米外的目标"), .init(start: 0.65, label: "③ 看约 10 秒，头和手机都不动")], keyframe: 0.5)
        case .coverSelf: GuideScene(motion: motion, cycle: 12.0, beats: [.init(start: 0.0, label: "① 遮右眼 2 秒 → 移开，双眼注视 2 秒"), .init(start: 0.3333333333333333, label: "② 遮左眼 2 秒 → 移开，双眼注视 2 秒"), .init(start: 0.6666666666666666, label: "③ 右眼、左眼各遮 1 秒，快速交替两轮")], keyframe: 0.12)
        case .cover: GuideScene(motion: motion, cycle: 12.0, beats: [.init(start: 0.0, label: "① 遮右眼 2 秒 → 移开，双眼注视 2 秒"), .init(start: 0.3333333333333333, label: "② 遮左眼 2 秒 → 移开，双眼注视 2 秒"), .init(start: 0.6666666666666666, label: "③ 右眼、左眼各遮 1 秒，快速交替两轮")], keyframe: 0.12)
        case .follow: GuideScene(motion: motion, cycle: 7.2, beats: [.init(start: 0.0, label: "① 水平：只用眼睛跟随圆点"), .init(start: 0.36, label: "② 垂直：头保持不动"), .init(start: 0.72, label: "③ 斜向：继续跟随")], keyframe: 0.2)
        case .acuity: GuideScene(motion: motion, cycle: 6.0, beats: [.init(start: 0.0, label: "① 看 E 的开口方向"), .init(start: 0.1, label: "② 2 秒内点选对应方向"), .init(start: 0.4, label: "③ 连对两次变小，答错或超时变大")], keyframe: 0.12)
        case .fva: GuideScene(motion: motion, cycle: 7.0, beats: [.init(start: 0.0, label: "① 全程看 E，点选开口方向"), .init(start: 0.12, label: "② 前一段像平时一样自然眨眼"), .init(start: 0.68, label: "③ 看到提示后，尽量保持睁眼")], keyframe: 0.3)
        case .curve: GuideScene(motion: motion, cycle: 6.6, beats: [.init(start: 0.0, label: "① 移到提示的距离并停稳"), .init(start: 0.12, label: "② 点选 E 的开口方向"), .init(start: 0.36, label: "③ 依次换到下一个距离")], keyframe: 0.2)
        }
    }
}

private enum GuidePose {
    case open, blink, left, right
    case lightingOpen, lightingLeft, lightingRight
    case sideFar, sideNear

    var label: String {
        switch self {
        case .open: "双眼注视"
        case .blink: "自然眨眼"
        case .left: "遮左眼"
        case .right: "遮右眼"
        case .lightingOpen: "双眼注视 · 屏幕映光"
        case .lightingLeft: "本人遮左眼 · 屏幕映光"
        case .lightingRight: "本人遮右眼 · 屏幕映光"
        case .sideFar: "自己遮左眼 · 从远处开始"
        case .sideNear: "保持遮眼 · 慢慢移近手机"
        }
    }

    /// 与安卓相同的十二秒教学循环；左右以被测者自身为准，不做镜像。
    static func at(_ motion: GuideMotion, _ phase: Double) -> GuidePose {
        let p = min(0.999999, max(0, phase))
        if motion == .rest { return .blink }
        if motion == .lighting { return .open }
        if motion == .cover || motion == .coverSelf {
            switch Int(p * 12) {
            case 0, 1, 8, 10: return .right
            case 4, 5, 9, 11: return .left
            default: return .open
            }
        }
        if motion == .near {
            if p < 0.06 || (p >= 0.62 && p <= 0.68) || p >= 0.94 { return .open }
            return p < 0.62 ? .left : .right
        }
        if motion == .move { return p >= 0.45 && p <= 0.8 ? .sideNear : .sideFar }
        let times: [Double]
        switch motion {
        case .blink: times = [0.2, 0.5, 0.8]
        case .fva: times = p < 0.68 ? [0.2, 0.45] : []
        case .open: times = [0.92]
        default: times = []
        }
        return times.contains(where: { abs(p - $0) < 0.025 }) ? .blink : .open
    }
}

private enum GuideEyeSide { case left, right }

/// 游戏版全套方法演示趣味动画舞台（小芽浇水 + 月夜萤火虫），与安卓 EyechoGuideStage 保持一致。
private struct GuideStage: View {
    let scene: GuideScene
    let phase: Double
    var height: CGFloat = EyeStyle.guideStageHeight

    var body: some View {
        let p = min(0.9999, max(0, phase))
        let pose = GuidePose.at(scene.motion, p)
        let caption = guideStageBadge(scene.motion, p, pose)
        let isFirefly = scene.motion.isFireflyTheme
        let borderColor = isFirefly ? EyeStyle.Game.Firefly.moonHalo.opacity(0.40) : EyeStyle.Game.Sprout.dropLight.opacity(0.50)
        let pillBg = isFirefly ? EyeStyle.Game.Firefly.skyTop.opacity(0.80) : EyeStyle.Game.Sprout.arrowDark.opacity(0.78)
        let pillText = isFirefly ? EyeStyle.Game.Firefly.glowCore : EyeStyle.Game.Sprout.cloudWhite

        ZStack(alignment: .bottom) {
            Canvas { ctx, size in
                GuideAnimationRenderer.draw(ctx, size: size, motion: scene.motion, p: CGFloat(p), pose: pose)
            }
            Text(caption)
                .font(EyeStyle.chip)
                .foregroundStyle(pillText)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 12).padding(.vertical, 3)
                .background(pillBg, in: EyeStyle.chipShape)
                .overlay { EyeStyle.chipShape.strokeBorder(pillText.opacity(0.24), lineWidth: 1) }
                .padding(.horizontal, 10).padding(.bottom, 7)
        }
        .frame(height: height)
        .frame(maxWidth: .infinity)
        .clipShape(RoundedRectangle(cornerRadius: EyeStyle.radiusLarge, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: EyeStyle.radiusLarge, style: .continuous)
                .strokeBorder(borderColor, lineWidth: 1)
        }
        .accessibilityLabel("趣味动画演示：" + caption)
    }
}

/// 服务页示范与测试引导共用趣味动画舞台，系统减弱动态效果时停在关键帧。
struct GuideFrame: View {
    let motion: GuideMotion
    let phase: Double
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        let scene = GuideScene.scene(motion)
        GuideStage(scene: scene, phase: reduceMotion ? scene.keyframe : phase, height: 190)
    }
}

private func guideStageBadge(_ motion: GuideMotion, _ phase: Double, _ pose: GuidePose) -> String {
    switch motion {
    case .rest: "闭眼休息"
    case .blink: pose == .blink ? "自然眨眼 · 润湿泪膜" : "放松注视 · 自然眨眼"
    case .open: pose == .blink ? "不舒服就眨眼" : "保持睁眼 · 稳住水膜"
    case .lighting: "固定支架 · 正对双眼"
    case .cover, .coverSelf: pose.label
    case .near: "\(pose.label) · 看 E 浇水"
    case .move: phase < 0.68 ? "\(pose.label) · 67→25 cm" : "持续模糊 · 点击确认"
    default:
        let label = guideDiagramLabel(motion, phase)
        return label.isEmpty ? pose.label : label
    }
}

private func guideDiagramLabel(_ motion: GuideMotion, _ p: Double) -> String {
    switch motion {
    case .posture: "调整到提示距离"
    case .acuity: Int(p * 5) == 2 ? "超时后视标变大" : "2 秒内点选方向"
    case .fva: p < 0.68 ? "自然眨眼 · 辨认 E" : "保持睁眼 · 辨认 E"
    case .near: "看 E · 点选方向"
    case .move: p < 0.68 ? "慢慢移近" : "持续模糊时确认"
    case .curve: p < 0.34 ? "67 cm" : p < 0.68 ? "50 cm" : "33 cm"
    case .flip: p < 0.3 || p >= 0.7 ? "远处停稳作答" : "近处停稳作答"
    case .fixate: "看萤火虫 · 头不动"
    case .follow: "跟随萤火虫 · 头不动"
    case .far: "越过手机看 6 米外"
    default: ""
    }
}

// MARK: - 斜视准备卡、戴镜小露插画、看远暖星徽标（与安卓 EyechoGuideStage 一一对应）

/// 斜视自测 33 cm 月夜森林萤火虫准备与校准卡（与干眼/老视的 SproutSetupCardView 双主题呼应）。
struct FireflySetupCardView: View {
    let title: String
    let subtitle: String
    let distanceCm: Double
    let targetCm: Double
    let toleranceCm: Double
    let faceDetected: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let inRange = faceDetected && distanceCm > 0 && abs(distanceCm - targetCm) <= toleranceCm
        let distanceLabel: String = (!faceDetected || distanceCm <= 0)
            ? "当前距离：检测中…"
            : inRange
                ? "当前距离：\(Int(distanceCm)) cm · 已稳定"
                : distanceCm < targetCm - toleranceCm
                    ? "当前距离：\(Int(distanceCm)) cm · 请微调远一点至 \(Int(targetCm)) cm"
                    : "当前距离：\(Int(distanceCm)) cm · 请微调近一点至 \(Int(targetCm)) cm"
        let ratio = (faceDetected && distanceCm > 0) ? min(max((distanceCm - 15) / 36, 0.05), 0.95) : 0.5

        TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: reduceMotion)) { timeline in
            let wave: CGFloat = reduceMotion ? 0.35 : CGFloat(timeline.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 3.2) / 3.2)
            ZStack {
                Canvas { ctx, size in
                    MoonlitFireflyRenderer.backdrop(ctx, size: size, phase: wave)
                }
                VStack(spacing: 10) {
                    Text("←  请保持 \(Int(targetCm))cm  →")
                        .font(EyeStyle.caption)
                        .foregroundStyle(EyeStyle.Game.Firefly.glowCore)
                        .padding(.horizontal, 14).padding(.vertical, 4)
                        .background(EyeStyle.Game.Firefly.skyTop.opacity(0.78), in: EyeStyle.chipShape)
                        .overlay { EyeStyle.chipShape.strokeBorder(EyeStyle.Game.Firefly.moonHalo.opacity(0.32), lineWidth: 1) }

                    VStack(spacing: 10) {
                        Text(title).font(EyeStyle.title).foregroundStyle(EyeStyle.Game.Firefly.glowCore).multilineTextAlignment(.center)
                        Text(subtitle).font(EyeStyle.caption).foregroundStyle(EyeStyle.Game.Firefly.moon).multilineTextAlignment(.center)

                        Canvas { ctx, size in
                            MoonlitFireflyRenderer.backdrop(ctx, size: size, phase: wave)
                            let w = size.width, h = size.height
                            let flyX = w * 0.50 + sin(wave * 2 * .pi) * w * 0.08
                            let flyY = h * 0.34 + cos(wave * 4 * .pi) * h * 0.04
                            let flyPt = CGPoint(x: flyX, y: flyY)
                            MoonlitFireflyRenderer.firefly(
                                ctx, center: flyPt, radius: min(w, h) * 0.13,
                                wingPhase: (wave * 5).truncatingRemainder(dividingBy: 1),
                                glowIntensity: 1.0,
                                tiltDeg: Double(-14 + sin(wave * 2 * .pi) * 10)
                            )
                            GuideAnimationRenderer.drawCuteEyesPair(
                                ctx, center: CGPoint(x: w * 0.50, y: h * 0.74),
                                eyeRadius: min(w, h) * 0.115, lookTarget: flyPt,
                                blinking: false, cover: nil, darkTheme: true
                            )
                        }
                        .frame(height: 142)
                        .clipShape(RoundedRectangle(cornerRadius: EyeStyle.radiusMedium, style: .continuous))
                        .overlay {
                            RoundedRectangle(cornerRadius: EyeStyle.radiusMedium, style: .continuous)
                                .strokeBorder(EyeStyle.Game.Firefly.moonHalo.opacity(0.40), lineWidth: 1.5)
                        }

                        VStack(alignment: .leading, spacing: 6) {
                            HStack {
                                Text("标准测试距离：\(Int(targetCm))cm").font(EyeStyle.chip).foregroundStyle(EyeStyle.Game.Firefly.glowCore)
                                Spacer()
                                Text("正对屏幕 · 露出双眼").font(EyeStyle.caption).foregroundStyle(EyeStyle.Game.Firefly.moon)
                            }
                            GeometryReader { geo in
                                ZStack(alignment: .leading) {
                                    Capsule().fill(EyeStyle.Game.Firefly.moon.opacity(0.22))
                                    Capsule()
                                        .fill(LinearGradient(colors: [EyeStyle.Game.Firefly.glowOuter, inRange ? EyeStyle.Game.Sprout.badgeStable : EyeStyle.Game.Firefly.glowCore], startPoint: .leading, endPoint: .trailing))
                                        .frame(width: geo.size.width * CGFloat(ratio))
                                }
                            }
                            .frame(height: 8)
                            Text(distanceLabel)
                                .font(EyeStyle.bodySecondary)
                                .foregroundStyle(inRange ? EyeStyle.Game.Sprout.leafBright : EyeStyle.Game.Firefly.moon)
                        }
                    }
                    .padding(14)
                    .background(EyeStyle.Game.Firefly.skyTop.opacity(0.76), in: RoundedRectangle(cornerRadius: EyeStyle.radiusLarge, style: .continuous))
                    .overlay {
                        RoundedRectangle(cornerRadius: EyeStyle.radiusLarge, style: .continuous)
                            .strokeBorder(EyeStyle.Game.Firefly.moonHalo.opacity(0.26), lineWidth: 1)
                    }
                }
                .padding(14)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: EyeStyle.radiusHero, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: EyeStyle.radiusHero, style: .continuous)
                .strokeBorder(EyeStyle.Game.Firefly.moonHalo.opacity(0.45), lineWidth: 1)
        }
    }
}

/// 老视戴镜确认页：阳光湖景中戴小圆眼镜的水滴精灵「小露」+ 花盆小芽趣味插画。
struct SproutGlassesMascotCardView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: reduceMotion)) { timeline in
            let wave: CGFloat = reduceMotion ? 0.25 : CGFloat(timeline.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 2.8) / 2.8)
            Canvas { ctx, size in
                let w = size.width, h = size.height, u = min(w, h)
                SproutSceneRenderer.drawBackdrop(ctx, size: size, mode: .scenic, wave: wave)
                SproutSceneRenderer.drawPottedSprout(ctx, center: CGPoint(x: w * 0.30, y: h * 0.88),
                                                     scale: min(max(u / 145, 0.65), 1.15), growthStage: 2, wilted: false, wave: wave, bounce: 1)
                let bob = sin(wave * 2 * .pi) * 3.5
                let mascotC = CGPoint(x: w * 0.66, y: h * 0.64 + bob)
                let mascotR = u * 0.27
                SproutSceneRenderer.drawDropletMascot(ctx, center: mascotC, radius: mascotR, cheer: 0.12)
                let lensR = mascotR * 0.23
                let leftLens = CGPoint(x: mascotC.x - mascotR * 0.25, y: mascotC.y + mascotR * 0.22)
                let rightLens = CGPoint(x: mascotC.x + mascotR * 0.25, y: mascotC.y + mascotR * 0.22)
                for lens in [leftLens, rightLens] {
                    let rect = CGRect(x: lens.x - lensR, y: lens.y - lensR, width: lensR * 2, height: lensR * 2)
                    ctx.fill(Path(ellipseIn: rect), with: .color(EyeStyle.Game.Sprout.dropLight.opacity(0.30)))
                    ctx.stroke(Path(ellipseIn: rect), with: .color(EyeStyle.Game.Sprout.arrowDark), lineWidth: mascotR * 0.08)
                }
                var bridge = Path()
                bridge.move(to: CGPoint(x: leftLens.x + lensR * 0.9, y: leftLens.y))
                bridge.addLine(to: CGPoint(x: rightLens.x - lensR * 0.9, y: rightLens.y))
                ctx.stroke(bridge, with: .color(EyeStyle.Game.Sprout.arrowDark), style: StrokeStyle(lineWidth: mascotR * 0.075, lineCap: .round))
                let glint = 0.55 + 0.45 * sin(wave * 2 * .pi)
                SproutSceneRenderer.drawFourPointStar(ctx, center: CGPoint(x: rightLens.x + lensR * 0.55, y: rightLens.y - lensR * 0.55),
                                                      radius: mascotR * 0.16 * glint, color: EyeStyle.Game.Sprout.daisyCenter)
            }
        }
        .frame(width: 216, height: 128)
        .clipShape(RoundedRectangle(cornerRadius: EyeStyle.radiusLarge, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: EyeStyle.radiusLarge, style: .continuous)
                .strokeBorder(EyeStyle.Game.Sprout.dropLight.opacity(0.65), lineWidth: 1.5)
        }
        .accessibilityLabel("戴着小圆眼镜的小露与小芽")
    }
}

/// 斜视看远舞台顶部微动画徽标：静谧月夜远星 + 远山轮廓。
struct FarStarFireflyBadgeView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 24.0, paused: reduceMotion)) { timeline in
            let pulse: CGFloat = reduceMotion ? 0.5 : CGFloat(timeline.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 2.2) / 2.2)
            Canvas { ctx, size in
                let w = size.width, h = size.height
                let glow = 0.72 + 0.28 * sin(pulse * 2 * .pi)
                let starC = CGPoint(x: w * 0.5, y: h * 0.30)
                let gr = h * 0.42
                ctx.fill(Path(ellipseIn: CGRect(x: starC.x - gr, y: starC.y - gr, width: gr * 2, height: gr * 2)),
                         with: .radialGradient(Gradient(colors: [EyeStyle.Game.Firefly.glowCore.opacity(0.75 * Double(glow)), EyeStyle.Game.Firefly.glowMid.opacity(0.25 * Double(glow)), .clear]),
                                               center: starC, startRadius: 0, endRadius: gr))
                SproutSceneRenderer.drawFourPointStar(ctx, center: starC, radius: h * 0.19 * glow, color: EyeStyle.Game.Firefly.glowCore)
                ctx.fill(Path(ellipseIn: CGRect(x: w * 0.22 - 2.2, y: h * 0.48 - 2.2, width: 4.4, height: 4.4)), with: .color(EyeStyle.Game.Firefly.moon.opacity(0.65)))
                ctx.fill(Path(ellipseIn: CGRect(x: w * 0.78 - 1.8, y: h * 0.44 - 1.8, width: 3.6, height: 3.6)), with: .color(EyeStyle.Game.Firefly.moon.opacity(0.55)))
                var ridge = Path()
                ridge.move(to: CGPoint(x: w * 0.10, y: h * 0.88))
                ridge.addQuadCurve(to: CGPoint(x: w * 0.52, y: h * 0.76), control: CGPoint(x: w * 0.34, y: h * 0.58))
                ridge.addQuadCurve(to: CGPoint(x: w * 0.90, y: h * 0.88), control: CGPoint(x: w * 0.72, y: h * 0.54))
                ctx.stroke(ridge, with: .color(EyeStyle.Game.Firefly.creekHighlight.opacity(0.45)), style: StrokeStyle(lineWidth: 2, lineCap: .round))
            }
        }
        .frame(width: 104, height: 64)
        .accessibilityLabel("越过手机望向远处暖星")
    }
}

// MARK: - 16 种趣味情景演示画布渲染器

private enum GuideAnimationRenderer {
    static func draw(_ ctx: GraphicsContext, size: CGSize, motion: GuideMotion, p: CGFloat, pose: GuidePose) {
        switch motion {
        case .acuity, .fva, .near, .curve, .flip:
            drawSproutWatering(ctx, size: size, motion: motion, p: p, pose: pose)
        case .move:
            drawSproutMove(ctx, size: size, p: p)
        case .blink, .open:
            drawSproutBlinkOpen(ctx, size: size, motion: motion, p: p, pose: pose)
        case .rest:
            drawRest(ctx, size: size, p: p)
        case .posture:
            drawPosture(ctx, size: size, p: p)
        case .fixate, .lighting:
            drawFireflyFixate(ctx, size: size, motion: motion, p: p)
        case .far:
            drawFireflyFar(ctx, size: size, p: p)
        case .cover, .coverSelf:
            drawFireflyCover(ctx, size: size, p: p, pose: pose)
        case .follow:
            drawFireflyFollow(ctx, size: size, p: p)
        }
    }

    private static func drawSproutWatering(_ ctx: GraphicsContext, size: CGSize, motion: GuideMotion, p: CGFloat, pose: GuidePose) {
        let w = size.width, h = size.height, u = min(w, h)
        SproutSceneRenderer.drawBackdrop(ctx, size: size, mode: .scenic, wave: p)

        let step = min(4, max(0, Int(p * 5)))
        let localP = p * 5 - CGFloat(step)
        let direction = [0, 3, 2, 1, 0][step]
        let isTimeoutEvap = motion == .acuity && step == 2
        let watering = !isTimeoutEvap && localP >= 0.28 && localP <= 0.95
        let wt: CGFloat = watering ? min(1, max(0, (localP - 0.28) / 0.67)) : 1
        let growthStage = isTimeoutEvap ? 1 : (step >= 4 ? 4 : step >= 3 ? 3 : step >= 1 ? 2 : 1)
        let bounce: CGFloat = (wt >= 0.45 && wt <= 0.95) ? (1 + 0.14 * sin(((wt - 0.45) / 0.50) * .pi)) : 1

        let sproutC = CGPoint(x: w * 0.20, y: h * 0.80)
        let sproutScale = min(max(u / 230, 0.65), 1.05)
        SproutSceneRenderer.drawPottedSprout(ctx, center: sproutC, scale: sproutScale, growthStage: growthStage, wilted: isTimeoutEvap, wave: p, bounce: bounce)

        let cheer: CGFloat = wt < 0.65 ? sin((wt / 0.65) * .pi) : 0
        let mascotC = CGPoint(x: w * 0.82 - cheer * u * 0.05, y: h * 0.73 - cheer * u * 0.09 + sin(p * 2 * .pi) * 3)
        SproutSceneRenderer.drawDropletMascot(ctx, center: mascotC, radius: u * 0.135, cheer: cheer)

        let dialC = CGPoint(x: w * 0.50, y: h * 0.41)
        let eEdge: CGFloat = {
            if motion == .acuity && step == 1 { return u * 0.14 }
            if motion == .acuity && step == 2 { return u * 0.23 }
            if motion == .curve { return u * (p < 0.34 ? 0.22 : p < 0.68 ? 0.18 : 0.15) }
            if motion == .flip { return u * ((p < 0.3 || p >= 0.7) ? 0.16 : 0.21) }
            return u * 0.19
        }()
        ctx.fill(RoundedRectangle(cornerRadius: u * 0.05).path(in: CGRect(x: dialC.x - u * 0.16, y: dialC.y - u * 0.16, width: u * 0.32, height: u * 0.32)),
                 with: .color(EyeStyle.Game.Sprout.arrowDark.opacity(0.20)))
        Cartoon.tumblingE(ctx, color: EyeStyle.Game.Sprout.cloudWhite, center: dialC, edge: eEdge, direction: direction)

        let arrowDist = u * 0.25, arrowSize = u * 0.14
        let dirs: [CGPoint] = [CGPoint(x: 1, y: 0), CGPoint(x: 0, y: 1), CGPoint(x: -1, y: 0), CGPoint(x: 0, y: -1)]
        for (idx, vec) in dirs.enumerated() {
            let active = !isTimeoutEvap && idx == direction && localP >= 0.18
            let pulse: CGFloat = active ? (1 + 0.16 * max(0, sin(localP * 2 * .pi))) : 1
            drawSolidArrow(ctx, center: CGPoint(x: dialC.x + vec.x * arrowDist, y: dialC.y + vec.y * arrowDist),
                           size: arrowSize * pulse, direction: idx, highlighted: active)
        }

        let targetSoil = CGPoint(x: sproutC.x, y: sproutC.y - 24 * sproutScale)
        if watering {
            let startPt = CGPoint(x: mascotC.x - u * 0.09, y: mascotC.y - u * 0.08)
            let ctrlPt = CGPoint(x: (startPt.x + targetSoil.x) * 0.5, y: h * 0.14)
            for i in 0..<4 {
                let lt = min(1, max(0, (wt - CGFloat(i) * 0.07) / 0.52))
                if lt > 0.02 && lt < 0.98 {
                    let inv = 1 - lt
                    let px = inv * inv * startPt.x + 2 * inv * lt * ctrlPt.x + lt * lt * targetSoil.x
                    let py = inv * inv * startPt.y + 2 * inv * lt * ctrlPt.y + lt * lt * targetSoil.y
                    SproutSceneRenderer.drawWaterDrop(ctx, center: CGPoint(x: px, y: py), radius: u * (0.042 - CGFloat(i) * 0.004))
                }
            }
            if wt >= 0.42 && wt <= 0.98 {
                let sp = min(1, max(0, (wt - 0.42) / 0.56))
                let a = Double(max(0, 1 - sp))
                for k in 0..<6 {
                    let ang = -CGFloat.pi * (0.14 + CGFloat(k) * 0.14)
                    let dist = u * (0.04 + sp * 0.16)
                    let sr = u * 0.018 * (1 - sp * 0.4)
                    let sx = targetSoil.x + cos(ang) * dist, sy = targetSoil.y + sin(ang) * dist
                    ctx.fill(Path(ellipseIn: CGRect(x: sx - sr, y: sy - sr, width: sr * 2, height: sr * 2)),
                             with: .color(EyeStyle.Game.Sprout.dropLight.opacity(a)))
                }
                SproutSceneRenderer.drawFourPointStar(ctx, center: CGPoint(x: targetSoil.x + u * 0.11, y: targetSoil.y - u * 0.18 - sp * u * 0.06),
                                                      radius: u * 0.048 * sin(sp * .pi), color: EyeStyle.Game.Sprout.daisyCenter)
            }
        } else if isTimeoutEvap {
            let a = Double(min(1, max(0.25, 1 - localP)))
            for i in 0..<3 {
                let sx = targetSoil.x + CGFloat(i - 1) * u * 0.06
                let sy = targetSoil.y - u * 0.14 - localP * u * 0.14
                let sway = sin((localP * 4 + CGFloat(i)) * .pi) * u * 0.025
                var vapor = Path()
                vapor.move(to: CGPoint(x: sx, y: sy + u * 0.06))
                vapor.addQuadCurve(to: CGPoint(x: sx - sway * 0.6, y: sy - u * 0.08), control: CGPoint(x: sx + sway, y: sy))
                ctx.stroke(vapor, with: .color(EyeStyle.Game.Sprout.cloudWhite.opacity(a * 0.85)), style: StrokeStyle(lineWidth: u * 0.018, lineCap: .round))
            }
        }

        switch motion {
        case .near:
            ctx.fill(RoundedRectangle(cornerRadius: u * 0.06).path(in: CGRect(x: w * 0.04, y: h * 0.07, width: w * 0.25, height: h * 0.25)),
                     with: .color(EyeStyle.Game.Sprout.arrowDark.opacity(0.56)))
            let coverSide: GuideEyeSide? = pose == .left ? .left : pose == .right ? .right : nil
            drawCuteEyesPair(ctx, center: CGPoint(x: w * 0.165, y: h * 0.195), eyeRadius: u * 0.060,
                             lookTarget: dialC, blinking: false, cover: coverSide, darkTheme: true)
        case .fva:
            let keepOpen = p >= 0.68
            ctx.fill(RoundedRectangle(cornerRadius: u * 0.06).path(in: CGRect(x: w * 0.04, y: h * 0.07, width: w * 0.25, height: h * 0.25)),
                     with: .color((keepOpen ? EyeStyle.Game.Sprout.dropDeep : EyeStyle.Game.Sprout.arrowDark).opacity(0.62)))
            drawCuteEyesPair(ctx, center: CGPoint(x: w * 0.165, y: h * 0.195), eyeRadius: u * 0.058,
                             lookTarget: dialC, blinking: pose == .blink, cover: nil, darkTheme: true, tearShield: keepOpen)
        case .curve:
            let activeIdx = p < 0.34 ? 0 : p < 0.68 ? 1 : 2
            let barY = h * 0.11, x0 = w * 0.14, x2 = w * 0.34
            var track = Path(); track.move(to: CGPoint(x: x0, y: barY)); track.addLine(to: CGPoint(x: x2, y: barY))
            ctx.stroke(track, with: .color(EyeStyle.Game.Sprout.cloudWhite.opacity(0.5)), style: StrokeStyle(lineWidth: u * 0.015, lineCap: .round))
            for i in 0..<3 {
                let cx = x0 + (x2 - x0) * (CGFloat(i) / 2)
                let r = i == activeIdx ? u * 0.036 : u * 0.022
                ctx.fill(Path(ellipseIn: CGRect(x: cx - r, y: barY - r, width: r * 2, height: r * 2)),
                         with: .color(i == activeIdx ? EyeStyle.Game.Sprout.leafBright : EyeStyle.Game.Sprout.cloudWhite.opacity(0.65)))
            }
        case .flip:
            let near = p >= 0.30 && p <= 0.70
            let bx0 = w * 0.69, bx1 = w * 0.93, by = h * 0.14
            var track = Path(); track.move(to: CGPoint(x: bx0, y: by)); track.addLine(to: CGPoint(x: bx1, y: by))
            ctx.stroke(track, with: .color(EyeStyle.Game.Sprout.cloudWhite.opacity(0.65)), style: StrokeStyle(lineWidth: u * 0.016, lineCap: .round))
            let r1 = u * 0.038, r2 = u * 0.020
            let ax = near ? bx1 : bx0, ix = near ? bx0 : bx1
            ctx.fill(Path(ellipseIn: CGRect(x: ax - r1, y: by - r1, width: r1 * 2, height: r1 * 2)), with: .color(EyeStyle.Game.Sprout.daisyCenter))
            ctx.fill(Path(ellipseIn: CGRect(x: ix - r2, y: by - r2, width: r2 * 2, height: r2 * 2)), with: .color(EyeStyle.Game.Sprout.cloudWhite))
        default: break
        }
    }

    private static func drawSproutMove(_ ctx: GraphicsContext, size: CGSize, p: CGFloat) {
        let w = size.width, h = size.height, u = min(w, h)
        SproutSceneRenderer.drawBackdrop(ctx, size: size, mode: .scenic, wave: p)
        let approach: CGFloat = p < 0.15 ? 0 : p < 0.68 ? (p - 0.15) / 0.53 : p < 0.85 ? 1 : max(0, (1 - p) / 0.15)
        let blurred = p >= 0.64 && p <= 0.88

        let barL = w * 0.14, barW = w * 0.72, barY = h * 0.11
        ctx.fill(RoundedRectangle(cornerRadius: u * 0.032).path(in: CGRect(x: barL - 6, y: barY - u * 0.032, width: barW + 12, height: u * 0.064)),
                 with: .color(EyeStyle.Game.Sprout.arrowDark.opacity(0.55)))
        ctx.fill(RoundedRectangle(cornerRadius: u * 0.016).path(in: CGRect(x: barL, y: barY - u * 0.016, width: barW * (0.08 + approach * 0.92), height: u * 0.032)),
                 with: .color(EyeStyle.Game.Sprout.leafBright))

        let sproutC = CGPoint(x: w * 0.50, y: h * 0.77)
        SproutSceneRenderer.drawLakeIslandSprout(ctx, center: sproutC, scale: min(max(u / 230, 0.65), 1.0), growthStage: 2, wilted: false, wave: p, bounce: 1)
        for i in 0..<3 {
            let t = (p * 2 + CGFloat(i) * 0.33).truncatingRemainder(dividingBy: 1)
            let dx = CGFloat(i - 1) * u * 0.14 * (1 - t)
            let dy = -u * 0.20 * (1 - t) - u * 0.05
            SproutSceneRenderer.drawWaterDrop(ctx, center: CGPoint(x: sproutC.x + dx, y: sproutC.y + dy), radius: u * 0.028 * (1 - t * 0.3))
        }

        let boxC = CGPoint(x: w * 0.50, y: h * 0.42)
        let bh = u * (0.17 + approach * 0.06), cLen = u * 0.075
        let frameCol = blurred ? EyeStyle.Game.Sprout.daisyCenter : EyeStyle.Game.Sprout.cloudWhite
        var br = Path()
        br.move(to: CGPoint(x: boxC.x - bh, y: boxC.y - bh + cLen)); br.addLine(to: CGPoint(x: boxC.x - bh, y: boxC.y - bh)); br.addLine(to: CGPoint(x: boxC.x - bh + cLen, y: boxC.y - bh))
        br.move(to: CGPoint(x: boxC.x + bh - cLen, y: boxC.y - bh)); br.addLine(to: CGPoint(x: boxC.x + bh, y: boxC.y - bh)); br.addLine(to: CGPoint(x: boxC.x + bh, y: boxC.y - bh + cLen))
        br.move(to: CGPoint(x: boxC.x - bh, y: boxC.y + bh - cLen)); br.addLine(to: CGPoint(x: boxC.x - bh, y: boxC.y + bh)); br.addLine(to: CGPoint(x: boxC.x - bh + cLen, y: boxC.y + bh))
        br.move(to: CGPoint(x: boxC.x + bh - cLen, y: boxC.y + bh)); br.addLine(to: CGPoint(x: boxC.x + bh, y: boxC.y + bh)); br.addLine(to: CGPoint(x: boxC.x + bh, y: boxC.y + bh - cLen))
        ctx.stroke(br, with: .color(frameCol), style: StrokeStyle(lineWidth: u * 0.016, lineCap: .round))

        let eEdge = u * (0.16 + approach * 0.11)
        if blurred {
            Cartoon.tumblingE(ctx, color: EyeStyle.Game.Sprout.cloudWhite.opacity(0.28), center: CGPoint(x: boxC.x - 3, y: boxC.y), edge: eEdge, direction: 0)
            Cartoon.tumblingE(ctx, color: EyeStyle.Game.Sprout.cloudWhite.opacity(0.28), center: CGPoint(x: boxC.x + 3, y: boxC.y), edge: eEdge, direction: 0)
        }
        Cartoon.tumblingE(ctx, color: EyeStyle.Game.Sprout.cloudWhite.opacity(blurred ? 0.72 : 1), center: boxC, edge: eEdge, direction: 0)

        ctx.fill(RoundedRectangle(cornerRadius: u * 0.05).path(in: CGRect(x: w * 0.05, y: h * 0.30, width: w * 0.22, height: h * 0.24)),
                 with: .color(EyeStyle.Game.Sprout.arrowDark.opacity(0.54)))
        drawCuteEyesPair(ctx, center: CGPoint(x: w * 0.16, y: h * 0.42), eyeRadius: u * 0.055,
                         lookTarget: boxC, blinking: false, cover: .left, darkTheme: true)
        SproutSceneRenderer.drawDropletMascot(ctx, center: CGPoint(x: w * (0.86 - approach * 0.08), y: h * 0.70), radius: u * 0.125, cheer: blurred ? 0.45 : 0.08)
    }

    private static func drawSproutBlinkOpen(_ ctx: GraphicsContext, size: CGSize, motion: GuideMotion, p: CGFloat, pose: GuidePose) {
        let w = size.width, h = size.height, u = min(w, h)
        SproutSceneRenderer.drawBackdrop(ctx, size: size, mode: .scenic, wave: p)
        let blinking = pose == .blink
        let isOpenHold = motion == .open && !blinking
        let eyesC = CGPoint(x: w * 0.50, y: h * 0.37)

        ctx.fill(RoundedRectangle(cornerRadius: u * 0.21).path(in: CGRect(x: eyesC.x - u * 0.42, y: eyesC.y - u * 0.21, width: u * 0.84, height: u * 0.42)),
                 with: .color(EyeStyle.Game.Sprout.arrowDark.opacity(0.42)))
        drawCuteEyesPair(ctx, center: eyesC, eyeRadius: u * 0.125, lookTarget: CGPoint(x: eyesC.x, y: eyesC.y + u * 0.02),
                         blinking: blinking, cover: nil, darkTheme: true, tearShield: isOpenHold)
        if blinking && motion == .blink {
            ctx.stroke(Path(ellipseIn: CGRect(x: eyesC.x - u * 0.46, y: eyesC.y - u * 0.24, width: u * 0.92, height: u * 0.48)),
                       with: .color(EyeStyle.Game.Sprout.dropLight.opacity(0.90)), lineWidth: u * 0.020)
        }
        SproutSceneRenderer.drawPottedSprout(ctx, center: CGPoint(x: w * 0.24, y: h * 0.81),
                                             scale: min(max(u / 235, 0.65), 1.0), growthStage: isOpenHold ? 3 : 2, wilted: false, wave: p, bounce: blinking ? 1.09 : 1)
        SproutSceneRenderer.drawDropletMascot(ctx, center: CGPoint(x: w * 0.77, y: h * 0.74 + sin(p * 2 * .pi) * 4),
                                              radius: u * 0.13, cheer: blinking ? 0.35 : 0.1)
        SproutSceneRenderer.drawWaterDrop(ctx, center: CGPoint(x: w * 0.50, y: h * 0.56 + sin(p * 4 * .pi) * u * 0.03), radius: u * 0.042)
    }

    private static func drawRest(_ ctx: GraphicsContext, size: CGSize, p: CGFloat) {
        let w = size.width, h = size.height, u = min(w, h)
        MoonlitFireflyRenderer.backdrop(ctx, size: size, phase: p)
        let eyesC = CGPoint(x: w * 0.50, y: h * 0.40)
        let breath = 0.85 + 0.15 * sin(p * 2 * .pi)
        let gr = u * 0.46 * breath
        ctx.fill(Path(ellipseIn: CGRect(x: eyesC.x - gr, y: eyesC.y - gr, width: gr * 2, height: gr * 2)),
                 with: .radialGradient(Gradient(colors: [EyeStyle.Game.Firefly.glowMid.opacity(0.28 * Double(breath)), EyeStyle.Game.Firefly.glowOuter.opacity(0.10 * Double(breath)), .clear]),
                                       center: eyesC, startRadius: 0, endRadius: gr))
        drawCuteEyesPair(ctx, center: eyesC, eyeRadius: u * 0.135, lookTarget: eyesC, blinking: true, cover: nil, darkTheme: true)
        for i in 0..<5 {
            let sx = w * (0.18 + CGFloat(i) * 0.16)
            let sy = h * (0.26 + 0.08 * sin((p * 2 + CGFloat(i) * 0.5) * .pi))
            SproutSceneRenderer.drawFourPointStar(ctx, center: CGPoint(x: sx, y: sy), radius: u * 0.028 * breath, color: EyeStyle.Game.Firefly.glowCore.opacity(0.72))
        }
        SproutSceneRenderer.drawPottedSprout(ctx, center: CGPoint(x: w * 0.24, y: h * 0.81),
                                             scale: min(max(u / 250, 0.6), 0.92), growthStage: 2, wilted: false, wave: p * 0.5, bounce: 1)
    }

    private static func drawPosture(_ ctx: GraphicsContext, size: CGSize, p: CGFloat) {
        let w = size.width, h = size.height, u = min(w, h)
        SproutSceneRenderer.drawBackdrop(ctx, size: size, mode: .scenic, wave: p)
        let aligned = p >= 0.48
        let drift: CGFloat = aligned ? 0 : sin((1 - p / 0.48) * .pi) * u * 0.14
        let ringC = CGPoint(x: w * 0.50, y: h * 0.39)
        let ringR = u * 0.24
        ctx.fill(Path(ellipseIn: CGRect(x: ringC.x - ringR, y: ringC.y - ringR, width: ringR * 2, height: ringR * 2)),
                 with: .color(EyeStyle.Game.Sprout.arrowDark.opacity(0.40)))
        ctx.stroke(Path(ellipseIn: CGRect(x: ringC.x - ringR, y: ringC.y - ringR, width: ringR * 2, height: ringR * 2)),
                   with: .color(aligned ? EyeStyle.Game.Sprout.badgeStable : EyeStyle.Game.Sprout.cloudWhite.opacity(0.75)), lineWidth: u * 0.022)
        drawCuteEyesPair(ctx, center: CGPoint(x: ringC.x + drift, y: ringC.y), eyeRadius: u * 0.095, lookTarget: ringC, blinking: false, cover: nil, darkTheme: true)
        if aligned {
            let bc = CGPoint(x: ringC.x + ringR * 0.76, y: ringC.y - ringR * 0.76), br = u * 0.078
            ctx.fill(Path(ellipseIn: CGRect(x: bc.x - br, y: bc.y - br, width: br * 2, height: br * 2)), with: .color(EyeStyle.Game.Sprout.badgeStable))
            var ck = Path()
            ck.move(to: CGPoint(x: bc.x - u * 0.035, y: bc.y))
            ck.addLine(to: CGPoint(x: bc.x - u * 0.008, y: bc.y + u * 0.028))
            ck.addLine(to: CGPoint(x: bc.x + u * 0.040, y: bc.y - u * 0.028))
            ctx.stroke(ck, with: .color(EyeStyle.Game.Sprout.cloudWhite), style: StrokeStyle(lineWidth: u * 0.020, lineCap: .round, lineJoin: .round))
        }
        SproutSceneRenderer.drawPottedSprout(ctx, center: CGPoint(x: w * 0.21, y: h * 0.81), scale: min(max(u / 235, 0.62), 1.0), growthStage: 2, wilted: false, wave: p, bounce: 1)
        SproutSceneRenderer.drawDropletMascot(ctx, center: CGPoint(x: w * 0.80, y: h * 0.74 + sin(p * 2 * .pi) * 3), radius: u * 0.13, cheer: aligned ? 0.3 : 0)
    }

    private static func drawFireflyFixate(_ ctx: GraphicsContext, size: CGSize, motion: GuideMotion, p: CGFloat) {
        let w = size.width, h = size.height, u = min(w, h)
        MoonlitFireflyRenderer.backdrop(ctx, size: size, phase: p)
        let flyC = CGPoint(x: w * 0.50, y: h * 0.28 + sin(p * 2 * .pi) * u * 0.025)
        MoonlitFireflyRenderer.firefly(ctx, center: flyC, radius: u * 0.125, wingPhase: (p * 6).truncatingRemainder(dividingBy: 1),
                                       glowIntensity: Double(0.85 + 0.15 * sin(p * 4 * .pi)), tiltDeg: -14)
        var dash = Path()
        dash.move(to: CGPoint(x: w * 0.50, y: flyC.y + u * 0.14))
        dash.addLine(to: CGPoint(x: w * 0.50, y: h * 0.54))
        ctx.stroke(dash, with: .color(EyeStyle.Game.Firefly.glowMid.opacity(0.42)), style: StrokeStyle(lineWidth: u * 0.012, dash: [8, 6]))
        drawCuteEyesPair(ctx, center: CGPoint(x: w * 0.50, y: h * 0.64), eyeRadius: u * 0.115, lookTarget: flyC, blinking: false, cover: nil, darkTheme: true)
        if motion == .lighting {
            var stand = Path()
            stand.move(to: CGPoint(x: w * 0.22, y: h * 0.76)); stand.addLine(to: CGPoint(x: w * 0.28, y: h * 0.54))
            stand.move(to: CGPoint(x: w * 0.78, y: h * 0.76)); stand.addLine(to: CGPoint(x: w * 0.72, y: h * 0.54))
            ctx.stroke(stand, with: .color(EyeStyle.Game.Firefly.moonHalo.opacity(0.55)), style: StrokeStyle(lineWidth: u * 0.018, lineCap: .round))
        }
    }

    private static func drawFireflyFar(_ ctx: GraphicsContext, size: CGSize, p: CGFloat) {
        let w = size.width, h = size.height, u = min(w, h)
        MoonlitFireflyRenderer.backdrop(ctx, size: size, phase: p)
        let starC = CGPoint(x: w * 0.50, y: h * 0.20)
        let glow = 0.78 + 0.22 * sin(p * 2 * .pi)
        let gr = u * 0.26
        ctx.fill(Path(ellipseIn: CGRect(x: starC.x - gr, y: starC.y - gr, width: gr * 2, height: gr * 2)),
                 with: .radialGradient(Gradient(colors: [EyeStyle.Game.Firefly.glowCore.opacity(0.85 * Double(glow)), EyeStyle.Game.Firefly.glowMid.opacity(0.35 * Double(glow)), .clear]),
                                       center: starC, startRadius: 0, endRadius: gr))
        SproutSceneRenderer.drawFourPointStar(ctx, center: starC, radius: u * 0.11 * glow, color: EyeStyle.Game.Firefly.glowCore)

        let phoneW = u * 0.20, phoneH = u * 0.28
        let phoneRect = CGRect(x: w * 0.72 - phoneW * 0.5, y: h * 0.44, width: phoneW, height: phoneH)
        ctx.fill(RoundedRectangle(cornerRadius: u * 0.03).path(in: phoneRect), with: .color(EyeStyle.Game.Firefly.skyTop.opacity(0.82)))
        ctx.stroke(RoundedRectangle(cornerRadius: u * 0.03).path(in: phoneRect), with: .color(EyeStyle.Game.Firefly.moonHalo.opacity(0.65)), lineWidth: u * 0.014)

        let eyesC = CGPoint(x: w * 0.38, y: h * 0.64)
        var ray = Path()
        ray.move(to: CGPoint(x: eyesC.x, y: eyesC.y - u * 0.13))
        ray.addLine(to: CGPoint(x: starC.x, y: starC.y + u * 0.11))
        ctx.stroke(ray, with: .color(EyeStyle.Game.Firefly.glowCore.opacity(0.70)), style: StrokeStyle(lineWidth: u * 0.016, lineCap: .round, dash: [10, 8]))
        drawCuteEyesPair(ctx, center: eyesC, eyeRadius: u * 0.11, lookTarget: starC, blinking: false, cover: nil, darkTheme: true)
    }

    private static func drawFireflyCover(_ ctx: GraphicsContext, size: CGSize, p: CGFloat, pose: GuidePose) {
        let w = size.width, h = size.height, u = min(w, h)
        MoonlitFireflyRenderer.backdrop(ctx, size: size, phase: p)
        let flyC = CGPoint(x: w * 0.50, y: h * 0.24 + sin(p * 4 * .pi) * u * 0.02)
        MoonlitFireflyRenderer.firefly(ctx, center: flyC, radius: u * 0.11, wingPhase: (p * 12).truncatingRemainder(dividingBy: 1), glowIntensity: 1, tiltDeg: -14)
        let coverSide: GuideEyeSide? = pose == .right ? .right : pose == .left ? .left : nil
        drawCuteEyesPair(ctx, center: CGPoint(x: w * 0.50, y: h * 0.60), eyeRadius: u * 0.135, lookTarget: flyC, blinking: false, cover: coverSide, darkTheme: true)
    }

    private static func drawFireflyFollow(_ ctx: GraphicsContext, size: CGSize, p: CGFloat) {
        let w = size.width, h = size.height, u = min(w, h)
        MoonlitFireflyRenderer.backdrop(ctx, size: size, phase: p)
        func posAt(_ tRaw: CGFloat) -> CGPoint {
            let t = (tRaw + 1).truncatingRemainder(dividingBy: 1)
            let twoPi: CGFloat = 2 * .pi
            let rel: CGPoint
            if t < 0.36 {
                rel = CGPoint(x: 0.32 * sin(t / 0.36 * twoPi), y: 0)
            } else if t < 0.72 {
                rel = CGPoint(x: 0, y: 0.22 * sin((t - 0.36) / 0.36 * twoPi))
            } else {
                let seg = (t - 0.72) / 0.28
                let wave = seg < 0.5 ? seg * 4 - 1 : 3 - seg * 4
                rel = CGPoint(x: 0.28 * wave, y: -0.18 * wave)
            }
            return CGPoint(x: w * (0.50 + rel.x), y: h * (0.34 + rel.y))
        }
        let trailPts = (1...7).reversed().map { posAt(p - CGFloat($0) * 0.016) } + [posAt(p)]
        let flyC = posAt(p)
        let prevC = posAt(p - 0.015)
        let tilt = Double(min(26, max(-26, (flyC.x - prevC.x) * 1.4)))
        MoonlitFireflyRenderer.trail(ctx, points: trailPts, radius: u * 0.11)
        MoonlitFireflyRenderer.firefly(ctx, center: flyC, radius: u * 0.115, wingPhase: (p * 10).truncatingRemainder(dividingBy: 1), glowIntensity: 1, tiltDeg: tilt)
        drawCuteEyesPair(ctx, center: CGPoint(x: w * 0.50, y: h * 0.69), eyeRadius: u * 0.095, lookTarget: flyC, blinking: false, cover: nil, darkTheme: true)
    }

    private static func drawSolidArrow(_ ctx: GraphicsContext, center: CGPoint, size: CGFloat, direction: Int, highlighted: Bool) {
        Cartoon.rotated(ctx, Double(direction) * 90, around: center) { layer in
            let left = center.x - size * 0.5, top = center.y - size * 0.5
            var arrow = Path()
            arrow.move(to: CGPoint(x: left + size * 0.12, y: top + size * 0.34))
            arrow.addLine(to: CGPoint(x: left + size * 0.50, y: top + size * 0.34))
            arrow.addLine(to: CGPoint(x: left + size * 0.50, y: top + size * 0.14))
            arrow.addLine(to: CGPoint(x: left + size * 0.90, y: top + size * 0.50))
            arrow.addLine(to: CGPoint(x: left + size * 0.50, y: top + size * 0.86))
            arrow.addLine(to: CGPoint(x: left + size * 0.50, y: top + size * 0.66))
            arrow.addLine(to: CGPoint(x: left + size * 0.12, y: top + size * 0.66))
            arrow.closeSubpath()
            layer.fill(arrow, with: .color(highlighted ? EyeStyle.Game.Sprout.dropBlue : EyeStyle.Game.Sprout.cloudWhite.opacity(0.94)))
            layer.stroke(arrow, with: .color(highlighted ? EyeStyle.Game.Sprout.cloudWhite : EyeStyle.Game.Sprout.arrowDark.opacity(0.28)),
                         style: StrokeStyle(lineWidth: max(1.5, size * 0.08), lineJoin: .round))
        }
    }

    static func drawCuteEyesPair(
        _ ctx: GraphicsContext, center: CGPoint, eyeRadius: CGFloat, lookTarget: CGPoint,
        blinking: Bool, cover: GuideEyeSide?, darkTheme: Bool, tearShield: Bool = false
    ) {
        let gap = eyeRadius * 1.48
        let rightEyeC = CGPoint(x: center.x - gap, y: center.y)
        let leftEyeC = CGPoint(x: center.x + gap, y: center.y)

        for (ec, which): (CGPoint, GuideEyeSide) in [(rightEyeC, .right), (leftEyeC, .left)] {
            if tearShield {
                let sr = eyeRadius * 1.32
                ctx.stroke(Path(ellipseIn: CGRect(x: ec.x - sr, y: ec.y - sr, width: sr * 2, height: sr * 2)),
                           with: .color(EyeStyle.Game.Sprout.dropLight.opacity(0.45)), lineWidth: eyeRadius * 0.16)
            }
            if blinking {
                var lid = Path()
                lid.move(to: CGPoint(x: ec.x - eyeRadius * 0.85, y: ec.y))
                lid.addQuadCurve(to: CGPoint(x: ec.x + eyeRadius * 0.85, y: ec.y), control: CGPoint(x: ec.x, y: ec.y + eyeRadius * 0.62))
                ctx.stroke(lid, with: .color(darkTheme ? EyeStyle.Game.Firefly.glowCore : EyeStyle.Game.Sprout.arrowDark),
                           style: StrokeStyle(lineWidth: eyeRadius * 0.24, lineCap: .round))
            } else {
                let rect = CGRect(x: ec.x - eyeRadius, y: ec.y - eyeRadius, width: eyeRadius * 2, height: eyeRadius * 2)
                ctx.fill(Path(ellipseIn: rect), with: .color(EyeStyle.Game.Sprout.cloudWhite))
                ctx.stroke(Path(ellipseIn: rect),
                           with: .color(darkTheme ? EyeStyle.Game.Firefly.moonHalo.opacity(0.72) : EyeStyle.Game.Sprout.arrowDark.opacity(0.45)),
                           lineWidth: eyeRadius * 0.13)
                let dx = lookTarget.x - ec.x, dy = lookTarget.y - ec.y
                let ang = atan2(dy, dx)
                let dist = hypot(dx, dy)
                let shift = min(eyeRadius * 0.34, dist * 0.08)
                let pc = CGPoint(x: ec.x + cos(ang) * shift, y: ec.y + sin(ang) * shift)
                let pr = eyeRadius * 0.48
                ctx.fill(Path(ellipseIn: CGRect(x: pc.x - pr, y: pc.y - pr, width: pr * 2, height: pr * 2)), with: .color(EyeStyle.Game.Sprout.arrowDark))
                let h1 = eyeRadius * 0.17, h2 = eyeRadius * 0.08
                ctx.fill(Path(ellipseIn: CGRect(x: pc.x - eyeRadius * 0.16 - h1, y: pc.y - eyeRadius * 0.16 - h1, width: h1 * 2, height: h1 * 2)),
                         with: .color(EyeStyle.Game.Sprout.cloudWhite))
                ctx.fill(Path(ellipseIn: CGRect(x: pc.x + eyeRadius * 0.15 - h2, y: pc.y + eyeRadius * 0.14 - h2, width: h2 * 2, height: h2 * 2)),
                         with: .color(EyeStyle.Game.Sprout.cloudWhite.opacity(0.75)))
            }
            if cover == which {
                let sr = eyeRadius * 1.24
                let sRect = CGRect(x: ec.x - sr, y: ec.y - sr, width: sr * 2, height: sr * 2)
                ctx.fill(Path(ellipseIn: sRect), with: .color(EyeStyle.Game.Sprout.dropLight.opacity(0.92)))
                ctx.stroke(Path(ellipseIn: sRect), with: .color(EyeStyle.Game.Sprout.cloudWhite), lineWidth: eyeRadius * 0.16)
                var palm = Path()
                palm.move(to: CGPoint(x: ec.x - sr * 0.42, y: ec.y + sr * 0.18))
                palm.addQuadCurve(to: CGPoint(x: ec.x + sr * 0.42, y: ec.y + sr * 0.18), control: CGPoint(x: ec.x, y: ec.y - sr * 0.34))
                ctx.stroke(palm, with: .color(EyeStyle.Game.Sprout.arrowDark.opacity(0.72)), style: StrokeStyle(lineWidth: eyeRadius * 0.18, lineCap: .round))
            }
        }
    }
}

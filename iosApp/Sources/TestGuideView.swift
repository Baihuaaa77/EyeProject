import SwiftUI

/// 测试前的方法演示：爱科数字人图片分镜与操作简图，和安卓保持一致。
/// 只出现在准备阶段，不参与正式刺激呈现或计时；系统减弱动态效果时停在关键帧并展示全部步骤。
enum GuideMotion { case posture, acuity, fva, blink, open, near, move, curve, flip, fixate, far, cover, coverSelf, follow
    /// 与安卓 guideMotion(step) 相同的映射。
    static func forStep(_ step: String, selfCover: Bool = false) -> GuideMotion {
        switch step {
        case "NEAR_VISION": .near
        case "PUSH_UP": .move
        case "DISTANCE_CURVE": .curve
        case "FLIPPER": .flip
        case "FIXATION", "GAZE_CALIBRATION": .fixate
        case "FAR_FIXATION": .far
        case "COVER": selfCover ? .coverSelf : .cover
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
            VStack(alignment: .trailing, spacing: 8) {
                Text(title).font(EyeStyle.title).foregroundStyle(EyeStyle.text).frame(maxWidth: .infinity, alignment: .leading)

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

/// 爱科图片分镜；只用于方法教学，不参与正式刺激与计时。
struct GuideScene {
    struct Beat { let start: Double; let label: String }
    let motion: GuideMotion
    let cycle: Double
    let beats: [Beat]
    let keyframe: Double
    static func scene(_ motion: GuideMotion) -> GuideScene {
        switch motion {
        case .posture: GuideScene(motion: motion, cycle: 5.2, beats: [.init(start: 0.0, label: "① 正对屏幕，露出双眼"), .init(start: 0.25, label: "② 前后微调到提示的距离"), .init(start: 0.6, label: "③ 光线均匀，避免逆光")], keyframe: 0.7)
        case .blink: GuideScene(motion: motion, cycle: 4.2, beats: [.init(start: 0.0, label: "① 正对屏幕，保持放松"), .init(start: 0.15, label: "② 像平时一样自然眨眼"), .init(start: 0.55, label: "③ 应用自动记录眨眼")], keyframe: 0.6)
        case .open: GuideScene(motion: motion, cycle: 4.8, beats: [.init(start: 0.0, label: "① 轻松睁开双眼"), .init(start: 0.15, label: "② 尽量保持睁眼，看着屏幕"), .init(start: 0.86, label: "③ 不舒服就眨眼，随时可停止")], keyframe: 0.5)
        case .near: GuideScene(motion: motion, cycle: 5.6, beats: [.init(start: 0.0, label: "① 用手掌遮住左眼（不压眼球）"), .init(start: 0.2, label: "② 用右眼看 E 的开口方向"), .init(start: 0.28, label: "③ 点选对应方向；之后换眼再做")], keyframe: 0.35)
        case .move: GuideScene(motion: motion, cycle: 7.6, beats: [.init(start: 0.0, label: "① 从 45 cm 外开始，看着 E"), .init(start: 0.15, label: "② 慢慢移近（每秒不超过 5 cm）"), .init(start: 0.6, label: "③ E 一开始持续模糊就点按，记录近点")], keyframe: 0.66)
        case .flip: GuideScene(motion: motion, cycle: 6.4, beats: [.init(start: 0.0, label: "① 远处停稳，点选 E 的开口"), .init(start: 0.15, label: "② 移近停稳，再点选一次"), .init(start: 0.55, label: "③ 移远，远近交替进行")], keyframe: 0.45)
        case .fixate: GuideScene(motion: motion, cycle: 4.0, beats: [.init(start: 0.0, label: "① 看着屏幕上方闪烁的圆点"), .init(start: 0.45, label: "② 头和手机都保持不动")], keyframe: 0.5)
        case .far: GuideScene(motion: motion, cycle: 5.2, beats: [.init(start: 0.0, label: "① 手机留在眼前约 33 cm，略低于视线"), .init(start: 0.3, label: "② 眼睛越过手机，看 6 米外的目标"), .init(start: 0.65, label: "③ 看约 10 秒，头和手机都不动")], keyframe: 0.5)
        case .coverSelf: GuideScene(motion: motion, cycle: 12.0, beats: [.init(start: 0.0, label: "① 遮右眼 2 秒 → 移开，双眼注视 2 秒"), .init(start: 0.3333333333333333, label: "② 遮左眼 2 秒 → 移开，双眼注视 2 秒"), .init(start: 0.6666666666666666, label: "③ 右眼、左眼各遮 1 秒，快速交替两轮")], keyframe: 0.12)
        case .cover: GuideScene(motion: motion, cycle: 12.0, beats: [.init(start: 0.0, label: "① 遮右眼 2 秒 → 移开，双眼注视 2 秒"), .init(start: 0.3333333333333333, label: "② 遮左眼 2 秒 → 移开，双眼注视 2 秒"), .init(start: 0.6666666666666666, label: "③ 右眼、左眼各遮 1 秒，快速交替两轮")], keyframe: 0.12)
        case .follow: GuideScene(motion: motion, cycle: 7.2, beats: [.init(start: 0.0, label: "① 水平：只用眼睛跟随圆点"), .init(start: 0.36, label: "② 垂直：头保持不动"), .init(start: 0.72, label: "③ 斜向：继续跟随")], keyframe: 0.2)
        case .acuity: GuideScene(motion: motion, cycle: 6.0, beats: [.init(start: 0.0, label: "① 看 E 的开口方向"), .init(start: 0.1, label: "② 2 秒内点选对应方向"), .init(start: 0.4, label: "③ 连对两次变小，答错或超时变大")], keyframe: 0.12)
        case .fva: GuideScene(motion: motion, cycle: 7.0, beats: [.init(start: 0.0, label: "① 全程看 E，点选开口方向"), .init(start: 0.12, label: "② 前一段像平时一样自然眨眼"), .init(start: 0.68, label: "③ 不停顿，转入尽量保持睁眼")], keyframe: 0.3)
        case .curve: GuideScene(motion: motion, cycle: 6.6, beats: [.init(start: 0.0, label: "① 移到提示的距离并停稳"), .init(start: 0.12, label: "② 点选 E 的开口方向"), .init(start: 0.36, label: "③ 依次换到下一个距离")], keyframe: 0.2)
        }
    }
}

private enum GuidePose: String {
    case open, blink, left, right
    case helperOpen = "helper_open", helperLeft = "helper_left", helperRight = "helper_right"
    var image: String { "eyecho_guide_" + rawValue }
    var label: String {
        switch self {
        case .open: "双眼注视"
        case .blink: "自然眨眼"
        case .left: "遮左眼"
        case .right: "遮右眼"
        case .helperOpen: "移开遮挡 · 双眼注视"
        case .helperLeft: "协助者遮左眼"
        case .helperRight: "协助者遮右眼"
        }
    }
    /// 与安卓相同的十二秒教学循环；左右以被测者自身为准，图片不做镜像。
    static func at(_ motion: GuideMotion, _ phase: Double) -> GuidePose {
        let p = min(0.999999, max(0, phase))
        if motion == .cover || motion == .coverSelf {
            let pose: GuidePose
            switch Int(p * 12) {
            case 0, 1, 8, 10: pose = .right
            case 4, 5, 9, 11: pose = .left
            default: pose = .open
            }
            if motion == .cover {
                switch pose {
                case .right: return .helperRight
                case .left: return .helperLeft
                default: return .helperOpen
                }
            }
            return pose
        }
        if motion == .near {
            if p < 0.06 || (p >= 0.62 && p <= 0.68) || p >= 0.94 { return .open }
            return p < 0.62 ? .left : .right
        }
        if motion == .move { return .left }
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

/// 完整展示头顶、手势与胸前标识；图像等比适配，不使用圆形裁切。
private struct GuideStage: View {
    let scene: GuideScene
    let phase: Double
    var height: CGFloat = EyeStyle.guideStageHeight
    private var portraitOnly: Bool { [.cover, .coverSelf, .blink, .open].contains(scene.motion) }
    var body: some View {
        let pose = GuidePose.at(scene.motion, phase)
        VStack(spacing: 8) {
            GeometryReader { proxy in
                HStack(spacing: 8) {
                    Image(pose.image).resizable().scaledToFit()
                        .frame(width: portraitOnly ? proxy.size.width : (proxy.size.width - 8) * 2 / 3, height: proxy.size.height)
                        .clipShape(RoundedRectangle(cornerRadius: EyeStyle.radiusLarge))
                        .accessibilityLabel("爱科示范：" + pose.label)
                    if !portraitOnly {
                        VStack(spacing: 0) {
                            GuideDiagram(motion: scene.motion, phase: phase)
                            Text(guideDiagramLabel(scene.motion, phase)).font(EyeStyle.chip)
                                .foregroundStyle(EyeStyle.text).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
                        }.frame(maxWidth: .infinity, maxHeight: .infinity)
                    }
                }
            }
            if portraitOnly || scene.motion == .near {
                Text(pose.label).font(EyeStyle.chip).foregroundStyle(EyeStyle.text)
            }
        }.padding(8).frame(height: height).frame(maxWidth: .infinity)
            .background(EyeStyle.surfaceVariant, in: RoundedRectangle(cornerRadius: EyeStyle.radiusLarge))
    }
}

/// 服务页示范与测试引导共用分镜，系统减弱动态效果时停在关键帧。
struct GuideFrame: View {
    let motion: GuideMotion
    let phase: Double
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        let scene = GuideScene.scene(motion)
        GuideStage(scene: scene, phase: reduceMotion ? scene.keyframe : phase, height: 190)
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
    case .fixate: "看圆点 · 头不动"
    case .follow: "跟随圆点 · 头不动"
    case .far: "越过手机看 6 米外"
    default: ""
    }
}

/// 手机、视标与注视点保留为矢量操作简图，正式测试沿用原来的刺激组件。
private struct GuideDiagram: View {
    let motion: GuideMotion
    let phase: Double
    var body: some View {
        Canvas { context, size in
            let p = min(1, max(0, phase))
            let unit = min(size.width / 100, size.height / 170)
            func at(_ x: CGFloat, _ y: CGFloat) -> CGPoint {
                CGPoint(x: size.width / 2 + (x - 50) * unit, y: size.height / 2 + (y - 85) * unit)
            }
            let approach: Double
            switch motion {
            case .move: approach = p < 0.15 ? 0 : p < 0.68 ? (p - 0.15) / 0.53 : p < 0.8 ? 1 : (1 - p) / 0.2
            case .flip: approach = p < 0.15 || p > 0.7 ? 0 : p < 0.3 ? (p - 0.15) / 0.15 : p < 0.55 ? 1 : (0.7 - p) / 0.15
            case .curve: approach = p < 0.3 ? 0 : p < 0.38 ? (p - 0.3) / 0.08 * 0.6 : p < 0.64 ? 0.6 : p < 0.72 ? 0.6 + (p - 0.64) / 0.08 * 0.4 : 1
            default: approach = 0.5
            }
            let phoneHeight: CGFloat = motion == .far ? 76 : 106 + CGFloat(approach) * 36
            let (tl, screen) = Cartoon.phone(context, center: at(50, motion == .far ? 116 : 85), height: phoneHeight * unit)
            let target = CGPoint(x: tl.x + screen.width / 2, y: tl.y + screen.height * 0.3)
            switch motion {
            case .far:
                Cartoon.fixationTarget(context, center: at(50, 27), size: 22 * unit)
                context.stroke(Cartoon.segment(at(50, 46), at(50, 66)), with: .color(EyeStyle.primary), lineWidth: 2 * unit)
            case .posture:
                let r = screen.width * 0.28
                let c = CGPoint(x: target.x, y: tl.y + screen.height / 2)
                context.stroke(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2)),
                               with: .color(EyeStyle.success), lineWidth: 2 * unit)
            case .fixate, .follow:
                let relative: CGPoint
                if p < 0.36 {
                    relative = CGPoint(x: 0.32 * sin(p / 0.36 * 2 * Double.pi), y: 0)
                } else if p < 0.72 {
                    relative = CGPoint(x: 0, y: 0.32 * sin((p - 0.36) / 0.36 * 2 * Double.pi))
                } else {
                    let t = (p - 0.72) / 0.28
                    let wave = t < 0.5 ? t * 4 - 1 : 3 - t * 4
                    relative = CGPoint(x: 0.32 * wave, y: -0.32 * wave)
                }
                let dot = motion == .follow ? CGPoint(x: tl.x + screen.width * (0.5 + relative.x),
                    y: tl.y + screen.height * (0.5 + relative.y)) : target
                Cartoon.fixationTarget(context, center: dot, size: 17 * unit)
            default:
                let step = min(4, max(0, Int(p * 5)))
                let direction = [0, 3, 2, 1, 0][step]
                let edge = screen.width * (motion == .acuity && step == 1 ? 0.26 : 0.36)
                Cartoon.tumblingE(context, color: EyeStyle.ink, center: target, edge: edge, direction: direction)
                let offsets = [CGPoint(x: 1, y: 0), CGPoint(x: 0, y: 1), CGPoint(x: -1, y: 0), CGPoint(x: 0, y: -1)]
                for (index, offset) in offsets.enumerated() {
                    let c = CGPoint(x: target.x + offset.x * screen.width * 0.32, y: tl.y + screen.height * 0.7 + offset.y * screen.width * 0.32)
                    let r = screen.width * 0.12
                    context.fill(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2)),
                                 with: .color(index == direction ? EyeStyle.primary : EyeStyle.borderSubtle))
                    let ink = index == direction ? Color.white : EyeStyle.ink
                    let length = screen.width * 0.055
                    let tip = CGPoint(x: c.x + offset.x * length, y: c.y + offset.y * length)
                    let normal = CGPoint(x: -offset.y * length, y: offset.x * length)
                    context.stroke(Cartoon.segment(CGPoint(x: c.x - offset.x * length, y: c.y - offset.y * length), tip), with: .color(ink), lineWidth: unit)
                    context.stroke(Cartoon.segment(tip, CGPoint(x: c.x + normal.x, y: c.y + normal.y)), with: .color(ink), lineWidth: unit)
                    context.stroke(Cartoon.segment(tip, CGPoint(x: c.x - normal.x, y: c.y - normal.y)), with: .color(ink), lineWidth: unit)
                }
            }
        }.accessibilityLabel("操作示意")
    }
}

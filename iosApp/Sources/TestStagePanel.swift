import SwiftUI

/// 测试舞台的细框与轻阴影，视标内部继续保留白色背景。
struct TestStagePanel: ViewModifier {
    @ViewBuilder func body(content: Content) -> some View {
        content.padding(20).frame(maxWidth: .infinity)
            .background(EyeStyle.panel, in: RoundedRectangle(cornerRadius: 22))
            .overlay { RoundedRectangle(cornerRadius: 22).stroke(EyeStyle.primary.opacity(0.16), lineWidth: 1) }
            .shadow(color: EyeStyle.primary.opacity(0.08), radius: 3, x: 0, y: 2)
    }
}

/// 使用共用核心给出的目标与容差，仅呈现距离状态，不改变采样门控。
/// 刚换目标距离、还在移过去的路上时（共用核心给出 distanceMoveToCm），提示「请移动到 xx 厘米处」而不是太近 / 太远。
struct TestDistanceBanner: View {
    @ObservedObject var runtime: EyeRuntime
    private var distance: Double { runtime.number("distanceCm") }
    private var moveTo: Double { runtime.number("distanceMoveToCm") }
    private var moving: Bool { moveTo > 0 }
    private var target: Double { runtime.number("targetDistanceCm") }
    private var tolerance: Double { runtime.number("distanceToleranceCm") }
    private var known: Bool {
        distance.isFinite && distance > 0 && (runtime.bool("faceDetected") || runtime.string("phase") == "NEAR_VISION")
    }
    private var close: Bool { known && !moving && distance < target - tolerance }
    private var far: Bool { known && !moving && distance > target + tolerance }
    private var background: Color {
        moving ? EyeStyle.primary : !known ? EyeStyle.surfaceVariant : close ? EyeStyle.tooClose : far ? EyeStyle.tooFar : EyeStyle.success
    }
    private var title: String {
        moving ? "请移动到 \(Int(moveTo.rounded())) 厘米处" : !known ? "请正对屏幕" : close ? "太近了" : far ? "太远了" : "距离合适"
    }
    private var detail: String {
        if moving { return known ? "当前约 \(Int(distance.rounded())) cm，到位后视标自动出现" : "露出双眼，让摄像头看到你" }
        if !known { return "露出双眼，让摄像头看到你" }
        if close { return "把手机往远处移约 \(max(1, Int((target - tolerance - distance).rounded()))) cm" }
        if far { return "把手机往近处移约 \(max(1, Int((distance - target - tolerance).rounded()))) cm" }
        return "约 \(Int(distance.rounded())) cm，保持住"
    }
    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: moving ? "arrow.forward" : !known ? "viewfinder" : close ? "arrow.up.right.and.arrow.down.left" : far ? "arrow.down.right.and.arrow.up.left" : "checkmark")
                .font(.system(size: 24, weight: .semibold)).frame(width: 28)
                .contentTransition(.opacity)
            VStack(alignment: .leading, spacing: 2) {
                // 单行：文字变化不改变高度，避免挤动下方注视画布
                Text(title).font(.headline).lineLimit(1)
                Text(detail).font(.subheadline).monospacedDigit().lineLimit(1)
            }.frame(maxWidth: .infinity, alignment: .leading)
            if known && tolerance > 0 { DistanceRuler(distance: distance, target: target, tolerance: tolerance) }
        }.foregroundStyle(known || moving ? EyeStyle.panel : EyeStyle.text).padding(.horizontal, 16).padding(.vertical, 10)
            .background(background, in: RoundedRectangle(cornerRadius: 16))
            .animation(.easeInOut(duration: 0.28), value: background)
            .accessibilityElement(children: .combine)
    }
}

/// 与安卓同一示意：左近右远，中间亮带为目标区间，圆点为当前位置。
private struct DistanceRuler: View {
    let distance: Double, target: Double, tolerance: Double
    var body: some View {
        GeometryReader { geometry in
            let width = geometry.size.width
            let range = tolerance * 4
            let x = { (cm: Double) in CGFloat(min(1, max(0, (cm - target + range) / (2 * range)))) * width }
            Capsule().fill(EyeStyle.panel.opacity(0.35)).frame(width: width, height: 6).position(x: width / 2, y: 14)
            Capsule().fill(EyeStyle.panel.opacity(0.85)).frame(width: x(target + tolerance) - x(target - tolerance), height: 10)
                .position(x: (x(target + tolerance) + x(target - tolerance)) / 2, y: 14)
            Circle().fill(EyeStyle.panel.opacity(0.3)).frame(width: 22, height: 22).position(x: x(distance), y: 14)
            Circle().fill(EyeStyle.panel).frame(width: 14, height: 14).position(x: x(distance), y: 14)
        }.frame(width: 72, height: 28).accessibilityHidden(true)
            .animation(.easeOut(duration: 0.2), value: distance)
    }
}

/// 任务中的质量提醒：浅色胶囊淡入，消失时收起。
/// 注视类任务（校准、注视、遮盖、追踪）用的提示位：始终占住固定高度，提示出现或消失都不挤动下方画布。
/// 画布一移动，屏幕上的校准点和注视目标就跟着移动，受试者刚才看的位置与记录的位置不再一致。与安卓 TestNoticeSlot 一致。
struct TestNoticeSlot: View {
    let text: String
    var body: some View {
        ZStack {
            if !text.isEmpty {
                Text(text).font(.body).foregroundStyle(EyeStyle.warning).multilineTextAlignment(.center).lineLimit(2)
                    .padding(.horizontal, 16).padding(.vertical, 8).frame(maxWidth: .infinity)
                    .background(EyeStyle.warning.opacity(0.12), in: Capsule())
                    .transition(.opacity)
            }
        }.frame(maxWidth: .infinity).frame(height: 64)
    }
}

struct TestNotice: View {
    let text: String
    var body: some View {
        if !text.isEmpty {
            Text(text).font(.body).foregroundStyle(EyeStyle.warning).multilineTextAlignment(.center)
                .padding(.horizontal, 16).padding(.vertical, 10).frame(maxWidth: .infinity)
                .background(EyeStyle.warning.opacity(0.12), in: Capsule())
                .transition(.opacity.combined(with: .move(edge: .top)))
        }
    }
}

/// 圆环计时：浅色底环 + 主色进度弧，中间放读数。
struct TimerRing<Content: View>: View {
    let progress: Double
    @ViewBuilder let content: Content
    var body: some View {
        ZStack {
            Circle().stroke(EyeStyle.surfaceVariant, lineWidth: 8)
            Circle().trim(from: 0, to: min(1, max(0, progress)))
                .stroke(EyeStyle.primary, style: StrokeStyle(lineWidth: 8, lineCap: .round))
                .rotationEffect(.degrees(-90))
            content
        }
    }
}

/// 倒计时：每秒环走一圈，数字缩放淡入；系统减弱动态效果时保持静止。
struct CountdownRing: View {
    let seconds: Int
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var sweep = 1.0
    var body: some View {
        TimerRing(progress: sweep) {
            Text("\(seconds)").font(.system(size: 64, weight: .semibold, design: .rounded)).monospacedDigit()
                .foregroundStyle(EyeStyle.primary).id(seconds)
                .transition(reduceMotion ? AnyTransition.identity : AnyTransition.scale(scale: 0.6).combined(with: .opacity))
        }.frame(width: 168, height: 168)
            .animation(reduceMotion ? nil : .spring(response: 0.3, dampingFraction: 0.7), value: seconds)
            .onAppear { restart() }
            .onChange(of: seconds) { _ in restart() }
    }
    private func restart() {
        var snap = Transaction(); snap.disablesAnimations = true
        withTransaction(snap) { sweep = 1 }
        guard !reduceMotion else { return }
        // 下一轮主循环再开始走环，避免与复位合并成同一次无动画更新。
        DispatchQueue.main.async { withAnimation(.linear(duration: 1)) { sweep = 0 } }
    }
}

/// 与安卓 OptotypeAnswerPad 同规格的圆键：直径 64、间距 10，按下缩到 90% 并加深底色，不可作答时变淡。
enum AnswerKey {
    static let size: CGFloat = 64
    static let gap: CGFloat = 10
}

/// 方向键：主色 10% 底、30% 细边，按下底色加深到 24%。
struct DirectionKeyStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .frame(width: AnswerKey.size, height: AnswerKey.size)
            .background(EyeStyle.primary.opacity(configuration.isPressed && enabled ? 0.24 : 0.1), in: Circle())
            .overlay { Circle().stroke(EyeStyle.primary.opacity(0.3), lineWidth: 1) }
            .contentShape(Circle())
            .scaleEffect(configuration.isPressed && enabled ? 0.9 : 1)
            .opacity(enabled ? 1 : 0.35)
            .animation(EyeStyle.pressSpring, value: configuration.isPressed)
            .animation(.easeOut(duration: 0.16), value: enabled)
    }
}

/// 绘制的箭头（基准朝右），不依赖系统符号字形，两端外观一致。
struct ArrowGlyph: Shape {
    func path(in rect: CGRect) -> Path {
        let w = rect.width, h = rect.height
        var path = Path()
        path.move(to: CGPoint(x: rect.minX + w * 0.12, y: rect.minY + h * 0.5)); path.addLine(to: CGPoint(x: rect.minX + w * 0.86, y: rect.minY + h * 0.5))
        path.move(to: CGPoint(x: rect.minX + w * 0.54, y: rect.minY + h * 0.18)); path.addLine(to: CGPoint(x: rect.minX + w * 0.86, y: rect.minY + h * 0.5))
        path.addLine(to: CGPoint(x: rect.minX + w * 0.54, y: rect.minY + h * 0.82))
        return path
    }
}

/// 中央「跳过」：与方向键同尺寸的圆键，浅底区分为次要动作。
struct SkipKeyStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(EyeStyle.body).foregroundStyle(EyeStyle.text)
            .frame(width: AnswerKey.size, height: AnswerKey.size)
            .background(configuration.isPressed && enabled ? EyeStyle.primary.opacity(0.12) : EyeStyle.surfaceVariant, in: Circle())
            .overlay { Circle().stroke(EyeStyle.primary.opacity(0.24), lineWidth: 1) }
            .contentShape(Circle())
            .scaleEffect(configuration.isPressed && enabled ? 0.9 : 1)
            .opacity(enabled ? 1 : 0.4)
            .animation(EyeStyle.pressSpring, value: configuration.isPressed)
            .animation(.easeOut(duration: 0.16), value: enabled)
    }
}

/// 测试顶栏：与安卓 TestSessionLayout 一致，左侧主色返回箭头、居中标题、右侧实心齿轮，不用系统导航栏的玻璃胶囊。
struct TestTopBar: View {
    let title: String
    let onBack: () -> Void
    let onSettings: () -> Void
    var body: some View {
        ZStack {
            Text(title).font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text).lineLimit(1)
            HStack {
                Button(action: onBack) {
                    Image(systemName: "chevron.left").font(.system(size: 19, weight: .semibold))
                        .frame(width: 48, height: 48).contentShape(Rectangle())
                }.accessibilityLabel("返回")
                Spacer()
                Button(action: onSettings) {
                    Image(systemName: "gearshape.fill").font(.system(size: 22))
                        .frame(width: 48, height: 48).contentShape(Rectangle())
                }.accessibilityLabel("系统设置")
            }.buttonStyle(TopBarIconStyle())
        }.frame(height: 52)
    }
}

private struct TopBarIconStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.foregroundStyle(EyeStyle.primary)
            .opacity(configuration.isPressed ? 0.55 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

/// 测试开始后顶部的整体进度与剩余时间：细条加一行小字。
struct TestProgressBar: View {
    let fraction: Double
    let text: String
    var body: some View {
        HStack(spacing: 10) {
            GeometryReader { geometry in
                ZStack(alignment: .leading) {
                    Capsule().fill(EyeStyle.surfaceVariant)
                    Capsule().fill(EyeStyle.primary).frame(width: geometry.size.width * min(1, max(0, fraction)))
                }
            }.frame(height: 4)
            Text(text).font(.footnote).foregroundStyle(EyeStyle.secondary).monospacedDigit()
        }.animation(.easeInOut(duration: 0.7), value: fraction)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("测试进度").accessibilityValue("\(Int(fraction * 100))%，\(text)")
    }
}

/// 进行中任务能放下时，舞台中可伸展的元素（刺激区、注视画布）吸收剩余高度。
private struct StageFillKey: EnvironmentKey { static let defaultValue = false }
extension EnvironmentValues {
    var stageFill: Bool {
        get { self[StageFillKey.self] }
        set { self[StageFillKey.self] = newValue }
    }
}

private struct StageHeight: ViewModifier {
    let base: CGFloat
    @Environment(\.stageFill) private var fill
    @ViewBuilder func body(content: Content) -> some View {
        if fill { content.frame(minHeight: base, maxHeight: .infinity) } else { content.frame(height: base) }
    }
}

extension View {
    /// 基础高度；可伸展时最小为该高度、其余吸收剩余空间。每屏只应有一个伸展元素。
    func stageHeight(_ base: CGFloat) -> some View { modifier(StageHeight(base: base)) }
}

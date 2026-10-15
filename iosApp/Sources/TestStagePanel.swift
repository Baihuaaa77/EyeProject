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
struct TestDistanceBanner: View {
    @ObservedObject var runtime: EyeRuntime
    private var distance: Double { runtime.number("distanceCm") }
    private var target: Double { runtime.number("targetDistanceCm") }
    private var tolerance: Double { runtime.number("distanceToleranceCm") }
    private var known: Bool {
        distance.isFinite && distance > 0 && (runtime.bool("faceDetected") || runtime.string("phase") == "NEAR_VISION")
    }
    private var close: Bool { known && distance < target - tolerance }
    private var far: Bool { known && distance > target + tolerance }
    private var background: Color {
        !known ? EyeStyle.surfaceVariant : close ? EyeStyle.tooClose : far ? EyeStyle.tooFar : EyeStyle.success
    }
    private var title: String { !known ? "请正对屏幕" : close ? "太近了" : far ? "太远了" : "距离合适" }
    private var detail: String {
        if !known { return "露出双眼，让摄像头看到你" }
        if close { return "把手机往远处移约 \(max(1, Int((target - tolerance - distance).rounded()))) cm" }
        if far { return "把手机往近处移约 \(max(1, Int((distance - target - tolerance).rounded()))) cm" }
        return "约 \(Int(distance.rounded())) cm，保持住"
    }
    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: !known ? "viewfinder" : close ? "arrow.up.right.and.arrow.down.left" : far ? "arrow.down.right.and.arrow.up.left" : "checkmark")
                .font(.system(size: 24, weight: .semibold)).frame(width: 28)
                .contentTransition(.opacity)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline)
                Text(detail).font(.subheadline).monospacedDigit()
            }.frame(maxWidth: .infinity, alignment: .leading)
            if known && tolerance > 0 { DistanceRuler(distance: distance, target: target, tolerance: tolerance) }
        }.foregroundStyle(known ? EyeStyle.panel : EyeStyle.text).padding(.horizontal, 16).padding(.vertical, 10)
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

/// 圆形方向键与次要胶囊共用的按压反馈：轻缩、加深底色，不可作答时变淡。
struct DirectionKeyStyle: ButtonStyle {
    var capsule = false
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        let fill = capsule ? (configuration.isPressed ? EyeStyle.primary.opacity(0.12) : EyeStyle.panel)
            : EyeStyle.primary.opacity(configuration.isPressed ? 0.22 : 0.08)
        return configuration.label
            .foregroundStyle(capsule ? EyeStyle.text : EyeStyle.primary)
            .frame(maxWidth: capsule ? CGFloat.infinity : 56, minHeight: capsule ? 52 : 56)
            .frame(width: capsule ? nil : 56)
            .background { if capsule { Capsule().fill(fill) } else { Circle().fill(fill) } }
            .overlay { if capsule { Capsule().stroke(EyeStyle.primary.opacity(0.24), lineWidth: 1) }
                else { Circle().stroke(EyeStyle.primary.opacity(0.28), lineWidth: 1) } }
            .contentShape(capsule ? AnyShape(Capsule()) : AnyShape(Circle()))
            .scaleEffect(configuration.isPressed && enabled ? (capsule ? 0.96 : 0.9) : 1)
            .opacity(enabled ? 1 : 0.35)
            .animation(.spring(response: 0.25, dampingFraction: 0.6), value: configuration.isPressed)
            .animation(.easeOut(duration: 0.16), value: enabled)
    }
}

/// 次要操作（重试、延长、复测）：浅底描边，与主按钮区分轻重。
struct EyeSecondaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.padding().frame(maxWidth: .infinity).foregroundStyle(EyeStyle.text)
            .background(EyeStyle.surfaceVariant.opacity(configuration.isPressed ? 0.7 : 1), in: RoundedRectangle(cornerRadius: 14))
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.6), value: configuration.isPressed)
    }
}

/// 中央「跳过」：与方向键同尺寸的圆键，浅底区分为次要动作。
struct SkipKeyStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.foregroundStyle(EyeStyle.text)
            .frame(width: 64, height: 64)
            .background(configuration.isPressed ? EyeStyle.primary.opacity(0.12) : EyeStyle.surfaceVariant, in: Circle())
            .overlay { Circle().stroke(EyeStyle.primary.opacity(0.24), lineWidth: 1) }
            .contentShape(Circle())
            .scaleEffect(configuration.isPressed && enabled ? 0.9 : 1)
            .opacity(enabled ? 1 : 0.4)
            .animation(.spring(response: 0.25, dampingFraction: 0.6), value: configuration.isPressed)
            .animation(.easeOut(duration: 0.16), value: enabled)
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

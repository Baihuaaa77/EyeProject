import SwiftUI

/// 测试舞台的细框与轻阴影，视标内部继续保留白色背景。
struct TestStagePanel: ViewModifier {
    func body(content: Content) -> some View {
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
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline)
                Text(detail).font(.subheadline)
            }.frame(maxWidth: .infinity, alignment: .leading)
            if known {
                GeometryReader { geometry in
                    let fraction = min(1, max(0, (distance - target + 20) / 40))
                    Capsule().fill(EyeStyle.panel.opacity(0.3)).frame(height: 4).position(x: geometry.size.width / 2, y: 14)
                    Circle().fill(EyeStyle.panel.opacity(0.3)).frame(width: 20, height: 20)
                        .position(x: 8 + fraction * (geometry.size.width - 16), y: 14)
                    Circle().fill(EyeStyle.panel).frame(width: 12, height: 12)
                        .position(x: 8 + fraction * (geometry.size.width - 16), y: 14)
                }.frame(width: 72, height: 28).accessibilityHidden(true)
            }
        }.foregroundStyle(known ? EyeStyle.panel : EyeStyle.text).padding(.horizontal, 16).padding(.vertical, 10)
            .background(background, in: RoundedRectangle(cornerRadius: 16))
    }
}

import SwiftUI

/// 对齐 core/designsystem 的亮色令牌；iOS 绘制器只消费令牌，不定义另一套主题。
enum EyeStyle {
    static let background = Color(rgb: 0xF3F6F5)
    static let panel = Color(rgb: 0xFFFFFF)
    static let text = Color(rgb: 0x13201F)
    static let secondary = Color(rgb: 0x566664)
    static let primary = Color(rgb: 0x0B7A78)
    static let brandStart = Color(rgb: 0x12A39A)
    static let brandEnd = Color(rgb: 0x2A5BD7)
    static let accent = Color(rgb: 0xF2A33A)
    static let warning = Color(rgb: 0x986000)
    static let success = Color(rgb: 0x2E7D4F)
    static let radius: CGFloat = 20
    static let spacing: CGFloat = 12
    static let title = Font.system(size: 22, weight: .semibold)
}

extension Color {
    init(rgb: UInt32) { self.init(red: Double((rgb >> 16) & 255) / 255, green: Double((rgb >> 8) & 255) / 255, blue: Double(rgb & 255) / 255) }
}

struct EyeButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.padding().frame(maxWidth: .infinity).foregroundStyle(EyeStyle.panel)
            .background(EyeStyle.primary.opacity(configuration.isPressed ? 0.7 : 1), in: RoundedRectangle(cornerRadius: 14))
    }
}

struct MascotView: View {
    var body: some View {
        Canvas { context, size in
            let s = min(size.width, size.height / 1.22)
            let cx = size.width / 2, cy = size.height / 2
            let face = CGRect(x: cx - s / 2, y: cy - s * 0.42, width: s, height: s * 0.84)
            context.fill(Path(roundedRect: face, cornerRadius: s * 0.4), with: .linearGradient(
                Gradient(colors: [EyeStyle.brandStart, EyeStyle.brandEnd]), startPoint: face.origin, endPoint: CGPoint(x: face.maxX, y: face.maxY)))
            for side in [-1.0, 1.0] {
                context.fill(Path(ellipseIn: CGRect(x: cx + side * s * 0.19 - s * 0.05, y: cy - s * 0.08, width: s * 0.1, height: s * 0.14)), with: .color(EyeStyle.text))
                context.fill(Path(ellipseIn: CGRect(x: cx + side * s * 0.31 - s * 0.05, y: cy + s * 0.1, width: s * 0.1, height: s * 0.06)), with: .color(EyeStyle.accent))
            }
            var mouth = Path(); mouth.move(to: CGPoint(x: cx - s * 0.05, y: cy + s * 0.1))
            mouth.addQuadCurve(to: CGPoint(x: cx + s * 0.05, y: cy + s * 0.1), control: CGPoint(x: cx, y: cy + s * 0.2))
            context.stroke(mouth, with: .color(EyeStyle.text), lineWidth: s * 0.024)
            context.fill(Path(ellipseIn: CGRect(x: cx - s * 0.15, y: cy - s * 0.59, width: s * 0.18, height: s * 0.09)), with: .color(EyeStyle.brandStart))
            context.fill(Path(ellipseIn: CGRect(x: cx, y: cy - s * 0.61, width: s * 0.2, height: s * 0.1)), with: .color(EyeStyle.brandStart))
        }.accessibilityLabel("小瞳")
    }
}

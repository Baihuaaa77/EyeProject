import SwiftUI

/// 对齐 core/designsystem 的亮色令牌；iOS 绘制器只消费令牌，不定义另一套主题。
enum EyeStyle {
    static let background = Color(rgb: 0xF3F6F5)
    static let panel = Color(rgb: 0xFFFFFF)
    static let surfaceVariant = Color(rgb: 0xE9EFEE)
    static let borderSubtle = Color(rgb: 0xDCE4E2)
    static let text = Color(rgb: 0x13201F)
    static let ink = Color(rgb: 0x13201F)
    static let secondary = Color(rgb: 0x566664)
    static let primary = Color(rgb: 0x0B7A78)
    static let brandStart = Color(rgb: 0x12A39A)
    static let brandEnd = Color(rgb: 0x2A5BD7)
    static let accent = Color(rgb: 0xF2A33A)
    static let warning = Color(rgb: 0x986000)
    static let success = Color(rgb: 0x2E7D4F)
    static let danger = Color(rgb: 0xC93430)
    static let dangerSoft = Color(rgb: 0xFFE4E1)
    static let tooClose = Color(rgb: 0xD7372C)
    static let tooFar = Color(rgb: 0x2563EB)
    /// EyeShapes：medium 14、large 16、panel 22。
    static let radius: CGFloat = 20
    static let radiusMedium: CGFloat = 14
    static let radiusLarge: CGFloat = 16
    static let spacing: CGFloat = 12
    /// EyeTypography：title 22、subtitle 18、button 17、body 16、bodySecondary 13。
    static let title = Font.system(size: 22, weight: .semibold)
    static let subtitle = Font.system(size: 18, weight: .semibold)
    static let button = Font.system(size: 17, weight: .semibold)
    static let body = Font.system(size: 16)
    static let bodySecondary = Font.system(size: 13)
    /// EyeMotion.pressSpring：阻尼 0.6、中等刚度。
    static let pressSpring = Animation.spring(response: 0.3, dampingFraction: 0.6)
}

extension Color {
    init(rgb: UInt32) { self.init(red: Double((rgb >> 16) & 255) / 255, green: Double((rgb >> 8) & 255) / 255, blue: Double(rgb & 255) / 255) }
}

/// 与安卓 EyeButton 同规格：最小高 56、圆角 14、按下弹簧缩到 96%，不用系统按压变暗。
/// 次要操作（重试、延长、复测）用 `.secondary`，浅底与主按钮区分轻重。
struct EyeButtonStyle: ButtonStyle {
    var role: Role = .primary
    enum Role { case primary, secondary, danger }
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        let (fill, content): (Color, Color) = switch role {
        case .primary: (EyeStyle.primary, EyeStyle.panel)
        case .secondary: (EyeStyle.surfaceVariant, EyeStyle.text)
        case .danger: (EyeStyle.dangerSoft, EyeStyle.danger)
        }
        return configuration.label.font(EyeStyle.button).multilineTextAlignment(.center)
            .padding(.horizontal, 16).padding(.vertical, 12).frame(maxWidth: .infinity, minHeight: 56)
            .foregroundStyle(content)
            .background(fill, in: RoundedRectangle(cornerRadius: EyeStyle.radiusMedium))
            .contentShape(RoundedRectangle(cornerRadius: EyeStyle.radiusMedium))
            .scaleEffect(configuration.isPressed && enabled ? 0.96 : 1)
            .opacity(enabled ? 1 : 0.5)
            .animation(EyeStyle.pressSpring, value: configuration.isPressed)
    }
}

/// 入口卡与选项行：白底圆角，按下轻缩，与安卓 EyeSelectCard 一致，不出现系统的整块变灰。
struct EyeCardButtonStyle: ButtonStyle {
    var selected = false
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(EyeStyle.body).foregroundStyle(EyeStyle.text)
            .padding(.horizontal, 16).padding(.vertical, 14).frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
            .background(selected ? EyeStyle.primary.opacity(0.08) : EyeStyle.panel, in: RoundedRectangle(cornerRadius: EyeStyle.radiusLarge))
            .overlay { RoundedRectangle(cornerRadius: EyeStyle.radiusLarge)
                .stroke(selected ? EyeStyle.primary : EyeStyle.borderSubtle, lineWidth: selected ? 1.5 : 1) }
            .contentShape(RoundedRectangle(cornerRadius: EyeStyle.radiusLarge))
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .animation(EyeStyle.pressSpring, value: configuration.isPressed)
    }
}

/// 吉祥物「小瞳」：逐项移植安卓 core:ui 的 drawMascot（双叶嫩芽、渐变团子头、顶部柔光、豆豆眼高光、腮红、微笑）。
/// [scale] 为头宽占画布短边的比例，[centerY] 为头部中心的纵向位置（画布高度的比例）。
struct MascotView: View {
    var scale: CGFloat = 0.8
    var centerY: CGFloat = 0.56
    var body: some View {
        Canvas { context, size in
            let base = context
            let s = min(size.width, size.height) * scale
            let c = CGPoint(x: size.width / 2, y: size.height * centerY)
            let top = c.y - s * 0.42
            func rotated(_ degrees: Double, around pivot: CGPoint, _ draw: (inout GraphicsContext) -> Void) {
                var layer = base
                layer.translateBy(x: pivot.x, y: pivot.y); layer.rotate(by: .degrees(degrees)); layer.translateBy(x: -pivot.x, y: -pivot.y)
                draw(&layer)
            }
            // 双叶嫩芽
            var stem = Path(); stem.move(to: CGPoint(x: c.x, y: top + s * 0.02))
            stem.addQuadCurve(to: CGPoint(x: c.x + s * 0.015, y: top - s * 0.1), control: CGPoint(x: c.x + s * 0.005, y: top - s * 0.05))
            context.stroke(stem, with: .color(EyeStyle.brandStart), style: StrokeStyle(lineWidth: s * 0.035, lineCap: .round))
            rotated(25, around: CGPoint(x: c.x - s * 0.06, y: top - s * 0.11)) {
                $0.fill(Path(ellipseIn: CGRect(x: c.x - s * 0.15, y: top - s * 0.155, width: s * 0.18, height: s * 0.09)), with: .color(EyeStyle.brandStart))
            }
            rotated(-30, around: CGPoint(x: c.x + s * 0.09, y: top - s * 0.14)) {
                $0.fill(Path(ellipseIn: CGRect(x: c.x - s * 0.01, y: top - s * 0.19, width: s * 0.2, height: s * 0.1)), with: .color(EyeStyle.brandStart))
            }
            // 团子头 + 顶部柔光
            let head = CGRect(x: c.x - s / 2, y: top, width: s, height: s * 0.84)
            context.fill(Path(roundedRect: head, cornerRadius: s * 0.4), with: .linearGradient(
                Gradient(colors: [EyeStyle.brandStart, EyeStyle.brandEnd]), startPoint: head.origin, endPoint: CGPoint(x: head.maxX, y: head.maxY)))
            rotated(-18, around: CGPoint(x: c.x - s * 0.2, y: top + s * 0.14)) {
                $0.fill(Path(ellipseIn: CGRect(x: c.x - s * 0.33, y: top + s * 0.09, width: s * 0.26, height: s * 0.1)), with: .color(.white.opacity(0.16)))
            }
            // 豆豆眼 + 高光
            for side in [-1.0, 1.0] {
                let eye = CGPoint(x: c.x + side * s * 0.19, y: c.y - s * 0.02)
                let rx = s * 0.052, ry = s * 0.072
                context.fill(Path(ellipseIn: CGRect(x: eye.x - rx, y: eye.y - ry, width: rx * 2, height: ry * 2)), with: .color(EyeStyle.ink))
                let glint = CGPoint(x: eye.x + rx * 0.4, y: eye.y - ry * 0.45)
                context.fill(Path(ellipseIn: CGRect(x: glint.x - s * 0.02, y: glint.y - s * 0.02, width: s * 0.04, height: s * 0.04)), with: .color(.white))
                // 腮红
                let blush = CGPoint(x: c.x + side * s * 0.31, y: c.y + s * 0.11)
                context.fill(Path(ellipseIn: CGRect(x: blush.x - s * 0.055, y: blush.y - s * 0.032, width: s * 0.11, height: s * 0.064)),
                    with: .color(EyeStyle.accent.opacity(0.75)))
            }
            // 微笑
            let mouthY = c.y + s * 0.1
            var mouth = Path(); mouth.move(to: CGPoint(x: c.x - s * 0.05, y: mouthY))
            mouth.addQuadCurve(to: CGPoint(x: c.x + s * 0.05, y: mouthY), control: CGPoint(x: c.x, y: mouthY + s * 0.05))
            context.stroke(mouth, with: .color(EyeStyle.ink), style: StrokeStyle(lineWidth: s * 0.024, lineCap: .round))
        }.accessibilityLabel("小瞳")
    }
}

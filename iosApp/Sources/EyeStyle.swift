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
    static let primarySoft = Color(rgb: 0xE3F4F2)
    static let neutralSoft = Color(rgb: 0xE9EFEE)
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
    /// EyeTypography：headline 34、title 22、subtitle 18、button 17、body 16、bodySecondary 13、chip 12。
    static let headline = Font.system(size: 34, weight: .semibold)
    static let chip = Font.system(size: 12, weight: .semibold)
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

/// 吉祥物「小瞳」：绘制见 Cartoon.mascot（逐项移植安卓 drawMascot）。
/// [scale] 为头宽占画布短边的比例，[centerY] 为头部中心的纵向位置（画布高度的比例）；
/// [gazeX] −1…1 为豆豆眼左右张望，[lid] 0 睁开…1 闭合。
struct MascotView: View {
    var scale: CGFloat = 0.8
    var centerY: CGFloat = 0.56
    var gazeX: CGFloat = 0
    var lid: CGFloat = 0
    var body: some View {
        Canvas { context, size in
            Cartoon.mascot(context, center: CGPoint(x: size.width / 2, y: size.height * centerY),
                size: min(size.width, size.height) * scale, gaze: CGPoint(x: gazeX, y: 0), lid: lid)
        }.accessibilityLabel("小瞳")
    }
}

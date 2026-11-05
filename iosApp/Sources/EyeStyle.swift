import SwiftUI
import UIKit

/// 对齐 core/designsystem 的令牌（2026-11 视觉改版，以「深海蓝」为标准）：每个颜色都随系统深浅色切换，
/// 浅色取 LightEyeColors、深色取 DarkEyeColors。iOS 绘制器只消费令牌，不定义另一套主题。
enum EyeStyle {
    static let background = dyn(0xF3F4F7, 0x050912)
    static let panel = dyn(0xFFFFFF, 0x0C1220)
    static let surfaceVariant = dyn(0xECEEF4, 0x121B2E)
    static let borderSubtle = dyn(0xDFE2EA, 0x1E2840)
    static let text = dyn(0x141A33, 0xF2F5FF)
    /// 示意图墨色：深浅主题都保持深色（视标、示意人物五官）。
    static let ink = dyn(0x141A33, 0x0B1020)
    static let secondary = dyn(0x464C66, 0xB4BCD2)
    static let tertiary = dyn(0x7D8298, 0x7A84A0)
    static let primary = dyn(0x23367A, 0x4A7DFF)
    static let primaryDark = dyn(0x1A2860, 0x7AA4FF)
    static let primarySoft = dyn(0xE6EAF5, 0x13214A)
    static let onPrimary = Color.white
    static let neutralSoft = dyn(0xECEEF4, 0x141C2E)
    static let brandStart = dyn(0xA9B8EE, 0xB4D0FF)
    static let brandEnd = dyn(0x23367A, 0x2E6BFF)
    /// 标志渐变起点（浅色用天蓝提亮，深色用按钮亮端）。
    static let brandMarkStart = dyn(0x2F5FD0, 0x5B95FF)
    static let accent = dyn(0xF5D90A, 0xFFE45C)
    static let accentSoft = dyn(0xFFF7CC, 0x2B2710)
    static let gray = dyn(0x7D8298, 0x6C7590)
    static let blue = dyn(0x23367A, 0x6E9BFF)
    static let warning = dyn(0x857400, 0xFFE45C)
    static let success = dyn(0x17804D, 0x5EE3A1)
    static let successSoft = dyn(0xE3F3EA, 0x0F2A22)
    static let warningSoft = dyn(0xFFF7CC, 0x2B2710)
    static let onStatus = dyn(0xFFFFFF, 0x050912)
    /// 柠檬黄「留意」色块上的深色字。
    static let onWarning = dyn(0x2A2400, 0x1A1600)
    static let danger = dyn(0xC9363F, 0xFF7A86)
    static let dangerSoft = dyn(0xFBE9EA, 0x2D1219)
    static let tooClose = dyn(0xD23A3A, 0xFF7A6E)
    static let tooFar = dyn(0x2F5FD0, 0x7AA4FF)
    /// 主卡渐变（浅色为白，深色为深蓝渐变）与描边。
    static let heroStart = dyn(0xFFFFFF, 0x18284F)
    static let heroEnd = dyn(0xFFFFFF, 0x0C1428)
    static let heroBorder = dyn(0xFFFFFF, 0x82A5FF, lightAlpha: 0.95, darkAlpha: 0.2)
    /// 主按钮渐变（浅色起止同色）。
    static let buttonStart = dyn(0x23367A, 0x2556F5)
    static let buttonEnd = dyn(0x2F4795, 0x5B95FF)
    /// 光流渐变（进度圆环、柱状图、进度条）：主蓝 → 青蓝，只用于数据图形。
    static let flowStart = dyn(0x23367A, 0x2E6BFF)
    static let flowEnd = dyn(0x4A9BE0, 0x3DDCE8)
    /// 光晕（只有深色发光，浅色透明）与投影色。
    static let glow = dyn(0x3C78FF, 0x3C78FF, lightAlpha: 0, darkAlpha: 1)
    static let shadow = dyn(0x19235A, 0x000000, lightAlpha: 0.2, darkAlpha: 0.6)
    /// 玻璃面：半透明填充 + 上亮下淡描边（与安卓 glassFill / glassEdgeTop / glassEdgeBottom 一致）。
    static let glassFill = dyn(0xFFFFFF, 0x0C1220, lightAlpha: 0.9, darkAlpha: 0.65)
    static let glassEdgeTop = dyn(0xFFFFFF, 0xFFFFFF, lightAlpha: 0.95, darkAlpha: 0.25)
    static let glassEdgeBottom = dyn(0x141E50, 0xFFFFFF, lightAlpha: 0.16, darkAlpha: 0.08)

    /// 固定浅色主色：白底测试画布上的注视目标（深色模式下也保持高对比）。
    static let fixedPrimary = Color(rgb: 0x23367A)

    /// EyeShapes：medium 14、large 16、panel 22、hero 26。
    static let radius: CGFloat = 22
    static let radiusMedium: CGFloat = 14
    static let radiusLarge: CGFloat = 16
    static let radiusHero: CGFloat = 26
    static let spacing: CGFloat = 12
    /// EyeTypography：display 56、headline 30、title 21、subtitle 17、button 16、body 16、bodySecondary 15、chip 13（最小 13）。
    static let display = Font.system(size: 56, weight: .bold)
    static let headline = Font.system(size: 30, weight: .bold)
    static let chip = Font.system(size: 13, weight: .semibold)
    static let title = Font.system(size: 21, weight: .semibold)
    static let subtitle = Font.system(size: 17, weight: .semibold)
    static let button = Font.system(size: 16, weight: .semibold)
    static let body = Font.system(size: 16)
    static let bodySecondary = Font.system(size: 15)
    static let metricValue = Font.system(size: 24, weight: .bold)
    /// EyeMotion.pressSpring：阻尼 0.6、中等刚度。
    static let pressSpring = Animation.spring(response: 0.3, dampingFraction: 0.6)

    /// 实心状态色块的底色与字色：「留意」（warning）用柠檬黄 accent 配深色字，其余语义色配 onStatus（与安卓 statusFill 一致）。
    static func statusFill(_ color: Color) -> (Color, Color) { color == warning ? (accent, onWarning) : (color, onStatus) }

    static var buttonGradient: LinearGradient {
        LinearGradient(colors: [buttonStart, buttonEnd], startPoint: .topLeading, endPoint: .bottomTrailing)
    }
    static var flowGradient: LinearGradient {
        LinearGradient(colors: [flowStart, flowEnd], startPoint: .leading, endPoint: .trailing)
    }
    static var heroGradient: LinearGradient {
        LinearGradient(colors: [heroStart, heroEnd], startPoint: .topLeading, endPoint: .bottomTrailing)
    }

    /// 浅色 / 深色两个取值的动态颜色。
    static func dyn(_ light: UInt32, _ dark: UInt32, lightAlpha: CGFloat = 1, darkAlpha: CGFloat = 1) -> Color {
        Color(UIColor { trait in
            trait.userInterfaceStyle == .dark ? UIColor(rgb: dark, alpha: darkAlpha) : UIColor(rgb: light, alpha: lightAlpha)
        })
    }
}

extension Color {
    init(rgb: UInt32) { self.init(red: Double((rgb >> 16) & 255) / 255, green: Double((rgb >> 8) & 255) / 255, blue: Double(rgb & 255) / 255) }
}

extension UIColor {
    convenience init(rgb: UInt32, alpha: CGFloat = 1) {
        self.init(red: CGFloat((rgb >> 16) & 255) / 255, green: CGFloat((rgb >> 8) & 255) / 255, blue: CGFloat(rgb & 255) / 255, alpha: alpha)
    }
}

/// 与安卓 EyeButton 同规格：最小高 52、圆角 14、按下弹簧缩到 96%，不用系统按压变暗。
/// 主按钮为按钮渐变底（深色带蓝色泛光）；次要操作（重试、延长、复测）用 `.secondary`，浅底与主按钮区分轻重。
struct EyeButtonStyle: ButtonStyle {
    var role: Role = .primary
    enum Role { case primary, secondary, danger }
    @Environment(\.isEnabled) private var enabled
    @Environment(\.colorScheme) private var scheme
    func makeBody(configuration: Configuration) -> some View {
        let shape = RoundedRectangle(cornerRadius: EyeStyle.radiusMedium, style: .continuous)
        let content: Color = switch role {
        case .primary: EyeStyle.onPrimary
        case .secondary: EyeStyle.text
        case .danger: EyeStyle.danger
        }
        return configuration.label.font(EyeStyle.button).multilineTextAlignment(.center)
            .padding(.horizontal, 16).padding(.vertical, 12).frame(maxWidth: .infinity, minHeight: 52)
            .foregroundStyle(content)
            .background {
                switch role {
                case .primary: shape.fill(EyeStyle.buttonGradient)
                    .shadow(color: scheme == .dark ? EyeStyle.glow.opacity(0.45) : EyeStyle.primary.opacity(0.3), radius: scheme == .dark ? 12 : 10, y: 5)
                case .secondary: shape.fill(EyeStyle.panel)
                    .overlay { shape.strokeBorder(EyeStyle.glassEdgeBottom, lineWidth: 1) }
                    .shadow(color: EyeStyle.shadow.opacity(0.6), radius: 3, y: 1)
                case .danger: shape.fill(EyeStyle.dangerSoft)
                }
            }
            .contentShape(shape)
            .scaleEffect(configuration.isPressed && enabled ? 0.96 : 1)
            .opacity(enabled ? 1 : 0.5)
            .animation(EyeStyle.pressSpring, value: configuration.isPressed)
    }
}

/// 入口卡与选项行：玻璃面圆角，按下轻缩，与安卓 EyeSelectCard 一致，不出现系统的整块变灰。
struct EyeCardButtonStyle: ButtonStyle {
    var selected = false
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(EyeStyle.body).foregroundStyle(EyeStyle.text)
            .padding(.horizontal, 16).padding(.vertical, 14).frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
            .eyeGlass(cornerRadius: EyeStyle.radiusLarge, fill: selected ? AnyShapeStyle(EyeStyle.primarySoft) : nil)
            .overlay { if selected { RoundedRectangle(cornerRadius: EyeStyle.radiusLarge, style: .continuous).stroke(EyeStyle.primary, lineWidth: 1.5) } }
            .contentShape(RoundedRectangle(cornerRadius: EyeStyle.radiusLarge))
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .animation(EyeStyle.pressSpring, value: configuration.isPressed)
    }
}

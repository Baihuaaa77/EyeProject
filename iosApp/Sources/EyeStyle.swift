import SwiftUI
import UIKit

/// 对齐 core/designsystem 的令牌（2026-11 视觉升级「深海蓝」：深海蓝底、深蓝卡片、电光蓝主操作、冰蓝点缀、香槟金「留意」）：每个颜色都随系统深浅色切换，
/// 浅色取 LightEyeColors、深色取 DarkEyeColors。iOS 绘制器只消费令牌，不定义另一套主题。
enum EyeStyle {
    static let background = dyn(0xF2F5FA, 0x081426)
    static let panel = dyn(0xFFFFFF, 0x132644)
    static let surfaceVariant = dyn(0xE9EEF7, 0x1B3157)
    static let borderSubtle = dyn(0xDCE3EE, 0x243C66)
    static let text = dyn(0x0F1B33, 0xF2F6FF)
    /// 示意图墨色：深浅主题都保持深色（视标、示意人物五官）。
    static let ink = dyn(0x0F1B33, 0x0B1324)
    static let secondary = dyn(0x44506A, 0xAFBCD8)
    static let tertiary = dyn(0x5F6B85, 0x8A9AC0)
    static let primary = dyn(0x2563EB, 0x6E9BFF)
    static let primaryDark = dyn(0x1D4ED8, 0x9DBBFF)
    static let primarySoft = dyn(0xE6EEFE, 0x1C3566)
    static let onPrimary = Color.white
    static let neutralSoft = dyn(0xE9EEF7, 0x1B3157)
    static let brandStart = dyn(0x9CC0FF, 0x9DC4FF)
    static let brandEnd = dyn(0x2563EB, 0x3578FF)
    /// 标志渐变起点（浅色用天蓝提亮，深色用按钮亮端）。
    /// 冰蓝荧光点缀（少量使用；白天不作文字）。
    static let ice = dyn(0x36C5E8, 0x49D9F5)
    /// 图标配色：冰蓝、薰衣草紫两组（底色为同色调浅 / 深块），与安卓 iceInk / iceSoft / violet / violetSoft 一致。
    static let iceInk = dyn(0x0E8FB0, 0x49D9F5)
    static let iceSoft = dyn(0xE3F6FB, 0x12324A)
    static let violet = dyn(0x6D5BD0, 0xA99BFF)
    static let violetSoft = dyn(0xEEEBFD, 0x29285A)
    static let brandMarkStart = dyn(0x3578FF, 0x6E9BFF)
    static let accent = dyn(0xF2CB70, 0xF2CB70)
    static let accentSoft = dyn(0xFBF3DE, 0x2E2814)
    static let gray = dyn(0x5F6B85, 0x8A9AC0)
    static let blue = dyn(0x2563EB, 0x6E9BFF)
    static let warning = dyn(0x8A6200, 0xF2CB70)
    // 测试到位提示与演示插画（短发男孩）；与安卓 Illustration.kt 同值。
    static let testReady = dyn(0x137538, 0x91C8D3)
    static let testTooClose = dyn(0xB42332, 0xFF7A86)
    static let testReadySoft = dyn(0xE3F5EA, 0x1A3547)
    /// 首页功能色块（护眼操薄荷绿、用眼趋势紫、亮度与蓝光珊瑚红、AI 主动问诊蓝）：左上到右下渐变，上面文字与图标一律白色。
    /// 深色主题压暗一档；与安卓 Vivid.kt 同值。
    enum Vivid {
        static let blue = gradient(dyn(0x4F8DFF, 0x3F7DF5), dyn(0x2B5FEA, 0x2550D2))
        static let red = gradient(dyn(0xFF7D6B, 0xF0685C), dyn(0xEC4A5C, 0xD33E52))
        static let violet = gradient(dyn(0x9D7CFF, 0x8C6DF2), dyn(0x6B47E6, 0x5C3ED2))
        static let mint = gradient(dyn(0x4FE0DF, 0x33C8CC), dyn(0x22BCC6, 0x169FAA))
        static let on = Color.white
        private static func gradient(_ top: Color, _ bottom: Color) -> LinearGradient {
            LinearGradient(colors: [top, bottom], startPoint: .topLeading, endPoint: .bottomTrailing)
        }
    }
    enum Illustration {
        static let skin = Color(rgb: 0xFFCBA8)
        /// 脖子、耳内、领口阴影
        static let skinShade = Color(rgb: 0xEBA884)
        /// 鼻子、手的轮廓与指缝
        static let skinLine = Color(rgb: 0xC98463)
        static let blush = Color(rgb: 0xF4A58A)
        static let hair = Color(rgb: 0x3D4956)
        static let mouth = Color(rgb: 0x8E4F42)
        static let shirt = EyeStyle.dyn(0x557FC1, 0x648BC8)
        /// 领口滚边
        static let shirtLight = EyeStyle.dyn(0x7599D1, 0x86A6DA)
    }
    static let success = dyn(0x15803D, 0x5EE3A1)
    static let successSoft = dyn(0xE3F5EA, 0x10302A)
    static let warningSoft = dyn(0xFBF3DE, 0x2E2814)
    static let onStatus = dyn(0xFFFFFF, 0x081426)
    /// 柠檬黄「留意」色块上的深色字。
    static let onWarning = dyn(0x2A1F00, 0x1B1608)
    static let danger = dyn(0xD92D3A, 0xFF7A86)
    static let dangerSoft = dyn(0xFDE8EA, 0x3A1A24)
    static let tooClose = dyn(0xD23A3A, 0xFF7A6E)
    static let tooFar = dyn(0x2563EB, 0x6E9BFF)
    /// 主卡渐变（浅色为白，深色为深蓝渐变）与描边。
    static let heroStart = dyn(0xFFFFFF, 0x1C3868)
    static let heroEnd = dyn(0xF4F7FD, 0x132644)
    static let heroBorder = dyn(0xFFFFFF, 0x49D9F5, lightAlpha: 1, darkAlpha: 0.2)
    /// 主按钮渐变（浅色起止同色）。
    static let buttonStart = dyn(0x2563EB, 0x2A66F0)
    static let buttonEnd = dyn(0x3578FF, 0x3578FF)
    /// 光流渐变（进度圆环、柱状图、进度条）：主蓝 → 青蓝，只用于数据图形。
    static let flowStart = dyn(0x3578FF, 0x3578FF)
    static let flowEnd = dyn(0x36C5E8, 0x49D9F5)
    /// 光晕（只有深色发光，浅色透明）与投影色。
    static let glow = dyn(0x49D9F5, 0x3578FF, lightAlpha: 0.33, darkAlpha: 1)
    static let shadow = dyn(0x1B2B55, 0x000000, lightAlpha: 0.18, darkAlpha: 0.6)
    /// 玻璃面：半透明填充 + 上亮下淡描边（与安卓 glassFill / glassEdgeTop / glassEdgeBottom 一致）。
    static let glassFill = dyn(0xFFFFFF, 0x132644, lightAlpha: 0.96, darkAlpha: 0.95)
    static let glassEdgeTop = dyn(0xFFFFFF, 0xFFFFFF, lightAlpha: 1, darkAlpha: 0.18)
    static let glassEdgeBottom = dyn(0x0F1B33, 0xFFFFFF, lightAlpha: 0.12, darkAlpha: 0.06)

    /// 固定浅色主色：白底测试画布上的注视目标（深色模式下也保持高对比）。
    static let fixedPrimary = Color(rgb: 0x23367A)

    /// EyeShapes：small 12（图标块）、medium 14（按钮 / 输入）、large 16（内嵌面板）、card / panel 20、hero 24。
    static let radiusSmall: CGFloat = 8
    static let radius: CGFloat = 14
    static let radiusMedium: CGFloat = 10
    static let radiusLarge: CGFloat = 12
    static let radiusHero: CGFloat = 16
    /// 标签、小按钮（不再用整圆胶囊；细进度条高度不足 16 时自然仍是圆头）。与安卓 EyeShapes.chip 一致。
    static let radiusChip: CGFloat = 8
    static var chipShape: RoundedRectangle { RoundedRectangle(cornerRadius: radiusChip, style: .continuous) }
    static let spacing: CGFloat = 12
    /// EyeTypography 阶梯 13 / 14 / 16 / 18 / 22 / 30 / 56：大数字 display 56 → 页标题 headline 30 → 分区标题 title 22 →
    /// 卡片标题 subtitle 18 → 正文 body 16 → 辅助 bodySecondary 14 → 标签 chip 13（最小）；读数 metricValue 26、按钮 button 16。
    static let display = Font.system(size: 56, weight: .bold)
    static let headline = Font.system(size: 30, weight: .bold)
    static let chip = Font.system(size: 13, weight: .semibold)
    static let title = Font.system(size: 22, weight: .semibold)
    static let subtitle = Font.system(size: 18, weight: .semibold)
    static let button = Font.system(size: 16, weight: .semibold)
    static let body = Font.system(size: 16)
    static let bodySecondary = Font.system(size: 14)
    static let metricValue = Font.system(size: 26, weight: .bold)
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

/// 间距令牌（4 / 8 节奏），与安卓 EyeSpacing 一一对应：元素内 xs / sm → 卡片内 md → 卡片之间 cardGap → 分区之间 xxl；页面左右留白 gutter。
enum EyeSpacing {
    static let xxs: CGFloat = 4
    static let xs: CGFloat = 8
    static let sm: CGFloat = 12
    static let md: CGFloat = 16
    static let lg: CGFloat = 20
    static let xl: CGFloat = 24
    static let xxl: CGFloat = 32
    static let gutter: CGFloat = 16
    static let cardPadding: CGFloat = 20
    static let heroPadding: CGFloat = 24
    static let cardGap: CGFloat = 16
    static let itemGap: CGFloat = 12
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
                    .shadow(color: scheme == .dark ? EyeStyle.glow.opacity(0.45) : EyeStyle.buttonEnd.opacity(0.35), radius: 12, y: 5)
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

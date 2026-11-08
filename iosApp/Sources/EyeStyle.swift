import SwiftUI
import UIKit

/// 对齐 core/designsystem 的令牌（2026-11-08 视觉改版「澄澈」：白天浅灰底白卡、夜晚纯黑底深灰卡，卡片不描边不投影、不用光晕与大面积渐变，
/// 品牌蓝按钮 + 类别色只用在小图标与图表上）：每个颜色都随系统深浅色切换，浅色取 LightEyeColors、深色取 DarkEyeColors。
/// iOS 绘制器只消费令牌，不定义另一套主题。
enum EyeStyle {
    static let background = dyn(0xF2F2F7, 0x000000)
    static let panel = dyn(0xFFFFFF, 0x1C1C1E)
    /// 浅填充：次要按钮、分段控件底、输入框、圆环底环。
    static let surfaceVariant = dyn(0xE9E9EE, 0x2C2C2E)
    static let borderSubtle = dyn(0xE3E3E8, 0x38383A)
    static let text = dyn(0x1C1C1E, 0xF5F5F7)
    /// 示意图墨色：深浅主题都保持深色（视标、示意人物五官）。
    static let ink = dyn(0x1C1C1E, 0x0B0B0D)
    static let secondary = dyn(0x636366, 0xAEAEB2)
    static let tertiary = dyn(0x737378, 0x8E8E93)
    static let primary = dyn(0x0A66D8, 0x4C9BFF)
    static let primaryDark = dyn(0x0852B0, 0x8AB8FF)
    static let primarySoft = dyn(0xE8F0FB, 0x243042)
    static let onPrimary = Color.white
    static let neutralSoft = dyn(0xE9E9EE, 0x2C2C2E)
    static let brandStart = dyn(0x0A66D8, 0x4C9BFF)
    static let brandEnd = dyn(0x0A66D8, 0x4C9BFF)
    /// 眨眼类别（青）：ice 图形、iceInk 文字与图标、iceSoft 浅底。
    static let ice = dyn(0x14A3B3, 0x3CC8D6)
    static let iceInk = dyn(0x08808D, 0x3CC8D6)
    static let iceSoft = dyn(0xE3F3F4, 0x3CC8D6, lightAlpha: 1, darkAlpha: 0.16)
    /// AI 爱科类别（紫）。
    static let violet = dyn(0x7A4FD8, 0xB49CFF)
    static let violetSoft = dyn(0xF1ECFC, 0xB49CFF, lightAlpha: 1, darkAlpha: 0.16)
    /// 用屏类别（靛蓝）。
    static let screen = dyn(0x5856D6, 0x8E8CFF)
    static let screenSoft = dyn(0xEEEEFC, 0x8E8CFF, lightAlpha: 1, darkAlpha: 0.16)
    /// 光照类别（橙）。
    static let sun = dyn(0xB4500A, 0xFF9F43)
    static let sunSoft = dyn(0xFCEFE3, 0xFF9F43, lightAlpha: 1, darkAlpha: 0.16)
    static let brandMarkStart = dyn(0x0A66D8, 0x4C9BFF)
    /// 琥珀「留意」：圆环、状态点与色块底（色块上配深色字 onWarning；浅底上作文字用 warning）。
    static let accent = dyn(0xF5A524, 0xFFB340)
    static let accentSoft = dyn(0xFDF1DE, 0xFFB340, lightAlpha: 1, darkAlpha: 0.16)
    static let gray = dyn(0x737378, 0x8E8E93)
    static let blue = dyn(0x0A66D8, 0x4C9BFF)
    static let warning = dyn(0xA15C00, 0xFFB340)
    // 测试到位提示（与「正常」同一组绿色）与演示插画（短发男孩）；与安卓 Illustration.kt 同值。
    static let testReady = dyn(0x1C7F3B, 0x4CD675)
    static let testTooClose = dyn(0xB42332, 0xFF6B6B)
    static let testReadySoft = dyn(0xE6F4EA, 0x4CD675, lightAlpha: 1, darkAlpha: 0.16)
    /// 列表图标块的实心底色（白色图标），两套主题同值。与安卓 tileBlue 等一致。
    enum Tile {
        static let blue = Color(rgb: 0x2F7CF6)
        static let indigo = Color(rgb: 0x5E5CE6)
        static let teal = Color(rgb: 0x14A3B3)
        static let orange = Color(rgb: 0xF28C28)
        static let green = Color(rgb: 0x30B158)
        static let red = Color(rgb: 0xF0454F)
        static let gray = Color(rgb: 0x8E8E93)
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
    static let success = dyn(0x1C7F3B, 0x4CD675)
    static let successSoft = dyn(0xE6F4EA, 0x4CD675, lightAlpha: 1, darkAlpha: 0.16)
    static let warningSoft = dyn(0xFDF1DE, 0xFFB340, lightAlpha: 1, darkAlpha: 0.16)
    static let onStatus = dyn(0xFFFFFF, 0x000000)
    /// 琥珀「留意」色块上的深色字。
    static let onWarning = dyn(0x2A1F00, 0x1B1608)
    static let danger = dyn(0xD70015, 0xFF6B6B)
    static let dangerSoft = dyn(0xFDECEE, 0xFF6B6B, lightAlpha: 1, darkAlpha: 0.16)
    static let tooClose = dyn(0xD70015, 0xFF6B6B)
    static let tooFar = dyn(0x0A66D8, 0x4C9BFF)
    /// 主卡底（改版后与普通卡片同色、纯色）；描边令牌透明。
    static let heroStart = dyn(0xFFFFFF, 0x1C1C1E)
    static let heroEnd = dyn(0xFFFFFF, 0x1C1C1E)
    static let heroBorder = Color.clear
    /// 主按钮底：品牌蓝纯色（两套主题同值，白字 ≥ 5:1）。
    static let buttonStart = dyn(0x0A66D8, 0x0A66D8)
    static let buttonEnd = dyn(0x0A66D8, 0x0A66D8)
    /// 数据图形色（纯色）。
    static let flowStart = dyn(0x0A66D8, 0x4C9BFF)
    static let flowEnd = dyn(0x0A66D8, 0x4C9BFF)
    /// 光晕（改版后透明，不再发光）与投影色（只给浮在内容上的底栏用）。
    static let glow = Color.clear
    static let shadow = dyn(0x000000, 0x000000, lightAlpha: 0.12, darkAlpha: 0.55)
    /// 卡片面（纯色）；描边令牌改版后透明。
    static let glassFill = dyn(0xFFFFFF, 0x1C1C1E)
    static let glassEdgeTop = Color.clear
    static let glassEdgeBottom = Color.clear

    /// 固定浅色主色：白底测试画布上的注视目标（深色模式下也保持高对比）。
    static let fixedPrimary = Color(rgb: 0x23367A)

    /// EyeShapes（.continuous 平滑圆角）：small 10（图标块）、medium 14（按钮 / 输入）、large 14（内嵌块）、card / panel 18、hero 22、chip 10。
    static let radiusSmall: CGFloat = 10
    static let radius: CGFloat = 18
    static let radiusMedium: CGFloat = 14
    static let radiusLarge: CGFloat = 14
    static let radiusHero: CGFloat = 22
    /// 标签、小按钮。与安卓 EyeShapes.chip 一致。
    static let radiusChip: CGFloat = 10
    static var chipShape: RoundedRectangle { RoundedRectangle(cornerRadius: radiusChip, style: .continuous) }
    static let spacing: CGFloat = 12
    /// EyeTypography：页面大标题 headline 32 → 分区标题 title 20 → 卡片标题 subtitle 17 → 正文 body 16 → 辅助 bodySecondary 14 →
    /// 标签 chip 13 → 最小 caption 11；数字用 SF Pro Rounded：大数字 display 56、读数 metricValue 28，单位 unit 14（次要文字色）；按钮 17。
    /// 中文最粗用到 semibold，bold 只留给页面大标题。
    static let display = Font.system(size: 56, weight: .semibold, design: .rounded)
    static let headline = Font.system(size: 32, weight: .bold)
    static let chip = Font.system(size: 13, weight: .semibold)
    static let caption = Font.system(size: 11, weight: .semibold)
    static let title = Font.system(size: 20, weight: .semibold)
    static let subtitle = Font.system(size: 17, weight: .semibold)
    static let button = Font.system(size: 17, weight: .semibold)
    static let body = Font.system(size: 16)
    static let bodySecondary = Font.system(size: 14)
    static let metricValue = Font.system(size: 28, weight: .semibold, design: .rounded)
    static let unit = Font.system(size: 14, weight: .medium)
    /// EyeMotion.pressSpring：阻尼 0.6、中等刚度。
    static let pressSpring = Animation.spring(response: 0.3, dampingFraction: 0.6)

    /// 实心状态色块的底色与字色：「留意」（warning）用琥珀 accent 配深色字，其余语义色配 onStatus（与安卓 statusFill 一致）。
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

    /// 读数排版：把「6小时25分」「180 lx」「22 次/分」拆成数字与单位——数字用 [numberFont]，单位用 unit 字号、次要文字色。
    /// 与安卓 eyeReading 一致。
    static func reading(_ value: String, unit extra: String? = nil, numberFont: Font = EyeStyle.metricValue, color: Color = EyeStyle.text) -> Text {
        let full = extra.map { "\(value) \($0)" } ?? value
        func numeric(_ c: Character) -> Bool { c.isNumber || ".,:+−—".contains(c) }
        var result = Text("")
        var run = ""
        var runIsNumber: Bool? = nil
        var last: Bool? = nil
        func flush() {
            guard let isNumber = runIsNumber, !run.isEmpty else { return }
            if let last, last != isNumber { result = result + Text("\u{2009}") }
            result = result + (isNumber ? Text(run).font(numberFont).foregroundColor(color) : Text(run).font(unit).foregroundColor(secondary))
            last = isNumber
            run = ""
        }
        for c in full {
            if c.isWhitespace { flush(); runIsNumber = nil; result = result + Text(String(c)).font(unit); last = nil; continue }
            let isNumber = numeric(c)
            if runIsNumber != isNumber { flush(); runIsNumber = isNumber }
            run.append(c)
        }
        flush()
        return result
    }

    /// 浅色 / 深色两个取值的动态颜色。
    static func dyn(_ light: UInt32, _ dark: UInt32, lightAlpha: CGFloat = 1, darkAlpha: CGFloat = 1) -> Color {
        Color(UIColor { trait in
            trait.userInterfaceStyle == .dark ? UIColor(rgb: dark, alpha: darkAlpha) : UIColor(rgb: light, alpha: lightAlpha)
        })
    }
}

/// 间距令牌（4 / 8 节奏），与安卓 EyeSpacing 一一对应：元素内 xs / sm → 卡片内 md → 卡片之间 cardGap → 分区之间 sectionGap；页面左右留白 gutter。
enum EyeSpacing {
    static let xxs: CGFloat = 4
    static let xs: CGFloat = 8
    static let sm: CGFloat = 12
    static let md: CGFloat = 16
    static let lg: CGFloat = 20
    static let xl: CGFloat = 24
    static let xxl: CGFloat = 32
    static let gutter: CGFloat = 16
    static let cardPadding: CGFloat = 16
    static let heroPadding: CGFloat = 20
    static let cardGap: CGFloat = 12
    static let sectionGap: CGFloat = 28
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

/// 与安卓 EyeButton 同规格：最小高 50、圆角 14、按下弹簧缩到 96%，不用系统按压变暗。
/// 主按钮品牌蓝纯色底；次要操作（重试、延长、复测）用 `.secondary` 浅填充底；都不描边、不投影。
struct EyeButtonStyle: ButtonStyle {
    var role: Role = .primary
    enum Role { case primary, secondary, danger }
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        let shape = RoundedRectangle(cornerRadius: EyeStyle.radiusMedium, style: .continuous)
        let content: Color = switch role {
        case .primary: EyeStyle.onPrimary
        case .secondary: EyeStyle.text
        case .danger: EyeStyle.danger
        }
        let fill: Color = switch role {
        case .primary: EyeStyle.buttonStart
        case .secondary: EyeStyle.surfaceVariant
        case .danger: EyeStyle.dangerSoft
        }
        return configuration.label.font(EyeStyle.button).multilineTextAlignment(.center)
            .padding(.horizontal, 16).padding(.vertical, 12).frame(maxWidth: .infinity, minHeight: 50)
            .foregroundStyle(content)
            .background(fill, in: shape)
            .contentShape(shape)
            .scaleEffect(configuration.isPressed && enabled ? 0.96 : 1)
            .opacity(enabled ? 1 : 0.5)
            .animation(EyeStyle.pressSpring, value: configuration.isPressed)
    }
}

/// 入口卡与选项行：纯色圆角面，按下轻缩，与安卓 EyeSelectCard 一致，不出现系统的整块变灰。
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

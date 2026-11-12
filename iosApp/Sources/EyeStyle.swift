import SwiftUI
import UIKit

/// DESIGN.md 的双端主题令牌：同色浮雕表面、中性文字与蓝青渐变数据。
enum EyeStyle {
    static let background = dyn(0xF5F6F8, 0x1E2023)
    static let optotypeSurround = dyn(0xFFFFFF, 0x1E2023, darkAlpha: 0)
    static let guideStageHeight: CGFloat = 200
    static let gazeCanvasHeight: CGFloat = 480
    static let gazeCanvasMinHeight: CGFloat = 160
    static let optotypeField = Color.white
    static func gazeCanvasBaseHeight(_ screenHeight: CGFloat) -> CGFloat {
        min(gazeCanvasHeight, max(gazeCanvasMinHeight, screenHeight * (screenHeight < gazeCanvasHeight ? 0.38 : 0.58)))
    }
    static let panel = dyn(0xFFFFFF, 0x26282B)
    /// 浅填充：次要按钮、分段控件底、输入框、圆环底环。
    static let surfaceVariant = dyn(0xE9EDF3, 0x1B1D20)
    static let guideBackground = Color(rgb: 0xE9EDF3)
    static let borderSubtle = dyn(0x5D6280, 0xFFFFFF, lightAlpha: 0.1216, darkAlpha: 0.0706)
    static let text = dyn(0x313949, 0xE3E5E8)
    /// 示意图墨色：深浅主题都保持深色（视标、示意人物五官）。
    static let ink = dyn(0x141A2B, 0x141A2B)
    static let secondary = dyn(0x5D6280, 0x9A9DA3)
    static let tertiary = dyn(0x878EA3, 0x73767C)
    static let primary = dyn(0x2F7BFF, 0x2F7BFF)
    static let primaryDark = dyn(0x0A6CFF, 0x0A6CFF)
    static let primarySoft = dyn(0xE9EDF3, 0x1B1D20)
    static let onPrimary = dyn(0x313949, 0xE3E5E8)
    static let neutralSoft = dyn(0xE8EDF7, 0x1B1D20)
    static let brandStart = dyn(0x2F7BFF, 0x2F7BFF)
    static let brandEnd = dyn(0x00EFE0, 0x00EFE0)
    /// 眨眼类别（青）：ice 图形、iceInk 文字与图标、iceSoft 浅底。
    static let ice = dyn(0x18C2FF, 0x18C2FF)
    static let iceInk = dyn(0x5D6280, 0x9A9DA3)
    static let iceSoft = dyn(0xE8EDF7, 0x1B1D20)
    /// AI 爱科类别（紫）。
    static let violet = dyn(0x8B78E6, 0x8B78E6)
    static let violetSoft = dyn(0xE8EDF7, 0x1B1D20)
    /// 用屏类别（靛蓝）。
    static let screen = dyn(0x2F7BFF, 0x2F7BFF)
    static let screenSoft = dyn(0xE8EDF7, 0x1B1D20)
    /// 光照类别（橙）。
    static let sun = dyn(0xFFB020, 0xFFB020)
    static let sunSoft = dyn(0xE8EDF7, 0x1B1D20)
    static let brandMarkStart = dyn(0x2F7BFF, 0x2F7BFF)
    /// 琥珀「留意」：圆环、状态点与色块底（色块上配深色字 onWarning；浅底上作文字用 warning）。
    static let accent = dyn(0xFFB020, 0xFFB020)
    static let accentSoft = dyn(0xE8EDF7, 0x1B1D20)
    static let gray = dyn(0x878EA3, 0x73767C)
    static let blue = dyn(0x2F7BFF, 0x2F7BFF)
    static let warning = dyn(0xFFB020, 0xFFB020)
    // 测试到位提示使用品牌主蓝，距离过远使用黄色；与安卓 Illustration.kt 同值。
    static let testReady = primary
    static let testTooFarText = dyn(0x8A6500, 0xFFE45C)
    static let testTooClose = dyn(0xFF5A6E, 0xFF5A6E)
    static let testReadySoft = primarySoft
    enum Tile {
        static let blue = Color(rgb: 0x2F7BFF)
        static let indigo = Color(rgb: 0x8B78E6)
        static let teal = Color(rgb: 0x2F7BFF)
        static let orange = Color(rgb: 0xFFB020)
        static let green = Color(rgb: 0x8B78E6)
        static let red = Color(rgb: 0xFF5A6E)
        static let gray = Color(rgb: 0x878EA3)
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
        /// 领口与袖口滚边
        static let shirtLight = EyeStyle.dyn(0x7599D1, 0x86A6DA)
        /// 袖子的描边：手臂叠在身体上时把两者分开
        static let shirtShade = EyeStyle.dyn(0x4A72B3, 0x5479B4)
    }
    static let success = dyn(0x2F7BFF, 0x2F7BFF)
    static let successSoft = dyn(0xE8EDF7, 0x1B1D20)
    static let warningSoft = dyn(0xE8EDF7, 0x1B1D20)
    static let onStatus = dyn(0x141A2B, 0xF2F3F5)
    /// 琥珀「留意」色块上的深色字。
    static let onWarning = dyn(0x141A2B, 0xF2F3F5)
    static let danger = dyn(0xFF5A6E, 0xFF5A6E)
    static let dangerSoft = dyn(0xE8EDF7, 0x1B1D20)
    static let tooClose = dyn(0xFF5A6E, 0xFF5A6E)
    static let tooFar = dyn(0xFFE45C, 0xFFE45C)
    static let heroStart = dyn(0xFFFFFF, 0x26282B)
    static let heroEnd = dyn(0xFFFFFF, 0x26282B)
    static let heroBorder = Color.clear
    /// 同色浮雕按钮表面，渐变短线标记主操作。
    static let buttonStart = dyn(0xEAF2FF, 0x303844)
    static let buttonEnd = dyn(0xEAF2FF, 0x303844)
    /// 蓝 → 水绿的数据图形渐变。
    static let flowStart = dyn(0x2F7BFF, 0x2F7BFF)
    static let flowEnd = dyn(0x00EFE0, 0x00EFE0)
    static let glow = dyn(0x00EFE0, 0x00EFE0, lightAlpha: 0.55, darkAlpha: 0.7)
    static let shadow = dyn(0xA3B1CD, 0x000000, lightAlpha: 0.4510, darkAlpha: 0.5490)
    static let glassFill = dyn(0xFFFFFF, 0x26282B)
    static let glassEdgeTop = dyn(0xFFFFFF, 0xFFFFFF, lightAlpha: 0, darkAlpha: 0.035)
    static let glassEdgeBottom = Color.clear

    static let heading = dyn(0x141A2B, 0xF2F3F5)
    static let shadowLight = dyn(0xFFFFFF, 0xFFFFFF, lightAlpha: 0.95, darkAlpha: 0.045)
    static let glowRadius: CGFloat = 7
    static let chatAvatarSize: CGFloat = 48
    static let chatIntroPortraitWidth: CGFloat = 112
    static let chatIntroPortraitHeight: CGFloat = 144
    static let homePortraitWidth: CGFloat = 124
    static let homePortraitHeight: CGFloat = 160
    static let homePortraitInset: CGFloat = 8
    static let homeChatMinHeight: CGFloat = 180
    static let ringGlowStrokeScale: CGFloat = 1.7
    /// 宽光晕描边超出的半宽，加三倍模糊半径，给滤镜完整的绘制空间。
    static func ringGlowOutset(lineWidth: CGFloat) -> CGFloat {
        glowRadius * 3 + lineWidth * (ringGlowStrokeScale - 1) / 2
    }
    static let tickGlowRadius: CGFloat = 2
    static let cardBorderWidth: CGFloat = 1.2
    static let cardBorderInset: CGFloat = 1.5
    static let cardGlowRadius: CGFloat = 3
    static let cardBorderOpacityLight: Double = 0.72
    static let cardBorderOpacityDark: Double = 0.64
    static let cardGlowOpacity: Double = 0.38
    static let cardBorderSubtle: Double = 0.65
    static var borderGradient: LinearGradient {
        LinearGradient(colors: [brandStart, dataCyan, violet], startPoint: .topLeading, endPoint: .bottomTrailing)
    }
    static let liquidShadowRadius: CGFloat = 18
    static let liquidShadowOffset: CGFloat = 8
    static let liquidShadowOpacity: Double = 0.65
    static let liquidReflectionRadius: CGFloat = 220
    static let liquidDockRadius: CGFloat = 32
    static let liquidSelectedOpacity: Double = 0.10
    static let liquidRimWidth: CGFloat = 1.2
    static let liquidInnerInset: CGFloat = 3
    static let liquidTintLight: Double = 0.045
    static let liquidTintDark: Double = 0.11
    static let liquidReflectionLight: Double = 0.72
    static let liquidReflectionDark: Double = 0.23
    static let liquidEdgeLight: Double = 0.92
    static let liquidEdgeDark: Double = 0.50
    static let glowOpacity: Double = 0.9
    static let glowCoreOpacity: Double = 0.32
    static let dataCyan = Color(rgb: 0x18C2FF)
    static let dataAqua = Color(rgb: 0x00EFE0)
    static let ringTrack = dyn(0xD1DDEE, 0x344458)
    static let dataMint = Color(rgb: 0x2CF2C8)
    static var dataGradient: LinearGradient {
        LinearGradient(colors: [brandStart, dataCyan, dataAqua], startPoint: .top, endPoint: .bottom)
    }
    static var ringGradient: AngularGradient {
        AngularGradient(stops: [.init(color: brandStart, location: 0), .init(color: dataCyan, location: 0.39),
            .init(color: dataAqua, location: 0.72), .init(color: dataAqua, location: 1)], center: .center)
    }
    static func readableText(_ color: Color) -> Color {
        if [primary, screen, blue, violet, Tile.indigo, Tile.teal].contains(color) { return text }
        if [ice, dataCyan, dataAqua, dataMint, sun, accent, warning, danger, success, tooClose].contains(color) { return text }
        return color
    }

    /// 固定浅色主色：白底测试画布上的注视目标（深色模式下也保持高对比）。
    static let fixedPrimary = Color(rgb: 0x23367A)

    static let radiusSmall: CGFloat = 10
    static let radius: CGFloat = 20
    static let radiusMedium: CGFloat = 14
    static let radiusLarge: CGFloat = 14
    static let radiusHero: CGFloat = 20
    /// 标签、小按钮。与安卓 EyeShapes.chip 一致。
    static let radiusChip: CGFloat = 999
    static var chipShape: RoundedRectangle { RoundedRectangle(cornerRadius: radiusChip, style: .continuous) }
    static let spacing: CGFloat = 12
    /// DESIGN.md 字阶：主数字 48、页面标题 22、卡片读数 26、正文 15、辅助 13。
    /// 中文标题最多使用 semibold。
    static let healthScore = Font.custom("Nunito-Black", size: 60, relativeTo: .largeTitle)
    static let healthScoreCompact = Font.custom("Nunito-Black", size: 48, relativeTo: .largeTitle)
    static let moodGold = Color(rgb: 0xFFCE65)
    static let moodWarm = Color(rgb: 0xE8BE7C)
    static let moodSilver = Color(rgb: 0xCAD0DA)
    static let chatBody = Font.system(size: 18)
    static let chatUserBackground = primaryDark
    static let chatUserText = Color.white
    static let display = Font.system(size: 60, weight: .semibold)
    static let displayCompact = Font.system(size: 48, weight: .semibold)
    static let headline = Font.system(size: 28, weight: .semibold)
    static let chip = Font.system(size: 13, weight: .semibold)
    static let caption = Font.system(size: 12, weight: .semibold)
    static let tab = Font.system(size: 11, weight: .medium)
    static let title = Font.system(size: 22, weight: .semibold)
    static let subtitle = Font.system(size: 20, weight: .semibold)
    static let label = Font.system(size: 16, weight: .semibold)
    static let navigationTitle = Font.system(size: 18, weight: .semibold)
    static let button = Font.system(size: 17, weight: .semibold)
    static let body = Font.system(size: 16)
    static let bodySecondary = Font.system(size: 14)
    static let metricValue = Font.system(size: 30, weight: .semibold)
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
            result = result + (isNumber ? Text(run).font(numberFont).foregroundColor(readableText(color)) : Text(run).font(unit).foregroundColor(secondary))
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
    static let gutter: CGFloat = 20
    static let cardPadding: CGFloat = 20
    static let heroPadding: CGFloat = 20
    static let cardGap: CGFloat = 16
    static let sectionGap: CGFloat = 24
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
struct EyeButtonStyle: ButtonStyle {
    var role: Role = .primary
    enum Role { case primary, secondary, danger }
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        let shape = RoundedRectangle(cornerRadius: EyeStyle.radiusMedium, style: .continuous)
        let content: Color = switch role {
        case .primary: EyeStyle.onPrimary
        case .secondary: EyeStyle.text
        case .danger: EyeStyle.text
        }
        let fill: Color = switch role {
        case .primary: EyeStyle.buttonStart
        case .secondary: EyeStyle.panel
        case .danger: EyeStyle.dangerSoft
        }
        return configuration.label.font(EyeStyle.button).multilineTextAlignment(.center)
            .padding(.horizontal, 16).padding(.vertical, 12).frame(maxWidth: .infinity, minHeight: 52)
            .foregroundStyle(content)
            .eyeGlass(cornerRadius: EyeStyle.radiusMedium, fill: AnyShapeStyle(fill), pressed: configuration.isPressed)
            .overlay {
                if role != .danger && enabled {
                    shape.inset(by: 1.8).stroke(EyeStyle.borderGradient, lineWidth: 1.2).opacity(role == .primary ? 1 : 0.55)
                        .shadow(color: EyeStyle.primary.opacity(0.65), radius: EyeStyle.glowRadius * 0.5)
                        .allowsHitTesting(false)
                }
            }
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
            .eyeGlass(cornerRadius: EyeStyle.radiusLarge, fill: selected ? AnyShapeStyle(EyeStyle.primarySoft) : nil, pressed: selected)
            .contentShape(RoundedRectangle(cornerRadius: EyeStyle.radiusLarge))
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .animation(EyeStyle.pressSpring, value: configuration.isPressed)
    }
}

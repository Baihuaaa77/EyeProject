import SwiftUI
import UIKit

/// DESIGN.md 的双端主题令牌：深咖啡背景、暖棕表面与亮橙强调色。
enum EyeStyle {
    // 诊断视频的语义颜色，与品牌橙色目标区分。
    static let diagnosticLeft = UIColor.systemCyan
    static let diagnosticRight = UIColor.systemPink
    static let diagnosticReference = UIColor.systemGreen
    static let diagnosticGlint = UIColor.white
    static let background = dyn(0x1B1511, 0x1B1511)
    static let optotypeSurround = dyn(0xFFFFFF, 0x1E2023, darkAlpha: 0)
    static let guideStageHeight: CGFloat = 200
    static let gazeCanvasHeight: CGFloat = 600
    static let gazeCanvasMinHeight: CGFloat = 160
    static let optotypeField = Color.white
    static let reflectionBackground = Color.black
    static let reflectionTarget = Color.white
    static func gazeCanvasBaseHeight(_ screenHeight: CGFloat) -> CGFloat {
        min(gazeCanvasHeight, max(gazeCanvasMinHeight, screenHeight * (screenHeight < gazeCanvasHeight ? 0.52 : 0.72)))
    }
    static let panel = dyn(0x30231A, 0x30231A)
    /// 浅填充：次要按钮、分段控件底、输入框、圆环底环。
    static let surfaceVariant = dyn(0x402C1C, 0x402C1C)
    static let guideBackground = Color(rgb: 0xE9EDF3)
    static let borderSubtle = dyn(0x634837, 0x634837)
    static let text = dyn(0xFFFFFF, 0xFFFFFF)
    /// 示意图墨色：深浅主题都保持深色（视标、示意人物五官）。
    static let ink = dyn(0x14130F, 0x14130F)
    static let secondary = dyn(0xBDB7AC, 0xBDB7AC)
    static let tertiary = dyn(0xAAA599, 0xAAA599)
    static let primary = dyn(0xFF8A00, 0xFF8A00)
    static let primaryDark = dyn(0xFF8A00, 0xFF8A00)
    static let primarySoft = dyn(0x51331D, 0x51331D)
    static let onPrimary = dyn(0x291606, 0x291606)
    static let neutralSoft = dyn(0x37291F, 0x37291F)
    static let brandStart = dyn(0xFF8A00, 0xFF8A00)
    static let brandEnd = dyn(0xFF8A00, 0xFF8A00)
    /// 眨眼类别（青）：ice 图形、iceInk 文字与图标、iceSoft 浅底。
    static let ice = dyn(0xD8B494, 0xD8B494)
    static let iceInk = dyn(0xD8B494, 0xD8B494)
    static let iceSoft = dyn(0x382B21, 0x382B21)
    /// AI 爱科类别（紫）。
    static let violet = dyn(0xE8A68B, 0xE8A68B)
    static let violetSoft = dyn(0x352821, 0x352821)
    /// 用屏类别（靛蓝）。
    static let screen = dyn(0xFF8A00, 0xFF8A00)
    static let screenSoft = dyn(0x33291F, 0x33291F)
    /// 光照类别（橙）。
    static let sun = dyn(0xEDB28B, 0xEDB28B)
    static let sunSoft = dyn(0x382B21, 0x382B21)
    static let brandMarkStart = dyn(0xFF8A00, 0xFF8A00)
    /// 琥珀「留意」：圆环、状态点与色块底（色块上配深色字 onWarning；浅底上作文字用 warning）。
    static let accent = dyn(0xFF8A00, 0xFF8A00)
    static let accentSoft = dyn(0x51331D, 0x51331D)
    static let gray = dyn(0xAAA599, 0xAAA599)
    static let blue = dyn(0xFF8A00, 0xFF8A00)
    static let warning = dyn(0xF6A267, 0xF6A267)
    // 测试到位提示使用品牌主蓝，距离过远使用黄色；与安卓 Illustration.kt 同值。
    static let testReady = primary
    static let testTooFarText = tooFar
    static let testTooClose = dyn(0xE99A86, 0xE99A86)
    static let testReadySoft = primarySoft
    enum Tile {
        static let blue = Color(rgb: 0xEFB285)
        static let indigo = Color(rgb: 0xE8A68B)
        static let teal = Color(rgb: 0xEFB285)
        static let orange = Color(rgb: 0xEFB285)
        static let green = Color(rgb: 0xD4B69A)
        static let red = Color(rgb: 0xD4B69A)
        static let gray = Color(rgb: 0xB5A994)
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
    /// 三项自测趣味小游戏场景色板（与安卓 EyeGamePalette 同值）。
    enum Game {
        enum Theater {
            static let stageBg = Color(rgb: 0x1D1713)
            static let stageDeep = Color(rgb: 0x12100E)
            static let stageGlow = Color(rgb: 0x3E2D20)
            static let skyNight = Color(rgb: 0x132238)
            static let skyDay = Color(rgb: 0x284B63)
            static let tearCyan = Color(rgb: 0x7ED6DF)
            static let tearHighlight = Color(rgb: 0xDFF8FB)
            static let tearDeep = Color(rgb: 0x2E8699)
            static let mucinViolet = Color(rgb: 0xB39DDB)
            static let lipidGold = Color(rgb: 0xFFCA58)
            static let dryCoral = Color(rgb: 0xEB7D65)
            static let coldWind = Color(rgb: 0x7FA9D6)
            static let leafSage = Color(rgb: 0x8DB596)
            static let warmLamp = Color(rgb: 0xFFD166)
            static let steam = Color(rgb: 0xF4EAE0)
            static let skinWarm = Color(rgb: 0xF3C6A5)
        }
        enum PostOffice {
            static let skyTop = Color(rgb: 0x478598)
            static let skyMid = Color(rgb: 0x7CB2BC)
            static let skyBottom = Color(rgb: 0xC2E1DF)
            static let cloud = Color(rgb: 0xEAF5F3)
            static let townFar = Color(rgb: 0x69A2AA)
            static let spireGreen = Color(rgb: 0x4B8E78)
            static let lampGold = Color(rgb: 0xF3BF68)
            static let lampBrass = Color(rgb: 0xD08334)
            static let lampBeam = Color(rgb: 0xFFF3C4)
            static let envelopeBase = Color(rgb: 0x1E388E)
            static let envelopeDeep = Color(rgb: 0x14276B)
            static let envelopeShadow = Color(rgb: 0x0D1B4D)
            static let envelopeFiligree = Color(rgb: 0xC4D3F2)
            static let flowerOuter = Color(rgb: 0x4A74F0)
            static let flowerInner = Color(rgb: 0x80A2FF)
            static let flowerCenter = Color(rgb: 0x192A6E)
            static let stemGreen = Color(rgb: 0x4D8A67)
            static let waxSeal = Color(rgb: 0x9EA8BA)
            static let optotypePinkWhite = Color(rgb: 0xFFE4EE)
            static let boxBody = Color(rgb: 0x3A5A4E)
            static let boxTop = Color(rgb: 0x567A6C)
            static let boxSlot = Color(rgb: 0x1E332B)
            static let boxRivet = Color(rgb: 0xC2D4CC)
        }
        enum Firefly {
            static let skyTop = Color(rgb: 0x071326)
            static let skyMid = Color(rgb: 0x0E2549)
            static let skyBottom = Color(rgb: 0x183D68)
            static let moon = Color(rgb: 0xE3F5FF)
            static let moonHalo = Color(rgb: 0x7AB8E0)
            static let treeFar = Color(rgb: 0x0D2442)
            static let treeNear = Color(rgb: 0x061020)
            static let creek = Color(rgb: 0x1D4E7A)
            static let creekHighlight = Color(rgb: 0x7EC0E6)
            static let bodyDark = Color(rgb: 0x2C2017)
            static let bodyWarm = Color(rgb: 0x6B4226)
            static let wing = Color(rgb: 0xE4F2E8)
            static let glowCore = Color(rgb: 0xFFFDE3)
            static let glowMid = Color(rgb: 0xFFDF6B)
            static let glowOuter = Color(rgb: 0xF5B335)
        }
    }
    static let success = dyn(0xB6C6B3, 0xB6C6B3)
    static let successSoft = dyn(0x262924, 0x262924)
    static let warningSoft = dyn(0x51331D, 0x51331D)
    static let onStatus = dyn(0x291606, 0x291606)
    /// 琥珀「留意」色块上的深色字。
    static let onWarning = dyn(0x291606, 0x291606)
    static let danger = dyn(0xE99A86, 0xE99A86)
    static let dangerSoft = dyn(0x422C29, 0x422C29)
    static let tooClose = dyn(0xE99A86, 0xE99A86)
    static let tooFar = dyn(0xFFE19A, 0xFFE19A)
    static let heroStart = dyn(0x49311E, 0x49311E)
    static let heroEnd = dyn(0x292019, 0x292019)
    static let heroBorder = glassEdgeTop
    /// 同色浮雕按钮表面，渐变短线标记主操作。
    static let buttonStart = dyn(0xFF8A00, 0xFF8A00)
    static let buttonEnd = dyn(0xFF8A00, 0xFF8A00)
    /// 蓝 → 水绿的数据图形渐变。
    static let flowStart = dyn(0xB6C6B3, 0xB6C6B3)
    static let flowEnd = dyn(0xED9A78, 0xED9A78)
    static let glow = dyn(0xC9885D, 0xC9885D)
    static let shadow = Color.black.opacity(0.2)
    static let glassFill = dyn(0x30231A, 0x30231A)
    static let glassEdgeTop = dyn(0xDFB184, 0xDFB184)
    static let glassEdgeBottom = borderSubtle
    static let tabBarFill = background
    static let tabBarEdge = glassEdgeTop

    static let heading = dyn(0xFFFFFF, 0xFFFFFF)
    static let shadowLight = Color.clear
    static let glowRadius: CGFloat = 7
    static let chatAvatarSize: CGFloat = 48
    static let chatIntroPortraitWidth: CGFloat = 100
    static let chatIntroPortraitHeight: CGFloat = 142
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
    static let dataCyan = primary
    static let dataAqua = Color(rgb: 0xED9A78)
    static let ringTrack = dyn(0x52524A, 0x52524A)
    static let dataMint = Color(rgb: 0xB6C6B3)
    static var dataGradient: LinearGradient {
        LinearGradient(colors: [brandStart, dataCyan, dataAqua], startPoint: .top, endPoint: .bottom)
    }
    static var ringGradient: AngularGradient {
        AngularGradient(stops: [.init(color: brandStart, location: 0), .init(color: dataCyan, location: 0.39),
            .init(color: dataAqua, location: 0.72), .init(color: dataAqua, location: 1)], center: .center)
    }
    static func readableText(_ color: Color) -> Color { color }

    /// 固定浅色主色：白底测试画布上的注视目标（深色模式下也保持高对比）。
    static let fixedPrimary = Color(rgb: 0x23367A)

    static let radiusSmall: CGFloat = 10
    static let radius: CGFloat = 26
    static let radiusMedium: CGFloat = 14
    static let radiusLarge: CGFloat = 14
    static let radiusHero: CGFloat = 26
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
    private static func type(_ size: CGFloat, semibold: Bool = false, relativeTo style: Font.TextStyle = .body) -> Font {
        .custom(semibold ? "EyeNotoSC-SemiBold" : "EyeNotoSC-Regular", size: size, relativeTo: style)
    }
    static let chatBody = type(15)
    static let chatUserBackground = primaryDark
    static let chatUserText = onPrimary
    static let display = type(42,semibold: true,relativeTo: .largeTitle)
    static let displayCompact = type(34,semibold: true,relativeTo: .largeTitle)
    static let headline = type(32,semibold: true,relativeTo: .largeTitle)
    static let chip = type(12,semibold: true,relativeTo: .caption)
    static let caption = type(12,relativeTo: .caption)
    static let tab = type(13,relativeTo: .caption)
    static let title = type(21,semibold: true,relativeTo: .title2)
    static let subtitle = type(18,semibold: true,relativeTo: .headline)
    static let label = type(13,relativeTo: .subheadline)
    static let navigationTitle = type(18,semibold: true,relativeTo: .headline)
    static let button = type(15,semibold: true)
    static let body = type(15)
    static let bodySecondary = type(13,relativeTo: .subheadline)
    static let metricValue = type(28,semibold: true,relativeTo: .title)
    static let unit = type(13,relativeTo: .subheadline)
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
    static func reading(_ value: String, unit extra: String? = nil, numberFont: Font = EyeStyle.metricValue, color: Color = EyeStyle.text, unitColor: Color = EyeStyle.secondary) -> Text {
        let full = extra.map { "\(value) \($0)" } ?? value
        func numeric(_ c: Character) -> Bool { c.isNumber || ".,:+−—".contains(c) }
        var result = Text("")
        var run = ""
        var runIsNumber: Bool? = nil
        var last: Bool? = nil
        func flush() {
            guard let isNumber = runIsNumber, !run.isEmpty else { return }
            if let last, last != isNumber { result = result + Text("\u{2009}") }
            result = result + (isNumber ? Text(run).font(numberFont).foregroundColor(readableText(color)) : Text(run).font(unit).foregroundColor(unitColor))
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
    static let cardGap: CGFloat = 12
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
        case .secondary: EyeStyle.surfaceVariant
        case .danger: EyeStyle.dangerSoft
        }
        return configuration.label.font(EyeStyle.button).multilineTextAlignment(.center)
            .padding(.horizontal, 16).padding(.vertical, 12).frame(maxWidth: .infinity, minHeight: 52)
            .foregroundStyle(content)
            .eyeGlass(cornerRadius: EyeStyle.radiusMedium, fill: AnyShapeStyle(fill))
            .contentShape(shape)
            .scaleEffect(configuration.isPressed && enabled ? 0.96 : 1)
            .opacity(enabled ? (configuration.isPressed ? 0.9 : 1) : 0.5)
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

import SwiftUI

// 通用界面组件：与安卓 core:ui 的 EyeHelpButton / EyeAccountButton / EyeGlyph / EyeListGroup / EyeListRow /
// EyeSubPage / EyeBarChart / EyeSplash 一一对应，外观与文案一致。样式只取 EyeStyle 令牌。

// MARK: - 问号说明

/// 标题旁的灰色圆圈问号：点开在旁边弹出小窗口简要说明（iOS 16.4 起为气泡，之前版本为半屏卡片）。
/// 用来替代标题后的括号注释与成段小字。
struct HelpButton: View {
    let title: String
    let text: String
    @State private var open = false
    var body: some View {
        Button { EyeHaptics.tap(); open = true } label: {
            Text("?").font(EyeStyle.chip).foregroundStyle(EyeStyle.secondary)
                .frame(width: 20, height: 20).background(EyeStyle.surfaceVariant, in: Circle())
                .frame(width: 44, height: 44).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(title) 说明")
        .popover(isPresented: $open) {
            VStack(alignment: .leading, spacing: 6) {
                Text(title).font(EyeStyle.button).foregroundStyle(EyeStyle.text)
                Text(text).font(EyeStyle.body).foregroundStyle(EyeStyle.secondary).fixedSize(horizontal: false, vertical: true)
            }
            .padding(16).frame(width: 288, alignment: .leading)
            .modifier(CompactPopover())
        }
    }
}

/// 让问号小窗在 iPhone 上也以气泡显示（iOS 16.4+），更早的系统退为较矮的卡片。
private struct CompactPopover: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 16.4, *) {
            content.presentationCompactAdaptation(.popover)
        } else {
            content.presentationDetents([.height(240)])
        }
    }
}

// MARK: - 顶层页右上角：账户 + 系统设置

/// 系统设置齿轮（36pt 浅填充圆钮，与测试页顶栏同一图标），与安卓 EyeSettingsGear 一致。
struct SettingsGear: View {
    let action: () -> Void
    var body: some View {
        Button { EyeHaptics.tap(); action() } label: {
            Image(systemName: "gearshape.fill").font(.system(size: 18)).foregroundStyle(EyeStyle.primary)
                .frame(width: 48, height: 48).eyeGlass(cornerRadius: EyeStyle.radiusMedium).contentShape(Rectangle())
        }.buttonStyle(.plain).accessibilityLabel("系统设置")
    }
}

/// 白天 / 夜晚切换按钮（2026-11 改版后顶层页不再放，深色模式开关在「我的 → 通用」，见 MinePage）。
/// 选择存在 UserDefaults「eye.themeMode」（system / light / dark，默认跟随系统），根视图据此设置 preferredColorScheme。与安卓 EyeThemeButton 一致。
struct ThemeToggleButton: View {
    @AppStorage("eye.themeMode") private var themeMode = "system"
    @Environment(\.colorScheme) private var scheme
    var body: some View {
        let dark = scheme == .dark
        Button { EyeHaptics.tap(); themeMode = dark ? "light" : "dark" } label: {
            Image(systemName: dark ? "sun.max.fill" : "moon.fill").font(.system(size: 20)).foregroundStyle(EyeStyle.primary)
                .frame(width: 44, height: 44).background(EyeStyle.panel, in: Circle())
                .overlay { Circle().stroke(EyeStyle.borderSubtle, lineWidth: 1) }
        }.buttonStyle(.plain).accessibilityLabel(dark ? "切换到白天模式" : "切换到夜晚模式")
    }
}

/// 账户头像（顶层页右上角唯一的按钮）：登录后品牌蓝实心圆 + 白色首字母，未登录为浅填充圆 + 人像线稿；点开完整的账户页。
/// 与安卓 EyeAccountButton 一致。
struct AccountButton: View {
    @ObservedObject private var cloud = CloudModel.shared
    let action: () -> Void
    var body: some View {
        let email = cloud.bool("signedIn") ? cloud.string("email") : ""
        Button { EyeHaptics.tap(); action() } label: {
            ZStack {
                Color.clear
                if email.isEmpty {
                    EyeGlyphView(glyph: .person, color: EyeStyle.primary).frame(width: 20, height: 20)
                } else {
                    Text(String(email.prefix(1)).uppercased()).font(EyeStyle.subtitle).foregroundStyle(EyeStyle.onPrimary)
                }
            }
            .frame(width: 48, height: 48).eyeGlass(cornerRadius: EyeStyle.radiusMedium).contentShape(Rectangle())
        }.buttonStyle(.plain).accessibilityLabel(email.isEmpty ? "账户（未登录）" : "账户（已登录）")
    }
}

/// 与安卓 EyePageHeader 一致。
struct PageHeader: View {
    let title: String
    var subtitle: String? = nil
    var openAccount: (() -> Void)? = nil
    var openSettings: (() -> Void)? = nil
    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(EyeStyle.headline).foregroundStyle(EyeStyle.heading).lineLimit(1).minimumScaleFactor(0.7)
                if let subtitle { Text(subtitle).font(EyeStyle.label).foregroundStyle(EyeStyle.secondary) }
            }.frame(maxWidth: .infinity, alignment: .leading)
            if let openAccount { AccountButton(action: openAccount) } else if let openSettings { SettingsGear(action: openSettings) }
        }.padding(.top, 20).padding(.bottom, 12)
    }
}

// MARK: - 线性图标（安卓 EyeGlyph 逐笔移植）

enum EyeGlyph {
    case document, family, cloud, shield, bell, sun, chart, bulb, eye, clock, person, info, moon, export, sync
    case logout, trash, server, camera, mic, hourglass, chat, ruler, coffee, gear, heart
}

/// 24 单位视口、1.8 线宽、圆头圆角；弧线一律按「x 轴正向起、顺时针」的角度取点绘制，与安卓一致。
struct EyeGlyphView: View {
    let glyph: EyeGlyph
    var color: Color = EyeStyle.primary
    var body: some View {
        Canvas { context, size in GlyphPainter.draw(glyph, context, size, color) }.accessibilityHidden(true)
    }
}

enum GlyphPainter {
    static func draw(_ glyph: EyeGlyph, _ ctx: GraphicsContext, _ size: CGSize, _ color: Color) {
        let k = min(size.width, size.height) / 24
        let ox = (size.width - 24 * k) / 2, oy = (size.height - 24 * k) / 2
        let ink = GraphicsContext.Shading.color(color)
        let style = StrokeStyle(lineWidth: 1.8 * k, lineCap: .round, lineJoin: .round)
        func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: ox + x * k, y: oy + y * k) }
        func seg(_ x1: CGFloat, _ y1: CGFloat, _ x2: CGFloat, _ y2: CGFloat) {
            var path = Path(); path.move(to: p(x1, y1)); path.addLine(to: p(x2, y2)); ctx.stroke(path, with: ink, style: style)
        }
        func poly(_ xy: [CGFloat], close: Bool = false) {
            var path = Path(); path.move(to: p(xy[0], xy[1]))
            var i = 2
            while i + 1 < xy.count { path.addLine(to: p(xy[i], xy[i + 1])); i += 2 }
            if close { path.closeSubpath() }
            ctx.stroke(path, with: ink, style: style)
        }
        func box(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat, _ r: CGFloat) {
            ctx.stroke(Path(roundedRect: CGRect(origin: p(x, y), size: CGSize(width: w * k, height: h * k)), cornerRadius: r * k), with: ink, style: style)
        }
        func ring(_ cx: CGFloat, _ cy: CGFloat, _ r: CGFloat) {
            ctx.stroke(Path(ellipseIn: CGRect(x: ox + (cx - r) * k, y: oy + (cy - r) * k, width: 2 * r * k, height: 2 * r * k)), with: ink, style: style)
        }
        func dot(_ cx: CGFloat, _ cy: CGFloat, _ r: CGFloat) {
            ctx.fill(Path(ellipseIn: CGRect(x: ox + (cx - r) * k, y: oy + (cy - r) * k, width: 2 * r * k, height: 2 * r * k)), with: ink)
        }
        func arc(_ cx: CGFloat, _ cy: CGFloat, _ rx: CGFloat, _ ry: CGFloat, _ start: Double, _ sweep: Double) {
            var path = Path()
            for i in 0...24 {
                let a = (start + sweep * Double(i) / 24) * .pi / 180
                let q = p(cx + rx * CGFloat(cos(a)), cy + ry * CGFloat(sin(a)))
                if i == 0 { path.move(to: q) } else { path.addLine(to: q) }
            }
            ctx.stroke(path, with: ink, style: style)
        }
        func curve(_ xy: [CGFloat], close: Bool = false) {
            var path = Path(); path.move(to: p(xy[0], xy[1]))
            var i = 2
            while i + 5 < xy.count {
                path.addCurve(to: p(xy[i + 4], xy[i + 5]), control1: p(xy[i], xy[i + 1]), control2: p(xy[i + 2], xy[i + 3]))
                i += 6
            }
            if close { path.closeSubpath() }
            ctx.stroke(path, with: ink, style: style)
        }
        func rays(_ count: Int, _ offset: Double, _ inner: CGFloat, _ outer: CGFloat) {
            for i in 0..<count {
                let a = (Double(i) * 360 / Double(count) + offset) * .pi / 180
                seg(12 + inner * CGFloat(cos(a)), 12 + inner * CGFloat(sin(a)), 12 + outer * CGFloat(cos(a)), 12 + outer * CGFloat(sin(a)))
            }
        }

        switch glyph {
        case .document: box(5, 3, 14, 18, 2.5); seg(8.5, 8, 15.5, 8); seg(8.5, 12, 15.5, 12); seg(8.5, 16, 12.5, 16)
        case .family: ring(9, 8, 3.2); arc(9, 19.5, 5.5, 5, 180, 180); ring(17, 10.5, 2.3); arc(17, 19.5, 3.8, 3.6, 180, 180)
        case .cloud:
            curve([7.5, 18.5, 12, 18.5, 14, 18.5, 17, 18.5, 19.8, 18.5, 21.5, 16.4, 20.6, 13.9,
                   19.9, 12, 18, 11.2, 16.6, 11.6, 15.9, 8.4, 13.3, 6.5, 10.6, 7.1, 8.3, 7.6, 6.8, 9.6, 6.9, 11.9,
                   4.6, 12.2, 3, 14.2, 3.3, 16.3, 3.6, 17.7, 5.2, 18.6, 7.5, 18.5], close: true)
        case .shield:
            curve([12, 3, 14.3, 4, 16.7, 5, 19, 6, 19, 7.8, 19, 9.7, 19, 11.5, 19, 15.8, 16.2, 19.2, 12, 21,
                   7.8, 19.2, 5, 15.8, 5, 11.5, 5, 9.7, 5, 7.8, 5, 6, 7.3, 5, 9.7, 4, 12, 3], close: true)
            poly([9, 12, 11.2, 14.2, 15.2, 10])
        case .bell:
            curve([6, 16.5, 6, 14.7, 6, 12.8, 6, 11, 6, 7.7, 8.7, 5, 12, 5, 15.3, 5, 18, 7.7, 18, 11, 18, 12.8, 18, 14.7, 18, 16.5])
            poly([18, 16.5, 19.5, 18, 4.5, 18, 6, 16.5])
            arc(12, 19.5, 2, 1.8, 0, 180); seg(12, 3, 12, 5)
        case .sun: ring(12, 12, 4); rays(8, 0, 7, 9.5)
        case .chart: poly([4, 4, 4, 20, 20, 20]); seg(8.5, 16.5, 8.5, 12); seg(12.5, 16.5, 12.5, 8); seg(16.5, 16.5, 16.5, 10.5)
        case .bulb:
            curve([9, 15.5, 7, 14.3, 6, 12.5, 6, 10.5, 6, 7, 8.7, 4.5, 12, 4.5, 15.3, 4.5, 18, 7, 18, 10.5, 18, 12.5, 17, 14.3, 15, 15.5])
            poly([15, 15.5, 15, 17.5, 9, 17.5, 9, 15.5]); seg(9.5, 20.5, 14.5, 20.5)
        case .eye:
            curve([2.5, 12, 5, 7.5, 8.3, 5.5, 12, 5.5, 15.7, 5.5, 19, 7.5, 21.5, 12,
                   19, 16.5, 15.7, 18.5, 12, 18.5, 8.3, 18.5, 5, 16.5, 2.5, 12], close: true)
            ring(12, 12, 3)
        case .clock: ring(12, 12, 8.5); poly([12, 7.5, 12, 12, 15.5, 14])
        case .person: ring(12, 8.5, 3.8); arc(12, 20.5, 7.5, 6.5, 200, 140)
        case .info: ring(12, 12, 8.5); seg(12, 11, 12, 16.5); dot(12, 7.8, 1.15)
        case .heart:
            curve([12, 20, 5, 15.5, 3, 12, 3, 8.8, 3, 6, 5.2, 4, 7.8, 4, 9.6, 4, 11.1, 5, 12, 6.6,
                   12.9, 5, 14.4, 4, 16.2, 4, 18.8, 4, 21, 6, 21, 8.8, 21, 12, 19, 15.5, 12, 20], close: true)
        case .moon:
            curve([14.5, 4.2, 10.5, 5, 8.2, 8.7, 8.9, 12.8, 9.6, 16.6, 13.2, 19.3, 17, 18.8,
                   15.6, 20.3, 13.6, 21, 11.6, 21, 6.9, 20.8, 3.2, 16.9, 3.3, 12.2, 3.4, 7.8, 6.9, 4.2, 11.4, 4,
                   12.5, 3.95, 13.5, 4.05, 14.5, 4.2], close: true)
        case .export: poly([8, 10, 6.5, 10, 6.5, 20, 17.5, 20, 17.5, 10, 16, 10]); seg(12, 3.5, 12, 14); poly([8.8, 6.7, 12, 3.5, 15.2, 6.7])
        case .sync:
            arc(12, 12, 7.5, 7.5, 200, 140); poly([16.6, 9.1, 19.05, 9.43, 19.5, 6.9])
            arc(12, 12, 7.5, 7.5, 20, 140); poly([7.4, 14.9, 4.95, 14.57, 4.5, 17.1])
        case .logout:
            curve([13, 4, 10.8, 4, 8.7, 4, 6.5, 4, 5.1, 4, 4, 5.1, 4, 6.5, 4, 10.2, 4, 13.8, 4, 17.5,
                   4, 18.9, 5.1, 20, 6.5, 20, 8.7, 20, 10.8, 20, 13, 20])
            seg(10, 12, 20, 12); poly([17, 9, 20, 12, 17, 15])
        case .trash:
            seg(4.5, 6.5, 19.5, 6.5); poly([9.5, 6.5, 9.5, 4.5, 14.5, 4.5, 14.5, 6.5]); poly([6.5, 6.5, 7.5, 20, 16.5, 20, 17.5, 6.5])
            seg(10.5, 10, 10.5, 16.5); seg(13.5, 10, 13.5, 16.5)
        case .server: box(4, 4.5, 16, 6.5, 2); box(4, 13, 16, 6.5, 2); dot(7.5, 7.75, 1); dot(7.5, 16.25, 1)
        case .camera: box(3, 7, 18, 13, 3); poly([8.5, 7, 10, 4.5, 14, 4.5, 15.5, 7]); ring(12, 13.5, 3.5)
        case .mic: box(9, 3, 6, 11, 3); arc(12, 11.5, 6.5, 5.5, 0, 180); seg(12, 17, 12, 20.5); seg(9, 20.5, 15, 20.5)
        case .hourglass:
            seg(6.5, 3.5, 17.5, 3.5); seg(6.5, 20.5, 17.5, 20.5)
            curve([7.5, 3.5, 7.5, 9, 12, 10, 12, 12, 12, 14, 7.5, 15, 7.5, 20.5])
            curve([16.5, 3.5, 16.5, 9, 12, 10, 12, 12, 12, 14, 16.5, 15, 16.5, 20.5])
        case .chat: box(3.5, 4.5, 17, 12, 4); poly([8, 16.5, 7, 20.5, 12, 16.5]); dot(8.5, 10.5, 1); dot(12, 10.5, 1); dot(15.5, 10.5, 1)
        case .ruler: box(3, 8, 18, 8, 1.5); seg(7, 8, 7, 11); seg(11, 8, 11, 12); seg(15, 8, 15, 11)
        case .coffee:
            curve([5, 9, 8.7, 9, 12.3, 9, 16, 9, 16, 10.7, 16, 12.3, 16, 14, 16, 16.2, 14.2, 18, 12, 18,
                   11, 18, 10, 18, 9, 18, 6.8, 18, 5, 16.2, 5, 14, 5, 12.3, 5, 10.7, 5, 9])
            arc(16, 12, 2.5, 2.2, -90, 180); seg(8.5, 4, 8.5, 6); seg(12, 4, 12, 6)
        case .gear: ring(12, 12, 3); rays(8, 22.5, 6, 8.8); ring(12, 12, 6)
        }
    }
}

/// 图标配色：primary 品牌蓝 / accent 橙 / success 绿 / blue 青 / violet 靛紫 / danger 红 / neutral 灰（与安卓 EyeTileTone 一致）。
/// 列表图标块用实心底 + 白色图标（[solid]），卡片标题等用浅底 + 彩色图标（[colors]）。
enum TileTone {
    case primary, accent, success, blue, violet, danger, neutral
    var colors: (Color, Color) {
        switch self {
        case .primary: return (EyeStyle.primarySoft, EyeStyle.primary)
        case .accent: return (EyeStyle.sunSoft, EyeStyle.sun)
        case .success: return (EyeStyle.successSoft, EyeStyle.success)
        case .blue: return (EyeStyle.iceSoft, EyeStyle.Tile.teal)
        case .violet: return (EyeStyle.violetSoft, EyeStyle.violet)
        case .danger: return (EyeStyle.dangerSoft, EyeStyle.danger)
        case .neutral: return (EyeStyle.surfaceVariant, EyeStyle.secondary)
        }
    }
    /// 实心图标块底色（两套主题同值，上面放白色图标）。
    var solid: Color {
        switch self {
        case .primary: return EyeStyle.Tile.blue
        case .accent: return EyeStyle.Tile.orange
        case .success: return EyeStyle.Tile.green
        case .blue: return EyeStyle.Tile.teal
        case .violet: return EyeStyle.Tile.indigo
        case .danger: return EyeStyle.Tile.red
        case .neutral: return EyeStyle.Tile.gray
        }
    }
}

struct IconTile: View {
    let glyph: EyeGlyph
    var tone: TileTone = .primary
    var size: CGFloat = 32
    var body: some View {
        EyeGlyphView(glyph: glyph, color: tone.solid).frame(width: size * 0.6, height: size * 0.6)
            .frame(width: size, height: size).eyeGlass(cornerRadius: EyeStyle.radiusMedium)
    }
}

// MARK: - 分组列表

/// 分组列表：可选组标题 + 玻璃圆角面板。
struct ListGroup<Content: View>: View {
    var title: String? = nil
    @ViewBuilder let content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let title { Text(title).font(EyeStyle.title).foregroundStyle(EyeStyle.heading).padding(.leading, 4).padding(.top, 12).padding(.bottom, 8) }
            VStack(spacing: EyeSpacing.sm) { content }
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// 按下时整行浅底高亮（不用系统的整块变灰）。
struct RowPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.background(configuration.isPressed ? EyeStyle.surfaceVariant : Color.clear)
    }
}

/// 列表行：图标色块 + 标题（可带副标题）+ 右侧取值 + 箭头；[indent] 让子项与上一行文字对齐。
struct ListRow: View {
    let title: String
    var glyph: EyeGlyph? = nil
    var tone: TileTone = .primary
    var subtitle: String? = nil
    var value: String? = nil
    var valueColor: Color = EyeStyle.secondary
    var titleColor: Color = EyeStyle.text
    var divider = true
    var enabled = true
    var indent: CGFloat = 0
    var glowBorder = true
    var action: (() -> Void)? = nil

    var body: some View {
        if let action {
            Button { EyeHaptics.tap(); action() } label: { content }
                .buttonStyle(RowPressStyle()).disabled(!enabled)
        } else {
            content
        }
    }

    private var content: some View {
        VStack(spacing: 0) {
            HStack(spacing: 14) {
                if let glyph { IconTile(glyph: glyph, tone: tone) }
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(EyeStyle.navigationTitle).foregroundStyle(enabled ? EyeStyle.readableText(titleColor) : EyeStyle.secondary)
                    if let subtitle { Text(subtitle).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary).multilineTextAlignment(.leading) }
                }.frame(maxWidth: .infinity, alignment: .leading)
                if let value { Text(value).font(EyeStyle.body).foregroundStyle(EyeStyle.readableText(valueColor)).lineLimit(1) }
                if action != nil {
                    Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundStyle(EyeStyle.tertiary)
                }
            }
            .padding(.leading, 16 + indent).padding(.trailing, 16).padding(.vertical, 10).frame(minHeight: 56)

        }.eyeGlass().eyeGlowBorder(strength: glowBorder ? EyeStyle.cardBorderSubtle : 0).contentShape(Rectangle())
    }
}

/// 列表里的开关行：标题旁可放问号说明。
struct SwitchRow: View {
    let title: String
    @Binding var isOn: Bool
    var glyph: EyeGlyph? = nil
    var tone: TileTone = .primary
    var subtitle: String? = nil
    var help: String? = nil
    var divider = true
    var enabled = true
    var glowBorder = true

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 14) {
                if let glyph { IconTile(glyph: glyph, tone: tone) }
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 0) {
                        Text(title).font(EyeStyle.navigationTitle).foregroundStyle(EyeStyle.text)
                        if let help { HelpButton(title: title, text: help) }
                    }
                    if let subtitle { Text(subtitle).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary) }
                }.frame(maxWidth: .infinity, alignment: .leading)
                Toggle("", isOn: Binding(get: { isOn }, set: { EyeHaptics.toggle(); isOn = $0 }))
                    .labelsHidden().tint(EyeStyle.primary).disabled(!enabled)
            }
            .padding(.horizontal, 16).padding(.vertical, 6).frame(minHeight: 56)
        }.eyeGlass().eyeGlowBorder(strength: glowBorder ? EyeStyle.cardBorderSubtle : 0)
    }
}

// MARK: - 二级页面

/// 二级完整页面（替代系统底部弹窗）：系统导航栏标题 + 返回（可右滑返回），内容竖向滚动。与安卓 EyeSubPage 同一排版。
struct SubPage<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: EyeSpacing.cardGap) { content }
                .padding(.horizontal, EyeSpacing.gutter).padding(.top, EyeSpacing.xxs).padding(.bottom, 28)
        }
        .background(EyeBackdrop())
        .navigationTitle(title).navigationBarTitleDisplayMode(.inline)
    }
}

/// 圆角纯色面板（平铺卡片，同 EyeCard）。
struct Panel<Content: View>: View {
    var padding: CGFloat = EyeSpacing.cardPadding
    @ViewBuilder let content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: EyeSpacing.itemGap) { content }
            .padding(padding).frame(maxWidth: .infinity, alignment: .leading)
            .eyeGlass().eyeGlowBorder()
    }
}

// MARK: - 小控件

/// 可多选的胶囊：选中时浅主色底 + 主色字。
struct ChoiceChip: View {
    let label: String
    let selected: Bool
    let action: () -> Void
    var body: some View {
        Button { EyeHaptics.toggle(); action() } label: {
            Text(label).font(EyeStyle.body).foregroundStyle(selected ? EyeStyle.primary : EyeStyle.text)
                .padding(.horizontal, 14).padding(.vertical, 9)
                .background(selected ? EyeStyle.primarySoft : EyeStyle.surfaceVariant, in: EyeStyle.chipShape)
                .overlay { EyeStyle.chipShape.stroke(selected ? EyeStyle.primary : EyeStyle.surfaceVariant, lineWidth: 1) }
        }.buttonStyle(PressScale())
    }
}

/// 小号胶囊按钮（列表行右侧的操作）。
struct PillButton: View {
    let label: String
    var primary = true
    let action: () -> Void
    var body: some View {
        Button { EyeHaptics.tap(); action() } label: {
            // 主要：浅主色底 + 主色字；次要：浅填充底 + 正文色（与安卓 PillButton 一致）
            Text(label).font(EyeStyle.bodySecondary.weight(.semibold)).foregroundStyle(primary ? EyeStyle.primary : EyeStyle.text)
                .padding(.horizontal, 16).padding(.vertical, 7)
                .background(primary ? EyeStyle.primarySoft : EyeStyle.surfaceVariant, in: Capsule())
        }.buttonStyle(PressScale())
    }
}

/// 按下轻缩（弹簧回弹）。
struct PressScale: ButtonStyle {
    var scale: CGFloat = 0.95
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.scaleEffect(configuration.isPressed ? scale : 1).animation(EyeStyle.pressSpring, value: configuration.isPressed)
    }
}

/// 简单的胶囊徽标（等级、状态）。
struct StatusChip: View {
    let text: String
    let color: Color
    var filled = false
    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(color).frame(width: 6, height: 6)
            Text(text).font(EyeStyle.chip).foregroundStyle(EyeStyle.text)
        }
            .padding(.horizontal, 12).padding(.vertical, 6)
            .background(EyeStyle.surfaceVariant, in: EyeStyle.chipShape)
    }
}

/// 圆点 + 一行正文。
struct Bullet: View {
    let text: String
    var dot: Color = EyeStyle.primary
    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Circle().fill(dot).frame(width: 6, height: 6).padding(.top, 8)
            Text(text).font(EyeStyle.body).foregroundStyle(EyeStyle.text).fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// 简单的分段选择（20 / 30 / 60 分钟）：浅色轨道 + 主色滑块弹簧滑动（安卓 EyeSegmentedRow）。
struct SegmentedPicker: View {
    let options: [(Int, String)]
    let selected: Int
    let onSelect: (Int) -> Void
    var body: some View {
        GeometryReader { geometry in
            let width = (geometry.size.width - 6) / CGFloat(max(options.count, 1))
            let index = CGFloat(options.firstIndex(where: { $0.0 == selected }) ?? 0)
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: EyeStyle.radiusSmall, style: .continuous).fill(EyeStyle.buttonGradient).frame(width: width).offset(x: width * index)
                    .animation(.spring(response: 0.35, dampingFraction: 0.8), value: selected)
                HStack(spacing: 0) {
                    ForEach(Array(options.enumerated()), id: \.offset) { _, option in
                        Button { EyeHaptics.tap(); onSelect(option.0) } label: {
                            Text(option.1).font(option.0 == selected ? EyeStyle.body.weight(.semibold) : EyeStyle.body)
                                .foregroundStyle(option.0 == selected ? EyeStyle.onPrimary : EyeStyle.text)
                                .frame(maxWidth: .infinity, maxHeight: .infinity).contentShape(Rectangle())
                        }.buttonStyle(.plain)
                    }
                }
            }.padding(3)
        }.frame(height: 46).background(EyeStyle.surfaceVariant, in: RoundedRectangle(cornerRadius: EyeStyle.radiusMedium, style: .continuous))
    }
}

// MARK: - 柱状图

struct BarItem: Identifiable {
    let id: Int
    let label: String
    let value: Double?
    let text: String?
    var highlight = false
    var warning = false
}

/// 光流柱状图（用眼趋势）：细胶囊柱 + 暗色轨道，柱身主蓝 → 青蓝渐变，今天满亮（深色带泛光）、其余稍暗，超出参考线的柱顶数值为琥珀色，超出参考线的琥珀色；参考线为虚线。与安卓 EyeBarChart 一致。
struct BarChartView: View {
    let bars: [BarItem]
    var reference: Double? = nil
    var maxValue: Double? = nil
    var height: CGFloat = 140
    /// 柱子颜色（类别色），默认主色。
    var tint: Color = EyeStyle.primary
    @Environment(\.accessibilityReduceMotion) private var reduced
    @State private var grow: CGFloat = 0

    var body: some View {
        let top = max(maxValue ?? 0, bars.compactMap { $0.value }.max() ?? 0, (reference ?? 0) * 1.15, 1)
        VStack(spacing: 4) {
            HStack(spacing: 0) {
                ForEach(bars) { bar in
                    Text(bar.highlight || bar.warning ? (bar.text ?? " ") : " ").font(EyeStyle.caption)
                        .foregroundStyle(EyeStyle.text)
                        .lineLimit(1).frame(maxWidth: .infinity)
                }
            }
            Canvas { context, size in
                let slot = size.width / CGFloat(max(bars.count, 1))
                let trackWidth = min(max(slot * 0.6, 12), 24)
                let barWidth = trackWidth * 0.44
                let radius = barWidth / 2
                for (i, bar) in bars.enumerated() {
                    let tx = slot * CGFloat(i) + (slot - trackWidth) / 2
                    context.fill(Path(roundedRect: CGRect(x: tx, y: 0, width: trackWidth, height: size.height), cornerRadius: trackWidth / 2), with: .color(EyeStyle.surfaceVariant))
                    guard let v = bar.value, v > 0 else { continue }
                    let x = slot * CGFloat(i) + (slot - barWidth) / 2
                    let h = max(CGFloat(v / top) * size.height * grow, v > 0 ? radius * 2 : 0)
                    let rect = CGRect(x: x, y: size.height - h, width: barWidth, height: h)
                    var layer = context
                    layer.addFilter(.shadow(color: EyeStyle.primary.opacity(EyeStyle.glowOpacity), radius: EyeStyle.glowRadius))
                    layer.addFilter(.shadow(color: EyeStyle.glow, radius: 3))
                    layer.fill(Path(roundedRect: rect, cornerRadius: radius), with: .linearGradient(
                        Gradient(colors: [EyeStyle.brandStart, EyeStyle.dataCyan, EyeStyle.dataAqua]),
                        startPoint: CGPoint(x: x, y: size.height - h), endPoint: CGPoint(x: x, y: size.height)))
                }
                if let reference {
                    let y = size.height - CGFloat(reference / top) * size.height
                    var line = Path(); line.move(to: CGPoint(x: 0, y: y)); line.addLine(to: CGPoint(x: size.width, y: y))
                    context.stroke(line, with: .color(EyeStyle.dataMint.opacity(0.6)), style: StrokeStyle(lineWidth: 1.5, dash: [4, 4]))
                }
            }.frame(height: height)
            HStack(spacing: 0) {
                ForEach(bars) { bar in
                    Text(bar.label).font(EyeStyle.caption).foregroundStyle(bar.highlight ? EyeStyle.text : EyeStyle.tertiary)
                        .lineLimit(1).frame(maxWidth: .infinity)
                }
            }.padding(.top, 2)
        }
        .onAppear { withAnimation(reduced ? nil : .easeOut(duration: 0.9)) { grow = 1 } }
    }
}

// MARK: - 应用图标与启动动画

/// 应用图标的展示（关于页）：与桌面图标同一张图（爱科头像 + 浅灰蓝圆盘）。
struct AppIconView: View {
    var body: some View {
        GeometryReader { geometry in
            let side = min(geometry.size.width, geometry.size.height)
            Image("EyechoAppIcon").resizable().scaledToFit()
                .frame(width: side, height: side)
                .clipShape(RoundedRectangle(cornerRadius: side * 0.2237, style: .continuous))
        }.accessibilityLabel("眼健康助手图标")
    }
}

/// 启动画面（约 1.3 秒）：标志轻缓放大淡入 + 细进度线 +「眼健康助手」，结束后淡出。
/// 只盖在已经创建好的首页上，不推迟首页加载。与安卓 EyeSplash 同一节奏。
struct SplashView: View {
    let onFinished: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var progress: CGFloat = 0
    @State private var visible = true
    var body: some View {
        ZStack {
            EyeStyle.background.ignoresSafeArea()
            VStack(spacing: 0) {
                BrandMark(size: 88).scaleEffect(0.92 + 0.08 * min(1, progress * 2.5)).opacity(Double(min(1, progress * 2.5)))
                Text("眼健康助手").font(EyeStyle.title).foregroundStyle(EyeStyle.text).padding(.top, 20)
                Text("你的主动眼健康助手").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary).padding(.top, 6)
                ZStack(alignment: .leading) {
                    EyeStyle.chipShape.fill(EyeStyle.borderSubtle)
                    EyeStyle.chipShape.fill(EyeStyle.flowGradient).frame(width: 120 * progress)
                }.frame(width: 120, height: 3).padding(.top, 32)
            }
        }
        .opacity(visible ? 1 : 0)
        .contentShape(Rectangle())
        .onTapGesture {}
        .accessibilityElement(children: .ignore).accessibilityLabel("正在启动眼健康助手")
        .onAppear {
            let duration = reduceMotion ? 0.5 : 1.1
            withAnimation(.easeOut(duration: duration)) { progress = 1 }
            DispatchQueue.main.asyncAfter(deadline: .now() + duration) {
                withAnimation(.easeOut(duration: 0.28)) { visible = false }
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { onFinished() }
            }
        }
    }
}

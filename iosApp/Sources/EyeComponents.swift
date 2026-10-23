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
                .frame(width: 36, height: 36).contentShape(Rectangle())
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

/// 系统设置齿轮（与测试页顶栏同一图标），与安卓 EyeSettingsGear 一致。
struct SettingsGear: View {
    let action: () -> Void
    var body: some View {
        Button { EyeHaptics.tap(); action() } label: {
            Image(systemName: "gearshape.fill").font(.system(size: 22)).foregroundStyle(EyeStyle.primary)
                .frame(width: 44, height: 44).background(EyeStyle.panel, in: Circle())
                .overlay { Circle().stroke(EyeStyle.borderSubtle, lineWidth: 1) }
        }.buttonStyle(.plain).accessibilityLabel("系统设置")
    }
}

/// 账户按钮：未登录画人像轮廓，登录后显示邮箱首字母与在线小绿点；点开完整的账户页。与安卓 EyeAccountButton 一致。
struct AccountButton: View {
    @ObservedObject private var cloud = CloudModel.shared
    let action: () -> Void
    var body: some View {
        let email = cloud.bool("signedIn") ? cloud.string("email") : ""
        Button { EyeHaptics.tap(); action() } label: {
            ZStack {
                Circle().fill(email.isEmpty ? EyeStyle.panel : EyeStyle.primarySoft)
                Circle().stroke(EyeStyle.borderSubtle, lineWidth: 1)
                if email.isEmpty {
                    EyeGlyphView(glyph: .person, color: EyeStyle.primary).frame(width: 22, height: 22)
                } else {
                    Text(String(email.prefix(1)).uppercased()).font(EyeStyle.subtitle).foregroundStyle(EyeStyle.primary)
                }
            }
            .frame(width: 44, height: 44)
            .overlay(alignment: .bottomTrailing) {
                if !email.isEmpty {
                    Circle().fill(EyeStyle.success).frame(width: 8, height: 8).padding(2).background(EyeStyle.panel, in: Circle()).offset(x: -1, y: -1)
                }
            }
        }.buttonStyle(.plain).accessibilityLabel(email.isEmpty ? "账户（未登录）" : "账户（已登录）")
    }
}

/// 顶层页标题行：大标题（可带一行副标题）+ 右上角账户与系统设置。
struct PageHeader: View {
    let title: String
    var subtitle: String? = nil
    let openAccount: () -> Void
    let openSettings: () -> Void
    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(EyeStyle.headline).foregroundStyle(EyeStyle.text).lineLimit(1).minimumScaleFactor(0.7)
                if let subtitle { Text(subtitle).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary) }
            }.frame(maxWidth: .infinity, alignment: .leading)
            AccountButton(action: openAccount)
            SettingsGear(action: openSettings)
        }.padding(.vertical, 16)
    }
}

// MARK: - 线性图标（安卓 EyeGlyph 逐笔移植）

enum EyeGlyph {
    case document, family, cloud, shield, bell, sun, chart, bulb, eye, clock, person, info, moon, export, sync
    case logout, trash, server, camera, mic, hourglass, chat, ruler, coffee, gear
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

/// 图标色块的配色：主色 / 琥珀 / 成功绿 / 靛蓝 / 警示 / 中性（与安卓 EyeTileTone 一致）。
enum TileTone {
    case primary, accent, success, blue, danger, neutral
    var colors: (Color, Color) {
        switch self {
        case .primary: return (EyeStyle.primarySoft, EyeStyle.primary)
        case .accent: return (Cartoon.accentSoft, EyeStyle.warning)
        case .success: return (EyeStyle.successSoft, EyeStyle.success)
        case .blue: return (EyeStyle.surfaceVariant, EyeStyle.brandEnd)
        case .danger: return (EyeStyle.dangerSoft, EyeStyle.danger)
        case .neutral: return (EyeStyle.surfaceVariant, EyeStyle.secondary)
        }
    }
}

/// 圆角色块上的线性图标。
struct IconTile: View {
    let glyph: EyeGlyph
    var tone: TileTone = .primary
    var size: CGFloat = 36
    var body: some View {
        let (background, foreground) = tone.colors
        EyeGlyphView(glyph: glyph, color: foreground).frame(width: size * 0.58, height: size * 0.58)
            .frame(width: size, height: size).background(background, in: RoundedRectangle(cornerRadius: 12))
    }
}

// MARK: - 分组列表

/// 分组列表：可选组标题 + 白色圆角面板。
struct ListGroup<Content: View>: View {
    var title: String? = nil
    @ViewBuilder let content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let title { Text(title).font(EyeStyle.chip).foregroundStyle(EyeStyle.secondary).padding(.leading, 4) }
            VStack(spacing: 0) { content }
                .background(EyeStyle.panel)
                .clipShape(RoundedRectangle(cornerRadius: 22))
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
                    Text(title).font(EyeStyle.body).foregroundStyle(enabled ? titleColor : EyeStyle.secondary)
                    if let subtitle { Text(subtitle).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary).multilineTextAlignment(.leading) }
                }.frame(maxWidth: .infinity, alignment: .leading)
                if let value { Text(value).font(EyeStyle.body).foregroundStyle(valueColor).lineLimit(1) }
                if action != nil {
                    Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundStyle(EyeStyle.secondary.opacity(0.7))
                }
            }
            .padding(.leading, 16 + indent).padding(.trailing, 16).padding(.vertical, 10).frame(minHeight: 60)
            if divider {
                Rectangle().fill(EyeStyle.borderSubtle).frame(height: 1).padding(.leading, (glyph == nil ? 16 : 66) + indent)
            }
        }.contentShape(Rectangle())
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

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 14) {
                if let glyph { IconTile(glyph: glyph, tone: tone) }
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 0) {
                        Text(title).font(EyeStyle.body).foregroundStyle(EyeStyle.text)
                        if let help { HelpButton(title: title, text: help) }
                    }
                    if let subtitle { Text(subtitle).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary) }
                }.frame(maxWidth: .infinity, alignment: .leading)
                Toggle("", isOn: Binding(get: { isOn }, set: { EyeHaptics.toggle(); isOn = $0 }))
                    .labelsHidden().tint(EyeStyle.primary).disabled(!enabled)
            }
            .padding(.horizontal, 16).padding(.vertical, 6).frame(minHeight: 60)
            if divider {
                Rectangle().fill(EyeStyle.borderSubtle).frame(height: 1).padding(.leading, glyph == nil ? 16 : 66)
            }
        }
    }
}

// MARK: - 二级页面

/// 二级完整页面（替代系统底部弹窗）：系统导航栏标题 + 返回（可右滑返回），内容竖向滚动。与安卓 EyeSubPage 同一排版。
struct SubPage<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) { content }
                .padding(.horizontal, 16).padding(.top, 4).padding(.bottom, 28)
        }
        .background(EyeStyle.background.ignoresSafeArea())
        .navigationTitle(title).navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(EyeStyle.background, for: .navigationBar)
    }
}

/// 白色圆角面板（卡片）。
struct Panel<Content: View>: View {
    var padding: CGFloat = 18
    @ViewBuilder let content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 12) { content }
            .padding(padding).frame(maxWidth: .infinity, alignment: .leading)
            .background(EyeStyle.panel, in: RoundedRectangle(cornerRadius: 22))
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
                .background(selected ? EyeStyle.primarySoft : EyeStyle.surfaceVariant, in: Capsule())
                .overlay { Capsule().stroke(selected ? EyeStyle.primary : EyeStyle.surfaceVariant, lineWidth: 1) }
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
            Text(label).font(EyeStyle.chip).foregroundStyle(primary ? EyeStyle.panel : EyeStyle.text)
                .padding(.horizontal, 14).padding(.vertical, 8)
                .background(primary ? EyeStyle.primary : EyeStyle.surfaceVariant, in: Capsule())
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
        Text(text).font(EyeStyle.chip).foregroundStyle(filled ? EyeStyle.onStatus : color)
            .padding(.horizontal, 12).padding(.vertical, 6)
            .background(filled ? color : color.opacity(0.12), in: Capsule())
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
            let width = (geometry.size.width - 8) / CGFloat(max(options.count, 1))
            let index = CGFloat(options.firstIndex(where: { $0.0 == selected }) ?? 0)
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: 12).fill(EyeStyle.primary).frame(width: width).offset(x: width * index)
                    .animation(.spring(response: 0.35, dampingFraction: 0.8), value: selected)
                HStack(spacing: 0) {
                    ForEach(Array(options.enumerated()), id: \.offset) { _, option in
                        Button { EyeHaptics.tap(); onSelect(option.0) } label: {
                            Text(option.1).font(EyeStyle.button)
                                .foregroundStyle(option.0 == selected ? EyeStyle.panel : EyeStyle.text)
                                .frame(maxWidth: .infinity, maxHeight: .infinity).contentShape(Rectangle())
                        }.buttonStyle(.plain)
                    }
                }
            }.padding(4)
        }.frame(height: 52).background(EyeStyle.surfaceVariant, in: RoundedRectangle(cornerRadius: EyeStyle.radiusMedium))
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

/// 简洁柱状图（用眼趋势）：柱顶数值、柱下日期，今天主色、其余浅主色，超出参考线的琥珀色；参考线为虚线。与安卓 EyeBarChart 一致。
struct BarChartView: View {
    let bars: [BarItem]
    var reference: Double? = nil
    var maxValue: Double? = nil
    var height: CGFloat = 140
    @State private var grow: CGFloat = 0

    var body: some View {
        let top = max(maxValue ?? 0, bars.compactMap { $0.value }.max() ?? 0, (reference ?? 0) * 1.15, 1)
        VStack(spacing: 4) {
            HStack(spacing: 0) {
                ForEach(bars) { bar in
                    Text(bar.text ?? " ").font(EyeStyle.chip).foregroundStyle(bar.highlight ? EyeStyle.primary : EyeStyle.secondary)
                        .lineLimit(1).frame(maxWidth: .infinity)
                }
            }
            Canvas { context, size in
                let slot = size.width / CGFloat(max(bars.count, 1))
                let barWidth = min(slot * 0.46, 28)
                var base = Path(); base.move(to: CGPoint(x: 0, y: size.height)); base.addLine(to: CGPoint(x: size.width, y: size.height))
                context.stroke(base, with: .color(EyeStyle.borderSubtle), lineWidth: 1)
                for (i, bar) in bars.enumerated() {
                    let x = slot * CGFloat(i) + (slot - barWidth) / 2
                    guard let v = bar.value else {
                        var dash = Path(); dash.move(to: CGPoint(x: x, y: size.height - 3)); dash.addLine(to: CGPoint(x: x + barWidth, y: size.height - 3))
                        context.stroke(dash, with: .color(EyeStyle.borderSubtle), lineWidth: 3)
                        continue
                    }
                    let h = max(CGFloat(v / top) * size.height * grow, v > 0 ? 4 : 0)
                    let color = bar.warning ? EyeStyle.accent : bar.highlight ? EyeStyle.primary : EyeStyle.primary.opacity(0.35)
                    context.fill(Path(roundedRect: CGRect(x: x, y: size.height - h, width: barWidth, height: h), cornerRadius: barWidth / 2.6), with: .color(color))
                }
                if let reference {
                    let y = size.height - CGFloat(reference / top) * size.height
                    var line = Path(); line.move(to: CGPoint(x: 0, y: y)); line.addLine(to: CGPoint(x: size.width, y: y))
                    context.stroke(line, with: .color(EyeStyle.secondary), style: StrokeStyle(lineWidth: 1, dash: [4, 3]))
                }
            }.frame(height: height)
            HStack(spacing: 0) {
                ForEach(bars) { bar in
                    Text(bar.label).font(EyeStyle.chip).foregroundStyle(bar.highlight ? EyeStyle.primary : EyeStyle.secondary)
                        .lineLimit(1).frame(maxWidth: .infinity)
                }
            }
        }
        .onAppear { withAnimation(.easeOut(duration: 0.7)) { grow = 1 } }
    }
}

// MARK: - 应用图标与启动动画

/// 应用图标（与桌面图标同一构图）。
struct AppIconView: View {
    var body: some View {
        Canvas { context, size in
            let side = min(size.width, size.height)
            Cartoon.appIcon(context, origin: CGPoint(x: (size.width - side) / 2, y: (size.height - side) / 2), side: side)
        }.accessibilityLabel("眼健康助手图标")
    }
}

/// 启动动画（约 1.3 秒）：小瞳蹦出来打招呼 + 加载进度条 + 「你的主动眼健康助手」，结束后淡出。
/// 只盖在已经创建好的首页上，不推迟首页加载。与安卓 EyeSplash 同一节奏。
struct SplashView: View {
    let onFinished: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var progress: CGFloat = 0
    @State private var visible = true
    var body: some View {
        ZStack {
            LinearGradient(colors: [EyeStyle.primarySoft, EyeStyle.background], startPoint: .top, endPoint: .bottom).ignoresSafeArea()
            VStack(spacing: 0) {
                MascotStage(mood: .happy, interactive: false, greet: true, headScale: 0.66).frame(width: 168, height: 168)
                Text("眼健康助手").font(EyeStyle.title).foregroundStyle(EyeStyle.text).padding(.top, 12)
                Text("你的主动眼健康助手").font(EyeStyle.body).foregroundStyle(EyeStyle.secondary).padding(.top, 6)
                ZStack(alignment: .leading) {
                    Capsule().fill(EyeStyle.surfaceVariant)
                    Capsule().fill(LinearGradient(colors: [EyeStyle.brandStart, EyeStyle.brandEnd], startPoint: .leading, endPoint: .trailing))
                        .frame(width: 168 * progress)
                }.frame(width: 168, height: 6).padding(.top, 28)
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

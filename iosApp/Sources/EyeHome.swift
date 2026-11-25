import SwiftUI
import EyeScreening

// 首页与它的二级页面（用眼趋势、健康建议、亮度与蓝光、休息一下）。与安卓 feature:home 同一顺序、排版与文案。

private let noDiscomfort = "没有不适"

/// 等级配色：轻松绿、留意琥珀、偏累红、待感知灰。
private func levelColor(_ level: String) -> Color {
    switch level { case "LOW": return EyeStyle.success; case "MEDIUM": return EyeStyle.warning; case "HIGH": return EyeStyle.danger; default: return EyeStyle.secondary }
}

/// 首页：日期与「今日」→ 开口圆环、眼健康指数及账户头像 → 今日数据与护眼工具 →
/// 快捷记下今日感受）→ 实时检测（未开始时一行）→ 休息提醒一行。与安卓 HomeContent 一致。
struct HomePage: View {
    let navigate: (AppRoute) -> Void
    let openTest: (String) -> Void
    var body: some View { BrownHomePage(navigate: navigate, openTest: openTest) }
}

/// 分区标题（设计令牌的 22 号半粗），与上一块内容拉开 sectionGap。
private struct SectionTitle: View {
    let text: String
    var body: some View {
        Text(text).font(EyeStyle.title).foregroundStyle(EyeStyle.text)
            .padding(.top, EyeSpacing.sectionGap - EyeSpacing.cardGap).padding(.leading, 4)
    }
}

// MARK: - 今日用眼

/// 圆环与状态点用的等级色（琥珀用更亮的 accent）。
private func ringColor(_ level: String) -> Color {
    switch level { case "LOW": return EyeStyle.success; case "MEDIUM": return EyeStyle.accent; case "HIGH": return EyeStyle.danger; default: return EyeStyle.tertiary }
}

/// 开口圆环与账户头像。眼健康指数 = 100 − 用眼负荷分，与安卓 EyeCareHero 一致。
private struct HeroCard: View {
    let report: CareReport
    let openAccount: () -> Void
    @ObservedObject private var cloud = CloudModel.shared
    private var mood: EyeMood {
        switch report.level { case "LOW": .happy; case "MEDIUM": .calm; case "HIGH": .tired; default: .waiting }
    }
    var body: some View {
        let index = report.score.map(healthIndex)
        VStack(alignment: .leading, spacing: EyeSpacing.md) {
            Text("眼健康指数").font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text)
            ZStack(alignment: .bottom) {
                ZStack {
                    ProgressRing(fraction: index.map { Double($0) / 100 }, lineWidth: 12, ticks: 40, showEndpoint: true, startAngle: 130, sweepAngle: 280, uniformTicks: true)
                    VStack(spacing: EyeSpacing.xxs) {
                        MoodFace(mood: mood).frame(width: 44, height: 44)
                        HStack(alignment: .firstTextBaseline, spacing: EyeSpacing.xxs) {
                            Text(index.map { "\($0)" } ?? "—").font(EyeStyle.healthScore).foregroundStyle(EyeStyle.heading)
                                .monospacedDigit().lineLimit(1).minimumScaleFactor(0.7)
                            Text("/100").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                        }
                        Text(report.label).font(EyeStyle.label).foregroundStyle(EyeStyle.secondary)
                    }.padding(.bottom, EyeSpacing.md)
                }.aspectRatio(1, contentMode: .fit).padding(.bottom, 24)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(index.map { "今日眼健康指数 \($0) 分，\(report.label)" } ?? "今日眼健康指数暂无数据")
                Button(action: openAccount) {
                    let initial = cloud.bool("signedIn") ? String(cloud.string("email").prefix(1)).uppercased() : ""
                    ZStack {
                        if initial.isEmpty { EyeGlyphView(glyph: .person, color: EyeStyle.primary).frame(width: 26, height: 26) }
                        else { Text(initial).font(EyeStyle.title).foregroundStyle(EyeStyle.text) }
                    }.frame(width: 56, height: 56).background(EyeStyle.panel, in: Circle())
                        .shadow(color: EyeStyle.shadowLight, radius: 8, x: -3, y: -3)
                        .shadow(color: EyeStyle.shadow, radius: 12, x: 4, y: 4)
                }.buttonStyle(.plain).accessibilityLabel("账户头像")
            }.frame(maxWidth: 260).frame(maxWidth: .infinity)
        }
    }
}

// MARK: - 今日感受（反馈）

/// 自动换行的流式排版（感受选项）。
struct FlowLayout: Layout {
    var spacing: CGFloat = 8
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, rowHeight: CGFloat = 0, width: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > 0 && x + size.width > maxWidth { x = 0; y += rowHeight + spacing; rowHeight = 0 }
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
            width = max(width, x - spacing)
        }
        return CGSize(width: proposal.width ?? width, height: y + rowHeight)
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, rowHeight: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > bounds.minX && x + size.width > bounds.maxX { x = bounds.minX; y += rowHeight + spacing; rowHeight = 0 }
            view.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}

/// 首页爱科卡的标签。
let eyechoCardLabel = "爱科 · AI 问诊"

/// 爱科卡：右侧爱科半身像（浅紫圆形衬底），左侧「今天眼睛感觉怎么样？」与快捷感受（点一下就记下今日感受，「更多」弹出完整选择）；
/// 已记下时显示记下的感受与「修改」。点卡片其余位置进入与爱科的对话。与安卓 EyechoCard 一致。
private struct EyechoCard: View {
    let report: CareReport
    let openChat: () -> Void
    let checkIn: ([String]) -> Void
    let edit: () -> Void
    var body: some View {
        let checked = report.checkedInToday
        let symptoms = report.todaySymptoms
        let quick = Array(EyeCareBridge.shared.symptoms.prefix(2)) + [noDiscomfort]
        ZStack(alignment: .topLeading) {
            Button { EyeHaptics.tap(); openChat() } label: {
                ZStack(alignment: .bottomTrailing) {
                    Color.clear
                    Image("EyechoHalf").resizable().scaledToFill()
                        .frame(width: EyeStyle.homePortraitWidth, height: EyeStyle.homePortraitHeight, alignment: .top).clipped()
                        .padding(.trailing, EyeStyle.homePortraitInset)
                        .accessibilityLabel("爱科")
                }
                .frame(maxWidth: .infinity, minHeight: EyeStyle.homeChatMinHeight, maxHeight: EyeStyle.homeChatMinHeight)
                .clipShape(RoundedRectangle(cornerRadius: EyeStyle.radiusHero, style: .continuous))
            }.buttonStyle(PressScale(scale: 0.98))
            VStack(alignment: .leading, spacing: 8) {
                EyeGlyphView(glyph: .chat, color: EyeStyle.violet).frame(width: 24, height: 24)
                Text(eyechoCardLabel).font(EyeStyle.title).foregroundStyle(EyeStyle.text)
                Text(checked ? "今天的感受已记下" : "今天眼睛感觉怎么样？").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                FlowLayout(spacing: 8) {
                    if checked {
                        ForEach(symptoms.isEmpty ? [noDiscomfort] : symptoms, id: \.self) { label in
                            Text(label).font(EyeStyle.chip).foregroundStyle(EyeStyle.readableText(EyeStyle.primary))
                                .padding(.horizontal, 10).padding(.vertical, 6).background(EyeStyle.primarySoft, in: EyeStyle.chipShape).frame(minHeight: 48)
                        }
                        Button(action: edit) {
                            EyeGlyphView(glyph: .edit, color: EyeStyle.primary).frame(width: 22, height: 22)
                                .frame(width: 48, height: 48)
                        }.buttonStyle(.plain).accessibilityLabel("编辑今日感受")
                    } else {
                        ForEach(quick, id: \.self) { label in
                            QuickChip(label: label) { checkIn(label == noDiscomfort ? [] : [label]) }
                        }
                        QuickChip(label: "更多", action: edit)
                    }
                }.padding(.top, 4)
            }
            .padding(18).frame(width: 240, alignment: .leading)
        }.eyeLiquidGlass(cornerRadius: EyeStyle.radiusHero)
    }
}

/// 爱科卡里的快捷感受：浅填充小块。
private struct QuickChip: View {
    let label: String
    let action: () -> Void
    var body: some View {
        Button { EyeHaptics.tap(); action() } label: {
            Text(label).font(EyeStyle.chip.weight(.medium)).foregroundStyle(EyeStyle.text)
                .padding(.horizontal, 10).padding(.vertical, 6).background(EyeStyle.surfaceVariant, in: EyeStyle.chipShape)
        }.buttonStyle(PressScale())
    }
}

/// 选择今天的感受：屏幕中间的弹窗，「没有不适」占一整行，其余等宽两列；选了才能保存。与安卓 FeelingDialog 一致。
struct FeelingSheet: View {
    let save: ([String]) -> Void
    let dismiss: () -> Void
    @State private var selected: [String]
    @State private var note: String
    let saveNote: (([String],String) -> Void)?
    let inline: Bool
    init(initial: [String], save: @escaping ([String]) -> Void, dismiss: @escaping () -> Void, initialNote: String = "", saveNote: (([String],String) -> Void)? = nil, inline: Bool = false) {
        self.save = save; self.dismiss = dismiss; self.inline = inline
        _selected = State(initialValue: initial)
        _note = State(initialValue: initialNote); self.saveNote = saveNote
    }
    var body: some View {
        if inline { fields }
        else {
            ZStack {
                EyeStyle.ink.opacity(0.55).ignoresSafeArea().contentShape(Rectangle()).onTapGesture { dismiss() }
                fields.padding(24)
            }.accessibilityAddTraits(.isModal)
        }
    }
    private var fields: some View {
        WarmCard {
            HStack {
                Text("今日感受").font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text)
                Spacer()
                Button("取消", action: dismiss).font(EyeStyle.bodySecondary).frame(minHeight: 44)
            }
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)], spacing: 8) {
                ForEach([noDiscomfort,"眼睛干涩","眼睛酸胀","眼睛疲劳"], id: \.self) { option($0) }
            }
            TextField("补充记录（可选）", text: $note,
                      prompt: Text("补充记录（可选）").foregroundColor(EyeStyle.secondary), axis: .vertical)
                .lineLimit(2...4).eyeInputField()
            Button("保存") {
                EyeHaptics.success()
                if let saveNote { saveNote(selected,String(note.prefix(500))) } else { save(selected) }
            }.buttonStyle(EyeButtonStyle()).disabled(selected.isEmpty)
        }
    }
    private func option(_ label: String) -> some View {
        let on = selected.contains(label)
        let foreground = on ? EyeStyle.onPrimary : EyeStyle.text
        return Button { EyeHaptics.toggle(); toggle(label) } label: {
            VStack(spacing: 4) {
                EyeGlyphView(glyph: .eye,color: foreground).frame(width: 24,height: 24)
                Text(label == noDiscomfort ? "舒适" : label == "眼睛干涩" ? "干涩" : label == "眼睛酸胀" ? "酸胀" : "疲劳")
                    .font(on ? EyeStyle.body.weight(.semibold) : EyeStyle.body)
            }.foregroundStyle(foreground).multilineTextAlignment(.center)
                .lineLimit(1).minimumScaleFactor(0.8)
                .padding(.horizontal, 6).frame(maxWidth: .infinity, minHeight: 52)
                .background(on ? EyeStyle.primary : EyeStyle.surfaceVariant, in: RoundedRectangle(cornerRadius: EyeStyle.radiusMedium))
                .overlay { RoundedRectangle(cornerRadius: EyeStyle.radiusMedium).strokeBorder(on ? EyeStyle.primary : EyeStyle.glassEdgeTop, lineWidth: 1.5).allowsHitTesting(false) }
                .contentShape(RoundedRectangle(cornerRadius: EyeStyle.radiusMedium))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? [.isSelected] : [])
    }
    private func toggle(_ label: String) {
        if label == noDiscomfort { selected = selected.contains(label) ? [] : [label]; return }
        if selected.contains(label) { selected.removeAll { $0 == label } }
        else { selected.removeAll { $0 == noDiscomfort }; selected.append(label) }
    }
}

// MARK: - 干预（图标）

func glyphFor(_ kind: String) -> (EyeGlyph, TileTone) {
    switch kind {
    case "BREAK": return (.coffee, .success)
    case "REMINDER": return (.bell, .accent)
    case "BLINK": return (.eye, .primary)
    case "DISTANCE": return (.ruler, .blue)
    case "BRIGHTNESS": return (.sun, .accent)
    case "NIGHT": return (.moon, .blue)
    case "OUTDOOR": return (.sun, .success)
    case "TEST": return (.document, .primary)
    case "PROFILE": return (.person, .primary)
    case "LIVE": return (.camera, .primary)
    default: return (.info, .neutral)
    }
}

// MARK: - 基础数据

/// 一格读数：名称在上、数值在下（需要留意时名称后带琥珀小圆点）。
struct SignalCell: View {
    let signal: CareReport.Signal?
    var body: some View {
        let attention = signal?.status == "ATTENTION"
        let unavailable = signal == nil || signal?.status == "UNAVAILABLE"
        MetricView(label: signal?.label ?? "", value: signal?.value ?? "—",
                   valueColor: unavailable ? EyeStyle.tertiary : EyeStyle.text, attention: attention)
    }
}

/// 读数网格：每行 [columns] 项，同行竖线分隔、行间横线分隔。
struct SignalGrid: View {
    let signals: [CareReport.Signal]
    let columns: Int
    var body: some View {
        let rows = stride(from: 0, to: signals.count, by: columns).map { Array(signals[$0..<min($0 + columns, signals.count)]) }
        VStack(spacing: 0) {
            ForEach(Array(rows.enumerated()), id: \.offset) { i, row in
                if i > 0 { Rectangle().fill(EyeStyle.borderSubtle).frame(height: 1).padding(.vertical, 12) }
                HStack(spacing: 0) {
                    ForEach(0..<columns, id: \.self) { k in
                        if k > 0 { VDivider() }
                        if k < row.count { SignalCell(signal: row[k]) } else { Color.clear.frame(maxWidth: .infinity) }
                    }
                }.fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

private struct CardHeader: View {
    let title: String
    let glyph: EyeGlyph
    let tone: TileTone
    var body: some View { CategoryHeader(title: title, glyph: glyph, tone: tone) }
}

/// 各读数在首页的类别图标与类别色。
private func signalCategory(_ key: String) -> (EyeGlyph, Color) {
    switch key {
    case "screen": return (.hourglass, EyeStyle.screen)
    case "longest": return (.clock, EyeStyle.sun)
    case "night": return (.moon, EyeStyle.violet)
    case "breaks": return (.coffee, EyeStyle.success)
    case "light", "brightness": return (.sun, EyeStyle.sun)
    default: return (.info, EyeStyle.secondary)
    }
}

/// 今日数据：本机系统提供的基础读数（屏幕亮度放在「亮度与蓝光」工具上），两列小卡：类别标签在上、大号读数在下。
private struct TodayData: View {
    let report: CareReport
    var body: some View {
        let signals = report.signals("BASIC").filter { $0.id != "brightness" }
        if !signals.isEmpty {
            SectionTitle(text: "今日数据")
            LazyVGrid(columns: [GridItem(.flexible(), spacing: EyeSpacing.cardGap), GridItem(.flexible(), spacing: EyeSpacing.cardGap)],
                      spacing: EyeSpacing.cardGap) {
                ForEach(signals) { SignalCard(signal: $0) }
            }
        }
    }
}

/// 一格读数小卡：类别标签（需要留意时右侧琥珀「留意」）+ 大号读数（数字大、单位灰）。
private struct SignalCard: View {
    let signal: CareReport.Signal
    var body: some View {
        let category = signalCategory(signal.id)
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 6) {
                EyeGlyphView(glyph: category.0, color: category.1).frame(width: 16, height: 16)
                Text(signal.label).font(EyeStyle.label).foregroundStyle(EyeStyle.secondary).lineLimit(1)
                Spacer(minLength: 4)
                if signal.status == "ATTENTION" { Text("留意").font(EyeStyle.chip).foregroundStyle(EyeStyle.readableText(EyeStyle.warning)) }
            }
            EyeStyle.reading(signal.value, color: signal.status == "UNAVAILABLE" ? EyeStyle.tertiary : EyeStyle.text)
                .lineLimit(1).minimumScaleFactor(0.7)
        }
        .padding(EyeSpacing.cardPadding).frame(maxWidth: .infinity, alignment: .leading)
        .eyeGlass().eyeGlowBorder()
        .accessibilityElement(children: .combine)
    }
}

// MARK: - 功能卡与休息提醒

/// 休息提醒：一行（图标 + 名称 + 间隔 + 开关），开启后下方可换间隔。不给出建议间隔。与安卓 ReminderCard 一致。
struct ReminderCard: View {
    @ObservedObject var reminder: EyeReminder
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 14) {
                EyeGlyphView(glyph: .bell, color: EyeStyle.success).frame(width: 18, height: 18)
                    .frame(width: 32, height: 32).background(EyeStyle.successSoft, in: RoundedRectangle(cornerRadius: EyeStyle.radiusSmall, style: .continuous))
                Text("休息提醒").font(EyeStyle.navigationTitle).foregroundStyle(EyeStyle.text).frame(maxWidth: .infinity, alignment: .leading)
                if reminder.enabled { Text("每 \(reminder.intervalMinutes) 分钟").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary) }
                Toggle("", isOn: Binding(get: { reminder.enabled }, set: { EyeHaptics.toggle(); reminder.setEnabled($0) }))
                    .labelsHidden().tint(EyeStyle.primary)
            }.frame(minHeight: 36)
            if reminder.enabled {
                SegmentedPicker(options: [(20, "每 20 分钟"), (30, "每 30 分钟"), (60, "每 60 分钟")], selected: reminder.intervalMinutes) { reminder.setInterval($0) }
            }
            if let notice = reminder.notice { Text(notice).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.readableText(EyeStyle.warning)) }
        }
        .padding(.horizontal, 16).padding(.vertical, 12).frame(maxWidth: .infinity, alignment: .leading)
        .eyeGlass().eyeGlowBorder()
        .animation(.easeOut(duration: 0.2), value: reminder.enabled)
    }
}

/// 护眼工具：三个工具入口，只保留图标与单行名称。与安卓 ToolRow 一致。
private struct ToolRow: View {
    let navigate: (AppRoute) -> Void
    let openExercise: () -> Void
    var body: some View {
        HStack(spacing: EyeSpacing.cardGap) {
            ToolTile(title: "护眼操", glyph: .eye, soft: EyeStyle.successSoft, ink: EyeStyle.success, action: openExercise)
            ToolTile(title: "用眼趋势", glyph: .chart, soft: EyeStyle.screenSoft, ink: EyeStyle.screen) { navigate(.trend) }
            ToolTile(title: "屏幕光", glyph: .sun, soft: EyeStyle.sunSoft, ink: EyeStyle.sun) { navigate(.brightness) }
        }.fixedSize(horizontal: false, vertical: true)
    }
}

private struct ToolTile: View {
    let title: String
    let glyph: EyeGlyph
    let soft: Color
    let ink: Color
    let action: () -> Void
    var body: some View {
        Button { EyeHaptics.tap(); action() } label: {
            VStack(alignment: .leading, spacing: 12) {
                EyeGlyphView(glyph: glyph, color: ink).frame(width: 22, height: 22)
                    .frame(width: 40, height: 40).background(soft, in: RoundedRectangle(cornerRadius: EyeStyle.radiusSmall, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(EyeStyle.navigationTitle).foregroundStyle(EyeStyle.text).lineLimit(1).minimumScaleFactor(0.8)
                }
            }
            .padding(.horizontal, 12).padding(.vertical, 16)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .eyeLiquidGlass()
            .contentShape(RoundedRectangle(cornerRadius: EyeStyle.radius))
        }.buttonStyle(PressScale(scale: 0.97))
    }
}

// MARK: - 用眼趋势报告

/// 眼健康指数 = 100 − 用眼负荷分（规则仍按负荷算，界面只展示指数，越高越好）。与安卓 healthIndex 一致。
func healthIndex(_ loadScore: Int) -> Int { min(max(100 - loadScore, 0), 100) }

/// 用眼趋势（2026-11-08 改版）：本周要点 → 眼健康指数、眨眼频率两张图表卡（类别标签 + 小字说明 + 大号读数 + 纯色柱图）→
/// 今天的主要原因（拉低眼健康指数的分值）→ 身体感受。苹果端没有系统用屏数据，没有用屏时长卡。与安卓 EyeTrendContent 一致。
struct TrendPage: View {
    @ObservedObject private var store = EyeCareStore.shared
    var body: some View {
        SubPage(title: "用眼报告") { V16WeeklyTrendView(report: store.report) }.onAppear { store.refresh() }
    }

    /// 图表卡：类别标签（类别色）+ 右侧一项对比 / 等级，下面小字说明 + 大号读数（数字大、单位灰），再下面是图。
    private func chart<Content: View>(_ title: String, glyph: EyeGlyph, tint: Color, caption: String, value: String,
                                      trailing: (String, Color)?, help: String?, @ViewBuilder _ content: () -> Content) -> some View {
        Panel {
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    EyeGlyphView(glyph: glyph, color: tint).frame(width: 16, height: 16)
                    Text(title).font(EyeStyle.chip).foregroundStyle(tint)
                    if let help { HelpButton(title: title, text: help) }
                    Spacer()
                    if let trailing { Text(trailing.0).font(EyeStyle.chip).foregroundStyle(trailing.1) }
                }
                Text(caption).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary).padding(.top, 2)
                EyeStyle.reading(value).lineLimit(1)
            }
            content().padding(.top, 6)
        }
    }
}

// MARK: - 健康建议

/// 健康建议：专业咨询建议（规则按近期自测、身体感受与年龄给出）+ 建议做的自测。AI 综合建议在单独的对话页。
struct AdvicePage: View {
    let openTest: (String) -> Void
    @ObservedObject private var store = EyeCareStore.shared
    var body: some View {
        let report = store.report
        let tests = report.actions.filter { $0.kind == "TEST" }
        SubPage(title: "健康建议") {
            EyeHeroCard {
                Text(report.headline).font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text).fixedSize(horizontal: false, vertical: true)
            }
            Text("专业咨询建议").font(EyeStyle.title).foregroundStyle(EyeStyle.text).padding(.leading, 4).padding(.top, 4)
            ForEach(report.consults) { ConsultCard(consult: $0) }
            if !tests.isEmpty {
                ListGroup(title: "建议做的自测") {
                    ForEach(Array(tests.enumerated()), id: \.element.id) { i, action in
                        let (glyph, tone) = glyphFor(action.kind)
                        ListRow(title: action.title, glyph: glyph, tone: tone, subtitle: action.detail, divider: i < tests.count - 1) {
                            if let target = action.target { openTest(target) }
                        }
                    }
                }
            }
        }
        .onAppear { store.refresh() }
    }
}

private struct ConsultCard: View {
    let consult: CareReport.Consult
    var body: some View {
        let strong = consult.urgent ? EyeStyle.danger : EyeStyle.primary
        let soft = consult.urgent ? EyeStyle.dangerSoft : EyeStyle.primarySoft
        Panel {
            HStack(spacing: 10) {
                Circle().fill(strong).frame(width: 10, height: 10)
                Text(consult.title).font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text).frame(maxWidth: .infinity, alignment: .leading)
                if consult.urgent { StatusChip(text: "尽快", color: EyeStyle.danger) }
            }
            Text(consult.detail).font(EyeStyle.body).foregroundStyle(EyeStyle.text).fixedSize(horizontal: false, vertical: true)
            if !consult.department.isEmpty {
                Text(consult.department).font(EyeStyle.chip).foregroundStyle(strong)
                    .padding(.horizontal, 10).padding(.vertical, 5).background(soft, in: EyeStyle.chipShape)
            }
        }
    }
}

// MARK: - 亮度与蓝光

private let blueLightHelp = "目前没有证据表明手机屏幕的蓝光会损伤眼睛，但晚上看屏幕会影响睡眠节律。夜间模式让屏幕颜色偏暖，更适合晚上使用。"

/// 亮度与蓝光：当前屏幕亮度、评估与做法，可在这里直接调节屏幕亮度。
struct BrightnessPage: View {
    @ObservedObject private var store = EyeCareStore.shared
    @State private var brightness = Double(UIScreen.main.brightness)
    var body: some View {
        let report = store.report
        let factor = report.factors.first { $0.id == "night-bright" || $0.id == "night" || $0.id == "dark-bright" }
        SubPage(title: "亮度与蓝光") {
            Panel {
                HStack(spacing: 10) {
                    IconTile(glyph: .sun, tone: .accent, size: 32)
                    Text("屏幕亮度").font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text).frame(maxWidth: .infinity, alignment: .leading)
                    Text("\(Int((brightness * 100).rounded()))%").font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text).monospacedDigit()
                }
                Slider(value: Binding(get: { brightness }, set: { brightness = $0; UIScreen.main.brightness = CGFloat($0) }), in: 0.05...1)
                    .tint(EyeStyle.primary)
                Text(factor?.title ?? "亮度以眼睛感觉舒适为准。").font(EyeStyle.body)
                    .foregroundStyle(factor != nil ? EyeStyle.warning : EyeStyle.text)
            }
            Panel {
                HStack(spacing: 0) {
                    Text("这样做更舒服").font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text)
                    HelpButton(title: "关于蓝光", text: blueLightHelp)
                }
                Bullet(text: "暗处调低，户外调高。")
                Bullet(text: "可开启自动亮度，睡前少看屏幕。")
            }
        }
        .onAppear { brightness = Double(UIScreen.main.brightness) }
        .onReceive(NotificationCenter.default.publisher(for: UIScreen.brightnessDidChangeNotification)) { _ in
            brightness = Double(UIScreen.main.brightness)
            store.refresh()
        }
        .onDisappear { store.refresh() }
    }
}

// MARK: - 休息一下

/// 按实际经过时间倒计时，提前关闭或进入后台后不能补记完成。
struct CareCountdown {
    private(set) var startedAt: Date?
    private(set) var completed = false
    private var cancelled = false
    mutating func start(at now: Date) { if startedAt == nil && !cancelled { startedAt = now } }
    func remaining(total: TimeInterval,at now: Date) -> TimeInterval {
        guard let startedAt else { return total }
        return max(0,min(total,total - now.timeIntervalSince(startedAt)))
    }
    mutating func complete(total: TimeInterval,at now: Date) -> Bool {
        guard startedAt != nil, !cancelled, !completed, remaining(total: total,at: now) <= 0 else { return false }
        completed = true; return true
    }
    mutating func cancel() { cancelled = true }
}

/// 看远、闭眼和热敷共用的圆环倒计时，结束时有提示音与轻触觉。
struct RestBreakView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var countdown = CareCountdown()
    @State private var scheduleStart = Date()
    private var running: Bool { countdown.startedAt != nil }
    private var done: Bool { countdown.completed }
    var title = "休息一下"
    var seconds = 20.0
    var hint = "看向6米外的远处。\n慢慢眨眼，放松肩膀。"
    var onComplete: () -> Void = {}
    var body: some View {
        NavigationStack {
            TimelineView(.periodic(from: countdown.startedAt ?? scheduleStart, by: 0.1)) { timeline in
                let elapsed = seconds - countdown.remaining(total: seconds,at: timeline.date)
                VStack(spacing: 0) {
                    Spacer()
                    ZStack {
                        ProgressRing(fraction: elapsed / seconds, lineWidth: 10)
                        HStack(alignment: .firstTextBaseline,spacing: 4) {
                            Text(done ? "完成" : "\(Int(ceil(seconds - elapsed)))").font(EyeStyle.display).monospacedDigit()
                            if !done { Text("秒").font(EyeStyle.body) }
                        }.foregroundStyle(EyeStyle.text)
                    }.frame(width: 240, height: 240)
                    if done { Text("做得好！").font(EyeStyle.title).foregroundStyle(EyeStyle.text).padding(.top, 24) }
                    Text(done ? "本次已记录。" : hint)
                        .font(EyeStyle.body).foregroundStyle(EyeStyle.secondary).multilineTextAlignment(.center)
                        .fixedSize(horizontal: false,vertical: true).padding(.horizontal, 24).padding(.top, 24)
                    Spacer()
                    Button(!running ? "开始" : done ? "完成" : "提前结束") {
                        EyeHaptics.tap()
                        if !running { countdown.start(at: Date()) } else { countdown.cancel(); dismiss() }
                    }.buttonStyle(EyeButtonStyle(role: !running || done ? .primary : .secondary)).padding(16)
                }
                .onChange(of: elapsed >= seconds) { finished in
                    if finished && scenePhase == .active { finishIfNeeded(at: timeline.date) }
                }
            }
            .background(EyeBackdrop())
            .navigationTitle(title).navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { countdown.cancel(); dismiss() } } }
        }
        .onDisappear { countdown.cancel() }
        .onChange(of: scenePhase) { phase in
            if phase == .background && !done { countdown.cancel(); dismiss() }
            else if phase == .active { finishIfNeeded(at: Date()) }
        }
    }
    private func finishIfNeeded(at now: Date) {
        if countdown.complete(total: seconds,at: now) {
            onComplete(); EyeCueSound.shared.play(.complete); EyeHaptics.success()
        }
    }
}

// MARK: - 护眼操

#if SCREENSHOT_CAPTURE
extension RestBreakView {
    /// 只在截图构建注入指定计时位置，不保存打卡或启动真实辅助任务。
    init(captureTitle: String,seconds: Double,hint: String,elapsed: Double? = nil) {
        self.init(title: captureTitle,seconds: seconds,hint: hint)
        var clock = CareCountdown()
        if let elapsed {
            let now = Date()
            clock.start(at: now.addingTimeInterval(-elapsed))
            if elapsed >= seconds { _ = clock.complete(total: seconds,at: now) }
        }
        _countdown = State(initialValue: clock)
    }
}
#endif

/// 「护眼操」互动小游戏完整页面（暖夜寻星，与安卓 EyeExercisePage 同一情景、流程与文案）：
/// 小猫头鹰在深咖啡星空中央实时跟随眼球转动；屏幕左/右/上/下方位轮流亮起迷路暖星，
/// 转动眼球看过去即可充能并收进星盘，最后和小猫头鹰一起闭眼听提示音休息 5 秒。
/// 判断与计时在共用 EyeExerciseCoach；没有相机权限时按节奏引导完成。
struct EyeExerciseView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var coach = EyeExerciseCoach()
    @State private var started = false
    @State private var state: EyeExerciseCoach.State?
    @State private var feedbackBanner: String?
    @State private var caughtMove: EyeExerciseCoach.Move?
    @State private var owlHappy = false
    @State private var cometProgress: CGFloat = 1
    @State private var restPulse: CGFloat = 0
    @State private var pulsePhase: CGFloat = 0
    @State private var smoothGazeX: CGFloat = 0
    @State private var smoothGazeY: CGFloat = 0
    private let ticker = Timer.publish(every: 0.05, on: .main, in: .common).autoconnect()

    var body: some View {
        GeometryReader { geo in
            ZStack {
                Canvas { ctx, size in
                    StarOwlScene.backdrop(ctx, size: size, twinkle: pulsePhase, resting: started && isStage(.rest))
                }
                .ignoresSafeArea()
                .accessibilityHidden(true)

                VStack(spacing: 0) {
                    topBar(width: geo.size.width)
                    if !started {
                        introStage(height: geo.size.height)
                    } else if isStage(.done) {
                        ExerciseCompletionView(
                            total: Int(state?.stepCount ?? 0),
                            seen: Int(state?.detectedCount ?? 0),
                            usedCamera: coach.usedCamera,
                            bestStreak: Int(state?.bestStreak ?? 0),
                            stepResults: currentStepResults,
                            onReplay: startExercise,
                            onClose: { dismiss() }
                        )
                    } else {
                        playStage
                    }
                }
                .padding(.horizontal, EyeSpacing.gutter)
                .padding(.vertical, EyeSpacing.sm)
            }
            .onReceive(ticker) { _ in
                if !reduceMotion {
                    pulsePhase = (pulsePhase + 0.018).truncatingRemainder(dividingBy: 1)
                }
                if started { tick() }
            }
        }
        .onDisappear { EyeLiveModel.shared.stopExercise() }
        .environment(\.colorScheme, .dark)
    }

    private var currentStepResults: [Bool] {
        (state?.stepResults ?? []).map(\.boolValue)
    }

    private func topBar(width: CGFloat) -> some View {
        let streak = Int(state?.streak ?? 0)
        return HStack(spacing: EyeSpacing.xs) {
            Button("结束") { dismiss() }
                .font(EyeStyle.body)
                .foregroundStyle(EyeStyle.text)
                .frame(minWidth: 48, minHeight: 48)
            ZStack(alignment: .leading) {
                EyeStyle.chipShape.fill(EyeStyle.surfaceVariant)
                GeometryReader { bar in
                    EyeStyle.chipShape
                        .fill(EyeStyle.flowGradient)
                        .frame(width: max(0, bar.size.width * CGFloat(started ? (state?.progress ?? 0) : 0)))
                }
            }
            .frame(height: 8)
            if started && !isStage(.done) {
                let badgeText = streak >= 2 ? "连星 ×\(streak)" : (state?.faceVisible == true || coach.usedCamera ? "眼动追踪" : "节奏引导")
                let highlight = streak >= 2
                Text(badgeText)
                    .font(EyeStyle.chip)
                    .foregroundStyle(highlight ? EyeStyle.moodGold : EyeStyle.secondary)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 5)
                    .background(highlight ? EyeStyle.primarySoft : EyeStyle.panel, in: EyeStyle.chipShape)
                    .overlay {
                        EyeStyle.chipShape.strokeBorder(highlight ? EyeStyle.primary : EyeStyle.borderSubtle, lineWidth: 1)
                    }
            }
        }
    }

    private func introStage(height: CGFloat) -> some View {
        ScrollView {
            VStack(spacing: EyeSpacing.md) {
                Text("暖夜寻星")
                    .font(EyeStyle.chip)
                    .foregroundStyle(EyeStyle.moodGold)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 6)
                    .background(EyeStyle.primarySoft, in: EyeStyle.chipShape)
                    .overlay { EyeStyle.chipShape.strokeBorder(EyeStyle.glassEdgeTop.opacity(0.6), lineWidth: 1) }

                Canvas { ctx, size in
                    let c = CGPoint(x: size.width / 2, y: size.height / 2)
                    let minSide = min(size.width, size.height)
                    StarOwlScene.constellationRing(
                        ctx, center: c, radius: minSide * 0.42, totalSteps: 8,
                        stepResults: [true, true, true], usedCamera: true,
                        calibrateProgress: 0.3, showCalibrateRing: false, resting: false, restPulse: 0
                    )
                    StarOwlScene.owl(ctx, center: c, size: minSide * 0.50, gazeX: 0, gazeY: -0.15)
                }
                .frame(width: 188, height: 188)
                .accessibilityLabel("等待出发的观星小猫头鹰与星盘")

                WarmCard(padding: 20, spacing: 14) {
                    Text("帮小猫头鹰寻回 8 颗星").font(EyeStyle.title).foregroundStyle(EyeStyle.text)
                    Text("头保持不动，只转眼睛看发光的星星；集齐星图后一起闭眼小憩。")
                        .font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
                    HStack(spacing: 8) {
                        introChip("1", "眼看星星")
                        introChip("2", "收进星盘")
                        introChip("3", "闭眼小憩")
                    }
                    Button("出发寻星", action: startExercise).buttonStyle(EyeButtonStyle())
                }
                .frame(maxWidth: 520)
            }
            .padding(.vertical, EyeSpacing.md)
            .frame(minHeight: max(0, height - 88))
        }
    }

    private func introChip(_ index: String, _ label: String) -> some View {
        VStack(spacing: 4) {
            Text(index)
                .font(EyeStyle.caption)
                .foregroundStyle(EyeStyle.moodGold)
                .frame(width: 22, height: 22)
                .background(EyeStyle.primarySoft, in: Circle())
            Text(label)
                .font(EyeStyle.caption)
                .foregroundStyle(EyeStyle.text)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 8)
        .padding(.vertical, 10)
        .background(EyeStyle.surfaceVariant, in: RoundedRectangle(cornerRadius: EyeStyle.radiusMedium, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: EyeStyle.radiusMedium, style: .continuous)
                .strokeBorder(EyeStyle.borderSubtle, lineWidth: 1)
        }
    }

    private var playStage: some View {
        let resting = isStage(.rest)
        let round = Int(state?.stepIndex ?? 0) / 4
        let total = Int(state?.stepCount ?? 8)
        let litCount = coach.usedCamera ? currentStepResults.filter { $0 }.count : currentStepResults.count
        let stagePulse = reduceMotion ? 0.5 : (0.5 - 0.5 * cos(pulsePhase * .pi * 2))

        return VStack(spacing: 0) {
            ZStack(alignment: .bottom) {
                Canvas { ctx, size in
                    let center = CGPoint(x: size.width * 0.5, y: size.height * 0.5)
                    let minSide = min(size.width, size.height)
                    let ringRadius = min(max(minSide * 0.25, 58), 132)
                    let owlSize = ringRadius * 1.16
                    let starRadius = min(max(minSide * 0.068, 18), 34)
                    let edgePad = starRadius * 2.15

                    func anchor(_ move: EyeExerciseCoach.Move) -> CGPoint {
                        if move.dx < 0 { return CGPoint(x: edgePad, y: center.y) }
                        if move.dx > 0 { return CGPoint(x: size.width - edgePad, y: center.y) }
                        if move.dy < 0 { return CGPoint(x: center.x, y: edgePad) }
                        return CGPoint(x: center.x, y: size.height - edgePad)
                    }

                    StarOwlScene.constellationRing(
                        ctx, center: center, radius: ringRadius, totalSteps: max(1, total),
                        stepResults: currentStepResults, usedCamera: coach.usedCamera,
                        calibrateProgress: CGFloat(state?.holdProgress ?? 0),
                        showCalibrateRing: isStage(.calibrate) || state == nil,
                        resting: resting, restPulse: restPulse
                    )
                    StarOwlScene.owl(
                        ctx, center: center, size: owlSize,
                        gazeX: smoothGazeX, gazeY: smoothGazeY,
                        sleeping: resting,
                        happy: owlHappy && isStage(.back),
                        excited: isStage(.move) && (state?.holdProgress ?? 0) > 0.3
                    )
                    if isStage(.move), let move = state?.move {
                        StarOwlScene.targetStar(
                            ctx, origin: center, target: anchor(move), move: move,
                            ringRadius: ringRadius, starRadius: starRadius,
                            holdProgress: CGFloat(state?.holdProgress ?? 0),
                            pulse: stagePulse
                        )
                    }
                    if let caughtMove, cometProgress < 1 {
                        StarOwlScene.comet(
                            ctx, from: anchor(caughtMove), to: center,
                            progress: cometProgress, starRadius: starRadius
                        )
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .accessibilityLabel(stageAccessibilityLabel)

                if resting {
                    VStack(spacing: 2) {
                        Text("\(state?.secondsLeft ?? 0)")
                            .font(EyeStyle.display)
                            .monospacedDigit()
                            .foregroundStyle(EyeStyle.moodGold)
                        Text("秒后轻轻睁眼")
                            .font(EyeStyle.bodySecondary)
                            .foregroundStyle(EyeStyle.secondary)
                    }
                    .padding(.bottom, EyeSpacing.md)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            let title = instructionTitle(round: round)
            let subtitle = instructionSub(litCount: litCount, total: total)
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text)
                    Text(subtitle)
                        .font(EyeStyle.bodySecondary)
                        .foregroundStyle(feedbackBanner != nil && owlHappy ? EyeStyle.moodGold : EyeStyle.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                if isStage(.move) {
                    Text("\(state?.secondsLeft ?? 0)")
                        .font(EyeStyle.metricValue)
                        .monospacedDigit()
                        .foregroundStyle(EyeStyle.primary)
                        .frame(width: 52, height: 52)
                        .background(EyeStyle.surfaceVariant, in: Circle())
                        .overlay { Circle().strokeBorder(EyeStyle.glassEdgeTop.opacity(0.6), lineWidth: 1) }
                }
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 14)
            .frame(maxWidth: 520)
            .background(EyeStyle.panel, in: RoundedRectangle(cornerRadius: EyeStyle.radius, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: EyeStyle.radius, style: .continuous)
                    .strokeBorder(EyeStyle.borderSubtle, lineWidth: 1)
            }
            .padding(.bottom, EyeSpacing.xs)
        }
    }

    private var stageAccessibilityLabel: String {
        if isStage(.calibrate) || state == nil { return "正在唤醒小猫头鹰，请看着中央" }
        if isStage(.move), let move = state?.move { return "\(move.label)，点亮发光的星星" }
        if isStage(.back) { return "请看回屏幕中央的小猫头鹰" }
        if isStage(.rest) { return "闭上眼睛休息，还剩 \(state?.secondsLeft ?? 0) 秒" }
        return "护眼操已完成"
    }

    private func instructionTitle(round: Int) -> String {
        if isStage(.calibrate) || state == nil { return "看着小猫头鹰" }
        if isStage(.move), let move = state?.move { return StarOwlScene.moveTitle(move, round: round) }
        if isStage(.back) { return "看回中间" }
        if isStage(.rest) { return "闭上眼睛休息" }
        return "完成啦！"
    }

    private func instructionSub(litCount: Int, total: Int) -> String {
        if let feedbackBanner { return feedbackBanner }
        if isStage(.calibrate) || state == nil { return "正在校准视线，准备出发寻星" }
        if isStage(.move) {
            return state?.faceVisible == true || coach.usedCamera ? "头保持不动，只转眼睛看发光的星星" : "跟着发光星星转动眼睛就好"
        }
        if isStage(.back) { return "把星光收进星盘（已点亮 \(litCount) / \(total)）" }
        if isStage(.rest) { return "听着每秒轻响，让眼睛安心小憩" }
        return ""
    }

    private func startExercise() {
        EyeLiveModel.shared.stopExercise()
        coach = EyeExerciseCoach()
        state = coach.state(timeMs: 0)
        feedbackBanner = nil
        caughtMove = nil
        owlHappy = false
        cometProgress = 1
        restPulse = 0
        started = true
#if !SCREENSHOT_CAPTURE
        let model = coach
        EyeLiveModel.shared.startExercise { face, time in model.onFace(face: face, timeMs: time) }
#endif
    }

    private var now: Int64 { Int64(ProcessInfo.processInfo.systemUptime * 1000) }
    private func isStage(_ stage: EyeExerciseCoach.Stage) -> Bool { state?.stage == stage }

    private func tick() {
        let t = now
        let previousMove = state?.move
        if let event = coach.tick(timeMs: t) {
            let nextState = coach.state(timeMs: t)
            handle(event, state: nextState, previousMove: previousMove)
            state = nextState
        } else {
            state = coach.state(timeMs: t)
        }
        // 平滑更新猫头鹰视线
        let targetX: CGFloat
        let targetY: CGFloat
        if isStage(.move), let s = state {
            if s.faceVisible {
                targetX = CGFloat(s.gazeX)
                targetY = CGFloat(s.gazeY)
            } else {
                targetX = CGFloat(s.move?.dx ?? 0) * 0.72
                targetY = CGFloat(s.move?.dy ?? 0) * 0.72
            }
        } else {
            targetX = 0; targetY = 0
        }
        let blend: CGFloat = reduceMotion ? 1 : 0.35
        smoothGazeX += (targetX - smoothGazeX) * blend
        smoothGazeY += (targetY - smoothGazeY) * blend
        if cometProgress < 1 {
            cometProgress = min(1, cometProgress + (reduceMotion ? 1 : 0.075))
        }
        if isStage(.rest) && restPulse < 1 {
            restPulse = min(1, restPulse + (reduceMotion ? 1 : 0.055))
        }
    }

    private func handle(_ event: EyeExerciseCoach.Event, state nextState: EyeExerciseCoach.State, previousMove: EyeExerciseCoach.Move?) {
        if event == .started {
            EyeCueSound.shared.play(.start)
        } else if event == .detected {
            let move = nextState.move ?? previousMove
            caughtMove = move
            owlHappy = true
            feedbackBanner = StarOwlScene.caughtFeedback(move, streak: Int(nextState.streak))
            EyeHaptics.success()
            EyeCueSound.shared.play(.phase)
            cometProgress = reduceMotion ? 1 : 0
        } else if event == .missed {
            caughtMove = nil
            owlHappy = false
            feedbackBanner = "这颗星溜走啦，看看下一颗"
            EyeHaptics.tap()
        } else if event == .next {
            if nextState.stage == .back && !coach.usedCamera {
                caughtMove = nextState.move ?? previousMove
                owlHappy = true
                feedbackBanner = "收下一颗星光，看回中间"
                EyeCueSound.shared.play(.phase)
                cometProgress = reduceMotion ? 1 : 0
            } else if nextState.stage == .move {
                owlHappy = false
                feedbackBanner = nil
            }
        } else if event == .rest {
            caughtMove = nil
            owlHappy = false
            feedbackBanner = nil
            restPulse = 0
            EyeHaptics.tap()
            EyeCueSound.shared.play(.phase)
        } else if event == .countdown {
            restPulse = 0
            EyeCueSound.shared.play(.count)
        } else if event == .done {
            EyeHaptics.success()
            EyeCueSound.shared.play(.complete)
        }
    }
}

/// 护眼操互动小游戏「暖夜寻星」的纯矢量场景绘制与文案（与安卓 EyeExerciseGameScene.kt 逐项对应）。
private enum StarOwlScene {
    private static let bgStars: [(CGFloat, CGFloat, CGFloat)] = [
        (0.11, 0.10, 1.4), (0.24, 0.07, 1.0), (0.38, 0.13, 1.2),
        (0.66, 0.08, 1.3), (0.84, 0.12, 1.5), (0.91, 0.24, 1.0),
        (0.08, 0.30, 1.1), (0.19, 0.44, 0.9), (0.87, 0.42, 1.2),
        (0.14, 0.68, 1.1), (0.81, 0.65, 1.0), (0.29, 0.76, 1.3),
        (0.72, 0.74, 1.2), (0.51, 0.05, 1.0),
    ]

    static func moveTitle(_ move: EyeExerciseCoach.Move, round: Int) -> String {
        if move.dx < 0 { return round == 0 ? "向左看 · 寻西边星" : "向左看 · 续亮西星" }
        if move.dx > 0 { return round == 0 ? "向右看 · 寻东边星" : "向右看 · 续亮东星" }
        if move.dy < 0 { return round == 0 ? "向上看 · 寻北极星" : "向上看 · 续亮北星" }
        return round == 0 ? "向下看 · 寻山谷星" : "向下看 · 续亮南星"
    }

    static func caughtFeedback(_ move: EyeExerciseCoach.Move?, streak: Int) -> String {
        if streak >= 3 { return "连亮 \(streak) 颗星，目光真灵巧！" }
        guard let move else { return "星光已收入星盘" }
        if move.dx < 0 { return "收下西边的星光啦" }
        if move.dx > 0 { return "收下东边的星光啦" }
        if move.dy < 0 { return "摘下高空的北极星啦" }
        return "捡起山谷里的星光啦"
    }

    static func rankTitle(total: Int, seen: Int, usedCamera: Bool) -> String {
        if !usedCamera { return "星夜漫步者" }
        if total > 0 && seen >= total { return "观星大师" }
        if seen >= (total * 3) / 4 { return "星夜向导" }
        if seen >= total / 2 { return "追星旅人" }
        return "悠闲赏星人"
    }

    static func rankSummary(total: Int, seen: Int, bestStreak: Int, usedCamera: Bool) -> String {
        if !usedCamera { return "跟着星光活动了一整圈，眼睛轻松多了" }
        if total > 0 && seen >= total { return "8 颗星全部亲眼点亮，眼部活动非常充分" }
        if bestStreak >= 3 { return "最高连亮 \(bestStreak) 颗星，眼肌舒展得很顺畅" }
        return "眼睛已经跟着星光热身放松啦"
    }

    static func backdrop(_ ctx: GraphicsContext, size: CGSize, twinkle: CGFloat, resting: Bool) {
        let w = size.width, h = size.height
        let rect = CGRect(origin: .zero, size: size)
        ctx.fill(
            Path(rect),
            with: .linearGradient(
                Gradient(stops: [
                    .init(color: EyeStyle.background, location: 0),
                    .init(color: EyeStyle.background, location: 0.72),
                    .init(color: EyeStyle.panel.opacity(resting ? 0.45 : 0.72), location: 1),
                ]),
                startPoint: CGPoint(x: w * 0.5, y: 0),
                endPoint: CGPoint(x: w * 0.5, y: h)
            )
        )
        let glowCenter = CGPoint(x: w * 0.5, y: h * 0.48)
        let glowR = min(w, h) * 0.52
        let glowAlpha = resting ? 0.08 : 0.16
        ctx.fill(
            Path(ellipseIn: CGRect(x: glowCenter.x - glowR, y: glowCenter.y - glowR, width: glowR * 2, height: glowR * 2)),
            with: .radialGradient(
                Gradient(stops: [
                    .init(color: EyeStyle.glow.opacity(glowAlpha), location: 0),
                    .init(color: EyeStyle.panel.opacity(glowAlpha * 0.4), location: 0.65),
                    .init(color: .clear, location: 1),
                ]),
                center: glowCenter, startRadius: 0, endRadius: glowR
            )
        )
        let baseR = min(max(min(w, h) * 0.0042, 1.5), 3.6)
        for (idx, item) in bgStars.enumerated() {
            let phase = (Double(idx) * 0.37 + Double(twinkle)) * 2 * .pi
            let a = (resting ? 0.18 : 0.32) + 0.14 * ((sin(phase) + 1) * 0.5)
            let r = item.2 * baseR
            let pt = CGPoint(x: w * item.0, y: h * item.1)
            let color = (idx % 3 == 0 ? EyeStyle.moodGold : EyeStyle.glassEdgeTop).opacity(a)
            ctx.fill(Path(ellipseIn: CGRect(x: pt.x - r, y: pt.y - r, width: r * 2, height: r * 2)), with: .color(color))
        }
        var backHill = Path()
        backHill.move(to: CGPoint(x: 0, y: h))
        backHill.addLine(to: CGPoint(x: 0, y: h * 0.87))
        backHill.addQuadCurve(to: CGPoint(x: w * 0.56, y: h * 0.86), control: CGPoint(x: w * 0.28, y: h * 0.78))
        backHill.addQuadCurve(to: CGPoint(x: w, y: h * 0.83), control: CGPoint(x: w * 0.80, y: h * 0.92))
        backHill.addLine(to: CGPoint(x: w, y: h))
        backHill.closeSubpath()
        ctx.fill(backHill, with: .color(EyeStyle.panel.opacity(resting ? 0.42 : 0.65)))

        var frontHill = Path()
        frontHill.move(to: CGPoint(x: 0, y: h))
        frontHill.addLine(to: CGPoint(x: 0, y: h * 0.92))
        frontHill.addQuadCurve(to: CGPoint(x: w * 0.64, y: h * 0.93), control: CGPoint(x: w * 0.32, y: h * 0.86))
        frontHill.addQuadCurve(to: CGPoint(x: w, y: h * 0.90), control: CGPoint(x: w * 0.85, y: h * 0.97))
        frontHill.addLine(to: CGPoint(x: w, y: h))
        frontHill.closeSubpath()
        ctx.fill(frontHill, with: .color(EyeStyle.surfaceVariant.opacity(resting ? 0.35 : 0.52)))
    }

    static func constellationRing(
        _ ctx: GraphicsContext,
        center: CGPoint,
        radius: CGFloat,
        totalSteps: Int,
        stepResults: [Bool],
        usedCamera: Bool,
        calibrateProgress: CGFloat,
        showCalibrateRing: Bool,
        resting: Bool,
        restPulse: CGFloat
    ) {
        let count = max(1, totalSteps)
        let ringRect = CGRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2)
        ctx.stroke(
            Path(ellipseIn: ringRect),
            with: .color(EyeStyle.borderSubtle.opacity(resting ? 0.25 : 0.48)),
            style: StrokeStyle(lineWidth: max(1.5, radius * 0.022), dash: [radius * 0.08, radius * 0.07])
        )
        if showCalibrateRing {
            var arc = Path()
            arc.addArc(
                center: center, radius: radius,
                startAngle: .degrees(-90),
                endAngle: .degrees(-90 + 360 * Double(min(1, max(0, calibrateProgress)))),
                clockwise: false
            )
            ctx.stroke(arc, with: .color(EyeStyle.primary), style: StrokeStyle(lineWidth: max(3.5, radius * 0.055), lineCap: .round))
        }
        if resting {
            let rippleR = radius * (1 + restPulse * 0.42)
            ctx.stroke(
                Path(ellipseIn: CGRect(x: center.x - rippleR, y: center.y - rippleR, width: rippleR * 2, height: rippleR * 2)),
                with: .color(EyeStyle.moodGold.opacity(Double(max(0, 1 - restPulse)) * 0.38)),
                lineWidth: max(2, radius * 0.032)
            )
        }
        let positions: [CGPoint] = (0..<count).map { i in
            let angle = -.pi / 2 + (2 * .pi * Double(i) / Double(count))
            return CGPoint(x: center.x + radius * CGFloat(cos(angle)), y: center.y + radius * CGFloat(sin(angle)))
        }
        if stepResults.count > 1 {
            for i in 0..<(min(stepResults.count, count) - 1) {
                let litA = stepResults[i] || !usedCamera
                let litB = stepResults[i + 1] || !usedCamera
                if litA && litB {
                    ctx.stroke(
                        Cartoon.segment(positions[i], positions[i + 1]),
                        with: .color(EyeStyle.glassEdgeTop.opacity(resting ? 0.28 : 0.45)),
                        style: StrokeStyle(lineWidth: max(1.5, radius * 0.02), lineCap: .round)
                    )
                }
            }
        }
        for (idx, pt) in positions.enumerated() {
            let done = idx < stepResults.count
            let caught = done && stepResults[idx]
            let guidedLit = done && !usedCamera
            if caught {
                let hr = radius * 0.13
                ctx.fill(Path(ellipseIn: CGRect(x: pt.x - hr, y: pt.y - hr, width: hr * 2, height: hr * 2)),
                         with: .color(EyeStyle.primary.opacity(resting ? 0.14 : 0.28)))
                fourPointStar(ctx, center: pt, radius: radius * 0.105, color: EyeStyle.moodGold.opacity(resting ? 0.6 : 1))
            } else if guidedLit {
                let hr = radius * 0.11
                ctx.fill(Path(ellipseIn: CGRect(x: pt.x - hr, y: pt.y - hr, width: hr * 2, height: hr * 2)),
                         with: .color(EyeStyle.glassEdgeTop.opacity(resting ? 0.12 : 0.22)))
                fourPointStar(ctx, center: pt, radius: radius * 0.09, color: EyeStyle.moodWarm.opacity(resting ? 0.55 : 0.92))
            } else if done {
                let sr = radius * 0.055
                ctx.stroke(Path(ellipseIn: CGRect(x: pt.x - sr, y: pt.y - sr, width: sr * 2, height: sr * 2)),
                           with: .color(EyeStyle.borderSubtle.opacity(0.7)), lineWidth: max(1.5, radius * 0.02))
            } else {
                let dr = radius * 0.036
                ctx.fill(Path(ellipseIn: CGRect(x: pt.x - dr, y: pt.y - dr, width: dr * 2, height: dr * 2)),
                         with: .color(EyeStyle.borderSubtle.opacity(0.5)))
            }
        }
    }

    static func owl(
        _ ctx: GraphicsContext,
        center: CGPoint,
        size: CGFloat,
        gazeX: CGFloat = 0,
        gazeY: CGFloat = 0,
        sleeping: Bool = false,
        happy: Bool = false,
        excited: Bool = false
    ) {
        let r = size * 0.5
        ctx.fill(
            Path(ellipseIn: CGRect(x: center.x - r * 0.62, y: center.y + r * 0.64, width: r * 1.24, height: r * 0.22)),
            with: .color(EyeStyle.panel.opacity(0.85))
        )
        let wingSpread: CGFloat = happy ? 0.24 : (excited ? 0.12 : 0)
        for side: CGFloat in [-1, 1] {
            var wing = Path()
            wing.move(to: CGPoint(x: center.x + side * r * 0.62, y: center.y - r * 0.06))
            wing.addCurve(
                to: CGPoint(x: center.x + side * r * 0.52, y: center.y + r * 0.56),
                control1: CGPoint(x: center.x + side * r * (0.98 + wingSpread), y: center.y + r * (0.04 - wingSpread * 0.7)),
                control2: CGPoint(x: center.x + side * r * (0.94 + wingSpread * 0.7), y: center.y + r * 0.50)
            )
            wing.closeSubpath()
            ctx.fill(wing, with: .color(EyeStyle.surfaceVariant))
            ctx.stroke(wing, with: .color(EyeStyle.glassEdgeTop.opacity(0.55)), style: StrokeStyle(lineWidth: r * 0.045, lineJoin: .round))
        }
        let bodyRect = CGRect(x: center.x - r * 0.72, y: center.y - r * 0.68, width: r * 1.44, height: r * 1.40)
        ctx.fill(Path(ellipseIn: bodyRect), with: .color(EyeStyle.panel))
        ctx.stroke(Path(ellipseIn: bodyRect), with: .color(EyeStyle.glassEdgeTop.opacity(0.72)), lineWidth: r * 0.045)

        for side: CGFloat in [-1, 1] {
            var ear = Path()
            ear.move(to: CGPoint(x: center.x + side * r * 0.26, y: center.y - r * 0.56))
            ear.addLine(to: CGPoint(x: center.x + side * r * 0.62, y: center.y - r * 0.88))
            ear.addLine(to: CGPoint(x: center.x + side * r * 0.60, y: center.y - r * 0.34))
            ear.closeSubpath()
            ctx.fill(ear, with: .color(EyeStyle.surfaceVariant))
            ctx.stroke(ear, with: .color(EyeStyle.glassEdgeTop.opacity(0.72)), style: StrokeStyle(lineWidth: r * 0.04, lineJoin: .round))
        }
        ctx.fill(
            Path(ellipseIn: CGRect(x: center.x - r * 0.44, y: center.y + r * 0.04, width: r * 0.88, height: r * 0.60)),
            with: .color(EyeStyle.surfaceVariant.opacity(0.9))
        )
        fourPointStar(ctx, center: CGPoint(x: center.x, y: center.y - r * 0.44), radius: r * 0.09,
                      color: EyeStyle.moodGold.opacity(sleeping ? 0.45 : 0.9))

        let eyeR = r * 0.27
        let eyeY = center.y - r * 0.10
        for side: CGFloat in [-1, 1] {
            let eyeC = CGPoint(x: center.x + side * r * 0.30, y: eyeY)
            let outerR = eyeR * 1.18
            ctx.fill(Path(ellipseIn: CGRect(x: eyeC.x - outerR, y: eyeC.y - outerR, width: outerR * 2, height: outerR * 2)),
                     with: .color(EyeStyle.glassEdgeTop.opacity(0.28)))
            let eyeRect = CGRect(x: eyeC.x - eyeR, y: eyeC.y - eyeR, width: eyeR * 2, height: eyeR * 2)
            ctx.fill(Path(ellipseIn: eyeRect), with: .color(EyeStyle.background))
            ctx.stroke(Path(ellipseIn: eyeRect), with: .color(EyeStyle.glassEdgeTop.opacity(0.8)), lineWidth: r * 0.036)
            if sleeping {
                var closed = Path()
                closed.move(to: CGPoint(x: eyeC.x - eyeR * 0.56, y: eyeC.y + eyeR * 0.04))
                closed.addQuadCurve(to: CGPoint(x: eyeC.x + eyeR * 0.56, y: eyeC.y + eyeR * 0.04),
                                    control: CGPoint(x: eyeC.x, y: eyeC.y + eyeR * 0.46))
                ctx.stroke(closed, with: .color(EyeStyle.moodWarm), style: StrokeStyle(lineWidth: r * 0.055, lineCap: .round))
            } else if happy {
                var smile = Path()
                smile.move(to: CGPoint(x: eyeC.x - eyeR * 0.54, y: eyeC.y + eyeR * 0.12))
                smile.addQuadCurve(to: CGPoint(x: eyeC.x + eyeR * 0.54, y: eyeC.y + eyeR * 0.12),
                                   control: CGPoint(x: eyeC.x, y: eyeC.y - eyeR * 0.44))
                ctx.stroke(smile, with: .color(EyeStyle.moodGold), style: StrokeStyle(lineWidth: r * 0.06, lineCap: .round))
            } else {
                let scleraR = eyeR * 0.86
                ctx.fill(Path(ellipseIn: CGRect(x: eyeC.x - scleraR, y: eyeC.y - scleraR, width: scleraR * 2, height: scleraR * 2)),
                         with: .color(Color.white.opacity(0.94)))
                let shift = eyeR * 0.34
                let pupilC = CGPoint(x: eyeC.x + min(1, max(-1, gazeX)) * shift, y: eyeC.y + min(1, max(-1, gazeY)) * shift)
                let irisR = eyeR * (excited ? 0.58 : 0.52)
                ctx.fill(Path(ellipseIn: CGRect(x: pupilC.x - irisR, y: pupilC.y - irisR, width: irisR * 2, height: irisR * 2)),
                         with: .color(EyeStyle.primary))
                let pr = irisR * 0.62
                ctx.fill(Path(ellipseIn: CGRect(x: pupilC.x - pr, y: pupilC.y - pr, width: pr * 2, height: pr * 2)),
                         with: .color(EyeStyle.ink))
                let hl = irisR * 0.24
                ctx.fill(Path(ellipseIn: CGRect(x: pupilC.x - irisR * 0.22 - hl, y: pupilC.y - irisR * 0.22 - hl, width: hl * 2, height: hl * 2)),
                         with: .color(.white))
            }
        }
        var beak = Path()
        beak.move(to: CGPoint(x: center.x, y: center.y - r * 0.02))
        beak.addLine(to: CGPoint(x: center.x + r * 0.11, y: center.y + r * 0.10))
        beak.addLine(to: CGPoint(x: center.x, y: center.y + r * 0.22))
        beak.addLine(to: CGPoint(x: center.x - r * 0.11, y: center.y + r * 0.10))
        beak.closeSubpath()
        ctx.fill(beak, with: .color(EyeStyle.primary))

        if sleeping {
            crescentMoon(ctx, center: CGPoint(x: center.x + r * 0.94, y: center.y - r * 0.96), radius: r * 0.22)
        }
    }

    static func targetStar(
        _ ctx: GraphicsContext,
        origin: CGPoint,
        target: CGPoint,
        move: EyeExerciseCoach.Move,
        ringRadius: CGFloat,
        starRadius: CGFloat,
        holdProgress: CGFloat,
        pulse: CGFloat
    ) {
        let active = min(1, max(0, holdProgress))
        let dx = CGFloat(move.dx), dy = CGFloat(move.dy)
        let lineStart = CGPoint(x: origin.x + dx * (ringRadius + starRadius * 0.6), y: origin.y + dy * (ringRadius + starRadius * 0.6))
        let lineEnd = CGPoint(x: target.x - dx * starRadius * 1.65, y: target.y - dy * starRadius * 1.65)
        let dist = hypot(lineEnd.x - lineStart.x, lineEnd.y - lineStart.y)
        if dist > starRadius * 0.5 {
            let lineColor = (active > 0.25 ? EyeStyle.primary : EyeStyle.glassEdgeTop).opacity(Double(0.28 + active * 0.45))
            ctx.stroke(
                Cartoon.segment(lineStart, lineEnd),
                with: .color(lineColor),
                style: StrokeStyle(lineWidth: max(2, starRadius * 0.10), lineCap: .round, dash: [starRadius * 0.36, starRadius * 0.30])
            )
            let perpX = -dy, perpY = dx
            let back = starRadius * 0.34, side = starRadius * 0.24
            var arrow = Path()
            arrow.move(to: CGPoint(x: lineEnd.x - dx * back + perpX * side, y: lineEnd.y - dy * back + perpY * side))
            arrow.addLine(to: lineEnd)
            arrow.addLine(to: CGPoint(x: lineEnd.x - dx * back - perpX * side, y: lineEnd.y - dy * back - perpY * side))
            ctx.stroke(
                arrow,
                with: .color((active > 0.25 ? EyeStyle.moodGold : EyeStyle.glassEdgeTop).opacity(Double(0.55 + active * 0.4))),
                style: StrokeStyle(lineWidth: max(2.2, starRadius * 0.11), lineCap: .round, lineJoin: .round)
            )
        }
        let auraR = starRadius * (1.75 + pulse * 0.24 + active * 0.32)
        ctx.fill(
            Path(ellipseIn: CGRect(x: target.x - auraR, y: target.y - auraR, width: auraR * 2, height: auraR * 2)),
            with: .radialGradient(
                Gradient(stops: [
                    .init(color: EyeStyle.primary.opacity(Double(0.36 + active * 0.28)), location: 0),
                    .init(color: EyeStyle.moodGold.opacity(Double(0.14 + active * 0.14)), location: 0.55),
                    .init(color: .clear, location: 1),
                ]),
                center: target, startRadius: 0, endRadius: auraR
            )
        )
        let ringR = starRadius * 1.24
        ctx.stroke(
            Path(ellipseIn: CGRect(x: target.x - ringR, y: target.y - ringR, width: ringR * 2, height: ringR * 2)),
            with: .color(EyeStyle.glassEdgeTop.opacity(0.28)),
            lineWidth: max(2.5, starRadius * 0.12)
        )
        if active > 0.02 {
            var arc = Path()
            arc.addArc(center: target, radius: ringR, startAngle: .degrees(-90),
                       endAngle: .degrees(-90 + 360 * Double(active)), clockwise: false)
            ctx.stroke(arc, with: .color(EyeStyle.primary), style: StrokeStyle(lineWidth: max(3.5, starRadius * 0.16), lineCap: .round))
        }
        let scale = 1 + pulse * 0.07 + active * 0.14
        fourPointStar(ctx, center: target, radius: starRadius * scale, color: EyeStyle.moodGold)
        let coreR = starRadius * 0.24 * scale
        ctx.fill(Path(ellipseIn: CGRect(x: target.x - coreR, y: target.y - coreR, width: coreR * 2, height: coreR * 2)),
                 with: .color(Color.white.opacity(0.92)))
    }

    static func comet(_ ctx: GraphicsContext, from: CGPoint, to: CGPoint, progress: CGFloat, starRadius: CGFloat) {
        let t = min(1, max(0, progress))
        if t < 0.72 {
            let u = t / 0.72
            let eased = 1 - (1 - u) * (1 - u)
            let mid = CGPoint(x: (from.x + to.x) * 0.5, y: (from.y + to.y) * 0.5 - starRadius * 1.4)
            let inv = 1 - eased
            let pos = CGPoint(
                x: inv * inv * from.x + 2 * inv * eased * mid.x + eased * eased * to.x,
                y: inv * inv * from.y + 2 * inv * eased * mid.y + eased * eased * to.y
            )
            for i in 1...4 {
                let tu = max(0, eased - CGFloat(i) * 0.06)
                let ti = 1 - tu
                let tail = CGPoint(
                    x: ti * ti * from.x + 2 * ti * tu * mid.x + tu * tu * to.x,
                    y: ti * ti * from.y + 2 * ti * tu * mid.y + tu * tu * to.y
                )
                let tr = starRadius * (0.42 - CGFloat(i) * 0.07)
                ctx.fill(Path(ellipseIn: CGRect(x: tail.x - tr, y: tail.y - tr, width: tr * 2, height: tr * 2)),
                         with: .color(EyeStyle.primary.opacity(Double(max(0.08, 0.55 - CGFloat(i) * 0.11)))))
            }
            fourPointStar(ctx, center: pos, radius: starRadius * 0.95, color: EyeStyle.moodGold)
        } else {
            let burst = (t - 0.72) / 0.28
            let alpha = Double(max(0, 1 - burst))
            let spread = starRadius * (1.2 + burst * 2.4)
            for i in 0..<8 {
                let a = Double(i) * (.pi / 4) + Double(burst) * 0.35
                let pt = CGPoint(x: to.x + CGFloat(cos(a)) * spread, y: to.y + CGFloat(sin(a)) * spread)
                fourPointStar(ctx, center: pt, radius: starRadius * 0.36 * (1 - burst * 0.4),
                              color: (i % 2 == 0 ? EyeStyle.moodGold : EyeStyle.primary).opacity(alpha))
            }
        }
    }

    static func fourPointStar(_ ctx: GraphicsContext, center: CGPoint, radius: CGFloat, color: Color) {
        let inner = radius * 0.32
        var path = Path()
        path.move(to: CGPoint(x: center.x, y: center.y - radius))
        path.addQuadCurve(to: CGPoint(x: center.x + radius, y: center.y), control: CGPoint(x: center.x + inner * 0.25, y: center.y - inner * 0.25))
        path.addQuadCurve(to: CGPoint(x: center.x, y: center.y + radius), control: CGPoint(x: center.x + inner * 0.25, y: center.y + inner * 0.25))
        path.addQuadCurve(to: CGPoint(x: center.x - radius, y: center.y), control: CGPoint(x: center.x - inner * 0.25, y: center.y + inner * 0.25))
        path.addQuadCurve(to: CGPoint(x: center.x, y: center.y - radius), control: CGPoint(x: center.x - inner * 0.25, y: center.y - inner * 0.25))
        path.closeSubpath()
        ctx.fill(path, with: .color(color))
    }

    private static func crescentMoon(_ ctx: GraphicsContext, center: CGPoint, radius: CGFloat) {
        var moon = Path()
        moon.move(to: CGPoint(x: center.x + radius * 0.2, y: center.y - radius))
        moon.addCurve(
            to: CGPoint(x: center.x + radius * 0.2, y: center.y + radius),
            control1: CGPoint(x: center.x - radius * 0.95, y: center.y - radius * 0.75),
            control2: CGPoint(x: center.x - radius * 0.95, y: center.y + radius * 0.75)
        )
        moon.addCurve(
            to: CGPoint(x: center.x + radius * 0.2, y: center.y - radius),
            control1: CGPoint(x: center.x - radius * 0.32, y: center.y + radius * 0.55),
            control2: CGPoint(x: center.x - radius * 0.32, y: center.y - radius * 0.55)
        )
        moon.closeSubpath()
        ctx.fill(moon, with: .color(EyeStyle.moodGold.opacity(0.88)))
    }
}

/// 与安卓共用的护眼操完成页：点亮的八星星盘、欢呼小猫头鹰、趣味称号、客观统计与底部操作按钮。
struct ExerciseCompletionView: View {
    let total: Int
    let seen: Int
    let usedCamera: Bool
    var bestStreak: Int = 0
    var stepResults: [Bool] = []
    var onReplay: (() -> Void)? = nil
    let onClose: () -> Void

    private var displayResults: [Bool] {
        if !stepResults.isEmpty { return stepResults }
        return (0..<max(0, total)).map { idx in !usedCamera || idx < seen }
    }

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(spacing: 0) {
                    Spacer(minLength: 16)
                    Canvas { ctx, size in
                        let c = CGPoint(x: size.width / 2, y: size.height / 2)
                        let s = min(size.width, size.height)
                        StarOwlScene.constellationRing(
                            ctx, center: c, radius: s * 0.42, totalSteps: max(1, total),
                            stepResults: displayResults, usedCamera: usedCamera,
                            calibrateProgress: 1, showCalibrateRing: false, resting: false, restPulse: 0
                        )
                        StarOwlScene.owl(ctx, center: c, size: s * 0.48, happy: true)
                    }
                    .frame(width: 184, height: 184)
                    .accessibilityLabel("已完成点亮的八星星盘与欢呼的小猫头鹰")

                    Text(StarOwlScene.rankTitle(total: total, seen: seen, usedCamera: usedCamera))
                        .font(EyeStyle.chip)
                        .foregroundStyle(EyeStyle.moodGold)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 6)
                        .background(EyeStyle.primarySoft, in: EyeStyle.chipShape)
                        .overlay { EyeStyle.chipShape.strokeBorder(EyeStyle.glassEdgeTop.opacity(0.7), lineWidth: 1) }
                        .padding(.top, EyeSpacing.lg)

                    Text("完成啦！")
                        .font(EyeStyle.displayCompact)
                        .foregroundStyle(EyeStyle.text)
                        .padding(.top, EyeSpacing.sm)

                    Text(usedCamera ? "共 \(total) 步，检测到你做到了 \(seen) 步" : "共 \(total) 步，做完啦")
                        .font(EyeStyle.body)
                        .foregroundStyle(EyeStyle.text.opacity(0.88))
                        .multilineTextAlignment(.center)
                        .padding(.top, EyeSpacing.xs)

                    Text(StarOwlScene.rankSummary(total: total, seen: seen, bestStreak: bestStreak, usedCamera: usedCamera))
                        .font(EyeStyle.bodySecondary)
                        .foregroundStyle(EyeStyle.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.top, 6)

                    Spacer(minLength: 16)
                }
                .frame(maxWidth: .infinity)
            }
            VStack(spacing: EyeSpacing.xs) {
                Button("完成", action: onClose).buttonStyle(EyeButtonStyle())
                if let onReplay {
                    Button("再寻一次", action: onReplay).buttonStyle(EyeButtonStyle(role: .secondary))
                }
            }
            .frame(maxWidth: 520)
            .padding(.top, EyeSpacing.sm)
        }
        .frame(maxHeight: .infinity)
    }
}

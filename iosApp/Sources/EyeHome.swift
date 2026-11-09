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
    @ObservedObject private var store = EyeCareStore.shared
    @ObservedObject private var subjects = SubjectStore.shared
    @StateObject private var reminder = EyeReminder()
    @State private var exerciseOpen = false
    @State private var liveRequests = 0
    @State private var feelingEditing = false

    private let today: String = {
        let formatter = DateFormatter(); formatter.locale = Locale(identifier: "zh_CN"); formatter.dateFormat = "M月d日 EEEE"
        return formatter.string(from: Date())
    }()
    private var selfId: String? { subjects.subjects.first { $0.relation == "SELF" }?.id }

    var body: some View {
        let report = store.report
        ScrollViewReader { proxy in
            PageScroll {
                PageHeader(title: "今日", subtitle: today)
                HeroCard(report: report, openAccount: { navigate(.account) })
                TodayData(report: report)
                SectionTitle(text: "护眼工具")
                ToolRow(report: report, navigate: navigate) { exerciseOpen = true }
                EyechoCard(report: report, openChat: { navigate(.chat) }, checkIn: { store.checkIn($0) }) { feelingEditing = true }
                Panel { LiveEyeSection(startRequests: liveRequests) }.id("live")
                ReminderCard(reminder: reminder)
            }
        }
        .onAppear { store.refresh() }
        .onChange(of: reminder.enabled) { _ in store.refresh() }
        .fullScreenCover(isPresented: $exerciseOpen) { EyeExerciseView() }
        .overlay {
            if feelingEditing {
                let fine = report.todaySymptoms.isEmpty
                FeelingSheet(initial: report.checkedInToday ? (fine ? [noDiscomfort] : report.todaySymptoms) : [],
                             save: { picked in feelingEditing = false; store.checkIn(picked.filter { $0 != noDiscomfort }) },
                             dismiss: { feelingEditing = false })
                .transition(.opacity)
            }
        }
        .animation(.easeOut(duration: 0.2), value: feelingEditing)
    }
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
                ZStack(alignment: .topTrailing) {
                    Color.clear
                    Circle().fill(EyeStyle.violetSoft).frame(width: 136, height: 136).offset(x: 4, y: 18)
                    Image("EyechoHalf").resizable().scaledToFit().frame(width: 152, height: 213).offset(x: 14, y: 6)
                        .accessibilityLabel("爱科")
                }
                .frame(maxWidth: .infinity, minHeight: 180, maxHeight: 180)
                .clipShape(RoundedRectangle(cornerRadius: EyeStyle.radiusHero, style: .continuous))
            }.buttonStyle(PressScale(scale: 0.98))
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 6) {
                    EyeGlyphView(glyph: .chat, color: EyeStyle.violet).frame(width: 16, height: 16)
                    Text(eyechoCardLabel).font(EyeStyle.chip).foregroundStyle(EyeStyle.readableText(EyeStyle.violet))
                }
                Text(checked ? "今天的感受已记下" : "今天眼睛感觉怎么样？").font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text)
                FlowLayout(spacing: 8) {
                    if checked {
                        ForEach(symptoms.isEmpty ? [noDiscomfort] : symptoms, id: \.self) { label in
                            Text(label).font(EyeStyle.chip).foregroundStyle(EyeStyle.readableText(EyeStyle.primary))
                                .padding(.horizontal, 10).padding(.vertical, 6).background(EyeStyle.primarySoft, in: EyeStyle.chipShape)
                        }
                        QuickChip(label: "修改", action: edit)
                    } else {
                        ForEach(quick, id: \.self) { label in
                            QuickChip(label: label) { checkIn(label == noDiscomfort ? [] : [label]) }
                        }
                        QuickChip(label: "更多", action: edit)
                    }
                }.padding(.top, 4)
            }
            .padding(18).frame(width: 230, alignment: .leading)
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
private struct FeelingSheet: View {
    let save: ([String]) -> Void
    let dismiss: () -> Void
    @State private var selected: [String]
    init(initial: [String], save: @escaping ([String]) -> Void, dismiss: @escaping () -> Void) {
        self.save = save; self.dismiss = dismiss
        _selected = State(initialValue: initial)
    }
    var body: some View {
        ZStack {
            EyeStyle.ink.opacity(0.55).ignoresSafeArea().contentShape(Rectangle()).onTapGesture { dismiss() }
            VStack(alignment: .leading, spacing: 10) {
                Text("今天眼睛感觉怎么样？").font(EyeStyle.title).foregroundStyle(EyeStyle.text).padding(.bottom, 6)
                option(noDiscomfort)
                LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
                    ForEach(EyeCareBridge.shared.symptoms, id: \.self) { option($0) }
                }
                Button("保存") { EyeHaptics.success(); save(selected) }.buttonStyle(EyeButtonStyle())
                    .disabled(selected.isEmpty).padding(.top, 4)
            }
            .padding(24).frame(maxWidth: .infinity, alignment: .leading)
            .background(EyeStyle.panel, in: RoundedRectangle(cornerRadius: EyeStyle.radius, style: .continuous))
            .padding(24)
        }
        .accessibilityAddTraits(.isModal)
    }
    private func option(_ label: String) -> some View {
        let on = selected.contains(label)
        return Button { EyeHaptics.toggle(); toggle(label) } label: {
            Text(label).font(on ? EyeStyle.body.weight(.semibold) : EyeStyle.body)
                .foregroundStyle(on ? EyeStyle.primary : EyeStyle.text).multilineTextAlignment(.center)
                .lineLimit(1).minimumScaleFactor(0.8)
                .padding(.horizontal, 6).frame(maxWidth: .infinity, minHeight: 52)
                .background(on ? EyeStyle.primarySoft : EyeStyle.background, in: RoundedRectangle(cornerRadius: EyeStyle.radiusMedium))
                .overlay { RoundedRectangle(cornerRadius: EyeStyle.radiusMedium).stroke(on ? EyeStyle.primary : EyeStyle.background, lineWidth: 1.5) }
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
private struct ReminderCard: View {
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

/// 护眼工具：三张等高白卡（浅色块图标 + 名称 + 一项数据）。与安卓 ToolRow 一致。
private struct ToolRow: View {
    let report: CareReport
    let navigate: (AppRoute) -> Void
    let openExercise: () -> Void
    var body: some View {
        let brightness = report.signals("BASIC").first { $0.id == "brightness" }?.value
        HStack(spacing: EyeSpacing.cardGap) {
            ToolTile(title: "护眼操", meta: "约 1 分钟", glyph: .eye, soft: EyeStyle.successSoft, ink: EyeStyle.success, action: openExercise)
            ToolTile(title: "用眼趋势", meta: "近 7 天", glyph: .chart, soft: EyeStyle.screenSoft, ink: EyeStyle.screen) { navigate(.trend) }
            ToolTile(title: "亮度与蓝光", meta: brightness.flatMap { $0 == "—" ? nil : "亮度 \($0)" } ?? "护眼设置",
                     glyph: .sun, soft: EyeStyle.sunSoft, ink: EyeStyle.sun) { navigate(.brightness) }
        }.fixedSize(horizontal: false, vertical: true)
    }
}

private struct ToolTile: View {
    let title: String
    let meta: String
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
                    Text(title).font(EyeStyle.navigationTitle).foregroundStyle(EyeStyle.text).lineLimit(2).minimumScaleFactor(0.8)
                    Text(meta).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary).lineLimit(1)
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
        let report = store.report
        let blinks = report.days.compactMap { $0.blinksPerMinute }
        SubPage(title: "用眼趋势") {
            if !report.summary.isEmpty {
                Panel {
                    Text("本周要点").font(EyeStyle.chip).foregroundStyle(EyeStyle.secondary)
                    ForEach(Array(report.summary.enumerated()), id: \.offset) { _, line in Bullet(text: line, dot: EyeStyle.screen) }
                }
            }
            chart("眼健康指数", glyph: .eye, tint: EyeStyle.primary, caption: "今天",
                  value: report.score.map { "\(healthIndex($0)) 分" } ?? "—",
                  trailing: report.score == nil ? nil : (report.label, levelColor(report.level)), help: nil) {
                BarChartView(bars: report.days.enumerated().map { i, d -> BarItem in
                    let index = d.score.map(healthIndex)
                    return BarItem(id: i, label: d.label, value: index.map { Double($0) }, text: index.map { "\($0) 分" }, highlight: d.label == "今天",
                            warning: (index ?? 100) <= 40)
                }, maxValue: 100, tint: EyeStyle.primary)
            }
            chart("眨眼频率", glyph: .eye, tint: EyeStyle.iceInk, caption: "平均",
                  value: blinks.isEmpty ? "—" : String(format: "%.1f 次/分", blinks.reduce(0, +) / Double(blinks.count)),
                  trailing: ("参考 15 次/分", EyeStyle.secondary),
                  help: "平时每分钟约眨眼 15 次，专注看屏幕时会明显变少。每次实时检测都会记下眨眼频率。") {
                BarChartView(bars: report.days.enumerated().map { i, d in
                    BarItem(id: i, label: d.label, value: d.blinksPerMinute, text: d.blinksPerMinute.map { "\(Int($0.rounded())) 次/分" },
                            highlight: d.label == "今天", warning: (d.blinksPerMinute ?? 99) < 12)
                }, reference: 15, maxValue: 20, tint: EyeStyle.ice)
            }
            if !report.factors.isEmpty {
                ListGroup(title: "今天的主要原因（拉低眼健康指数）") {
                    ForEach(Array(report.factors.enumerated()), id: \.element.id) { i, f in
                        ListRow(title: f.title, subtitle: f.detail, value: "−\(f.points)", valueColor: EyeStyle.warning, divider: i < report.factors.count - 1)
                    }
                }
            }
            if report.checkInDays > 0 {
                ListGroup(title: "身体感受") {
                    ListRow(title: "打卡天数", glyph: .eye, value: "\(report.checkInDays) 天")
                    ListRow(title: "有不适的天数", glyph: .info, tone: .accent, value: "\(report.symptomDays) 天", divider: false)
                }
            }
        }
        .onAppear { store.refresh() }
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
                Text(factor.map { "\($0.title)：\($0.detail)" } ?? "屏幕亮度和周围环境比较协调。").font(EyeStyle.body)
                    .foregroundStyle(factor != nil ? EyeStyle.warning : EyeStyle.text)
            }
            Panel {
                HStack(spacing: 0) {
                    Text("这样做更舒服").font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text)
                    HelpButton(title: "关于蓝光", text: blueLightHelp)
                }
                Bullet(text: "屏幕亮度和周围环境接近：暗处调低，户外调高。")
                Bullet(text: "晚上打开夜间模式或护眼模式，让屏幕颜色偏暖。")
                Bullet(text: "睡前一小时尽量少看屏幕。")
                Bullet(text: "打开自动亮度，让手机跟着环境光调整。")
            }
        }
        .onAppear { brightness = Double(UIScreen.main.brightness) }
        .onDisappear { store.refresh() }
    }
}

// MARK: - 休息一下

/// 「现在休息 20 秒」：看远处，圆环倒计时（中间大号秒数），结束时有提示音与轻触觉。与安卓 RestBreakPage 同一流程。
struct RestBreakView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var started = Date()
    @State private var done = false
    private let seconds = 20.0
    var body: some View {
        NavigationStack {
            TimelineView(.periodic(from: started, by: 0.1)) { timeline in
                let elapsed = min(seconds, timeline.date.timeIntervalSince(started))
                VStack(spacing: 0) {
                    Spacer()
                    ZStack {
                        ProgressRing(fraction: elapsed / seconds, lineWidth: 10)
                        Text(done ? "完成" : "\(Int(ceil(seconds - elapsed)))").font(EyeStyle.display).foregroundStyle(EyeStyle.text).monospacedDigit()
                    }.frame(width: 240, height: 240)
                    Text(done ? "做得好！" : "秒").font(EyeStyle.title).foregroundStyle(EyeStyle.text).padding(.top, 24)
                    Text(done ? "眼睛放松一下就舒服多了，继续保持。" : "看向 6 米外的远处，慢慢眨几下眼，放松肩膀。")
                        .font(EyeStyle.body).foregroundStyle(EyeStyle.secondary).multilineTextAlignment(.center).padding(.horizontal, 32).padding(.top, 8)
                    Spacer()
                    Button(done ? "完成" : "提前结束") { EyeHaptics.tap(); dismiss() }
                        .buttonStyle(EyeButtonStyle(role: done ? .primary : .secondary)).padding(16)
                }
                .onChange(of: elapsed >= seconds) { finished in
                    if finished && !done { done = true; EyeCueSound.shared.play(.complete); EyeHaptics.success() }
                }
            }
            .background(EyeBackdrop())
            .navigationTitle("休息一下").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() } } }
        }
        .onAppear { started = Date() }
    }
}

// MARK: - 护眼操

/// 「护眼操」完整页面（与安卓 EyeExercisePage 同一流程与文案）：深色底，顶部进度条；上方大箭头 +「向左看」，
/// 注视点（准星）在屏幕正中，下方倒计时。眼睛转到位时提示朝那个方向翻过去、手机振动，注视点顺着滑出画面再回来；最后闭眼休息 5 秒，每秒轻响一下。
/// 判断与计时在共用 EyeExerciseCoach；没有相机权限时按时间带着做。
struct EyeExerciseView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var coach = EyeExerciseCoach()
    @State private var state: EyeExerciseCoach.State?
    @State private var flipMove: EyeExerciseCoach.Move?
    @State private var flyMove: EyeExerciseCoach.Move?
    @State private var fly: CGFloat = 0
    private let ticker = Timer.publish(every: 0.05, on: .main, in: .common).autoconnect()

    var body: some View {
        GeometryReader { geo in
            ZStack {
                EyeStyle.background.ignoresSafeArea()
                VStack(spacing: 0) {
                    HStack(spacing: 8) {
                        Button("结束") { dismiss() }.font(EyeStyle.body).foregroundStyle(.white).frame(minWidth: 44, minHeight: 44)
                        ZStack(alignment: .leading) {
                            EyeStyle.chipShape.fill(Color.white.opacity(0.25))
                            EyeStyle.chipShape.fill(EyeStyle.flowGradient).frame(width: max(0, (geo.size.width - 100) * CGFloat(state?.progress ?? 0)))
                        }.frame(height: 6)
                    }
                    // 上半区：动作提示；中间：注视点；下半区：倒计时与提示语。上下等高，注视点正好在屏幕中央
                    VStack(spacing: 20) {
                        Spacer(minLength: 0)
                        instruction.id(pageKey).transition(flipTransition).animation(.easeInOut(duration: 0.38), value: pageKey)
                    }.frame(maxHeight: .infinity)
                    if !isStage(.done) {
                        Canvas { ctx, size in
                            Cartoon.fixationTarget(ctx, center: CGPoint(x: size.width / 2, y: size.height / 2), size: min(size.width, size.height) * 0.62)
                        }
                        .frame(width: 150, height: 150)
                        .opacity(isStage(.rest) ? 0.35 : 1)
                        .offset(x: CGFloat(flyMove?.dx ?? 0) * fly * geo.size.width * 0.8,
                                y: CGFloat(flyMove?.dy ?? 0) * fly * geo.size.height * 0.45)
                    } else {
                        ProgressRing(fraction: 1, lineWidth: 8).frame(width: 170, height: 170)
                    }
                    VStack(spacing: 0) {
                        if !isStage(.done) {
                            Text(isStage(.move) || isStage(.rest) ? "\(state?.secondsLeft ?? 0)" : " ")
                                .font(.system(size: 88, weight: .bold, design: .rounded)).foregroundStyle(.white).padding(.top, 8)
                            Spacer(minLength: 0)
                            Text(state?.faceVisible == true || coach.usedCamera ? "头保持不动，只转眼睛" : "跟着提示转动眼睛就好")
                                .font(EyeStyle.bodySecondary).foregroundStyle(Color.white.opacity(0.7))
                        } else {
                            Text(coach.usedCamera ? "共 \(state?.stepCount ?? 0) 步，检测到你做到了 \(state?.detectedCount ?? 0) 步" : "共 \(state?.stepCount ?? 0) 步，做完啦")
                                .font(EyeStyle.body).foregroundStyle(.white).multilineTextAlignment(.center).padding(.top, 16)
                            Spacer(minLength: 0)
                            Button("完成") { dismiss() }.buttonStyle(EyeButtonStyle())
                        }
                    }.frame(maxHeight: .infinity)
                }
                .padding(.horizontal, 20).padding(.vertical, 12)
            }
            .onReceive(ticker) { _ in tick(size: geo.size) }
        }
        .onAppear {
            let model = coach
            EyeLiveModel.shared.startExercise { face, time in model.onFace(face: face, timeMs: time) }
        }
        .onDisappear { EyeLiveModel.shared.stopExercise() }
        // 护眼操固定深色页：令牌按深色取值
        .environment(\.colorScheme, .dark)
    }

    private var now: Int64 { Int64(ProcessInfo.processInfo.systemUptime * 1000) }
    private func isStage(_ stage: EyeExerciseCoach.Stage) -> Bool { state?.stage == stage }
    private var pageKey: String { "\(state?.stage.name ?? "")-\(state?.stepIndex ?? 0)" }

    private func tick(size: CGSize) {
        let t = now
        if let event = coach.tick(timeMs: t) { handle(event, move: coach.state(timeMs: t).move) }
        state = coach.state(timeMs: t)
    }

    private func handle(_ event: EyeExerciseCoach.Event, move: EyeExerciseCoach.Move?) {
        if event == .detected {
            flipMove = move; flyMove = move
            EyeHaptics.success(); EyeCueSound.shared.play(.phase)
            // 注视点顺着方向滑出去，再从另一边滑回来
            withAnimation(.easeIn(duration: 0.45)) { fly = 1 }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) {
                fly = -1
                withAnimation(.easeOut(duration: 0.45)) { fly = 0 }
            }
        } else if event == .missed {
            flipMove = nil; EyeHaptics.tap()
        } else if event == .rest {
            flipMove = nil; EyeHaptics.tap(); EyeCueSound.shared.play(.phase)
        } else if event == .countdown {
            // 闭着眼看不到数字：每过一秒轻响一下
            EyeCueSound.shared.play(.count)
        } else if event == .done {
            EyeHaptics.success(); EyeCueSound.shared.play(.complete)
        } else {
            flipMove = nil
        }
    }

    /// 看到了就整页朝那个方向翻走、新的一页从另一边进来；其余淡入淡出。
    private var flipTransition: AnyTransition {
        guard let move = flipMove else { return .opacity }
        let out: Edge = move.dx < 0 ? .leading : move.dx > 0 ? .trailing : move.dy < 0 ? .top : .bottom
        let into: Edge = out == .leading ? .trailing : out == .trailing ? .leading : out == .top ? .bottom : .top
        return .asymmetric(insertion: .move(edge: into).combined(with: .opacity), removal: .move(edge: out).combined(with: .opacity))
    }

    @ViewBuilder private var instruction: some View {
        VStack(spacing: 14) {
            if isStage(.calibrate) || state == nil {
                Text("看着中间的圆点").font(EyeStyle.headline).foregroundStyle(.white)
                Text("准备开始").font(EyeStyle.body).foregroundStyle(Color.white.opacity(0.7))
            } else if isStage(.move), let move = state?.move {
                Image(systemName: arrowName(move)).font(.system(size: 56, weight: .bold)).foregroundStyle(Color.white.opacity(0.6))
                    .accessibilityLabel(move.label)
                Text(move.label).font(EyeStyle.title).foregroundStyle(.white)
            } else if isStage(.back) {
                Text("看回中间").font(EyeStyle.title).foregroundStyle(.white)
            } else if isStage(.rest) {
                Text("闭上眼睛休息").font(EyeStyle.title).foregroundStyle(.white)
            } else {
                Text("完成啦！").font(EyeStyle.headline).foregroundStyle(.white)
            }
        }.frame(maxWidth: .infinity)
    }

    private func arrowName(_ move: EyeExerciseCoach.Move) -> String {
        move.dx < 0 ? "arrow.left" : move.dx > 0 ? "arrow.right" : move.dy < 0 ? "arrow.up" : "arrow.down"
    }
}

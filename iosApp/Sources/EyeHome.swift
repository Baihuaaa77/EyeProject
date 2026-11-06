import SwiftUI
import EyeScreening

// 首页与它的二级页面（用眼趋势报告、健康建议、亮度与蓝光、休息一下）。与安卓 feature:home 同一顺序、排版与文案。

private let noDiscomfort = "没有不适"

/// 等级配色：轻松绿、留意琥珀、偏累红、待感知灰。
private func levelColor(_ level: String) -> Color {
    switch level { case "LOW": return EyeStyle.success; case "MEDIUM": return EyeStyle.warning; case "HIGH": return EyeStyle.danger; default: return EyeStyle.secondary }
}

/// 首页：「今日」与日期（右上角账户、系统设置）→ 眼健康指数仪表圈 → 基础数据 → 功能卡（护眼操、用眼趋势、亮度与蓝光三个彩色方块
/// + 整行 AI 主动问诊）→ 今日感受 → 休息提醒 → 实时检测（始终展开）。不分小节标题。与安卓 HomeContent 一致。
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
                PageHeader(title: "今日", subtitle: today, openAccount: { navigate(.account) }, openSettings: { navigate(.settings) })
                HeroCard(report: report)
                if report.signals("BASIC").contains(where: { $0.id != "brightness" }) { BasicCard(report: report) }
                FeatureCard(report: report, navigate: navigate) { exerciseOpen = true }
                CheckInCard(report: report) { feelingEditing = true }
                ReminderCard(reminder: reminder, suggested: report.loaded ? report.suggestedReminderMinutes : nil)
                Panel { LiveEyeSection(startRequests: liveRequests) }.id("live")
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

// MARK: - 今日用眼

/// 今日用眼：不加卡片框，页面正中一只大号光流仪表圈包住眼健康指数，是首页的视觉中心；
/// 眼健康指数 = 100 − 用眼负荷分，圈越满越健康。圈内自上而下居中：「眼健康指数」、大号分数、等级徽标。与安卓 EyeCareHero 一致。
private struct HeroCard: View {
    let report: CareReport
    var body: some View {
        let index = report.score.map(healthIndex)
        ZStack {
            ProgressRing(fraction: index.map { Double($0) / 100 }, lineWidth: 16)
            VStack(spacing: EyeSpacing.xs) {
                // 自上而下居中：「眼健康指数」→ 大号分数 → 等级徽标
                Text("眼健康指数").font(EyeStyle.subtitle).foregroundStyle(EyeStyle.secondary)
                (Text(index.map { "\($0)" } ?? "—").font(EyeStyle.display).foregroundColor(EyeStyle.text)
                 + Text("/100").font(EyeStyle.title).foregroundColor(EyeStyle.tertiary))
                    .monospacedDigit().lineLimit(1)
                    .accessibilityLabel(index.map { "今日眼健康指数 \($0) 分" } ?? "今日眼健康指数暂无数据")
                let chip = EyeStyle.statusFill(levelColor(report.level))
                Text(report.label).font(EyeStyle.body.weight(.semibold)).foregroundStyle(chip.1)
                    .padding(.horizontal, 14).padding(.vertical, 4).background(chip.0, in: Capsule())
            }
        }
        .frame(width: 296, height: 296)
        .frame(maxWidth: .infinity)
        .padding(.vertical, EyeSpacing.xs)
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

/// 今日感受：卡片只显示已记下的感受（标签）；点「记录」/「修改」弹出选择框（FeelingSheet）。与安卓 CheckInCard 一致。
private struct CheckInCard: View {
    let report: CareReport
    let edit: () -> Void
    var body: some View {
        Panel {
            let checked = report.checkedInToday
            let symptoms = report.todaySymptoms
            CategoryHeader(title: "今日感受", glyph: .heart, tone: .primary) {
                PillButton(label: checked ? "修改" : "记录", primary: !checked, action: edit)
            }
            if checked {
                FlowLayout(spacing: 8) {
                    ForEach(symptoms.isEmpty ? [noDiscomfort] : symptoms, id: \.self) { label in
                        let attention = !symptoms.isEmpty
                        Text(label).font(EyeStyle.body.weight(.semibold))
                            .foregroundStyle(EyeStyle.primary)
                            .padding(.horizontal, 12).padding(.vertical, 6)
                            .background(EyeStyle.primarySoft, in: Capsule())
                    }
                }
            }
        }
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
            .background(EyeStyle.panel, in: RoundedRectangle(cornerRadius: 22))
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

/// 基础数据（本机系统提供的项）。
private struct BasicCard: View {
    let report: CareReport
    var body: some View {
        Panel {
            CardHeader(title: "基础数据", glyph: .hourglass, tone: .blue)
            SignalGrid(signals: report.signals("BASIC"), columns: 2)  // 与安卓一致：读数大字两列
        }
    }
}

// MARK: - 功能卡与休息提醒

/// 休息提醒：开关（间隔）+ 间隔选择；建议间隔与当前不同时给出一键采用。
private struct ReminderCard: View {
    @ObservedObject var reminder: EyeReminder
    let suggested: Int?
    var body: some View {
        Panel {
            HStack(spacing: 10) {
                VStack(alignment: .leading, spacing: 6) {
                    CategoryHeader(title: "休息提醒", glyph: .bell, tone: .accent)
                    Text(reminder.enabled ? "每 \(reminder.intervalMinutes) 分钟提醒一次" : "按间隔提醒你远眺放松")
                        .font(EyeStyle.body).foregroundStyle(EyeStyle.text)
                }.frame(maxWidth: .infinity, alignment: .leading)
                Toggle("", isOn: Binding(get: { reminder.enabled }, set: { EyeHaptics.toggle(); reminder.setEnabled($0) }))
                    .labelsHidden().tint(EyeStyle.primary)
            }.frame(minHeight: 48)
            SegmentedPicker(options: [(20, "20分钟"), (30, "30分钟"), (60, "60分钟")], selected: reminder.intervalMinutes) { reminder.setInterval($0) }
            if let suggested, suggested != reminder.intervalMinutes {
                HStack {
                    Text("建议每 \(suggested) 分钟休息一次").font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
                    Spacer()
                    PillButton(label: "采用") { reminder.setInterval(suggested); reminder.setEnabled(true) }
                }
            }
            if let notice = reminder.notice { Text(notice).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.warning) }
        }
    }
}

/// 功能卡：一张卡里上排三个彩色方块（护眼操绿、用眼趋势蓝、亮度与蓝光珊瑚红），下面整行紫色的 AI 主动问诊。
/// 色块上文字与图标一律白色，靠整块颜色做视觉区分。与安卓 FeatureCard 一致。
private struct FeatureCard: View {
    let report: CareReport
    let navigate: (AppRoute) -> Void
    let openExercise: () -> Void
    var body: some View {
        VStack(spacing: 10) {
            HStack(spacing: 10) {
                VividTile(title: "护眼操", subtitle: "约 1 分钟", glyph: .eye, fill: EyeStyle.Vivid.green, action: openExercise)
                VividTile(title: "用眼趋势", subtitle: "近 7 天变化", glyph: .chart, fill: EyeStyle.Vivid.blue) { navigate(.trend) }
                VividTile(title: "亮度与蓝光", subtitle: report.signal("brightness").map { "屏幕亮度 \($0.value)" } ?? "夜间模式与护眼",
                          glyph: .sun, fill: EyeStyle.Vivid.red) { navigate(.brightness) }
            }.fixedSize(horizontal: false, vertical: true)
            ChatEntryCard { navigate(.chat) }
        }
        .padding(12).frame(maxWidth: .infinity)
        .eyeGlass()
    }
}

/// 功能色块：渐变底 + 左上白色图标块 + 白色标题与一行说明，右下角放大的半透明图标做装饰；按下轻微缩小。与安卓 VividTile 一致。
private struct VividTile: View {
    let title: String
    let subtitle: String
    let glyph: EyeGlyph
    let fill: LinearGradient
    let action: () -> Void
    var body: some View {
        Button { EyeHaptics.tap(); action() } label: {
            VStack(alignment: .leading, spacing: 0) {
                EyeGlyphView(glyph: glyph, color: EyeStyle.Vivid.on).frame(width: 28, height: 28)
                    .frame(width: 42, height: 42)
                    .background(EyeStyle.Vivid.on.opacity(0.22), in: RoundedRectangle(cornerRadius: EyeStyle.radiusMedium, style: .continuous))
                Spacer(minLength: 14)
                Text(title).font(EyeStyle.button).foregroundStyle(EyeStyle.Vivid.on)
                Text(subtitle).font(EyeStyle.chip).foregroundStyle(EyeStyle.Vivid.on.opacity(0.86))
                    .lineLimit(2).multilineTextAlignment(.leading).padding(.top, 2)
            }
            .padding(12).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .frame(minHeight: 140)
            .background(alignment: .bottomTrailing) {
                EyeGlyphView(glyph: glyph, color: EyeStyle.Vivid.on.opacity(0.16)).frame(width: 76, height: 76).offset(x: 16, y: 16)
            }
            .background(fill)
            .clipShape(RoundedRectangle(cornerRadius: EyeStyle.radiusLarge, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: EyeStyle.radiusLarge))
        }.buttonStyle(PressScale(scale: 0.96))
    }
}

// MARK: - 用眼趋势报告

/// 眼健康指数 = 100 − 用眼负荷分（规则仍按负荷算，界面只展示指数，越高越好）。与安卓 healthIndex 一致。
func healthIndex(_ loadScore: Int) -> Int { min(max(100 - loadScore, 0), 100) }

/// 近 7 天眼健康指数、眨眼频率的趋势与小结，以及今天的主要原因。
struct TrendPage: View {
    @ObservedObject private var store = EyeCareStore.shared
    var body: some View {
        let report = store.report
        let scores = report.days.compactMap { $0.score.map(healthIndex) }
        SubPage(title: "用眼趋势报告") {
            EyeHeroCard {
                VStack(alignment: .leading, spacing: 4) {
                    Text("近 7 天平均眼健康指数").font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
                    Text(scores.isEmpty ? "—" : "\(scores.reduce(0, +) / scores.count) 分").font(EyeStyle.headline).foregroundStyle(EyeStyle.text)
                }
                ForEach(Array(report.summary.enumerated()), id: \.offset) { _, line in Bullet(text: line) }
            }
            chart("眼健康指数", unit: "分", help: nil) {
                BarChartView(bars: report.days.enumerated().map { i, d -> BarItem in
                    let index = d.score.map(healthIndex)
                    return BarItem(id: i, label: d.label, value: index.map { Double($0) }, text: index.map { "\($0)" }, highlight: d.label == "今天",
                            warning: (index ?? 100) <= 40)
                }, maxValue: 100)
            }
            chart("眨眼频率", unit: "次/分", help: "平时每分钟约眨眼 15 次，专注看屏幕时会明显变少。每次实时检测都会记下眨眼频率。") {
                BarChartView(bars: report.days.enumerated().map { i, d in
                    BarItem(id: i, label: d.label, value: d.blinksPerMinute, text: d.blinksPerMinute.map { "\(Int($0.rounded()))" },
                            highlight: d.label == "今天", warning: (d.blinksPerMinute ?? 99) < 12)
                }, reference: 15, maxValue: 20)
            }
            if !report.factors.isEmpty {
                ListGroup(title: "今天的主要原因") {
                    ForEach(Array(report.factors.enumerated()), id: \.element.id) { i, f in
                        ListRow(title: f.title, subtitle: f.detail, value: "+\(f.points)", valueColor: EyeStyle.warning, divider: i < report.factors.count - 1)
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

    private func chart<Content: View>(_ title: String, unit: String, help: String?, @ViewBuilder _ content: () -> Content) -> some View {
        Panel {
            HStack(spacing: 0) {
                Text(title).font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text)
                if let help { HelpButton(title: title, text: help) }
                Spacer()
                Text(unit).font(EyeStyle.chip).foregroundStyle(EyeStyle.tertiary)
            }
            content()
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
                    .padding(.horizontal, 10).padding(.vertical, 5).background(soft, in: Capsule())
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
                            Capsule().fill(Color.white.opacity(0.25))
                            Capsule().fill(EyeStyle.flowGradient).frame(width: max(0, (geo.size.width - 100) * CGFloat(state?.progress ?? 0)))
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

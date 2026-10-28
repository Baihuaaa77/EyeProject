import SwiftUI
import EyeScreening

// 首页与它的二级页面（用眼趋势报告、健康建议、亮度与蓝光、休息一下）。与安卓 feature:home 同一顺序、排版与文案。

private let loopHelp = "小瞳按「感知 → 评估 → 归因 → 干预 → 反馈」帮你管理用眼：汇总眨眼、距离、屏幕亮度和你的档案，评估今天的用眼负荷（0–100，越高越累），找出主要原因，给出马上能做的建议，再根据你每天的感受调整。结果仅供参考，不是诊断。"
private let perceptionHelp = "视觉行为来自实时检测（画面只在本机处理，不保存）；个体档案来自「我的 → 家庭成员」里本人的资料。这些数据都只保存在本机。"
private let noDiscomfort = "没有不适"

/// 等级配色：轻松绿、留意琥珀、偏累红、待感知灰；圆环用更明亮的一组。
private func levelColor(_ level: String) -> Color {
    switch level { case "LOW": return EyeStyle.success; case "MEDIUM": return EyeStyle.warning; case "HIGH": return EyeStyle.danger; default: return EyeStyle.secondary }
}
private func ringColor(_ level: String) -> Color {
    switch level { case "LOW": return EyeStyle.success; case "MEDIUM": return EyeStyle.accent; case "HIGH": return EyeStyle.danger; default: return EyeStyle.borderSubtle }
}

/// 首页：问候与日期（右上角账户、系统设置）→ 今日用眼 → 今日感受 → 为你推荐 → 多源感知 → 主动服务。
struct HomePage: View {
    let navigate: (AppRoute) -> Void
    let openTest: (String) -> Void
    @ObservedObject private var store = EyeCareStore.shared
    @ObservedObject private var subjects = SubjectStore.shared
    @StateObject private var reminder = EyeReminder()
    @State private var breakOpen = false
    @State private var exerciseOpen = false
    @State private var liveRequests = 0

    private var greeting: String {
        switch Calendar.current.component(.hour, from: Date()) {
        case 5...10: return "早上好"
        case 11...12: return "中午好"
        case 13...17: return "下午好"
        default: return "晚上好"
        }
    }
    private let today: String = {
        let formatter = DateFormatter(); formatter.locale = Locale(identifier: "zh_CN"); formatter.dateFormat = "M月d日 EEEE"
        return formatter.string(from: Date())
    }()
    private var selfId: String? { subjects.subjects.first { $0.relation == "SELF" }?.id }

    var body: some View {
        let report = store.report
        ScrollViewReader { proxy in
            PageScroll {
                PageHeader(title: greeting, subtitle: today, openAccount: { navigate(.account) }, openSettings: { navigate(.settings) })
                HeroCard(report: report) { tip in
                    switch tip.kind {
                    case "BREAK": breakOpen = true
                    case "EXERCISE": exerciseOpen = true
                    case "REMINDER": reminder.setInterval(tip.value ?? 20); reminder.setEnabled(true)
                    case "BRIGHTNESS", "NIGHT": navigate(.brightness)
                    case "TEST": if let target = tip.target { openTest(target) }
                    case "PROFILE": navigate(.subjectEdit(selfId))
                    case "LIVE":
                        liveRequests += 1
                        withAnimation(.easeOut(duration: 0.3)) { proxy.scrollTo("behavior", anchor: .top) }
                    default: break
                    }
                }
                CheckInCard(report: report) { store.checkIn($0) }
                SectionTitle(title: "多源感知", help: perceptionHelp)
                if report.signals("BASIC").contains(where: { $0.id != "brightness" }) { BasicCard(report: report) }
                BehaviorCard(report: report, liveRequests: liveRequests).id("behavior")
                SectionTitle(title: "主动服务", help: nil)
                ExerciseCard { exerciseOpen = true }
                ReminderCard(reminder: reminder, suggested: report.loaded ? report.suggestedReminderMinutes : nil)
                ServiceTiles(report: report, navigate: navigate)
            }
        }
        .onAppear { store.refresh() }
        .onChange(of: reminder.enabled) { _ in store.refresh() }
        .fullScreenCover(isPresented: $breakOpen) { RestBreakView() }
        .fullScreenCover(isPresented: $exerciseOpen) { EyeExerciseView() }
    }
}

private struct SectionTitle: View {
    let title: String
    let help: String?
    var body: some View {
        HStack(spacing: 0) {
            Text(title).font(EyeStyle.title).foregroundStyle(EyeStyle.text)
            if let help { HelpButton(title: title, text: help) }
        }.padding(.leading, 4).padding(.top, 6)
    }
}

// MARK: - 今日用眼

/// 今日用眼：负荷分圆环 + 会说话的小瞳（随机弹出今天的提醒，可执行的点气泡直接去做；负荷越高嫩芽越蔫）+ 一句话 + 闭环五步进度。
private struct HeroCard: View {
    let report: CareReport
    let onTip: (CareReport.Tip) -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 0) {
                Text("今日用眼").font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text)
                HelpButton(title: "主动护眼", text: loopHelp)
                Spacer()
                StatusChip(text: report.label, color: levelColor(report.level), filled: true)
            }
            ZStack(alignment: .bottomLeading) {
                MascotTalk(mood: MascotMood(code: report.mood), lines: report.tips.map { MascotLine(text: $0.text, action: $0.action) },
                           headScale: 0.8, wilt: report.score.map { CGFloat(min(1, max(0, Double($0 - 30) / 50))) },
                           bubbleHeight: 84, tail: .end, mascotFraction: 0.5) { index in
                    if report.tips.indices.contains(index) { onTip(report.tips[index]) }
                }
                ScoreRing(score: report.score, color: ringColor(report.level)).frame(width: 124, height: 124).padding(.bottom, 6)
            }.frame(height: 220)
            Text(report.headline).font(EyeStyle.body).foregroundStyle(EyeStyle.text).fixedSize(horizontal: false, vertical: true)
            LoopStrip(stages: report.loop)
        }
        .padding(.horizontal, 20).padding(.vertical, 18)
        .background(LinearGradient(colors: [EyeStyle.primarySoft, EyeStyle.panel, levelColor(report.level).opacity(0.12)],
                                   startPoint: .topLeading, endPoint: .bottomTrailing), in: RoundedRectangle(cornerRadius: 22))
    }
}

/// 负荷分圆环：灰色底环 + 等级色进度弧（出现时转动到位），中间大号分数。
private struct ScoreRing: View {
    let score: Int?
    let color: Color
    @State private var shown: CGFloat = 0
    var body: some View {
        let fraction = CGFloat(score ?? 0) / 100
        ZStack {
            Circle().stroke(EyeStyle.surfaceVariant, lineWidth: 12)
            if score != nil {
                Circle().trim(from: 0, to: max(0.02, min(1, shown)))
                    .stroke(color, style: StrokeStyle(lineWidth: 12, lineCap: .round)).rotationEffect(.degrees(-90))
            }
            VStack(spacing: 0) {
                Text(score.map { "\($0)" } ?? "—").font(EyeStyle.headline).foregroundStyle(EyeStyle.text).monospacedDigit()
                Text("用眼负荷").font(EyeStyle.chip).foregroundStyle(EyeStyle.secondary)
            }
        }
        .padding(6)
        .onAppear { withAnimation(.easeOut(duration: 0.7)) { shown = fraction } }
        .onChange(of: score) { _ in withAnimation(.easeOut(duration: 0.7)) { shown = fraction } }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(score.map { "今日用眼负荷 \($0) 分" } ?? "今日用眼负荷暂无数据")
    }
}

/// 闭环五步：完成的一步实心主色带勾，未完成空心；之间连线。
private struct LoopStrip: View {
    let stages: [CareReport.Stage]
    var body: some View {
        let items = stages.isEmpty ? ["感知", "评估", "归因", "干预", "反馈"].map { CareReport.Stage(id: $0, label: $0, done: false, summary: "") } : stages
        HStack(alignment: .top, spacing: 0) {
            ForEach(Array(items.enumerated()), id: \.offset) { i, stage in
                VStack(spacing: 4) {
                    ZStack {
                        Circle().fill(stage.done ? EyeStyle.primary : EyeStyle.panel)
                        Circle().stroke(stage.done ? EyeStyle.primary : EyeStyle.borderSubtle, lineWidth: 1.5)
                        if stage.done {
                            Image(systemName: "checkmark").font(.system(size: 11, weight: .bold)).foregroundStyle(EyeStyle.panel)
                        } else {
                            Circle().fill(EyeStyle.borderSubtle).frame(width: 6, height: 6)
                        }
                    }.frame(width: 26, height: 26)
                    Text(stage.label).font(EyeStyle.chip).foregroundStyle(stage.done ? EyeStyle.primary : EyeStyle.secondary)
                }
                .frame(width: 44)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("\(stage.label)：\(stage.done ? "已完成" : "待完成")，\(stage.summary)")
                if i < items.count - 1 {
                    Capsule().fill(stage.done && items[i + 1].done ? EyeStyle.primary : EyeStyle.borderSubtle)
                        .frame(height: 2).frame(maxWidth: .infinity).padding(.top, 12)
                }
            }
        }.padding(.top, 4)
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

/// 今日感受打卡：与「视觉行为」同样的卡片标题；没打卡时展开选项，打卡后收成一行，可修改。
private struct CheckInCard: View {
    let report: CareReport
    let onCheckIn: ([String]) -> Void
    @State private var editing = false
    @State private var selected: [String] = []
    var body: some View {
        Panel {
            let checked = report.checkedInToday && !editing
            let fine = report.todaySymptoms.isEmpty
            HStack(spacing: 10) {
                IconTile(glyph: .heart, tone: report.checkedInToday && !fine ? .accent : .success, size: 32)
                Text("今日感受").font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text).frame(maxWidth: .infinity, alignment: .leading)
                if checked {
                    PillButton(label: "修改", primary: false) {
                        selected = fine ? [noDiscomfort] : report.todaySymptoms
                        editing = true
                    }
                }
            }
            if checked {
                Text(fine ? noDiscomfort : report.todaySymptoms.joined(separator: "、"))
                    .font(EyeStyle.subtitle).foregroundStyle(fine ? EyeStyle.text : EyeStyle.warning)
            } else {
                Text("今天眼睛感觉怎么样？").font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
                FlowLayout(spacing: 8) {
                    ForEach([noDiscomfort] + EyeCareBridge.shared.symptoms, id: \.self) { label in
                        ChoiceChip(label: label, selected: selected.contains(label)) { toggle(label) }
                    }
                }
                Button("记录今天的感受") {
                    EyeHaptics.success()
                    onCheckIn(selected.filter { $0 != noDiscomfort })
                    editing = false
                }.buttonStyle(EyeButtonStyle()).disabled(selected.isEmpty)
            }
        }
        .animation(.easeOut(duration: 0.25), value: editing)
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

// MARK: - 多源感知

/// 一格读数：名称、数值（需要留意时琥珀色并带小圆点）。
struct SignalCell: View {
    let signal: CareReport.Signal?
    var body: some View {
        let attention = signal?.status == "ATTENTION"
        let unavailable = signal == nil || signal?.status == "UNAVAILABLE"
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 4) {
                Text(signal?.label ?? "").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary).lineLimit(1)
                if attention { Circle().fill(EyeStyle.accent).frame(width: 6, height: 6) }
            }
            Text(signal?.value ?? "—").font(EyeStyle.subtitle).lineLimit(1).minimumScaleFactor(0.7)
                .foregroundStyle(unavailable ? EyeStyle.secondary : attention ? EyeStyle.warning : EyeStyle.text)
        }
        .padding(.horizontal, 12).padding(.vertical, 10).frame(maxWidth: .infinity, alignment: .leading)
        .background(EyeStyle.background, in: RoundedRectangle(cornerRadius: EyeStyle.radiusMedium))
    }
}

struct SignalGrid: View {
    let signals: [CareReport.Signal]
    let columns: Int
    var body: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: columns), spacing: 8) {
            ForEach(signals) { SignalCell(signal: $0) }
        }
    }
}

private struct CardHeader: View {
    let title: String
    let glyph: EyeGlyph
    let tone: TileTone
    var trailing: String? = nil
    var body: some View {
        HStack(spacing: 10) {
            IconTile(glyph: glyph, tone: tone, size: 32)
            Text(title).font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text).frame(maxWidth: .infinity, alignment: .leading)
            if let trailing { Text(trailing).font(EyeStyle.chip).foregroundStyle(EyeStyle.secondary) }
        }
    }
}

/// 基础数据（本机系统提供的项）。
private struct BasicCard: View {
    let report: CareReport
    var body: some View {
        Panel {
            CardHeader(title: "基础数据", glyph: .hourglass, tone: .blue)
            SignalGrid(signals: report.signals("BASIC"), columns: 3)
        }
    }
}

/// 视觉行为：眨眼频率、观看距离、头部姿态 + 实时检测（默认关闭）。
private struct BehaviorCard: View {
    let report: CareReport
    let liveRequests: Int
    var body: some View {
        let signals = report.signals("BEHAVIOR")
        Panel {
            CardHeader(title: "视觉行为", glyph: .eye, tone: .primary, trailing: signals.first?.note)
            SignalGrid(signals: signals, columns: 3)
            Rectangle().fill(EyeStyle.borderSubtle).frame(height: 1)
            LiveEyeSection(startRequests: liveRequests)
        }
    }
}

// MARK: - 主动服务

/// 护眼操入口：跟小瞳转转眼球（向左、右、上、下看），约 1 分钟。
private struct ExerciseCard: View {
    let start: () -> Void
    var body: some View {
        Button { EyeHaptics.tap(); start() } label: {
            HStack(spacing: 10) {
                MascotStage(mood: .happy, interactive: false, headScale: 0.78).frame(width: 56, height: 56)
                VStack(alignment: .leading, spacing: 2) {
                    Text("护眼操").font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text)
                    Text("跟小瞳转转眼球 · 约 1 分钟").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                }.frame(maxWidth: .infinity, alignment: .leading)
                PillButton(label: "开始", action: start)
            }
            .padding(.horizontal, 18).padding(.vertical, 14)
            .background(EyeStyle.panel, in: RoundedRectangle(cornerRadius: 22))
        }.buttonStyle(.plain)
    }
}

/// 休息提醒：开关（间隔）+ 间隔选择；建议间隔与当前不同时给出一键采用。
private struct ReminderCard: View {
    @ObservedObject var reminder: EyeReminder
    let suggested: Int?
    var body: some View {
        Panel {
            HStack(spacing: 10) {
                IconTile(glyph: .bell, tone: .accent, size: 32)
                VStack(alignment: .leading, spacing: 2) {
                    Text("休息提醒").font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text)
                    Text(reminder.enabled ? "每 \(reminder.intervalMinutes) 分钟提醒一次" : "按间隔提醒你远眺放松")
                        .font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                }.frame(maxWidth: .infinity, alignment: .leading)
                Toggle("", isOn: Binding(get: { reminder.enabled }, set: { EyeHaptics.toggle(); reminder.setEnabled($0) }))
                    .labelsHidden().tint(EyeStyle.primary)
            }.frame(minHeight: 48)
            SegmentedPicker(options: [(20, "20分钟"), (30, "30分钟"), (60, "60分钟")], selected: reminder.intervalMinutes) { reminder.setInterval($0) }
            if let suggested, suggested != reminder.intervalMinutes {
                HStack {
                    Text("小瞳建议每 \(suggested) 分钟休息一次").font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
                    Spacer()
                    PillButton(label: "采用") { reminder.setInterval(suggested); reminder.setEnabled(true) }
                }
            }
            if let notice = reminder.notice { Text(notice).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.warning) }
        }
    }
}

/// 服务入口 2×2：用眼趋势报告、专业咨询建议、AI 综合建议、亮度与蓝光。
private struct ServiceTiles: View {
    let report: CareReport
    let navigate: (AppRoute) -> Void
    var body: some View {
        let urgent = report.consults.contains { $0.urgent }
        VStack(spacing: 10) {
            HStack(spacing: 10) {
                tile("用眼趋势报告", "近 7 天变化", .chart, .success) { navigate(.trend) }
                tile("专业咨询建议", report.consults.first?.title ?? "结合自测与档案", .chat, urgent ? .danger : .blue) { navigate(.advice) }
            }
            HStack(spacing: 10) {
                tile("AI 综合建议", "小瞳结合大模型给建议", .bulb, .accent) { navigate(.advice) }
                tile("亮度与蓝光", report.signal("brightness").map { "屏幕亮度 \($0.value)" } ?? "夜间模式与护眼", .sun, .accent) { navigate(.brightness) }
            }
        }
    }
    private func tile(_ title: String, _ subtitle: String, _ glyph: EyeGlyph, _ tone: TileTone, _ action: @escaping () -> Void) -> some View {
        Button { EyeHaptics.tap(); action() } label: {
            VStack(alignment: .leading, spacing: 10) {
                IconTile(glyph: glyph, tone: tone, size: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(EyeStyle.button).foregroundStyle(EyeStyle.text)
                    Text(subtitle).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary).lineLimit(2).multilineTextAlignment(.leading)
                }
                Spacer(minLength: 0)
            }
            .padding(16).frame(maxWidth: .infinity, minHeight: 120, alignment: .topLeading)
            .background(EyeStyle.panel, in: RoundedRectangle(cornerRadius: 22))
            .contentShape(RoundedRectangle(cornerRadius: 22))
        }.buttonStyle(PressScale(scale: 0.97))
    }
}

// MARK: - 用眼趋势报告

/// 近 7 天用眼负荷、眨眼频率的趋势与小结，以及今天的主要原因。
struct TrendPage: View {
    @ObservedObject private var store = EyeCareStore.shared
    var body: some View {
        let report = store.report
        let scores = report.days.compactMap { $0.score }
        SubPage(title: "用眼趋势报告") {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("近 7 天平均用眼负荷").font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
                        Text(scores.isEmpty ? "—" : "\(scores.reduce(0, +) / scores.count) 分").font(EyeStyle.headline).foregroundStyle(EyeStyle.text)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                    MascotStage(mood: MascotMood(code: report.mood)).frame(width: 104, height: 104)
                }
                ForEach(Array(report.summary.enumerated()), id: \.offset) { _, line in Bullet(text: line) }
            }
            .padding(20).frame(maxWidth: .infinity, alignment: .leading)
            .background(LinearGradient(colors: [EyeStyle.primarySoft, EyeStyle.panel], startPoint: .topLeading, endPoint: .bottomTrailing),
                        in: RoundedRectangle(cornerRadius: 22))
            chart("用眼负荷", unit: "分", help: nil) {
                BarChartView(bars: report.days.enumerated().map { i, d in
                    BarItem(id: i, label: d.label, value: d.score.map { Double($0) }, text: d.score.map { "\($0)" }, highlight: d.label == "今天",
                            warning: (d.score ?? 0) >= 60)
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
                Text(unit).font(EyeStyle.chip).foregroundStyle(EyeStyle.secondary)
            }
            content()
        }
    }
}

// MARK: - 健康建议

/// 健康建议：专业咨询建议（规则按近期自测、身体感受与年龄给出）+ AI 综合建议 + 建议做的自测。
struct AdvicePage: View {
    let openTest: (String) -> Void
    @ObservedObject private var store = EyeCareStore.shared
    var body: some View {
        let report = store.report
        let tests = report.actions.filter { $0.kind == "TEST" }
        SubPage(title: "健康建议") {
            HStack(spacing: 8) {
                MascotStage(mood: MascotMood(code: report.mood)).frame(width: 96, height: 96)
                Text(report.headline).font(EyeStyle.body).foregroundStyle(EyeStyle.text).frame(maxWidth: .infinity, alignment: .leading)
            }.padding(.leading, 8).padding(.trailing, 18).padding(.vertical, 8)
                .background(EyeStyle.panel, in: RoundedRectangle(cornerRadius: 22))
            Text("专业咨询建议").font(EyeStyle.title).foregroundStyle(EyeStyle.text).padding(.leading, 4).padding(.top, 4)
            ForEach(report.consults) { ConsultCard(consult: $0) }
            AiAdviceView()
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

/// 「现在休息 20 秒」：小瞳陪你看远处，圆环倒计时，结束时小瞳蹦一下并有提示音与轻触觉。与安卓 RestBreakPage 同一流程。
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
                        Circle().stroke(EyeStyle.surfaceVariant, lineWidth: 10)
                        Circle().trim(from: 0, to: CGFloat(elapsed / seconds))
                            .stroke(EyeStyle.primary, style: StrokeStyle(lineWidth: 10, lineCap: .round)).rotationEffect(.degrees(-90))
                        MascotStage(mood: done ? .happy : .sleepy, interactive: false, greet: done, headScale: 0.62).frame(width: 170, height: 170)
                    }.frame(width: 240, height: 240)
                    Text(done ? "做得好！" : "\(Int(ceil(seconds - elapsed))) 秒").font(EyeStyle.headline).foregroundStyle(EyeStyle.text)
                        .monospacedDigit().padding(.top, 20)
                    Text(done ? "眼睛放松一下就舒服多了，继续保持。" : "看向 6 米外的远处，慢慢眨几下眼，放松肩膀。")
                        .font(EyeStyle.body).foregroundStyle(EyeStyle.secondary).multilineTextAlignment(.center).padding(.horizontal, 32).padding(.top, 8)
                    Spacer()
                    Button(done ? "完成" : "提前结束") { EyeHaptics.tap(); dismiss() }
                        .buttonStyle(EyeButtonStyle(role: done ? .primary : .secondary)).padding(16)
                }
                .onChange(of: elapsed >= seconds) { finished in
                    if finished && !done { done = true; MascotSound.shared.play("eye_hop"); EyeHaptics.success() }
                }
            }
            .background(EyeStyle.background.ignoresSafeArea())
            .navigationTitle("休息一下").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() } } }
        }
        .onAppear { started = Date() }
    }
}

// MARK: - 护眼操

/// 「护眼操」完整页面（与安卓 EyeExercisePage 同一流程与文案）：深色底，上方进度条，中间大箭头 + 「向左看」+ 倒计时，
/// 下方是小瞳和它的气泡。眼睛转到位时整页朝那个方向翻过去、手机振动，小瞳顺着飞出画面打个滚再回来；最后闭眼休息 5 秒。
/// 判断与计时在共用 EyeExerciseCoach；没有相机权限时按时间带着做。
struct EyeExerciseView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var coach = EyeExerciseCoach()
    @State private var state: EyeExerciseCoach.State?
    @State private var flipMove: EyeExerciseCoach.Move?
    @State private var flyMove: EyeExerciseCoach.Move?
    @State private var fly: CGFloat = 0
    @State private var spin: Double = 0
    private let ticker = Timer.publish(every: 0.05, on: .main, in: .common).autoconnect()

    var body: some View {
        GeometryReader { geo in
            ZStack {
                EyeStyle.ink.ignoresSafeArea()
                VStack(spacing: 0) {
                    HStack(spacing: 8) {
                        Button("结束") { dismiss() }.font(EyeStyle.body).foregroundStyle(.white).frame(minWidth: 44, minHeight: 44)
                        ZStack(alignment: .leading) {
                            Capsule().fill(Color.white.opacity(0.25))
                            Capsule().fill(EyeStyle.success).frame(width: max(0, (geo.size.width - 100) * CGFloat(state?.progress ?? 0)))
                        }.frame(height: 6)
                    }
                    Spacer()
                    instruction.id(pageKey).transition(flipTransition).animation(.easeInOut(duration: 0.38), value: pageKey)
                    Spacer()
                    if !isStage(.done) {
                        MascotBubble(line: MascotLine(text: bubbleText))
                        MascotStage(mood: isStage(.rest) ? .sleepy : .happy, interactive: false)
                            .frame(width: 150, height: 150)
                            .rotationEffect(.degrees(spin * (flyMove.map { $0.dx < 0 || $0.dy < 0 ? -1.0 : 1.0 } ?? 1.0)))
                            .offset(x: CGFloat(flyMove?.dx ?? 0) * fly * geo.size.width * 0.8,
                                    y: CGFloat(flyMove?.dy ?? 0) * fly * geo.size.height * 0.45)
                        Text(state?.faceVisible == true || coach.usedCamera ? "头保持不动，只转眼睛" : "跟着提示转动眼睛就好")
                            .font(EyeStyle.bodySecondary).foregroundStyle(Color.white.opacity(0.7))
                    } else {
                        MascotStage(mood: .happy, interactive: false, greet: true).frame(width: 170, height: 170)
                        Text(coach.usedCamera ? "共 \(state?.stepCount ?? 0) 步，小瞳看到你做到了 \(state?.detectedCount ?? 0) 步" : "共 \(state?.stepCount ?? 0) 步，做完啦")
                            .font(EyeStyle.body).foregroundStyle(.white).multilineTextAlignment(.center)
                        Button("完成") { dismiss() }.buttonStyle(EyeButtonStyle()).padding(.top, 16)
                    }
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
            EyeHaptics.success(); MascotSound.shared.play("eye_spin")
            // 小瞳顺着方向飞出去、边飞边打滚，再从另一边飞回来
            spin = 0
            withAnimation(.easeIn(duration: 0.45)) { fly = 1; spin = 360 }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) {
                fly = -1
                withAnimation(.easeOut(duration: 0.45)) { fly = 0; spin = 720 }
            }
        } else if event == .missed {
            flipMove = nil; EyeHaptics.tap()
        } else if event == .rest {
            flipMove = nil; MascotSound.shared.play("eye_yawn")
        } else if event == .done {
            EyeHaptics.success(); MascotSound.shared.play("eye_heart")
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
                Text("看着小瞳").font(EyeStyle.headline).foregroundStyle(.white)
                Text("准备开始").font(EyeStyle.body).foregroundStyle(Color.white.opacity(0.7))
            } else if isStage(.move), let move = state?.move {
                Image(systemName: arrowName(move)).font(.system(size: 56, weight: .bold)).foregroundStyle(Color.white.opacity(0.6))
                    .accessibilityLabel(move.label)
                Text(move.label).font(EyeStyle.title).foregroundStyle(.white)
                Text("\(state?.secondsLeft ?? 0)").font(.system(size: 96, weight: .bold, design: .rounded)).foregroundStyle(.white)
            } else if isStage(.back) {
                Text("看回中间").font(EyeStyle.title).foregroundStyle(.white)
            } else if isStage(.rest) {
                Text("闭上眼睛休息").font(EyeStyle.title).foregroundStyle(.white)
                Text("\(state?.secondsLeft ?? 0)").font(.system(size: 96, weight: .bold, design: .rounded)).foregroundStyle(.white)
            } else {
                Text("完成啦！").font(EyeStyle.headline).foregroundStyle(.white)
            }
        }.frame(maxWidth: .infinity)
    }

    private func arrowName(_ move: EyeExerciseCoach.Move) -> String {
        move.dx < 0 ? "arrow.left" : move.dx > 0 ? "arrow.right" : move.dy < 0 ? "arrow.up" : "arrow.down"
    }

    /// 小瞳气泡：跟着阶段说一句（与安卓同一文案）。
    private var bubbleText: String {
        if isStage(.move), let move = state?.move {
            return move.dx < 0 ? "眼睛往左边看，看我飞过去！" : move.dx > 0 ? "再往右边看～" : move.dy < 0 ? "抬眼看看上面" : "低眼看看下面"
        }
        if isStage(.back) { return "真棒，看回我这里" }
        if isStage(.rest) { return "闭上眼睛，歇一会儿" }
        return "跟我一起转转眼球吧"
    }
}

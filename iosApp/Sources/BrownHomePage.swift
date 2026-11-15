import SwiftUI
import EyeScreening

/// 棕色深色首页。负荷分、历史与打卡仍来自共用报告，复测启动真实检测。
struct BrownHomePage: View {
    let navigate: (AppRoute) -> Void
    let openTest: (String) -> Void
    @ObservedObject private var store = EyeCareStore.shared
    @StateObject private var reminder = EyeReminder()
    @State private var feeling = false
    @State private var allData = false
    @State private var exercise = false
    @State private var liveRequests = 0
    private var report: CareReport { store.report }
    private var grade: String { switch report.level { case "LOW": "较轻"; case "MEDIUM": "中等"; case "HIGH": "较大"; default: "暂无读数" } }
    private var tone: Color { switch report.level { case "LOW": EyeStyle.success; case "HIGH": EyeStyle.danger; case "MEDIUM": EyeStyle.primary; default: EyeStyle.secondary } }
    private var greeting: String {
        switch Calendar.current.component(.hour, from: Date()) { case 5...10: "早上好"; case 11...13: "中午好"; case 14...17: "下午好"; default: "晚上好" }
    }
    private var today: String {
        let f = DateFormatter(); f.locale = Locale(identifier: "zh_CN"); f.dateFormat = "M月d日 EEEE"; return f.string(from: Date())
    }
    private func signal(_ key: String) -> String { report.signals.first { $0.id == key }?.value ?? "—" }
    private var weekScores: [Int?] {
        var calendar = Calendar(identifier: .iso8601); calendar.timeZone = .current
        guard let monday = calendar.dateInterval(of: .weekOfYear, for: Date())?.start else { return Array(repeating: nil, count: 7) }
        let formatter = DateFormatter(); formatter.dateFormat = "yyyy-MM-dd"
        return (0..<7).map { offset in
            guard let date = calendar.date(byAdding: .day, value: offset, to: monday) else { return nil }
            return report.days.first { $0.id == formatter.string(from: date) }?.score
        }
    }
    var body: some View {
        ScrollViewReader { proxy in
            PageScroll {
                PageHeader(title: greeting, subtitle: today, openAccount: { navigate(.account) }, openSettings: { navigate(.settings) })
                WarmCard(fill: BrownStyle.hero, padding: 16, spacing: 2) {
                    HStack(spacing: 0) {
                        Text("今日用眼负荷").font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text)
                        HelpButton(title: "今日用眼负荷", text: "根据已有用屏、休息与自测数据进行评估，不替代医学诊断。")
                        Spacer(minLength: 0)
                        Button { allData = true } label: { Text("全部数据  ›").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.primary).frame(minHeight: 44) }.buttonStyle(.plain)
                    }
                    ViewThatFits(in: .horizontal) {
                        HStack(spacing: 0) {
                            reading { liveRequests += 1; proxy.scrollTo("live", anchor: .top) }.frame(minWidth: 108, alignment: .leading)
                            LoadEyeArtwork(level: report.level).frame(maxWidth: .infinity).frame(height: 112)
                        }
                        VStack { reading { liveRequests += 1 }; LoadEyeArtwork(level: report.level).frame(height: 112) }
                    }
                    LoadRail(score: report.score)
                    HStack { Text("较轻"); Spacer(); Text("较大") }.font(EyeStyle.caption).foregroundStyle(EyeStyle.secondary)
                    Rectangle().fill(EyeStyle.borderSubtle).frame(height: 1).padding(.top, 4)
                    HStack(alignment: .top, spacing: 16) {
                        metric("环境光", signal("light"))
                        Rectangle().fill(EyeStyle.borderSubtle).frame(width: 1, height: 54)
                        metric("今日用屏", signal("screen"))
                    }.padding(.top, 4)
                }
                WarmCard(fill: BrownStyle.trend, spacing: 2) {
                    HStack {
                        Text("本周用眼趋势").font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text)
                        Spacer()
                        Button { navigate(.trend) } label: { Text("按日查看  ›").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.primary).frame(minHeight: 44) }.buttonStyle(.plain)
                    }
                    TrendRibbon(values: weekScores)
                    HStack { ForEach(["周初", "周中", "周末"], id: \.self) { text in Text(text).font(EyeStyle.caption).foregroundStyle(EyeStyle.secondary).frame(maxWidth: .infinity) } }
                }
                HStack(spacing: 10) {
                    quick("AI 主动问诊", .chat, BrownStyle.consult) { navigate(.chat) }
                    quick("今日感受", .heart, BrownStyle.feeling) { feeling = true }
                }
                WarmCard(padding: 14) {
                    LiveEyeSection(startRequests: liveRequests)
                    Rectangle().fill(EyeStyle.borderSubtle).frame(height: 1)
                    HStack(spacing: 8) {
                        EyeGlyphView(glyph: .bell, color: EyeStyle.ice).frame(width: 20, height: 20)
                        Text("休息一下").font(EyeStyle.navigationTitle).foregroundStyle(EyeStyle.text)
                        Spacer()
                        Toggle("休息一下", isOn: Binding(get: { reminder.enabled }, set: { reminder.setEnabled($0) })).labelsHidden().tint(EyeStyle.primary)
                    }.frame(minHeight: 48)
                    if reminder.enabled { SegmentedPicker(options: [(20,"20 分钟"),(30,"30 分钟"),(60,"60 分钟")], selected: reminder.intervalMinutes) { reminder.setInterval($0) } }
                    if let notice = reminder.notice { Text(notice).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.warning) }
                }.id("live")
                WarmDisclosure(title: "日常护眼") {
                    ListRow(title: "护眼操", glyph: .eye) { exercise = true }
                    ListRow(title: "屏幕光", glyph: .sun, divider: false) { navigate(.brightness) }
                }
            }
        }
        .onAppear { store.refresh() }
        .onChange(of: reminder.enabled) { _ in store.refresh() }
        .fullScreenCover(isPresented: $exercise) { EyeExerciseView() }
        .fullScreenCover(isPresented: $allData) {
            NavigationStack {
                SubPage(title: "全部数据") { ForEach(report.signals) { item in ListRow(title: item.label, value: item.value) } }
                    .toolbar { ToolbarItem(placement: .navigationBarLeading) { Button("关闭") { allData = false } } }
            }
        }
        .overlay {
            if feeling {
                FeelingSheet(initial: report.checkedInToday ? (report.todaySymptoms.isEmpty ? ["没有不适"] : report.todaySymptoms) : [],
                    save: { picked in feeling = false; store.checkIn(picked.filter { $0 != "没有不适" }) }, dismiss: { feeling = false })
            }
        }
    }
    private func reading(_ rescan: @escaping () -> Void) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(grade).font(report.score == nil ? EyeStyle.title : EyeStyle.display).foregroundStyle(tone).fixedSize(horizontal: true, vertical: false)
            Button(action: rescan) { Label(report.score == nil ? "检测" : "复测", systemImage: "arrow.clockwise").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary).padding(.vertical, 8) }.buttonStyle(.plain)
        }
    }
    private func metric(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 4) { Text(title).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary); EyeStyle.reading(value).lineLimit(1).minimumScaleFactor(0.7) }.frame(maxWidth: .infinity, alignment: .leading)
    }
    private func quick(_ title: String, _ glyph: EyeGlyph, _ fill: LinearGradient, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            WarmCard(fill: fill, padding: 14) {
                HStack { EyeGlyphView(glyph: glyph, color: EyeStyle.primary).frame(width: 24, height: 24); Spacer(); Text(title == "今日感受" ? (report.checkedInToday ? "已记录" : "记录") : "↗").font(EyeStyle.caption).foregroundStyle(EyeStyle.secondary) }
                Text(title).font(EyeStyle.navigationTitle).foregroundStyle(EyeStyle.text).padding(.top, 6)
            }
        }.buttonStyle(PressScale(scale: 0.98))
    }
}

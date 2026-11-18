import SwiftUI
import EyeScreening

#if SCREENSHOT_CAPTURE
extension BrownHomePage {
    /// 截图专用入口：展示当前首页的真实打卡弹窗。
    init(captureFeeling: Bool) {
        navigate = { _ in }; openTest = { _ in }
        _feeling = State(initialValue: captureFeeling)
    }
}
#endif

/// 棕色深色首页。负荷分、历史与打卡仍来自共用报告，复测启动真实检测。
struct BrownHomePage: View {
    let navigate: (AppRoute) -> Void
    let openTest: (String) -> Void
    @ObservedObject private var store = EyeCareStore.shared
    @StateObject private var reminder = EyeReminder()
    @State private var feeling = false
    @State private var careTimer: String?
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
    private var availableSignals: [CareReport.Signal] {
        report.signals.filter { !["light", "screen", "longest", "night"].contains($0.id) }
    }
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
                        HelpButton(title: "今日用眼负荷", text: "根据已有实时检测、打卡与自测数据进行评估，不替代医学诊断。")
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
                }
                quick("AI 问诊", BrownStyle.consult) { navigate(.chat) }
                WarmCard(fill: BrownStyle.trend, spacing: 2) {
                    HStack {
                        Text("本周用眼趋势").font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text)
                        Spacer()
                        Button { feeling = true } label: { Text("记录感受  ›").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.primary).frame(minHeight: 44) }.buttonStyle(.plain)
                    }
                    Button { navigate(.trend) } label: { TrendRibbon(values: weekScores) }.buttonStyle(.plain)
                    Button("查看本周趋势") { navigate(.trend) }.font(EyeStyle.bodySecondary)
                    HStack { ForEach(["周初", "周中", "周末"], id: \.self) { text in Text(text).font(EyeStyle.caption).foregroundStyle(EyeStyle.secondary).frame(maxWidth: .infinity) } }
                }
                companionCheckIns
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
                SubPage(title: "全部数据") { ForEach(availableSignals) { item in ListRow(title: item.label, value: item.value) } }
                    .toolbar { ToolbarItem(placement: .navigationBarLeading) { Button("关闭") { allData = false } } }
            }
        }
        .fullScreenCover(isPresented: Binding(get: { careTimer != nil },set: { if !$0 { careTimer = nil } })) {
            companionTimerPage
        }
        .overlay {
            if feeling {
                FeelingSheet(initial: report.checkedInToday ? (report.todaySymptoms.isEmpty ? ["没有不适"] : report.todaySymptoms) : [],
                    save: { picked in feeling = false; store.checkIn(picked.filter { $0 != "没有不适" }) }, dismiss: { feeling = false }, initialNote: report.todayNote, saveNote: { picked,note in feeling = false; store.checkIn(picked.filter { $0 != "没有不适" },note: note) })
            }
        }
    }
    @ViewBuilder private var companionTimerPage: some View {
            if let key = careTimer {
                RestBreakView(title: key,seconds: key == "闭眼休息" ? 30 : key == "热敷眼睛" ? 180 : 20,
                    hint: key == "热敷眼睛" ? "准备温度舒适的热敷用品。\n遵循用品说明，不适时停止。" : key == "闭眼休息" ? "轻轻闭眼。\n让眼睛休息30秒。" : "看向6米外的远处。\n放松视线，持续20秒。",
                    onComplete: { store.careCheckIn(key) })
            }
    }
    private var companionCheckIns: some View {
                WarmCard {
                    HStack {
                        Text("陪伴小打卡").font(EyeStyle.subtitle)
                        Spacer()
                        Text("今日 \(report.completedCare.count) 项").font(EyeStyle.caption).foregroundStyle(EyeStyle.secondary)
                    }
                    ForEach(["闭眼休息","远眺20秒","热敷眼睛"],id: \.self) { key in
                        ListRow(title: key,value: report.completedCare.contains(key) ? "已完成" : "开始") { careTimer = key }
                    }
                }
    }
    private func reading(_ rescan: @escaping () -> Void) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(grade).font(report.score == nil ? EyeStyle.title : EyeStyle.display).foregroundStyle(tone).fixedSize(horizontal: true, vertical: false)
            Button(action: rescan) { Label(report.score == nil ? "检测" : "复测", systemImage: "arrow.clockwise").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary).padding(.vertical, 8) }.buttonStyle(.plain)
        }
    }
    private func quick(_ title: String, _ fill: LinearGradient, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            WarmCard(fill: fill, padding: 14) {
                HStack { Spacer(); Text("↗").font(EyeStyle.caption).foregroundStyle(EyeStyle.secondary) }
                Text(title).font(EyeStyle.navigationTitle).foregroundStyle(EyeStyle.text).padding(.top, 6)
            }
        }.buttonStyle(PressScale(scale: 0.98))
    }
}

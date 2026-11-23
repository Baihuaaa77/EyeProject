import SwiftUI
import EyeScreening

#if SCREENSHOT_CAPTURE
extension BrownHomePage {
    /// 截图专用入口：展示当前首页展开后的感受记录。
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
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var feeling = false
    @State private var careTimer: String?
    @State private var allData = false
    @State private var booking = false
    @State private var exercise = false
    @State private var careDetails = false
    private var report: CareReport { store.report }
    private var grade: String { switch report.level { case "LOW": "轻度"; case "MEDIUM": "中度"; case "HIGH": "重度"; default: "暂无读数" } }
    private var tone: Color { report.score.map(BrownStyle.loadColor) ?? EyeStyle.secondary }
    private var greeting: String {
        switch Calendar.current.component(.hour, from: Date()) { case 5...10: "早上好"; case 11...13: "中午好"; case 14...17: "下午好"; default: "晚上好" }
    }
    private var today: String {
        let f = DateFormatter(); f.locale = Locale(identifier: "zh_CN"); f.dateFormat = "M月d日 EEEE"; return f.string(from: Date())
    }
    private enum ScrollTarget: Hashable { case feeling }

    var body: some View {
        ScrollViewReader { proxy in
            PageScroll(backgroundColor: BrownStyle.homeBackground) {
                PageHeader(title: greeting, subtitle: today, openAccount: { navigate(.account) }, openSettings: { navigate(.settings) }, warm: true)
                WarmCard(fill: BrownStyle.homeSurface, padding: 16, spacing: 4, edge: tone) {
                    HStack {
                        Text("用眼负荷").font(EyeStyle.title).foregroundStyle(EyeStyle.text)
                        Spacer(minLength: 0)
                        homeLink("用眼全记录") { allData.toggle() }
                    }
                    ViewThatFits(in: .horizontal) {
                        HStack(spacing: 0) {
                            reading.frame(minWidth: 104, alignment: .leading)
                            LoadEyeArtwork(level: report.level, tone: tone).frame(maxWidth: .infinity).frame(height: 112)
                        }
                        VStack(alignment: .leading) { reading; LoadEyeArtwork(level: report.level, tone: tone).frame(height: 94) }
                    }
                    LoadRail(score: report.score, tone: tone)
                    HStack { Text("较轻"); Spacer(); Text("较重") }.font(EyeStyle.caption).foregroundStyle(EyeStyle.text)
                    HStack(spacing: 8) {
                        homeAction("AI问诊") { navigate(.chat) }
                        homeAction("智能挂号") { booking = true }
                    }.padding(.top, 8)
                }
                if allData { DailyRecordsView(report: report, onClose: { allData = false }) }
                companionCheckIns
                HomeWeekChart(history: report.historyDays, feelingExpanded: feeling,
                              onFeeling: { feeling.toggle() }, onTrend: { navigate(.trend) }) {
                    if feeling { feelingForm }
                }.id(ScrollTarget.feeling)
                WarmCard(padding: 14) {
                    LiveEyeSection()
                    Rectangle().fill(EyeStyle.borderSubtle).frame(height: 1)
                    HStack(spacing: 8) {
                        EyeGlyphView(glyph: .bell, color: EyeStyle.ice).frame(width: 20, height: 20)
                        Text("休息一下").font(EyeStyle.navigationTitle).foregroundStyle(EyeStyle.text)
                        Spacer()
                        Toggle("休息一下", isOn: Binding(get: { reminder.enabled }, set: { reminder.setEnabled($0) })).labelsHidden().tint(EyeStyle.primary)
                    }.frame(minHeight: 48)
                    if reminder.enabled { SegmentedPicker(options: [(20,"20 分钟"),(30,"30 分钟"),(60,"60 分钟")], selected: reminder.intervalMinutes) { reminder.setInterval($0) } }
                    if let notice = reminder.notice { Text(notice).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.warning) }
                }
                WarmDisclosure(title: "日常护眼") {
                    ListRow(title: "护眼操", glyph: .eye) { exercise = true }
                    ListRow(title: "屏幕光", glyph: .sun, divider: false) { navigate(.brightness) }
                }
            }
            .onChange(of: feeling) { expanded in
                guard expanded else { return }
                withAnimation(reduceMotion ? nil : .easeInOut) {
                    proxy.scrollTo(ScrollTarget.feeling, anchor: .top)
                }
            }
        }
        .onAppear { store.refresh() }
        .onChange(of: reminder.enabled) { _ in store.refresh() }
        .fullScreenCover(isPresented: $booking) { OfflineSessionCover(session: .booking) }
        .fullScreenCover(isPresented: $exercise) { EyeExerciseView() }
        .fullScreenCover(isPresented: Binding(get: { careTimer != nil },set: { if !$0 { careTimer = nil } })) {
            companionTimerPage
        }
    }
    private var feelingForm: some View {
        let noDiscomfort = "没有不适"
        let initial = report.checkedInToday ? (report.todaySymptoms.isEmpty ? [noDiscomfort] : report.todaySymptoms) : []
        return FeelingSheet(initial: initial,
                            save: { saveFeeling($0, note: "") },
                            dismiss: { feeling = false },
                            initialNote: report.todayNote,
                            saveNote: { saveFeeling($0, note: $1) },
                            inline: true)
    }

    private func saveFeeling(_ selected: [String], note: String) {
        store.checkIn(selected.filter { $0 != "没有不适" }, note: note)
        feeling = false
    }

    @ViewBuilder private var companionTimerPage: some View {
            if let key = careTimer {
                RestBreakView(title: key,seconds: key == "闭眼休息" ? 30 : key == "热敷眼睛" ? 180 : 20,
                    hint: key == "热敷眼睛" ? "可用干净的温湿毛巾，也可选眼部专用热敷眼罩，按说明加热和清洁。\n先试温，温热不烫再轻放在闭合的眼睑上；感到烫或不舒服就取下。" : key == "闭眼休息" ? "轻轻闭眼。\n让眼睛休息30秒。" : "看向6米外的远处。\n放松视线，持续20秒。",
                    onComplete: { store.careCheckIn(key) })
            }
    }
    private var companionCheckIns: some View {
        WarmCard(fill: BrownStyle.homeSurface, padding: 14, spacing: 4, edge: BrownStyle.homeEdge) {
            let habits = ["闭眼休息", "远眺20秒", "热敷眼睛"]
            HStack {
                Text("加油小贴贴").font(EyeStyle.subtitle)
                Spacer()
                homeLink("今日 \(habits.filter { report.completedCare.contains($0) }.count) 次") { careDetails.toggle() }
            }
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 6) {
                    ForEach(habits, id: \.self) { name in habitButton(name) }
                }
                VStack(spacing: 6) {
                    ForEach(habits, id: \.self) { name in habitButton(name) }
                }
            }
            if careDetails {
                Text(report.completedCare.isEmpty ? "今天还没打卡" : report.completedCare.joined(separator: "、"))
                    .font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
            }
        }
    }

    private func habitButton(_ name: String) -> some View {
        homeAction(report.completedCare.contains(name) ? "✓ \(name)" : name) { careTimer = name }
    }

    private var reading: some View {
        Text(grade).font(report.score == nil ? EyeStyle.title : EyeStyle.display)
            .foregroundStyle(tone).fixedSize(horizontal: true, vertical: false)
    }

    private func homeLink(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title + " ›").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.text).frame(minHeight: 44)
        }.buttonStyle(.plain)
    }

    private func homeAction(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).font(EyeStyle.navigationTitle).foregroundStyle(EyeStyle.text).fixedSize(horizontal: true, vertical: false)
                .padding(.horizontal, 5).frame(maxWidth: .infinity, minHeight: 46)
                .background(BrownStyle.homeAction, in: RoundedRectangle(cornerRadius: EyeStyle.radiusMedium))
                .overlay { RoundedRectangle(cornerRadius: EyeStyle.radiusMedium).strokeBorder(BrownStyle.homeEdge) }
        }.buttonStyle(.plain)
    }
}

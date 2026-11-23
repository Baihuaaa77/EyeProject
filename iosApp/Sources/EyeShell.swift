import SwiftUI
import UserNotifications

/// 与安卓 EyeProjectNavHost 一致的三个顶层页：首页 / 服务 / 我的，底部自绘导航栏。
enum TopLevelTab: CaseIterable { case home, services, mine
    var label: String { switch self { case .home: "首页"; case .services: "服务"; case .mine: "我的" } }
    var icon: NavIcon { switch self { case .home: .home; case .services: .services; case .mine: .mine } }
}

/// 二级完整页面（NavigationStack 推入，替代系统底部弹窗）；与安卓各 feature 的路由一一对应。
enum AppRoute: Hashable {
    case account, settings, records, subjects, cloud, permissions, trend, advice, brightness, chat, exercise
    /// 编辑成员资料；nil 为新建家庭成员
    case subjectEdit(String?)
}

struct EyeRootView: View {
    @Environment(\.scenePhase) private var scenePhase
    @State private var tab = TopLevelTab.home
    @State private var path: [AppRoute] = []
    @State private var selectedTest: String?
    /// 冷启动的启动动画：盖在已经创建好的首页上，结束后移除
    @State private var splash = true
    /// 未登录时启动先提示登录 / 注册（可跳过）；每次冷启动只出现一次
    @State private var welcome = false
    /// 「系统设置」：与测试页同一份偏好（保存在本机，测试开始时读取）
    @StateObject private var voice = EyeVoice()
    @StateObject private var feedback = EyeFeedback()

    var body: some View {
        ZStack {
            NavigationStack(path: $path) {
                VStack(spacing: 0) {
                    ZStack {
                        switch tab {
                        case .home: HomePage(navigate: navigate, openTest: openTest)
                        case .services: ServicesPage(open: openTest, navigate: navigate)
                        case .mine: MinePage(navigate: navigate)
                        }
                    }.frame(maxWidth: .infinity, maxHeight: .infinity)
                        .transition(.opacity)
                        .animation(.easeOut(duration: 0.2), value: tab)
                    EyeBottomBar(selected: $tab)
                }
                .background(EyeStyle.background.ignoresSafeArea())
                .toolbar(.hidden, for: .navigationBar)
                .navigationDestination(for: AppRoute.self) { destination($0) }
            }
            if splash { SplashView { splash = false; welcome = needsWelcome }.transition(.opacity).zIndex(1) }
        }
        .scrollIndicators(.hidden)
        // 测试全屏呈现：不用卡片式弹窗，避免顶部留出灵动岛下方的空隙和下滑误关。
        .fullScreenCover(item: Binding(get: { selectedTest.map(TestSelection.init) }, set: { selectedTest = $0?.id })) { test in
            EyeTestView(kind: test.id)
        }
        .fullScreenCover(isPresented: $welcome) { WelcomeView { welcome = false } }
        .onAppear { EyeCareStore.shared.appBecameActive() }
        .onChange(of: scenePhase) { phase in
            if phase == .active {
                // 回到前台时补传离线期间保存的记录与资料（未登录或未同意时不联网）
                CloudModel.shared.client.syncNow(userInitiated: false)
                EyeCareStore.shared.appBecameActive()
            } else if phase == .background {
                EyeCareStore.shared.appResignedActive()
            }
        }
    }

    private func navigate(_ route: AppRoute) { path.append(route) }
    /// 连着云端服务却没登录（用户主动清空服务器地址、只在本机用的不打扰）
    private var needsWelcome: Bool { CloudModel.shared.bool("configured") && !CloudModel.shared.bool("signedIn") }
    private func openTest(_ kind: String) { selectedTest = kind }

    @ViewBuilder private func destination(_ route: AppRoute) -> some View {
        switch route {
        case .exercise: EyeExerciseView()
        case .account: AccountPage(editProfile: { navigate(.subjectEdit($0)) },openCloudSync: { navigate(.cloud) })
        case .settings: SystemSettingsPage(voice: voice, feedback: feedback,openAccount: { navigate(.account) },openPermissions: { navigate(.permissions) },openCloud: { navigate(.cloud) })
        case .records: RecordsPage()
        case .subjects: SubjectsPage(edit: { navigate(.subjectEdit($0)) })
        case .cloud: CloudSyncPage(openAccount: { navigate(.account) })
        case .permissions: PermissionsPage()
        case .trend: TrendPage()
        case .advice: AdvicePage(openTest: openTest)
        case .chat: AiChatPage(openAccount: { navigate(.account) })
        case .brightness: BrightnessPage()
        case .subjectEdit(let id):
            SubjectEditView(item: id.flatMap { id in SubjectStore.shared.subjects.first { $0.id == id } } ?? SubjectStore.newMember())
        }
    }
}

private struct TestSelection: Identifiable { let id: String; init(_ id: String) { self.id = id } }

private struct EyeBottomBar: View {
    @Binding var selected: TopLevelTab
    @Environment(\.accessibilityReduceMotion) private var reducedMotion
    var body: some View {
        HStack(spacing: 4) {
            ForEach(TopLevelTab.allCases, id: \.self) { tab in
                let active = tab == selected
                Button { if !active { EyeHaptics.tap() }; selected = tab } label: {
                    HStack(spacing: 6) {
                        NavIconView(icon: tab.icon, selected: false).frame(width: 24, height: 24).scaleEffect(active ? 1.06 : 1)
                        Text(tab.label).font(EyeStyle.body)
                    }
                    .foregroundStyle(active ? EyeStyle.onPrimary : EyeStyle.text)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background { if active { Capsule().fill(EyeStyle.primary) } }
                    .contentShape(Capsule())
                }.buttonStyle(.plain)
                    .accessibilityLabel(tab.label)
                    .accessibilityAddTraits(active ? [.isSelected] : [])
                    .animation(reducedMotion ? nil : .spring(response: 0.35, dampingFraction: 0.8), value: active)
            }
        }
        .padding(6).frame(height: 60)
        .background(EyeStyle.tabBarFill, in: Capsule())
        .overlay { Capsule().strokeBorder(EyeStyle.tabBarEdge).allowsHitTesting(false) }
        .padding(.horizontal, 16).padding(.top, 12).padding(.bottom, 12)
    }
}

/// 顶层页骨架：顶部彩色渐变光背景、左右 20 内边距、竖向滚动。
struct PageScroll<Content: View>: View {
    @ViewBuilder let content: Content
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: EyeSpacing.cardGap) { content }
                .padding(.horizontal, EyeSpacing.gutter).padding(.bottom, EyeSpacing.xl)
        }.background(EyeBackdrop())
    }
}

/// 休息提醒：本地通知按间隔重复，文案与安卓通知一致；关闭即撤销。
final class EyeReminder: ObservableObject {
    @Published private(set) var enabled = UserDefaults.standard.bool(forKey: "eye.reminderEnabled")
    @Published private(set) var intervalMinutes = UserDefaults.standard.object(forKey: "eye.reminderMinutes") as? Int ?? 20
    @Published private(set) var notice: String?
    private let identifier = "eye.rest.reminder"

    func setEnabled(_ value: Bool) {
        guard value else { cancel(); return }
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { granted, _ in
            DispatchQueue.main.async {
                if granted { self.notice = nil; self.schedule() }
                else { self.notice = "未获得通知权限，请在系统设置中允许「眼健康助手」发送通知。"; self.cancel() }
            }
        }
    }

    func setInterval(_ minutes: Int) {
        intervalMinutes = minutes
        UserDefaults.standard.set(minutes, forKey: "eye.reminderMinutes")
        if enabled { schedule() }
    }

    private func schedule() {
        let content = UNMutableNotificationContent()
        content.title = "该休息一下眼睛了"
        content.body = "请放下屏幕，眺望远处并放松眼睛。"
        content.sound = .default
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: TimeInterval(intervalMinutes * 60), repeats: true)
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [identifier])
        center.add(UNNotificationRequest(identifier: identifier, content: content, trigger: trigger))
        enabled = true; UserDefaults.standard.set(true, forKey: "eye.reminderEnabled")
    }

    private func cancel() {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [identifier])
        enabled = false; UserDefaults.standard.set(false, forKey: "eye.reminderEnabled")
    }
}

// MARK: 服务

/// 服务页（2026-11-08 视觉改版）：「服务」大标题与账户头像 → 示范主卡（轮流演示三项自测的做法）→
private struct ServicesPage: View {
    let open: (String) -> Void
    let navigate: (AppRoute) -> Void
    @ObservedObject private var store = EyeCareStore.shared
    @State private var restOpen = false
    var body: some View {
        PageScroll {
            PageHeader(title: "服务", openAccount: { navigate(.account) }, openSettings: { navigate(.settings) })
            if serviceWeek.contains(where: { $0 != nil }) {
                WeekUsageCard(values: serviceWeek, labels: ["一","二","三","四","五","六","日"],
                    todayIndex: (Calendar.current.component(.weekday, from: Date()) + 5) % 7)
            }
            Text("选择一项自测").font(EyeStyle.title).foregroundStyle(EyeStyle.text)
                .padding(.top, EyeSpacing.sectionGap - EyeSpacing.cardGap).padding(.leading, 4)
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 16), GridItem(.flexible(), spacing: 16)], spacing: 16) {
                ServiceRow(title: "干眼自测", glyph: .dryEye, tone: .blue) { open("DRY_EYE") }
                ServiceRow(title: "眼位自测", glyph: .alignment, tone: .violet) { open("STRABISMUS") }
                ServiceRow(title: "老视自测", glyph: .presbyopia, tone: .primary) { open("PRESBYOPIA") }
                ServiceRow(title: "色觉自测", glyph: .color, tone: .accent, divider: false) { open("COLOR_VISION") }
            }
            Text("日常护眼").font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text).padding(.top, 8)
            Button { restOpen = true } label: {
                WarmCard {
                    EyeGlyphView(glyph: .eye, color: EyeStyle.primary).frame(width: 32, height: 32).frame(maxWidth: .infinity).padding(.top, 12)
                    Text("转转眼睛").font(EyeStyle.navigationTitle).foregroundStyle(EyeStyle.text).frame(maxWidth: .infinity).padding(.vertical, 12)
                }
            }.buttonStyle(.plain).frame(maxWidth: .infinity)
            WarmDisclosure(title: "自测方法") { DemoHeroCard() }
        }.fullScreenCover(isPresented: $restOpen) { OfflineSessionCover(session: .rest) }
    }
    private var serviceWeek: [Int?] {
        var calendar = Calendar(identifier: .iso8601)
        calendar.timeZone = .current
        guard let monday = calendar.dateInterval(of: .weekOfYear, for: Date())?.start else { return Array(repeating: nil, count: 7) }
        let formatter = DateFormatter(); formatter.calendar = calendar; formatter.dateFormat = "yyyy-MM-dd"
        return (0..<7).map { offset in
            guard let day = calendar.date(byAdding: .day, value: offset, to: monday) else { return nil }
            return store.report.days.first { $0.id == formatter.string(from: day) }?.screenMinutes
        }
    }

}

/// 示范主卡：随机轮流演示三项自测的做法（每种连播两遍），上方是测试名称与一句做法（随示范淡入淡出）。
/// 点示范区换下一个。与安卓 DemoHero 同一排版与文案；系统「减弱动态效果」时停在关键帧。
private struct DemoHeroCard: View {
    private struct Clip { let motion: GuideMotion; let name: String; let line: String }
    private static let clips = [
        Clip(motion: .blink, name: "干眼", line: "像平时一样自然眨眼"),
        Clip(motion: .cover, name: "眼位", line: "本人遮眼，再移开"),
        Clip(motion: .near, name: "老视", line: "看清 E 的开口朝哪边"),
    ]
    /// 每种示范连播的遍数。
    private static let repeats: Double = 2
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var order = DemoHeroCard.clips.shuffled()
    @State private var start = Date()
    @State private var skip = 0
    @State private var pausedAt: Date?
    @State private var pausedDuration: TimeInterval = 0

    var body: some View {
        let lengths = order.map { GuideScene.scene($0.motion).cycle * Self.repeats }
        TimelineView(.animation(paused: reduceMotion || pausedAt != nil)) { timeline in
            let (segment, elapsed) = segmentAt((pausedAt ?? timeline.date).timeIntervalSince(start) - pausedDuration, lengths)
            let clip = order[(segment + skip) % order.count]
            let scene = GuideScene.scene(clip.motion)
            EyeHeroCard(padding: 16, spacing: 10) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("示范：\(clip.name)").font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text)
                    Text(clip.line).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                }
                .padding(.horizontal, 4)
                .id(clip.name).transition(.opacity)
                .animation(.easeOut(duration: 0.28), value: clip.name)
                if !reduceMotion {
                    HStack {
                        Spacer()
                        Button(pausedAt == nil ? "暂停示范" : "播放示范") {
                            if let paused = pausedAt { pausedDuration += Date().timeIntervalSince(paused); pausedAt = nil }
                            else { pausedAt = Date() }
                        }.font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.primaryDark).frame(minHeight: 44)
                    }
                }
                GuideFrame(motion: clip.motion, phase: reduceMotion ? scene.keyframe : elapsed.truncatingRemainder(dividingBy: scene.cycle) / scene.cycle)
                    .clipShape(RoundedRectangle(cornerRadius: EyeStyle.radiusLarge, style: .continuous))
                    .contentShape(Rectangle())
                    .onTapGesture { skip += 1; EyeHaptics.tap() }
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("正在示范\(clip.name)自测，点一下换下一个示范")
                    .accessibilityAddTraits(.isButton)
            }
        }
    }

    /// 轮播时钟 [seconds] 落在第几段、该段已播多少秒。
    private func segmentAt(_ seconds: Double, _ lengths: [Double]) -> (Int, Double) {
        let total = lengths.reduce(0, +)
        var t = max(0, seconds).truncatingRemainder(dividingBy: total)
        for (i, length) in lengths.enumerated() {
            if t < length || i == lengths.count - 1 { return (i, t) }
            t -= length
        }
        return (0, 0)
    }
}

private struct ServiceRow: View {
    let title: String
    let glyph: ServiceGlyph
    let tone: TileTone
    var divider = true
    let action: () -> Void
    var body: some View {
        Button { EyeHaptics.tap(); action() } label: {
            VStack(spacing: 16) {
                ServiceGlyphView(glyph: glyph, color: glyph == .presbyopia ? EyeStyle.Tile.orange : glyph == .alignment ? EyeStyle.Tile.indigo : EyeStyle.Tile.teal)
                    .frame(width: 32, height: 32)
                Text(title).font(EyeStyle.navigationTitle).foregroundStyle(EyeStyle.text)
            }.frame(maxWidth: .infinity, minHeight: 148)
                .eyeGlass(fill: AnyShapeStyle(BrownStyle.surface))
        }.buttonStyle(PressScale(scale: 0.98))
    }
}

#if SCREENSHOT_CAPTURE
/// 截图入口沿用三个主页与真实底栏，只移除启动浮层和导航副作用。
@MainActor
struct CaptureMainPage: View {
    let tab: TopLevelTab
    var body: some View {
        VStack(spacing: 0) {
            Group {
                switch tab {
                case .home: HomePage(navigate: { _ in }, openTest: { _ in })
                case .services: ServicesPage(open: { _ in }, navigate: { _ in })
                case .mine: MinePage(navigate: { _ in })
                }
            }.frame(maxWidth: .infinity, maxHeight: .infinity)
            EyeBottomBar(selected: .constant(tab))
        }.background(EyeStyle.background.ignoresSafeArea())
    }
}
#endif

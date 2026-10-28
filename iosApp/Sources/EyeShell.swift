import SwiftUI
import UserNotifications

/// 与安卓 EyeProjectNavHost 一致的三个顶层页：首页 / 服务 / 我的，底部自绘导航栏。
enum TopLevelTab: CaseIterable { case home, services, mine
    var label: String { switch self { case .home: "首页"; case .services: "服务"; case .mine: "我的" } }
    var icon: NavIcon { switch self { case .home: .home; case .services: .services; case .mine: .mine } }
}

/// 二级完整页面（NavigationStack 推入，替代系统底部弹窗）；与安卓各 feature 的路由一一对应。
enum AppRoute: Hashable {
    case account, settings, records, subjects, cloud, permissions, trend, advice, brightness
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
            if splash { SplashView { splash = false }.transition(.opacity).zIndex(1) }
        }
        // 测试全屏呈现：不用卡片式弹窗，避免顶部留出灵动岛下方的空隙和下滑误关。
        .fullScreenCover(item: Binding(get: { selectedTest.map(TestSelection.init) }, set: { selectedTest = $0?.id })) { test in
            EyeTestView(kind: test.id)
        }
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
    private func openTest(_ kind: String) { selectedTest = kind }

    @ViewBuilder private func destination(_ route: AppRoute) -> some View {
        switch route {
        case .account: AccountPage(openCloudSync: { navigate(.cloud) })
        case .settings: SystemSettingsPage(voice: voice, feedback: feedback)
        case .records: RecordsPage()
        case .subjects: SubjectsPage(edit: { navigate(.subjectEdit($0)) })
        case .cloud: CloudSyncPage(openAccount: { navigate(.account) })
        case .permissions: PermissionsPage()
        case .trend: TrendPage()
        case .advice: AdvicePage(openTest: openTest)
        case .brightness: BrightnessPage()
        case .subjectEdit(let id):
            SubjectEditView(item: id.flatMap { id in SubjectStore.shared.subjects.first { $0.id == id } } ?? SubjectStore.newMember())
        }
    }
}

private struct TestSelection: Identifiable { let id: String; init(_ id: String) { self.id = id } }

/// 白底细上边框；选中项图标后方的浅主色胶囊以弹簧展开，图标与文字同步变色并轻微放大。
private struct EyeBottomBar: View {
    @Binding var selected: TopLevelTab
    var body: some View {
        HStack(spacing: 0) {
            ForEach(TopLevelTab.allCases, id: \.self) { tab in
                let active = tab == selected
                Button { if !active { EyeHaptics.tap() }; selected = tab } label: {
                    VStack(spacing: 4) {
                        ZStack {
                            Capsule().fill(EyeStyle.primarySoft).frame(width: active ? 56 : 0, height: 30)
                            NavIconView(icon: tab.icon).frame(width: 22, height: 22).scaleEffect(active ? 1.08 : 1)
                        }.frame(width: 56, height: 30)
                        Text(tab.label).font(EyeStyle.chip)
                    }.foregroundStyle(active ? EyeStyle.primary : EyeStyle.secondary)
                        .frame(maxWidth: .infinity, minHeight: 68).contentShape(Rectangle())
                }.buttonStyle(.plain)
                    .accessibilityAddTraits(active ? [.isSelected] : [])
                    .animation(.spring(response: 0.35, dampingFraction: 0.8), value: active)
            }
        }.background(EyeStyle.panel.ignoresSafeArea(edges: .bottom))
            .overlay(alignment: .top) { Rectangle().fill(EyeStyle.borderSubtle).frame(height: 1) }
    }
}

/// 顶层页骨架：浅色背景、左右 20 内边距、竖向滚动。
struct PageScroll<Content: View>: View {
    @ViewBuilder let content: Content
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) { content }
                .padding(.horizontal, 20).padding(.bottom, 24)
        }.background(EyeStyle.background)
    }
}

/// 入口卡：浅主色圆角底上的图标 + 标题 / 副标题 + 右箭头，最小高 80（安卓 EyeSelectCard）。
private struct SelectCard<Leading: View>: View {
    let title: String
    var subtitle: String?
    @ViewBuilder let leading: Leading
    let action: () -> Void
    var body: some View {
        Button { EyeHaptics.tap(); action() } label: {
            HStack(spacing: 14) {
                leading.frame(width: 48, height: 48).background(EyeStyle.primarySoft, in: RoundedRectangle(cornerRadius: EyeStyle.radiusMedium))
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text)
                    if let subtitle { Text(subtitle).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary) }
                }.frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "arrow.right").font(.system(size: 15, weight: .medium)).foregroundStyle(EyeStyle.secondary)
            }.padding(.horizontal, 20).padding(.vertical, 16).frame(minHeight: 80)
                .background(EyeStyle.panel, in: RoundedRectangle(cornerRadius: 22))
                .contentShape(RoundedRectangle(cornerRadius: 22))
        }.buttonStyle(PressScale(scale: 0.98))
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

/// 服务页：标题（右上角账户与系统设置）+ 小瞳欢迎卡 + 三个自测入口（顺序、文案、图标与安卓一致）。
private struct ServicesPage: View {
    let open: (String) -> Void
    let navigate: (AppRoute) -> Void
    var body: some View {
        PageScroll {
            PageHeader(title: "服务", openAccount: { navigate(.account) }, openSettings: { navigate(.settings) })
            DemoHeroCard()
            Text("选择一项自测").font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text).padding(.top, 4)
            SelectCard(title: "干眼自测", subtitle: "约 2 分钟 · 自然眨眼与睁眼观察") {
                ServiceGlyphView(glyph: .dryEye).frame(width: 28, height: 28)
            } action: { open("DRY_EYE") }
            SelectCard(title: "眼位自测", subtitle: "注视、遮眼、跟随 · 需一位协助者") {
                ServiceGlyphView(glyph: .alignment).frame(width: 28, height: 28)
            } action: { open("STRABISMUS") }
            SelectCard(title: "老视自测", subtitle: "近视力与调节 · 看 E 选方向") {
                ServiceGlyphView(glyph: .presbyopia).frame(width: 28, height: 28)
            } action: { open("PRESBYOPIA") }
            SelectCard(title: "色觉自测", subtitle: "约 1 分钟 · 看色点图认数字") {
                ServiceGlyphView(glyph: .color).frame(width: 28, height: 28)
            } action: { open("COLOR_VISION") }
            Text("自测结果仅供参考，不能替代医院检查。").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary).padding(.top, 4)
        }
    }
}

/// 示范舞台：小瞳随机轮流演示三项自测的做法（每种连播两遍），上方气泡先说这是哪项测试、再说「跟着我一起做吧」。
/// 点舞台换下一个示范。与安卓 DemoHero 同一排版与文案；系统「减弱动态效果」时停在关键帧、气泡常显。
private struct DemoHeroCard: View {
    private struct Clip { let motion: GuideMotion; let name: String; let lines: [String] }
    private static let clips = [
        Clip(motion: .blink, name: "干眼自测", lines: ["干眼自测：像平时一样眨眨眼", "跟着我一起做吧"]),
        Clip(motion: .cover, name: "眼位自测", lines: ["眼位自测：遮住一只眼，再移开", "找家人帮忙一起做哦"]),
        Clip(motion: .near, name: "老视自测", lines: ["老视自测：看清 E 的开口朝哪边", "跟着我一起做吧"]),
    ]
    /// 每种示范连播的遍数。
    private static let repeats: Double = 2
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var order = DemoHeroCard.clips.shuffled()
    @State private var start = Date()
    @State private var skip = 0

    var body: some View {
        let lengths = order.map { GuideScene.scene($0.motion).cycle * Self.repeats }
        TimelineView(.animation(paused: reduceMotion)) { timeline in
            let (segment, elapsed) = segmentAt(timeline.date.timeIntervalSince(start), lengths)
            let clip = order[(segment + skip) % order.count]
            let scene = GuideScene.scene(clip.motion)
            let k = elapsed / lengths[segment]
            let line = reduceMotion ? 0 : (k >= 0.03 && k <= 0.42 ? 0 : (k >= 0.52 && k <= 0.92 ? 1 : -1))
            let key = line >= 0 ? "\(clip.name)\(line)" : ""
            VStack(spacing: 0) {
                ZStack(alignment: .bottomLeading) {
                    Color.clear
                    if line >= 0 {
                        MascotBubble(line: MascotLine(text: clip.lines[line]), tail: .start)
                            .id(key)
                            .transition(reduceMotion ? .opacity : .scale(scale: 0.6, anchor: UnitPoint(x: 0.15, y: 1)).combined(with: .opacity))
                    }
                }
                .frame(height: 60).padding(.leading, 4)
                .animation(reduceMotion ? .easeOut(duration: 0.18) : .spring(response: 0.35, dampingFraction: 0.55), value: key)
                GuideFrame(motion: clip.motion, phase: reduceMotion ? scene.keyframe : elapsed.truncatingRemainder(dividingBy: scene.cycle) / scene.cycle)
                    .contentShape(Rectangle())
                    .onTapGesture { skip += 1; MascotSound.shared.play("eye_pop") }
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("小瞳正在示范\(clip.name)，点一下换下一个示范")
                    .accessibilityAddTraits(.isButton)
            }
            .padding(12)
            .background(EyeStyle.primarySoft, in: RoundedRectangle(cornerRadius: 22))
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

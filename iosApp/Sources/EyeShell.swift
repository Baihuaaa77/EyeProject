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
            MascotHeroCard()
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
            Text("自测结果仅供参考，不能替代医院检查。").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary).padding(.top, 4)
        }
    }
}

/// 欢迎卡：小瞳待机（眨眼、张望、呼吸），点一下会互动。
private struct MascotHeroCard: View {
    var body: some View {
        HStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 6) {
                Text("我是小瞳").font(EyeStyle.title).foregroundStyle(EyeStyle.text)
                Text("每项测试前，我会先示范一遍怎么做。跟着做就好。").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
            }.frame(maxWidth: .infinity, alignment: .leading)
            MascotStage(mood: .happy, headScale: 0.72).frame(width: 112, height: 112)
        }.padding(.leading, 20).padding(.trailing, 8).padding(.vertical, 16)
            .background(EyeStyle.primarySoft, in: RoundedRectangle(cornerRadius: 22))
    }
}

import SwiftUI
import UserNotifications

/// 与安卓 EyeProjectNavHost 一致的三个顶层页：首页 / 服务 / 我的，底部自绘导航栏。
enum TopLevelTab: CaseIterable { case home, services, mine
    var label: String { switch self { case .home: "首页"; case .services: "服务"; case .mine: "我的" } }
    var icon: NavIcon { switch self { case .home: .home; case .services: .services; case .mine: .mine } }
}

struct EyeRootView: View {
    @Environment(\.scenePhase) private var scenePhase
    @State private var tab = TopLevelTab.home
    @State private var selectedTest: String?
    @State private var settingsOpen = false
    /// 顶层页右上角齿轮打开的「系统设置」：与测试页同一份偏好（保存在本机，测试开始时读取）
    @StateObject private var voice = EyeVoice()
    @StateObject private var feedback = EyeFeedback()
    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                switch tab {
                case .home: HomePage { settingsOpen = true }
                case .services: ServicesPage(open: { selectedTest = $0 }) { settingsOpen = true }
                case .mine: MinePage { settingsOpen = true }
                }
            }.frame(maxWidth: .infinity, maxHeight: .infinity)
                .transition(.opacity)
                .animation(.easeOut(duration: 0.2), value: tab)
            EyeBottomBar(selected: $tab)
        }.background(EyeStyle.background.ignoresSafeArea())
            // 测试全屏呈现：不用卡片式弹窗，避免顶部留出灵动岛下方的空隙和下滑误关。
            .fullScreenCover(item: Binding(get: { selectedTest.map(TestSelection.init) }, set: { selectedTest = $0?.id })) { test in
                EyeTestView(kind: test.id)
            }
            .sheet(isPresented: $settingsOpen) { TestSettingsView(voice: voice, feedback: feedback) }
            // 回到前台时补传离线期间保存的记录与资料（未登录或未同意时不联网）
            .onChange(of: scenePhase) { phase in if phase == .active { CloudModel.shared.client.syncNow(userInitiated: false) } }
    }
}

private struct TestSelection: Identifiable { let id: String; init(_ id: String) { self.id = id } }

/// 顶层页右上角常驻的「系统设置」齿轮（与测试页顶栏同一图标），与安卓一致。
private struct SettingsGear: View {
    let action: () -> Void
    var body: some View {
        Button { EyeHaptics.tap(); action() } label: {
            Image(systemName: "gearshape.fill").font(.system(size: 22)).foregroundStyle(EyeStyle.primary)
                .frame(width: 44, height: 44).background(EyeStyle.panel, in: Circle())
                .overlay { Circle().stroke(EyeStyle.borderSubtle, lineWidth: 1) }
        }.buttonStyle(.plain).accessibilityLabel("系统设置")
    }
}

/// 顶层页标题行：大标题 + 右上角齿轮。
private struct PageHeader: View {
    let title: String
    let openSettings: () -> Void
    var body: some View {
        HStack {
            Text(title).font(EyeStyle.headline).foregroundStyle(EyeStyle.text)
            Spacer()
            SettingsGear(action: openSettings)
        }.padding(.vertical, 16)
    }
}

/// 白底细上边框；选中项图标后方的浅主色胶囊以弹簧展开，图标与文字同步变色并轻微放大。
private struct EyeBottomBar: View {
    @Binding var selected: TopLevelTab
    var body: some View {
        HStack(spacing: 0) {
            ForEach(TopLevelTab.allCases, id: \.self) { tab in
                let active = tab == selected
                Button { selected = tab } label: {
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

/// 页面骨架：浅色背景、左右 20 内边距、竖向滚动。
private struct PageScroll<Content: View>: View {
    @ViewBuilder let content: Content
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: EyeStyle.spacing) { content }
                .padding(.horizontal, 20).padding(.bottom, 24)
        }.background(EyeStyle.background)
    }
}

/// 分区卡片：白底圆角面板 + 细边框 + 可选标题（安卓 EyeSectionCard）。
private struct SectionCard<Content: View>: View {
    var title: String?
    @ViewBuilder let content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let title { Text(title).font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text) }
            content
        }.padding(16).frame(maxWidth: .infinity, alignment: .leading)
            .background(EyeStyle.panel, in: RoundedRectangle(cornerRadius: 22))
            .overlay { RoundedRectangle(cornerRadius: 22).stroke(EyeStyle.borderSubtle, lineWidth: 1) }
    }
}

/// 入口卡：浅主色圆角底上的图标 + 标题 / 副标题 + 右箭头，最小高 80（安卓 EyeSelectCard）。
private struct SelectCard<Leading: View>: View {
    let title: String
    var subtitle: String?
    @ViewBuilder let leading: Leading
    let action: () -> Void
    var body: some View {
        Button(action: action) {
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
        }.buttonStyle(PressScaleStyle())
    }
}

private struct PressScaleStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.scaleEffect(configuration.isPressed ? 0.98 : 1).animation(EyeStyle.pressSpring, value: configuration.isPressed)
    }
}

/// 整行可点的开关行（安卓 toggleable Row + 仅展示的 EyeSwitch）。
private struct SwitchRow: View {
    let title: String
    let detail: String
    @Binding var isOn: Bool
    var body: some View {
        Toggle(isOn: $isOn) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(EyeStyle.body).foregroundStyle(EyeStyle.text)
                Text(detail).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
            }
        }.tint(EyeStyle.primary).frame(minHeight: 48)
    }
}

// MARK: 首页

/// 首页：品牌顶栏（右上角系统设置）+ 实时眼部检测视窗 + 护眼设置（休息提醒），与安卓同一顺序。
private struct HomePage: View {
    let openSettings: () -> Void
    @StateObject private var reminder = EyeReminder()
    private let today: String = {
        let formatter = DateFormatter(); formatter.locale = Locale(identifier: "zh_CN"); formatter.dateFormat = "M月d日 EEEE"
        return formatter.string(from: Date())
    }()
    var body: some View {
        PageScroll {
            HStack(spacing: 12) {
                BrandMarkView().frame(width: 44, height: 44)
                VStack(alignment: .leading, spacing: 2) {
                    Text("眼健康助手").font(EyeStyle.headline).foregroundStyle(EyeStyle.text).minimumScaleFactor(0.7).lineLimit(1)
                    Text(today).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                }.frame(maxWidth: .infinity, alignment: .leading)
                SettingsGear(action: openSettings)
            }.padding(.vertical, 16)
            EyeLiveCard()
            SectionCard(title: "护眼设置") {
                SwitchRow(title: "休息提醒",
                    detail: reminder.enabled ? "每 \(reminder.intervalMinutes) 分钟提醒一次" : "按间隔提醒你远眺放松",
                    isOn: Binding(get: { reminder.enabled }, set: { reminder.setEnabled($0) }))
                IntervalPicker(selected: reminder.intervalMinutes) { reminder.setInterval($0) }
                if let notice = reminder.notice { Text(notice).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.warning) }
            }
        }
    }
}

/// 分段选择器：浅色轨道 + 主色滑块弹簧滑动（安卓 EyeSegmentedRow）。
private struct IntervalPicker: View {
    let selected: Int
    let onSelect: (Int) -> Void
    private let options = [20, 30, 60]
    var body: some View {
        GeometryReader { geometry in
            let width = (geometry.size.width - 8) / CGFloat(options.count)
            let index = CGFloat(options.firstIndex(of: selected) ?? 0)
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: 12).fill(EyeStyle.primary).frame(width: width).offset(x: width * index)
                    .animation(.spring(response: 0.35, dampingFraction: 0.8), value: selected)
                HStack(spacing: 0) {
                    ForEach(options, id: \.self) { minutes in
                        Button { onSelect(minutes) } label: {
                            Text("\(minutes)分钟").font(EyeStyle.body)
                                .foregroundStyle(minutes == selected ? EyeStyle.panel : EyeStyle.text)
                                .frame(maxWidth: .infinity, maxHeight: .infinity).contentShape(Rectangle())
                        }.buttonStyle(.plain)
                    }
                }
            }.padding(4)
        }.frame(height: 52).background(EyeStyle.surfaceVariant, in: RoundedRectangle(cornerRadius: EyeStyle.radiusMedium))
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

/// 服务页：标题（右上角系统设置）+ 品牌欢迎卡 + 三个自测入口（顺序、文案、图标与安卓一致）。
private struct ServicesPage: View {
    let open: (String) -> Void
    let openSettings: () -> Void
    var body: some View {
        PageScroll {
            PageHeader(title: "服务", openSettings: openSettings)
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

/// 欢迎卡：小瞳待机，偶尔眨眼、左右张望（5.2 秒一轮，与安卓相同时间点）；减弱动态效果时静止。
private struct MascotHeroCard: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        HStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 6) {
                Text("我是小瞳").font(EyeStyle.title).foregroundStyle(EyeStyle.text)
                Text("每项测试前，我会先示范一遍怎么做。跟着做就好。").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
            }.frame(maxWidth: .infinity, alignment: .leading)
            TimelineView(.animation(paused: reduceMotion)) { timeline in
                let p = reduceMotion ? 0 : timeline.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 5.2) / 5.2
                MascotView(gazeX: Self.gaze(p), lid: Self.blink(p)).frame(width: 112, height: 112)
            }
        }.padding(.leading, 20).padding(.trailing, 8).padding(.vertical, 16)
            .background(EyeStyle.primarySoft, in: RoundedRectangle(cornerRadius: 22))
    }
    private static func blink(_ p: Double) -> CGFloat { CGFloat(max(1 - abs(p - 0.3) / 0.025, 1 - abs(p - 0.36) / 0.025, 0)) }
    private static func gaze(_ p: Double) -> CGFloat {
        switch p {
        case ..<0.5: 0
        case ..<0.65: CGFloat(-(p - 0.5) / 0.15)
        case ..<0.8: CGFloat(-1 + (p - 0.65) / 0.15 * 2)
        case ..<0.9: CGFloat(1 - (p - 0.8) / 0.1)
        default: 0
        }
    }
}

// MARK: 我的

//// 我的：家庭成员、测试记录、云端同步、权限与通知、关于与免责声明（与安卓同一顺序与文案）。
private struct MinePage: View {
    let openSettings: () -> Void
    @State private var showRecords = false
    @State private var recordCount = 0
    private let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
    var body: some View {
        PageScroll {
            PageHeader(title: "我的", openSettings: openSettings)
            SubjectsSection()
            SelectCard(title: "测试记录", subtitle: recordCount == 0 ? "暂无记录" : "共 \(recordCount) 条") {
                Image(systemName: "doc.text").font(.system(size: 22)).foregroundStyle(EyeStyle.primary)
            } action: { EyeHaptics.tap(); showRecords = true }
            CloudSection()
            SelectCard(title: "权限与通知") {
                Image(systemName: "checkmark.shield").font(.system(size: 22)).foregroundStyle(EyeStyle.primary)
            } action: {
                EyeHaptics.tap()
                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            }
            SectionCard {
                HStack(spacing: 12) {
                    BrandMarkView().frame(width: 44, height: 44)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("眼健康助手").font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text)
                        Text("守护每一次注视").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                    }
                }.padding(.bottom, 4)
                Text(((version.isEmpty ? [] : ["版本 \(version)"]) + ["结果仅供参考，不能替代眼科检查"]).joined(separator: " · "))
                    .font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
            }
        }.sheet(isPresented: $showRecords, onDismiss: recount) { RecordsView() }
            .onAppear(perform: recount)
    }

    private func recount() {
        recordCount = ((try? FileManager.default.contentsOfDirectory(atPath: EyeRuntime.recordsDirectory.path)) ?? [])
            .filter { $0.hasSuffix(".json") }.count
    }
}

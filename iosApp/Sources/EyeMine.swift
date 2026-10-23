import AVFoundation
import Speech
import SwiftUI
import UserNotifications
import EyeScreening

/// 「我的」入口页：个人卡（会动的小瞳 + 本人称呼与档案完善度 + 账号一行），三格概览，
/// 分组入口（健康档案 / 数据与隐私 / 通用）点进二级完整页面；不在本页堆放开关与按钮。与安卓 MineScreen 同一排版与文案。
struct MinePage: View {
    let navigate: (AppRoute) -> Void
    @ObservedObject private var store = SubjectStore.shared
    @ObservedObject private var cloud = CloudModel.shared
    @State private var recordCount = 0

    private var me: SubjectProfileItem? { store.subjects.first { $0.relation == "SELF" } }
    private var signedIn: Bool { cloud.bool("signedIn") }
    private var cloudSummary: String {
        !cloud.bool("configured") ? "仅本机" : !signedIn ? "未登录" : cloud.bool("healthDataSync") ? "已开启" : "未开启"
    }

    var body: some View {
        PageScroll {
            PageHeader(title: "我的", openAccount: { navigate(.account) }, openSettings: { navigate(.settings) })
            profileCard
            HStack(spacing: 10) {
                stat("测试记录", "\(recordCount)", "条") { navigate(.records) }
                stat("家庭成员", "\(store.subjects.count)", "人") { navigate(.subjects) }
                stat("已上传", "\(cloud.int("synced"))", "条") { navigate(.cloud) }
            }
            ListGroup(title: "健康档案") {
                ListRow(title: "测试记录", glyph: .document, value: recordCount == 0 ? "暂无" : "\(recordCount) 条") { navigate(.records) }
                ListRow(title: "家庭成员与资料", glyph: .family, tone: .accent, value: "\(store.subjects.count) 人") { navigate(.subjects) }
                ListRow(title: "用眼趋势报告", glyph: .chart, tone: .success, divider: false) { navigate(.trend) }
            }
            ListGroup(title: "数据与隐私") {
                ListRow(title: "云端同步", glyph: .cloud, tone: .blue, value: cloudSummary) { navigate(.cloud) }
                ListRow(title: "权限与通知", glyph: .shield, tone: .success, divider: false) { navigate(.permissions) }
            }
            ListGroup(title: "通用") {
                ListRow(title: "系统设置", glyph: .gear, tone: .neutral, divider: false) { navigate(.settings) }
            }
        }.onAppear(perform: recount)
    }

    private var profileCard: some View {
        let complete = completeness(me)
        let age = me.flatMap { SubjectProfiles.shared.age(fields: $0.fields, nowMs: Int64(Date().timeIntervalSince1970 * 1000))?.intValue }
        return VStack(spacing: 0) {
            Button { EyeHaptics.tap(); navigate(.subjectEdit(me?.id)) } label: {
                HStack(spacing: 4) {
                    MascotStage(mood: .happy, headScale: 0.7).frame(width: 104, height: 104)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(me?.displayName ?? "我").font(EyeStyle.title).foregroundStyle(EyeStyle.text)
                        Text(([age.map { "\($0) 岁" }, complete >= 5 ? "档案已完善" : "档案完善 \(complete)/5"] as [String?]).compactMap { $0 }.joined(separator: " · "))
                            .font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                    Image(systemName: "chevron.right").font(.system(size: 14, weight: .semibold)).foregroundStyle(EyeStyle.secondary)
                }.padding(.leading, 8).padding(.trailing, 16).padding(.top, 8).padding(.bottom, 4).contentShape(Rectangle())
            }.buttonStyle(RowPressStyle())
            Rectangle().fill(EyeStyle.borderSubtle).frame(height: 1).padding(.horizontal, 16)
            Button { EyeHaptics.tap(); navigate(.account) } label: {
                HStack {
                    Text(signedIn ? cloud.string("email") : "未登录").font(EyeStyle.body)
                        .foregroundStyle(signedIn ? EyeStyle.text : EyeStyle.secondary).lineLimit(1)
                    Spacer()
                    Text(signedIn ? "账户" : "登录 / 注册").font(EyeStyle.body).foregroundStyle(EyeStyle.primary)
                }.padding(.horizontal, 20).padding(.vertical, 14).contentShape(Rectangle())
            }.buttonStyle(RowPressStyle())
        }
        .background(LinearGradient(colors: [EyeStyle.primarySoft, EyeStyle.panel], startPoint: .topLeading, endPoint: .bottomTrailing))
        .clipShape(RoundedRectangle(cornerRadius: 22))
    }

    private func stat(_ label: String, _ value: String, _ unit: String, _ action: @escaping () -> Void) -> some View {
        Button { EyeHaptics.tap(); action() } label: {
            VStack(alignment: .leading, spacing: 2) {
                HStack(alignment: .firstTextBaseline, spacing: 3) {
                    Text(value).font(EyeStyle.title).foregroundStyle(EyeStyle.text).monospacedDigit()
                    Text(unit).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                }
                Text(label).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
            }.padding(.horizontal, 14).padding(.vertical, 12).frame(maxWidth: .infinity, alignment: .leading)
                .background(EyeStyle.panel, in: RoundedRectangle(cornerRadius: EyeStyle.radiusLarge))
        }.buttonStyle(PressScale(scale: 0.97))
    }

    /// 本人资料的完善程度（年龄、职业、视力、眼病史、性别）：已填项数 / 5（与安卓 profileCompleteness 一致）。
    private func completeness(_ s: SubjectProfileItem?) -> Int {
        guard let f = s?.fields else { return 0 }
        let vision = f["基础视力"].flatMap { $0.isEmpty ? nil : $0 } ?? f["日常矫正方式"]
        return [f["出生年份"], f["职业"], vision, f["既往眼病"], f["性别"]].filter { !($0 ?? "").isEmpty }.count
    }

    private func recount() {
        recordCount = ((try? FileManager.default.contentsOfDirectory(atPath: EyeRuntime.recordsDirectory.path)) ?? [])
            .filter { $0.hasSuffix(".json") }.count
    }
}

/// 「我的 → 权限与通知」：逐项显示用途与是否已允许，点一下去系统设置；回到本页自动刷新。
struct PermissionsPage: View {
    @Environment(\.scenePhase) private var scenePhase
    @State private var camera = false
    @State private var microphone = false
    @State private var speech = false
    @State private var notifications = false

    var body: some View {
        SubPage(title: "权限与通知") {
            ListGroup(title: "测试与检测") {
                row("相机", .camera, .primary, "检测眼部关键点、眨眼与距离", camera)
                row("麦克风", .mic, .success, "测试中用语音作答", microphone)
                row("语音识别", .chat, .success, "把「上、下、左、右」转成答案", speech, divider: false)
            }
            ListGroup(title: "主动护眼") {
                row("通知", .bell, .accent, "休息提醒", notifications, divider: false, notificationsPage: true)
            }
            ListGroup {
                ListRow(title: "打开系统设置", glyph: .gear, tone: .neutral, divider: false) { open(false) }
            }
        }
        .onAppear(perform: refresh)
        .onChange(of: scenePhase) { if $0 == .active { refresh() } }
    }

    private func row(_ title: String, _ glyph: EyeGlyph, _ tone: TileTone, _ subtitle: String, _ granted: Bool,
                     divider: Bool = true, notificationsPage: Bool = false) -> some View {
        ListRow(title: title, glyph: glyph, tone: tone, subtitle: subtitle, value: granted ? "已允许" : "未允许",
                valueColor: granted ? EyeStyle.success : EyeStyle.warning, divider: divider) { open(notificationsPage) }
    }

    private func open(_ notificationsPage: Bool) {
        let target: String
        if notificationsPage, #available(iOS 16.0, *) { target = UIApplication.openNotificationSettingsURLString }
        else { target = UIApplication.openSettingsURLString }
        if let url = URL(string: target) { UIApplication.shared.open(url) }
    }

    private func refresh() {
        camera = AVCaptureDevice.authorizationStatus(for: .video) == .authorized
        microphone = AVAudioSession.sharedInstance().recordPermission == .granted
        speech = SFSpeechRecognizer.authorizationStatus() == .authorized
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            let allowed = settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional
            DispatchQueue.main.async { notifications = allowed }
        }
    }
}

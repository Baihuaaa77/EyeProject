import SwiftUI
import Security
import UniformTypeIdentifiers
import EyeScreening

/// 苹果端云端同步：规则全部在共用的 CloudClient（shared/sync），这里只实现网络、钥匙串与文件三个平台端口，
/// 以及「账户」「云端同步」两个完整页面，与安卓同一套接口、同意与同步规则。
final class CloudModel: ObservableObject {
    static let shared = CloudModel()
    @Published private(set) var state: [String: Any] = [:]
    private(set) lazy var client: CloudClient = IosCloudClientKt.makeIosCloudClient(
        transport: CloudTransport(), platform: CloudStorage()) { [weak self] in self?.refresh() }

    // 首次访问可能来自保存记录的后台线程：状态只在主线程更新
    private init() { DispatchQueue.main.async { self.refresh() } }

    func refresh() { explanations = [:]; advices = [:]; chatState = nil; state = EyeRuntime.object(client.snapshotJson()) }
    func string(_ key: String) -> String { state[key] as? String ?? "" }
    func bool(_ key: String) -> Bool { state[key] as? Bool ?? false }
    func int(_ key: String) -> Int { (state[key] as? NSNumber)?.intValue ?? 0 }

    /// 测试记录已写入本机：登记待上传（满足条件时自动上传）。
    func recordSaved(_ fileName: String) { DispatchQueue.main.async { self.client.recordSaved(fileName: fileName) } }
    /// 成员资料在本机改动后排队同步。
    func subjectsChanged() { DispatchQueue.main.async { self.client.subjectsChanged() } }

    /// 一条本机记录的 AI 解读状态；见 CloudClient.explanationJson。按文件缓存到下次 [refresh]
    /// （卡片每次重绘要读十几个字段，逐个重建 JSON 会白占主线程）。
    private var explanations: [String: [String: Any]] = [:]
    func explanation(_ fileName: String) -> [String: Any] {
        if let cached = explanations[fileName] { return cached }
        let value = EyeRuntime.object(client.explanationJson(fileName: fileName))
        explanations[fileName] = value
        return value
    }
    /// 生成解读；agreeFirst 为卡片内点了「同意并生成」。
    func explain(_ fileName: String, agreeFirst: Bool) { client.explain(fileName: fileName, agreeFirst: agreeFirst) }

    /// AI 综合建议的状态（[requestJson] 为共用 EyeCareAdvice 抽取的概况）；见 CloudClient.adviceJson，缓存到下次 [refresh]。
    private var advices: [String: [String: Any]] = [:]
    func advice(_ requestJson: String) -> [String: Any] {
        if let cached = advices[requestJson] { return cached }
        let value = EyeRuntime.object(client.adviceJson(requestJson: requestJson))
        advices[requestJson] = value
        return value
    }
    /// 生成综合建议；agreeFirst 为卡片内点了「同意并生成」。
    func advise(_ requestJson: String, agreeFirst: Bool) { client.advise(requestJson: requestJson, agreeFirst: agreeFirst) }

    /// 首页快捷提问带进对话页的草稿
    @Published var chatDraft = ""
    /// 对话页状态；见 CloudClient.chatJson，缓存到下次 [refresh]。
    private var chatState: [String: Any]?
    func chat() -> [String: Any] {
        if let cached = chatState { return cached }
        let value = EyeRuntime.object(client.chatJson())
        chatState = value
        return value
    }
    /// 发一条消息：带上当下的去标识概况（与综合建议同一份），已生成的综合建议作为开场。
    func sendChat(_ text: String, agreeFirst: Bool) {
        client.sendChat(text: text, adviceRequestJson: EyeCareStore.shared.adviceRequestJson, agreeFirst: agreeFirst)
    }
    func clearChat() { client.clearChat() }
}

/// URLSession 实现的一次 HTTP 往返；请求体与令牌不写日志。
final class CloudTransport: NSObject, HttpTransport {
    func send(method: String, url: String, headers: [String: String], body: String?, callback: HttpCallback) {
        guard let target = URL(string: url) else { callback.onFailure(message: "服务器地址无效"); return }
        var request = URLRequest(url: target, timeoutInterval: 20)
        request.httpMethod = method
        headers.forEach { request.setValue($0.value, forHTTPHeaderField: $0.key) }
        request.httpBody = body?.data(using: .utf8)
        URLSession.shared.dataTask(with: request) { data, response, error in
            if let error { callback.onFailure(message: error.localizedDescription); return }
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            callback.onResponse(status: Int32(status), body: data.flatMap { String(data: $0, encoding: .utf8) } ?? "")
        }.resume()
    }
}

/// 登录会话存钥匙串（仅本机、解锁后可用）；发件箱、成员同步簿记与服务器地址存应用支持目录（不在「文件」App 中显示）。
private final class CloudStorage: NSObject, CloudPlatform {
    private let service = "com.zkyjss.eyeproject.cloud"
    private let directory: URL = {
        let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("cloud", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }()

    func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    func readSecret(key: String) -> String? {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                    kSecAttrAccount as String: key, kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess, let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func writeSecret(key: String, value: String?) {
        let match: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: key]
        SecItemDelete(match as CFDictionary)
        guard let value, let data = value.data(using: .utf8) else { return }
        var add = match
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }

    private func file(_ key: String) -> URL { directory.appendingPathComponent(key + ".json") }
    func readValue(key: String) -> String? { try? String(contentsOf: file(key), encoding: .utf8) }
    func writeValue(key: String, value: String?) {
        if let value { try? value.data(using: .utf8)?.write(to: file(key), options: [.atomic, .completeFileProtection]) }
        else { try? FileManager.default.removeItem(at: file(key)) }
    }

    func recordFileNames() -> [String] {
        ((try? FileManager.default.contentsOfDirectory(atPath: EyeRuntime.recordsDirectory.path)) ?? []).filter { $0.hasSuffix(".json") }
    }
    func readRecord(fileName: String) -> String? {
        try? String(contentsOf: EyeRuntime.recordsDirectory.appendingPathComponent(fileName), encoding: .utf8)
    }
    func subjectsJson() -> String { SubjectStore.shared.jsonText }
    func upsertSubjectJson(json: String) { SubjectStore.shared.upsertFromCloud(json) }
    func removeSubject(id: String) { SubjectStore.shared.removeFromCloud(id) }
}

// MARK: - 账户页

/// 隐私要点（注册前的问号说明与云端同步页共用，两端同一文案）；正式隐私政策文本待法务提供后替换。
enum CloudTexts {
    static let dataHandling = [
        "上传测试记录与成员资料，不收集身份证号、照片或视频。",
        "传输加密，云端以独立密钥加密保存，查看、导出、删除都有审计记录。",
        "可随时关闭上传、导出全部云端数据，或注销账号删除云端数据；本机记录始终保留。",
        "未满 14 岁孩子的数据需监护人同意才上传；研究用途需单独同意。",
        "AI 功能需单独同意：只发送去标识的结果与概况，由第三方大模型生成文字，风险等级仍由测试规则决定。",
    ]
    static let server = "默认是课题组的服务器，一般不用改。局域网试用时可填电脑上运行的服务地址，如 http://192.168.1.5:8080；清空即只在本机保存。更换地址会退出登录。"
    static let sync = "把测试记录与家庭成员资料加密保存到云端，换手机或多台设备时可以找回。关闭后不再上传，已上传的数据可在下方导出或注销账号删除。"
    static let guardian = "未满 14 岁孩子的测试记录，需要监护人同意后才会上传。"
    static let research = "可选。同意后，去掉姓名、账号等身份信息的数据可用于改进眼健康研究；不同意不影响任何功能。"
    static let ai = "生成 AI 解读时，把这次结果的等级、指标、判断依据、年龄段和几项相关资料选项发给第三方大模型（DeepSeek），不发送姓名、账号、照片或视频；风险等级仍由测试规则决定。"
    static let advice = "生成 AI 综合建议时，把年龄段、职业、戴镜方式、既往眼病选项，实时检测的眨眼与观看距离，屏幕亮度等级，三项自测最近的等级，今天的用眼负荷与主要原因，以及你记录的感受发给第三方大模型（DeepSeek）；不发送姓名、账号、具体日期、照片或视频。"
    static let chat = "和小瞳聊天时，把你输入的文字、最近几轮对话，以及综合建议用到的去标识用眼概况发给第三方大模型（DeepSeek）回答；服务器不保存对话内容，记录只在这台手机上；不发送姓名、账号、照片或视频。"
}

/// 账户（右上角人像按钮进入的完整页面）：账号概况、登录 / 注册、退出登录、注销账号与服务器地址。
/// 分项同意、立即同步与导出在「我的 → 云端同步」。与安卓 AccountScreen 同一排版与文案。
struct AccountPage: View {
    @ObservedObject private var cloud = CloudModel.shared
    let openCloudSync: () -> Void
    @State private var confirmDelete = false
    private var busy: Bool { cloud.bool("busy") }
    private var message: String { cloud.string("message") }

    var body: some View {
        SubPage(title: "账户") {
            header
            if !cloud.bool("configured") {
                ServerCard(cloud: cloud, emphasize: true)
                if !message.isEmpty { note(message) }
            } else if !cloud.bool("signedIn") {
                SignInForm()
                ServerCard(cloud: cloud, emphasize: false)
            } else {
                signedIn
            }
        }
        .onChange(of: cloud.int("outcomeId")) { _ in if cloud.bool("outcomeOk") { EyeHaptics.success() } else { EyeHaptics.failure() } }
        .onChange(of: confirmDelete) { if $0 { EyeHaptics.failure() } }
    }

    private var header: some View {
        let signedIn = cloud.bool("signedIn")
        let address = cloud.string("email")
        let (label, color): (String, Color) = !cloud.bool("configured") ? ("未连接服务器", EyeStyle.secondary)
            : !signedIn ? ("数据只在本机", EyeStyle.secondary)
            : cloud.bool("healthDataSync") ? ("云端同步已开启", EyeStyle.success) : ("已登录 · 仅本机", EyeStyle.secondary)
        return HStack(spacing: 16) {
            ZStack {
                Circle().fill(EyeStyle.panel)
                if signedIn && !address.isEmpty {
                    Text(String(address.prefix(1)).uppercased()).font(EyeStyle.title).foregroundStyle(EyeStyle.primary)
                } else {
                    EyeGlyphView(glyph: .person).frame(width: 30, height: 30)
                }
            }.frame(width: 60, height: 60)
            VStack(alignment: .leading, spacing: 6) {
                Text(signedIn ? address : "未登录").font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text).lineLimit(1)
                StatusChip(text: label, color: color)
            }
            Spacer(minLength: 0)
        }
        .padding(20).frame(maxWidth: .infinity, alignment: .leading)
        .background(LinearGradient(colors: [EyeStyle.primarySoft, EyeStyle.panel], startPoint: .topLeading, endPoint: .bottomTrailing),
                    in: RoundedRectangle(cornerRadius: 22))
    }

    private var signedIn: some View {
        VStack(alignment: .leading, spacing: 12) {
            ListGroup(title: "账号") {
                ListRow(title: "邮箱", glyph: .person, value: cloud.string("email"))
                ListRow(title: "云端同步", glyph: .cloud, tone: .blue, value: cloud.bool("healthDataSync") ? "已开启" : "未开启",
                        divider: false, action: openCloudSync)
            }
            if !message.isEmpty { note(message) }
            ListGroup {
                // 退出登录随时可点：立即清本机会话，不等同步或服务器
                ListRow(title: "退出登录", glyph: .logout, tone: .neutral) { cloud.client.signOut() }
                ListRow(title: confirmDelete ? "确认注销账号" : "注销账号", glyph: .trash, tone: .danger,
                        subtitle: confirmDelete ? "将永久删除云端账号与全部已上传数据，本机记录保留" : nil,
                        titleColor: EyeStyle.danger, divider: false, enabled: !busy) {
                    if confirmDelete { confirmDelete = false; cloud.client.deleteCloudAccount() } else { confirmDelete = true }
                }
            }
            ListGroup(title: "服务器") {
                ListRow(title: "服务器地址", glyph: .server, tone: .neutral, value: cloud.string("serverUrl"), divider: false)
            }
        }
    }

    private func note(_ text: String) -> some View {
        Text(text).font(EyeStyle.body).foregroundStyle(EyeStyle.secondary).padding(.horizontal, 4)
    }
}

/// 登录 / 注册表单：账户页与启动时的登录引导共用（与安卓 SignInForm 一致）。
struct SignInForm: View {
    @ObservedObject private var cloud = CloudModel.shared
    @State private var mode = 0
    @State private var email = ""
    @State private var password = ""
    @State private var agreed = false
    private var busy: Bool { cloud.bool("busy") }
    private var message: String { cloud.string("message") }

    var body: some View {
        Panel(padding: 20) {
            SegmentedPicker(options: [(0, "登录"), (1, "注册")], selected: mode) { mode = $0 }
            field("邮箱") {
                TextField("name@example.com", text: $email).keyboardType(.emailAddress).textInputAutocapitalization(.never).autocorrectionDisabled()
            }
            field("密码") { SecureField(mode == 1 ? "至少 8 位，含字母和数字" : "", text: $password) }
            if mode == 1 {
                HStack(spacing: 0) {
                    Text("同意隐私政策").font(EyeStyle.body).foregroundStyle(EyeStyle.text)
                    HelpButton(title: "隐私政策要点", text: CloudTexts.dataHandling.joined(separator: "\n"))
                    Spacer()
                    Toggle("", isOn: Binding(get: { agreed }, set: { EyeHaptics.toggle(); agreed = $0 })).labelsHidden().tint(EyeStyle.primary)
                }.frame(minHeight: 48)
            }
            if !message.isEmpty { note(message) }
            if mode == 0 {
                Button("登录") { EyeHaptics.tap(); cloud.client.signIn(email: email.trimmingCharacters(in: .whitespaces), password: password) }
                    .buttonStyle(EyeButtonStyle()).disabled(busy || email.isEmpty || password.isEmpty)
            } else {
                Button("注册") { EyeHaptics.tap(); cloud.client.register(email: email.trimmingCharacters(in: .whitespaces), password: password) }
                    .buttonStyle(EyeButtonStyle()).disabled(busy || !agreed || email.isEmpty || password.isEmpty)
            }
        }
    }


    private func field<Content: View>(_ label: String, @ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
            content().font(EyeStyle.body).padding(.horizontal, 14).frame(minHeight: 48)
                .background(EyeStyle.surfaceVariant, in: RoundedRectangle(cornerRadius: EyeStyle.radiusMedium))
                .overlay { RoundedRectangle(cornerRadius: EyeStyle.radiusMedium).stroke(EyeStyle.borderSubtle, lineWidth: 1) }
        }
    }

    private func note(_ text: String) -> some View {
        Text(text).font(EyeStyle.body).foregroundStyle(EyeStyle.secondary).padding(.horizontal, 4)
    }
}

/// 服务器地址：未登录时可修改（更换会退出登录）。
private struct ServerCard: View {
    @ObservedObject var cloud: CloudModel
    let emphasize: Bool
    @State private var text = CloudModel.shared.string("serverUrl")
    var body: some View {
        let busy = cloud.bool("busy")
        // 与当前生效地址相同时显示「已保存」，改动后才可点「保存」（与安卓一致）
        let saved = text.trimmingCharacters(in: .whitespaces) == cloud.string("serverUrl")
        Panel(padding: 20) {
            HStack(spacing: 0) {
                Text("服务器").font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text)
                HelpButton(title: "服务器地址", text: CloudTexts.server)
            }
            if emphasize { Text("连接服务器后才能登录；不连接时数据只保存在本机。").font(EyeStyle.body).foregroundStyle(EyeStyle.secondary) }
            TextField("http://192.168.1.5:8080", text: $text)
                .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                .font(EyeStyle.body).padding(.horizontal, 14).frame(minHeight: 48)
                .background(EyeStyle.surfaceVariant, in: RoundedRectangle(cornerRadius: EyeStyle.radiusMedium))
                .overlay { RoundedRectangle(cornerRadius: EyeStyle.radiusMedium).stroke(EyeStyle.borderSubtle, lineWidth: 1) }
            Button(saved && !text.isEmpty ? "已保存" : "保存") { EyeHaptics.tap(); _ = cloud.client.setServer(url: text) }
                .buttonStyle(EyeButtonStyle(role: emphasize ? .primary : .secondary)).disabled(busy || saved)
        }
    }
}

// MARK: - 云端同步页

/// 「我的 → 云端同步」完整页面：同步状态与立即同步、分项同意（每项旁边问号说明）、导出云端数据、数据如何处理。
/// 登录、退出与注销在右上角的账户页。与安卓 CloudSyncScreen 同一排版与文案。
struct CloudSyncPage: View {
    @ObservedObject private var cloud = CloudModel.shared
    let openAccount: () -> Void
    @State private var exportText: String?
    @State private var exporting = false
    @State private var handlingOpen = false
    private var busy: Bool { cloud.bool("busy") }

    var body: some View {
        SubPage(title: "云端同步") {
            if !cloud.bool("configured") {
                prompt("还没有连接服务器", "数据目前只保存在本机。在账户页填写服务器地址并登录后，即可开启云端同步。")
            } else if !cloud.bool("signedIn") {
                prompt("登录后开启云端同步", "测试记录和家人资料可以在多台设备之间同步，本机记录始终保留。")
            } else {
                status
                ListGroup(title: "授权") {
                    consent("云端保存记录与资料", "HEALTH_DATA_SYNC", "healthDataSync", .cloud, .blue, CloudTexts.sync)
                    consent("监护人同意", "GUARDIAN", "guardian", .family, .primary, CloudTexts.guardian)
                    consent("用于研究", "RESEARCH_USE", "researchUse", .chart, .success, CloudTexts.research)
                    consent("AI 解读", "AI_EXPLANATION", "aiExplanation", .bulb, .accent, CloudTexts.ai)
                    consent("AI 综合建议", "AI_ADVICE", "aiAdvice", .chat, .blue, CloudTexts.advice)
                    consent("AI 对话", "AI_CHAT", "aiChat", .chat, .primary, CloudTexts.chat, divider: false)
                }
                ListGroup(title: "数据") {
                    ListRow(title: "导出云端数据", glyph: .export, tone: .neutral, divider: false, enabled: !busy) {
                        cloud.client.exportCloudData { text in
                            DispatchQueue.main.async { if let text { exportText = text; exporting = true } }
                        }
                    }
                }
            }
            ListGroup {
                ListRow(title: "数据如何处理", glyph: .shield, tone: .success, value: handlingOpen ? "收起" : nil, divider: handlingOpen) {
                    withAnimation(.easeOut(duration: 0.2)) { handlingOpen.toggle() }
                }
                if handlingOpen {
                    VStack(alignment: .leading, spacing: 8) {
                        ForEach(CloudTexts.dataHandling, id: \.self) { Text("· " + $0).font(EyeStyle.body).foregroundStyle(EyeStyle.secondary) }
                    }.padding(.leading, 66).padding(.trailing, 16).padding(.top, 4).padding(.bottom, 16)
                }
            }
        }
        .onChange(of: cloud.int("outcomeId")) { _ in if cloud.bool("outcomeOk") { EyeHaptics.success() } else { EyeHaptics.failure() } }
        .fileExporter(isPresented: $exporting, document: CloudExportDocument(text: exportText ?? ""), contentType: .json,
                      defaultFilename: "eyeproject-cloud-export.json") { _ in exportText = nil }
    }

    private func prompt(_ title: String, _ text: String) -> some View {
        Panel(padding: 20) {
            IconTile(glyph: .cloud, tone: .blue, size: 48)
            Text(title).font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text)
            Text(text).font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
            Button("去登录") { EyeHaptics.tap(); openAccount() }.buttonStyle(EyeButtonStyle())
        }
    }

    private var status: some View {
        var parts: [String] = []
        if cloud.int("pending") > 0 { parts.append("待上传 \(cloud.int("pending")) 条") }
        if cloud.int("awaitingGuardian") > 0 { parts.append("\(cloud.int("awaitingGuardian")) 条待监护人同意") }
        if cloud.int("failed") > 0 { parts.append("\(cloud.int("failed")) 条未能上传") }
        if busy { parts.append("正在同步…") }
        let on = cloud.bool("healthDataSync")
        return Panel(padding: 20) {
            HStack(alignment: .center) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("已上传").font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
                    HStack(alignment: .firstTextBaseline, spacing: 4) {
                        Text("\(cloud.int("synced"))").font(EyeStyle.headline).foregroundStyle(EyeStyle.text).monospacedDigit()
                        Text("条记录").font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
                    }
                }
                Spacer()
                StatusChip(text: on ? "已开启" : "未开启", color: on ? EyeStyle.success : EyeStyle.secondary)
            }
            if !parts.isEmpty { Text(parts.joined(separator: " · ")).font(EyeStyle.body).foregroundStyle(EyeStyle.secondary) }
            if !cloud.string("message").isEmpty { Text(cloud.string("message")).font(EyeStyle.body).foregroundStyle(EyeStyle.secondary) }
            Button(busy ? "正在同步…" : "立即同步") { EyeHaptics.tap(); cloud.client.syncNow(userInitiated: true) }
                .buttonStyle(EyeButtonStyle()).disabled(busy || !on)
        }
    }

    private func consent(_ title: String, _ type: String, _ key: String, _ glyph: EyeGlyph, _ tone: TileTone, _ help: String,
                         divider: Bool = true) -> some View {
        SwitchRow(title: title, isOn: Binding(get: { cloud.bool(key) }, set: { cloud.client.setConsent(type: type, granted: $0) }),
                  glyph: glyph, tone: tone, help: help, divider: divider, enabled: !busy)
    }
}

// 云端导出经系统文件选择器保存。
private struct CloudExportDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    var text: String
    init(text: String) { self.text = text }
    init(configuration: ReadConfiguration) throws { text = String(data: configuration.file.regularFileContents ?? Data(), encoding: .utf8) ?? "" }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: Data(text.utf8)) }
}

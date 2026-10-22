import SwiftUI
import Security
import UniformTypeIdentifiers
import EyeScreening

/// 苹果端云端同步：规则全部在共用的 CloudClient（shared/sync），这里只实现网络、钥匙串与文件三个平台端口
/// 和「我的 → 云端同步」界面，与安卓同一套接口、同意与同步规则。
final class CloudModel: ObservableObject {
    static let shared = CloudModel()
    @Published private(set) var state: [String: Any] = [:]
    private(set) lazy var client: CloudClient = IosCloudClientKt.makeIosCloudClient(
        transport: CloudTransport(), platform: CloudStorage()) { [weak self] in self?.refresh() }

    // 首次访问可能来自保存记录的后台线程：状态只在主线程更新
    private init() { DispatchQueue.main.async { self.refresh() } }

    func refresh() { explanations = [:]; state = EyeRuntime.object(client.snapshotJson()) }
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

//// 「云端同步」：标题右侧一个状态徽标（未配置 / 未登录 / 已开启 / 仅本机），正文只放必要的输入与开关；
/// 数据处理说明收在「数据如何处理」里。与安卓 CloudSyncSection 同一排版与文案；所有上传以分项同意为前提。
struct CloudSection: View {
    @ObservedObject private var cloud = CloudModel.shared
    @State private var email = ""
    @State private var password = ""
    @State private var agreed = false
    @State private var confirmDelete = false
    @State private var moreOpen = false
    @State private var exportText: String?
    @State private var exporting = false
    private var busy: Bool { cloud.bool("busy") }
    private var message: String { cloud.string("message") }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("云端同步").font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text)
                Spacer()
                chip
            }
            if !cloud.bool("configured") {
                note("未连接服务器，数据只保存在本机。")
                ServerRow(cloud: cloud, busy: false)
                if !message.isEmpty { note(message) }
            } else if !cloud.bool("signedIn") {
                signedOut
            } else {
                signedIn
            }
        }.padding(16).frame(maxWidth: .infinity, alignment: .leading)
            .background(EyeStyle.panel, in: RoundedRectangle(cornerRadius: EyeStyle.radius))
            .animation(.easeOut(duration: 0.2), value: cloud.bool("signedIn"))
            // 每次操作结束：成功 / 失败各一种振动（序号递增，同一结果不重复触发），与安卓一致
            .onChange(of: cloud.int("outcomeId")) { _ in if cloud.bool("outcomeOk") { EyeHaptics.success() } else { EyeHaptics.failure() } }
            .onChange(of: confirmDelete) { if $0 { EyeHaptics.failure() } }
            .fileExporter(isPresented: $exporting, document: CloudExportDocument(text: exportText ?? ""), contentType: .json,
                          defaultFilename: "eyeproject-cloud-export.json") { _ in exportText = nil }
    }

    private var chip: some View {
        let (label, color): (String, Color) = !cloud.bool("configured") ? ("未配置", EyeStyle.secondary)
            : !cloud.bool("signedIn") ? ("未登录", EyeStyle.secondary)
            : cloud.bool("healthDataSync") ? ("已开启", EyeStyle.success) : ("仅本机", EyeStyle.secondary)
        return HStack(spacing: 6) {
            if busy { ProgressView().controlSize(.mini) }
            Text(label).font(EyeStyle.chip)
        }.foregroundStyle(color).padding(.horizontal, 10).padding(.vertical, 4).background(color.opacity(0.12), in: Capsule())
    }

    private var signedOut: some View {
        VStack(alignment: .leading, spacing: 12) {
            TextField("邮箱", text: $email).keyboardType(.emailAddress).textInputAutocapitalization(.never)
                .autocorrectionDisabled().textFieldStyle(.roundedBorder)
            SecureField("密码（至少 8 位，含字母和数字）", text: $password).textFieldStyle(.roundedBorder)
            toggleRow("同意隐私政策", isOn: $agreed)
            if !message.isEmpty { note(message) }
            HStack(spacing: 12) {
                Button("登录") { EyeHaptics.tap(); cloud.client.signIn(email: email, password: password) }.buttonStyle(EyeButtonStyle())
                    .disabled(busy || email.isEmpty || password.isEmpty)
                Button("注册") { EyeHaptics.tap(); cloud.client.register(email: email, password: password) }.buttonStyle(EyeButtonStyle(role: .secondary))
                    .disabled(busy || !agreed || email.isEmpty || password.isEmpty)
            }
            moreSettings(server: true)
        }
    }

    private var signedIn: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(cloud.string("email")).font(EyeStyle.body).foregroundStyle(EyeStyle.text)
            consent("云端保存测试记录与资料", "HEALTH_DATA_SYNC", "healthDataSync")
            consent("监护人同意（未满 14 岁）", "GUARDIAN", "guardian")
            consent("用于研究（可选，去标识）", "RESEARCH_USE", "researchUse")
            consent("AI 解读（去标识结果发给大模型）", "AI_EXPLANATION", "aiExplanation")
            note(statusLine)
            if !message.isEmpty { note(message) }
            HStack(spacing: 12) {
                Button("立即同步") { EyeHaptics.tap(); cloud.client.syncNow(userInitiated: true) }.buttonStyle(EyeButtonStyle())
                    .disabled(busy || !cloud.bool("healthDataSync"))
                Button("导出数据") {
                    EyeHaptics.tap()
                    cloud.client.exportCloudData { text in
                        DispatchQueue.main.async { if let text { exportText = text; exporting = true } }
                    }
                }.buttonStyle(EyeButtonStyle(role: .secondary)).disabled(busy)
            }
            HStack(spacing: 12) {
                Button("退出登录") { EyeHaptics.tap(); cloud.client.signOut() }.buttonStyle(EyeButtonStyle(role: .secondary)).disabled(busy)
                Button(confirmDelete ? "确认注销" : "注销账号") {
                    if confirmDelete { confirmDelete = false; cloud.client.deleteCloudAccount() } else { confirmDelete = true }
                }.buttonStyle(EyeButtonStyle(role: .danger)).disabled(busy)
            }
            if confirmDelete { note("将永久删除云端账号与全部已上传数据，本机记录保留。") }
            moreSettings(server: false)
        }
    }

    /// 收起的次要内容：数据如何处理、服务器地址（未登录时可改）。
    @ViewBuilder private func moreSettings(server: Bool) -> some View {
        Button { EyeHaptics.tap(); withAnimation(.easeOut(duration: 0.2)) { moreOpen.toggle() } } label: {
            Text(moreOpen ? "收起 ▴" : server ? "数据如何处理 · 服务器设置 ▾" : "数据如何处理 ▾").font(EyeStyle.body).foregroundStyle(EyeStyle.primary)
                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading).contentShape(Rectangle())
        }.buttonStyle(.plain)
        if moreOpen {
            ForEach(Self.dataHandling, id: \.self) { note("· " + $0) }
            if server { ServerRow(cloud: cloud, busy: busy) }
        }
    }

    /// 数据处理要点（两端同一文案）；正式隐私政策文本待法务提供后替换。
    static let dataHandling = [
        "上传测试记录与成员资料，不收集身份证号、照片或视频。",
        "传输加密，云端以独立密钥加密保存，查看、导出、删除都有审计记录。",
        "可随时关闭上传、导出全部云端数据，或注销账号删除云端数据；本机记录始终保留。",
        "未满 14 岁孩子的数据需监护人同意才上传；研究用途需单独同意。",
        "AI 解读需单独同意：只发送结果等级、指标、判断依据、年龄段和几项相关资料选项，由第三方大模型生成解读，风险等级仍由测试规则决定。",
    ]

    private var statusLine: String {
        var parts = ["已上传 \(cloud.int("synced")) 条"]
        if cloud.int("pending") > 0 { parts.append("待上传 \(cloud.int("pending")) 条") }
        if cloud.int("awaitingGuardian") > 0 { parts.append("\(cloud.int("awaitingGuardian")) 条待监护人同意") }
        if cloud.int("failed") > 0 { parts.append("\(cloud.int("failed")) 条未能上传") }
        if busy { parts.append("正在同步…") }
        return parts.joined(separator: " · ")
    }

    private func consent(_ title: String, _ type: String, _ key: String) -> some View {
        toggleRow(title, isOn: Binding(get: { cloud.bool(key) }, set: { cloud.client.setConsent(type: type, granted: $0) })).disabled(busy)
    }

    private func toggleRow(_ title: String, isOn: Binding<Bool>) -> some View {
        Toggle(title, isOn: Binding(get: { isOn.wrappedValue }, set: { EyeHaptics.toggle(); isOn.wrappedValue = $0 }))
            .font(EyeStyle.body).foregroundStyle(EyeStyle.text).tint(EyeStyle.primary).frame(minHeight: 48)
    }

    private func note(_ text: String) -> some View {
        Text(text).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary).frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// 服务器地址：局域网试用填电脑地址（如 http://192.168.1.5:8080），正式部署填 https 域名。
private struct ServerRow: View {
    @ObservedObject var cloud: CloudModel
    let busy: Bool
    @State private var text = CloudModel.shared.string("serverUrl")
    var body: some View {
        HStack(spacing: 8) {
            TextField("服务器地址，如 http://192.168.1.5:8080", text: $text)
                .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled().textFieldStyle(.roundedBorder)
            Button("保存") { EyeHaptics.tap(); _ = cloud.client.setServer(url: text) }
                .font(EyeStyle.button).foregroundStyle(EyeStyle.primary).frame(minWidth: 56, minHeight: 44)
                .disabled(busy || text.trimmingCharacters(in: .whitespaces) == cloud.string("serverUrl"))
        }
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

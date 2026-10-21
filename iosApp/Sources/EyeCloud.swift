import SwiftUI
import Security
import UniformTypeIdentifiers
import EyeScreening

/// 苹果端云端同步：规则全部在共用的 CloudClient（shared/sync），这里只实现网络、钥匙串与文件三个平台端口
/// 和「我的 → 云端同步与数据」界面，与安卓同一套接口、同意与同步规则。
final class CloudModel: ObservableObject {
    static let shared = CloudModel()
    @Published private(set) var state: [String: Any] = [:]
    private(set) lazy var client: CloudClient = IosCloudClientKt.makeIosCloudClient(
        transport: CloudTransport(), platform: CloudStorage()) { [weak self] in self?.refresh() }

    // 首次访问可能来自保存记录的后台线程：状态只在主线程更新
    private init() { DispatchQueue.main.async { self.refresh() } }

    func refresh() { state = EyeRuntime.object(client.snapshotJson()) }
    func string(_ key: String) -> String { state[key] as? String ?? "" }
    func bool(_ key: String) -> Bool { state[key] as? Bool ?? false }
    func int(_ key: String) -> Int { (state[key] as? NSNumber)?.intValue ?? 0 }

    /// 测试记录已写入本机：登记待上传（满足条件时自动上传）。
    func recordSaved(_ fileName: String) { DispatchQueue.main.async { self.client.recordSaved(fileName: fileName) } }
    /// 成员资料在本机改动后排队同步。
    func subjectsChanged() { DispatchQueue.main.async { self.client.subjectsChanged() } }
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

/// 「我的 → 云端同步与数据」：未配置 / 未登录 / 已登录三态，文案与安卓一致；所有上传以分项同意为前提。
struct CloudSection: View {
    @ObservedObject private var cloud = CloudModel.shared
    @State private var server = CloudModel.shared.string("serverUrl")
    @State private var email = ""
    @State private var password = ""
    @State private var agreed = false
    @State private var confirmDelete = false
    @State private var exportText: String?
    @State private var exporting = false
    private var busy: Bool { cloud.bool("busy") }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("云端同步与数据").font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text)
            serverRow
            if !cloud.bool("configured") {
                note("未配置云端服务。测试记录和资料只保存在这台手机上，不会上传。")
            } else if !cloud.bool("signedIn") {
                signedOut
            } else {
                signedIn
            }
            if !cloud.string("message").isEmpty { note(cloud.string("message")) }
        }.padding(16).frame(maxWidth: .infinity, alignment: .leading)
            .background(EyeStyle.panel, in: RoundedRectangle(cornerRadius: EyeStyle.radius))
            .fileExporter(isPresented: $exporting, document: CloudExportDocument(text: exportText ?? ""), contentType: .json,
                          defaultFilename: "eyeproject-cloud-export.json") { _ in exportText = nil }
    }

    /// 服务器地址：局域网试用时填电脑的地址（如 http://192.168.1.5:8080），正式部署后填 https 域名。
    private var serverRow: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("服务器地址").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
            HStack {
                TextField("如 http://192.168.1.5:8080，留空表示不使用云端", text: $server)
                    .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled().textFieldStyle(.roundedBorder)
                Button("保存") { _ = cloud.client.setServer(url: server) }.disabled(busy || server == cloud.string("serverUrl"))
            }
        }
    }

    private var signedOut: some View {
        VStack(alignment: .leading, spacing: 10) {
            note("登录后可在多台设备间保存和对比测试记录。不登录也能正常使用全部测试。")
            TextField("邮箱", text: $email).keyboardType(.emailAddress).textInputAutocapitalization(.never)
                .autocorrectionDisabled().textFieldStyle(.roundedBorder)
            SecureField("密码（至少 8 位，含字母和数字）", text: $password).textFieldStyle(.roundedBorder)
            dataHandling
            Toggle("我已阅读上述说明并同意隐私政策（注册时需要）", isOn: $agreed).font(EyeStyle.bodySecondary)
            HStack(spacing: 12) {
                Button("登录") { cloud.client.signIn(email: email, password: password) }.buttonStyle(EyeButtonStyle())
                    .disabled(busy || email.isEmpty || password.isEmpty)
                Button("注册") { cloud.client.register(email: email, password: password) }.buttonStyle(EyeButtonStyle(role: .secondary))
                    .disabled(busy || !agreed || email.isEmpty || password.isEmpty)
            }
        }
    }

    private var signedIn: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(cloud.string("email")).font(EyeStyle.body)
                Spacer()
                Text(cloud.bool("healthDataSync") ? "同步已开启" : "仅本机").font(EyeStyle.chip)
                    .foregroundStyle(cloud.bool("healthDataSync") ? EyeStyle.success : EyeStyle.secondary)
            }
            consent("同意将测试记录加密存储到云端", "HEALTH_DATA_SYNC")
            if !cloud.bool("healthDataSync") && cloud.int("synced") > 0 {
                note("已停止上传。之前上传的 \(cloud.int("synced")) 条记录仍在云端，可导出查看；如需删除请注销云端账号。")
            }
            consent("我是监护人，同意上传为未满 14 岁孩子所做的测试", "GUARDIAN")
            consent("（可选）同意去除身份信息后用于眼健康研究", "RESEARCH_USE")
            note(statusLine)
            dataHandling
            HStack(spacing: 12) {
                Button("立即同步") { cloud.client.syncNow() }.buttonStyle(EyeButtonStyle()).disabled(busy || !cloud.bool("healthDataSync"))
                Button("导出云端数据") {
                    cloud.client.exportCloudData { text in
                        DispatchQueue.main.async { if let text { exportText = text; exporting = true } }
                    }
                }.buttonStyle(EyeButtonStyle(role: .secondary)).disabled(busy)
            }
            HStack(spacing: 12) {
                Button("退出登录") { cloud.client.signOut() }.buttonStyle(EyeButtonStyle(role: .secondary)).disabled(busy)
                Button(confirmDelete ? "确认删除" : "注销云端账号") {
                    if confirmDelete { confirmDelete = false; cloud.client.deleteCloudAccount() } else { confirmDelete = true }
                }.buttonStyle(EyeButtonStyle(role: .danger)).disabled(busy)
            }
            if confirmDelete { note("将永久删除云端账号、家庭成员和全部已上传记录，无法恢复。本机记录不受影响。再点一次「确认删除」继续。") }
        }
    }

    private var statusLine: String {
        var parts = ["已上传 \(cloud.int("synced")) 条"]
        if cloud.int("pending") > 0 { parts.append("待上传 \(cloud.int("pending")) 条") }
        if cloud.int("awaitingGuardian") > 0 { parts.append("\(cloud.int("awaitingGuardian")) 项待监护人同意") }
        if cloud.int("failed") > 0 { parts.append("\(cloud.int("failed")) 条未能上传") }
        if busy { parts.append("正在同步…") }
        return parts.joined(separator: " · ")
    }

    private func consent(_ title: String, _ type: String) -> some View {
        Toggle(title, isOn: Binding(get: { cloud.bool(type == "HEALTH_DATA_SYNC" ? "healthDataSync" : type == "GUARDIAN" ? "guardian" : "researchUse") },
                                    set: { cloud.client.setConsent(type: type, granted: $0) }))
            .font(EyeStyle.bodySecondary).disabled(busy)
    }

    private var dataHandling: some View {
        DisclosureGroup("数据如何处理") {
            VStack(alignment: .leading, spacing: 6) {
                ForEach(["上传内容：测试记录（含问卷回答、逐题作答和眼动数据）与成员资料。不收集身份证号、照片或视频。",
                         "保护方式：传输加密（正式部署使用 HTTPS），云端以独立密钥加密保存；所有查看、导出、删除操作留有审计记录。",
                         "你的权利：可随时关闭上传、导出全部云端数据，或注销账号立即删除全部云端数据。本机记录始终保留。",
                         "儿童：为未满 14 岁孩子所做的测试与资料，需监护人单独同意后才会上传。",
                         "结果性质：测试与评估仅为初筛参考，不能替代眼科诊断。"], id: \.self) { note($0) }
            }
        }.font(EyeStyle.bodySecondary)
    }

    private func note(_ text: String) -> some View {
        Text(text).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary).frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// 云端导出经系统文件选择器保存。
private struct CloudExportDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    var text: String
    init(text: String) { self.text = text }
    init(configuration: ReadConfiguration) throws { text = String(data: configuration.file.regularFileContents ?? Data(), encoding: .utf8) ?? "" }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: Data(text.utf8)) }
}

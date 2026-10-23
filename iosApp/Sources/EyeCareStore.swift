import Foundation
import UIKit
import EyeScreening

/// 主动护眼报告（共用 EyeCareEngine 的 JSON 解析结果），字段与安卓 EyeCareReport 一一对应。
struct CareReport {
    struct Signal: Identifiable { let id: String; let group: String; let label: String; let value: String; let status: String; let note: String }
    struct Factor: Identifiable { let id: String; let title: String; let detail: String; let points: Int }
    struct Action: Identifiable { let id: String; let title: String; let detail: String; let kind: String; let target: String?; let value: Int? }
    struct Consult: Identifiable { let id: Int; let title: String; let detail: String; let department: String; let urgent: Bool }
    struct Stage: Identifiable { let id: String; let label: String; let done: Bool; let summary: String }
    struct Day: Identifiable {
        let id: String; let label: String; let blinksPerMinute: Double?; let score: Int?
    }

    var level = "UNKNOWN"
    var score: Int?
    var label = "读取中"
    var headline = "正在汇总今天的用眼数据…"
    var mood = "THINKING"
    var loop: [Stage] = []
    var signals: [Signal] = []
    var factors: [Factor] = []
    var actions: [Action] = []
    var consults: [Consult] = []
    var days: [Day] = []
    var summary: [String] = []
    var checkInDays = 0
    var symptomDays = 0
    var checkedInToday = false
    var todaySymptoms: [String] = []
    var suggestedReminderMinutes = 20
    var loaded = false

    static let empty = CareReport()

    init() {}

    init(json: String) {
        let o = EyeRuntime.object(json)
        guard let level = o["level"] as? String else { return }
        loaded = true
        self.level = level
        score = o["score"] as? Int
        label = o["label"] as? String ?? ""
        headline = o["headline"] as? String ?? ""
        mood = o["mood"] as? String ?? "HAPPY"
        func list(_ key: String, _ source: [String: Any]) -> [[String: Any]] { source[key] as? [[String: Any]] ?? [] }
        loop = list("loop", o).map { Stage(id: $0["key"] as? String ?? "", label: $0["label"] as? String ?? "", done: $0["done"] as? Bool ?? false, summary: $0["summary"] as? String ?? "") }
        signals = list("signals", o).map { Signal(id: $0["key"] as? String ?? UUID().uuidString, group: $0["group"] as? String ?? "", label: $0["label"] as? String ?? "",
            value: $0["value"] as? String ?? "—", status: $0["status"] as? String ?? "UNAVAILABLE", note: $0["note"] as? String ?? "") }
        factors = list("factors", o).map { Factor(id: $0["key"] as? String ?? UUID().uuidString, title: $0["title"] as? String ?? "", detail: $0["detail"] as? String ?? "",
            points: $0["points"] as? Int ?? 0) }
        actions = list("actions", o).map { Action(id: $0["key"] as? String ?? UUID().uuidString, title: $0["title"] as? String ?? "", detail: $0["detail"] as? String ?? "",
            kind: $0["kind"] as? String ?? "", target: $0["target"] as? String, value: $0["value"] as? Int) }
        consults = list("consults", o).enumerated().map { i, c in Consult(id: i, title: c["title"] as? String ?? "", detail: c["detail"] as? String ?? "",
            department: c["department"] as? String ?? "", urgent: c["urgent"] as? Bool ?? false) }
        let trend = o["trend"] as? [String: Any] ?? [:]
        days = list("days", trend).map { d in Day(id: d["day"] as? String ?? UUID().uuidString, label: d["label"] as? String ?? "",
            blinksPerMinute: (d["blinksPerMinute"] as? NSNumber)?.doubleValue, score: d["score"] as? Int) }
        summary = trend["summary"] as? [String] ?? []
        checkInDays = trend["checkInDays"] as? Int ?? 0
        symptomDays = trend["symptomDays"] as? Int ?? 0
        checkedInToday = o["checkedInToday"] as? Bool ?? false
        todaySymptoms = o["todaySymptoms"] as? [String] ?? []
        suggestedReminderMinutes = o["suggestedReminderMinutes"] as? Int ?? 20
    }

    func signals(_ group: String) -> [Signal] { signals.filter { $0.group == group } }
    func signal(_ key: String) -> Signal? { signals.first { $0.id == key } }
}

/// 主动护眼的本机数据（安卓 EyeCareRepository 的苹果端）：账本以 JSON 存在应用支持目录（只在本机），
/// 规则与报告全部在共用的 EyeCareBridge / EyeCareEngine。账本记录本应用使用时长、实时检测会话与每日感受打卡。
final class EyeCareStore: ObservableObject {
    static let shared = EyeCareStore()

    @Published private(set) var report = CareReport.empty
    /// AI 综合建议的输入（共用 EyeCareAdvice 抽取的去标识概况 JSON）
    @Published private(set) var adviceRequestJson = ""

    private var ledger: String
    private let file: URL = {
        let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url.appendingPathComponent("eyecare.json")
    }()
    private let work = DispatchQueue(label: "eye.care.report", qos: .userInitiated)
    private var activeSince: Date?
    /// 本人测试结论的缓存：记录文件不变就不重新解析
    private var testsSignature = ""
    private var testsCache: [[String: Any]] = []

    private init() {
        ledger = (try? String(contentsOf: file, encoding: .utf8)) ?? ""
    }

    private var nowMs: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
    private var offsetMinutes: Int32 { Int32(TimeZone.current.secondsFromGMT() / 60) }
    var today: String { EyeCareBridge.shared.dayOf(epochMs: nowMs, utcOffsetMinutes: offsetMinutes) }

    // MARK: 写入

    /// 实时检测结束：本次的眨眼频率、观看距离与头部偏转。
    func addSession(_ session: LiveEyeMonitor.Session) {
        let day = today, now = nowMs
        mutate { EyeCareBridge.shared.addSession(ledgerJson: $0, day: day, endedAtMs: now, session: session) }
    }

    /// 今日感受打卡（闭环「反馈」）；空数组表示没有不适。
    func checkIn(_ symptoms: [String]) {
        let day = today, now = nowMs
        mutate { EyeCareBridge.shared.checkIn(ledgerJson: $0, day: day, symptoms: symptoms, atMs: now) }
    }

    /// 回到前台开始计时；退到后台时把这段使用时长记入当天。
    func appBecameActive() { activeSince = Date(); refresh() }

    func appResignedActive() {
        guard let start = activeSince else { return }
        activeSince = nil
        let seconds = Int64(Date().timeIntervalSince(start))
        guard seconds > 0 else { return }
        let day = today
        mutate { EyeCareBridge.shared.addAppSeconds(ledgerJson: $0, day: day, seconds: seconds) }
    }

    private func mutate(_ change: (String) -> String) {
        ledger = change(ledger)
        let text = ledger, target = file
        work.async { try? text.data(using: .utf8)?.write(to: target, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]) }
        refresh()
    }

    // MARK: 报告

    /// 重新计算报告：主线程取本人资料、屏幕亮度与提醒设置，记录解析与规则计算在后台队列。
    func refresh() {
        let fields = SubjectStore.shared.subjects.first { $0.relation == "SELF" }?.fields ?? [:]
        let now = nowMs, offset = offsetMinutes
        let age = SubjectProfiles.shared.age(fields: fields, nowMs: now)?.intValue
        let brightness = Double(UIScreen.main.brightness)
        let reminderOn = UserDefaults.standard.bool(forKey: "eye.reminderEnabled")
        let reminderMinutes = UserDefaults.standard.object(forKey: "eye.reminderMinutes") as? Int ?? 20
        let ledger = self.ledger
        let day = EyeCareBridge.shared.dayOf(epochMs: now, utcOffsetMinutes: offset)
        let hour = Int(EyeCareBridge.shared.hourOf(epochMs: now, utcOffsetMinutes: offset))
        work.async {
            var context: [String: Any] = [
                "today": day, "hour": hour, "usageAccess": "UNSUPPORTED", "ambientSupported": false,
                "screenBrightness": brightness, "profile": fields, "tests": self.tests(offset),
                "reminderEnabled": reminderOn, "reminderMinutes": reminderMinutes,
            ]
            if let age { context["age"] = age }
            let contextJson = (try? JSONSerialization.data(withJSONObject: context)).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
            let parsed = CareReport(json: EyeCareBridge.shared.reportJson(ledgerJson: ledger, contextJson: contextJson))
            let advice = EyeCareAdvice.shared.requestJson(ledgerJson: ledger, contextJson: contextJson) ?? ""
            DispatchQueue.main.async {
                self.report = parsed
                self.adviceRequestJson = advice
            }
        }
    }

    /// 本人（或未选成员）的测试结论；只在记录文件变化时重新解析（在后台队列调用）。
    private func tests(_ offset: Int32) -> [[String: Any]] {
        let directory = EyeRuntime.recordsDirectory
        let names = ((try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []).filter { $0.hasSuffix(".json") }.sorted()
        let signature = names.joined(separator: "|")
        if signature == testsSignature { return testsCache }
        let entries: [LocalRecordEntry] = names.compactMap { name in
            guard let text = try? String(contentsOf: directory.appendingPathComponent(name), encoding: .utf8) else { return nil }
            return LocalRecordCatalog.shared.entry(fileName: name, record: text)
        }
        let json = EyeCareBridge.shared.testBriefsJson(entries: entries, utcOffsetMinutes: offset)
        let parsed = ((try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [[String: Any]]) ?? []
        testsSignature = signature
        testsCache = parsed
        return parsed
    }
}

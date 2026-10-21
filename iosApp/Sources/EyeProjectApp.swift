import SwiftUI
import EyeScreening
import UniformTypeIdentifiers

@main
struct EyeProjectApp: App {
    var body: some Scene {
        WindowGroup { EyeRootView().preferredColorScheme(.light).tint(EyeStyle.primary) }
    }
}

/// 测试记录：与安卓同一份列表（共用 LocalRecordCatalog 解析）。每行为测试名、时间与结论徽标，
/// 点开看要点，并可把完整记录（JSON）分享给医生或开发者。
struct RecordsView: View {
    @Environment(\.dismiss) private var dismiss
    private let records = RecordsView.load()

    struct Entry: Identifiable {
        let url: URL, title: String, testedAt: Date?, level: String, badge: String, lines: [String]
        let version: String, subjectLabel: String, advice: String, qualityNotes: [String], sections: [ReportModel.Section], reportText: String
        var id: String { url.lastPathComponent }
    }

    var body: some View {
        NavigationStack {
            List {
                if records.isEmpty { Text("还没有测试记录").foregroundStyle(EyeStyle.secondary) }
                ForEach(records) { RecordRow(entry: $0) }
            }.navigationTitle("测试记录").toolbar { Button("完成") { EyeHaptics.tap(); dismiss() } }
        }
    }

    private static func load() -> [Entry] {
        let files = (try? FileManager.default.contentsOfDirectory(at: EyeRuntime.recordsDirectory, includingPropertiesForKeys: nil)) ?? []
        return files.filter { $0.pathExtension == "json" }.compactMap { url -> Entry? in
            guard let text = try? String(contentsOf: url, encoding: .utf8),
                  let json = LocalRecordCatalog.shared.entryJson(fileName: url.lastPathComponent, record: text) else { return nil }
            let e = EyeRuntime.object(json)
            let ms = (e["testedAtMs"] as? NSNumber)?.doubleValue
            let testedAt = ms.map { Date(timeIntervalSince1970: $0 / 1000) }
            let parsed = LocalRecordCatalog.shared.entry(fileName: url.lastPathComponent, record: text)
            let formatter = DateFormatter()
            formatter.locale = Locale(identifier: "zh_CN"); formatter.dateFormat = "yyyy-MM-dd HH:mm:ss Z"
            let sections = (e["sections"] as? [[String: Any]] ?? []).enumerated().map { i, section in
                ReportModel.Section(id: i, title: section["title"] as? String ?? "", rows: (section["rows"] as? [[String: Any]] ?? []).enumerated().map { j, row in
                    ReportModel.Row(id: j, label: row["label"] as? String ?? "", value: row["value"] as? String ?? "未记录")
                })
            }
            return Entry(url: url, title: e["title"] as? String ?? url.lastPathComponent,
                         testedAt: testedAt,
                         level: e["level"] as? String ?? "UNKNOWN", badge: e["badge"] as? String ?? "",
                         lines: e["lines"] as? [String] ?? [], version: e["version"] as? String ?? "未记录（旧版本）",
                         subjectLabel: e["subjectLabel"] as? String ?? "未选择成员", advice: e["advice"] as? String ?? "",
                         qualityNotes: e["qualityNotes"] as? [String] ?? [], sections: sections,
                         reportText: parsed?.reportText(testedAtLabel: testedAt.map { formatter.string(from: $0) } ?? "未记录") ?? "")
        }.sorted { ($0.testedAt ?? .distantPast) > ($1.testedAt ?? .distantPast) }
    }
}

private struct RecordRow: View {
    let entry: RecordsView.Entry
    @State private var expanded = false
    @State private var exportingReport = false
    @State private var exportError: String?
    private static let formatter: DateFormatter = {
        let f = DateFormatter(); f.locale = Locale(identifier: "zh_CN"); f.dateFormat = "yyyy-MM-dd HH:mm"; return f
    }()
    private var tone: Color {
        switch entry.level {
        case "LOW": return EyeStyle.success
        case "HIGH": return EyeStyle.danger
        case "MEDIUM", "UNCERTAIN": return EyeStyle.warning
        default: return EyeStyle.secondary
        }
    }
    var body: some View {
        DisclosureGroup(isExpanded: $expanded) {
            if !entry.advice.isEmpty { Text(entry.advice).font(EyeStyle.body) }
            ForEach(Array(entry.lines.enumerated()), id: \.offset) { _, line in
                Text("· \(line)").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
            }
            if !entry.qualityNotes.isEmpty {
                DisclosureGroup("数据可靠性说明") {
                    ForEach(Array(entry.qualityNotes.enumerated()), id: \.offset) { _, note in Text(note).font(EyeStyle.bodySecondary) }
                }
            }
            ForEach(entry.sections) { section in
                DisclosureGroup(section.title) {
                    ForEach(section.rows) { row in
                        Text(row.label.isEmpty ? row.value : "\(row.label)：\(row.value)").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                    }
                }
            }
            Text("评估版本 \(entry.version)").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
            // 非默认按钮样式：同一行两个按钮各自响应，不会点一处触发整行
            HStack(spacing: 12) {
                Button("导出报告") { EyeHaptics.tap(); exportingReport = true }
                ShareLink(item: entry.url) { Text("导出原始数据") }.simultaneousGesture(TapGesture().onEnded { EyeHaptics.tap() })
            }.buttonStyle(EyeButtonStyle(role: .secondary))
            if let exportError { Text(exportError).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.danger) }
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(entry.title).font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text)
                    Text((entry.testedAt.map { Self.formatter.string(from: $0) } ?? entry.url.lastPathComponent) + " · " + entry.subjectLabel)
                        .font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                }
                Spacer()
                Text(entry.badge).font(EyeStyle.chip).foregroundStyle(tone)
                    .padding(.horizontal, 10).padding(.vertical, 4).background(tone.opacity(0.12), in: Capsule())
            }
        }
        .onChange(of: expanded) { _ in EyeHaptics.tap() }
        .fileExporter(isPresented: $exportingReport, document: RecordReportDocument(text: entry.reportText), contentType: .plainText,
                      defaultFilename: entry.url.deletingPathExtension().lastPathComponent + "_报告.txt") { result in
            if case .failure = result { exportError = "导出失败，请换个位置再试"; EyeHaptics.failure() } else { exportError = nil; EyeHaptics.success() }
        }
    }
}

/// 可读报告经系统文件选择器保存，原始 JSON 保留原样。
private struct RecordReportDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.plainText] }
    var text: String
    init(text: String) { self.text = text }
    init(configuration: ReadConfiguration) throws { text = String(data: configuration.file.regularFileContents ?? Data(), encoding: .utf8) ?? "" }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: Data(text.utf8)) }
}

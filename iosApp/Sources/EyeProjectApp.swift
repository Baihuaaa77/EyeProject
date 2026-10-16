import SwiftUI
import EyeScreening

@main
struct EyeProjectApp: App {
    var body: some Scene {
        WindowGroup { EyeRootView().preferredColorScheme(.light).tint(EyeStyle.primary) }
    }
}

/// 本机测试记录：与安卓同一份列表（共用 LocalRecordCatalog 解析）。每行为测试名、时间与结论徽标，
/// 点开看要点，并可把完整记录（JSON）分享给医生或开发者。
struct RecordsView: View {
    @Environment(\.dismiss) private var dismiss
    private let records = RecordsView.load()

    struct Entry: Identifiable {
        let url: URL, title: String, testedAt: Date?, level: String, badge: String, lines: [String]
        var id: String { url.lastPathComponent }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if records.isEmpty { Text("还没有测试记录").foregroundStyle(EyeStyle.secondary) }
                    ForEach(records) { RecordRow(entry: $0) }
                } footer: { Text("记录只保存在这台手机上；删除应用会一并删除。") }
            }.navigationTitle("本机测试记录").toolbar { Button("完成") { dismiss() } }
        }
    }

    private static func load() -> [Entry] {
        let files = (try? FileManager.default.contentsOfDirectory(at: EyeRuntime.recordsDirectory, includingPropertiesForKeys: nil)) ?? []
        return files.filter { $0.pathExtension == "json" }.compactMap { url -> Entry? in
            guard let text = try? String(contentsOf: url, encoding: .utf8),
                  let json = LocalRecordCatalog.shared.entryJson(fileName: url.lastPathComponent, record: text) else { return nil }
            let e = EyeRuntime.object(json)
            let ms = (e["testedAtMs"] as? NSNumber)?.doubleValue
            return Entry(url: url, title: e["title"] as? String ?? url.lastPathComponent,
                         testedAt: ms.map { Date(timeIntervalSince1970: $0 / 1000) },
                         level: e["level"] as? String ?? "UNKNOWN", badge: e["badge"] as? String ?? "",
                         lines: e["lines"] as? [String] ?? [])
        }.sorted { ($0.testedAt ?? .distantPast) > ($1.testedAt ?? .distantPast) }
    }
}

private struct RecordRow: View {
    let entry: RecordsView.Entry
    @State private var expanded = false
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
            ForEach(Array(entry.lines.enumerated()), id: \.offset) { _, line in
                Text("· \(line)").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
            }
            ShareLink(item: entry.url) { Label("导出完整记录", systemImage: "square.and.arrow.up") }
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(entry.title).font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text)
                    Text(entry.testedAt.map { Self.formatter.string(from: $0) } ?? entry.url.lastPathComponent)
                        .font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                }
                Spacer()
                Text(entry.badge).font(EyeStyle.chip).foregroundStyle(tone)
                    .padding(.horizontal, 10).padding(.vertical, 4).background(tone.opacity(0.12), in: Capsule())
            }
        }
    }
}

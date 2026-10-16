import SwiftUI

@main
struct EyeProjectApp: App {
    var body: some Scene {
        WindowGroup { EyeRootView().preferredColorScheme(.light).tint(EyeStyle.primary) }
    }
}

/// 本机测试记录：按时间倒序列出 JSON 记录，可直接分享给医生或开发者。
struct RecordsView: View {
    @Environment(\.dismiss) private var dismiss
    let files = (try? FileManager.default.contentsOfDirectory(at: EyeRuntime.recordsDirectory, includingPropertiesForKeys: nil))?
        .filter { $0.pathExtension == "json" }.sorted { $0.lastPathComponent > $1.lastPathComponent } ?? []
    var body: some View {
        NavigationStack {
            List {
                if files.isEmpty { Text("还没有测试记录") }
                ForEach(files, id: \.self) { url in ShareLink(item: url) { Label(url.lastPathComponent, systemImage: "doc.text") } }
            }.navigationTitle("本机测试记录").toolbar { Button("完成") { dismiss() } }
        }
    }
}

import SwiftUI

@main
struct EyeProjectApp: App {
    var body: some Scene {
        WindowGroup { EyeHomeView().preferredColorScheme(.light).tint(EyeStyle.primary) }
    }
}

struct EyeHomeView: View {
    @State private var selected: String?
    @State private var showRecords = false
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: EyeStyle.spacing) {
                    MascotView().frame(width: 120, height: 120).frame(maxWidth: .infinity)
                    Text("眼健康自测").font(EyeStyle.title).foregroundStyle(EyeStyle.text)
                    Text("完成测试后，记录保存在这台手机上。").font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
                    ForEach([("DRY_EYE", "干眼自测"), ("PRESBYOPIA", "老视自测"), ("STRABISMUS", "眼位自测")], id: \.0) { item in
                        Button { selected = item.0 } label: {
                            HStack { Text(item.1); Spacer(); Image(systemName: "chevron.right").foregroundStyle(EyeStyle.secondary) }
                        }.buttonStyle(EyeCardButtonStyle())
                    }
                    Button("本机测试记录") { showRecords = true }.buttonStyle(EyeButtonStyle())
                }.padding()
            }.background(EyeStyle.background.ignoresSafeArea()).navigationTitle("眼健康助手")
                // 测试全屏呈现：不用卡片式弹窗，避免顶部留出灵动岛下方的空隙和下滑误关。
                .fullScreenCover(item: Binding(get: { selected.map(TestSelection.init) }, set: { selected = $0?.id })) { test in
                    EyeTestView(kind: test.id)
                }
                .sheet(isPresented: $showRecords) { RecordsView() }
        }
    }
}

private struct TestSelection: Identifiable { let id: String; init(_ id: String) { self.id = id } }

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

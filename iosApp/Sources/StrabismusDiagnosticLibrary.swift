import SwiftUI
import AVKit

/// 同时收录正常结束和中断的测试；轮询仅在此页打开时进行，不重读逐帧大文件。
struct StrabismusDiagnosticLibrary: View {
    var sessionId: String? = nil
    @Environment(\.dismiss) private var dismiss
    @State private var sessions: [Item] = []
    @State private var selected: URL?
    @State private var player: AVPlayer?
    struct Item: Identifiable {
        let id: String; let directory: URL; let created: Double; let state: String; let error: String; let frames: Int
        var video: URL { directory.appendingPathComponent("comparison.mp4") }
    }
    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(spacing: 16) {
                    if let player { VideoPlayer(player: player).aspectRatio(16/9, contentMode: .fit) }
                    if sessions.isEmpty { Text("暂无斜视诊断视频").font(EyeStyle.body).foregroundStyle(EyeStyle.secondary) }
                    ForEach(sessions) { item in
                        VStack(alignment: .leading, spacing: 12) {
                            Text(Date(timeIntervalSince1970: item.created/1000), style: .date).font(EyeStyle.subtitle)
                            Text(Date(timeIntervalSince1970: item.created/1000), style: .time).font(EyeStyle.bodySecondary)
                            Text(label(item)).font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
                            if item.state == "ready" {
                                Button("回看拼接视频") { player?.pause(); selected = item.video; player = AVPlayer(url: item.video); player?.play() }.buttonStyle(EyeButtonStyle())
                                ShareLink(item: item.video) { Text("导出拼接视频") }.buttonStyle(EyeButtonStyle(role: .secondary))
                                ShareLink(item: item.directory.appendingPathComponent("original.mp4")) { Text("导出原视频") }.buttonStyle(EyeButtonStyle(role: .secondary))
                            }
                            ShareLink(item: item.directory.appendingPathComponent("frames.jsonl")) { Text("导出逐帧诊断数据") }.buttonStyle(EyeButtonStyle(role: .secondary))
                        }.padding(16).frame(maxWidth: .infinity, alignment: .leading).eyeGlass()
                    }
                }.padding()
            }
            .background(EyeStyle.background).foregroundStyle(EyeStyle.text)
            .navigationTitle("斜视诊断视频").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() } } }
        }
        .preferredColorScheme(.dark)
        .task {
            while !Task.isCancelled {
                let loaded = await Task.detached(priority: .utility) { Self.load() }.value
                sessions = loaded.filter { sessionId == nil || $0.id == sessionId }
                try? await Task.sleep(nanoseconds: 750_000_000)
            }
        }
        .onDisappear { player?.pause(); player = nil }
    }
    private func label(_ item: Item) -> String {
        switch item.state {
        case "ready": return "已保存 · \(item.frames) 帧"
        case "recording": return "录制中或上次未正常结束，可导出诊断数据"
        case "finishing": return "正在收尾保存"
        case "failed": return "视频未完整保存：\(item.error)"
        default: return "上次录制意外中断，可导出逐帧数据"
        }
    }
    static func load() -> [Item] {
        let directories = (try? FileManager.default.contentsOfDirectory(at: StrabismusDiagnosticVideo.root, includingPropertiesForKeys: nil)) ?? []
        return directories.compactMap { directory in
            guard let data = try? Data(contentsOf: directory.appendingPathComponent("manifest.json")),
                  let value = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
                  let id = value["sessionId"] as? String else { return nil }
            return Item(id: id, directory: directory, created: (value["createdAtMs"] as? NSNumber)?.doubleValue ?? 0,
                        state: value["state"] as? String ?? "interrupted", error: value["error"] as? String ?? "无可编码画面",
                        frames: value["frames"] as? Int ?? 0)
        }.sorted { $0.created > $1.created }
    }
}

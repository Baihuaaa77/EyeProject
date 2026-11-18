import SwiftUI
import AVKit
import UIKit

/// 同时收录正常结束和中断的测试；轮询仅在此页打开时进行，不重读逐帧大文件。
struct StrabismusDiagnosticLibrary: View {
    var sessionId: String? = nil
    @Environment(\.dismiss) private var dismiss
    @State private var sessions: [Item] = []
    @State private var selected: URL?
    struct Item: Identifiable {
        let id: String; let directory: URL; let created: Double; let state: String; let error: String; let frames: Int
        var video: URL { directory.appendingPathComponent("comparison.mp4") }
    }
    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(spacing: 16) {
                    if let selected { DiagnosticPlayback(url: selected).id(selected) }
                    if sessions.isEmpty { Text("暂无斜视诊断视频").font(EyeStyle.body).foregroundStyle(EyeStyle.secondary) }
                    ForEach(sessions) { item in
                        VStack(alignment: .leading, spacing: 12) {
                            Text(Date(timeIntervalSince1970: item.created/1000), style: .date).font(EyeStyle.subtitle)
                            Text(Date(timeIntervalSince1970: item.created/1000), style: .time).font(EyeStyle.bodySecondary)
                            Text(label(item)).font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
                            if item.state == "ready" {
                                Button("回看拼接视频") { selected = item.video }.buttonStyle(EyeButtonStyle())
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

/// 视频内容缩放，播放和进度控件留在画面外。
private struct DiagnosticPlayback: View {
    @State private var player: AVPlayer
    @State private var zoom: CGFloat = 1
    @State private var time = 0.0
    @State private var duration = 0.0
    @State private var playing = false
    @State private var observer: Any?

    init(url: URL) { _player = State(initialValue: AVPlayer(url: url)) }

    var body: some View {
        VStack(spacing: 8) {
            DiagnosticZoomSurface(player: player, zoom: $zoom)
                .aspectRatio(16.0 / 9, contentMode: .fit)
                .accessibilityLabel("拼接视频，双指缩放，放大后可拖动")
            Slider(value: Binding(get: { min(time, max(duration, 0.001)) }, set: {
                time = $0
                player.seek(to: CMTime(seconds: $0, preferredTimescale: 600))
            }), in: 0...max(duration, 0.001)).accessibilityLabel("播放进度")
            HStack {
                Button(playing ? "暂停" : "播放") {
                    if playing { player.pause() }
                    else {
                        if duration > 0, time >= duration - 0.1 { player.seek(to: .zero) }
                        player.play()
                    }
                    playing = player.rate > 0
                }
                Spacer()
                Button("缩小") { zoom = max(1, zoom / 1.5) }.disabled(zoom <= 1)
                Button("放大") { zoom = min(6, zoom * 1.5) }.disabled(zoom >= 6)
                Button("复位") { zoom = 1 }.disabled(zoom <= 1)
            }.buttonStyle(.bordered).controlSize(.large)
        }
        .onAppear {
            guard observer == nil else { return }
            observer = player.addPeriodicTimeObserver(forInterval: CMTime(value: 1, timescale: 5), queue: .main) { stamp in
                if stamp.seconds.isFinite { time = max(0, stamp.seconds) }
                if let total = player.currentItem?.duration.seconds, total.isFinite { duration = max(0, total) }
                playing = player.rate > 0
            }
            player.play(); playing = true
        }
        .onDisappear {
            player.pause()
            if let observer { player.removeTimeObserver(observer); self.observer = nil }
        }
    }
}

private struct DiagnosticZoomSurface: UIViewRepresentable {
    let player: AVPlayer
    @Binding var zoom: CGFloat

    func makeUIView(context: Context) -> VideoScroll {
        let view = VideoScroll()
        view.video.playerLayer.player = player
        return view
    }

    func updateUIView(_ view: VideoScroll, context: Context) {
        let scale = $zoom
        view.onZoom = { value in
            DispatchQueue.main.async { if abs(scale.wrappedValue - value) > 0.01 { scale.wrappedValue = value } }
        }
        if !view.isZooming, abs(view.zoomScale - zoom) > 0.01 {
            view.setZoomScale(zoom, animated: false)
        }
    }

    final class VideoCanvas: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }

    final class VideoScroll: UIScrollView, UIScrollViewDelegate {
        let video = VideoCanvas()
        var onZoom: ((CGFloat) -> Void)?
        private var viewport = CGSize.zero

        init() {
            super.init(frame: .zero)
            delegate = self
            minimumZoomScale = 1
            maximumZoomScale = 6
            bouncesZoom = false
            showsHorizontalScrollIndicator = false
            showsVerticalScrollIndicator = false
            contentInsetAdjustmentBehavior = .never
            video.playerLayer.videoGravity = .resizeAspect
            backgroundColor = UIColor(EyeStyle.reflectionBackground)
            addSubview(video)
            panGestureRecognizer.isEnabled = false
        }

        required init?(coder: NSCoder) { fatalError("不使用归档初始化") }

        override func layoutSubviews() {
            super.layoutSubviews()
            guard viewport != bounds.size, bounds.width > 0, bounds.height > 0 else { return }
            viewport = bounds.size
            let scale = zoomScale
            setZoomScale(1, animated: false)
            video.frame = CGRect(origin: .zero, size: viewport)
            contentSize = viewport
            setZoomScale(scale, animated: false)
        }

        func viewForZooming(in scrollView: UIScrollView) -> UIView? { video }
        func scrollViewDidZoom(_ scrollView: UIScrollView) {
            panGestureRecognizer.isEnabled = zoomScale > 1.01
            onZoom?(zoomScale)
        }
    }
}

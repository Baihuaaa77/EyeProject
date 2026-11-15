import SwiftUI
import AVFoundation
import UIKit

final class MoviePlayback: ObservableObject {
    let player: AVPlayer
    let start: Double
    let results: [EyeAnalysis]
    @Published var time = 0.0
    @Published var duration = 0.0
    @Published var playing = false
    @Published var rows: [String:[FrameRecord]] = [:]
    @Published var videoStartPTS = 0.0
    private var observer: Any?
    init(directory: URL,trial: TrialSummary) {
        player = AVPlayer(url: directory.appendingPathComponent("camera.mov"))
        start = trial.startHostTime; results = trial.results
        observer = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.2,preferredTimescale: 600),queue: .main) { [weak self] t in
            guard let self = self else { return }
            self.time = max(0,CMTimeGetSeconds(t))
            let total = CMTimeGetSeconds(self.player.currentItem?.duration ?? .zero)
            self.duration = total.isFinite ? total : 0
            self.playing = self.player.rate>0
        }
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            do {
                let data = try String(contentsOf: directory.appendingPathComponent("frames.jsonl"),encoding: .utf8)
                let decoder = JSONDecoder()
                let records = try data.split(whereSeparator: \.isNewline).map { try decoder.decode(FrameRecord.self,from: Data($0.utf8)) }
                let metadata = (try? JSONSerialization.jsonObject(with: Data(contentsOf: directory.appendingPathComponent("camera.json")))) as? [String:Any]
                let origin = metadata?["videoStartPTS"] as? Double ?? records.first?.pts ?? 0
                let grouped = Dictionary(uniqueKeysWithValues: ["image_left","image_right"].map { eye in
                    (eye,records.filter { $0.eye == nil || $0.eye == eye }.sorted { $0.pts<$1.pts })
                })
                DispatchQueue.main.async { self?.rows = grouped; self?.videoStartPTS = origin }
            } catch { /* 旧记录缺少逐帧数据时仍可播放原视频。 */ }
        }
    }
    deinit { if let observer = observer { player.removeTimeObserver(observer) } }
    func toggle() {
        if player.rate>0 { player.pause(); playing = false }
        else {
            if duration>0 && time>=duration-0.1 { player.seek(to: .zero) }
            player.play(); playing = true
        }
    }
    func seek(_ value: Double) { player.seek(to: CMTime(seconds: value,preferredTimescale: 600),toleranceBefore: .zero,toleranceAfter: .zero) }
}

/// 缩放整个播放表面（视频＋检测叠加），播放器实例和播放时间不随缩放或开关重建。
struct InteractiveMovieView: View {
    @StateObject private var playback: MoviePlayback
    @State private var overlay = false
    @State private var zoom: CGFloat = 1
    @State private var expanded = false
    init(directory: URL,trial: TrialSummary) { _playback = StateObject(wrappedValue: MoviePlayback(directory: directory,trial: trial)) }
    var body: some View {
        VStack(alignment: .leading,spacing: 12) {
            Text("原视频").font(LabStyle.heading)
            Toggle("显示检测结果",isOn: $overlay)
            ZoomMovieSurface(playback: playback,overlay: overlay,zoom: $zoom).id(expanded).frame(height: 340).clipped()
                .accessibilityLabel("可双指缩放和拖动的原视频")
            controls
            HStack {
                Button("缩小") { zoom = max(1,zoom/1.5) }.disabled(zoom<=1)
                Button("适合窗口") { zoom = 1 }
                Button("放大") { zoom = min(12,zoom*1.5) }.disabled(zoom>=12)
                Spacer()
                Button("全屏") { expanded = true }
            }.buttonStyle(.bordered).frame(minHeight: 44)
        }
        .fullScreenCover(isPresented: $expanded) {
            VStack(spacing: 12) {
                HStack { Button("完成") { expanded = false }; Spacer(); Toggle("检测叠加",isOn: $overlay) }
                ZoomMovieSurface(playback: playback,overlay: overlay,zoom: $zoom)
                controls
                HStack {
                    Button("缩小") { zoom = max(1,zoom/1.5) }
                    Button("适合窗口") { zoom = 1 }
                    Button("放大") { zoom = min(12,zoom*1.5) }
                }.buttonStyle(.bordered).frame(minHeight: 44)
            }.padding().background(LabStyle.background)
        }
        .onDisappear { if !expanded { playback.player.pause() } }
    }
    private var controls: some View {
        VStack(spacing: 8) {
            Slider(value: Binding(get: { min(playback.time,max(playback.duration,0.001)) },set: { playback.seek($0) }),in: 0...max(playback.duration,0.001)).accessibilityLabel("播放进度")
            HStack {
                Button(playback.playing ? "暂停" : "播放",action: playback.toggle).buttonStyle(.bordered).frame(minWidth: 64,minHeight: 44)
                Text("\(Int(playback.time)) / \(Int(playback.duration)) 秒").font(LabStyle.detail).monospacedDigit()
                Spacer()
                Text(String(format: "%.1f×",Double(zoom))).font(LabStyle.detail)
            }
        }
    }
}

struct ZoomMovieSurface: UIViewRepresentable {
    @ObservedObject var playback: MoviePlayback
    let overlay: Bool
    @Binding var zoom: CGFloat
    func makeUIView(context: Context) -> VideoZoomScroll {
        let view = VideoZoomScroll(player: playback.player)
        view.onZoom = { value in DispatchQueue.main.async { if abs(zoom-value)>0.01 { zoom = value } } }
        return view
    }
    func updateUIView(_ view: VideoZoomScroll,context: Context) {
        view.canvas.marks.rows = playback.rows; view.canvas.marks.results = playback.results
        view.canvas.marks.start = playback.start; view.canvas.marks.videoStartPTS = playback.videoStartPTS
        view.canvas.marks.isHidden = !overlay; view.canvas.marks.setNeedsDisplay()
        if !view.isZooming,abs(view.zoomScale-zoom)>0.01 { view.setZoomScale(zoom,animated: false) }
    }
    static func dismantleUIView(_ view: VideoZoomScroll,coordinator: ()) { view.detach() }
}

final class VideoZoomScroll: UIScrollView, UIScrollViewDelegate {
    let canvas: VideoCanvas
    var onZoom: ((CGFloat) -> Void)?
    private var previousSize = CGSize.zero
    init(player: AVPlayer) {
        canvas = VideoCanvas(player: player)
        super.init(frame: .zero)
        delegate = self; minimumZoomScale = 1; maximumZoomScale = 12
        backgroundColor = LabStyle.stimulusBackground
        showsHorizontalScrollIndicator = false; showsVerticalScrollIndicator = false
        contentInsetAdjustmentBehavior = .never
        addSubview(canvas)
        let tap = UITapGestureRecognizer(target: self,action: #selector(doubleTap(_:))); tap.numberOfTapsRequired = 2
        addGestureRecognizer(tap)
    }
    required init?(coder: NSCoder) { fatalError("不使用归档初始化") }
    override func layoutSubviews() {
        if bounds.size != previousSize,bounds.width>0,bounds.height>0 {
            previousSize = bounds.size
            let saved = zoomScale; setZoomScale(1,animated: false)
            canvas.frame = CGRect(origin: .zero,size: bounds.size); contentSize = bounds.size
            setZoomScale(saved,animated: false)
        }
        super.layoutSubviews()
        canvas.center = CGPoint(x: max(bounds.width,contentSize.width)/2,y: max(bounds.height,contentSize.height)/2)
    }
    func viewForZooming(in scrollView: UIScrollView) -> UIView? { canvas }
    func scrollViewDidZoom(_ scrollView: UIScrollView) { onZoom?(zoomScale) }
    @objc private func doubleTap(_ tap: UITapGestureRecognizer) {
        if zoomScale>1.01 { setZoomScale(1,animated: !UIAccessibility.isReduceMotionEnabled) }
        else {
            let p = tap.location(in: canvas),factor: CGFloat = 3
            zoom(to: CGRect(x: p.x-bounds.width/factor/2,y: p.y-bounds.height/factor/2,width: bounds.width/factor,height: bounds.height/factor),animated: !UIAccessibility.isReduceMotionEnabled)
        }
    }
    func detach() { canvas.detach() }
}

final class VideoCanvas: UIView {
    let playerLayer: AVPlayerLayer
    let marks = VideoResultOverlay()
    private let player: AVPlayer
    private var observer: Any?
    init(player: AVPlayer) {
        self.player = player; playerLayer = AVPlayerLayer(player: player)
        super.init(frame: .zero)
        playerLayer.videoGravity = .resizeAspect; layer.addSublayer(playerLayer)
        marks.isOpaque = false; marks.backgroundColor = .clear; marks.isUserInteractionEnabled = false
        addSubview(marks)
        observer = player.addPeriodicTimeObserver(forInterval: CMTime(value: 1,timescale: 30),queue: .main) { [weak self] time in
            guard let self = self else { return }
            self.marks.videoTime = CMTimeGetSeconds(time)
            let rect = self.playerLayer.videoRect
            if !rect.isEmpty { self.marks.frame = rect }
            if !self.marks.isHidden { self.marks.setNeedsDisplay() }
        }
    }
    required init?(coder: NSCoder) { fatalError("不使用归档初始化") }
    override func layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin(); CATransaction.setDisableActions(true); playerLayer.frame = bounds; CATransaction.commit()
        marks.frame = playerLayer.videoRect
    }
    func detach() { if let observer = observer { player.removeTimeObserver(observer); self.observer = nil }; playerLayer.player = nil }
    deinit { if let observer = observer { player.removeTimeObserver(observer) } }
}

final class VideoResultOverlay: UIView {
    var rows: [String:[FrameRecord]] = [:]
    var results: [EyeAnalysis] = []
    var start = 0.0
    var videoStartPTS = 0.0
    var videoTime = 0.0
    override func draw(_ rect: CGRect) {
        guard let context = UIGraphicsGetCurrentContext() else { return }
        let selected = ["image_left","image_right"].compactMap { EyeMediaExporter.nearest(videoTime,rows: rows[$0] ?? [],origin: videoStartPTS) }
        EyeMediaExporter.fullOverlay(context,canvasSize: bounds.size,rows: selected,results: results,videoTime: videoTime,videoStartPTS: videoStartPTS,start: start)
    }
}

/// 眼区图片默认仅占较小预览，可单独展开交互缩放。
struct EyePhotoView: View {
    let image: UIImage
    @State private var expanded = false
    var body: some View {
        Button { expanded = true } label: {
            Image(uiImage: image).resizable().interpolation(.none).scaledToFit().frame(maxWidth: 200,maxHeight: 100)
        }.accessibilityLabel("打开眼部图片并缩放")
        .fullScreenCover(isPresented: $expanded) {
            VStack {
                HStack { Button("完成") { expanded = false }; Spacer(); Text("双指缩放").font(LabStyle.detail) }.padding()
                ZoomPhotoSurface(image: image)
            }.background(LabStyle.background)
        }
    }
}

struct ZoomPhotoSurface: UIViewRepresentable {
    let image: UIImage
    final class PhotoScroll: UIScrollView, UIScrollViewDelegate {
        let photo = UIImageView()
        override init(frame: CGRect) {
            super.init(frame: frame); delegate = self; minimumZoomScale = 1; maximumZoomScale = 12
            photo.contentMode = .scaleAspectFit; addSubview(photo)
        }
        required init?(coder: NSCoder) { fatalError("不使用归档初始化") }
        override func layoutSubviews() { super.layoutSubviews(); if zoomScale == 1 { photo.frame = bounds; contentSize = bounds.size } }
        func viewForZooming(in scrollView: UIScrollView) -> UIView? { photo }
    }
    func makeUIView(context: Context) -> PhotoScroll { let view = PhotoScroll(); view.photo.image = image; return view }
    func updateUIView(_ view: PhotoScroll,context: Context) {}
}

import AVFoundation
import UIKit
import EyeScreening

/// 实时增量编码，结束时仅封装MP4；队列最多保留两幅源图，不能拖住相机分析。
final class StrabismusDiagnosticVideo {
    static var root: URL { EyeRuntime.recordsDirectory.appendingPathComponent("strabismus-diagnostics", isDirectory: true) }
    private let queue: DispatchQueue
    private let lock = NSLock()
    private var accepting = false
    private struct Source { let buffer: CVPixelBuffer; let face: FaceMetrics?; let time: Int64; let id: String }
    private var latest: Source?
    private var draining = false
    private var captureId = ""
    private var lastSubmitted: Int64 = -1
    private var session: Session?

    init(encodingQueue: DispatchQueue = DispatchQueue(label: "eye.strabismus.video", qos: .utility, autoreleaseFrequency: .workItem)) {
        queue = encodingQueue
    }

    func event(_ json: String) {
        guard let data = json.data(using: .utf8), let row = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return }
        let type = row["event"] as? String ?? ""
        if type == "start" || type == "end" {
            lock.lock(); accepting = type == "start"; if accepting { lastSubmitted = -1; captureId = row["sessionId"] as? String ?? "" }; lock.unlock()
        }
        queue.async { [self] in
            if type == "start" {
                if let old = session { finish(old, reason: "新测试开始") }
                do { session = try Session(row) } catch {
                    session = nil
                    if let id = row["sessionId"] as? String {
                        let directory = Self.root.appendingPathComponent(id, isDirectory: true)
                        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                        var failed = row; failed["state"] = "failed"; failed["error"] = error.localizedDescription
                        if let data = try? JSONSerialization.data(withJSONObject: failed) {
                            try? data.write(to: directory.appendingPathComponent("manifest.json"), options: .atomic)
                        }
                    }
                    lock.lock(); accepting = false; lock.unlock()
                }
            }
            guard let s = session, row["sessionId"] as? String == s.id else { return }
            do {
                try s.log?.write(contentsOf: data + Data([10]))
                if type == "frame", let stamp = (row["captureTimeMs"] as? NSNumber)?.int64Value {
                    s.rows[stamp] = row
                    if let image = s.pending.removeValue(forKey: stamp) { append(s, image, row) }
                    while s.rows.count > 24, let first = s.rows.keys.min() { s.rows.removeValue(forKey: first) }
                } else if type == "end" {
                    s.manifest["audit"] = row
                    lock.lock()
                    let last = latest?.id == s.id ? latest : nil
                    if last != nil { latest = nil }
                    lock.unlock()
                    if let last { process(last) }
                    finish(s, reason: row["reason"] as? String ?? "测试结束"); session = nil
                }
            } catch {
                s.manifest["error"] = error.localizedDescription
                lock.lock(); accepting = false; lock.unlock()
                finish(s, reason: "诊断写入失败，测试本身继续"); session = nil
            }
        }
    }

    /// 仅使用当前帧：原视频不叠标注，眼区直接裁原像素，不做插值定位。
    func capture(_ buffer: CVPixelBuffer, face: FaceMetrics?, time: Int64) {
        lock.lock()
        guard accepting, lastSubmitted < 0 || time - lastSubmitted >= 83 else { lock.unlock(); return }
        lastSubmitted = time
        // 忙时覆盖待处理槽；最多保留正在处理与最新一幅，结束时也能留下真实末帧。
        latest = Source(buffer: buffer, face: face, time: time, id: captureId)
        let launch = !draining; draining = true
        lock.unlock()
        if launch { queue.async { [self] in drainLatest() } }
    }
    private func drainLatest() {
        lock.lock()
        guard let source = latest else { draining = false; lock.unlock(); return }
        latest = nil; lock.unlock()
        process(source)
        queue.async { [self] in drainLatest() }
    }
    private func process(_ source: Source) {
        guard let s = session, source.id == s.id else { return }
        let buffer = source.buffer, time = source.time
        let started = ProcessInfo.processInfo.systemUptime
        defer {
            let elapsed = (ProcessInfo.processInfo.systemUptime - started) * 1000
            s.processingTotal += elapsed; s.processingCount += 1; s.processingMax = max(s.processingMax, elapsed)
        }
        do {
            if s.raw == nil {
                s.base = time
                let creationStart = ProcessInfo.processInfo.systemUptime
                s.raw = try DiagnosticMovie.original(url: s.directory.appendingPathComponent("original.mp4"), width: CVPixelBufferGetWidth(buffer), height: CVPixelBufferGetHeight(buffer))
                s.composite = try DiagnosticMovie(url: s.directory.appendingPathComponent("comparison.mp4"), width: 1280, height: 720, bitrate: 3_000_000)
                s.manifest["encoderCreationMs"] = (ProcessInfo.processInfo.systemUptime - creationStart) * 1000
                s.manifest["sourceWidth"] = CVPixelBufferGetWidth(buffer); s.manifest["sourceHeight"] = CVPixelBufferGetHeight(buffer)
            }
            let extractionStart = ProcessInfo.processInfo.systemUptime
            let images = try extract(source, session: s)
            s.extractionTotal += (ProcessInfo.processInfo.systemUptime - extractionStart) * 1000
            let pts = CMTime(value: max(0, time - s.base), timescale: 1000)
            guard let raw = s.raw else { return }
            s.manifest["originalWidth"] = raw.width; s.manifest["originalHeight"] = raw.height
            let writingStart = ProcessInfo.processInfo.systemUptime
            guard raw.append(images.original ?? buffer, at: pts) else {
                if raw.writer.status == .failed { throw raw.writer.error ?? NSError(domain: "诊断编码", code: 3) }
                s.dropped += 1; return
            }
            s.rawAppendTotal += (ProcessInfo.processInfo.systemUptime - writingStart) * 1000
            let left = images.left, right = images.right
            s.lastLeft = left; s.lastRight = right
            if let row = s.rows.removeValue(forKey: time) { append(s, images, row) }
            else { s.pending[time] = images }
            while s.pending.count > 2, let first = s.pending.keys.min(), let old = s.pending.removeValue(forKey: first) {
                append(s, old, ["phase": "无对应分析信息", "reasons": ["帮助暂停、分析缺失或处理延迟"], "valid": false])
            }
        } catch {
            s.manifest["error"] = error.localizedDescription; s.saveManifest()
            lock.lock(); accepting = false; lock.unlock()
            finish(s, reason: "诊断录制失败，测试本身继续"); session = nil
        }
    }

    /// 直接读取BGRA像素，缩略图与眼区拷贝完成即解锁；不走CoreImage的同步GPU读回。
    private func extract(_ source: Source, session s: Session) throws -> DiagnosticImages {
        let buffer = source.buffer
        guard CVPixelBufferLockBaseAddress(buffer, .readOnly) == kCVReturnSuccess else { throw NSError(domain: "诊断像素", code: 1) }
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        let w = CVPixelBufferGetWidth(buffer), h = CVPixelBufferGetHeight(buffer), stride = CVPixelBufferGetBytesPerRow(buffer)
        guard let bytes = CVPixelBufferGetBaseAddress(buffer) else { throw NSError(domain: "诊断像素", code: 2) }
        let retained = Unmanaged.passRetained(buffer)
        guard let provider = CGDataProvider(dataInfo: retained.toOpaque(), data: UnsafeRawPointer(bytes), size: stride * h, releaseData: { info, _, _ in
            if let info { Unmanaged<CVPixelBuffer>.fromOpaque(info).release() }
        }) else { retained.release(); throw NSError(domain: "诊断像素", code: 2) }
        guard let image = CGImage(width: w, height: h, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: stride,
                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipFirst.rawValue).union(.byteOrder32Little),
                provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent) else { throw NSError(domain: "诊断像素", code: 2) }
        func raster(_ cg: CGImage, width: Int, height: Int, scale: Bool) throws -> CGImage {
            guard let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue) else { throw NSError(domain: "诊断像素", code: 3) }
            context.interpolationQuality = scale ? .low : .none
            context.draw(cg, in: CGRect(x: 0, y: 0, width: width, height: height))
            guard let owned = context.makeImage() else { throw NSError(domain: "诊断像素", code: 4) }
            return owned
        }
        let scale = min(1, 720 / Double(max(w, h)))
        let full = try raster(image, width: max(1, Int(Double(w) * scale)), height: max(1, Int(Double(h) * scale)), scale: true)
        func crop(_ eye: EyeLandmarks?, previous: DiagnosticCrop?) throws -> DiagnosticCrop? {
            let points = eye?.earPoints ?? []
            let rect: CGRect
            if points.count >= 4 {
                let xs = points.map { CGFloat($0.x) }, ys = points.map { CGFloat($0.y) }
                let span = max(8, (xs.max() ?? 0) - (xs.min() ?? 0))
                rect = CGRect(x: (xs.min() ?? 0) - span * 0.4, y: (ys.min() ?? 0) - span * 0.4,
                    width: span * 1.8, height: ((ys.max() ?? 0) - (ys.min() ?? 0)) + span * 0.8).integral
                    .intersection(CGRect(x: 0, y: 0, width: w, height: h))
            } else if let previous { rect = previous.rect } else { return nil }
            guard rect.width > 1, rect.height > 1, let clipped = image.cropping(to: rect) else { return nil }
            let owned = try raster(clipped, width: clipped.width, height: clipped.height, scale: false)
            return DiagnosticCrop(rect: rect, image: owned, inherited: points.count < 4)
        }
        let left = try crop(source.face?.leftEye, previous: s.lastLeft), right = try crop(source.face?.rightEye, previous: s.lastRight)
        var original: CVPixelBuffer?
        if let raw = s.raw, raw.width != w || raw.height != h {
            s.manifest["encodingNote"] = "原画编码降采样至\(raw.width)×\(raw.height)；眼区仍裁原像素"
            guard let destination = raw.pixelBuffer() else { throw NSError(domain: "诊断像素", code: 7) }
            try Self.draw(image, to: destination); original = destination
        }
        return DiagnosticImages(time: source.time, full: full, left: left, right: right, original: original)
    }
    private static func draw(_ image: CGImage, to buffer: CVPixelBuffer) throws {
        guard CVPixelBufferLockBaseAddress(buffer, []) == kCVReturnSuccess else { throw NSError(domain: "诊断像素", code: 5) }
        defer { CVPixelBufferUnlockBaseAddress(buffer, []) }
        let w = CVPixelBufferGetWidth(buffer), h = CVPixelBufferGetHeight(buffer)
        guard let context = CGContext(data: CVPixelBufferGetBaseAddress(buffer), width: w, height: h, bitsPerComponent: 8,
            bytesPerRow: CVPixelBufferGetBytesPerRow(buffer), space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue) else { throw NSError(domain: "诊断像素", code: 6) }
        context.interpolationQuality = .low
        context.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
    }

    private func append(_ s: Session, _ images: DiagnosticImages, _ row: [String: Any]) {
        guard images.time > s.lastComposite else { s.dropped += 1; return }
        let started = ProcessInfo.processInfo.systemUptime
        defer {
            let elapsed = (ProcessInfo.processInfo.systemUptime - started) * 1000
            s.compositionTotal += elapsed; s.compositionCount += 1; s.compositionMax = max(s.compositionMax, elapsed)
        }
        var panelRow = row; panelRow["encodingNote"] = s.manifest["encodingNote"]
        let rendered = DiagnosticPanelRenderer.render(images, row: panelRow, elapsed: Double(images.time - s.base) / 1000)
        guard let cg = rendered.cgImage, let movie = s.composite, let buffer = movie.pixelBuffer() else { s.dropped += 1; return }
        do { try Self.draw(cg, to: buffer) } catch { s.manifest["error"] = error.localizedDescription; s.dropped += 1; return }
        if movie.append(buffer, at: CMTime(value: max(0, images.time - s.base), timescale: 1000)) { s.lastComposite = images.time; s.frames += 1 }
        else { s.dropped += 1 }
    }

    private func finish(_ s: Session, reason: String) {
        guard !s.finishing else { return }; s.finishing = true
        let finishStart = Date()
        for stamp in s.pending.keys.sorted() {
            if let image = s.pending[stamp] { append(s, image, s.rows[stamp] ?? ["phase": "分析缺失", "reasons": ["无同帧诊断信息"], "valid": false]) }
        }
        s.pending.removeAll(); s.rows.removeAll()
        try? s.log?.close(); s.log = nil
        s.manifest["extractionMeanMs"] = s.extractionTotal / Double(max(1, s.processingCount))
        s.manifest["rawAppendMeanMs"] = s.rawAppendTotal / Double(max(1, s.processingCount))
        s.manifest["processingMeanMs"] = s.processingTotal / Double(max(1, s.processingCount))
        s.manifest["processingMaxMs"] = s.processingMax
        s.manifest["compositionMeanMs"] = s.compositionTotal / Double(max(1, s.compositionCount))
        s.manifest["compositionMaxMs"] = s.compositionMax
        s.manifest["state"] = "finishing"; s.manifest["reason"] = reason; s.saveManifest()
        let group = DispatchGroup()
        for movie in [s.raw, s.composite].compactMap({ $0 }) { group.enter(); movie.finish { group.leave() } }
        queue.asyncAfter(deadline: .now() + 10) {
            guard s.manifest["state"] as? String == "finishing" else { return }
            s.manifest["state"] = "failed"; s.manifest["error"] = "编码器收尾超时，可导出逐帧诊断数据"
            s.saveManifest()
        }
        group.notify(queue: queue) {
            let complete = s.raw?.completed == true && s.composite?.completed == true && s.frames > 0 && s.manifest["error"] == nil
            s.manifest["state"] = complete ? "ready" : "failed"
            s.manifest["finalizationMs"] = Int(Date().timeIntervalSince(finishStart) * 1000)
            s.manifest["frames"] = s.frames; s.manifest["droppedFrames"] = s.dropped
            s.manifest["durationSeconds"] = max(0, Double(s.lastComposite - s.base) / 1000)
            if !complete { s.manifest["error"] = s.manifest["error"] ?? s.raw?.error ?? s.composite?.error ?? "没有可编码的视频帧" }
            s.saveManifest()
        }
    }

    private final class Session {
        let id: String; let directory: URL
        var manifest: [String: Any]; var log: FileHandle?
        var raw: DiagnosticMovie?; var composite: DiagnosticMovie?
        var base: Int64 = 0; var lastComposite: Int64 = -1; var frames = 0; var dropped = 0; var finishing = false
        var pending: [Int64: DiagnosticImages] = [:]; var rows: [Int64: [String: Any]] = [:]
        var extractionTotal = 0.0, rawAppendTotal = 0.0
        var processingTotal = 0.0, processingMax = 0.0, compositionTotal = 0.0, compositionMax = 0.0
        var processingCount = 0, compositionCount = 0
        var lastLeft: DiagnosticCrop?; var lastRight: DiagnosticCrop?
        init(_ row: [String: Any]) throws {
            id = row["sessionId"] as? String ?? UUID().uuidString
            directory = StrabismusDiagnosticVideo.root.appendingPathComponent(id, isDirectory: true)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            var backupRoot = StrabismusDiagnosticVideo.root
            var resources = URLResourceValues(); resources.isExcludedFromBackup = true
            try backupRoot.setResourceValues(resources)
            manifest = row; manifest["state"] = "recording"; manifest["maxRecordingFps"] = 12
            let url = directory.appendingPathComponent("frames.jsonl")
            FileManager.default.createFile(atPath: url.path, contents: nil)
            log = try FileHandle(forWritingTo: url); saveManifest()
        }
        func saveManifest() {
            guard let data = try? JSONSerialization.data(withJSONObject: manifest, options: [.sortedKeys]) else { return }
            try? data.write(to: directory.appendingPathComponent("manifest.json"), options: .atomic)
        }
    }
}

private final class DiagnosticMovie {
    let width: Int; let height: Int
    let writer: AVAssetWriter; let input: AVAssetWriterInput; let adaptor: AVAssetWriterInputPixelBufferAdaptor
    var completed: Bool { writer.status == .completed }
    var error: String? { writer.error?.localizedDescription }
    init(url: URL, width: Int, height: Int, bitrate: Int) throws {
        self.width = width; self.height = height
        writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
        let settings: [String: Any] = [AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: width, AVVideoHeightKey: height, AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: bitrate, AVVideoMaxKeyFrameIntervalKey: 24]]
        guard writer.canApply(outputSettings: settings, forMediaType: .video) else { throw NSError(domain: "诊断编码", code: 4) }
        input = AVAssetWriterInput(mediaType: .video, outputSettings: settings)
        input.expectsMediaDataInRealTime = true
        adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA, kCVPixelBufferWidthKey as String: width,
            kCVPixelBufferHeightKey as String: height, kCVPixelBufferIOSurfacePropertiesKey as String: [:]])
        guard writer.canAdd(input) else { throw NSError(domain: "诊断视频", code: 1, userInfo: [NSLocalizedDescriptionKey: "本机编码器不支持当前分辨率"]) }
        writer.add(input)
        guard writer.startWriting() else { throw writer.error ?? NSError(domain: "诊断视频", code: 2) }
        writer.startSession(atSourceTime: .zero)
    }
    static func original(url: URL, width: Int, height: Int) throws -> DiagnosticMovie {
        var failure: Error = NSError(domain: "诊断编码", code: 5)
        var attempted = Set<String>()
        for limit in [max(width, height), 1920, 1280] {
            let scale = min(1, Double(limit) / Double(max(width, height)))
            let w = Int(Double(width) * scale) / 2 * 2, h = Int(Double(height) * scale) / 2 * 2
            guard attempted.insert("\(w)×\(h)").inserted else { continue }
            do { return try DiagnosticMovie(url: url, width: w, height: h, bitrate: 8_000_000) }
            catch { failure = error; try? FileManager.default.removeItem(at: url) }
        }
        throw failure
    }
    func append(_ buffer: CVPixelBuffer, at time: CMTime) -> Bool {
        guard writer.status == .writing, input.isReadyForMoreMediaData else { return false }
        return adaptor.append(buffer, withPresentationTime: time)
    }
    func pixelBuffer() -> CVPixelBuffer? {
        guard let pool = adaptor.pixelBufferPool else { return nil }
        var buffer: CVPixelBuffer?; CVPixelBufferPoolCreatePixelBuffer(nil, pool, &buffer); return buffer
    }
    func finish(_ done: @escaping () -> Void) {
        guard writer.status == .writing else { done(); return }
        input.markAsFinished(); writer.finishWriting(completionHandler: done)
    }
}

struct DiagnosticCrop { let rect: CGRect; let image: CGImage; let inherited: Bool }
struct DiagnosticImages { let time: Int64; let full: CGImage; let left: DiagnosticCrop?; let right: DiagnosticCrop?; var original: CVPixelBuffer? = nil }

/// 四联画使用同一源帧；左上角为相机原画，中间为左右眼原像素和标注，右侧为全屏注视坐标。
enum DiagnosticPanelRenderer {
    static func render(_ images: DiagnosticImages, row: [String: Any], elapsed: Double) -> UIImage {
        let format = UIGraphicsImageRendererFormat(); format.scale = 1; format.opaque = true
        return UIGraphicsImageRenderer(size: CGSize(width: 1280, height: 720), format: format).image { renderer in
            let ctx = renderer.cgContext
            UIColor(EyeStyle.background).setFill(); ctx.fill(CGRect(x: 0, y: 0, width: 1280, height: 720))
            func text(_ value: String, _ rect: CGRect, _ color: UIColor = .white, size: CGFloat = 17) {
                (value as NSString).draw(in: rect, withAttributes: [.font: UIFont.systemFont(ofSize: size), .foregroundColor: color])
            }
            func fit(_ size: CGSize, _ rect: CGRect) -> CGRect {
                let k = min(rect.width / max(1, size.width), rect.height / max(1, size.height))
                return CGRect(x: rect.midX - size.width*k/2, y: rect.midY-size.height*k/2, width: size.width*k, height: size.height*k)
            }
            text(row["encodingNote"] == nil ? "原视频 · 不镜像" : "原画 · 编码降采样 · 不镜像", CGRect(x: 16, y: 12, width: 300, height: 28))
            let full = fit(CGSize(width: images.full.width, height: images.full.height), CGRect(x: 16, y: 48, width: 300, height: 570))
            UIImage(cgImage: images.full).draw(in: full)
            text("原始眼区放大", CGRect(x: 334, y: 12, width: 590, height: 28))
            text("同帧映光检测", CGRect(x: 334, y: 326, width: 600, height: 28))
            for (index, key) in ["left", "right"].enumerated() {
                let crop = index == 0 ? images.left : images.right
                let eye = row[key] as? [String: Any] ?? [:]
                let color = index == 0 ? EyeStyle.diagnosticLeft : EyeStyle.diagnosticRight
                let x = CGFloat(334 + index*300)
                text(index == 0 ? "受测者左眼" : "受测者右眼", CGRect(x: x, y: 46, width: 280, height: 25), color)
                guard let crop else { text("眼区不可定位", CGRect(x: x, y: 140, width: 280, height: 50)); continue }
                let area = CGRect(x: x, y: 78, width: 286, height: 225)
                let raw = fit(CGSize(width: crop.image.width, height: crop.image.height), area)
                UIImage(cgImage: crop.image).draw(in: raw)
                let overlay = raw.offsetBy(dx: 0, dy: 280)
                UIImage(cgImage: crop.image).draw(in: overlay)
                func point(_ a: Any?) -> CGPoint? {
                    guard let a = a as? [NSNumber], a.count >= 2 else { return nil }
                    return CGPoint(x: overlay.minX + (CGFloat(a[0].doubleValue)-crop.rect.minX)*overlay.width/crop.rect.width,
                                   y: overlay.minY + (CGFloat(a[1].doubleValue)-crop.rect.minY)*overlay.height/crop.rect.height)
                }
                let marker = point(eye["glint"])
                if let marker {
                    ctx.saveGState(); ctx.clip(to: overlay); ctx.setLineWidth(2)
                    ctx.setStrokeColor(EyeStyle.diagnosticGlint.cgColor)
                    ctx.strokeEllipse(in: CGRect(x: marker.x-6, y: marker.y-6, width: 12, height: 12))
                    ctx.restoreGState()
                }
                let summary: String
                if crop.inherited { summary = "眼区定位暂缺" }
                else if marker == nil { summary = eye["rejection"] as? String ?? "未检测到映光点" }
                else if let confidence = eye["glintConfidence"] as? NSNumber, confidence.doubleValue.isFinite {
                    let source = eye["glintCodeMatched"] as? Bool == true ? "光码确认" : "候选亮斑"
                    summary = "置信度 \(Int(confidence.doubleValue * 100))% · \(source)"
                } else { summary = "已检出 · 置信度未记录" }
                text(summary, CGRect(x: x, y: 588, width: 286, height: 50), color, size: 14)
            }
            let displayW = (row["displayWidth"] as? NSNumber)?.doubleValue ?? 390
            let displayH = (row["displayHeight"] as? NSNumber)?.doubleValue ?? 844
            let phone = fit(CGSize(width: displayW, height: displayH), CGRect(x: 972, y: 60, width: 280, height: 540))
            UIColor.black.setFill(); UIBezierPath(roundedRect: phone, cornerRadius: 20).fill()
            UIColor(EyeStyle.secondary).setStroke(); UIBezierPath(roundedRect: phone, cornerRadius: 20).stroke()
            text("手机屏幕 · 全屏坐标", CGRect(x: 968, y: 12, width: 300, height: 28))
            for (key, color, radius) in [("target", UIColor(EyeStyle.primary), CGFloat(7)), ("gaze", EyeStyle.diagnosticReference, CGFloat(7))] {
                if key == "target" && row["targetVisible"] as? Bool == false { continue }
                let color = key == "target" && row["targetColor"] as? String == "white" ? EyeStyle.diagnosticGlint : color
                guard let a = row[key] as? [NSNumber], a.count >= 2 else { continue }
                let px = a[0].doubleValue, py = a[1].doubleValue
                guard px.isFinite, py.isFinite else { continue }
                let p = CGPoint(x: phone.minX + CGFloat(min(1,max(0,px)))*phone.width, y: phone.minY+CGFloat(min(1,max(0,py)))*phone.height)
                color.setStroke(); color.setFill()
                if key == "target" { UIBezierPath(ovalIn: CGRect(x: p.x-radius, y: p.y-radius, width: radius*2, height: radius*2)).fill() }
                else {
                    let cross = UIBezierPath(); cross.move(to: CGPoint(x:p.x-radius,y:p.y)); cross.addLine(to: CGPoint(x:p.x+radius,y:p.y))
                    cross.move(to: CGPoint(x:p.x,y:p.y-radius)); cross.addLine(to: CGPoint(x:p.x,y:p.y+radius)); cross.lineWidth = 2; cross.stroke()
                    if px < 0 || px > 1 || py < 0 || py > 1 { text("屏外估计", CGRect(x: phone.minX, y: phone.maxY+2, width: 200, height: 20), color, size: 13) }
                }
            }
            let usesIrisMotion = row["inputLeft"] != nil && row["calibrated"] == nil
            text(usesIrisMotion ? "实心：实际目标 · 眼部标注见左侧" : "实心：实际目标   绿色：双眼融合注视点", CGRect(x: 334, y: 623, width: 890, height: 24), UIColor(EyeStyle.primary), size: 15)
            let phaseKey = row["phase"] as? String ?? "未知阶段"
            let phase = ["GAZE_CALIBRATION": "注视标定", "FIXATION": "定点映光", "FAR_FIXATION": "看远", "COVER": "交替遮盖", "PURSUIT": "移动点注视"][phaseKey] ?? phaseKey
            let acquisition = usesIrisMotion ? (row["valid"] as? Bool == true ? "采集质量合格" : "本帧质量未通过") : phaseKey == "GAZE_CALIBRATION" && row["calibrationFailed"] as? Bool != true
                ? "标定中 · 入样 \(row["calibrationSamples"] as? Int ?? 0)"
                : (row["calibrated"] as? Bool == true ? "已标定" : "未获得可靠标定，不绘制估计")
            func number(_ key: String) -> String {
                guard let n = (row[key] as? NSNumber)?.doubleValue, n.isFinite, n > 0 else { return "—" }
                return String(format: "%.1f", n)
            }
            let status = String(format: "%.2f s", elapsed) + "  \(phase)  \(acquisition)  距离 \(number("distanceCm")) cm  分析 \(number("fps")) 帧/秒"
            text(status, CGRect(x: 16, y: 646, width: 1230, height: 24))
            text(row["warning"] as? String ?? "", CGRect(x: 16, y: 672, width: 1240, height: 22), UIColor(EyeStyle.secondary), size: 14)
            let reasons = (row["reasons"] as? [String] ?? []).joined(separator: "；")
            text(reasons.isEmpty ? "质量门控通过；标注只是诊断观测" : reasons, CGRect(x: 16, y: 696, width: 1240, height: 24), UIColor(EyeStyle.secondary), size: 14)
        }
    }
}

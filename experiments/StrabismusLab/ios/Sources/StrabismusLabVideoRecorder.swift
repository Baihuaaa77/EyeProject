import AVFoundation
import CoreGraphics
import CoreImage
import CoreMedia
import CoreVideo
import EyeScreening
import Foundation
import UIKit

/// StrabismusLab iOS 诊断视频双路管线：
/// 1) 采集期间零阻塞录制高清原始视频 `original.mp4`（记录每帧与 `report.frames` 的精确帧号映射）；
/// 2) 测试结束完成 v0.9 双向光码解调与 3D 球面光学反演后，离线合成叠加可视化视频 `annotated.mp4`
///    （含双眼 4× 眼区特写画中画、虹膜外圈、0.80R 搜索边界、v0.8 锁定映光准星、光码相位灯与实时 3D HDE/VDE 读数）。
final class StrabismusLabVideoRecorder {
    struct RecordedFrameStamp {
        let frameIndex: Int32
        let ptsMs: Int64
    }

    private let queue = DispatchQueue(label: "com.zkyjss.strabismuslab.video", qos: .utility, autoreleaseFrequency: .workItem)
    private let ciContext = CIContext(options: [.cacheIntermediates: false])
    private let lock = NSLock()

    private var active = false
    private var sessionId = ""
    private var outputURL: URL?
    private var writer: AVAssetWriter?
    private var input: AVAssetWriterInput?
    private var adaptor: AVAssetWriterInputPixelBufferAdaptor?
    private var baseTimeMs: Int64 = -1
    private var lastPtsMs: Int64 = -1
    private var videoWidth = 720
    private var videoHeight = 1280
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var stamps: [RecordedFrameStamp] = []

    /// 单槽防堆积，绝不拖慢 30fps 相机推理队列。
    private struct PendingFrame {
        let buffer: CVPixelBuffer
        let frameIndex: Int32
        let timeMs: Int64
        let sessionId: String
    }
    private var pending: PendingFrame?
    private var draining = false

    func startSession(sessionId: String, directory: URL) {
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appendingPathComponent("original.mp4")
        lock.lock()
        self.active = true
        self.sessionId = sessionId
        self.pending = nil
        lock.unlock()

        queue.async { [weak self] in
            guard let self = self else { return }
            self.abortWriterLocked()
            try? FileManager.default.removeItem(at: url)
            self.outputURL = url
            self.baseTimeMs = -1
            self.lastPtsMs = -1
            self.sourceWidth = 0
            self.sourceHeight = 0
            self.stamps.removeAll(keepingCapacity: true)
        }
    }

    func cancelSession() {
        lock.lock()
        active = false
        pending = nil
        lock.unlock()
        queue.async { [weak self] in
            guard let self = self else { return }
            let oldURL = self.outputURL
            self.abortWriterLocked()
            if let u = oldURL { try? FileManager.default.removeItem(at: u) }
            self.outputURL = nil
            self.stamps.removeAll()
        }
    }

    /// 由相机队列在每帧送入引擎后立刻调用；仅保留最新待编码帧，零阻塞。
    func appendFrame(_ buffer: CVPixelBuffer, frameIndex: Int32, timeMs: Int64) {
        lock.lock()
        guard active, frameIndex > 0 else {
            lock.unlock()
            return
        }
        pending = PendingFrame(buffer: buffer, frameIndex: frameIndex, timeMs: timeMs, sessionId: sessionId)
        let shouldLaunch = !draining
        draining = true
        lock.unlock()
        if shouldLaunch {
            queue.async { [weak self] in self?.drainPending() }
        }
    }

    private func drainPending() {
        while true {
            lock.lock()
            guard let item = pending else {
                draining = false
                lock.unlock()
                return
            }
            pending = nil
            let stillActive = active && item.sessionId == sessionId
            lock.unlock()
            if stillActive {
                encodeFrame(item)
            }
        }
    }

    private func encodeFrame(_ item: PendingFrame) {
        guard let url = outputURL else { return }
        let srcW = CVPixelBufferGetWidth(item.buffer)
        let srcH = CVPixelBufferGetHeight(item.buffer)
        guard srcW > 0, srcH > 0 else { return }

        if writer == nil {
            sourceWidth = srcW
            sourceHeight = srcH
            let scale = min(1.0, 1280.0 / Double(max(srcW, srcH)))
            videoWidth = max(2, Int((Double(srcW) * scale / 2.0).rounded()) * 2)
            videoHeight = max(2, Int((Double(srcH) * scale / 2.0).rounded()) * 2)
            guard setupWriter(url: url, width: videoWidth, height: videoHeight) else { return }
            baseTimeMs = item.timeMs
            lastPtsMs = -1
        }

        guard let writer = writer, let input = input, let adaptor = adaptor,
              writer.status == .writing, input.isReadyForMoreMediaData else { return }

        var ptsMs = max(0, item.timeMs - baseTimeMs)
        if ptsMs <= lastPtsMs { ptsMs = lastPtsMs + 1 }
        guard let pool = adaptor.pixelBufferPool else { return }

        var outBuffer: CVPixelBuffer?
        let status = CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, pool, &outBuffer)
        guard status == kCVReturnSuccess, let dst = outBuffer else { return }

        let sx = CGFloat(videoWidth) / CGFloat(srcW)
        let sy = CGFloat(videoHeight) / CGFloat(srcH)
        let ciImg = CIImage(cvPixelBuffer: item.buffer).transformed(by: CGAffineTransform(scaleX: sx, y: sy))
        ciContext.render(ciImg, to: dst)

        let pts = CMTime(value: ptsMs, timescale: 1000)
        if adaptor.append(dst, withPresentationTime: pts) {
            lastPtsMs = ptsMs
            stamps.append(RecordedFrameStamp(frameIndex: item.frameIndex, ptsMs: ptsMs))
        }
    }

    private func setupWriter(url: URL, width: Int, height: Int) -> Bool {
        do {
            let w = try AVAssetWriter(outputURL: url, fileType: .mp4)
            let settings: [String: Any] = [
                AVVideoCodecKey: AVVideoCodecType.h264,
                AVVideoWidthKey: width,
                AVVideoHeightKey: height,
                AVVideoCompressionPropertiesKey: [
                    AVVideoAverageBitRateKey: 4_500_000,
                    AVVideoMaxKeyFrameIntervalKey: 30,
                    AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel,
                ],
            ]
            let inp = AVAssetWriterInput(mediaType: .video, outputSettings: settings)
            inp.expectsMediaDataInRealTime = true
            let attrs: [String: Any] = [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                kCVPixelBufferWidthKey as String: width,
                kCVPixelBufferHeightKey as String: height,
                kCVPixelBufferIOSurfacePropertiesKey as String: [:],
            ]
            let adp = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: inp, sourcePixelBufferAttributes: attrs)
            guard w.canAdd(inp) else { return false }
            w.add(inp)
            guard w.startWriting() else { return false }
            w.startSession(atSourceTime: .zero)
            self.writer = w
            self.input = inp
            self.adaptor = adp
            return true
        } catch {
            return false
        }
    }

    private func abortWriterLocked() {
        writer?.cancelWriting()
        writer = nil
        input = nil
        adaptor = nil
    }

    /// 结束原始视频封箱，随后离线叠加 v0.9 最终解调结果与双眼 4× 放大特写，输出 `annotated.mp4`。
    func finishAndRenderAnnotated(
        report: LabSessionReport,
        directory: URL,
        onProgress: @escaping (Double) -> Void,
        completion: @escaping (URL?, URL?) -> Void
    ) {
        lock.lock()
        active = false
        let lastPending = pending
        pending = nil
        lock.unlock()

        queue.async { [weak self] in
            guard let self = self else {
                DispatchQueue.main.async { completion(nil, nil) }
                return
            }
            if let lastPending = lastPending {
                self.encodeFrame(lastPending)
            }
            guard let rawURL = self.outputURL, let w = self.writer, let inp = self.input else {
                DispatchQueue.main.async { completion(nil, nil) }
                return
            }
            let capturedStamps = self.stamps
            let srcW = self.sourceWidth > 0 ? self.sourceWidth : Int(report.sourceWidth)
            let srcH = self.sourceHeight > 0 ? self.sourceHeight : Int(report.sourceHeight)

            inp.markAsFinished()
            let sema = DispatchSemaphore(value: 0)
            w.finishWriting { sema.signal() }
            sema.wait()
            let ok = (w.status == .completed)
            self.writer = nil
            self.input = nil
            self.adaptor = nil
            self.outputURL = nil

            guard ok, FileManager.default.fileExists(atPath: rawURL.path) else {
                DispatchQueue.main.async { completion(nil, nil) }
                return
            }

            let annotatedURL = directory.appendingPathComponent("annotated.mp4")
            try? FileManager.default.removeItem(at: annotatedURL)
            let rendered = Self.renderOverlayVideo(
                inputURL: rawURL,
                outputURL: annotatedURL,
                report: report,
                stamps: capturedStamps,
                sourceWidth: max(1, srcW),
                sourceHeight: max(1, srcH),
                onProgress: onProgress
            )
            DispatchQueue.main.async {
                completion(rawURL, rendered ? annotatedURL : nil)
            }
        }
    }

    // MARK: - 离线科研级叠加渲染引擎

    private static func renderOverlayVideo(
        inputURL: URL,
        outputURL: URL,
        report: LabSessionReport,
        stamps: [RecordedFrameStamp],
        sourceWidth: Int,
        sourceHeight: Int,
        onProgress: @escaping (Double) -> Void
    ) -> Bool {
        let asset = AVURLAsset(url: inputURL)
        guard let track = asset.tracks(withMediaType: .video).first else { return false }
        let natural = track.naturalSize
        let outW = max(2, Int(natural.width))
        let outH = max(2, Int(natural.height))

        guard let reader = try? AVAssetReader(asset: asset) else { return false }
        let readOutput = AVAssetReaderTrackOutput(
            track: track,
            outputSettings: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        )
        readOutput.alwaysCopiesSampleData = false
        guard reader.canAdd(readOutput) else { return false }
        reader.add(readOutput)

        guard let writer = try? AVAssetWriter(outputURL: outputURL, fileType: .mp4) else { return false }
        let writeInput = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: outW,
            AVVideoHeightKey: outH,
            AVVideoCompressionPropertiesKey: [
                AVVideoAverageBitRateKey: 5_000_000,
                AVVideoMaxKeyFrameIntervalKey: 30,
                AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel,
            ],
        ])
        writeInput.expectsMediaDataInRealTime = false
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(
            assetWriterInput: writeInput,
            sourcePixelBufferAttributes: [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                kCVPixelBufferWidthKey as String: outW,
                kCVPixelBufferHeightKey as String: outH,
                kCVPixelBufferIOSurfacePropertiesKey as String: [:],
            ]
        )
        guard writer.canAdd(writeInput) else { return false }
        writer.add(writeInput)

        guard reader.startReading(), writer.startWriting() else { return false }
        writer.startSession(atSourceTime: .zero)

        let frameByIndex: [Int32: LabFrameLog] = Dictionary(
            uniqueKeysWithValues: report.frames.map { ($0.index, $0) }
        )
        let coverOnsetSecs = report.coverRefixations.map { ($0.uncoveredAtSec, $0.eventType, $0.eye) }
        let searchRadius = CGFloat(report.config.searchRadiusFraction)
        let ciContext = CIContext(options: [.cacheIntermediates: false])
        let colorSpace = CGColorSpaceCreateDeviceRGB()

        let totalCount = max(1, stamps.count)
        var frameIdx = 0

        while true {
            var keepGoing = true
            autoreleasepool {
                guard reader.status == .reading,
                      let sample = readOutput.copyNextSampleBuffer(),
                      let srcBuf = CMSampleBufferGetImageBuffer(sample) else {
                    keepGoing = false
                    return
                }
                let pts = CMSampleBufferGetPresentationTimeStamp(sample)
                let ptsMs = Int64((CMTimeGetSeconds(pts) * 1000.0).rounded())

                // 匹配对应的 LabFrameLog（优先按精确录制顺序，回退按时间戳最近邻）
                let matchedLog: LabFrameLog? = {
                    if frameIdx < stamps.count {
                        let stamp = stamps[frameIdx]
                        if abs(stamp.ptsMs - ptsMs) <= 80, let log = frameByIndex[stamp.frameIndex] {
                            return log
                        }
                    }
                    if let nearest = stamps.min(by: { abs($0.ptsMs - ptsMs) < abs($1.ptsMs - ptsMs) }) {
                        return frameByIndex[nearest.frameIndex]
                    }
                    return nil
                }()

                while !writeInput.isReadyForMoreMediaData && writer.status == .writing {
                    Thread.sleep(forTimeInterval: 0.002)
                }
                guard writer.status == .writing, let pool = adaptor.pixelBufferPool else { return }

                var dstOpt: CVPixelBuffer?
                guard CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, pool, &dstOpt) == kCVReturnSuccess,
                      let dstBuf = dstOpt else { return }

                if let cgImg = ciContext.createCGImage(CIImage(cvPixelBuffer: srcBuf), from: CGRect(x: 0, y: 0, width: outW, height: outH)) {
                    drawAnnotatedFrame(
                        into: dstBuf,
                        baseImage: cgImg,
                        log: matchedLog,
                        outW: outW,
                        outH: outH,
                        sourceW: sourceWidth,
                        sourceH: sourceHeight,
                        searchRadius: searchRadius,
                        coverEvents: coverOnsetSecs,
                        colorSpace: colorSpace
                    )
                    adaptor.append(dstBuf, withPresentationTime: pts)
                }

                frameIdx += 1
                if frameIdx % 15 == 0 {
                    let p = min(0.99, Double(frameIdx) / Double(totalCount))
                    DispatchQueue.main.async { onProgress(p) }
                }
            }
            if !keepGoing { break }
        }

        writeInput.markAsFinished()
        let sema = DispatchSemaphore(value: 0)
        writer.finishWriting { sema.signal() }
        sema.wait()
        DispatchQueue.main.async { onProgress(1.0) }
        return writer.status == .completed
    }

    private static func drawAnnotatedFrame(
        into buffer: CVPixelBuffer,
        baseImage: CGImage,
        log: LabFrameLog?,
        outW: Int,
        outH: Int,
        sourceW: Int,
        sourceH: Int,
        searchRadius: CGFloat,
        coverEvents: [(Double, String, String)],
        colorSpace: CGColorSpace
    ) {
        CVPixelBufferLockBaseAddress(buffer, [])
        defer { CVPixelBufferUnlockBaseAddress(buffer, []) }
        guard let base = CVPixelBufferGetBaseAddress(buffer) else { return }
        let stride = CVPixelBufferGetBytesPerRow(buffer)
        let bitmapInfo = CGBitmapInfo.byteOrder32Little.rawValue | CGImageAlphaInfo.premultipliedFirst.rawValue

        guard let ctx = CGContext(
            data: base,
            width: outW,
            height: outH,
            bitsPerComponent: 8,
            bytesPerRow: stride,
            space: colorSpace,
            bitmapInfo: bitmapInfo
        ) else { return }

        let W = CGFloat(outW)
        let H = CGFloat(outH)
        let sx = W / CGFloat(sourceW)
        let sy = H / CGFloat(sourceH)

        // 1) 底图：正立绘制原始相机画面
        ctx.draw(baseImage, in: CGRect(x: 0, y: 0, width: W, height: H))

        // 2) 切换到左上角原点坐标系，与 LabFrameLog / UIKit 绘制完全对齐
        ctx.saveGState()
        ctx.translateBy(x: 0, y: H)
        ctx.scaleBy(x: 1, y: -1)
        UIGraphicsPushContext(ctx)
        defer {
            UIGraphicsPopContext()
            ctx.restoreGState()
        }

        let colCyan = UIColor(red: 56/255, green: 189/255, blue: 248/255, alpha: 1)
        let colAmber = UIColor(red: 245/255, green: 158/255, blue: 11/255, alpha: 1)
        let colGreen = UIColor(red: 34/255, green: 197/255, blue: 94/255, alpha: 1)
        let colWarn = UIColor(red: 251/255, green: 191/255, blue: 36/255, alpha: 1)
        let colRed = UIColor(red: 244/255, green: 63/255, blue: 94/255, alpha: 1)
        let colInk = UIColor(red: 241/255, green: 245/255, blue: 249/255, alpha: 1)
        let colSub = UIColor(red: 148/255, green: 163/255, blue: 184/255, alpha: 1)
        let colPanel = UIColor(red: 9/255, green: 12/255, blue: 16/255, alpha: 0.78)

        func drawEyeOverlay(
            irisX: Float?, irisY: Float?, irisD: Float?,
            glintX: Float?, glintY: Float?,
            source: LabGlintSource,
            tint: UIColor
        ) {
            guard let ix = irisX, let iy = irisY, let id = irisD, id > 0 else { return }
            let cx = CGFloat(ix) * sx
            let cy = CGFloat(iy) * sy
            let r = CGFloat(id) * 0.5 * sx

            // 完整虹膜边缘
            ctx.setStrokeColor(UIColor(white: 0.85, alpha: 0.75).cgColor)
            ctx.setLineWidth(1.5)
            ctx.setLineDash(phase: 0, lengths: [])
            ctx.strokeEllipse(in: CGRect(x: cx - r, y: cy - r, width: r * 2, height: r * 2))

            // 0.80R 搜索圈
            let sr = r * searchRadius
            ctx.setStrokeColor(tint.withAlphaComponent(0.9).cgColor)
            ctx.setLineWidth(1.8)
            ctx.setLineDash(phase: 0, lengths: [6, 4])
            ctx.strokeEllipse(in: CGRect(x: cx - sr, y: cy - sr, width: sr * 2, height: sr * 2))
            ctx.setLineDash(phase: 0, lengths: [])

            // 虹膜中心细十字
            ctx.setStrokeColor(tint.withAlphaComponent(0.6).cgColor)
            ctx.setLineWidth(1.0)
            ctx.move(to: CGPoint(x: cx - 5, y: cy)); ctx.addLine(to: CGPoint(x: cx + 5, y: cy))
            ctx.move(to: CGPoint(x: cx, y: cy - 5)); ctx.addLine(to: CGPoint(x: cx, y: cy + 5))
            ctx.strokePath()

            // v0.8 最终角膜映光准星
            if let gx = glintX, let gy = glintY {
                let px = CGFloat(gx) * sx
                let py = CGFloat(gy) * sy
                if source == .observed || source == .weak {
                    let gCol = source == .observed ? colGreen : colCyan
                    ctx.setStrokeColor(gCol.cgColor)
                    ctx.setLineWidth(2.0)
                    ctx.move(to: CGPoint(x: px - 8, y: py)); ctx.addLine(to: CGPoint(x: px + 8, y: py))
                    ctx.move(to: CGPoint(x: px, y: py - 8)); ctx.addLine(to: CGPoint(x: px, y: py + 8))
                    ctx.strokePath()
                    ctx.strokeEllipse(in: CGRect(x: px - 4, y: py - 4, width: 8, height: 8))
                } else if source == .predicted {
                    ctx.setStrokeColor(colWarn.withAlphaComponent(0.75).cgColor)
                    ctx.setLineWidth(1.4)
                    ctx.setLineDash(phase: 0, lengths: [3, 3])
                    ctx.strokeEllipse(in: CGRect(x: px - 5, y: py - 5, width: 10, height: 10))
                    ctx.setLineDash(phase: 0, lengths: [])
                }
            }
        }

        // 双眼 4× 放大特写画中画（左上 = 左眼 L，右上 = 右眼 R）
        func drawEyeZoomBox(
            label: String,
            boxRect: CGRect,
            irisX: Float?, irisY: Float?, irisD: Float?,
            glintX: Float?, glintY: Float?,
            source: LabGlintSource,
            tint: UIColor
        ) {
            ctx.setFillColor(colPanel.cgColor)
            ctx.fill(boxRect)
            if let ix = irisX, let iy = irisY, let id = irisD, id > 4 {
                let cx = CGFloat(ix) * sx
                let cy = CGFloat(iy) * sy
                let cropHalf = max(24.0, CGFloat(id) * sx * 1.25)
                let cropRect = CGRect(
                    x: max(0, min(W - cropHalf * 2, cx - cropHalf)),
                    y: max(0, min(H - cropHalf * 2, cy - cropHalf)),
                    width: min(W, cropHalf * 2),
                    height: min(H, cropHalf * 2)
                )
                if let sub = baseImage.cropping(to: cropRect) {
                    // CGContext.draw 在当前翻转坐标系中需局部再翻转才能正立画出裁剪块
                    ctx.saveGState()
                    ctx.translateBy(x: boxRect.minX, y: boxRect.maxY)
                    ctx.scaleBy(x: 1, y: -1)
                    ctx.draw(sub, in: CGRect(x: 0, y: 0, width: boxRect.width, height: boxRect.height))
                    ctx.restoreGState()

                    let zsx = boxRect.width / max(1, cropRect.width)
                    let zsy = boxRect.height / max(1, cropRect.height)
                    let zIx = boxRect.minX + (cx - cropRect.minX) * zsx
                    let zIy = boxRect.minY + (cy - cropRect.minY) * zsy
                    let zR = CGFloat(id) * 0.5 * sx * zsx

                    ctx.setStrokeColor(UIColor(white: 0.9, alpha: 0.8).cgColor)
                    ctx.setLineWidth(1.4)
                    ctx.strokeEllipse(in: CGRect(x: zIx - zR, y: zIy - zR, width: zR * 2, height: zR * 2))

                    let zSr = zR * searchRadius
                    ctx.setStrokeColor(tint.withAlphaComponent(0.9).cgColor)
                    ctx.setLineWidth(1.8)
                    ctx.setLineDash(phase: 0, lengths: [6, 4])
                    ctx.strokeEllipse(in: CGRect(x: zIx - zSr, y: zIy - zSr, width: zSr * 2, height: zSr * 2))
                    ctx.setLineDash(phase: 0, lengths: [])

                    // 虹膜中心蓝/黄细十字
                    ctx.setStrokeColor(tint.cgColor)
                    ctx.setLineWidth(1.2)
                    ctx.move(to: CGPoint(x: zIx - 8, y: zIy)); ctx.addLine(to: CGPoint(x: zIx + 8, y: zIy))
                    ctx.move(to: CGPoint(x: zIx, y: zIy - 8)); ctx.addLine(to: CGPoint(x: zIx, y: zIy + 8))
                    ctx.strokePath()

                    if let gx = glintX, let gy = glintY {
                        let zGx = boxRect.minX + (CGFloat(gx) * sx - cropRect.minX) * zsx
                        let zGy = boxRect.minY + (CGFloat(gy) * sy - cropRect.minY) * zsy
                        let col = (source == .observed) ? colGreen : (source == .weak ? colCyan : colWarn)
                        ctx.setStrokeColor(col.cgColor)
                        ctx.setLineWidth(2.2)
                        ctx.move(to: CGPoint(x: zGx - 11, y: zGy)); ctx.addLine(to: CGPoint(x: zGx + 11, y: zGy))
                        ctx.move(to: CGPoint(x: zGx, y: zGy - 11)); ctx.addLine(to: CGPoint(x: zGx, y: zGy + 11))
                        ctx.strokePath()
                        ctx.strokeEllipse(in: CGRect(x: zGx - 6, y: zGy - 6, width: 12, height: 12))
                    }
                }
            }
            ctx.setStrokeColor(tint.cgColor)
            ctx.setLineWidth(2.0)
            ctx.stroke(boxRect)

            let badge = NSAttributedString(
                string: " \(label) · \(source.label) ",
                attributes: [
                    .font: UIFont.monospacedSystemFont(ofSize: 13, weight: .bold),
                    .foregroundColor: tint,
                    .backgroundColor: colPanel,
                ]
            )
            badge.draw(at: CGPoint(x: boxRect.minX + 4, y: boxRect.minY + 4))
        }

        if let f = log {
            drawEyeOverlay(
                irisX: f.leftIrisX?.floatValue, irisY: f.leftIrisY?.floatValue, irisD: f.leftIrisDiameterPx?.floatValue,
                glintX: f.leftGlintX?.floatValue, glintY: f.leftGlintY?.floatValue,
                source: f.leftGlintSource, tint: colCyan
            )
            drawEyeOverlay(
                irisX: f.rightIrisX?.floatValue, irisY: f.rightIrisY?.floatValue, irisD: f.rightIrisDiameterPx?.floatValue,
                glintX: f.rightGlintX?.floatValue, glintY: f.rightGlintY?.floatValue,
                source: f.rightGlintSource, tint: colAmber
            )

            // 顶部信息栏
            let topBar = CGRect(x: 0, y: 0, width: W, height: 76)
            ctx.setFillColor(colPanel.cgColor)
            ctx.fill(topBar)

            let phaseColor: UIColor = {
                switch f.leftEmissionPhase {
                case .on: return colGreen
                case .off, .dim: return colSub
                case .transition: return colWarn
                default: return colCyan
                }
            }()
            ctx.setFillColor(phaseColor.cgColor)
            ctx.fillEllipse(in: CGRect(x: 16, y: 18, width: 12, height: 12))

            let headerStr = "StrabismusLab v0.9 · \(f.stage.title) · #\(f.index) · \(String(format: "%.2fs", f.elapsedSec))"
            NSAttributedString(string: headerStr, attributes: [
                .font: UIFont.monospacedSystemFont(ofSize: 16, weight: .bold),
                .foregroundColor: colInk,
            ]).draw(at: CGPoint(x: 36, y: 14))

            let yawS = f.yawDeg.map { String(format: "%+.0f", $0.floatValue) } ?? "-"
            let pitS = f.pitchDeg.map { String(format: "%+.0f", $0.floatValue) } ?? "-"
            let rolS = f.rollDeg.map { String(format: "%+.0f", $0.floatValue) } ?? "-"
            let subStr = "d=\(String(format: "%.1f", f.distanceCm))cm · Y/P/R \(yawS)/\(pitS)/\(rolS)° · 相位 \(f.leftEmissionPhase.label) · 刺激 \(String(format: "%.0f%%", f.stimulusIntensity * 100))"
            NSAttributedString(string: subStr, attributes: [
                .font: UIFont.monospacedSystemFont(ofSize: 13, weight: .medium),
                .foregroundColor: colSub,
            ]).draw(at: CGPoint(x: 16, y: 44))

            // 左右眼 4× 放大特写画中画
            let boxSize: CGFloat = min(184, W * 0.27)
            drawEyeZoomBox(
                label: "L 4×",
                boxRect: CGRect(x: 14, y: 88, width: boxSize, height: boxSize),
                irisX: f.leftIrisX?.floatValue, irisY: f.leftIrisY?.floatValue, irisD: f.leftIrisDiameterPx?.floatValue,
                glintX: f.leftGlintX?.floatValue, glintY: f.leftGlintY?.floatValue,
                source: f.leftGlintSource, tint: colCyan
            )
            drawEyeZoomBox(
                label: "R 4×",
                boxRect: CGRect(x: W - boxSize - 14, y: 88, width: boxSize, height: boxSize),
                irisX: f.rightIrisX?.floatValue, irisY: f.rightIrisY?.floatValue, irisD: f.rightIrisDiameterPx?.floatValue,
                glintX: f.rightGlintX?.floatValue, glintY: f.rightGlintY?.floatValue,
                source: f.rightGlintSource, tint: colAmber
            )

            // 遮盖拐点高亮横幅（若处于精确拐点后 0..600ms 窗口）
            if let hit = coverEvents.first(where: { f.elapsedSec >= $0.0 && f.elapsedSec <= $0.0 + 0.65 }) {
                let tag = hit.1 == "COVER_CONTRALATERAL" ? "显斜检测窗 · 对侧眼 \(hit.2)" : "隐斜/IXT 复位窗 · 同侧眼 \(hit.2)"
                let bannerRect = CGRect(x: 16, y: H - 142, width: W - 32, height: 30)
                ctx.setFillColor(colWarn.withAlphaComponent(0.9).cgColor)
                ctx.fill(bannerRect)
                NSAttributedString(string: " \(tag) (dt=\(String(format: "+%.0f", (f.elapsedSec - hit.0) * 1000))ms)", attributes: [
                    .font: UIFont.monospacedSystemFont(ofSize: 14, weight: .bold),
                    .foregroundColor: UIColor.black,
                ]).draw(at: CGPoint(x: 22, y: H - 137))
            }

            // 底部科研遥测条
            let botBar = CGRect(x: 0, y: H - 104, width: W, height: 104)
            ctx.setFillColor(colPanel.cgColor)
            ctx.fill(botBar)

            let h3d = f.hde3dPrism.map { String(format: "%+.1fΔ", $0.doubleValue) } ?? "—"
            let v3d = f.vde3dPrism.map { String(format: "%+.1fΔ", $0.doubleValue) } ?? "—"
            let hLin = f.hdePrism.map { String(format: "%+.1fΔ", $0.doubleValue) } ?? "—"
            let line1 = "3D HDE \(h3d) · 3D VDE \(v3d)   (线性对照 \(hLin))"
            let mainColor = (f.hde3dPrism.map { abs($0.doubleValue) >= 10.0 } ?? false) ? colRed : colCyan
            NSAttributedString(string: line1, attributes: [
                .font: UIFont.monospacedSystemFont(ofSize: 18, weight: .bold),
                .foregroundColor: mainColor,
            ]).draw(at: CGPoint(x: 16, y: H - 92))

            let lVis = f.leftVisibilityContrast.map { String(format: "%.0f", $0.floatValue) } ?? "-"
            let rVis = f.rightVisibilityContrast.map { String(format: "%.0f", $0.floatValue) } ?? "-"
            let cov = f.coveredEye ?? "NONE"
            let rollCorr = f.rollCorrectionDeg.map { String(format: "%+.1f°", $0.doubleValue) } ?? "-"
            let line2 = "遮盖:\(cov) · 可见对比度 L:\(lVis) R:\(rVis) · Roll旋正:\(rollCorr) · 候选:\(f.leftCandidateCount)/\(f.rightCandidateCount)"
            NSAttributedString(string: line2, attributes: [
                .font: UIFont.monospacedSystemFont(ofSize: 13, weight: .medium),
                .foregroundColor: colInk,
            ]).draw(at: CGPoint(x: 16, y: H - 56))

            let line3 = f.rejectionReason.map { "拒判: \($0)" } ?? "帧有效 · 绿十字=亮灯观测 / 琥珀虚圈=灭灯预测 / 虚线框=\(Int(searchRadius * 100))%R"
            NSAttributedString(string: line3, attributes: [
                .font: UIFont.monospacedSystemFont(ofSize: 12, weight: .regular),
                .foregroundColor: f.rejectionReason != nil ? colRed : colSub,
            ]).draw(at: CGPoint(x: 16, y: H - 28))
        }
    }
}

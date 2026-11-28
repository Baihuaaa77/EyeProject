import AVFoundation
import CoreGraphics
import CoreImage
import CoreMedia
import CoreVideo
import EyeScreening
import Foundation
import UIKit

/// StrabismusLab iOS 诊断视频双路管线（v0.9.1 四联科研对比拼接版）：
/// 1) 采集期间零阻塞录制高清原始视频 `original.mp4`（1080×1920，精确记录每帧与 `report.frames` 的帧号与候选点映射）；
/// 2) 测试结束完成 v0.9.1 双向光码解调与 3D 球面光学反演后，离线渲染 **1280×720 横屏四联科研拼接视频 `annotated.mp4`**：
///    - 左列：原始不镜像全画面（叠加双眼虹膜与搜索框）
///    - 中列上排：左右眼高清纯净放大（无任何线条遮挡，与主画面左右严格对齐）
///    - 中列下排：同帧映光检测叠加（虹膜外圈 + 0.80R 虚线圈 + 全部单帧候选点 + v0.8 锁定映光准星，上下直接对照）
///    - 右列：模拟手机屏幕编码白点亮灭 / 追踪目标 + 实时 3D 聚散 HDE/VDE、光码相位灯与遮盖状态仪表
///    - 底部通栏：时间、阶段、测距、头姿、Roll 旋正与遮盖复位窗高亮横幅
final class StrabismusLabVideoRecorder {
    struct RecordedFrameStamp {
        let frameIndex: Int32
        let ptsMs: Int64
        let leftCandidates: [(Float, Float)]
        let rightCandidates: [(Float, Float)]
    }

    private let queue = DispatchQueue(label: "com.zkyjss.strabismuslab.video", qos: .utility, autoreleaseFrequency: .workItem)
    private let ciContext = CIContext(options: [.cacheIntermediates: false])
    private let lock = NSLock()

    private var active = false
    private var sessionId = ""
    private var sessionDirectory: URL?
    private var outputURL: URL?
    private var writer: AVAssetWriter?
    private var input: AVAssetWriterInput?
    private var adaptor: AVAssetWriterInputPixelBufferAdaptor?
    private var baseTimeMs: Int64 = -1
    private var lastPtsMs: Int64 = -1
    private var videoWidth = 1080
    private var videoHeight = 1920
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var stamps: [RecordedFrameStamp] = []

    private struct PendingFrame {
        let buffer: CVPixelBuffer
        let frameIndex: Int32
        let timeMs: Int64
        let sessionId: String
        let leftCandidates: [(Float, Float)]
        let rightCandidates: [(Float, Float)]
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
            self.sessionDirectory = directory
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
            let oldDir = self.sessionDirectory
            self.abortWriterLocked()
            if let u = oldURL { try? FileManager.default.removeItem(at: u) }
            // 取消未完成会话时顺手清理空目录，避免历史库残留空文件夹
            if let d = oldDir {
                let contents = (try? FileManager.default.contentsOfDirectory(atPath: d.path)) ?? []
                if contents.isEmpty { try? FileManager.default.removeItem(at: d) }
            }
            self.outputURL = nil
            self.sessionDirectory = nil
            self.stamps.removeAll()
        }
    }

    func appendFrame(
        _ buffer: CVPixelBuffer,
        frameIndex: Int32,
        timeMs: Int64,
        leftCandidates: [(Float, Float)],
        rightCandidates: [(Float, Float)]
    ) {
        lock.lock()
        guard active, frameIndex > 0 else {
            lock.unlock()
            return
        }
        pending = PendingFrame(
            buffer: buffer,
            frameIndex: frameIndex,
            timeMs: timeMs,
            sessionId: sessionId,
            leftCandidates: leftCandidates,
            rightCandidates: rightCandidates
        )
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
            // 1080×1920 高清原画（从 4K 精确 2× 缩放），确保离线裁剪眼区放大像素锐利清晰
            let scale = min(1.0, 1920.0 / Double(max(srcW, srcH)))
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
            stamps.append(RecordedFrameStamp(
                frameIndex: item.frameIndex,
                ptsMs: ptsMs,
                leftCandidates: item.leftCandidates,
                rightCandidates: item.rightCandidates
            ))
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
                    AVVideoAverageBitRateKey: 6_500_000,
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

    /// 结束原始视频封箱，随后离线渲染 1280×720 四联科研拼接视频 `annotated.mp4`。
    /// 通过 `beginBackgroundTask` 保活防止切屏中断，并通过临时文件原子替换彻底杜绝无 `moov` 损坏文件。
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

        var bgTask: UIBackgroundTaskIdentifier = .invalid
        let beginBg = {
            bgTask = UIApplication.shared.beginBackgroundTask(withName: "StrabismusLabRenderAnnotated") {
                if bgTask != .invalid {
                    UIApplication.shared.endBackgroundTask(bgTask)
                    bgTask = .invalid
                }
            }
        }
        if Thread.isMainThread {
            beginBg()
        } else {
            DispatchQueue.main.sync(execute: beginBg)
        }

        let endBg = {
            DispatchQueue.main.async {
                if bgTask != .invalid {
                    UIApplication.shared.endBackgroundTask(bgTask)
                    bgTask = .invalid
                }
            }
        }

        queue.async { [weak self] in
            defer { endBg() }
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
            self.sessionDirectory = nil

            guard ok, FileManager.default.fileExists(atPath: rawURL.path) else {
                DispatchQueue.main.async { completion(nil, nil) }
                return
            }

            let annotatedURL = directory.appendingPathComponent("annotated.mp4")
            let tmpURL = directory.appendingPathComponent("annotated.tmp.mp4")
            try? FileManager.default.removeItem(at: tmpURL)
            let rendered = Self.renderQuadPanelVideo(
                inputURL: rawURL,
                outputURL: tmpURL,
                report: report,
                stamps: capturedStamps,
                sourceWidth: max(1, srcW),
                sourceHeight: max(1, srcH),
                onProgress: onProgress
            )
            var finalAnnotated: URL? = nil
            if rendered, FileManager.default.fileExists(atPath: tmpURL.path) {
                try? FileManager.default.removeItem(at: annotatedURL)
                if (try? FileManager.default.moveItem(at: tmpURL, to: annotatedURL)) != nil {
                    finalAnnotated = annotatedURL
                }
            } else {
                try? FileManager.default.removeItem(at: tmpURL)
            }
            DispatchQueue.main.async {
                completion(rawURL, finalAnnotated)
            }
        }
    }

    // MARK: - 1280×720 四联科研对比拼接渲染器

    private static func renderQuadPanelVideo(
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

        let outW = 1280
        let outH = 720

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
                AVVideoAverageBitRateKey: 6_000_000,
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
        let coverEvents = report.coverRefixations.map { ($0.uncoveredAtSec, $0.eventType, $0.eye) }
        let searchRadius = CGFloat(report.config.searchRadiusFraction)
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let bgraBitmapInfo = CGBitmapInfo(rawValue: CGBitmapInfo.byteOrder32Little.rawValue | CGImageAlphaInfo.premultipliedFirst.rawValue)

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

                let matchedStamp: RecordedFrameStamp? = {
                    if frameIdx < stamps.count && abs(stamps[frameIdx].ptsMs - ptsMs) <= 80 {
                        return stamps[frameIdx]
                    }
                    return stamps.min(by: { abs($0.ptsMs - ptsMs) < abs($1.ptsMs - ptsMs) })
                }()
                let matchedLog = matchedStamp.flatMap { frameByIndex[$0.frameIndex] }

                while !writeInput.isReadyForMoreMediaData && writer.status == .writing {
                    Thread.sleep(forTimeInterval: 0.002)
                }
                guard writer.status == .writing, let pool = adaptor.pixelBufferPool else { return }

                var dstOpt: CVPixelBuffer?
                guard CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, pool, &dstOpt) == kCVReturnSuccess,
                      let dstBuf = dstOpt else { return }

                // 零拷贝直接从已解码的 BGRA CVPixelBuffer 构造 CGImage（省去每帧 CIContext GPU 往返，提速 3–5×）
                CVPixelBufferLockBaseAddress(srcBuf, .readOnly)
                defer { CVPixelBufferUnlockBaseAddress(srcBuf, .readOnly) }
                if let srcBase = CVPixelBufferGetBaseAddress(srcBuf) {
                    let srcStride = CVPixelBufferGetBytesPerRow(srcBuf)
                    let srcBufW = CVPixelBufferGetWidth(srcBuf)
                    let srcBufH = CVPixelBufferGetHeight(srcBuf)
                    if let provider = CGDataProvider(
                        dataInfo: nil,
                        data: srcBase,
                        size: srcStride * srcBufH,
                        releaseData: { _, _, _ in }
                    ),
                       let cgImg = CGImage(
                           width: srcBufW,
                           height: srcBufH,
                           bitsPerComponent: 8,
                           bitsPerPixel: 32,
                           bytesPerRow: srcStride,
                           space: colorSpace,
                           bitmapInfo: bgraBitmapInfo,
                           provider: provider,
                           decode: nil,
                           shouldInterpolate: true,
                           intent: .defaultIntent
                       ) {
                        drawQuadPanelFrame(
                            into: dstBuf,
                            rawImage: cgImg,
                            log: matchedLog,
                            stamp: matchedStamp,
                            outW: outW,
                            outH: outH,
                            rawW: srcBufW,
                            rawH: srcBufH,
                            sourceW: sourceWidth,
                            sourceH: sourceHeight,
                            searchRadius: searchRadius,
                            coverEvents: coverEvents,
                            colorSpace: colorSpace
                        )
                        adaptor.append(dstBuf, withPresentationTime: pts)
                    }
                }

                frameIdx += 1
                if frameIdx % 12 == 0 {
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

    private struct EyeSlot {
        let title: String
        let irisX: Float?
        let irisY: Float?
        let irisD: Float?
        let glintX: Float?
        let glintY: Float?
        let relX: Float?
        let relY: Float?
        let confidence: Float?
        let source: LabGlintSource
        let candidates: [(Float, Float)]
        let tint: UIColor
    }

    private static func drawQuadPanelFrame(
        into buffer: CVPixelBuffer,
        rawImage: CGImage,
        log: LabFrameLog?,
        stamp: RecordedFrameStamp?,
        outW: Int,
        outH: Int,
        rawW: Int,
        rawH: Int,
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

        // 切换到左上角原点坐标系（所有文字与几何统一左上角原点）
        ctx.translateBy(x: 0, y: H)
        ctx.scaleBy(x: 1, y: -1)
        UIGraphicsPushContext(ctx)
        defer { UIGraphicsPopContext() }

        let colCanvas = UIColor(red: 9/255, green: 12/255, blue: 16/255, alpha: 1)
        let colSurface = UIColor(red: 18/255, green: 23/255, blue: 31/255, alpha: 1)
        let colHairline = UIColor(red: 36/255, green: 46/255, blue: 61/255, alpha: 1)
        let colCyan = UIColor(red: 56/255, green: 189/255, blue: 248/255, alpha: 1)
        let colAmber = UIColor(red: 245/255, green: 158/255, blue: 11/255, alpha: 1)
        let colGreen = UIColor(red: 34/255, green: 197/255, blue: 94/255, alpha: 1)
        let colWarn = UIColor(red: 251/255, green: 191/255, blue: 36/255, alpha: 1)
        let colRed = UIColor(red: 244/255, green: 63/255, blue: 94/255, alpha: 1)
        let colInk = UIColor(red: 241/255, green: 245/255, blue: 249/255, alpha: 1)
        let colSub = UIColor(red: 148/255, green: 163/255, blue: 184/255, alpha: 1)

        // 背景底板
        ctx.setFillColor(colCanvas.cgColor)
        ctx.fill(CGRect(x: 0, y: 0, width: W, height: H))

        func drawText(_ s: String, at pt: CGPoint, color: UIColor = colInk, size: CGFloat = 14, bold: Bool = false, mono: Bool = false) {
            let font: UIFont = mono
                ? UIFont.monospacedSystemFont(ofSize: size, weight: bold ? .bold : .medium)
                : UIFont.systemFont(ofSize: size, weight: bold ? .semibold : .regular)
            NSAttributedString(string: s, attributes: [.font: font, .foregroundColor: color]).draw(at: pt)
        }

        func drawUpright(_ image: CGImage, in rect: CGRect, crisp: Bool = false) {
            ctx.saveGState()
            ctx.interpolationQuality = crisp ? .high : .medium
            ctx.translateBy(x: rect.minX, y: rect.maxY)
            ctx.scaleBy(x: 1, y: -1)
            ctx.draw(image, in: CGRect(x: 0, y: 0, width: rect.width, height: rect.height))
            ctx.restoreGState()
        }

        func aspectFit(_ imgSize: CGSize, into box: CGRect) -> CGRect {
            let k = min(box.width / max(1, imgSize.width), box.height / max(1, imgSize.height))
            let w = imgSize.width * k
            let h = imgSize.height * k
            return CGRect(x: box.midX - w * 0.5, y: box.midY - h * 0.5, width: w, height: h)
        }

        let toRawX = CGFloat(rawW) / CGFloat(sourceW)
        let toRawY = CGFloat(rawH) / CGFloat(sourceH)

        // ==================== 1) 左侧面板：原相机全画面（不镜像） ====================
        drawText("原画全览 · 不镜像", at: CGPoint(x: 16, y: 12), color: colInk, size: 15, bold: true)
        let leftBox = CGRect(x: 16, y: 38, width: 302, height: 564)
        ctx.setFillColor(UIColor.black.cgColor)
        ctx.fill(leftBox)
        let fullRect = aspectFit(CGSize(width: rawW, height: rawH), into: leftBox)
        drawUpright(rawImage, in: fullRect)
        ctx.setStrokeColor(colHairline.cgColor)
        ctx.setLineWidth(1.2)
        ctx.stroke(leftBox)

        // ==================== 2) 中间四格：上排纯净放大原图 + 下排同帧标注对照 ====================
        drawText("原始高清眼区放大（纯净无遮挡）", at: CGPoint(x: 334, y: 12), color: colInk, size: 15, bold: true)
        drawText("同帧映光检测对照（绿十字=实测 · 白圈=候选 · 虚线=\(Int(searchRadius * 100))%R）", at: CGPoint(x: 334, y: 318), color: colSub, size: 13, bold: true)

        if let f = log {
            let leftModelSlot = EyeSlot(
                title: "受试者左眼 · L",
                irisX: f.leftIrisX?.floatValue, irisY: f.leftIrisY?.floatValue, irisD: f.leftIrisDiameterPx?.floatValue,
                glintX: f.leftGlintX?.floatValue, glintY: f.leftGlintY?.floatValue,
                relX: f.leftRelX?.floatValue, relY: f.leftRelY?.floatValue,
                confidence: f.leftGlintConfidence?.floatValue, source: f.leftGlintSource,
                candidates: stamp?.leftCandidates ?? [], tint: colCyan
            )
            let rightModelSlot = EyeSlot(
                title: "受试者右眼 · R",
                irisX: f.rightIrisX?.floatValue, irisY: f.rightIrisY?.floatValue, irisD: f.rightIrisDiameterPx?.floatValue,
                glintX: f.rightGlintX?.floatValue, glintY: f.rightGlintY?.floatValue,
                relX: f.rightRelX?.floatValue, relY: f.rightRelY?.floatValue,
                confidence: f.rightGlintConfidence?.floatValue, source: f.rightGlintSource,
                candidates: stamp?.rightCandidates ?? [], tint: colAmber
            )
            // 按主画面实际左右位置排列：画面左侧眼放左格、画面右侧眼放右格，视觉 1:1 顺眼对齐！
            let rightIsOnLeftOfImage = (f.rightIrisX?.floatValue ?? 0) < (f.leftIrisX?.floatValue ?? Float.greatestFiniteMagnitude)
            let orderedSlots = rightIsOnLeftOfImage ? [rightModelSlot, leftModelSlot] : [leftModelSlot, rightModelSlot]

            // 在全览图上标出双眼虹膜框
            for slot in orderedSlots {
                if let ix = slot.irisX, let iy = slot.irisY, let id = slot.irisD, id > 0 {
                    let fx = fullRect.minX + CGFloat(ix) / CGFloat(sourceW) * fullRect.width
                    let fy = fullRect.minY + CGFloat(iy) / CGFloat(sourceH) * fullRect.height
                    let fr = CGFloat(id) * 0.5 / CGFloat(sourceW) * fullRect.width
                    ctx.setStrokeColor(slot.tint.withAlphaComponent(0.9).cgColor)
                    ctx.setLineWidth(1.5)
                    ctx.strokeEllipse(in: CGRect(x: fx - fr, y: fy - fr, width: fr * 2, height: fr * 2))
                }
            }

            for (colIdx, slot) in orderedSlots.enumerated() {
                let colX = CGFloat(334 + colIdx * 300)
                let colW: CGFloat = 286
                let rawBox = CGRect(x: colX, y: 60, width: colW, height: 244)
                let annBox = CGRect(x: colX, y: 342, width: colW, height: 244)

                drawText(slot.title, at: CGPoint(x: colX, y: 38), color: slot.tint, size: 13, bold: true)

                ctx.setFillColor(colSurface.cgColor)
                ctx.fill(rawBox)
                ctx.fill(annBox)

                if let ix = slot.irisX, let iy = slot.irisY, let id = slot.irisD, id > 4 {
                    let rx = CGFloat(ix) * toRawX
                    let ry = CGFloat(iy) * toRawY
                    let rRaw = CGFloat(id) * 0.5 * toRawX
                    let halfW = max(36.0, rRaw * 2.35)
                    let halfH = halfW * (rawBox.height / rawBox.width)
                    let cropRect = CGRect(
                        x: max(0, min(CGFloat(rawW) - halfW * 2, rx - halfW)),
                        y: max(0, min(CGFloat(rawH) - halfH * 2, ry - halfH)),
                        width: min(CGFloat(rawW), halfW * 2),
                        height: min(CGFloat(rawH), halfH * 2)
                    )

                    if let sub = rawImage.cropping(to: cropRect) {
                        // 上排：纯净原图放大
                        drawUpright(sub, in: rawBox, crisp: true)
                        // 下排：同帧放大 + 科研叠加层
                        drawUpright(sub, in: annBox, crisp: true)

                        let zsx = annBox.width / max(1, cropRect.width)
                        let zsy = annBox.height / max(1, cropRect.height)
                        func mapPt(_ srcX: Float, _ srcY: Float) -> CGPoint {
                            CGPoint(
                                x: annBox.minX + (CGFloat(srcX) * toRawX - cropRect.minX) * zsx,
                                y: annBox.minY + (CGFloat(srcY) * toRawY - cropRect.minY) * zsy
                            )
                        }

                        ctx.saveGState()
                        ctx.clip(to: annBox)

                        let cPt = mapPt(ix, iy)
                        let cR = rRaw * zsx

                        // 完整虹膜缘
                        ctx.setStrokeColor(UIColor(white: 0.92, alpha: 0.85).cgColor)
                        ctx.setLineWidth(1.4)
                        ctx.strokeEllipse(in: CGRect(x: cPt.x - cR, y: cPt.y - cR, width: cR * 2, height: cR * 2))

                        // 0.80R 搜索虚线圈
                        let sr = cR * searchRadius
                        ctx.setStrokeColor(slot.tint.withAlphaComponent(0.9).cgColor)
                        ctx.setLineWidth(1.8)
                        ctx.setLineDash(phase: 0, lengths: [7, 5])
                        ctx.strokeEllipse(in: CGRect(x: cPt.x - sr, y: cPt.y - sr, width: sr * 2, height: sr * 2))
                        ctx.setLineDash(phase: 0, lengths: [])

                        // 虹膜中心细十字
                        ctx.setStrokeColor(slot.tint.withAlphaComponent(0.8).cgColor)
                        ctx.setLineWidth(1.2)
                        ctx.move(to: CGPoint(x: cPt.x - 8, y: cPt.y)); ctx.addLine(to: CGPoint(x: cPt.x + 8, y: cPt.y))
                        ctx.move(to: CGPoint(x: cPt.x, y: cPt.y - 8)); ctx.addLine(to: CGPoint(x: cPt.x, y: cPt.y + 8))
                        ctx.strokePath()

                        // 单帧全部候选点（白色细小圈，便于一眼审计候选池）
                        ctx.setStrokeColor(UIColor.white.withAlphaComponent(0.75).cgColor)
                        ctx.setLineWidth(1.2)
                        for (cx, cy) in slot.candidates {
                            let cp = mapPt(cx, cy)
                            ctx.strokeEllipse(in: CGRect(x: cp.x - 4, y: cp.y - 4, width: 8, height: 8))
                        }

                        // v0.8 最终锁定角膜映光准星
                        if let gx = slot.glintX, let gy = slot.glintY {
                            let gp = mapPt(gx, gy)
                            if slot.source == .observed || slot.source == .weak {
                                let gCol = slot.source == .observed ? colGreen : colCyan
                                ctx.setStrokeColor(gCol.cgColor)
                                ctx.setLineWidth(2.4)
                                ctx.move(to: CGPoint(x: gp.x - 13, y: gp.y)); ctx.addLine(to: CGPoint(x: gp.x + 13, y: gp.y))
                                ctx.move(to: CGPoint(x: gp.x, y: gp.y - 13)); ctx.addLine(to: CGPoint(x: gp.x, y: gp.y + 13))
                                ctx.strokePath()
                                ctx.strokeEllipse(in: CGRect(x: gp.x - 7, y: gp.y - 7, width: 14, height: 14))
                            } else if slot.source == .predicted {
                                ctx.setStrokeColor(colWarn.withAlphaComponent(0.85).cgColor)
                                ctx.setLineWidth(1.8)
                                ctx.setLineDash(phase: 0, lengths: [4, 4])
                                ctx.strokeEllipse(in: CGRect(x: gp.x - 8, y: gp.y - 8, width: 16, height: 16))
                                ctx.setLineDash(phase: 0, lengths: [])
                            }
                        }
                        ctx.restoreGState()
                    }
                } else {
                    drawText("眼区不可定位/被遮盖", at: CGPoint(x: colX + 60, y: 170), color: colSub, size: 13)
                    drawText("无可用光学像素", at: CGPoint(x: colX + 80, y: 452), color: colSub, size: 13)
                }

                ctx.setStrokeColor(slot.tint.withAlphaComponent(0.75).cgColor)
                ctx.setLineWidth(1.5)
                ctx.stroke(rawBox)
                ctx.stroke(annBox)

                // 下格说明行
                let confTxt = slot.confidence.map { "\(Int($0 * 100))%" } ?? "—"
                let relTxt = (slot.relX != nil && slot.relY != nil)
                    ? String(format: "(%+.2f,%+.2f)R", slot.relX!, slot.relY!)
                    : "—"
                drawText("\(slot.source.label) · 置信 \(confTxt) · 候选 \(slot.candidates.count) · \(relTxt)",
                         at: CGPoint(x: colX, y: 590), color: slot.tint, size: 11, mono: true)
            }

            // ==================== 3) 右侧面板：屏幕刺激模拟器 + 科研仪表 ====================
            drawText("屏幕光码同步 & 实时读数", at: CGPoint(x: 944, y: 12), color: colInk, size: 15, bold: true)
            let phoneOuter = CGRect(x: 1014, y: 44, width: 176, height: 306)
            ctx.setFillColor(UIColor.black.cgColor)
            let phonePath = UIBezierPath(roundedRect: phoneOuter, cornerRadius: 20)
            phonePath.fill()
            colHairline.setStroke()
            phonePath.lineWidth = 2
            phonePath.stroke()

            // 灵动岛小条
            let island = CGRect(x: phoneOuter.midX - 24, y: phoneOuter.minY + 8, width: 48, height: 10)
            UIColor(white: 0.14, alpha: 1).setFill()
            UIBezierPath(roundedRect: island, cornerRadius: 5).fill()

            if f.stage.usesOpticalStimulus {
                let lum = CGFloat(min(1, max(0, f.stimulusIntensity)))
                let dotR: CGFloat = 24
                let dotRect = CGRect(x: phoneOuter.midX - dotR, y: phoneOuter.minY + 26, width: dotR * 2, height: dotR * 2)
                UIColor(white: lum, alpha: 1).setFill()
                UIBezierPath(ovalIn: dotRect).fill()
                colCyan.withAlphaComponent(0.55).setStroke()
                let ring = UIBezierPath(ovalIn: dotRect.insetBy(dx: -3, dy: -3))
                ring.lineWidth = 1.2
                ring.stroke()
                drawText("亮度 \(Int(lum * 100))%", at: CGPoint(x: phoneOuter.minX + 46, y: phoneOuter.maxY - 26), color: colSub, size: 12, mono: true)
            } else if f.stage == .pursuit, let tx = f.targetNormX?.floatValue, let ty = f.targetNormY?.floatValue {
                let px = phoneOuter.minX + CGFloat(min(0.92, max(0.08, tx))) * phoneOuter.width
                let py = phoneOuter.minY + CGFloat(min(0.90, max(0.10, ty))) * phoneOuter.height
                colAmber.setFill()
                UIBezierPath(ovalIn: CGRect(x: px - 7, y: py - 7, width: 14, height: 14)).fill()
            }

            // 仪表读数卡
            let cardRect = CGRect(x: 944, y: 364, width: 320, height: 242)
            ctx.setFillColor(colSurface.cgColor)
            ctx.fill(cardRect)
            ctx.setStrokeColor(colHairline.cgColor)
            ctx.setLineWidth(1.2)
            ctx.stroke(cardRect)

            let phaseColor: UIColor = {
                switch f.leftEmissionPhase {
                case .on: return colGreen
                case .off, .dim: return colSub
                case .transition: return colWarn
                default: return colCyan
                }
            }()
            ctx.setFillColor(phaseColor.cgColor)
            ctx.fillEllipse(in: CGRect(x: 960, y: 380, width: 11, height: 11))
            drawText("光码相位：\(f.leftEmissionPhase.label)", at: CGPoint(x: 978, y: 376), color: phaseColor, size: 13, bold: true, mono: true)

            let hde3dStr = f.hde3dPrism.map { String(format: "%+.1fΔ", $0.doubleValue) } ?? "—"
            let v3dStr = f.vde3dPrism.map { String(format: "%+.1fΔ", $0.doubleValue) } ?? "—"
            let linStr = f.hdePrism.map { String(format: "%+.1fΔ", $0.doubleValue) } ?? "—"
            let rollStr = f.rollCorrectionDeg.map { String(format: "%+.1f°", $0.doubleValue) } ?? "—"
            drawText("3D 水平 HDE（抵消 κ 角）", at: CGPoint(x: 960, y: 406), color: colSub, size: 12)
            drawText(hde3dStr, at: CGPoint(x: 960, y: 424), color: colCyan, size: 28, bold: true, mono: true)
            drawText("3D VDE \(v3dStr)  ·  线性 \(linStr)", at: CGPoint(x: 960, y: 462), color: colInk, size: 13, mono: true)
            drawText("Roll 旋正：\(rollStr)", at: CGPoint(x: 960, y: 484), color: colSub, size: 12, mono: true)

            let lVis = f.leftVisibilityContrast.map { String(format: "%.0f", $0.floatValue) } ?? "-"
            let rVis = f.rightVisibilityContrast.map { String(format: "%.0f", $0.floatValue) } ?? "-"
            let cov = f.coveredEye ?? "NONE"
            drawText("遮盖状态：\(cov)", at: CGPoint(x: 960, y: 514), color: cov == "NONE" ? colInk : colWarn, size: 13, bold: true, mono: true)
            drawText("眼区对比度 L:\(lVis)  R:\(rVis)", at: CGPoint(x: 960, y: 536), color: colSub, size: 12, mono: true)

            if let hit = coverEvents.first(where: { f.elapsedSec >= $0.0 && f.elapsedSec <= $0.0 + 0.65 }) {
                let tag = hit.1 == "COVER_CONTRALATERAL" ? "显斜窗·对侧 \(hit.2)" : "隐斜/IXT窗·\(hit.2)"
                let banner = CGRect(x: 956, y: 564, width: 296, height: 30)
                ctx.setFillColor(colWarn.cgColor)
                ctx.fill(banner)
                drawText(" \(tag) \(String(format: "+%.0f", (f.elapsedSec - hit.0) * 1000))ms",
                         at: CGPoint(x: 962, y: 571), color: UIColor.black, size: 13, bold: true, mono: true)
            } else {
                drawText("双指捏合可 1–6× 放大检视眼区像素", at: CGPoint(x: 960, y: 574), color: colSub, size: 11)
            }

            // ==================== 4) 底部通栏：完整状态遥测 ====================
            let botBar = CGRect(x: 16, y: 620, width: 1248, height: 86)
            ctx.setFillColor(colSurface.cgColor)
            ctx.fill(botBar)
            ctx.setStrokeColor(colHairline.cgColor)
            ctx.setLineWidth(1.2)
            ctx.stroke(botBar)

            let yawS = f.yawDeg.map { String(format: "%+.1f", $0.floatValue) } ?? "-"
            let pitS = f.pitchDeg.map { String(format: "%+.1f", $0.floatValue) } ?? "-"
            let rolS = f.rollDeg.map { String(format: "%+.1f", $0.floatValue) } ?? "-"
            let line1 = "StrabismusLab v0.9.5  ·  \(f.stage.title)  ·  #\(f.index)  ·  t=\(String(format: "%.2fs", f.elapsedSec))  ·  距离 \(String(format: "%.1f", f.distanceCm)) cm  ·  头姿 Y/P/R \(yawS)/\(pitS)/\(rolS)°  ·  \(String(format: "%.0f", f.fps)) fps"
            drawText(line1, at: CGPoint(x: 30, y: 634), color: colInk, size: 14, bold: true, mono: true)

            let line2 = f.rejectionReason.map { "本帧拒判：\($0)" }
                ?? "质量门控通过 · 上排原始高清眼区 vs 下排同帧叠加对照 · 绿十字=亮灯实测 / 青十字=弱观测 / 琥珀虚圈=灭灯预测 / 白小圈=单帧候选池"
            drawText(line2, at: CGPoint(x: 30, y: 668), color: f.rejectionReason != nil ? colRed : colSub, size: 12, mono: false)
        }
    }
}

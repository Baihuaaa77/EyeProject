import AVFoundation
import Vision
import UIKit
import CoreImage

/// 所有采集、像素读取、Vision 与视频写入都在同一个串行队列；缓冲不跨帧持有。
final class LabCamera: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    var onPreview: ((UIImage?, String, Bool) -> Void)?
    var onError: ((String) -> Void)?
    private let queue = DispatchQueue(label: "corneal.lab.camera", qos: .userInitiated, autoreleaseFrequency: .workItem)
    private let session = AVCaptureSession()
    private let context = CIContext()
    private var device: AVCaptureDevice?
    private var configured = false
    private var frame = 0
    private var lastPreview = 0.0
    private var writer: AVAssetWriter?
    private var writerInput: AVAssetWriterInput?
    private var recording = false
    private var startTime = 0.0
    private var videoStartPTS: Double?
    private var samples: [PatchSample] = []
    private var records: [FrameRecord] = []
    private var patchData = Data()
    private var eyeImages: [String: UIImage] = [:]
    private var lastPoints: [String: CGPoint] = [:]
    private var dropped = 0
    private var writeDropped = 0
    private var cameraInfo: [String: Any] = [:]
    private var hasLockedExposure = false
    private var reusedExposure = false
    private var observers: [NSObjectProtocol] = []

    override init() {
        super.init()
        observers.append(NotificationCenter.default.addObserver(forName: AVCaptureSession.wasInterruptedNotification,
            object: session, queue: nil) { [weak self] _ in self?.onError?("相机被系统中断，本轮已停止；请重新准备相机。") })
        observers.append(NotificationCenter.default.addObserver(forName: AVCaptureSession.runtimeErrorNotification,
            object: session, queue: nil) { [weak self] _ in self?.onError?("相机运行异常，请重新准备相机。") })
    }
    deinit { observers.forEach { NotificationCenter.default.removeObserver($0) } }

    func prepare(reuseExposure: Bool = true) {
        AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
            guard let self = self else { return }
            guard granted else { self.onError?("请在 iPhone 设置中允许相机权限。"); return }
            self.queue.async {
                do {
                    if !self.configured { try self.configure() }
                    if let device = self.device {
                        try device.lockForConfiguration()
                        self.reusedExposure = reuseExposure && self.hasLockedExposure
                        if !self.reusedExposure {
                            if device.isExposureModeSupported(.continuousAutoExposure) { device.exposureMode = .continuousAutoExposure }
                            if device.isWhiteBalanceModeSupported(.continuousAutoWhiteBalance) { device.whiteBalanceMode = .continuousAutoWhiteBalance }
                        }
                        device.unlockForConfiguration()
                    }
                    if !self.session.isRunning { self.session.startRunning() }
                } catch { self.onError?("相机准备失败：\(error.localizedDescription)") }
            }
        }
    }

    private func configure() throws {
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front) else {
            throw NSError(domain: "Lab", code: 1, userInfo: [NSLocalizedDescriptionKey: "未找到前置彩色相机"])
        }
        self.device = device
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        session.sessionPreset = session.canSetSessionPreset(.hd1920x1080) ? .hd1920x1080 : .high
        let input = try AVCaptureDeviceInput(device: device)
        guard session.canAddInput(input) else { throw NSError(domain: "Lab", code: 2) }
        session.addInput(input)
        let output = AVCaptureVideoDataOutput()
        output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        output.alwaysDiscardsLateVideoFrames = true
        output.setSampleBufferDelegate(self, queue: queue)
        guard session.canAddOutput(output) else { throw NSError(domain: "Lab", code: 3) }
        session.addOutput(output)
        if let connection = output.connection(with: .video) {
            if connection.isVideoOrientationSupported { connection.videoOrientation = .portrait }
            if connection.isVideoMirroringSupported { connection.automaticallyAdjustsVideoMirroring = false; connection.isVideoMirrored = false }
            if connection.isVideoStabilizationSupported { connection.preferredVideoStabilizationMode = .off }
        }
        try device.lockForConfiguration()
        if device.activeFormat.videoSupportedFrameRateRanges.contains(where: { $0.minFrameRate <= 30 && $0.maxFrameRate >= 30 }) {
            device.activeVideoMinFrameDuration = CMTime(value: 1, timescale: 30)
            device.activeVideoMaxFrameDuration = CMTime(value: 1, timescale: 30)
        }
        if device.isLowLightBoostSupported { device.automaticallyEnablesLowLightBoostWhenAvailable = false }
        device.unlockForConfiguration()
        configured = true
    }

    /// 黑背景、恒定白点预适应后锁定曝光和白平衡。锁定失败即拒绝编码试验。
    func lockExposure(completion: @escaping (Result<[String: Any], Error>) -> Void) {
        queue.async {
            do {
                guard let device = self.device, self.session.isRunning,
                      device.isExposureModeSupported(.locked), device.isWhiteBalanceModeSupported(.locked) else {
                    throw NSError(domain: "Lab", code: 4, userInfo: [NSLocalizedDescriptionKey: "本机无法锁定曝光或白平衡，不能做有效编码对照"])
                }
                try device.lockForConfiguration()
                device.exposureMode = .locked
                device.whiteBalanceMode = .locked
                if device.isFocusModeSupported(.locked) { device.focusMode = .locked }
                device.unlockForConfiguration()
                self.hasLockedExposure = true
                self.cameraInfo = ["camera": device.localizedName, "uniqueFormat": String(describing: device.activeFormat),
                    "exposureSeconds": CMTimeGetSeconds(device.exposureDuration), "iso": device.iso,
                    "exposureLocked": device.exposureMode == .locked, "whiteBalanceLocked": device.whiteBalanceMode == .locked,
                    "focusMode": device.focusMode.rawValue, "requestedFPS": 30, "analysisEveryNFrames": 2,
                    "videoMirrored": false, "orientation": "portrait buffers / Vision up", "reusedExposure": self.reusedExposure]
                completion(.success(self.cameraInfo))
            } catch { completion(.failure(error)) }
        }
    }

    func beginRecording(directory: URL, start: Double, completion: @escaping (Result<Void, Error>) -> Void) {
        queue.async {
            do {
                self.writer = try AVAssetWriter(outputURL: directory.appendingPathComponent("camera.mov"), fileType: .mov)
                self.writerInput = nil; self.videoStartPTS = nil
                self.samples = []; self.records = []; self.patchData = Data(); self.eyeImages = [:]; self.lastPoints = [:]
                self.dropped = 0; self.writeDropped = 0; self.frame = 0; self.startTime = start; self.recording = true
                completion(.success(()))
            } catch { completion(.failure(error)) }
        }
    }

    func finish(completion: @escaping ([PatchSample], [FrameRecord], Data, [String: UIImage], [String: Any]) -> Void) {
        queue.async {
            self.recording = false
            let samples = self.samples, records = self.records, data = self.patchData, images = self.eyeImages
            var info = self.cameraInfo
            info["captureDroppedFrames"] = self.dropped; info["videoWriteDroppedFrames"] = self.writeDropped
            info["videoStartPTS"] = self.videoStartPTS.map { $0 as Any } ?? NSNull()
            info["receivedFrames"] = self.frame
            let writer = self.writer
            self.writerInput?.markAsFinished()
            self.writer = nil; self.writerInput = nil
            self.samples = []; self.records = []; self.patchData = Data()
            if writer?.status == .writing {
                writer?.finishWriting {
                    var finalInfo = info
                    finalInfo["videoStatus"] = writer?.status.rawValue ?? -1
                    finalInfo["videoError"] = writer?.error?.localizedDescription ?? ""
                    completion(samples, records, data, images, finalInfo)
                }
            } else {
                info["videoStatus"] = writer?.status.rawValue ?? -1
                info["videoError"] = writer?.error?.localizedDescription ?? "未写入视频"
                completion(samples, records, data, images, info)
            }
        }
    }

    func stop() { queue.async {
        self.recording = false
        self.writer?.cancelWriting(); self.writer = nil; self.writerInput = nil
        if self.session.isRunning { self.session.stopRunning() }
    } }

    func captureOutput(_ output: AVCaptureOutput, didDrop sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        if recording { dropped += 1 }
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput buffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard let pixels = CMSampleBufferGetImageBuffer(buffer), let device = device else { return }
        let pts = CMSampleBufferGetPresentationTimeStamp(buffer)
        let clock = session.masterClock ?? CMClockGetHostTimeClock()
        let host = CMTimeGetSeconds(CMSyncConvertTime(pts, from: clock, to: CMClockGetHostTimeClock()))
        guard host.isFinite else { return }
        let width = CVPixelBufferGetWidth(pixels), height = CVPixelBufferGetHeight(pixels)
        let saving = recording && host >= startTime
        if recording && !saving { return }
        frame += 1
        if saving { appendVideo(buffer, width: width, height: height, pts: pts) }
        func record(_ status: String, sample: PatchSample? = nil) {
            guard saving else { return }
            let offset = sample == nil ? nil : patchData.count
            if let sample = sample {
                var little = sample.pixels.map { $0.bitPattern.littleEndian }
                little.withUnsafeBytes { patchData.append(contentsOf: $0) }
                samples.append(sample)
            }
            records.append(FrameRecord(index: frame, pts: CMTimeGetSeconds(pts), hostTime: host,
                exposureSeconds: CMTimeGetSeconds(device.exposureDuration), iso: device.iso, width: width, height: height,
                status: status, eye: sample?.eye, roi: sample?.roi, pupil: sample?.pupil,
                rawCandidate: sample?.rawCandidate, saturation: sample?.saturation, patchOffset: offset))
        }
        guard frame % 2 == 0 else { record("not_analyzed"); return }
        let request = VNDetectFaceLandmarksRequest()
        do { try VNImageRequestHandler(cvPixelBuffer: pixels, orientation: .up).perform([request]) }
        catch { record("vision_error"); return }
        guard let faces = request.results, faces.count == 1, let face = faces.first, face.confidence >= 0.7,
              abs(face.yaw?.doubleValue ?? 0) < 0.3, abs(face.roll?.doubleValue ?? 0) < 0.25,
              let landmarks = face.landmarks else {
            record("no_single_stable_face")
            preview(pixels, time: host, text: "请正对手机，让相机看清双眼", ready: false); return
        }
        func imagePoint(_ p: CGPoint) -> CGPoint {
            CGPoint(x: (face.boundingBox.minX+p.x*face.boundingBox.width)*Double(width),
                y: (1-face.boundingBox.minY-p.y*face.boundingBox.height)*Double(height))
        }
        let regions: [(VNFaceLandmarkRegion2D?, VNFaceLandmarkRegion2D?)] = [(landmarks.leftEye, landmarks.leftPupil), (landmarks.rightEye, landmarks.rightPupil)]
        var eyes: [(CGPoint, Double)] = []
        for (region, pupilRegion) in regions {
            guard let region = region, region.pointCount >= 4, let pupilRegion = pupilRegion, pupilRegion.pointCount > 0 else { continue }
            let points = region.normalizedPoints.map(imagePoint)
            let eyeWidth = (points.map(\.x).max() ?? 0) - (points.map(\.x).min() ?? 0)
            let eyeHeight = (points.map(\.y).max() ?? 0) - (points.map(\.y).min() ?? 0)
            guard eyeWidth >= 20, eyeHeight/eyeWidth >= 0.08 else { continue }
            eyes.append((imagePoint(pupilRegion.normalizedPoints[0]), eyeWidth))
        }
        eyes.sort { $0.0.x < $1.0.x }
        guard eyes.count == 2 else { record("blink_or_eye_roi_missing"); preview(pixels, time: host, text: "眨眼或瞳孔区域不可用", ready: false); return }
        CVPixelBufferLockBaseAddress(pixels, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixels, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(pixels)?.assumingMemoryBound(to: UInt8.self) else { record("pixel_buffer_missing"); return }
        let stride = CVPixelBufferGetBytesPerRow(pixels)
        for (index, eye) in eyes.enumerated() {
            let name = index == 0 ? "image_left" : "image_right"
            let p = eye.0, w = eye.1*1.4, h = w/2
            let rect = LabRect(x: Double(p.x)-w/2, y: Double(p.y)-h/2, width: w, height: h)
            guard rect.x >= 0, rect.y >= 0, rect.x+w < Double(width), rect.y+h < Double(height) else { record("eye_roi_cropped"); continue }
            if let previous = lastPoints[name], hypot(p.x-previous.x,p.y-previous.y) > eye.1*0.25 {
                lastPoints[name] = p; record("large_motion"); continue
            }
            lastPoints[name] = p
            var patch = [Float](repeating: 0, count: CornealAnalyzer.count)
            for y in 0..<CornealAnalyzer.height { for x in 0..<CornealAnalyzer.width {
                let sx = Int(rect.x+(Double(x)+0.5)/Double(CornealAnalyzer.width)*w)
                let sy = Int(rect.y+(Double(y)+0.5)/Double(CornealAnalyzer.height)*h)
                let i = sy*stride+sx*4
                patch[y*CornealAnalyzer.width+x] = 0.2126*Float(base[i+2])+0.7152*Float(base[i+1])+0.0722*Float(base[i])
            } }
            let hp = CornealAnalyzer.highPass(patch)
            var candidate: Int?, score: Float = 0
            for i in hp.indices {
                let x = Double(i%64)/64, y = Double(i/64)/32
                if abs(x-0.5)<0.32 && abs(y-0.5)<0.36 && patch[i]>140 && hp[i]>max(12,score) { score = hp[i]; candidate = i }
            }
            let raw = candidate.map { LabPoint(x: (Double($0%64)+0.5)/64, y: (Double($0/64)+0.5)/32) }
            let saturation = Double(patch.filter { $0 >= 250 }.count)/Double(patch.count)
            let sample = PatchSample(eye: name, hostTime: host, pixels: patch, roi: rect,
                pupil: LabPoint(x: Double(p.x),y: Double(p.y)), rawCandidate: raw, saturation: saturation)
            if saving {
                record(saturation > 0.15 ? "saturated_excluded" : "valid", sample: sample)
                if saturation <= 0.15 { eyeImages[name] = Self.patchImage(patch) }
            }
        }
        preview(pixels, time: host, text: "双眼可见 · \(width)×\(height) · ISO \(Int(device.iso))", ready: true)
    }

    private func preview(_ buffer: CVPixelBuffer, time: Double, text: String, ready: Bool) {
        guard !recording, time-lastPreview > 0.35 else { return }
        lastPreview = time
        let image = CIImage(cvPixelBuffer: buffer)
        let cg = context.createCGImage(image, from: image.extent)
        onPreview?(cg.map { UIImage(cgImage: $0) }, text, ready)
    }

    private func appendVideo(_ buffer: CMSampleBuffer, width: Int, height: Int, pts: CMTime) {
        guard let writer = writer else { return }
        if writerInput == nil {
            let input = AVAssetWriterInput(mediaType: .video, outputSettings: [AVVideoCodecKey: AVVideoCodecType.h264,
                AVVideoWidthKey: width, AVVideoHeightKey: height,
                AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: 16_000_000, AVVideoMaxKeyFrameIntervalKey: 30]])
            input.expectsMediaDataInRealTime = true
            guard writer.canAdd(input) else { writeDropped += 1; return }
            writer.add(input); writerInput = input
            guard writer.startWriting() else { writeDropped += 1; return }
            writer.startSession(atSourceTime: pts); videoStartPTS = CMTimeGetSeconds(pts)
        }
        guard writer.status == .writing, let input = writerInput, input.isReadyForMoreMediaData else { writeDropped += 1; return }
        if !input.append(buffer) { writeDropped += 1 }
    }

    static func patchImage(_ pixels: [Float]) -> UIImage? {
        let data = Data(pixels.map { UInt8(max(0,min(255,Int($0)))) })
        guard let provider = CGDataProvider(data: data as CFData), let cg = CGImage(width: 64,height: 32,bitsPerComponent: 8,
            bitsPerPixel: 8,bytesPerRow: 64,space: CGColorSpaceCreateDeviceGray(),bitmapInfo: CGBitmapInfo(rawValue: 0),
            provider: provider,decode: nil,shouldInterpolate: false,intent: .defaultIntent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

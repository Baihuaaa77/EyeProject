import AVFoundation
import EyeScreening
import UIKit
import CoreImage

/// 所有采集、像素读取、MediaPipe 与视频写入都在同一个串行队列；缓冲不跨帧持有。
final class LabCamera: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    var onPreview: ((UIImage?, String, Bool) -> Void)?
    var onError: ((String) -> Void)?
    private let queue = DispatchQueue(label: "corneal.lab.camera", qos: .userInitiated, autoreleaseFrequency: .workItem)
    private let session = AVCaptureSession()
    private let context = CIContext()
    private var device: AVCaptureDevice?
    private var configured = false
    private var detector: MainAppDetector?
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
    private var rgbData = Data()
    private var captureColor = false
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
        detector = try MainAppDetector()
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
                    "videoMirrored": false, "orientation": "portrait buffers / MediaPipe up", "reusedExposure": self.reusedExposure]
                completion(.success(self.cameraInfo))
            } catch { completion(.failure(error)) }
        }
    }

    func beginRecording(directory: URL, start: Double, captureColor: Bool = false, completion: @escaping (Result<Void, Error>) -> Void) {
        queue.async {
            do {
                self.writer = try AVAssetWriter(outputURL: directory.appendingPathComponent("camera.mov"), fileType: .mov)
                self.writerInput = nil; self.videoStartPTS = nil
                self.captureColor = true; self.rgbData = Data()
                self.samples = []; self.records = []; self.patchData = Data(); self.eyeImages = [:]; self.lastPoints = [:]
                self.dropped = 0; self.writeDropped = 0; self.frame = 0; self.startTime = start; self.recording = true
                completion(.success(()))
            } catch { completion(.failure(error)) }
        }
    }

    func finish(completion: @escaping ([PatchSample], [FrameRecord], Data, Data, [String: UIImage], [String: Any]) -> Void) {
        queue.async {
            self.recording = false
            let samples = self.samples, records = self.records, data = self.patchData, rgb = self.rgbData, images = self.eyeImages
            var info = self.cameraInfo
            info["captureDroppedFrames"] = self.dropped; info["videoWriteDroppedFrames"] = self.writeDropped
            info["videoStartPTS"] = self.videoStartPTS.map { $0 as Any } ?? NSNull()
            info["receivedFrames"] = self.frame
            let writer = self.writer
            self.writerInput?.markAsFinished()
            self.writer = nil; self.writerInput = nil
            self.samples = []; self.records = []; self.patchData = Data(); self.rgbData = Data()
            if writer?.status == .writing {
                writer?.finishWriting {
                    var finalInfo = info
                    finalInfo["videoStatus"] = writer?.status.rawValue ?? -1
                    finalInfo["videoError"] = writer?.error?.localizedDescription ?? ""
                    completion(samples, records, data, rgb, images, finalInfo)
                }
            } else {
                info["videoStatus"] = writer?.status.rawValue ?? -1
                info["videoError"] = writer?.error?.localizedDescription ?? "未写入视频"
                completion(samples, records, data, rgb, images, info)
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
            let rgbOffset = sample?.rgb == nil ? nil : rgbData.count
            if let sample = sample {
                var little = sample.pixels.map { $0.bitPattern.littleEndian }
                little.withUnsafeBytes { patchData.append(contentsOf: $0) }
                if let rgb = sample.rgb { rgbData.append(contentsOf: rgb) }
                samples.append(sample)
            }
            records.append(FrameRecord(index: frame, pts: CMTimeGetSeconds(pts), hostTime: host,
                exposureSeconds: CMTimeGetSeconds(device.exposureDuration), iso: device.iso, width: width, height: height,
                status: status, eye: sample?.eye, roi: sample?.roi, pupil: sample?.pupil,
                rawCandidate: sample?.rawCandidate, saturation: sample?.saturation, patchOffset: offset,iris: sample?.iris,rgbPatchOffset: rgbOffset,nuisance: sample?.nuisance,baselinePupil: sample?.baselinePupil,baselineReason: sample?.baselineReason))
        }
        guard frame % 2 == 0 else { record("not_analyzed"); return }
        let face: FaceMetrics
        do {
            guard let detected = try detector?.analyze(pixels,hostTime: host) else {
                record("no_face_landmarks"); preview(pixels,time: host,text: "未检测到完整眼部关键点",ready: false); return
            }
            face = detected
        } catch { record("mediapipe_error"); return }
        if let pose = face.headPose, abs(pose.yawDeg)>10 || abs(pose.pitchDeg)>10 || abs(pose.rollDeg)>8 {
            record("head_pose_rejected"); preview(pixels,time: host,text: "请正对手机，保持头部稳定",ready: false); return
        }
        var eyes: [(LabPoint,Double,[LabPoint],Double,OpticalEyeMeasurement?)] = []
        for (landmarks,optical) in [(face.leftEye,face.leftOptical),(face.rightEye,face.rightOptical)] {
            guard let landmarks = landmarks, let center = MainAppDetector.point(landmarks.irisPoints.first), landmarks.irisDiameterPx>=12 else { continue }
            let contour = landmarks.earPoints.map { LabPoint(x: Double($0.x),y: Double($0.y)) }
            let eyeWidth = (contour.map(\.x).max() ?? 0)-(contour.map(\.x).min() ?? 0)
            let eyeHeight = (contour.map(\.y).max() ?? 0)-(contour.map(\.y).min() ?? 0)
            guard eyeWidth>=20,eyeHeight/eyeWidth>=0.08 else { continue }
            eyes.append((center,eyeWidth,contour,Double(landmarks.irisDiameterPx),optical))
        }
        eyes.sort { $0.0.x<$1.0.x }
        guard eyes.count == 2 else { record("iris_landmarks_unavailable"); preview(pixels,time: host,text: "虹膜关键点不足或闭眼",ready: false); return }
        CVPixelBufferLockBaseAddress(pixels, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixels, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(pixels)?.assumingMemoryBound(to: UInt8.self) else { record("pixel_buffer_missing"); return }
        let stride = CVPixelBufferGetBytesPerRow(pixels)
        var irisCount = 0
        for (index, eye) in eyes.enumerated() {
            let name = index == 0 ? "image_left" : "image_right"
            let p = eye.0, w = eye.1*1.4, h = w/2
            let rect = LabRect(x: Double(p.x)-w/2, y: Double(p.y)-h/2, width: w, height: h)
            guard rect.x >= 0, rect.y >= 0, rect.x+w < Double(width), rect.y+h < Double(height) else { record("eye_roi_cropped"); continue }
            if let previous = lastPoints[name], hypot(p.x-Double(previous.x),p.y-Double(previous.y)) > eye.1*0.25 {
                lastPoints[name] = CGPoint(x: p.x,y: p.y); record("large_motion"); continue
            }
            lastPoints[name] = CGPoint(x: p.x,y: p.y)
            var patch = [Float](repeating: 0, count: CornealAnalyzer.count)
            var rgb = captureColor ? [UInt8](repeating: 0,count: CornealAnalyzer.count*3) : []
            for y in 0..<CornealAnalyzer.height { for x in 0..<CornealAnalyzer.width {
                let sx = rect.x+(Double(x)+0.5)/Double(CornealAnalyzer.width)*w-0.5
                let sy = rect.y+(Double(y)+0.5)/Double(CornealAnalyzer.height)*h-0.5
                let ix = max(0,min(width-2,Int(floor(sx)))), iy = max(0,min(height-2,Int(floor(sy))))
                let fx = max(0,min(1,sx-Double(ix))), fy = max(0,min(1,sy-Double(iy)))
                var channels = [Float](repeating: 0,count: 3)
                for channel in 0..<3 {
                    let offset = 2-channel
                    let a = Double(base[iy*stride+ix*4+offset]), b = Double(base[iy*stride+(ix+1)*4+offset])
                    let c = Double(base[(iy+1)*stride+ix*4+offset]), d = Double(base[(iy+1)*stride+(ix+1)*4+offset])
                    channels[channel] = Float((a*(1-fx)+b*fx)*(1-fy)+(c*(1-fx)+d*fx)*fy)
                    if captureColor { rgb[(y*CornealAnalyzer.width+x)*3+channel] = UInt8(max(0,min(255,channels[channel]))) }
                }
                patch[y*CornealAnalyzer.width+x] = 0.2126*channels[0]+0.7152*channels[1]+0.0722*channels[2]
            } }
            let eyelid = eye.2.map { LabPoint(x: ($0.x-rect.x)/w,y: ($0.y-rect.y)/h) }
            let iris = IrisRegion(center: LabPoint(x: 0.5,y: 0.5),radiusX: eye.3*0.5*0.75/w,radiusY: eye.3*0.5*0.75/h,confidence: 1,eyelid: eyelid,
                boundaryRadiusX: eye.3*0.5/w,boundaryRadiusY: eye.3*0.5/h)
            irisCount += 1
            func relative(_ point: Point2f?) -> LabPoint? {
                MainAppDetector.point(point).map { LabPoint(x: ($0.x-rect.x)/w,y: ($0.y-rect.y)/h) }
            }
            let baseline = relative(eye.4?.cornealReflex)
            let raw = baseline.flatMap { iris.contains($0) ? $0 : nil }
            let baselinePupil = relative(eye.4?.pupilCenter)
            let center = LabPoint(x: eye.2.map(\.x).reduce(0,+)/Double(eye.2.count),y: eye.2.map(\.y).reduce(0,+)/Double(eye.2.count))
            let saturation = Double(patch.filter { $0 >= 250 }.count)/Double(patch.count)
            let sample = PatchSample(eye: name, hostTime: host, pixels: patch, roi: rect,
                pupil: LabPoint(x: Double(p.x),y: Double(p.y)), rawCandidate: raw, saturation: saturation,iris: iris,rgb: captureColor ? rgb : nil,
                nuisance: [(p.x-center.x)/eye.1,(p.y-center.y)/eye.1,
                    Double(face.faceBox.left+face.faceBox.right)/Double(width)/2,Double(face.faceBox.top+face.faceBox.bottom)/Double(height)/2],
                baselinePupil: baselinePupil,baselineReason: eye.4?.rejectionReason)
            if saving {
                record(saturation > 0.15 ? "saturated_excluded" : "valid", sample: sample)
                if saturation <= 0.15 { eyeImages[name] = Self.patchImage(patch) }
            }
        }
        preview(pixels, time: host, text: "双眼可见 · 虹膜内区 \(irisCount)/2 · ISO \(Int(device.iso))", ready: true)
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
        guard let provider = CGDataProvider(data: data as CFData), let cg = CGImage(width: CornealAnalyzer.width,height: CornealAnalyzer.height,bitsPerComponent: 8,
            bitsPerPixel: 8,bytesPerRow: CornealAnalyzer.width,space: CGColorSpaceCreateDeviceGray(),bitmapInfo: CGBitmapInfo(rawValue: 0),
            provider: provider,decode: nil,shouldInterpolate: false,intent: .defaultIntent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

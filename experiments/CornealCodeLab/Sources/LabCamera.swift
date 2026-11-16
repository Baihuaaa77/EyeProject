import AVFoundation
import EyeScreening
import UIKit
import CoreImage
import QuartzCore

/// 所有采集、像素读取、MediaPipe 与视频写入都在同一个串行队列；缓冲不跨帧持有。
final class LabCamera: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    var onPreview: ((String, Bool, Double?, Double, Double?) -> Void)?
    var onError: ((String) -> Void)?
    private let queue = DispatchQueue(label: "corneal.lab.camera", qos: .userInitiated, autoreleaseFrequency: .workItem)
    private let session = AVCaptureSession()
    var captureSession: AVCaptureSession { session }
    private var device: AVCaptureDevice?
    private var configured = false
    private var active = false
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
    private var nativeFile: FileHandle?
    private var nativeBytes = 0
    private var captureColor = false
    private var eyeImages: [String: UIImage] = [:]
    private var lastPoints: [String: CGPoint] = [:]
    private var dropped = 0
    private var writeDropped = 0
    private var cameraInfo: [String: Any] = [:]
    private var hasLockedExposure = false
    private var reusedExposure = false
    private var trialConfig = TrialConfiguration()
    private var cachedFace: FaceMetrics?
    private var cachedFaceTime = -Double.infinity
    private var stableCenters: [String: LabPoint] = [:]
    private var landmarkFresh = true
    private var observers: [NSObjectProtocol] = []
    private var distanceCM: Double?
    private var irisDiameterPixels: Double?
    private var distanceTime = -Double.infinity
    private var distanceSource = ""
    private var focalLengthPx = 0.0
    private var windowStart = 0.0
    private var windowFrames = 0
    private var observedFPS = 0.0
    private var processingTotalMS = 0.0
    private var processingFrames = 0
    private var lastProcessingMS = 0.0

    /// 开始时再次读取本轮开关，避免准备相机后修改曝光设置却没有生效。
    func applyExposurePolicy(reuse: Bool, configuration: TrialConfiguration) {
        queue.async {
            self.trialConfig = configuration
            guard let device = self.device else { return }
            do {
                try device.lockForConfiguration(); defer { device.unlockForConfiguration() }
                self.reusedExposure = reuse && self.hasLockedExposure && abs(CMTimeGetSeconds(device.exposureDuration)*1000-configuration.exposureMS)<0.5 && abs(Double(device.iso)-configuration.exposureISO)<max(1,configuration.exposureISO*0.03)
                if !self.reusedExposure {
                    self.hasLockedExposure = false
                    if device.isExposureModeSupported(.continuousAutoExposure) { device.exposureMode = .continuousAutoExposure }
                    if device.isWhiteBalanceModeSupported(.continuousAutoWhiteBalance) { device.whiteBalanceMode = .continuousAutoWhiteBalance }
                }
            } catch { self.onError?("曝光设置失败："+error.localizedDescription) }
        }
    }

    override init() {
        super.init()
        observers.append(NotificationCenter.default.addObserver(forName: AVCaptureSession.wasInterruptedNotification,
            object: session, queue: nil) { [weak self] _ in self?.reportSessionError("相机被系统中断，本轮已停止；请重新准备相机。") })
        observers.append(NotificationCenter.default.addObserver(forName: AVCaptureSession.runtimeErrorNotification,
            object: session, queue: nil) { [weak self] _ in self?.reportSessionError("相机运行异常，请重新准备相机。") })
    }
    deinit { observers.forEach { NotificationCenter.default.removeObserver($0) } }

    /// 未准备或已停止的预览层也可能收到系统通知，不把它当作正在采集的故障。
    private func reportSessionError(_ message: String) {
        queue.async { [weak self] in
            guard let self = self,self.active else { return }
            self.onError?(message)
        }
    }

    func prepare(reuseExposure: Bool = true, configuration: TrialConfiguration) {
        AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
            guard let self = self else { return }
            guard granted else { self.onError?("请在 iPhone 设置中允许相机权限。"); return }
            self.queue.async {
                do {
                    self.active = true
                    self.trialConfig = configuration
                    if !self.configured { try self.configure() }
                    try self.selectCaptureFormat()
                    if let device = self.device {
                        try device.lockForConfiguration()
                        self.reusedExposure = reuseExposure && self.hasLockedExposure && abs(CMTimeGetSeconds(device.exposureDuration)*1000-configuration.exposureMS)<0.5 && abs(Double(device.iso)-configuration.exposureISO)<max(1,configuration.exposureISO*0.03)
                        if !self.reusedExposure {
                            self.hasLockedExposure = false
                            if device.isExposureModeSupported(.continuousAutoExposure) { device.exposureMode = .continuousAutoExposure }
                            if device.isWhiteBalanceModeSupported(.continuousAutoWhiteBalance) { device.whiteBalanceMode = .continuousAutoWhiteBalance }
                        }
                        device.unlockForConfiguration()
                    }
                    if !self.session.isRunning { self.session.startRunning() }
                } catch { self.active = false; self.onError?("相机准备失败：\(error.localizedDescription)") }
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
            if connection.isCameraIntrinsicMatrixDeliverySupported { connection.isCameraIntrinsicMatrixDeliveryEnabled = true }
        }
        try device.lockForConfiguration()
        if device.isLowLightBoostSupported { device.automaticallyEnablesLowLightBoostWhenAvailable = false }
        device.unlockForConfiguration()
        configured = true
    }

    private func selectCaptureFormat() throws {
        guard let device = device else { return }
        let fps = Double(trialConfig.captureFPS)
        session.beginConfiguration(); session.sessionPreset = .inputPriority; session.commitConfiguration()
        let ceiling = trialConfig.prefer4K ? 3840 : 1920
        let formats = device.formats.filter { format in
            let d = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
            return d.width<=ceiling && d.height<=2160 && format.videoSupportedFrameRateRanges.contains { $0.minFrameRate<=fps && $0.maxFrameRate>=fps }
        }.sorted { a,b in
            let x = CMVideoFormatDescriptionGetDimensions(a.formatDescription),y = CMVideoFormatDescriptionGetDimensions(b.formatDescription)
            return Int64(x.width)*Int64(x.height)>Int64(y.width)*Int64(y.height)
        }
        guard let format = formats.first else { throw NSError(domain: "Lab",code: 9,userInfo: [NSLocalizedDescriptionKey:"没有支持所选帧率的前摄格式"]) }
        try device.lockForConfiguration(); defer { device.unlockForConfiguration() }
        if device.activeFormat != format { device.activeFormat = format; hasLockedExposure = false }
        device.activeVideoMinFrameDuration = CMTime(value: 1,timescale: Int32(trialConfig.captureFPS))
        device.activeVideoMaxFrameDuration = device.activeVideoMinFrameDuration
        device.automaticallyAdjustsVideoHDREnabled = false
        if format.isVideoHDRSupported { device.isVideoHDREnabled = false }
        device.isGlobalToneMappingEnabled = false
        cachedFace = nil; stableCenters = [:]; distanceCM = nil; distanceTime = -Double.infinity
        windowStart = 0; windowFrames = 0; observedFPS = 0
    }

    private func effectState(_ device: AVCaptureDevice) -> [String:Bool] {
        var states = ["portraitEnabled": AVCaptureDevice.isPortraitEffectEnabled,"portraitActive": device.isPortraitEffectActive,
                      "studioLightEnabled": AVCaptureDevice.isStudioLightEnabled,"studioLightActive": device.isStudioLightActive,
                      "centerStageEnabled": AVCaptureDevice.isCenterStageEnabled,"centerStageActive": device.isCenterStageActive]
        if #available(iOS 17.0, *) { states["reactionEffectsEnabled"] = AVCaptureDevice.reactionEffectsEnabled }
        return states
    }

    /// 黑背景、恒定白点预适应后锁定曝光和白平衡。锁定失败即拒绝编码试验。
    func lockExposure(completion: @escaping (Result<[String: Any], Error>) -> Void) {
        queue.async {
            do {
                guard let device = self.device, self.session.isRunning,
                      device.isExposureModeSupported(.locked), device.isWhiteBalanceModeSupported(.locked) else {
                    throw NSError(domain: "Lab", code: 4, userInfo: [NSLocalizedDescriptionKey: "本机无法锁定曝光或白平衡，不能做有效编码对照"])
                }
                guard !self.effectState(device).values.contains(true) else { throw NSError(domain: "Lab",code: 10,userInfo: [NSLocalizedDescriptionKey:"请先在控制中心关闭人像、影棚光、居中及反应特效"]) }
                try device.lockForConfiguration()
                guard device.isExposureModeSupported(.custom) else { device.unlockForConfiguration(); throw NSError(domain: "Lab",code: 11,userInfo: [NSLocalizedDescriptionKey:"设备不支持手动曝光"]) }
                let seconds = self.trialConfig.exposureMS/1000
                let minSeconds = CMTimeGetSeconds(device.activeFormat.minExposureDuration)
                let maxSeconds = min(CMTimeGetSeconds(device.activeFormat.maxExposureDuration),1/Double(self.trialConfig.captureFPS))
                guard seconds>=minSeconds,seconds<=maxSeconds else { device.unlockForConfiguration(); throw NSError(domain: "Lab",code: 12,userInfo: [NSLocalizedDescriptionKey:"当前格式不支持请求的曝光时长"]) }
                let iso = Float(self.trialConfig.exposureISO)
                guard iso>=device.activeFormat.minISO,iso<=device.activeFormat.maxISO else { device.unlockForConfiguration(); throw NSError(domain: "Lab",code: 13,userInfo: [NSLocalizedDescriptionKey:"所选ISO超出当前格式支持范围"]) }
                if !self.reusedExposure {
                    let settled = DispatchSemaphore(value: 0)
                    device.setExposureModeCustom(duration: CMTime(seconds: seconds,preferredTimescale: 1_000_000),iso: iso) { _ in settled.signal() }
                    device.unlockForConfiguration()
                    guard settled.wait(timeout: .now()+3) == .success else { throw NSError(domain: "Lab",code: 14,userInfo: [NSLocalizedDescriptionKey:"手动曝光未在期限内生效"]) }
                    try device.lockForConfiguration()
                }
                device.whiteBalanceMode = .locked
                if device.isFocusModeSupported(.locked) { device.focusMode = .locked }
                device.unlockForConfiguration()
                self.hasLockedExposure = true
                self.cameraInfo = ["camera": device.localizedName, "uniqueFormat": String(describing: device.activeFormat),
                    "exposureSeconds": CMTimeGetSeconds(device.exposureDuration), "iso": device.iso,
                    "exposureLocked": device.exposureMode == .locked || device.exposureMode == .custom, "whiteBalanceLocked": device.whiteBalanceMode == .locked,
                    "focusMode": device.focusMode.rawValue, "requestedFPS": self.trialConfig.captureFPS, "landmarksEveryNFrames": 2,"patchEveryNFrames": 1,
                    "requestedExposureMS": self.trialConfig.exposureMS,"requestedISO": self.trialConfig.exposureISO,
                    "videoHDR": device.isVideoHDREnabled,"globalToneMapping": device.isGlobalToneMappingEnabled,
                    "ISP说明": "关闭HDR与全局映射不代表关闭局部ISP处理", "systemEffects": self.effectState(device),
                    "patchGrid": "128x64分析网格，中心EMA；虹膜较大时扩大原生裁剪窗并等比采样，原生PNG不缩小", "landmarkReuseMaxSeconds": 0.08,
                    "inferenceMaxDimension": 1280,"preview": "原生AVCaptureVideoPreviewLayer，独立于分析回调",
                    "videoMirrored": false, "orientation": "portrait buffers / MediaPipe up", "reusedExposure": self.reusedExposure]
                completion(.success(self.cameraInfo))
            } catch { completion(.failure(error)) }
        }
    }

    func beginRecording(directory: URL, start: Double, captureColor: Bool = false, completion: @escaping (Result<Double, Error>) -> Void) {
        queue.async {
            do {
                self.writer = try AVAssetWriter(outputURL: directory.appendingPathComponent("camera.mov"), fileType: .mov)
                let nativeURL = directory.appendingPathComponent("temporary_eye_pixels.bgra")
                FileManager.default.createFile(atPath: nativeURL.path,contents: nil)
                self.nativeFile = try FileHandle(forWritingTo: nativeURL); self.nativeBytes = 0
                self.writerInput = nil; self.videoStartPTS = nil
                self.captureColor = true; self.rgbData = Data()
                self.samples = []; self.records = []; self.patchData = Data(); self.eyeImages = [:]; self.lastPoints = [:]; self.stableCenters = [:]
                self.dropped = 0; self.writeDropped = 0; self.frame = 0; self.startTime = max(start,labHostTime()+0.3); self.recording = true
                self.processingTotalMS = 0; self.processingFrames = 0
                completion(.success(self.startTime))
            } catch { completion(.failure(error)) }
        }
    }

    func finish(completion: @escaping ([PatchSample], [FrameRecord], Data, Data, [String: UIImage], [String: Any]) -> Void) {
        queue.async {
            self.recording = false
            try? self.nativeFile?.close(); self.nativeFile = nil
            let samples = self.samples, records = self.records, data = self.patchData, rgb = self.rgbData, images = self.eyeImages
            var info = self.cameraInfo
            info["captureDroppedFrames"] = self.dropped; info["videoWriteDroppedFrames"] = self.writeDropped
            info["videoStartPTS"] = self.videoStartPTS.map { $0 as Any } ?? NSNull()
            info["receivedFrames"] = self.frame
            info["averageProcessingMS"] = self.processingTotalMS/Double(max(1,self.processingFrames))
            info["lastDeliveryFPS"] = self.observedFPS
            info["inferenceBackend"] = self.detector?.usingGPU == true ? "GPU" : "CPU"
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
        self.active = false
        self.recording = false
        try? self.nativeFile?.close(); self.nativeFile = nil
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
        let processingStart = CACurrentMediaTime()
        defer {
            lastProcessingMS = (CACurrentMediaTime()-processingStart)*1000
            if recording { processingTotalMS += lastProcessingMS; processingFrames += 1 }
        }
        if windowStart == 0 { windowStart = host }
        windowFrames += 1
        if host-windowStart>=1 { observedFPS = Double(windowFrames)/max(0.001,host-windowStart); windowFrames = 0; windowStart = host }
        let width = CVPixelBufferGetWidth(pixels), height = CVPixelBufferGetHeight(pixels)
        let saving = recording && host >= startTime
        if recording && !saving { return }
        frame += 1
        if saving { appendVideo(buffer, width: width, height: height, pts: pts) }
        func record(_ status: String, sample: PatchSample? = nil, rejectedEye: String? = nil) {
            guard saving else { return }
            let offset = sample == nil ? nil : patchData.count
            let rgbOffset = sample?.rgb == nil ? nil : rgbData.count
            var nativeOffset: Int?, nativeROI: LabRect?
            // 相机原像素按行写入临时文件；不在实时管线上做 PNG 压缩，也不跨帧占用 PixelBuffer。
            if let sample = sample,let file = nativeFile {
                let x = Int(floor(sample.roi.x)), y = Int(floor(sample.roi.y))
                let w = Int(ceil(sample.roi.x+sample.roi.width))-x, h = Int(ceil(sample.roi.y+sample.roi.height))-y
                // 有 sample 时调用方已持有当前缓冲的只读锁。
                if let base = CVPixelBufferGetBaseAddress(pixels)?.assumingMemoryBound(to: UInt8.self) {
                    let stride = CVPixelBufferGetBytesPerRow(pixels)
                    var bytes = Data(capacity: w*h*4)
                    for row in y..<(y+h) { bytes.append(contentsOf: UnsafeBufferPointer(start: base+row*stride+x*4,count: w*4)) }
                    do {
                        try file.write(contentsOf: bytes); nativeOffset = nativeBytes; nativeBytes += bytes.count
                        nativeROI = LabRect(x: Double(x),y: Double(y),width: Double(w),height: Double(h))
                    } catch { onError?("原生眼区像素写入失败："+error.localizedDescription) }
                }
            }
            if let sample = sample {
                var little = sample.pixels.map { $0.bitPattern.littleEndian }
                little.withUnsafeBytes { patchData.append(contentsOf: $0) }
                if let rgb = sample.rgb { rgbData.append(contentsOf: rgb) }
                samples.append(sample)
            }
            records.append(FrameRecord(index: frame, pts: CMTimeGetSeconds(pts), hostTime: host,
                exposureSeconds: CMTimeGetSeconds(device.exposureDuration), iso: device.iso, width: width, height: height,
                status: status, eye: sample?.eye ?? rejectedEye, roi: sample?.roi, pupil: sample?.pupil,
                rawCandidate: sample?.rawCandidate, saturation: sample?.saturation, patchOffset: offset,iris: sample?.iris,rgbPatchOffset: rgbOffset,nuisance: sample?.nuisance,baselinePupil: sample?.baselinePupil,baselineReason: sample?.baselineReason,nativeOffset: nativeOffset,nativeROI: nativeROI,landmarkFresh: landmarkFresh,landmarkAgeSeconds: host-cachedFaceTime,
                distanceCM: host-distanceTime<=0.2 ? distanceCM : nil,focalLengthPx: focalLengthPx,distanceSource: distanceSource,processingMS: (CACurrentMediaTime()-processingStart)*1000))
        }
        if saving && frame % 30 == 0 && self.effectState(device).values.contains(true) { record("system_effect_active"); onError?("采集中系统视频效果被开启，本轮停止"); return }
        let face: FaceMetrics
        do {
            landmarkFresh = frame % 2 == 0 || host-cachedFaceTime>0.08
            let detectedFace: FaceMetrics?
            if landmarkFresh { detectedFace = try detector?.analyze(pixels,hostTime: host); cachedFace = detectedFace; cachedFaceTime = host }
            else { detectedFace = cachedFace }
            guard let detected = detectedFace else {
                record("no_face_landmarks"); preview(pixels,time: host,text: "未检测到完整眼部关键点",ready: false); return
            }
            face = detected
        } catch { record("mediapipe_error"); preview(pixels,time: host,text: "关键点检测失败，请重新准备相机",ready: false); return }
        if let pose = face.headPose, abs(pose.yawDeg)>10 || abs(pose.pitchDeg)>10 || abs(pose.rollDeg)>8 {
            record("head_pose_rejected"); preview(pixels,time: host,text: "请正对手机，保持头部稳定",ready: false); return
        }
        let opticalGray = CameraGrayImage(pixels); defer { opticalGray.release() }
        var eyes: [(LabPoint,Double,[LabPoint],Double,OpticalEyeMeasurement?)] = []
        for landmarks in [face.leftEye,face.rightEye] {
            // 每个交付帧都用当前像素重算相同Kotlin基线；仅关键点允许短暂复用。
            let optical = saving ? EyeOpticalAlgorithm.shared.measureFullIris(bitmap: opticalGray,eye: landmarks) : nil
            guard let landmarks = landmarks, let center = MainAppDetector.point(landmarks.irisPoints.first), landmarks.irisDiameterPx>=12 else { continue }
            let contour = landmarks.earPoints.map { LabPoint(x: Double($0.x),y: Double($0.y)) }
            let eyeWidth = (contour.map(\.x).max() ?? 0)-(contour.map(\.x).min() ?? 0)
            let eyeHeight = (contour.map(\.y).max() ?? 0)-(contour.map(\.y).min() ?? 0)
            guard eyeWidth>=20,eyeHeight/eyeWidth>=0.08 else { continue }
            eyes.append((center,eyeWidth,contour,Double(landmarks.irisDiameterPx),optical))
        }
        eyes.sort { $0.0.x<$1.0.x }
        guard eyes.count == 2 else { record("iris_landmarks_unavailable"); preview(pixels,time: host,text: "虹膜关键点不足或闭眼",ready: false); return }
        let focal = DeviceOptics.focal(buffer,device: device,width: width,height: height)
        irisDiameterPixels = eyes.map { $0.3 }.min()
        focalLengthPx = focal.0; distanceSource = focal.1
        let measured = Double(DepthFromIris.shared.distanceCm(irisDiameterPx: Float(eyes.map { $0.3 }.reduce(0,+)/2),focalLengthPx: Float(focalLengthPx)))
        if measured.isFinite && (15...100).contains(measured) {
            distanceCM = measured; distanceTime = host
        } else { distanceCM = nil }
        // 准备与预适应阶段只需几何、测距和原生预览，省掉两眼像素提取与序列分配。
        if !saving {
            preview(pixels,time: host,text: statusText(irisPixels: eyes.map { $0.3 }.min() ?? 0),ready: distanceCM != nil)
            return
        }
        CVPixelBufferLockBaseAddress(pixels, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixels, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(pixels)?.assumingMemoryBound(to: UInt8.self) else { record("pixel_buffer_missing"); return }
        let stride = CVPixelBufferGetBytesPerRow(pixels)
        var irisCount = 0
        for (index, eye) in eyes.enumerated() {
            let name = index == 0 ? "image_left" : "image_right"
            // 窗口至少容纳完整虹膜；近距离4K虹膜可能高于64像素，不能在上下边缘裁掉。
            let p = eye.0,h = max(64,ceil(eye.3*1.25)),w = h*2
            let previousCenter = stableCenters[name] ?? p
            let smoothed = landmarkFresh ? LabPoint(x: previousCenter.x*0.65+p.x*0.35,y: previousCenter.y*0.65+p.y*0.35) : previousCenter
            stableCenters[name] = smoothed
            let rect = LabRect(x: floor(smoothed.x)-w/2,y: floor(smoothed.y)-h/2,width: w,height: h)
            guard rect.x >= 0, rect.y >= 0, rect.x+w < Double(width), rect.y+h < Double(height) else { record("eye_roi_cropped",rejectedEye: name); continue }
            if let previous = lastPoints[name], hypot(p.x-Double(previous.x),p.y-Double(previous.y)) > eye.1*0.25 {
                lastPoints[name] = CGPoint(x: p.x,y: p.y); record("large_motion",rejectedEye: name); continue
            }
            lastPoints[name] = CGPoint(x: p.x,y: p.y)
            var patch = [Float](repeating: 0, count: CornealAnalyzer.count)
            var rgb = captureColor ? [UInt8](repeating: 0,count: CornealAnalyzer.count*3) : []
            for y in 0..<CornealAnalyzer.height { for x in 0..<CornealAnalyzer.width {
                let i = y*CornealAnalyzer.width+x
                let r: Float,g: Float,b: Float
                if w == 128 {
                    let source = (Int(rect.y)+y)*stride+(Int(rect.x)+x)*4
                    r = Float(base[source+2]); g = Float(base[source+1]); b = Float(base[source])
                } else {
                    let sx = rect.x+(Double(x)+0.5)*w/128-0.5,sy = rect.y+(Double(y)+0.5)*h/64-0.5
                    let ix = Int(floor(sx)),iy = Int(floor(sy)),fx = Float(sx-Double(ix)),fy = Float(sy-Double(iy))
                    func channel(_ offset: Int) -> Float {
                        let a = Float(base[iy*stride+ix*4+offset]),b = Float(base[iy*stride+(ix+1)*4+offset])
                        let c = Float(base[(iy+1)*stride+ix*4+offset]),d = Float(base[(iy+1)*stride+(ix+1)*4+offset])
                        return (a*(1-fx)+b*fx)*(1-fy)+(c*(1-fx)+d*fx)*fy
                    }
                    r = channel(2); g = channel(1); b = channel(0)
                }
                patch[i] = 0.2126*r+0.7152*g+0.0722*b
                if captureColor { rgb[i*3] = UInt8(max(0,min(255,r))); rgb[i*3+1] = UInt8(max(0,min(255,g))); rgb[i*3+2] = UInt8(max(0,min(255,b))) }
            } }
            let eyelid = eye.2.map { LabPoint(x: ($0.x-rect.x)/w,y: ($0.y-rect.y)/h) }
            let iris = IrisRegion(center: LabPoint(x: (p.x-rect.x)/w,y: (p.y-rect.y)/h),radiusX: eye.3*0.5*IrisSearch.radiusFraction/w,radiusY: eye.3*0.5*IrisSearch.radiusFraction/h,confidence: 1,eyelid: eyelid,
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
            }
        }
        preview(pixels,time: host,text: statusText(irisPixels: eyes.map { $0.3 }.min() ?? 0),ready: distanceCM != nil)
    }

    private func statusText(irisPixels: Double) -> String {
        let distance = distanceCM.map { String(format: "%.1f cm",$0) } ?? "测距不可用，请保持15–100 cm"
        return "\(distance) · 虹膜 \(Int(irisPixels)) px · 采集 \(Int(observedFPS.rounded())) fps · 分析 \(Int(lastProcessingMS)) ms · \(detector?.usingGPU == true ? "GPU" : "CPU")"
    }
    private func preview(_ buffer: CVPixelBuffer, time: Double, text: String, ready: Bool) {
        guard !recording, time-lastPreview > 0.2 else { return }
        lastPreview = time
        onPreview?(text,ready,time-distanceTime<=0.2 ? distanceCM : nil,time,ready ? irisDiameterPixels : nil)
    }

    private func appendVideo(_ buffer: CMSampleBuffer, width: Int, height: Int, pts: CMTime) {
        guard let writer = writer else { return }
        if writerInput == nil {
            let input = AVAssetWriterInput(mediaType: .video, outputSettings: [AVVideoCodecKey: AVVideoCodecType.h264,
                AVVideoWidthKey: width, AVVideoHeightKey: height,
                AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: max(16_000_000,width*height*8), AVVideoMaxKeyFrameIntervalKey: 30]])
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

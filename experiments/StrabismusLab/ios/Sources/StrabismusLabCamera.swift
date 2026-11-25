import AVFoundation
import CoreImage
import CoreMedia
import CoreVideo
import EyeScreening
import MediaPipeTasksVision
import SwiftUI
import UIKit

/// 将单帧 CVPixelBuffer 暴露给 KMP 共用光学算法的临时灰度视图，处理完毕后必须立刻 release 归还相机缓冲池。
final class LabGrayFrame: NSObject, GrayImage {
    private var buffer: CVPixelBuffer?
    let width: Int32
    let height: Int32

    init(_ buffer: CVPixelBuffer) {
        self.buffer = buffer
        self.width = Int32(CVPixelBufferGetWidth(buffer))
        self.height = Int32(CVPixelBufferGetHeight(buffer))
        super.init()
    }

    func release() { buffer = nil }

    func grayRegion(left: Int32, top: Int32, width: Int32, height: Int32) -> KotlinIntArray {
        guard let buffer = buffer else { return KotlinIntArray(size: width * height) }
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(buffer) else { return KotlinIntArray(size: width * height) }
        let stride = Int32(CVPixelBufferGetBytesPerRow(buffer))
        return BgraPixels.shared.grayRegion(
            base: base.assumingMemoryBound(to: UInt8.self),
            bytesPerRow: stride,
            left: left,
            top: top,
            width: width,
            height: height
        )
    }
}

/// 独立 4K 前置相机采集管线：MediaPipe 478 关键点 + KMP ScreenPointOptics（默认 0.80f 虹膜搜索半径）。
final class StrabismusLabCamera: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    let captureSession = AVCaptureSession()
    var onFrame: ((FaceLandmarkFrame, Float, Int64) -> Void)?
    var onError: ((String) -> Void)?

    private let queue = DispatchQueue(label: "com.zkyjss.strabismuslab.camera", autoreleaseFrequency: .workItem)
    private let resizeContext = CIContext(options: [.cacheIntermediates: false])
    private var detector: FaceLandmarker?
    private var device: AVCaptureDevice?
    private var active = false
    private var configured = false
    private var usingGpu = true
    private var searchRadius: Float = 0.80
    private var lastAnalyzedMs: Int64 = 0
    private var smoothedFps: Float = 30
    private var lastFrameNanos: UInt64 = 0

    func setSearchRadius(_ radius: Float) {
        queue.async { self.searchRadius = min(0.95, max(0.55, radius)) }
    }

    func start(searchRadius: Float) {
        queue.async {
            self.searchRadius = min(0.95, max(0.55, searchRadius))
            self.active = true
            do {
                if !self.configured {
                    try self.configureSession()
                    self.configured = true
                }
                if self.detector == nil {
                    self.detector = try self.makeDetector(gpu: true)
                    self.usingGpu = true
                }
                if !self.captureSession.isRunning {
                    self.captureSession.startRunning()
                }
            } catch {
                DispatchQueue.main.async { self.onError?("前置相机启动失败：\(error.localizedDescription)") }
            }
        }
    }

    func stop() {
        queue.async {
            self.active = false
            if self.captureSession.isRunning {
                self.captureSession.stopRunning()
            }
        }
    }

    private func configureSession() throws {
        captureSession.beginConfiguration()
        defer { captureSession.commitConfiguration() }

        guard let cam = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front) else {
            throw NSError(domain: "StrabismusLabCamera", code: 1, userInfo: [NSLocalizedDescriptionKey: "未找到前置摄像头"])
        }
        self.device = cam
        let input = try AVCaptureDeviceInput(device: cam)
        if captureSession.canAddInput(input) { captureSession.addInput(input) }

        let output = AVCaptureVideoDataOutput()
        output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        output.alwaysDiscardsLateVideoFrames = true
        output.setSampleBufferDelegate(self, queue: queue)
        if captureSession.canAddOutput(output) { captureSession.addOutput(output) }

        // 优先 4K 前置输出，逐级回退 1080p / 720p，避免手动锁定不兼容的 HDR/binned 格式
        if captureSession.canSetSessionPreset(.hd4K3840x2160) {
            captureSession.sessionPreset = .hd4K3840x2160
        } else if captureSession.canSetSessionPreset(.hd1920x1080) {
            captureSession.sessionPreset = .hd1920x1080
        } else {
            captureSession.sessionPreset = .hd1280x720
        }

        if let conn = output.connection(with: .video) {
            if conn.isVideoOrientationSupported { conn.videoOrientation = .portrait }
            if conn.isVideoMirroringSupported {
                conn.automaticallyAdjustsVideoMirroring = false
                conn.isVideoMirrored = false
            }
            if conn.isCameraIntrinsicMatrixDeliverySupported { conn.isCameraIntrinsicMatrixDeliveryEnabled = true }
        }
    }

    private func makeDetector(gpu: Bool) throws -> FaceLandmarker {
        guard let modelPath = Bundle.main.path(forResource: "face_landmarker", ofType: "task") else {
            throw NSError(domain: "StrabismusLabCamera", code: 2, userInfo: [NSLocalizedDescriptionKey: "缺少 face_landmarker.task 模型"])
        }
        let options = FaceLandmarkerOptions()
        options.baseOptions.modelAssetPath = modelPath
        options.baseOptions.delegate = gpu ? .GPU : .CPU
        options.runningMode = .video
        options.numFaces = 1
        options.minFaceDetectionConfidence = 0.5
        options.minFacePresenceConfidence = 0.5
        options.minTrackingConfidence = 0.5
        options.outputFacialTransformationMatrixes = true
        return try FaceLandmarker(options: options)
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        autoreleasepool { process(sampleBuffer) }
    }

    private func inferenceBuffer(from buffer: CVPixelBuffer) throws -> CVPixelBuffer {
        let w = CVPixelBufferGetWidth(buffer)
        let h = CVPixelBufferGetHeight(buffer)
        let scale = min(1.0, 1280.0 / Double(max(w, h)))
        guard scale < 1.0 else { return buffer }
        var scaled: CVPixelBuffer?
        let status = CVPixelBufferCreate(
            kCFAllocatorDefault,
            Int(Double(w) * scale),
            Int(Double(h) * scale),
            kCVPixelFormatType_32BGRA,
            [kCVPixelBufferIOSurfacePropertiesKey as String: [:]] as CFDictionary,
            &scaled
        )
        guard status == kCVReturnSuccess, let out = scaled else { return buffer }
        resizeContext.render(CIImage(cvPixelBuffer: buffer).transformed(by: CGAffineTransform(scaleX: scale, y: scale)), to: out)
        return out
    }

    private func process(_ sampleBuffer: CMSampleBuffer) {
        guard active, let detector = detector, let buffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let nowMs = Int64(ProcessInfo.processInfo.systemUptime * 1000)
        if nowMs - lastAnalyzedMs < 28 { return }
        lastAnalyzedMs = nowMs

        let startNanos = DispatchTime.now().uptimeNanoseconds
        if lastFrameNanos > 0 && startNanos > lastFrameNanos {
            let inst = Float(1_000_000_000.0 / Double(startNanos - lastFrameNanos))
            smoothedFps = smoothedFps * 0.8 + inst * 0.2
        }
        lastFrameNanos = startNanos

        do {
            let inferBuf = try inferenceBuffer(from: buffer)
            let mpImage = try MPImage(pixelBuffer: inferBuf, orientation: .up)
            let result: FaceLandmarkerResult
            do {
                result = try detector.detect(videoFrame: mpImage, timestampInMilliseconds: Int(nowMs))
            } catch {
                guard usingGpu else { throw error }
                self.detector = try makeDetector(gpu: false)
                self.usingGpu = false
                return
            }

            let points = result.faceLandmarks.first ?? []
            var flat = [Float](repeating: 0, count: points.count * 3)
            for (i, p) in points.enumerated() {
                flat[i * 3] = p.x
                flat[i * 3 + 1] = p.y
                flat[i * 3 + 2] = p.z
            }
            let ktPoints = flat.withUnsafeMutableBufferPointer { raw in
                raw.baseAddress.map { BgraPixels.shared.floats(base: $0, count: Int32(raw.count)) } ?? KotlinFloatArray(size: 0)
            }
            let matrix = result.facialTransformationMatrixes.first
            let ktTransform = KotlinFloatArray(size: matrix == nil ? 0 : 16)
            if let m = matrix {
                for r in 0..<4 {
                    for c in 0..<4 {
                        ktTransform.set(index: Int32(c * 4 + r), value: m.data[r * 4 + c])
                    }
                }
            }

            let gray = LabGrayFrame(buffer)
            defer { gray.release() }

            var focal: Float = 0
            if let data = CMGetAttachment(sampleBuffer, key: kCMSampleBufferAttachmentKey_CameraIntrinsicMatrix, attachmentModeOut: nil) as? Data,
               data.count >= 48 {
                let vals = data.withUnsafeBytes { Array($0.bindMemory(to: Float.self)) }
                if vals.count >= 12 { focal = max(vals[0], vals[5]) }
            }
            if focal <= 0, let dev = device {
                let fovRad = dev.activeFormat.videoFieldOfView * Float.pi / 180
                focal = Float(max(gray.width, gray.height)) / (2 * tan(fovRad / 2))
            }

            let face: FaceMetrics? = points.isEmpty ? nil : ScreenPointOptics.shared.face(
                points: ktPoints,
                matrix: ktTransform,
                image: gray,
                searchRadiusFraction: searchRadius
            )
            let latencyMs = Int64((DispatchTime.now().uptimeNanoseconds - startNanos) / 1_000_000)
            let frame = FaceLandmarkFrame(
                faces: face.map { [$0] } ?? [],
                sourceWidth: gray.width,
                sourceHeight: gray.height,
                timestampMillis: Int64(Date().timeIntervalSince1970 * 1000),
                detectionLatencyMillis: latencyMs,
                fps: smoothedFps,
                imageBrightness: KotlinFloat(value: 0),
                analysisElapsedRealtimeMs: KotlinLong(value: nowMs),
                sourceElapsedRealtimeMs: KotlinLong(value: nowMs)
            )
            onFrame?(frame, focal, nowMs)
        } catch {
            DispatchQueue.main.async { self.onError?("帧分析异常：\(error.localizedDescription)") }
        }
    }
}

/// SwiftUI 原生相机预览包装器。
struct LabCameraPreviewView: UIViewRepresentable {
    let session: AVCaptureSession

    final class PreviewHostView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }

    func makeUIView(context: Context) -> PreviewHostView {
        let view = PreviewHostView()
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(_ uiView: PreviewHostView, context: Context) {
        if uiView.previewLayer.session !== session {
            uiView.previewLayer.session = session
        }
    }
}

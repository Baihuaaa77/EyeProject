import AVFoundation
import CoreMedia
import CoreVideo
import UIKit
import EyeScreening
import MediaPipeTasksVision

/// PixelBuffer 保留到共用光学算法完成；显式使用 BGRA→RGB 灰度，不用 YUV 亮度代替。
/// 像素由共用核心的 BgraPixels 一次读完（逐像素跨桥会把分析帧率拖到每秒几帧）。
final class CameraGrayImage: NSObject, GrayImage {
    let buffer: CVPixelBuffer
    var width: Int32 { Int32(CVPixelBufferGetWidth(buffer)) }
    var height: Int32 { Int32(CVPixelBufferGetHeight(buffer)) }
    init(_ buffer: CVPixelBuffer) { self.buffer = buffer; super.init() }
    private func withPixels<T>(_ fallback: T, _ body: (UnsafeMutablePointer<UInt8>, Int32) -> T) -> T {
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(buffer) else { return fallback }
        return body(base.assumingMemoryBound(to: UInt8.self), Int32(CVPixelBufferGetBytesPerRow(buffer)))
    }
    func grayRegion(left: Int32, top: Int32, width: Int32, height: Int32) -> KotlinIntArray {
        withPixels(KotlinIntArray(size: width * height)) { bytes, stride in
            BgraPixels.shared.grayRegion(base: bytes, bytesPerRow: stride, left: left, top: top, width: width, height: height)
        }
    }
    func brightness() -> Float {
        withPixels(Float(0)) { bytes, stride in BgraPixels.shared.brightness(base: bytes, bytesPerRow: stride, width: width, height: height) }
    }
}

final class EyeCamera: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    /// 相机队列上已算好的一帧：关键点几何与光学测量、图像宽高、焦距、单调时间、亮度。
    var onFrame: ((FaceMetrics?, Int32, Int32, Float, Int64, Float) -> Void)?
    var onError: ((String) -> Void)?
    /// 眨眼对比实验页用：同一帧的原始像素与共用几何结果，在相机队列上同步回调（回调返回前像素缓冲有效）。
    var onAnalyzedFrame: ((CVPixelBuffer, FaceMetrics?, Int64) -> Void)?
    /// 眨眼对比实验页的预览用；正式测试不显示预览。
    var captureSession: AVCaptureSession { session }
    private let queue = DispatchQueue(label: "eye.camera.analysis")
    private let session = AVCaptureSession()
    private var detector: FaceLandmarker?
    private var device: AVCaptureDevice?
    private var lastTime: Int64 = 0
    private var active = false
    private var generation = 0
    private let requestLock = NSLock()
    private var requestNumber = 0
    var distanceScale: Float {
        get { let value = UserDefaults.standard.float(forKey: "eye.distanceScale"); return value > 0 ? value : 1 }
        set { UserDefaults.standard.set(newValue, forKey: "eye.distanceScale") }
    }
    func start() {
        requestLock.lock(); requestNumber += 1; let ticket = requestNumber; requestLock.unlock()
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: queue.async { self.startAuthorized(ticket) }
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                if granted { self.queue.async { self.startAuthorized(ticket) } }
                else { self.onError?("请在系统设置中允许相机权限。") }
            }
        default: onError?("相机权限未授予，请在设置中允许后重试。")
        }
    }
    private func startAuthorized(_ ticket: Int) {
        requestLock.lock(); let valid = requestNumber == ticket; requestLock.unlock()
        guard valid else { return }
        guard !active else { return }
        do {
            if detector == nil {
                guard let model = Bundle.main.path(forResource: "face_landmarker", ofType: "task") else {
                    throw NSError(domain: "EyeCamera", code: 1, userInfo: [NSLocalizedDescriptionKey: "缺少眼部检测模型"])
                }
                let options = FaceLandmarkerOptions()
                options.baseOptions.modelAssetPath = model
                options.runningMode = .video; options.numFaces = 1
                options.minFaceDetectionConfidence = 0.5; options.minFacePresenceConfidence = 0.5
                options.minTrackingConfidence = 0.5; options.outputFacialTransformationMatrixes = true
                detector = try FaceLandmarker(options: options)
            }
            if session.inputs.isEmpty {
                guard let front = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front) else {
                    throw NSError(domain: "EyeCamera", code: 2, userInfo: [NSLocalizedDescriptionKey: "没有前置摄像头"])
                }
                device = front
                session.beginConfiguration(); session.sessionPreset = .hd1280x720
                let input = try AVCaptureDeviceInput(device: front)
                guard session.canAddInput(input) else { session.commitConfiguration(); return }
                session.addInput(input)
                let output = AVCaptureVideoDataOutput()
                output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
                output.alwaysDiscardsLateVideoFrames = true; output.setSampleBufferDelegate(self, queue: queue)
                guard session.canAddOutput(output) else { session.commitConfiguration(); return }
                session.addOutput(output)
                if let connection = output.connection(with: .video) {
                    if connection.isVideoOrientationSupported { connection.videoOrientation = .portrait }
                    if connection.isVideoMirroringSupported { connection.automaticallyAdjustsVideoMirroring = false; connection.isVideoMirrored = false }
                    if connection.isCameraIntrinsicMatrixDeliverySupported { connection.isCameraIntrinsicMatrixDeliveryEnabled = true }
                }
                session.commitConfiguration()
            }
            active = true; generation += 1; lastTime = 0
            session.startRunning()
        } catch { onError?("相机启动失败：\(error.localizedDescription)") }
    }
    func stop() {
        requestLock.lock(); requestNumber += 1; requestLock.unlock()
        queue.async { self.active = false; self.generation += 1; if self.session.isRunning { self.session.stopRunning() } }
    }
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        let time = Int64(ProcessInfo.processInfo.systemUptime * 1000)
        // 约 30 帧/秒：眨眼只持续 100–150 ms，帧率过低会漏检；追随速度相关也需要足够的相邻帧。
        guard active, time - lastTime >= 30, let buffer = CMSampleBufferGetImageBuffer(sampleBuffer), let detector = detector else { return }
        lastTime = time
        do {
            let image = try MPImage(pixelBuffer: buffer, orientation: .up)
            let result = try detector.detect(videoFrame: image, timestampInMilliseconds: Int(time))
            let points = result.faceLandmarks.first ?? []
            let array = KotlinFloatArray(size: Int32(points.count * 3))
            for (i, point) in points.enumerated() {
                array.set(index: Int32(i * 3), value: point.x)
                array.set(index: Int32(i * 3 + 1), value: point.y)
                array.set(index: Int32(i * 3 + 2), value: point.z)
            }
            let matrix = result.facialTransformationMatrixes.first
            let transform = KotlinFloatArray(size: matrix == nil ? 0 : 16)
            if let matrix = matrix {
                // MediaPipe Matrix 使用行列访问；共用引擎接收列主序 4×4 矩阵。
                for row in 0..<4 { for column in 0..<4 {
                    transform.set(index: Int32(column * 4 + row), value: matrix.data[row * 4 + column])
                }}
            }
            let frame = CameraGrayImage(buffer)
            var focal: Float = 0
            if let data = CMGetAttachment(sampleBuffer, key: kCMSampleBufferAttachmentKey_CameraIntrinsicMatrix, attachmentModeOut: nil) as? Data, data.count >= 48 {
                let values = data.withUnsafeBytes { raw -> [Float] in Array(raw.bindMemory(to: Float.self)) }
                if values.count >= 12 { focal = max(values[0], values[5]) }
            }
            if focal <= 0, let device = device {
                let fov = device.activeFormat.videoFieldOfView * Float.pi / 180
                focal = Float(max(frame.width, frame.height)) / (2 * tan(fov / 2))
            }
            focal *= distanceScale
            // 关键点几何与光学测量在相机队列完成，主线程只接收结果。
            let face = points.isEmpty ? nil : LandmarkGeometry.shared.face(points: array, matrix: transform, image: frame)
            onAnalyzedFrame?(buffer, face, time)
            onFrame?(face, frame.width, frame.height, focal, time, frame.brightness())
        } catch { onError?("眼部检测失败：\(error.localizedDescription)") }
    }
}

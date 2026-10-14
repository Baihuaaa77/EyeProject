import AVFoundation
import CoreMedia
import CoreVideo
import UIKit
import EyeScreening
import MediaPipeTasksVision

/// PixelBuffer 保留到共用光学算法完成；显式使用 BGRA→RGB 灰度，不用 YUV 亮度代替。
final class CameraGrayImage: NSObject, GrayImage {
    let buffer: CVPixelBuffer
    var width: Int32 { Int32(CVPixelBufferGetWidth(buffer)) }
    var height: Int32 { Int32(CVPixelBufferGetHeight(buffer)) }
    init(_ buffer: CVPixelBuffer) { self.buffer = buffer; super.init() }
    func grayRegion(left: Int32, top: Int32, width: Int32, height: Int32) -> KotlinIntArray {
        let values = KotlinIntArray(size: width * height)
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(buffer) else { return values }
        let bytes = base.assumingMemoryBound(to: UInt8.self), stride = CVPixelBufferGetBytesPerRow(buffer)
        for y in 0..<Int(height) { for x in 0..<Int(width) {
            let i = (Int(top) + y) * stride + (Int(left) + x) * 4
            let gray = (0.2126 * Float(bytes[i + 2]) + 0.7152 * Float(bytes[i + 1]) + 0.0722 * Float(bytes[i])).rounded()
            values.set(index: Int32(y * Int(width) + x), value: Int32(gray))
        }}
        return values
    }
    func brightness() -> Float {
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(buffer) else { return 0 }
        let bytes = base.assumingMemoryBound(to: UInt8.self), stride = CVPixelBufferGetBytesPerRow(buffer)
        var total: Float = 0, count: Float = 0
        // 与安卓相同的 12×12 网格与 RGB 权重。
        for row in 0..<12 { for column in 0..<12 {
            let y = min(Int(height) - 1, Int((Float(row) + 0.5) * Float(height) / 12))
            let x = min(Int(width) - 1, Int((Float(column) + 0.5) * Float(width) / 12))
            let i = y * stride + x * 4
            total += 0.2126 * Float(bytes[i + 2]) + 0.7152 * Float(bytes[i + 1]) + 0.0722 * Float(bytes[i]); count += 1
        }}
        return count > 0 ? total / count : 0
    }
}

final class EyeCamera: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    var onFrame: ((KotlinFloatArray, KotlinFloatArray, CameraGrayImage, Float, Int64, Float) -> Void)?
    var onError: ((String) -> Void)?
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
        guard active, time - lastTime >= 66, let buffer = CMSampleBufferGetImageBuffer(sampleBuffer), let detector = detector else { return }
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
            onFrame?(array, transform, frame, focal, time, frame.brightness())
        } catch { onError?("眼部检测失败：\(error.localizedDescription)") }
    }
}

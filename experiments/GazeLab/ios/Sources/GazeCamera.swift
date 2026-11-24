import AVFoundation
import MediaPipeTasksVision
import UIKit

final class GazeCamera: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    private struct Selection {
        var methods: Set<GazeMethod> = []
        var revision = 0
        var streamResults = false
        var webCalibration: WebEyeTrackPersonalization?
        var collectWebSamples = false
    }

    var onReading: ((GazeReading, Double, Int) -> Void)?
    var onFrame: ((GazeCameraFrame, Int) -> Void)?
    var onReady: (([GazeModelIdentity], [String], WebEyeTrackHead?) -> Void)?
    var onError: ((String) -> Void)?

    private let queue = DispatchQueue(label: "gazelab.camera", qos: .userInitiated, autoreleaseFrequency: .workItem)
    private let session = AVCaptureSession()
    private var camera: AVCaptureDevice?
    private var detector: FaceLandmarker?
    private var engines: GazeInference?
    private let selectionLock = NSLock()
    private var selection = Selection()

    func select(_ methods: Set<GazeMethod>, streamResults: Bool, webCalibration: WebEyeTrackPersonalization?,
                collectWebSamples: Bool) -> Int {
        selectionLock.lock()
        defer { selectionLock.unlock() }
        selection = Selection(methods: methods, revision: selection.revision + 1, streamResults: streamResults,
                              webCalibration: webCalibration, collectWebSamples: collectWebSamples)
        return selection.revision
    }

    private func currentSelection() -> Selection {
        selectionLock.lock()
        defer { selectionLock.unlock() }
        return selection
    }

    func start() {
        queue.async { [self] in
            guard !session.isRunning else { return }
            do {
                if engines == nil { engines = GazeInference() }
                if let engines { onReady?(engines.identities, engines.issues, engines.webEyeTrackBaseHead) }
                if detector == nil { try configure() }
                session.startRunning()
                if !session.isRunning { onError?("前置相机启动失败") }
            } catch {
                onError?(error.localizedDescription)
            }
        }
    }

    func stop() {
        queue.async { [self] in
            if session.isRunning { session.stopRunning() }
        }
    }

    private func configure() throws {
        guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front),
              let path = Bundle.main.path(forResource: "face_landmarker", ofType: "task") else {
            throw NSError(domain: "GazeCamera", code: 1, userInfo: [NSLocalizedDescriptionKey: "前置相机或人脸模型不可用"])
        }
        let options = FaceLandmarkerOptions()
        options.baseOptions.modelAssetPath = path
        options.runningMode = .video
        options.numFaces = 1
        options.outputFacialTransformationMatrixes = true
        let landmarker = try FaceLandmarker(options: options)
        let input = try AVCaptureDeviceInput(device: camera)
        let output = AVCaptureVideoDataOutput()
        output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        // 串行分析繁忙时丢弃迟到帧，避免处理积压的画面。
        output.alwaysDiscardsLateVideoFrames = true
        output.setSampleBufferDelegate(self, queue: queue)

        session.beginConfiguration()
        defer { session.commitConfiguration() }
        if session.canSetSessionPreset(.hd1280x720) { session.sessionPreset = .hd1280x720 }
        guard session.canAddInput(input), session.canAddOutput(output) else {
            throw NSError(domain: "GazeCamera", code: 2, userInfo: [NSLocalizedDescriptionKey: "相机输入或输出不可用"])
        }
        session.addInput(input)
        session.addOutput(output)
        if let connection = output.connection(with: .video) {
            if connection.isVideoOrientationSupported { connection.videoOrientation = .portrait }
            if connection.isVideoMirroringSupported {
                connection.automaticallyAdjustsVideoMirroring = false
                connection.isVideoMirrored = false
            }
            if connection.isCameraIntrinsicMatrixDeliverySupported { connection.isCameraIntrinsicMatrixDeliveryEnabled = true }
        }
        self.camera = camera
        detector = landmarker
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        let now = ProcessInfo.processInfo.systemUptime
        let selection = currentSelection()
        guard !selection.methods.isEmpty, let buffer = CMSampleBufferGetImageBuffer(sampleBuffer),
              let detector, let engines else { return }
        let width = CVPixelBufferGetWidth(buffer)
        let height = CVPixelBufferGetHeight(buffer)
        var detection: FaceLandmarkerResult?
        var issue: String?
        do {
            let image = try MPImage(pixelBuffer: buffer, orientation: .up)
            detection = try detector.detect(videoFrame: image, timestampInMilliseconds: Int(now * 1000))
        } catch {
            issue = error.localizedDescription
        }

        let landmarkMs = (ProcessInfo.processInfo.systemUptime - now) * 1000
        let face = detection?.faceLandmarks.first.flatMap { GazeFace(landmarks: $0, width: width, height: height) }
        let focal: Double?
        if let data = CMGetAttachment(sampleBuffer, key: kCMSampleBufferAttachmentKey_CameraIntrinsicMatrix, attachmentModeOut: nil) as? Data,
           data.count >= 48 {
            focal = data.withUnsafeBytes { bytes in
                Double(max(bytes.loadUnaligned(fromByteOffset: 0, as: Float.self),
                           bytes.loadUnaligned(fromByteOffset: 20, as: Float.self)))
            }
        } else {
            focal = camera.map { Double(max(width, height)) / (2 * tan(Double($0.activeFormat.videoFieldOfView) * .pi / 360)) }
        }
        let poseMatrix = detection?.facialTransformationMatrixes.first.map { matrix in
            (0..<16).map { Double(matrix.data[$0]) }
        }
        let isCurrent = { self.currentSelection().revision == selection.revision }
        let publish: ((GazeReading) -> Void)? = selection.streamResults ? { reading in
            self.onReading?(reading, now, selection.revision)
        } : nil
        let readings = engines.predict(buffer: buffer, face: face, poseMatrix: poseMatrix, cameraIssue: issue,
                                      methods: selection.methods, revision: selection.revision,
                                      webCalibration: selection.webCalibration, collectWebSamples: selection.collectWebSamples,
                                      isCurrent: isCurrent, onReading: publish)
        guard isCurrent() else { return }
        let angles = poseMatrix.map { m in
            [atan2(-m[8], hypot(m[0], m[4])), atan2(m[9], m[10]), atan2(m[4], m[0])].map { $0 * 180 / .pi }
        }
        onFrame?(GazeCameraFrame(time: now, width: width, height: height,
                                faceBox: face?.boundsArray, headAnglesDegrees: angles?.allSatisfy(\.isFinite) == true ? angles : nil,
                                eyeDistancePx: face?.eyeDistance, cameraFocalPx: focal?.isFinite == true ? focal : nil, landmarkMs: landmarkMs,
                                processingMs: (ProcessInfo.processInfo.systemUptime - now) * 1000, readings: readings),
                 selection.revision)
    }
}

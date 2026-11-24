import AVFoundation
import MediaPipeTasksVision
import UIKit

final class GazeCamera: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    private struct Selection {
        var methods: Set<GazeMethod> = []
        var revision = 0
        var streamResults = false
        var mgazeCalibration: MGazeRidgeCalibration?
        var webCalibration: WebEyeTrackPersonalization?
        var webPreprocessorGeneration = 0
        var webFilterGeneration = 0
        var collectSamples = false
    }

    var onReading: ((GazeReading, Double, Int) -> Void)?
    var onFrame: ((GazeCameraFrame, Int) -> Void)?
    var onReady: (([GazeModelIdentity], [String], WebEyeTrackHead?) -> Void)?
    var onError: ((String) -> Void)?

    var captureSession: AVCaptureSession { session }

    private let queue = DispatchQueue(label: "gazelab.camera", qos: .userInitiated, autoreleaseFrequency: .workItem)
    private let session = AVCaptureSession()
    private var camera: AVCaptureDevice?
    private var detector: FaceLandmarker?
    private var engines: GazeInference?
    private var lastTimestampMs = 0
    private let selectionLock = NSLock()
    private var selection = Selection()

    func select(_ methods: Set<GazeMethod>, streamResults: Bool,
                mgazeCalibration: MGazeRidgeCalibration?,
                webCalibration: WebEyeTrackPersonalization?,
                webPreprocessorGeneration: Int,
                webFilterGeneration: Int,
                collectSamples: Bool) -> Int {
        selectionLock.lock()
        defer { selectionLock.unlock() }
        selection = Selection(methods: methods, revision: selection.revision + 1, streamResults: streamResults,
                              mgazeCalibration: mgazeCalibration, webCalibration: webCalibration,
                              webPreprocessorGeneration: webPreprocessorGeneration,
                              webFilterGeneration: webFilterGeneration,
                              collectSamples: collectSamples)
        return selection.revision
    }

    /// 仅更新 WebEyeTrack 滤波器代际（例如五点验证切点时），不中断当前正在进行的实验 revision。
    func bumpWebFilterGeneration(_ generation: Int) {
        selectionLock.lock()
        selection.webFilterGeneration = generation
        selectionLock.unlock()
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
        // MediaPipe Video 模式要求时间戳严格递增，防止相邻帧落在同一毫秒整数。
        let timestampMs = max(lastTimestampMs + 1, Int(now * 1000))
        lastTimestampMs = timestampMs
        do {
            let image = try MPImage(pixelBuffer: buffer, orientation: .up)
            detection = try detector.detect(videoFrame: image, timestampInMilliseconds: timestampMs)
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
        let latest = currentSelection()
        let readings = engines.predict(buffer: buffer, face: face, poseMatrix: poseMatrix, focalPx: focal,
                                       cameraIssue: issue, methods: selection.methods,
                                       webPreprocessorGeneration: latest.webPreprocessorGeneration,
                                       webFilterGeneration: latest.webFilterGeneration,
                                       mgazeCalibration: selection.mgazeCalibration,
                                       webCalibration: selection.webCalibration,
                                       collectSamples: selection.collectSamples,
                                       isCurrent: isCurrent, onReading: publish)
        guard isCurrent() else { return }
        let angles = poseMatrix.map { m in
            [atan2(-m[8], hypot(m[0], m[4])), atan2(m[9], m[10]), atan2(m[4], m[0])].map { $0 * 180 / .pi }
        }
        onFrame?(GazeCameraFrame(time: now, width: width, height: height,
                                 faceBox: face?.boundsArray,
                                 headAnglesDegrees: angles?.allSatisfy(\.isFinite) == true ? angles : nil,
                                 eyeDistancePx: face?.eyeDistance,
                                 cameraFocalPx: focal?.isFinite == true ? focal : nil,
                                 landmarkMs: landmarkMs,
                                 processingMs: (ProcessInfo.processInfo.systemUptime - now) * 1000,
                                 readings: readings,
                                 previewBoxes: face?.previewBoxes),
                 selection.revision)
    }
}

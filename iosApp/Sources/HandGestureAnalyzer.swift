import CoreVideo
import Dispatch
import Vision
import EyeScreening

/// 借用未镜像的竖屏前摄画面。最多留一帧，Vision 在独立队列上执行。
final class HandGestureAnalyzer {
    private let queue = DispatchQueue(label: "eye.hand.analysis", qos: .userInitiated, autoreleaseFrequency: .workItem)
    private let slot = DispatchSemaphore(value: 1)
    private let request = VNDetectHumanHandPoseRequest()
    private let onSample: (Int32, Int64) -> Void
    private var lastFrame: Int64 = 0

    init(onSample: @escaping (Int32, Int64) -> Void) {
        self.onSample = onSample
        request.maximumHandCount = 2
    }

    /// 仅由相机队列调用。被队列持有的像素缓冲在闭包结束时归还。
    func offer(_ buffer: CVPixelBuffer, time: Int64) {
        guard time - lastFrame >= 50, slot.wait(timeout: .now()) == .success else { return }
        lastFrame = time
        queue.async {
            let direction = self.direction(in: buffer)
            DispatchQueue.main.async {
                self.slot.signal()
                self.onSample(direction, time)
            }
        }
    }

    private func direction(in buffer: CVPixelBuffer) -> Int32 {
        let handler = VNImageRequestHandler(cvPixelBuffer: buffer, orientation: .up, options: [:])
        do { try handler.perform([request]) } catch { return -1 }
        let hands = request.results ?? []
        let width = Float(CVPixelBufferGetWidth(buffer))
        let height = Float(CVPixelBufferGetHeight(buffer))
        let longest = max(width, height)
        let directions = KotlinIntArray(size: Int32(hands.count))
        for (index, hand) in hands.enumerated() {
            directions.set(index: Int32(index), value: direction(of: hand, xScale: width / longest, yScale: height / longest))
        }
        return HandPointing.shared.selectDirection(hands: directions)
    }

    private func direction(of hand: VNHumanHandPoseObservation, xScale: Float, yScale: Float) -> Int32 {
        let points = KotlinFloatArray(size: Int32(Self.joints.count * 2))
        for (index, joint) in Self.joints.enumerated() {
            if let point = try? hand.recognizedPoint(joint), point.confidence >= Self.minConfidence {
                // 转成使用者视角，且将 Vision 的左下原点转为左上原点。
                points.set(index: Int32(index * 2), value: Float(1 - point.location.x) * xScale)
                points.set(index: Int32(index * 2 + 1), value: Float(1 - point.location.y) * yScale)
            } else {
                // 握拳或单指指向时，藏在掌心的其余指尖常被遮挡导致置信度偏低；标记为 NaN 由共用算法按未伸展处理。
                points.set(index: Int32(index * 2), value: Float.nan)
                points.set(index: Int32(index * 2 + 1), value: Float.nan)
            }
        }
        return HandPointing.shared.direction(points: points)
    }

    private static let minConfidence: Float = 0.25

    private static let joints: [VNHumanHandPoseObservation.JointName] = [
        .wrist,
        .thumbCMC, .thumbMP, .thumbIP, .thumbTip,
        .indexMCP, .indexPIP, .indexTip,
        .middleMCP, .middlePIP, .middleTip,
        .ringMCP, .ringPIP, .ringTip,
        .littleMCP, .littlePIP, .littleTip,
    ]
}

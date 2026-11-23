import Foundation

@main
struct GazeExperimentChecks {
    static func main() {
        let targets = [(0.1, 0.2), (0.5, 0.2), (0.9, 0.2), (0.1, 0.8), (0.5, 0.8), (0.9, 0.8)]
        let pairs = targets.map { x, y in
            (input: GazePoint(x: 2 * x + y + 7, y: x - 3 * y + 8), target: GazePoint(x: x, y: y))
        }
        let map = GazeCalibration.fit(pairs)!
        let heldOut = map.apply(GazePoint(x: 8.15, y: 6.45))
        precondition(abs(heldOut.x - 0.27) < 0.04 && abs(heldOut.y - 0.605) < 0.04)
        let degenerate = pairs.map { (GazePoint(x: $0.input.x, y: 3 * $0.input.x), $0.target) }
        precondition(GazeCalibration.fit(degenerate) == nil)
        precondition(quantile([1, 3], 0.5) == 2)

        let raw = [GazeReading(method: .mgaze, raw: pairs[0].input, latencyMs: 10, issue: nil),
                   GazeReading(method: .aff, raw: nil, latencyMs: 4, issue: "模型推理失败")]
        let frame = GazeCameraFrame(time: 1, width: 720, height: 1280, faceBox: nil, headAnglesDegrees: nil,
                                    eyeDistancePx: nil, cameraFocalPx: nil, landmarkMs: 4, processingMs: 14, readings: raw)
        let models = [GazeMethod.mgaze, .aff].map { GazeModelIdentity(method: $0, source: "测试", sha256: "", filename: "") }
        var run = GazeRun(kind: .validation, calibrationID: UUID(), widthPx: 1000, heightPx: 2000,
                          displayScale: 3, device: "测试机", osVersion: "测试系统", appBuild: "1",
                          models: models, expectedRateHz: 6, settleSeconds: 0.65, sampleSeconds: 1.8,
                          targets: [GazeTargetSamples(id: 0, expected: pairs[0].target, frames: [frame])],
                          calibrations: [.init("MGazeNet"): map, .init("AFF-Net"): map])
        run.summarize()
        precondition(run.metrics[0].valid == 1 && run.metrics[0].pairedFrames == 0)
        precondition(run.metrics[0].medianErrorPx != nil && run.metrics[1].medianErrorPx == nil)
        precondition((try? JSONEncoder().encode(run)) != nil)
        print("标定、成对验证指标和记录序列化通过")
    }
}

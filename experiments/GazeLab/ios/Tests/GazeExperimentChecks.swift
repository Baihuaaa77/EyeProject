import Foundation

@main
struct GazeExperimentChecks {
    static func main() {
        // 1. 低岭正则二维仿射标定精度校验（消除 0.04 向心收缩偏差）
        let targets = [(0.1, 0.2), (0.5, 0.2), (0.9, 0.2), (0.1, 0.8), (0.5, 0.8), (0.9, 0.8)]
        let pairs = targets.map { x, y in
            (input: GazePoint(x: 2 * x + y + 7, y: x - 3 * y + 8), target: GazePoint(x: x, y: y))
        }
        let map = GazeCalibration.fit(pairs)!
        let heldOut = map.apply(GazePoint(x: 8.15, y: 6.45))
        precondition(abs(heldOut.x - 0.27) < 0.005 && abs(heldOut.y - 0.605) < 0.005)
        let degenerate = pairs.map { (GazePoint(x: $0.input.x, y: 3 * $0.input.x), $0.target) }
        precondition(GazeCalibration.fit(degenerate) == nil)
        precondition(quantile([1, 3], 0.5) == 2)

        // 2. MGazeNet 258 维 Multivariate Ridge Calibration 校验
        var mgSamples: [(features: [Float], target: GazePoint)] = []
        let gridTargets = [(0.1, 0.16), (0.5, 0.16), (0.9, 0.16),
                           (0.1, 0.51), (0.5, 0.51), (0.9, 0.51),
                           (0.1, 0.86), (0.5, 0.86), (0.9, 0.86)]
        for (tx, ty) in gridTargets {
            for k in 0..<4 {
                var feat = Array(repeating: Float(0), count: 258)
                let jitter = Float(k - 1) * 0.001
                feat[0] = Float(tx * 4.0 - 2.0) + jitter
                feat[1] = Float(ty * 3.0 - 1.5) - jitter
                feat[10] = Float(tx * 2.0 + ty)
                feat[100] = Float(tx - ty * 2.0)
                mgSamples.append((feat, GazePoint(x: tx, y: ty)))
            }
        }
        let ridge = MGazeRidgeCalibration.fit(samples: mgSamples, targetCount: 9, alpha: 0.5)!
        var testFeat = Array(repeating: Float(0), count: 258)
        let testX = 0.28, testY = 0.72
        testFeat[0] = Float(testX * 4.0 - 2.0)
        testFeat[1] = Float(testY * 3.0 - 1.5)
        testFeat[10] = Float(testX * 2.0 + testY)
        testFeat[100] = Float(testX - testY * 2.0)
        let ridgePred = ridge.apply(testFeat)!
        precondition(abs(ridgePred.x - testX) < 0.02 && abs(ridgePred.y - testY) < 0.02)

        // 3. 验证汇总：统一未滤波未裁剪主指标 + WebEyeTrack 滤波对照 + 成对一致性 + JSON 编解码往返
        let webRaw = GazePoint(x: 0.0, y: 0.0)
        let webUnfiltered = GazePoint(x: 0.25, y: 0.25)
        let webFiltered = GazePoint(x: 0.12, y: 0.20)
        let raw = [
            GazeReading(method: .mgaze, raw: pairs[0].input, latencyMs: 10, issue: nil,
                        mgazeCalibratedPoint: GazePoint(x: 0.11, y: 0.21)),
            GazeReading(method: .aff, raw: nil, latencyMs: 4, issue: "模型推理失败"),
            GazeReading(method: .webEyeTrack, raw: webRaw, latencyMs: 8, issue: nil,
                        webEyeTrackUnfiltered: webUnfiltered, webEyeTrackPoint: webFiltered),
        ]
        let frame = GazeCameraFrame(time: 1, width: 720, height: 1280, faceBox: nil, headAnglesDegrees: nil,
                                    eyeDistancePx: nil, cameraFocalPx: nil, landmarkMs: 4, processingMs: 14, readings: raw)
        let nextFrame = GazeCameraFrame(time: 1.025, width: 720, height: 1280, faceBox: nil, headAnglesDegrees: nil,
                                        eyeDistancePx: nil, cameraFocalPx: nil, landmarkMs: 4, processingMs: 14, readings: raw)
        let models = [GazeMethod.mgaze, .aff, .webEyeTrack].map {
            GazeModelIdentity(method: $0, source: "测试", sha256: "", filename: "")
        }
        let mgazeID = UUID(), affID = UUID(), webID = UUID()
        let dummyHead = makeDummyHead()
        let webProfile = makeDummyWebProfile(head: dummyHead)
        var run = GazeRun(kind: .validation, calibrationID: nil, widthPx: 1000, heightPx: 2000,
                          displayScale: 3, device: "测试机", osVersion: "测试系统", appBuild: "9",
                          models: models, settleSeconds: 0.65, sampleSeconds: 1.8,
                          targets: [GazeTargetSamples(id: 0, expected: pairs[0].target, frames: [frame, nextFrame])],
                          calibrations: ["AFF-Net": map],
                          calibrationIDs: ["MGazeNet": mgazeID, "AFF-Net": affID, "WebEyeTrack": webID],
                          mgazeRidgeCalibration: ridge,
                          webEyeTrackCalibration: webProfile)
        run.summarize()
        precondition(abs((run.actualFrameRateHz ?? 0) - 40) < 0.001)
        precondition(run.metrics[0].valid == 2 && run.metrics[0].pairedFrames == 0)
        precondition(run.metrics[0].medianErrorPx != nil && run.metrics[1].medianErrorPx == nil)
        // WebEyeTrack 主指标使用 webEyeTrackUnfiltered (0.25, 0.25) vs (0.1, 0.2) -> hypot(150, 100) ≈ 180.28
        let expectedUnfiltered = hypot((0.25 - 0.1) * 1000, (0.25 - 0.2) * 2000)
        let expectedFiltered = hypot((0.12 - 0.1) * 1000, (0.20 - 0.2) * 2000)
        precondition(abs((run.metrics[2].medianErrorPx ?? 0) - expectedUnfiltered) < 1e-6)
        precondition(abs((run.metrics[2].filteredMedianErrorPx ?? 0) - expectedFiltered) < 1e-6)

        // JSON 编码/解码往返检查
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        let encoded = try! encoder.encode(run)
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let decodedRun = try! decoder.decode(GazeRun.self, from: encoded)
        precondition(decodedRun.mgazeRidgeCalibration?.featureCount == 258)
        precondition(decodedRun.calibrationIDs == ["MGazeNet": mgazeID, "AFF-Net": affID, "WebEyeTrack": webID])

        checkWebEyeTrack()
        checkWebEyeTrackPersonalization()
        print("标定、258D Ridge、双口径成对验证、记录往返和 WebEyeTrack 几何/预测头/Kalman 全部通过")
    }

    static func makeDummyHead() -> WebEyeTrackHead {
        func layer(_ inputCount: Int, _ outputCount: Int, _ diagonal: Int) -> WebEyeTrackHead.Layer {
            var kernel = Array(repeating: Float.zero, count: inputCount * outputCount)
            for index in 0..<diagonal { kernel[index * outputCount + index] = 1 }
            return .init(kernel: kernel, bias: Array(repeating: 0, count: outputCount))
        }
        return WebEyeTrackHead(layers: [layer(518, 16, 3), layer(16, 16, 3), layer(16, 2, 2)])
    }

    static func makeDummyWebProfile(head: WebEyeTrackHead) -> WebEyeTrackPersonalization {
        let points: [(Float, Float, Float, Double, Double)] = [
            (0.1, 0.1, 0, 0.2, 0.2), (0.6, 0.1, 0, 0.8, 0.2), (0.1, 0.6, 0, 0.2, 0.8),
            (0.6, 0.6, 0, 0.8, 0.8), (0.35, 0.35, 1, 0.65, 0.40),
        ]
        let samples = points.map { x, y, z, targetX, targetY in
            WebEyeTrackPersonalization.Sample(features: [x, y, z] + Array(repeating: 0, count: 515),
                                              target: GazePoint(x: targetX, y: targetY))
        }
        return WebEyeTrackPersonalization.fit(samples, baseHead: head)!
    }

    static func checkWebEyeTrack() {
        var points = Array(repeating: GazePoint(x: 320, y: 400), count: 478)
        points[103] = GazePoint(x: 220, y: 220)
        points[150] = GazePoint(x: 220, y: 580)
        points[379] = GazePoint(x: 420, y: 580)
        points[332] = GazePoint(x: 420, y: 220)
        points[151] = GazePoint(x: 320, y: 310)
        points[195] = GazePoint(x: 320, y: 400)
        let patch = WebEyeTrackPreprocessor.eyePatch(points: points)!
        let top = patch.source(x: 256, y: 0)!
        let end = patch.source(x: 256, y: 127)!
        precondition(abs(top.x - 320) < 0.01 && abs(top.y - 310) < 1 && abs(end.y - 400) < 4)
        points[127] = GazePoint(x: 230, y: 390)
        points[356] = GazePoint(x: 410, y: 390)
        points[471] = GazePoint(x: 269, y: 358)
        points[469] = GazePoint(x: 283, y: 358)
        points[476] = GazePoint(x: 357, y: 358)
        points[474] = GazePoint(x: 371, y: 358)
        points[33] = GazePoint(x: 245, y: 358)
        points[133] = GazePoint(x: 307, y: 358)
        points[362] = GazePoint(x: 333, y: 358)
        points[263] = GazePoint(x: 395, y: 358)
        let identity: [Double] = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]
        var web = WebEyeTrackPreprocessor()
        let head = web.geometry(points: points, faceMatrix: identity, width: 640, height: 800, focalPx: 900)!
        precondition(head.headVector == [0, 0, -1])
        precondition(abs(head.faceWidthCm - 1.2 * 180 / 14) < 1e-6)
        precondition(abs(head.faceOriginCm[0]) < 1e-6 && head.faceOriginCm[1] < 0)
        // reset() 后应能重新估算不同人脸宽度
        web.reset()
        points[356] = GazePoint(x: 430, y: 390)
        let head2 = web.geometry(points: points, faceMatrix: identity, width: 640, height: 800, focalPx: 900)!
        precondition(abs(head2.faceWidthCm - 1.2 * 200 / 14) < 1e-6)
        precondition(web.geometry(points: Array(points.prefix(468)), faceMatrix: identity, width: 640, height: 800) == nil)
    }

    static func checkWebEyeTrackPersonalization() {
        let head = makeDummyHead()
        let points: [(Float, Float, Float, Double, Double)] = [
            (0.1, 0.1, 0, 0.2, 0.2), (0.6, 0.1, 0, 0.8, 0.2), (0.1, 0.6, 0, 0.2, 0.8),
            (0.6, 0.6, 0, 0.8, 0.8), (0.35, 0.35, 1, 0.65, 0.40),
        ]
        let samples = points.map { x, y, z, targetX, targetY in
            WebEyeTrackPersonalization.Sample(features: [x, y, z] + Array(repeating: 0, count: 515),
                                              target: GazePoint(x: targetX, y: targetY))
        }
        precondition(head.valid && abs(head.predict(samples[0].features).x - 0.1) < 1e-6)
        precondition(WebEyeTrackPersonalization.fit(Array(samples.prefix(3)), baseHead: head) == nil)
        let profile = WebEyeTrackPersonalization.fit(samples, baseHead: head)!
        precondition(profile.sampleCount == 5 && profile.steps == 1)
        precondition(profile.supportMSEAfter < profile.supportMSEBefore)
        precondition(abs(profile.head.layers[2].kernel[4] - 1e-5) < 1e-7)
        precondition(abs(profile.head.layers[2].kernel[5] + 1e-5) < 1e-7)
        precondition(head.layers[2].kernel[4] == 0)

        let model = GazeModelIdentity(method: .webEyeTrack, source: "测试", sha256: "", filename: "")
        let rejected = GazeReading(method: .webEyeTrack, raw: nil, latencyMs: 0, issue: "闭眼或眼部遮挡")
        func frame(_ reading: GazeReading) -> GazeCameraFrame {
            GazeCameraFrame(time: 1, width: 720, height: 1280, faceBox: nil, headAnglesDegrees: nil,
                            eyeDistancePx: nil, cameraFocalPx: nil, landmarkMs: 1, processingMs: 2, readings: [reading])
        }
        // 每目标提供 3 帧有效样本（满足 >= 3 帧门槛）
        let rows = samples.enumerated().map { index, sample in
            let reading = GazeReading(method: .webEyeTrack, raw: head.predict(sample.features), latencyMs: 1, issue: nil,
                                      webEyeTrackFeatures: sample.features)
            return GazeTargetSamples(id: index, expected: sample.target,
                                     frames: [frame(rejected), frame(reading), frame(reading), frame(reading)])
        }
        var run = GazeRun(kind: .calibration, calibrationID: nil, widthPx: 1000, heightPx: 2000,
                          displayScale: 3, device: "测试机", osVersion: "测试系统", appBuild: "9",
                          models: [model], settleSeconds: 0.65, sampleSeconds: 1.8, targets: rows.map {
                              GazeTargetSamples(id: $0.id, expected: $0.expected, frames: [frame(rejected)])
                          })
        run.fitWebEyeTrack(baseHead: head)
        precondition(run.calibratedMethodCount == 0)
        precondition(run.webEyeTrackCalibrationIssue == "WebEyeTrack 标定失败 · 有效点 0/5 · 闭眼或眼部遮挡")
        run.targets = rows
        run.fitWebEyeTrack(baseHead: head)
        precondition(run.calibratedMethodCount == 1 && run.webEyeTrackCalibration?.sampleCount == 5)
        precondition(run.webEyeTrackCalibrationIssue == nil)

        var filter = WebEyeTrackFilter()
        let initial = GazePoint(x: 0.2, y: -0.3)
        let first = filter.step(initial)
        precondition(abs(first.x - initial.x * 2.0001 / 2.0101) < 1e-12)
        filter.reset()
        let afterReset = filter.step(initial)
        precondition(abs(afterReset.x - first.x) < 1e-12)
    }
}

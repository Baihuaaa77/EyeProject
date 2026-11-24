import Foundation

enum GazeMethod: String, CaseIterable, Codable {
    case mgaze = "MGazeNet"
    case aff = "AFF-Net"
    case itracker = "iTracker"
    case l2cs = "L2CS-Net"
    case webEyeTrack = "WebEyeTrack"

    var coordinates: String { self == .l2cs ? "yaw/pitch（度）" : "屏幕坐标（模型原始输出）" }
    var calibrationTag: String {
        switch self {
        case .mgaze: "258D Ridge"
        case .webEyeTrack: "MLP+仿射"
        default: "2D 仿射"
        }
    }
    var usesAngles: Bool { self == .l2cs }
    var inputCount: Int {
        switch self {
        case .l2cs, .webEyeTrack: 1
        default: 4
        }
    }
}

struct GazePoint: Codable, Equatable {
    let x: Double
    let y: Double

    var finite: Bool { x.isFinite && y.isFinite }
    var onScreen: Bool { (0...1).contains(x) && (0...1).contains(y) }

    static func median(_ points: [GazePoint]) -> GazePoint {
        GazePoint(x: quantile(points.map(\.x), 0.5)!, y: quantile(points.map(\.y), 0.5)!)
    }
}

func quantile(_ values: [Double], _ fraction: Double) -> Double? {
    guard !values.isEmpty else { return nil }
    let sorted = values.sorted()
    let position = Double(sorted.count - 1) * fraction
    let index = Int(position)
    return sorted[index] + (sorted[min(index + 1, sorted.count - 1)] - sorted[index]) * (position - Double(index))
}

struct GazeCalibration: Codable {
    let inputCenter: GazePoint
    let inputScale: GazePoint
    let x: [Double]
    let y: [Double]
    let targetCount: Int

    func apply(_ point: GazePoint) -> GazePoint {
        let u = (point.x - inputCenter.x) / inputScale.x
        let v = (point.y - inputCenter.y) / inputScale.y
        return GazePoint(x: x[0] * u + x[1] * v + x[2], y: y[0] * u + y[1] * v + y[2])
    }

    static func fit(_ pairs: [(input: GazePoint, target: GazePoint)], minimumTargets: Int = 6,
                    ridge: Double = 0.002) -> GazeCalibration? {
        guard pairs.count >= minimumTargets else { return nil }
        let count = Double(pairs.count)
        let center = GazePoint(x: pairs.map(\.input.x).reduce(0, +) / count,
                               y: pairs.map(\.input.y).reduce(0, +) / count)
        let scale = GazePoint(x: sqrt(pairs.map { pow($0.input.x - center.x, 2) }.reduce(0, +) / count),
                              y: sqrt(pairs.map { pow($0.input.y - center.y, 2) }.reduce(0, +) / count))
        guard min(scale.x, scale.y) > 0.0001 else { return nil }

        var system = Array(repeating: Array(repeating: 0.0, count: 5), count: 3)
        for (input, target) in pairs {
            let row = [(input.x - center.x) / scale.x, (input.y - center.y) / scale.y, 1]
            for i in 0..<3 {
                for j in 0..<3 { system[i][j] += row[i] * row[j] }
                system[i][3] += row[i] * target.x
                system[i][4] += row[i] * target.y
            }
        }
        // 拒绝输入近乎共线的标定，再用极小岭正则抑制病态放大抖动，同时避免向心收缩。
        let correlationLimit = ridge == 0 ? 1 - 1e-9 : 0.985
        guard abs(system[0][1]) / count < correlationLimit else { return nil }
        system[0][0] += ridge * count
        system[1][1] += ridge * count
        for column in 0..<3 {
            guard let pivot = (column..<3).max(by: { abs(system[$0][column]) < abs(system[$1][column]) }),
                  abs(system[pivot][column]) > 1e-8 else { return nil }
            system.swapAt(column, pivot)
            let divisor = system[column][column]
            for j in column..<5 { system[column][j] /= divisor }
            for i in 0..<3 where i != column {
                let factor = system[i][column]
                for j in column..<5 { system[i][j] -= factor * system[column][j] }
            }
        }
        return GazeCalibration(inputCenter: center, inputScale: scale, x: system.map { $0[3] },
                               y: system.map { $0[4] }, targetCount: pairs.count)
    }
}

/// GazeFollower 原生 Multivariate Ridge Calibration（对 258 维完整输出特征做岭回归，不惩罚截距）。
/// 使用对偶 Gram 矩阵求解，在样本数 N <= 120 时与原空间 (X^T X + alpha I)^-1 X^T Y 精确等价且仅需 < 1ms。
struct MGazeRidgeCalibration: Codable {
    let featureCount: Int
    let meanFeature: [Double]
    let meanTarget: GazePoint
    let weightsX: [Double]
    let weightsY: [Double]
    let alpha: Double
    let sampleCount: Int
    let targetCount: Int

    func apply(_ features: [Float]) -> GazePoint? {
        guard features.count == featureCount else { return nil }
        var gx = meanTarget.x
        var gy = meanTarget.y
        for i in 0..<featureCount {
            let d = Double(features[i]) - meanFeature[i]
            gx += d * weightsX[i]
            gy += d * weightsY[i]
        }
        let pt = GazePoint(x: gx, y: gy)
        return pt.finite ? pt : nil
    }

    static func fit(samples: [(features: [Float], target: GazePoint)], targetCount: Int,
                    alpha: Double = 2.0) -> MGazeRidgeCalibration? {
        let n = samples.count
        guard n >= 12, targetCount >= 6, let d = samples.first?.features.count, d >= 2,
              samples.allSatisfy({ $0.features.count == d && $0.features.allSatisfy(\.isFinite) && $0.target.finite })
        else { return nil }

        let nd = Double(n)
        var meanF = Array(repeating: 0.0, count: d)
        var meanTx = 0.0, meanTy = 0.0
        for sample in samples {
            meanTx += sample.target.x
            meanTy += sample.target.y
            for j in 0..<d { meanF[j] += Double(sample.features[j]) }
        }
        meanTx /= nd
        meanTy /= nd
        for j in 0..<d { meanF[j] /= nd }

        var centered = Array(repeating: Array(repeating: 0.0, count: d), count: n)
        for i in 0..<n {
            for j in 0..<d { centered[i][j] = Double(samples[i].features[j]) - meanF[j] }
        }

        // 构造增广方程组 (K + alpha * I) [cx, cy] = [yx, yy]，其中 K = centered * centered^T
        var aug = Array(repeating: Array(repeating: 0.0, count: n + 2), count: n)
        for i in 0..<n {
            for j in i..<n {
                var dot = 0.0
                for k in 0..<d { dot += centered[i][k] * centered[j][k] }
                aug[i][j] = dot
                aug[j][i] = dot
            }
            aug[i][i] += alpha
            aug[i][n] = samples[i].target.x - meanTx
            aug[i][n + 1] = samples[i].target.y - meanTy
        }

        for col in 0..<n {
            guard let pivot = (col..<n).max(by: { abs(aug[$0][col]) < abs(aug[$1][col]) }),
                  abs(aug[pivot][col]) > 1e-10 else { return nil }
            if pivot != col { aug.swapAt(col, pivot) }
            let inv = 1.0 / aug[col][col]
            for j in col..<(n + 2) { aug[col][j] *= inv }
            for i in 0..<n where i != col {
                let factor = aug[i][col]
                if factor == 0 { continue }
                for j in col..<(n + 2) { aug[i][j] -= factor * aug[col][j] }
            }
        }

        var wx = Array(repeating: 0.0, count: d)
        var wy = Array(repeating: 0.0, count: d)
        for i in 0..<n {
            let cx = aug[i][n], cy = aug[i][n + 1]
            for j in 0..<d {
                wx[j] += centered[i][j] * cx
                wy[j] += centered[i][j] * cy
            }
        }
        guard wx.allSatisfy(\.isFinite), wy.allSatisfy(\.isFinite) else { return nil }
        return MGazeRidgeCalibration(featureCount: d, meanFeature: meanF,
                                     meanTarget: GazePoint(x: meanTx, y: meanTy),
                                     weightsX: wx, weightsY: wy, alpha: alpha,
                                     sampleCount: n, targetCount: targetCount)
    }
}

struct GazeReading: Codable {
    let method: GazeMethod
    let raw: GazePoint?
    let latencyMs: Double
    let issue: String?
    var webEyeTrack: WebEyeTrackGeometry? = nil
    var webEyeTrackUnfiltered: GazePoint? = nil
    var webEyeTrackPoint: GazePoint? = nil
    var mgazeCalibratedPoint: GazePoint? = nil
    // 标定用高维特征仅保留在内存中，不写入 JSON。
    var webEyeTrackFeatures: [Float]? = nil
    var mgazeFeatures: [Float]? = nil

    private enum CodingKeys: String, CodingKey {
        case method, raw, latencyMs, issue, webEyeTrack, webEyeTrackUnfiltered, webEyeTrackPoint, mgazeCalibratedPoint
    }
}

struct GazeCameraFrame: Codable {
    let time: Double
    let width: Int
    let height: Int
    let faceBox: [Double]?
    let headAnglesDegrees: [Double]?
    let eyeDistancePx: Double?
    let cameraFocalPx: Double?
    let landmarkMs: Double
    let processingMs: Double
    let readings: [GazeReading]
    var poseDrifted: Bool? = nil
    // 仅内存用于相机小窗叠加显示（归一化到 [0, 1]）。
    var previewBoxes: [[Double]]? = nil

    private enum CodingKeys: String, CodingKey {
        case time, width, height, faceBox, headAnglesDegrees, eyeDistancePx, cameraFocalPx,
             landmarkMs, processingMs, readings, poseDrifted
    }
}

struct GazeModelIdentity: Codable {
    struct Asset: Codable {
        let filename: String
        let sha256: String
    }

    let method: GazeMethod
    let source: String
    let sha256: String
    let filename: String
    var webEyeTrackHead: Asset? = nil
}

struct GazeTargetSamples: Codable, Identifiable {
    let id: Int
    let expected: GazePoint
    var onset: Double?
    var frames: [GazeCameraFrame] = []
}

struct GazeMethodMetrics: Codable, Identifiable {
    var id: GazeMethod { method }
    let method: GazeMethod
    let valid: Int
    let total: Int
    let inScreen: Int
    let validTargets: Int
    let meanErrorPx: Double?
    let medianErrorPx: Double?
    let p90ErrorPx: Double?
    let pairedMedianErrorPx: Double?
    let pairedFrames: Int
    let medianFixationJitterPx: Double?
    let medianInferenceMs: Double?
    let p90InferenceMs: Double?
    /// 含原生滤波/限幅的后处理对照指标（如 WebEyeTrack 的 Kalman + 裁剪）。
    var filteredMedianErrorPx: Double? = nil
    var filteredP90ErrorPx: Double? = nil
    var filteredFixationJitterPx: Double? = nil
}

struct GazeRun: Codable, Identifiable {
    enum Kind: String, Codable { case calibration, validation }
    var id = UUID()
    var started = Date()
    let kind: Kind
    let calibrationID: UUID?
    let widthPx: Double
    let heightPx: Double
    let displayScale: Double
    let device: String
    let osVersion: String
    let appBuild: String
    let models: [GazeModelIdentity]
    let settleSeconds: Double
    let sampleSeconds: Double
    var calibrationMethod = "MGazeNet 使用 258 维 Multivariate Ridge；AFF-Net/iTracker/L2CS-Net 使用逐目标中位数二维岭回归 0.002；WebEyeTrack 使用代表帧仿射 0.002 与 Adam 预测头个性化；验证主指标统一采用未滤波未裁剪坐标"
    var targets: [GazeTargetSamples]
    var calibrations: [String: GazeCalibration] = [:]
    var calibrationIDs: [String: UUID] = [:]
    var mgazeRidgeCalibration: MGazeRidgeCalibration? = nil
    var webEyeTrackCalibration: WebEyeTrackPersonalization? = nil
    var webEyeTrackCalibrationIssue: String? = nil
    var metrics: [GazeMethodMetrics] = []
    var finished: Date?
    var status: String?
    var actualFrameRateHz: Double?

    var diagonalPx: Double { hypot(widthPx, heightPx) }

    var calibratedMethodCount: Int {
        var set = Set(calibrations.keys)
        if mgazeRidgeCalibration != nil { set.insert(GazeMethod.mgaze.rawValue) }
        if webEyeTrackCalibration != nil { set.insert(GazeMethod.webEyeTrack.rawValue) }
        return set.count
    }

    /// 优先取头姿未漂移帧；若未漂移帧数量不足 minCount，则回退到该目标全部帧。
    private static func preferredFrames(_ frames: [GazeCameraFrame], minCount: Int) -> [GazeCameraFrame] {
        let stable = frames.filter { $0.poseDrifted != true }
        return stable.count >= minCount ? stable : frames
    }

    mutating func fitMGazeRidge() {
        var collected: [(features: [Float], target: GazePoint)] = []
        var validTargetCount = 0
        let maxPerTarget = 12
        for target in targets {
            let pool = Self.preferredFrames(target.frames, minCount: 3)
            let feats = pool.compactMap { frame -> [Float]? in
                frame.readings.first(where: { $0.method == .mgaze })?.mgazeFeatures
            }
            guard feats.count >= 3 else { continue }
            validTargetCount += 1
            if feats.count <= maxPerTarget {
                for f in feats { collected.append((f, target.expected)) }
            } else {
                // 均匀抽样，防止个别高帧率点主导拟合。
                for k in 0..<maxPerTarget {
                    let idx = k * (feats.count - 1) / (maxPerTarget - 1)
                    collected.append((feats[idx], target.expected))
                }
            }
        }
        mgazeRidgeCalibration = MGazeRidgeCalibration.fit(samples: collected, targetCount: validTargetCount, alpha: 2.0)
    }

    mutating func fitWebEyeTrack(baseHead: WebEyeTrackHead?) {
        let readings = targets.map { target in
            Self.preferredFrames(target.frames, minCount: 3)
                .compactMap { $0.readings.first { $0.method == .webEyeTrack } }
        }
        let samples = zip(targets, readings).compactMap { target, readings -> WebEyeTrackPersonalization.Sample? in
            let candidates = readings.compactMap { reading -> (GazePoint, [Float])? in
                guard let raw = reading.raw, let features = reading.webEyeTrackFeatures else { return nil }
                return (raw, features)
            }
            // 至少需要 3 帧（若全目标均不足 3 帧，兜底允许 >= 2 帧）以避免选入单帧扫视/眨眼噪声。
            guard candidates.count >= 3 else { return nil }
            let median = GazePoint.median(candidates.map(\.0))
            guard let representative = candidates.min(by: {
                hypot($0.0.x - median.x, $0.0.y - median.y) < hypot($1.0.x - median.x, $1.0.y - median.y)
            }) else { return nil }
            return .init(features: representative.1, target: target.expected)
        }
        webEyeTrackCalibration = baseHead.flatMap { WebEyeTrackPersonalization.fit(samples, baseHead: $0) }
        guard webEyeTrackCalibration == nil else {
            webEyeTrackCalibrationIssue = nil
            return
        }

        let reason: String
        if baseHead == nil {
            reason = "预测头未加载"
        } else if samples.count < 4 {
            let allReadings = targets.flatMap { target in
                target.frames.compactMap { $0.readings.first { $0.method == .webEyeTrack } }
            }
            let issues = allReadings.compactMap(\.issue)
            let counts = Dictionary(grouping: issues, by: { $0 }).mapValues(\.count)
            let mostCommon = counts.keys.sorted().max { counts[$0, default: 0] < counts[$1, default: 0] }
            reason = mostCommon ?? (allReadings.isEmpty ? "未收到预测帧" : "单点有效帧不足")
        } else {
            reason = "仿射或预测头拟合失败"
        }
        webEyeTrackCalibrationIssue = "WebEyeTrack 标定失败 · 有效点 \(samples.count)/\(targets.count) · \(reason)"
    }

    func isMethodCalibrated(_ method: GazeMethod) -> Bool {
        switch method {
        case .webEyeTrack: webEyeTrackCalibration != nil
        case .mgaze: mgazeRidgeCalibration != nil || calibrations[method.rawValue] != nil
        default: calibrations[method.rawValue] != nil
        }
    }

    /// 统一返回各模型在验证时的“未滤波、未裁剪”预测坐标（用于公平对比）。
    func unfilteredPoint(for reading: GazeReading) -> GazePoint? {
        switch reading.method {
        case .webEyeTrack:
            return reading.webEyeTrackUnfiltered
        case .mgaze:
            if let pt = reading.mgazeCalibratedPoint { return pt }
            if let ridge = mgazeRidgeCalibration, let feats = reading.mgazeFeatures { return ridge.apply(feats) }
            return reading.raw.flatMap { calibrations[GazeMethod.mgaze.rawValue]?.apply($0) }
        default:
            return reading.raw.flatMap { calibrations[reading.method.rawValue]?.apply($0) }
        }
    }

    mutating func summarize() {
        let frames = targets.flatMap(\.frames)
        if frames.count > 1, let first = frames.first, let last = frames.last, last.time > first.time {
            let intervals = targets.flatMap { target in
                zip(target.frames, target.frames.dropFirst()).map { $0.1.time - $0.0.time }
            }.filter { $0 > 0 }
            if let median = quantile(intervals, 0.5), median > 0 { actualFrameRateHz = 1 / median }
        }
        let calibrated = models.map(\.method).filter { isMethodCalibrated($0) }
        let calibratedSet = Set(calibrated)
        metrics = models.map(\.method).map { method in
            let readings = frames.flatMap(\.readings).filter { $0.method == method }
            let latencies = readings.filter { $0.raw != nil }.map(\.latencyMs)
            var errors: [Double] = []
            var pairedErrors: [Double] = []
            var jitter: [Double] = []
            var filteredErrors: [Double] = []
            var filteredJitter: [Double] = []
            var inScreen = 0
            var validTargets = 0
            if kind == .validation, calibratedSet.contains(method) {
                for target in targets {
                    var points: [GazePoint] = []
                    var filteredPoints: [GazePoint] = []
                    for frame in target.frames {
                        guard let reading = frame.readings.first(where: { $0.method == method }) else { continue }
                        guard let point = unfilteredPoint(for: reading), point.finite else { continue }
                        points.append(point)
                        let error = distance(point, target.expected)
                        errors.append(error)
                        if point.onScreen { inScreen += 1 }
                        if method == .webEyeTrack, let fp = reading.webEyeTrackPoint, fp.finite {
                            filteredPoints.append(fp)
                            filteredErrors.append(distance(fp, target.expected))
                        }
                        // 与主误差口径严格一致：判断该帧所有已标定模型均产生了有限的未滤波坐标。
                        let validMethods = Set(frame.readings.compactMap { r -> GazeMethod? in
                            guard calibratedSet.contains(r.method),
                                  let p = unfilteredPoint(for: r), p.finite else { return nil }
                            return r.method
                        })
                        if calibratedSet.count >= 2 && calibratedSet.isSubset(of: validMethods) {
                            pairedErrors.append(error)
                        }
                    }
                    if !points.isEmpty {
                        validTargets += 1
                        let center = GazePoint.median(points)
                        if points.count >= 2, let value = quantile(points.map { distance($0, center) }, 0.5) {
                            jitter.append(value)
                        }
                    }
                    if filteredPoints.count >= 2 {
                        let center = GazePoint.median(filteredPoints)
                        if let value = quantile(filteredPoints.map { distance($0, center) }, 0.5) {
                            filteredJitter.append(value)
                        }
                    }
                }
            }
            return GazeMethodMetrics(
                method: method, valid: readings.filter { $0.raw != nil }.count, total: frames.count,
                inScreen: inScreen, validTargets: validTargets,
                meanErrorPx: errors.isEmpty ? nil : errors.reduce(0, +) / Double(errors.count),
                medianErrorPx: quantile(errors, 0.5), p90ErrorPx: quantile(errors, 0.9),
                pairedMedianErrorPx: quantile(pairedErrors, 0.5), pairedFrames: pairedErrors.count,
                medianFixationJitterPx: quantile(jitter, 0.5), medianInferenceMs: quantile(latencies, 0.5),
                p90InferenceMs: quantile(latencies, 0.9),
                filteredMedianErrorPx: quantile(filteredErrors, 0.5),
                filteredP90ErrorPx: quantile(filteredErrors, 0.9),
                filteredFixationJitterPx: quantile(filteredJitter, 0.5)
            )
        }
    }

    func distance(_ a: GazePoint, _ b: GazePoint) -> Double {
        hypot((a.x - b.x) * widthPx, (a.y - b.y) * heightPx)
    }
}

enum GazeArchive {
    static let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("注视实验记录", isDirectory: true)

    static func save(_ run: GazeRun) throws -> URL {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let prefix = run.kind == .calibration ? "标定" : "验证"
        let url = directory.appendingPathComponent("\(prefix)-\(run.id.uuidString).json")
        let json = JSONEncoder()
        json.outputFormatting = [.prettyPrinted, .sortedKeys]
        json.dateEncodingStrategy = .iso8601
        try json.encode(run).write(to: url, options: .atomic)
        return url
    }

    static func load(_ url: URL) -> GazeRun? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try? decoder.decode(GazeRun.self, from: data)
    }

    static func delete(_ url: URL) {
        try? FileManager.default.removeItem(at: url)
    }

    static func records() -> [URL] {
        let urls = (try? FileManager.default.contentsOfDirectory(at: directory,
                                      includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
        return urls.filter { $0.pathExtension == "json" }.sorted {
            let left = (try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
            let right = (try? $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
            return left > right
        }
    }
}

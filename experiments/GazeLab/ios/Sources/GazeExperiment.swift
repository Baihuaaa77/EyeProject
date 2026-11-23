import Foundation

enum GazeMethod: String, CaseIterable, Codable {
    case mgaze = "MGazeNet"
    case aff = "AFF-Net"
    case itracker = "iTracker"
    case l2cs = "L2CS-Net"
    case webEyeTrack = "WebEyeTrack"

    var coordinates: String { self == .l2cs ? "yaw/pitch（度）" : "屏幕坐标（模型原始输出）" }
    var usesAngles: Bool { self == .l2cs }
    var inputCount: Int {
        switch self {
        case .l2cs: 1
        case .webEyeTrack: 3
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

    static func fit(_ pairs: [(input: GazePoint, target: GazePoint)]) -> GazeCalibration? {
        guard pairs.count >= 6 else { return nil }
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
        // 拒绝输入近乎共线的标定，再用小幅岭正则抑制放大抖动。
        guard abs(system[0][1]) / count < 0.98 else { return nil }
        system[0][0] += 0.04 * count
        system[1][1] += 0.04 * count
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

struct GazeReading: Codable {
    let method: GazeMethod
    let raw: GazePoint?
    let latencyMs: Double
    let issue: String?
    var webEyeTrack: WebEyeTrackGeometry? = nil
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
}

struct GazeModelIdentity: Codable {
    let method: GazeMethod
    let source: String
    let sha256: String
    let filename: String
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
}

struct GazeRun: Encodable, Identifiable {
    enum Kind: String, Codable { case calibration, validation }
    let id = UUID()
    let started = Date()
    let kind: Kind
    let calibrationID: UUID?
    let widthPx: Double
    let heightPx: Double
    let displayScale: Double
    let device: String
    let osVersion: String
    let appBuild: String
    let models: [GazeModelIdentity]
    let expectedRateHz: Double
    let settleSeconds: Double
    let sampleSeconds: Double
    let calibrationMethod = "逐目标中位数、标准化二维岭回归，正则 0.04；L2CS 使用角度到屏幕的经验映射"
    var targets: [GazeTargetSamples]
    var calibrations: [String: GazeCalibration] = [:]
    var metrics: [GazeMethodMetrics] = []
    var finished: Date?
    var status: String?
    var actualFrameRateHz: Double?

    mutating func summarize() {
        let frames = targets.flatMap(\.frames)
        if frames.count > 1, let first = frames.first, let last = frames.last, last.time > first.time {
            let intervals = targets.flatMap { target in
                zip(target.frames, target.frames.dropFirst()).map { $0.1.time - $0.0.time }
            }
            if let median = quantile(intervals, 0.5), median > 0 { actualFrameRateHz = 1 / median }
        }
        let calibrated = models.map(\.method).filter { calibrations[$0.rawValue] != nil }
        let calibratedSet = Set(calibrated)
        metrics = models.map(\.method).map { method in
            let readings = frames.flatMap(\.readings).filter { $0.method == method }
            let latencies = readings.filter { $0.raw != nil }.map(\.latencyMs)
            var errors: [Double] = []
            var pairedErrors: [Double] = []
            var jitter: [Double] = []
            var inScreen = 0
            var validTargets = 0
            if kind == .validation, let mapping = calibrations[method.rawValue] {
                for target in targets {
                    var points: [GazePoint] = []
                    for frame in target.frames {
                        guard let raw = frame.readings.first(where: { $0.method == method })?.raw else { continue }
                        let point = mapping.apply(raw)
                        guard point.finite else { continue }
                        points.append(point)
                        let error = distance(point, target.expected)
                        errors.append(error)
                        if point.onScreen { inScreen += 1 }
                        let validMethods = Set(frame.readings.filter { $0.raw != nil }.map(\.method))
                        if calibratedSet.count >= 2 && calibratedSet.isSubset(of: validMethods) { pairedErrors.append(error) }
                    }
                    if !points.isEmpty {
                        validTargets += 1
                        let center = GazePoint.median(points)
                        if points.count >= 2, let value = quantile(points.map { distance($0, center) }, 0.5) { jitter.append(value) }
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
                p90InferenceMs: quantile(latencies, 0.9)
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

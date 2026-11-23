import CoreVideo
import CryptoKit
import Foundation
import MediaPipeTasksVision

struct GazeFace {
    let bounds: CGRect
    let mgazeBounds: CGRect
    let left: CGRect
    let right: CGRect
    let openEyes: Bool
    let eyeDistance: Double
    var boundsArray: [Double] { [bounds.minX, bounds.minY, bounds.maxX, bounds.maxY].map(Double.init) }

    init?(landmarks: [NormalizedLandmark], width: Int, height: Int) {
        guard landmarks.count >= 468 else { return nil }
        let w = CGFloat(width), h = CGFloat(height)
        let points = landmarks.prefix(468).map { CGPoint(x: CGFloat($0.x) * w, y: CGFloat($0.y) * h) }
        guard points.allSatisfy({ $0.x.isFinite && $0.y.isFinite }) else { return nil }
        let x = points.map(\.x), y = points.map(\.y)
        let image = CGRect(x: 0, y: 0, width: w, height: h)
        let raw = CGRect(x: x.min()!, y: y.min()!, width: x.max()! - x.min()!, height: y.max()! - y.min()!)
        guard raw.width >= 40, raw.height >= 40 else { return nil }
        bounds = raw.intersection(image)
        let delta = (raw.width - raw.height) / 4
        mgazeBounds = raw.insetBy(dx: delta, dy: -delta).intersection(image)
        let padding = abs(points[362].x - points[133].x) * 0.2
        func eye(_ a: Int, _ b: Int) -> CGRect {
            let a = points[a], b = points[b]
            let width = abs(a.x - b.x) + 2 * padding
            let height = width * 0.75
            return CGRect(x: min(a.x, b.x) - padding, y: (a.y + b.y) / 2 - height * 0.6,
                          width: width, height: height)
        }
        left = eye(33, 133)
        right = eye(362, 263)
        eyeDistance = Double(hypot(left.midX - right.midX, left.midY - right.midY))
        func ratio(_ a: Int, _ b: Int, _ c: Int, _ d: Int) -> CGFloat {
            let height = hypot(points[a].x - points[b].x, points[a].y - points[b].y)
            let width = hypot(points[c].x - points[d].x, points[c].y - points[d].y)
            return height / max(width, 1)
        }
        openEyes = ratio(159, 145, 33, 133) >= 0.13 && ratio(386, 374, 362, 263) >= 0.13
    }
}

final class GazeInference {
    private struct Manifest: Decodable {
        let models: [Entry]
    }
    private struct Entry: Decodable {
        let identity: GazeModelIdentity
        let outputNames: [String]
        let inputNames: [String]
    }
    private struct Loaded {
        let entry: Entry
        let model: GazeMNN
    }

    private(set) var identities: [GazeModelIdentity] = []
    private(set) var issues: [String] = []
    private var loaded: [Loaded] = []

    init() {
        guard let folder = Bundle.main.resourceURL?.appendingPathComponent("Models"),
              let data = try? Data(contentsOf: folder.appendingPathComponent("manifest.json")),
              let manifest = try? JSONDecoder().decode(Manifest.self, from: data) else {
            issues = ["安装包缺少实验模型"]
            return
        }
        for entry in manifest.models {
            let id = entry.identity
            let url = folder.appendingPathComponent(id.filename)
            let inputCount = id.method == .l2cs ? 1 : 4
            let outputCount = id.method == .l2cs ? 2 : 1
            guard entry.inputNames.count == inputCount, Set(entry.inputNames).count == inputCount,
                  entry.outputNames.count == outputCount,
                  let bytes = try? Data(contentsOf: url, options: .mappedIfSafe),
                  SHA256.hash(data: bytes).map({ String(format: "%02x", $0) }).joined() == id.sha256,
                  let model = GazeMNN(modelPath: url.path) else {
                issues.append("\(id.method.rawValue) 加载失败")
                continue
            }
            loaded.append(Loaded(entry: entry, model: model))
            identities.append(id)
        }
    }

    func predict(buffer: CVPixelBuffer, face: GazeFace?, cameraIssue: String?) -> [GazeReading] {
        let lock = CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { if lock == kCVReturnSuccess { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) } }
        guard lock == kCVReturnSuccess, let pixels = CVPixelBufferGetBaseAddress(buffer),
              let face, face.openEyes else {
            let reason = cameraIssue != nil ? "人脸检测异常" : face == nil ? "未检测到人脸" : !face!.openEyes ? "闭眼或眼部遮挡" : "相机缓冲不可读"
            return identities.map { GazeReading(method: $0.method, raw: nil, latencyMs: 0, issue: reason) }
        }

        let image = GazeImage(
            pixels: pixels.assumingMemoryBound(to: UInt8.self), stride: CVPixelBufferGetBytesPerRow(buffer),
            width: CVPixelBufferGetWidth(buffer), height: CVPixelBufferGetHeight(buffer)
        )
        var face224: Data?
        return loaded.map { loaded in
            let start = ProcessInfo.processInfo.systemUptime
            let entry = loaded.entry
            let method = entry.identity.method
            let values: [Data]?
            switch method {
            case .mgaze:
                if image.contains(face.left), image.contains(face.right) {
                    values = [
                        image.crop(face.mgazeBounds, size: 224, planar: false),
                        image.crop(face.left, size: 112, planar: false),
                        image.crop(face.right, size: 112, planar: false, flip: true),
                        image.ranges([face.mgazeBounds, face.left, face.right])
                    ]
                } else { values = nil }
            case .aff, .itracker:
                if image.contains(face.left), image.contains(face.right) {
                    let size = method == .aff ? 112 : 224
                    let rightFlip = method == .aff
                    let cached = face224 ?? image.crop(image.square(face.bounds, expand: 1), size: 224, planar: true)
                    face224 = cached
                    values = [
                        image.crop(image.square(face.left, expand: 1.2), size: size, planar: true),
                        image.crop(image.square(face.right, expand: 1.2), size: size, planar: true, flip: rightFlip),
                        cached,
                        method == .aff ? image.corners([face.bounds, face.left, face.right]) : image.grid(face.bounds)
                    ]
                } else { values = nil }
            case .l2cs:
                values = [image.crop(face.bounds, size: 448, planar: true, rgbImageNet: true)]
            }

            var prediction: GazePoint?
            if let values {
                let inputs = Dictionary(uniqueKeysWithValues: zip(entry.inputNames, values))
                if let output = loaded.model.predict(inputs, output: entry.outputNames[0]) {
                    let a = Self.floats(output)
                    if method.usesAngles {
                        if let yaw = Self.angle(a), let second = loaded.model.output(entry.outputNames[1]),
                           let pitch = Self.angle(Self.floats(second)) {
                            prediction = GazePoint(x: yaw, y: pitch)
                        }
                    } else if a.count >= 2 {
                        prediction = GazePoint(x: Double(a[0]), y: Double(a[1]))
                    }
                }
            }
            if prediction?.finite != true { prediction = nil }
            return GazeReading(method: method, raw: prediction,
                               latencyMs: (ProcessInfo.processInfo.systemUptime - start) * 1000,
                               issue: prediction == nil ? (values == nil ? "眼部裁剪超出画面" : "模型推理失败") : nil)
        }
    }

    private static func floats(_ data: Data) -> [Float] {
        data.withUnsafeBytes { Array($0.bindMemory(to: Float.self)) }
    }

    private static func angle(_ bins: [Float]) -> Double? {
        guard bins.count == 90, bins.allSatisfy(\.isFinite) else { return nil }
        return zip(bins.indices, bins).reduce(0.0) { $0 + Double($1.0) * Double($1.1) } * 4 - 180
    }
}

private struct GazeImage {
    let pixels: UnsafePointer<UInt8>
    let stride: Int
    let width: Int
    let height: Int

    private var bounds: CGRect { CGRect(x: 0, y: 0, width: CGFloat(width), height: CGFloat(height)) }

    func contains(_ area: CGRect) -> Bool { bounds.contains(area) && area.width > 4 && area.height > 4 }

    func square(_ area: CGRect, expand: CGFloat) -> CGRect {
        let side = max(area.width, area.height) * expand
        return CGRect(x: area.midX - side / 2, y: area.midY - side / 2, width: side, height: side).intersection(bounds)
    }

    func ranges(_ boxes: [CGRect]) -> Data {
        let w = CGFloat(width), h = CGFloat(height)
        return Self.data(boxes.flatMap { [Float($0.width / w), Float($0.height / h), Float($0.minX / w), Float($0.minY / h)] })
    }

    func corners(_ boxes: [CGRect]) -> Data {
        Self.data(boxes.flatMap { [Float($0.minX), Float($0.minY), Float($0.maxX), Float($0.maxY)] })
    }

    func grid(_ box: CGRect) -> Data {
        let values: [Float] = (0..<625).map { index in
            box.contains(CGPoint(x: CGFloat(index % 25 * width / 25), y: CGFloat(index / 25 * height / 25))) ? 1 : 0
        }
        return Self.data(values)
    }

    func crop(_ area: CGRect, size: Int, planar: Bool, flip: Bool = false, rgbImageNet: Bool = false) -> Data {
        let rect = area.integral.intersection(bounds)
        let mean: [Float] = rgbImageNet ? [0.485, 0.456, 0.406] : [0, 0, 0]
        let std: [Float] = rgbImageNet ? [0.229, 0.224, 0.225] : [1, 1, 1]
        let count = size * size
        var values = [Float](repeating: 0, count: count * 3)
        let x0 = Int(rect.minX), y0 = Int(rect.minY)
        let cropWidth = Int(rect.width), cropHeight = Int(rect.height)
        guard cropWidth > 0, cropHeight > 0 else { return Self.data(values) }
        func sample(_ index: Int, sourceSize: Int, origin: Int) -> (Int, Int, Float) {
            let position = max(0, min(Float(sourceSize - 1), (Float(index) + 0.5) * Float(sourceSize) / Float(size) - 0.5))
            let first = Int(position)
            return (origin + first, origin + min(first + 1, sourceSize - 1), position - Float(first))
        }
        let xs = (0..<size).map { sample(flip ? size - 1 - $0 : $0, sourceSize: cropWidth, origin: x0) }
        for row in 0..<size {
            let (top, bottom, dy) = sample(row, sourceSize: cropHeight, origin: y0)
            for col in 0..<size {
                let (left, right, dx) = xs[col]
                let index = row * size + col
                for channel in 0..<3 {
                    let c = rgbImageNet ? 2 - channel : channel
                    let upper = Float(pixels[top * stride + left * 4 + c]) * (1 - dx)
                              + Float(pixels[top * stride + right * 4 + c]) * dx
                    let lower = Float(pixels[bottom * stride + left * 4 + c]) * (1 - dx)
                              + Float(pixels[bottom * stride + right * 4 + c]) * dx
                    let value = (upper * (1 - dy) + lower * dy) / 255
                    values[planar ? channel * count + index : index * 3 + channel] = (value - mean[channel]) / std[channel]
                }
            }
        }
        return Self.data(values)
    }

    private static func data(_ values: [Float]) -> Data { values.withUnsafeBytes { Data($0) } }
}

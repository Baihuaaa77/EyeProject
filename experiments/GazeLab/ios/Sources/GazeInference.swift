import CoreVideo
import CryptoKit
import Foundation
import MediaPipeTasksVision

struct GazeFace {
    let bounds: CGRect
    let mgazeBounds: CGRect
    let l2csBounds: CGRect
    let left: CGRect
    let right: CGRect
    let affLeft: CGRect
    let affRight: CGRect
    let openEyes: Bool
    let eyeDistance: Double
    let landmarksPx: [GazePoint]
    let previewBoxes: [[Double]]
    var boundsArray: [Double] { [bounds.minX, bounds.minY, bounds.maxX, bounds.maxY].map(Double.init) }

    init?(landmarks: [NormalizedLandmark], width: Int, height: Int) {
        guard landmarks.count >= 468 else { return nil }
        let w = CGFloat(width), h = CGFloat(height)
        let points = landmarks.prefix(468).map { CGPoint(x: CGFloat($0.x) * w, y: CGFloat($0.y) * h) }
        guard points.allSatisfy({ $0.x.isFinite && $0.y.isFinite }) else { return nil }
        let all = landmarks.prefix(478).map { GazePoint(x: Double($0.x) * Double(width), y: Double($0.y) * Double(height)) }
        landmarksPx = all.allSatisfy(\.finite) ? all : []
        let x = points.map(\.x), y = points.map(\.y)
        let image = CGRect(x: 0, y: 0, width: w, height: h)
        let raw = CGRect(x: x.min()!, y: y.min()!, width: x.max()! - x.min()!, height: y.max()! - y.min()!)
        guard raw.width >= 40, raw.height >= 40 else { return nil }
        bounds = raw.intersection(image)

        // MGazeNet (GazeFollower) 正方形化人脸框公式。
        let delta = (raw.width - raw.height) / 4
        mgazeBounds = raw.insetBy(dx: delta, dy: -delta).intersection(image)

        // L2CS-Net (RetinaFace 风格) 适度扩展额头与边界上下文，减轻 468 点紧密框非等比拉伸形变。
        l2csBounds = CGRect(
            x: raw.minX - raw.width * 0.08,
            y: raw.minY - raw.height * 0.22,
            width: raw.width * 1.16,
            height: raw.height * 1.30
        ).intersection(image)

        // GazeFollower 眼框（用于 MGazeNet）。
        let padding = abs(points[362].x - points[133].x) * 0.2
        func mgazeEye(_ a: Int, _ b: Int) -> CGRect {
            let a = points[a], b = points[b]
            let width = abs(a.x - b.x) + 2 * padding
            let height = width * 0.75
            return CGRect(x: min(a.x, b.x) - padding, y: (a.y + b.y) / 2 - height * 0.6,
                          width: width, height: height)
        }
        left = mgazeEye(33, 133)
        right = mgazeEye(362, 263)

        // MobilePoG (AFF-Net / iTracker) 紧致眼框：围绕眼轮廓极值加小幅对称边距，再由 crop 统一做 1.2x square。
        func compactEye(_ indices: [Int]) -> CGRect {
            let xs = indices.map { points[$0].x }
            let ys = indices.map { points[$0].y }
            let minX = xs.min()!, maxX = xs.max()!
            let minY = ys.min()!, maxY = ys.max()!
            let ew = max(maxX - minX, 4)
            let padX = ew * 0.12
            let padY = ew * 0.28
            let midY = (minY + maxY) / 2
            let eh = max(maxY - minY, ew * 0.35) + 2 * padY
            return CGRect(x: minX - padX, y: midY - eh / 2, width: ew + 2 * padX, height: eh)
        }
        affLeft = compactEye([33, 133, 159, 145, 160, 158, 153, 144])
        affRight = compactEye([362, 263, 386, 374, 385, 387, 373, 380])

        eyeDistance = Double(hypot(left.midX - right.midX, left.midY - right.midY))
        func ratio(_ a: Int, _ b: Int, _ c: Int, _ d: Int) -> CGFloat {
            let height = hypot(points[a].x - points[b].x, points[a].y - points[b].y)
            let width = hypot(points[c].x - points[d].x, points[c].y - points[d].y)
            return height / max(width, 1)
        }
        openEyes = ratio(159, 145, 33, 133) >= 0.13 && ratio(386, 374, 362, 263) >= 0.13

        let dw = Double(width), dh = Double(height)
        previewBoxes = [bounds, left, right].map {
            [Double($0.minX) / dw, Double($0.minY) / dh, Double($0.width) / dw, Double($0.height) / dh]
        }
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
    private(set) var webEyeTrackBaseHead: WebEyeTrackHead?
    private var loaded: [Loaded] = []
    private var webEyeTrack = WebEyeTrackPreprocessor()
    private var webEyeTrackFilter = WebEyeTrackFilter()
    private var webPreprocessorGen = -1
    private var webFilterGen = -1

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
            let inputCount = id.method.inputCount
            let outputCount = id.method == .l2cs ? 2 : 1
            guard entry.inputNames.count == inputCount, Set(entry.inputNames).count == inputCount,
                  entry.outputNames.count == outputCount,
                  let bytes = try? Data(contentsOf: url, options: .mappedIfSafe),
                  SHA256.hash(data: bytes).map({ String(format: "%02x", $0) }).joined() == id.sha256,
                  let model = GazeMNN(modelPath: url.path) else {
                issues.append("\(id.method.rawValue) 加载失败")
                continue
            }
            if id.method == .webEyeTrack {
                guard let asset = id.webEyeTrackHead,
                      let payload = try? Data(contentsOf: folder.appendingPathComponent(asset.filename)),
                      SHA256.hash(data: payload).map({ String(format: "%02x", $0) }).joined() == asset.sha256,
                      let head = try? JSONDecoder().decode(WebEyeTrackHead.self, from: payload), head.valid else {
                    issues.append("WebEyeTrack 预测头加载失败")
                    continue
                }
                webEyeTrackBaseHead = head
            }
            loaded.append(Loaded(entry: entry, model: model))
            identities.append(id)
        }
    }

    func predict(buffer: CVPixelBuffer, face: GazeFace?, poseMatrix: [Double]?, focalPx: Double?,
                 cameraIssue: String?, methods: Set<GazeMethod>,
                 webPreprocessorGeneration: Int, webFilterGeneration: Int,
                 mgazeCalibration: MGazeRidgeCalibration?,
                 webCalibration: WebEyeTrackPersonalization?,
                 collectSamples: Bool, isCurrent: () -> Bool,
                 onReading: ((GazeReading) -> Void)?) -> [GazeReading] {
        let selected = loaded.filter { methods.contains($0.entry.identity.method) }
        guard !selected.isEmpty, isCurrent() else { return [] }
        if webPreprocessorGen != webPreprocessorGeneration {
            webEyeTrack.reset()
            webPreprocessorGen = webPreprocessorGeneration
        }
        if webFilterGen != webFilterGeneration {
            webEyeTrackFilter.reset()
            webFilterGen = webFilterGeneration
        }

        let lock = CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { if lock == kCVReturnSuccess { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) } }
        guard lock == kCVReturnSuccess, let pixels = CVPixelBufferGetBaseAddress(buffer),
              let face else {
            let reason = cameraIssue != nil ? "人脸检测异常" : face == nil ? "未检测到人脸" : "相机缓冲不可读"
            return selected.map { GazeReading(method: $0.entry.identity.method, raw: nil, latencyMs: 0, issue: reason) }
        }

        let image = GazeImage(
            pixels: pixels.assumingMemoryBound(to: UInt8.self), stride: CVPixelBufferGetBytesPerRow(buffer),
            width: CVPixelBufferGetWidth(buffer), height: CVPixelBufferGetHeight(buffer)
        )
        var face224Bgr: Data?
        var readings: [GazeReading] = []
        for loaded in selected {
            guard isCurrent() else { break }
            let start = ProcessInfo.processInfo.systemUptime
            let entry = loaded.entry
            let method = entry.identity.method
            if method != .webEyeTrack, !face.openEyes {
                readings.append(GazeReading(method: method, raw: nil, latencyMs: 0, issue: "闭眼或眼部遮挡"))
                continue
            }
            let values: [Data]?
            var geometry: WebEyeTrackGeometry?
            var missingInput = "眼部裁剪超出画面"
            switch method {
            case .mgaze:
                // GazeFollower 实跑链路（WebCamCamera -> MGazeNet）输入为 RGB / 255。
                if image.contains(face.left), image.contains(face.right) {
                    values = [
                        image.crop(face.mgazeBounds, size: 224, bgr: false),
                        image.crop(face.left, size: 112, bgr: false),
                        image.crop(face.right, size: 112, flip: true, bgr: false),
                        image.ranges([face.mgazeBounds, face.left, face.right])
                    ]
                } else { values = nil }
            case .aff, .itracker:
                // MobilePoG (cv2.imread -> ToTensor) 训练管线输入为 BGR / 255。
                if image.contains(face.affLeft), image.contains(face.affRight) {
                    let size = method == .aff ? 112 : 224
                    let rightFlip = method == .aff
                    let cached = face224Bgr ?? image.crop(image.square(face.bounds, expand: 1), size: 224, bgr: true)
                    face224Bgr = cached
                    values = [
                        image.crop(image.square(face.affLeft, expand: 1.2), size: size, bgr: true),
                        image.crop(image.square(face.affRight, expand: 1.2), size: size, flip: rightFlip, bgr: true),
                        cached,
                        method == .aff
                            // AFF-Net 像素框缩放到 MobilePoG 竖屏训练尺度 360×640，匹配 AGN rects_fc 分布。
                            ? image.corners([face.bounds, face.affLeft, face.affRight], targetWidth: 360, targetHeight: 640)
                            // iTracker faceGrid 值为 1/255，匹配 MobilePoG uint8 -> ToTensor() 分布。
                            : image.grid(face.bounds)
                    ]
                } else { values = nil }
            case .l2cs:
                values = [image.crop(face.l2csBounds, size: 448, bgr: false, imageNet: true)]
            case .webEyeTrack:
                let open = face.openEyes
                if let poseMatrix {
                    geometry = webEyeTrack.geometry(points: face.landmarksPx, faceMatrix: poseMatrix,
                                                    width: image.width, height: image.height,
                                                    focalPx: focalPx, updateDepth: open)
                }
                if open, geometry != nil, let patch = WebEyeTrackPreprocessor.eyePatch(points: face.landmarksPx),
                   let pixels = image.webEyeTrackPatch(patch) {
                    values = [pixels]
                } else {
                    values = nil
                    missingInput = open ? "眼部透视裁剪或头姿不可用" : "闭眼或眼部遮挡"
                }
            }

            guard isCurrent() else { break }
            var prediction: GazePoint?
            var webFeatures: [Float]?
            var mgazeFeatures: [Float]?
            var mgazePoint: GazePoint?
            var unfiltered: GazePoint?
            var screenPoint: GazePoint?
            if let values {
                let inputs = Dictionary(uniqueKeysWithValues: zip(entry.inputNames, values))
                if let output = loaded.model.predict(inputs, output: entry.outputNames[0]) {
                    let a = Self.floats(output)
                    if method == .webEyeTrack, a.count == 512, let geometry,
                       let head = webCalibration?.head ?? webEyeTrackBaseHead {
                        let input = a + geometry.headVector.map(Float.init) + geometry.faceOriginCm.map(Float.init)
                        let raw = head.predict(input)
                        let mapped = webCalibration?.affine.apply(raw) ?? raw
                        if raw.finite, mapped.finite {
                            let filtered = webEyeTrackFilter.step(mapped)
                            prediction = raw
                            unfiltered = GazePoint(x: mapped.x + 0.5, y: mapped.y + 0.5)
                            screenPoint = GazePoint(x: min(1, max(0, filtered.x + 0.5)), y: min(1, max(0, filtered.y + 0.5)))
                            if collectSamples { webFeatures = input }
                        }
                    } else if method.usesAngles {
                        if let yaw = Self.angle(a), let second = loaded.model.output(entry.outputNames[1]),
                           let pitch = Self.angle(Self.floats(second)) {
                            prediction = GazePoint(x: yaw, y: pitch)
                        }
                    } else if method == .mgaze, a.count >= 2 {
                        prediction = GazePoint(x: Double(a[0]), y: Double(a[1]))
                        if let mgazeCalibration, a.count == mgazeCalibration.featureCount {
                            mgazePoint = mgazeCalibration.apply(a)
                        }
                        if collectSamples && a.allSatisfy(\.isFinite) { mgazeFeatures = a }
                    } else if method != .webEyeTrack, a.count >= 2 {
                        prediction = GazePoint(x: Double(a[0]), y: Double(a[1]))
                    }
                }
            }
            if prediction?.finite != true { prediction = nil }
            let reading = GazeReading(method: method, raw: prediction,
                                      latencyMs: (ProcessInfo.processInfo.systemUptime - start) * 1000,
                                      issue: prediction == nil ? (values == nil ? missingInput : "模型推理失败") : nil,
                                      webEyeTrack: geometry, webEyeTrackUnfiltered: unfiltered, webEyeTrackPoint: screenPoint,
                                      mgazeCalibratedPoint: mgazePoint,
                                      webEyeTrackFeatures: webFeatures, mgazeFeatures: mgazeFeatures)
            readings.append(reading)
            if prediction != nil { onReading?(reading) }
        }
        return readings
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

    func corners(_ boxes: [CGRect], targetWidth: Float, targetHeight: Float) -> Data {
        let sx = targetWidth / Float(max(width, 1))
        let sy = targetHeight / Float(max(height, 1))
        return Self.data(boxes.flatMap {
            [Float($0.minX) * sx, Float($0.minY) * sy, Float($0.maxX) * sx, Float($0.maxY) * sy]
        })
    }

    /// 精确匹配 MobilePoG `make_grid()` + `transforms.ToTensor()`：
    /// 掩膜取每格中心点判断，有效值为 1/255（而非 1.0）。
    func grid(_ box: CGRect) -> Data {
        let active: Float = 1.0 / 255.0
        let cellW = Double(width) / 25.0
        let cellH = Double(height) / 25.0
        let values: [Float] = (0..<625).map { index in
            let cx = (Double(index % 25) + 0.5) * cellW
            let cy = (Double(index / 25) + 0.5) * cellH
            return box.contains(CGPoint(x: cx, y: cy)) ? active : 0
        }
        return Self.data(values)
    }

    func crop(_ area: CGRect, size: Int, flip: Bool = false, bgr: Bool, imageNet: Bool = false) -> Data {
        let rect = area.integral.intersection(bounds)
        let count = size * size
        var values = [Float](repeating: 0, count: count * 3)
        let x0 = Int(rect.minX), y0 = Int(rect.minY)
        let cropWidth = Int(rect.width), cropHeight = Int(rect.height)
        guard cropWidth > 0, cropHeight > 0 else { return Self.data(values) }

        let inv255: Float = 1.0 / 255.0
        let mean0: Float = imageNet ? 0.485 : 0
        let mean1: Float = imageNet ? 0.456 : 0
        let mean2: Float = imageNet ? 0.406 : 0
        let invStd0: Float = imageNet ? (1.0 / 0.229) : 1
        let invStd1: Float = imageNet ? (1.0 / 0.224) : 1
        let invStd2: Float = imageNet ? (1.0 / 0.225) : 1

        // BGRA 像素中的源通道偏移：RGB 为 (2, 1, 0)，BGR 为 (0, 1, 2)。
        let ch0 = bgr ? 0 : 2
        let ch1 = 1
        let ch2 = bgr ? 2 : 0

        let scaleX = Float(cropWidth) / Float(size)
        let scaleY = Float(cropHeight) / Float(size)
        let maxX = Float(cropWidth - 1)
        let maxY = Float(cropHeight - 1)

        var xLeft = [Int](repeating: 0, count: size)
        var xRight = [Int](repeating: 0, count: size)
        var xWeight = [Float](repeating: 0, count: size)
        for col in 0..<size {
            let idx = flip ? (size - 1 - col) : col
            let pos = max(0, min(maxX, (Float(idx) + 0.5) * scaleX - 0.5))
            let first = Int(pos)
            xLeft[col] = (x0 + first) * 4
            xRight[col] = (x0 + min(first + 1, cropWidth - 1)) * 4
            xWeight[col] = pos - Float(first)
        }

        let src = pixels
        let rowStride = stride
        values.withUnsafeMutableBufferPointer { dst in
            let p0 = dst.baseAddress!
            let p1 = p0 + count
            let p2 = p1 + count
            for row in 0..<size {
                let posY = max(0, min(maxY, (Float(row) + 0.5) * scaleY - 0.5))
                let firstY = Int(posY)
                let dy = posY - Float(firstY)
                let oneMinusDy = 1 - dy
                let topRow = src + (y0 + firstY) * rowStride
                let botRow = src + (y0 + min(firstY + 1, cropHeight - 1)) * rowStride
                let rowBase = row * size
                for col in 0..<size {
                    let xl = xLeft[col]
                    let xr = xRight[col]
                    let dx = xWeight[col]
                    let oneMinusDx = 1 - dx
                    let outIdx = rowBase + col

                    let u0 = Float(topRow[xl + ch0]) * oneMinusDx + Float(topRow[xr + ch0]) * dx
                    let l0 = Float(botRow[xl + ch0]) * oneMinusDx + Float(botRow[xr + ch0]) * dx
                    p0[outIdx] = ((u0 * oneMinusDy + l0 * dy) * inv255 - mean0) * invStd0

                    let u1 = Float(topRow[xl + ch1]) * oneMinusDx + Float(topRow[xr + ch1]) * dx
                    let l1 = Float(botRow[xl + ch1]) * oneMinusDx + Float(botRow[xr + ch1]) * dx
                    p1[outIdx] = ((u1 * oneMinusDy + l1 * dy) * inv255 - mean1) * invStd1

                    let u2 = Float(topRow[xl + ch2]) * oneMinusDx + Float(topRow[xr + ch2]) * dx
                    let l2 = Float(botRow[xl + ch2]) * oneMinusDx + Float(botRow[xr + ch2]) * dx
                    p2[outIdx] = ((u2 * oneMinusDy + l2 * dy) * inv255 - mean2) * invStd2
                }
            }
        }
        return Self.data(values)
    }

    func webEyeTrackPatch(_ patch: WebEyeTrackPatch) -> Data? {
        let patchWidth = WebEyeTrackPatch.width, patchHeight = WebEyeTrackPatch.height
        let count = patchWidth * patchHeight
        var result = [Float](repeating: 0, count: count * 3)
        var valid = 0
        let inv255: Float = 1.0 / 255.0
        let src = pixels
        let rowStride = stride
        let wDouble = Double(width), hDouble = Double(height)

        result.withUnsafeMutableBufferPointer { dst in
            let out = dst.baseAddress!
            for y in 0..<patchHeight {
                for x in 0..<patchWidth {
                    guard let p = patch.source(x: x, y: y),
                          p.x >= 0, p.x < wDouble, p.y >= 0, p.y < hDouble else { continue }
                    let input = Int(p.y) * rowStride + Int(p.x) * 4
                    let output = (y * patchWidth + x) * 3
                    out[output] = Float(src[input + 2]) * inv255
                    out[output + 1] = Float(src[input + 1]) * inv255
                    out[output + 2] = Float(src[input]) * inv255
                    valid += 1
                }
            }
        }
        return valid * 4 >= count * 3 ? Self.data(result) : nil
    }

    static func data(_ values: [Float]) -> Data { values.withUnsafeBytes { Data($0) } }
}

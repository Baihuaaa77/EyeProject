import Foundation

// 根据 RedForestAI/WebEyeTrack 的 mathUtils.ts 移植，保留作者的人脸尺度与投影约定。
// 上游许可证与版本记录见实验目录的 WebEyeTrack-LICENSE.txt。
struct WebEyeTrackGeometry: Codable {
    let headVector: [Double]
    let faceOriginCm: [Double]
    let faceWidthCm: Double
}

struct WebEyeTrackPreprocessor {
    private var faceWidthCm: Double?
    private var depthCm = 60.0

    mutating func geometry(points: [GazePoint], faceMatrix m: [Double], width: Int, height: Int) -> WebEyeTrackGeometry? {
        guard points.count >= 478, m.count == 16, m.allSatisfy(\.isFinite), width > 0, height > 0 else { return nil }

        let faceWidthPx = distance(points[356], points[127])
        let irisA = distance(pixel(points[476]), pixel(points[474]))
        let irisB = distance(pixel(points[471]), pixel(points[469]))
        guard faceWidthPx > 10, irisA >= 2, irisB >= 2 else { return nil }
        let pixelFaceWidth = distance(pixel(points[356]), pixel(points[127]))
        let estimate = 2.4 * pixelFaceWidth / (irisA + irisB)
        guard (7...32).contains(estimate) else { return nil }
        let faceWidth = faceWidthCm ?? estimate

        let matrixScale = (0..<3).map { i in hypot(hypot(m[i], m[i + 4]), m[i + 8]) }.reduce(0, +) / 3
        guard matrixScale > 0.1 && matrixScale < 3 else { return nil }
        let pitch = -atan2(m[9], m[10])
        let yaw = asin(max(-1, min(1, -m[8])))
        let roll = atan2(m[4], m[0])
        let x = cos(pitch) * sin(yaw), y = sin(pitch)
        let head = [x * cos(roll) - y * sin(roll), x * sin(roll) + y * cos(roll), -cos(pitch) * cos(yaw)]

        // 源实现先把平面点逆旋转，再施加同一旋转，两次旋转相消。
        let nose = points[4]
        let scale = faceWidth / (faceWidthPx * matrixScale)
        let relative = points.map { GazePoint(x: ($0.x - nose.x) * scale, y: ($0.y - nose.y) * scale) }
        let count = Double(points.count)
        let centroidX = points.map(\.x).reduce(0, +) / count
        let centroidY = points.map(\.y).reduce(0, +) / count

        // 浏览器实现缺省 fx=fy=图像宽度。径向尺度优化和人脸原点使用同一焦距。
        let focal = Double(width)
        let cx = focal / 2, cy = Double(height) / 2
        let rayX = (nose.x - cx) / focal, rayY = (nose.y - cy) / focal
        var depth = depthCm
        for _ in 0..<10 {
            let factor = focal / depth
            var residual = 0.0
            for (measured, point) in zip(points, relative) {
                let projectedX = floor(point.x * factor + rayX * focal + cx + 0.5)
                let projectedY = floor(point.y * factor + rayY * focal + cy + 0.5)
                let dx = measured.x - projectedX, dy = measured.y - projectedY
                let towardCenter = dx * (centroidX - projectedX) + dy * (centroidY - projectedY)
                residual += (towardCenter < 0 ? -1 : 1) * hypot(dx, dy)
            }
            let update = max(-5, min(5, 0.1 * residual / count))
            if abs(update) < 0.25 { break }
            depth = max(10, min(250, depth + update))
        }
        let eyes = [33, 133, 362, 263].map { relative[$0] }
        let origin = [rayX * depth + eyes.map(\.x).reduce(0, +) / 4,
                      rayY * depth + eyes.map(\.y).reduce(0, +) / 4, depth]
        guard (head + origin).allSatisfy(\.isFinite) else { return nil }
        faceWidthCm = faceWidth
        depthCm = depth
        return WebEyeTrackGeometry(headVector: head, faceOriginCm: origin, faceWidthCm: faceWidth)
    }

    static func eyePatch(points: [GazePoint]) -> WebEyeTrackPatch? {
        guard points.count >= 380 else { return nil }
        let nose = pixel(points[4])
        let outline = [103, 150, 379, 332].map { index in
            let point = pixel(points[index])
            return GazePoint(x: nose.x + (point.x - nose.x) * 1.4, y: nose.y + (point.y - nose.y) * 1.2)
        }
        let square = [GazePoint(x: 0, y: 0), GazePoint(x: 0, y: 512),
                      GazePoint(x: 512, y: 512), GazePoint(x: 512, y: 0)]
        guard let forward = WebEyeTrackHomography(source: outline, destination: square),
              let inverse = WebEyeTrackHomography(source: square, destination: outline),
              let top = forward.map(pixel(points[151])), let bottom = forward.map(pixel(points[195])),
              abs(top.y) < 1024, abs(bottom.y) < 1024 else { return nil }
        let first = Int(floor(top.y + 0.5))
        let rows = Int(floor(bottom.y - top.y + 0.5))
        guard rows >= 5, first < 512, first + rows > 0 else { return nil }
        return WebEyeTrackPatch(inverse: inverse, firstRow: first, rows: rows)
    }

    private static func pixel(_ point: GazePoint) -> GazePoint {
        GazePoint(x: floor(point.x), y: floor(point.y))
    }

    private func pixel(_ point: GazePoint) -> GazePoint { Self.pixel(point) }

    private func distance(_ p: GazePoint, _ q: GazePoint) -> Double { hypot(p.x - q.x, p.y - q.y) }
}

struct WebEyeTrackPatch {
    static let width = 512
    static let height = 128
    let inverse: WebEyeTrackHomography
    let firstRow: Int
    let rows: Int

    func source(x: Int, y: Int) -> GazePoint? {
        let row = firstRow + y * rows / Self.height
        guard (0..<512).contains(row) else { return nil }
        return inverse.map(GazePoint(x: Double(x), y: Double(row)))
    }
}

struct WebEyeTrackHomography {
    private let m: [Double]

    init?(source: [GazePoint], destination: [GazePoint]) {
        var a: [[Double]] = []
        for (s, d) in zip(source, destination) {
            a.append([s.x, s.y, 1, 0, 0, 0, -d.x * s.x, -d.x * s.y, d.x])
            a.append([0, 0, 0, s.x, s.y, 1, -d.y * s.x, -d.y * s.y, d.y])
        }

        for column in 0..<8 {
            guard let pivot = (column..<8).max(by: { abs(a[$0][column]) < abs(a[$1][column]) }),
                  abs(a[pivot][column]) > 1e-9 else { return nil }
            a.swapAt(column, pivot)
            let divisor = a[column][column]
            for j in column..<9 { a[column][j] /= divisor }
            for i in 0..<8 where i != column {
                let factor = a[i][column]
                for j in column..<9 { a[i][j] -= factor * a[column][j] }
            }
        }
        m = a.map { $0[8] }
    }

    func map(_ p: GazePoint) -> GazePoint? {
        let denominator = m[6] * p.x + m[7] * p.y + 1
        guard abs(denominator) > 1e-9 else { return nil }
        let value = GazePoint(x: (m[0] * p.x + m[1] * p.y + m[2]) / denominator,
                              y: (m[3] * p.x + m[4] * p.y + m[5]) / denominator)
        return value.finite ? value : nil
    }
}

import Accelerate
import CoreVideo
import EyeScreening

/// 一张眼片在源图上的裁剪矩形（整数像素，right / bottom 为排他边界）。
struct OcecEyeCrop: Equatable {
    let left: Int, top: Int, right: Int, bottom: Int
    var width: Int { right - left }
    var height: Int { bottom - top }
}

/// OCEC 眼片裁剪几何：眼睑 6 点 → 裁剪矩形。逐行移植 newpart `core:vision` 的 OcecEyeGeometry，
/// 两个比例常量同为**未标定的初值**（OCEC 与训练构图共生，框偏一点睁眼就会被判成闭眼）。
enum OcecEyeGeometry {
    /// 眼框在睑裂外接框基础上的外扩比例（待标定）。
    static let marginRatio: Float = 0.15
    /// 眼框高度下限（占睑裂宽的比例）：闭眼时上下睑点靠拢，没有下限框会塌成一条线（待标定）。
    static let minHeightRatio: Float = 0.33

    static func crop(points: [Point2f], imageWidth: Int, imageHeight: Int) -> OcecEyeCrop? {
        guard points.count >= 6, imageWidth > 0, imageHeight > 0 else { return nil }
        let xs = points.map { $0.x }, ys = points.map { $0.y }
        guard let minX = xs.min(), let maxX = xs.max(), let minY = ys.min(), let maxY = ys.max(),
              minX.isFinite, maxX.isFinite, minY.isFinite, maxY.isFinite else { return nil }
        let eyeWidth = maxX - minX
        guard eyeWidth > 1 else { return nil }
        let eyeHeight = max(maxY - minY, eyeWidth * minHeightRatio)
        let centerX = (minX + maxX) / 2, centerY = (minY + maxY) / 2
        let halfWidth = eyeWidth * (1 + marginRatio) / 2, halfHeight = eyeHeight * (1 + marginRatio) / 2
        // 截断取整（Kotlin toInt 同为向零截断）：改取整方式等于改构图
        let left = max(Int(centerX - halfWidth), 0), top = max(Int(centerY - halfHeight), 0)
        let right = min(Int(centerX + halfWidth), imageWidth - 1), bottom = min(Int(centerY + halfHeight), imageHeight - 1)
        guard right - left >= 2, bottom - top >= 2 else { return nil }
        return OcecEyeCrop(left: left, top: top, right: right, bottom: bottom)
    }
}

/// OCEC-P 睁眼概率分类器（输入 24×40 RGB÷255、NHWC，输出已过 Sigmoid 的睁眼概率）。
///
/// iOS 不另引 TFLite（应用已静态链接 MediaPipe，再链一份有符号冲突风险），而是用 Accelerate 手写同一前向：
/// 权重由 `scripts/ios/export_ocec_weights.py` 从 ocec_p_float32.tflite 导出，脚本已用 numpy 同算法与 TFLite 逐张对拍；
/// 本类再由单测拿真机眼片夹具与 TFLite 输出对拍。网络：3×3 卷积 → 3×3/2 卷积 → 1×1 扩张 → 3×3 深度卷积
/// → SE → 1×1 投影 + 残差（激活均为 SiLU）→ 全局均值/最大池化拼接 → 仿射 → 全连接 ×2 → Sigmoid。
///
/// 内部缓冲复用，**不是线程安全的**：同一实例只在一个队列上调用。
final class OcecEyeClassifier {
    static let inputWidth = 40, inputHeight = 24
    private static let weightCount = 26_257

    private let conv0W, conv0B, conv1W, conv1B, expandW, expandB, depthW, depthB: [Float]
    private let seReduceW, seReduceB, seExpandW, seExpandB, projectW, projectB: [Float]
    private let headScale, headShift, fc1W, fc1B, fc2W, fc2B: [Float]

    // 逐帧复用的中间缓冲（尺寸见 probability 里的注释）
    private var input = [Float](repeating: 0, count: 24 * 40 * 3)
    private var cols0 = [Float](repeating: 0, count: 960 * 27)
    private var act0 = [Float](repeating: 0, count: 960 * 32)
    private var cols1 = [Float](repeating: 0, count: 240 * 288)
    private var skip = [Float](repeating: 0, count: 240 * 32)
    private var expanded = [Float](repeating: 0, count: 240 * 128)
    private var depth = [Float](repeating: 0, count: 240 * 128)
    private var projected = [Float](repeating: 0, count: 240 * 32)

    init?(bundle: Bundle = .main) {
        guard let url = bundle.url(forResource: "ocec_p_weights", withExtension: "bin"),
              let data = try? Data(contentsOf: url), data.count == 8 + Self.weightCount * 4,
              String(data: data.prefix(8), encoding: .ascii) == "OCECP001" else { return nil }
        var all = [Float](repeating: 0, count: Self.weightCount)
        _ = all.withUnsafeMutableBufferPointer { data.copyBytes(to: $0, from: 8..<data.count) }
        var offset = 0
        func take(_ count: Int) -> [Float] { defer { offset += count }; return Array(all[offset..<offset + count]) }
        conv0W = take(27 * 32); conv0B = take(32)
        conv1W = take(288 * 32); conv1B = take(32)
        expandW = take(32 * 128); expandB = take(128)
        depthW = take(9 * 128); depthB = take(128)
        seReduceW = take(128 * 16); seReduceB = take(16)
        seExpandW = take(16 * 128); seExpandB = take(128)
        projectW = take(128 * 32); projectB = take(32)
        headScale = take(64); headShift = take(64)
        fc1W = take(64 * 32); fc1B = take(32)
        fc2W = take(32); fc2B = take(1)
    }

    /// 眼片按**非等比直接拉伸**铺满 40×24（与安卓 Canvas.drawBitmap 无滤镜一致：最近邻，取目标像素中心映射回源图），
    /// 返回 RGB 字节。BGRA 像素缓冲之外的格式返回 nil。
    static func sample(_ buffer: CVPixelBuffer, crop: OcecEyeCrop) -> [UInt8]? {
        guard CVPixelBufferGetPixelFormatType(buffer) == kCVPixelFormatType_32BGRA,
              crop.left >= 0, crop.top >= 0, crop.width > 0, crop.height > 0,
              crop.right <= CVPixelBufferGetWidth(buffer), crop.bottom <= CVPixelBufferGetHeight(buffer) else { return nil }
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(buffer)?.assumingMemoryBound(to: UInt8.self) else { return nil }
        let stride = CVPixelBufferGetBytesPerRow(buffer)
        var rgb = [UInt8](repeating: 0, count: inputWidth * inputHeight * 3)
        for y in 0..<inputHeight {
            let sy = crop.top + min(crop.height - 1, Int((Double(y) + 0.5) * Double(crop.height) / Double(inputHeight)))
            for x in 0..<inputWidth {
                let sx = crop.left + min(crop.width - 1, Int((Double(x) + 0.5) * Double(crop.width) / Double(inputWidth)))
                let pixel = base + sy * stride + sx * 4, i = (y * inputWidth + x) * 3
                rgb[i] = pixel[2]; rgb[i + 1] = pixel[1]; rgb[i + 2] = pixel[0]
            }
        }
        return rgb
    }

    /// 40×24 RGB 字节 → 睁眼概率（0–1，越低越接近闭眼）。
    func probability(rgb: [UInt8]) -> Float {
        precondition(rgb.count == input.count, "OCEC 输入须为 40×24×3")
        for i in 0..<rgb.count { input[i] = Float(rgb[i]) / 255 }
        // 3×3 卷积 3→32，步长 1：[960, 27] × [27, 32]
        Self.im2col(input, height: 24, width: 40, channels: 3, stride: 1, into: &cols0)
        Self.matmul(cols0, conv0W, &act0, rows: 960, outputs: 32, inner: 27)
        Self.biasSiLU(&act0, conv0B)
        // 四周补零后 3×3 卷积 32→32，步长 2：24×40 → 12×20，作为残差支路
        Self.im2col(act0, height: 24, width: 40, channels: 32, stride: 2, into: &cols1)
        Self.matmul(cols1, conv1W, &skip, rows: 240, outputs: 32, inner: 288)
        Self.biasSiLU(&skip, conv1B)
        // 1×1 扩张 32→128
        Self.matmul(skip, expandW, &expanded, rows: 240, outputs: 128, inner: 32)
        Self.biasSiLU(&expanded, expandB)
        // 3×3 深度卷积（四周补零，步长 1）
        for y in 0..<12 { for x in 0..<20 {
            let o = (y * 20 + x) * 128
            for c in 0..<128 { depth[o + c] = depthB[c] }
            for ky in 0..<3 {
                let iy = y + ky - 1
                if iy < 0 || iy >= 12 { continue }
                for kx in 0..<3 {
                    let ix = x + kx - 1
                    if ix < 0 || ix >= 20 { continue }
                    let i = (iy * 20 + ix) * 128, k = (ky * 3 + kx) * 128
                    for c in 0..<128 { depth[o + c] += expanded[i + c] * depthW[k + c] }
                }
            }
        }}
        Self.biasSiLU(&depth, nil)
        // SE：全局均值 → 1×1 压缩 128→16（ReLU）→ 1×1 激励 16→128（Sigmoid）→ 逐通道相乘
        var mean = [Float](repeating: 0, count: 128)
        for r in 0..<240 { for c in 0..<128 { mean[c] += depth[r * 128 + c] } }
        for c in 0..<128 { mean[c] /= 240 }
        var squeezed = [Float](repeating: 0, count: 16)
        Self.matmul(mean, seReduceW, &squeezed, rows: 1, outputs: 16, inner: 128)
        for j in 0..<16 { squeezed[j] = max(squeezed[j] + seReduceB[j], 0) }
        var gate = [Float](repeating: 0, count: 128)
        Self.matmul(squeezed, seExpandW, &gate, rows: 1, outputs: 128, inner: 16)
        for c in 0..<128 { gate[c] = Self.sigmoid(gate[c] + seExpandB[c]) }
        for r in 0..<240 { for c in 0..<128 { depth[r * 128 + c] *= gate[c] } }
        // 1×1 投影 128→32 + 残差
        Self.matmul(depth, projectW, &projected, rows: 240, outputs: 32, inner: 128)
        for r in 0..<240 { for c in 0..<32 { projected[r * 32 + c] += projectB[c] + skip[r * 32 + c] } }
        Self.biasSiLU(&projected, nil)
        // 头部：[全局均值, 全局最大] → 仿射 → 全连接 64→32（ReLU）→ 32→1 → Sigmoid
        var feature = [Float](repeating: 0, count: 64)
        for c in 0..<32 { feature[32 + c] = -.greatestFiniteMagnitude }
        for r in 0..<240 { for c in 0..<32 {
            let v = projected[r * 32 + c]
            feature[c] += v; feature[32 + c] = max(feature[32 + c], v)
        }}
        for c in 0..<32 { feature[c] /= 240 }
        for j in 0..<64 { feature[j] = feature[j] * headScale[j] + headShift[j] }
        var hidden = [Float](repeating: 0, count: 32)
        Self.matmul(feature, fc1W, &hidden, rows: 1, outputs: 32, inner: 64)
        var logit = fc2B[0]
        for j in 0..<32 { logit += max(hidden[j] + fc1B[j], 0) * fc2W[j] }
        return Self.sigmoid(logit)
    }

    /// 3×3 卷积展开（四周补一圈零）：输入 [h, w, c] → [oh·ow, 9c]，列序 (ky, kx, c) 与导出的权重一致。
    private static func im2col(_ x: [Float], height h: Int, width w: Int, channels c: Int, stride: Int, into cols: inout [Float]) {
        let oh = (h - 1) / stride + 1, ow = (w - 1) / stride + 1, row = 9 * c
        x.withUnsafeBufferPointer { source in cols.withUnsafeMutableBufferPointer { out in
            for oy in 0..<oh { for ox in 0..<ow {
                let base = (oy * ow + ox) * row
                for ky in 0..<3 { for kx in 0..<3 {
                    let iy = oy * stride + ky - 1, ix = ox * stride + kx - 1, o = base + (ky * 3 + kx) * c
                    if iy < 0 || iy >= h || ix < 0 || ix >= w {
                        for ch in 0..<c { out[o + ch] = 0 }
                    } else {
                        let i = (iy * w + ix) * c
                        for ch in 0..<c { out[o + ch] = source[i + ch] }
                    }
                }}
            }}
        }}
    }

    /// C[rows×outputs] = A[rows×inner] · B[inner×outputs]（行主序）。
    private static func matmul(_ a: [Float], _ b: [Float], _ c: inout [Float], rows: Int, outputs: Int, inner: Int) {
        a.withUnsafeBufferPointer { pa in b.withUnsafeBufferPointer { pb in c.withUnsafeMutableBufferPointer { pc in
            vDSP_mmul(pa.baseAddress!, 1, pb.baseAddress!, 1, pc.baseAddress!, 1,
                      vDSP_Length(rows), vDSP_Length(outputs), vDSP_Length(inner))
        }}}
    }

    /// 逐通道加偏置（可无）后做 SiLU：x·sigmoid(x)。
    private static func biasSiLU(_ values: inout [Float], _ bias: [Float]?) {
        let channels = bias?.count ?? 1
        let offsets = bias ?? [0]
        values.withUnsafeMutableBufferPointer { v in
            for i in 0..<v.count {
                let x = v[i] + offsets[i % channels]
                v[i] = x * sigmoid(x)
            }
        }
    }

    private static func sigmoid(_ x: Float) -> Float { 1 / (1 + exp(-x)) }
}

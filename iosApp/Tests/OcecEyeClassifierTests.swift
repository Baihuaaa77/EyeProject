import XCTest
@testable import EyeProject
import EyeScreening
import CoreVideo

/// 眨眼对比实验页的 OCEC 手写推理：与 TFLite 对拍、裁片采样与裁剪几何与安卓同口径。
final class OcecEyeClassifierTests: XCTestCase {
    /// 夹具由 scripts/ios/export_ocec_weights.py 生成：真机眼片（睁眼与闭眼都有）+ TFLite 解释器输出。
    func testHandWrittenForwardMatchesTFLiteOnDeviceEyeCrops() throws {
        let classifier = try XCTUnwrap(OcecEyeClassifier(), "应用包里缺少 ocec_p_weights.bin")
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "ocec_fixture", withExtension: "json"))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        let cases = try XCTUnwrap(json["cases"] as? [[String: Any]])
        XCTAssertGreaterThan(cases.count, 3)
        for item in cases {
            let rgb = try XCTUnwrap(Data(base64Encoded: item["rgb"] as? String ?? ""))
            let expected = try XCTUnwrap((item["prob"] as? NSNumber)?.floatValue)
            XCTAssertEqual(classifier.probability(rgb: [UInt8](rgb)), expected, accuracy: 1e-4, item["name"] as? String ?? "")
        }
    }

    /// 最近邻拉伸：目标像素中心映射回裁剪框后向下取整；BGRA 转成 RGB。
    func testSamplerStretchesTheCropFromPixelCentres() throws {
        var pixelBuffer: CVPixelBuffer?
        XCTAssertEqual(CVPixelBufferCreate(kCFAllocatorDefault, 100, 60, kCVPixelFormatType_32BGRA, nil, &pixelBuffer), kCVReturnSuccess)
        let buffer = try XCTUnwrap(pixelBuffer)
        CVPixelBufferLockBaseAddress(buffer, [])
        let base = try XCTUnwrap(CVPixelBufferGetBaseAddress(buffer)).assumingMemoryBound(to: UInt8.self)
        let stride = CVPixelBufferGetBytesPerRow(buffer)
        for y in 0..<60 { for x in 0..<100 {
            let p = base + y * stride + x * 4
            p[0] = UInt8(x); p[1] = UInt8(y); p[2] = 7; p[3] = 255
        }}
        CVPixelBufferUnlockBaseAddress(buffer, [])
        // 80×48 的框拉到 40×24：每个目标像素取源图 (2x+1, 2y+1) 偏移处
        let rgb = try XCTUnwrap(OcecEyeClassifier.sample(buffer, crop: OcecEyeCrop(left: 10, top: 5, right: 90, bottom: 53)))
        XCTAssertEqual(rgb.count, 40 * 24 * 3)
        for y in [0, 11, 23] { for x in [0, 17, 39] {
            let i = (y * 40 + x) * 3
            XCTAssertEqual(rgb[i], 7)
            XCTAssertEqual(rgb[i + 1], UInt8(6 + 2 * y))
            XCTAssertEqual(rgb[i + 2], UInt8(11 + 2 * x))
        }}
        XCTAssertNil(OcecEyeClassifier.sample(buffer, crop: OcecEyeCrop(left: 10, top: 5, right: 101, bottom: 53)), "框越界不采样")
    }

    /// 闭眼时 6 点几乎共线：框高取眼宽的 0.33，再外扩 15%，截断取整。
    func testGeometryKeepsAMinimumHeightForClosedEyes() {
        let flat = [200, 212, 224, 236, 248, 260].map { Point2f(x: Float($0), y: 100) }
        XCTAssertEqual(OcecEyeGeometry.crop(points: flat, imageWidth: 720, imageHeight: 1280),
                       OcecEyeCrop(left: 195, top: 88, right: 264, bottom: 111))
        XCTAssertNil(OcecEyeGeometry.crop(points: Array(flat.prefix(5)), imageWidth: 720, imageHeight: 1280), "不足 6 点不定位")
        XCTAssertEqual(OcecEyeGeometry.crop(points: flat, imageWidth: 250, imageHeight: 1280)?.right, 249, "右边界夹到图内")
    }
}

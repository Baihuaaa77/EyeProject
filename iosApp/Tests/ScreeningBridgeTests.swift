import XCTest
@testable import EyeProject
import EyeScreening
import CoreVideo

final class ScreeningBridgeTests: XCTestCase {
    func testFeedbackSoundsAreBundledWithTheApp() throws {
        for sound in ["eye_start", "eye_answer", "eye_phase", "eye_target", "eye_warning", "eye_tick", "eye_complete"] {
            let url = try XCTUnwrap(Bundle.main.url(forResource: sound, withExtension: "wav"), "缺少提示音：\(sound)")
            let data = try Data(contentsOf: url)
            XCTAssertGreaterThan(data.count, 44)
            XCTAssertEqual(String(data: data.prefix(4), encoding: .ascii), "RIFF")
            XCTAssertEqual(String(data: data.dropFirst(8).prefix(4), encoding: .ascii), "WAVE")
        }
    }
    func testAllThreeTasksUseTheSharedProfileAndCommands() {
        let controller = MobileScreeningController(systemEvent: { _ in }, recordSaved: { _, _ in })
        controller.configureDisplay(widthPixels: 1170, heightPixels: 2532, pixelsPerMm: 18)
        for kind in ["DRY_EYE", "PRESBYOPIA", "STRABISMUS"] {
            controller.selectTest(name: kind)
            XCTAssertEqual(EyeRuntime.object(controller.snapshotJson())["kind"] as? String, kind)
            let questions = EyeRuntime.object(controller.questionnaireJson())["questions"] as? [[String: Any]]
            XCTAssertFalse(questions?.isEmpty ?? true)
            XCTAssertFalse(controller.profileComplete(), "未完成问卷不能进入测量")
        }
        controller.close()
    }
    func testIOSPixelsUseTheSameRGBWeights() {
        var pixelBuffer: CVPixelBuffer?
        XCTAssertEqual(CVPixelBufferCreate(kCFAllocatorDefault, 2, 2, kCVPixelFormatType_32BGRA, nil, &pixelBuffer), kCVReturnSuccess)
        guard let buffer = pixelBuffer else { return XCTFail("无法创建测试像素缓冲") }
        CVPixelBufferLockBaseAddress(buffer, [])
        guard let base = CVPixelBufferGetBaseAddress(buffer) else { return XCTFail("缺少像素") }
        let bytes = base.assumingMemoryBound(to: UInt8.self), stride = CVPixelBufferGetBytesPerRow(buffer)
        let colors: [[UInt8]] = [[0,0,255,255],[0,255,0,255],[255,0,0,255],[255,255,255,255]]
        for y in 0..<2 { for x in 0..<2 { for c in 0..<4 { bytes[y * stride + x * 4 + c] = colors[y * 2 + x][c] } } }
        CVPixelBufferUnlockBaseAddress(buffer, [])
        let image = CameraGrayImage(buffer)
        let region = image.grayRegion(left: 0, top: 0, width: 2, height: 2)
        for (i, expected) in [54,182,18,255].enumerated() { XCTAssertEqual(region.get(index: Int32(i)), Int32(expected)) }
        XCTAssertEqual(image.grayRegion(left: 1, top: 1, width: 1, height: 1).get(index: 0), 255)
    }
}

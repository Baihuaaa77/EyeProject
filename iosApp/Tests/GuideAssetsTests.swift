import UIKit
import XCTest
@testable import EyeProject

/// 验证 XcodeGen 真正把共用图片放进应用，并保留透明通道。
@MainActor
final class GuideAssetsTests: XCTestCase {
    func testEveryGuidePoseIsBundledWithAlpha() throws {
        for pose in ["open", "blink", "left", "right", "helper_open", "helper_left", "helper_right"] {
            let name = "eyecho_guide_" + pose
            let image = try XCTUnwrap(UIImage(named: name)?.cgImage, "缺少爱科分镜：" + name)
            XCTAssertTrue([CGImageAlphaInfo.premultipliedFirst, .premultipliedLast, .first, .last].contains(image.alphaInfo),
                          "人物图片必须保留透明背景：" + name)
        }
    }
}

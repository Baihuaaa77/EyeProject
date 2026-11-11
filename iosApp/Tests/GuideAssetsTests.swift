import SwiftUI
import UIKit
import XCTest
@testable import EyeProject

/// 验证 XcodeGen 真正把共用图片放进应用，并保留透明通道。
@MainActor
final class GuideAssetsTests: XCTestCase {
    func testEveryGuidePoseIsBundledWithAlpha() throws {
        for pose in ["open", "blink", "left", "right", "helper_open", "helper_left", "helper_right"] {
            let name = "eyecho_guide_" + pose
            let image = try XCTUnwrap(EyechoGuideImages.image(named: name)?.cgImage, "缺少爱科分镜：" + name)
            XCTAssertTrue([CGImageAlphaInfo.premultipliedFirst, .premultipliedLast, .first, .last].contains(image.alphaInfo),
                          "人物图片必须保留透明背景：" + name)
        }
    }

    /// 检查应用实际使用的 SwiftUI 图片，而不仅仅检查 UIImage 能解码。
    func testPortraitActuallyRendersInBothThemes() throws {
        for dark in [false, true] {
            for pose in ["open", "blink", "left", "right", "helper_open", "helper_left", "helper_right"] {
                let view = EyechoGuideImage(name: "eyecho_guide_" + pose)
                    .frame(width: 320, height: 240)
                    .background(EyeStyle.surfaceVariant)
                    .environment(\.colorScheme, dark ? .dark : .light)
                let renderer = ImageRenderer(content: view)
                renderer.scale = 1
                let image = try XCTUnwrap(renderer.uiImage)
                XCTAssertGreaterThan(try skinPixels(image, minX: 0, maxX: 320), 1500, "人物不能显示为空白：" + pose)
                attach(image, name: "爱科-\(pose)-\(dark ? "深色" : "浅色")")
            }
        }
    }

    /// 完整舞台左侧必须有人物；手机简图和文字不能代替这项检查。
    func testServiceStageKeepsPortraitVisibleBesidePhone() throws {
        for motion in [GuideMotion.near, .move, .cover, .coverSelf, .fva] {
            let renderer = ImageRenderer(content: GuideFrame(motion: motion, phase: 0.8).frame(width: 343, height: 190))
            renderer.scale = 1
            let image = try XCTUnwrap(renderer.uiImage)
            XCTAssertGreaterThan(try skinPixels(image, minX: 0, maxX: 210), 500, "示范舞台左侧缺少爱科：\(motion)")
            attach(image, name: "服务页爱科-\(motion)")
        }
    }

    private func skinPixels(_ image: UIImage, minX: Int, maxX: Int) throws -> Int {
        let cg = try XCTUnwrap(image.cgImage)
        let width = cg.width, height = cg.height
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        let context = try XCTUnwrap(CGContext(data: &pixels, width: width, height: height, bitsPerComponent: 8,
            bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue))
        context.draw(cg, in: CGRect(x: 0, y: 0, width: width, height: height))
        var count = 0
        for y in 0..<height { for x in max(0, minX)..<min(width, maxX) {
            let i = (y * width + x) * 4
            let r = Int(pixels[i]), g = Int(pixels[i + 1]), b = Int(pixels[i + 2])
            if r > 120 && r > g + 12 && g > b + 4 { count += 1 }
        } }
        return count
    }

    private func attach(_ image: UIImage, name: String) {
        let attachment = XCTAttachment(image: image)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}

import SwiftUI
import UIKit
import XCTest
@testable import EyeProject

/// 静态资源存在不代表 Xcode 已打包；直接渲染应用使用的眼睛组件验证。
@MainActor
final class BrownArtworkTests: XCTestCase {
    func testEveryLoadStateActuallyRenders() throws {
        for level in ["LOW", "MEDIUM", "HIGH", "UNKNOWN"] {
            let renderer = ImageRenderer(content: LoadEyeArtwork(level: level).frame(width: 300, height: 162).background(EyeStyle.background))
            renderer.scale = 1
            let image = try XCTUnwrap(renderer.uiImage?.cgImage)
            var bytes = [UInt8](repeating: 0, count: image.width * image.height * 4)
            try bytes.withUnsafeMutableBytes { buffer in
                let context = try XCTUnwrap(CGContext(data: buffer.baseAddress, width: image.width, height: image.height,
                    bitsPerComponent: 8, bytesPerRow: image.width * 4, space: CGColorSpaceCreateDeviceRGB(),
                    bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
                context.draw(image, in: CGRect(x: 0, y: 0, width: CGFloat(image.width), height: CGFloat(image.height)))
            }
            let visible = stride(from: 0, to: bytes.count, by: 4).filter { Int(bytes[$0]) + Int(bytes[$0 + 1]) + Int(bytes[$0 + 2]) > 180 }.count
            XCTAssertGreaterThan(visible, 1500, "负荷眼睛不能显示为空白：" + level)
        }
    }
}

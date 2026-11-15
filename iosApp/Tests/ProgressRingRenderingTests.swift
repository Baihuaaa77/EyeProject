import SwiftUI
import UIKit
import XCTest
@testable import EyeProject

/// 验证真实 SwiftUI 首帧像素，防止出现「刻度已亮、进度弧仍停在零」的回归。
@MainActor
final class ProgressRingRenderingTests: XCTestCase {
    func testNonzeroScoreIsVisibleOnFirstFrame() throws {
        for dark in [false, true] {
            for reduced in [false, true] {
                let image = try render(fraction: 0.79, dark: dark, reduced: reduced)
                let arc = try pixel(image, x: 6, y: 130)
                // 左侧弧线使用新的暖色数据渐变；空轨道、空白或只有内侧刻度均无法满足。
                XCTAssertGreaterThan(arc.r - arc.b, 0.18, "首帧必须直接绘出当前分数的进度弧")
                attach(image, name: "79分-\(dark ? "深色" : "浅色")-禁用动画\(reduced)")
            }
        }
    }

    func testZeroAndMissingScoreKeepNeutralTrackVisible() throws {
        for dark in [false, true] {
            for fraction: Double? in [nil, 0] {
                let image = try render(fraction: fraction, dark: dark, reduced: true)
                let track = try pixel(image, x: 6, y: 130)
                let background = try pixel(image, x: 0, y: 0)
                XCTAssertLessThan(abs(track.b - track.r), 0.15, "没有分数时不能伪造彩色进度")
                XCTAssertGreaterThan(abs(track.r - background.r) + abs(track.g - background.g) + abs(track.b - background.b),
                    0.18, "无数据和零分时也应保留可见轨道")
            }
        }
    }

    func testGlowExtendsPastRingBoundsWithoutFourFlatEdges() throws {
        for dark in [false, true] {
            let view = ProgressRing(fraction: 1, lineWidth: 12, startAngle: 0, sweepAngle: 360)
                .frame(width: 260, height: 260).padding(32).background(EyeStyle.background)
                .environment(\.colorScheme, dark ? .dark : .light)
                .transaction { $0.disablesAnimations = true }
            let renderer = ImageRenderer(content: view)
            renderer.scale = 1
            let image = try XCTUnwrap(renderer.uiImage)
            let background = try pixel(image, x: 0, y: 0)
            // 这四点位于原 260 方形画布之外；被裁切的版本只会留下纯背景。
            for (x, y) in [(30, 162), (293, 162), (162, 30), (162, 293)] {
                let halo = try pixel(image, x: x, y: y)
                XCTAssertGreaterThan(abs(halo.r - background.r) + abs(halo.g - background.g) + abs(halo.b - background.b),
                    0.03, "上下左右的光晕应延伸到圆环布局边界之外")
            }
            attach(image, name: "完整外晕-\(dark ? "深色" : "浅色")")
        }
    }

    private func render(fraction: Double?, dark: Bool, reduced: Bool) throws -> UIImage {
        let view = ProgressRing(fraction: fraction, lineWidth: 12, ticks: 40, showEndpoint: true,
            startAngle: 130, sweepAngle: 280, uniformTicks: true)
            .frame(width: 260, height: 260).background(EyeStyle.background)
            .environment(\.colorScheme, dark ? .dark : .light)
            .transaction { $0.disablesAnimations = reduced }
        let renderer = ImageRenderer(content: view)
        renderer.scale = 1
        return try XCTUnwrap(renderer.uiImage)
    }

    private func pixel(_ image: UIImage, x: Int, y: Int) throws -> (r: Double, g: Double, b: Double) {
        let cg = try XCTUnwrap(image.cgImage)
        var rgba = [UInt8](repeating: 0, count: cg.width * cg.height * 4)
        try rgba.withUnsafeMutableBytes { buffer in
            let context = try XCTUnwrap(CGContext(data: buffer.baseAddress, width: cg.width, height: cg.height,
                bitsPerComponent: 8, bytesPerRow: cg.width * 4, space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.premultipliedLast.rawValue))
            context.draw(cg, in: CGRect(x: 0, y: 0, width: CGFloat(cg.width), height: CGFloat(cg.height)))
        }
        let i = (y * cg.width + x) * 4
        return (Double(rgba[i]) / 255, Double(rgba[i + 1]) / 255, Double(rgba[i + 2]) / 255)
    }

    private func attach(_ image: UIImage, name: String) {
        let attachment = XCTAttachment(image: image)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}

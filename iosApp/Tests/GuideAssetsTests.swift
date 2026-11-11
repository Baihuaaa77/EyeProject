import SwiftUI
import UIKit
import XCTest
@testable import EyeProject

/// 验证 XcodeGen 真正把共用图片放进应用，并保留透明通道。
@MainActor
final class GuideAssetsTests: XCTestCase {
    func testEveryGuidePoseIsBundledWithExpectedBackground() throws {
        for pose in ["open", "blink", "left", "right", "lighting_open", "lighting_left", "lighting_right", "side_far", "side_near"] {
            let name = "eyecho_guide_" + pose
            let image = try XCTUnwrap(EyechoGuideImages.image(named: name)?.cgImage, "缺少爱科分镜：" + name)
            if ["open", "blink", "left", "right"].contains(pose) {
            XCTAssertTrue([CGImageAlphaInfo.premultipliedFirst, .premultipliedLast, .first, .last].contains(image.alphaInfo),
                          "人物图片必须保留透明背景：" + name)
            }
        }
    }

    /// 检查应用实际使用的 SwiftUI 图片，而不仅仅检查 UIImage 能解码。
    func testPortraitActuallyRendersInBothThemes() throws {
        for dark in [false, true] {
            for pose in ["open", "blink", "left", "right", "lighting_open", "lighting_left", "lighting_right", "side_far", "side_near"] {
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

    /// 完整舞台必须有人物；手机简图和文字不能代替这项检查。
    func testServiceStageKeepsPersonVisible() throws {
        for motion in [GuideMotion.near, .move, .cover, .coverSelf, .fva] {
            let renderer = ImageRenderer(content: GuideFrame(motion: motion, phase: 0.8).frame(width: 343, height: 190))
            renderer.scale = 1
            let image = try XCTUnwrap(renderer.uiImage)
            XCTAssertGreaterThan(try skinPixels(image, minX: 0, maxX: 343), 500, "示范舞台缺少爱科：\(motion)")
            attach(image, name: "服务页爱科-\(motion)")
        }
    }

    func testHelpAndCompletionViewsRender() async throws {
        for dark in [false, true] {
            let help = TestHelpView(motion: .coverSelf, title: "交替遮盖", cue: "本人按提示遮眼，协助者在手机旁打光。", onClose: {})
            let helpImage = try await windowImage(help, dark: dark)
            XCTAssertGreaterThan(try skinPixels(helpImage, minX: 0, maxX: helpImage.cgImage!.width), 500)
            attach(helpImage, name: "当前环节问号-\(dark ? "深色" : "浅色")")
        }
        let completion = ExerciseCompletionView(total: 8, seen: 7, usedCamera: true, onClose: {})
            .padding(20).background(EyeStyle.background.ignoresSafeArea())
        attach(try await windowImage(completion, dark: true), name: "护眼操完成页")
    }

    /// ScrollView 依赖 UIKit 托管；离屏 ImageRenderer 会漏掉滚动内容，因此使用真实模拟器窗口。
    private func windowImage<V: View>(_ view: V, dark: Bool) async throws -> UIImage {
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let previous = scene.windows.first { $0.isKeyWindow }
        let window = UIWindow(windowScene: scene)
        window.frame = UIScreen.main.bounds
        window.windowLevel = .alert + 1
        window.overrideUserInterfaceStyle = dark ? .dark : .light
        let content = view.environment(\.colorScheme, dark ? .dark : .light)
            .environment(\.accessibilityReduceMotion, true)
            .transaction { $0.disablesAnimations = true }
        let host = UIHostingController(rootView: content)
        window.rootViewController = host
        window.makeKeyAndVisible()
        defer { window.isHidden = true; window.rootViewController = nil; previous?.makeKey() }
        host.view.setNeedsLayout()
        host.view.layoutIfNeeded()
        try await Task.sleep(nanoseconds: 600_000_000)
        let renderer = UIGraphicsImageRenderer(bounds: window.bounds)
        return renderer.image { _ in window.drawHierarchy(in: window.bounds, afterScreenUpdates: true) }
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

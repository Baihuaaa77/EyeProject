import SwiftUI
import UIKit

/// 共用 PNG 是独立资源，显式按文件路径解码，避免 SwiftUI 名称查找在真机上返回空图。
/// 七个姿势各解码一次，分镜切换时复用位图，不在每一帧重复读取文件。
@MainActor
enum EyechoGuideImages {
    private static let cache = NSCache<NSString, UIImage>()

    static func image(named name: String) -> UIImage? {
        if let image = cache.object(forKey: name as NSString) { return image }
        let image: UIImage?
        if let url = Bundle.main.url(forResource: name, withExtension: "png") {
            image = UIImage(contentsOfFile: url.path)
        } else {
            image = UIImage(named: name, in: .main, compatibleWith: nil)
        }
        if let image { cache.setObject(image, forKey: name as NSString) }
        return image
    }
}

/// 测试引导、服务示范和静态准备提示共用真实解码后的图片，保留原色与透明通道。
struct EyechoGuideImage: View {
    let name: String
    var body: some View {
        if let image = EyechoGuideImages.image(named: name) {
            Image(uiImage: image).renderingMode(.original).resizable().scaledToFit()
        } else {
            Text("演示图片暂不可用").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
        }
    }
}

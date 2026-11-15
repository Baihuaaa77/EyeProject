import SwiftUI
import UIKit

/// DESIGN.md 的双端主题令牌：同色浮雕表面、中性文字与蓝青渐变数据。
enum EyeStyle {
    static let background = dyn(0x10110F, 0x10110F)
    static let optotypeSurround = dyn(0xFFFFFF, 0x1E2023, darkAlpha: 0)
    static let guideStageHeight: CGFloat = 200
    static let gazeCanvasHeight: CGFloat = 480
    static let gazeCanvasMinHeight: CGFloat = 160
    static let optotypeField = Color.white
    static func gazeCanvasBaseHeight(_ screenHeight: CGFloat) -> CGFloat {
        min(gazeCanvasHeight, max(gazeCanvasMinHeight, screenHeight * (screenHeight < gazeCanvasHeight ? 0.38 : 0.58)))
    }
    static let panel = dyn(0x22231E, 0x22231E)
    /// 浅填充：次要按钮、分段控件底、输入框、圆环底环。
    static let surfaceVariant = dyn(0x171814, 0x171814)
    static let guideBackground = Color(rgb: 0xE9EDF3)
    static let borderSubtle = dyn(0x2E3029, 0x2E3029)
    static let text = dyn(0xF3F0E9, 0xF3F0E9)

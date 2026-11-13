import SwiftUI
import UIKit

/// 独立实验 App 的界面令牌；刺激强度由实验配置决定，不使用界面主题做亮度标定。
enum LabStyle {
    static let background = Color(red: 16/255, green: 17/255, blue: 15/255)
    static let primary = Color(red: 246/255, green: 162/255, blue: 103/255)
    static let text = Color.white
    static let secondary = Color.gray
    static let candidate = Color.orange
    static let coded = Color.green
    static let stimulusBackground = UIColor.black
    static let title = Font.title2.weight(.semibold)
    static let body = Font.body
    static let detail = Font.caption
    static let radius: CGFloat = 16
}

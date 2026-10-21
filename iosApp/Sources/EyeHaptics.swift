import UIKit

/// 页面操作的轻触觉（测试中的作答反馈另有分级，见 EyeFeedback）。
/// 点按 / 开关轻触，操作成功与失败各一种；跟随系统「系统触感反馈」设置。与安卓 EyeHaptics 同一分级。
enum EyeHaptics {
    /// 点按入口、按钮、选项。
    static func tap() { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
    /// 开关切换。
    static func toggle() { UISelectionFeedbackGenerator().selectionChanged() }
    /// 保存、登录、同步等操作成功。
    static func success() { UINotificationFeedbackGenerator().notificationOccurred(.success) }
    /// 操作失败或输入有误。
    static func failure() { UINotificationFeedbackGenerator().notificationOccurred(.error) }
}

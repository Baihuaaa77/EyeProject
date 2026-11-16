import Foundation

enum FixationPattern: String, Codable, CaseIterable, Identifiable {
    case ringDot, crossDot
    var id: String { rawValue }
    var title: String { self == .ringDot ? "缺口圆环＋中心点" : "短尾十字＋中心点" }
    static let centerFraction = 0.2

    // 只保留历史记录的枚举与中心尺寸约定，不再提供图案刺激或模板匹配。
}

struct PatternMatch: Codable {
    var correlation: Double
    var alternativeCorrelation: Double
    var diameterPixels: Double
    var angleRadians: Double
    var resolved: Bool
}

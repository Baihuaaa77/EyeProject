import SwiftUI

/// StrabismusLab 独立临床光学实验室设计令牌（与 Android LabTheme.kt 1:1 严格对齐）。
enum LabPalette {
    static let canvas = Color(hex: 0x090C10)
    static let stimulusBlack = Color.black
    static let surface = Color(hex: 0x12171F)
    static let surfaceElevated = Color(hex: 0x1A212C)
    static let surfaceSubtle = Color(hex: 0x0E131A)
    static let hairline = Color(hex: 0x242E3D)
    static let hairlineStrong = Color(hex: 0x334155)

    static let inkPrimary = Color(hex: 0xF1F5F9)
    static let inkSecondary = Color(hex: 0x94A3B8)
    static let inkTertiary = Color(hex: 0x64748B)

    static let accent = Color(hex: 0x38BDF8)
    static let accentSoft = Color(hex: 0x38BDF8).opacity(0.16)
    static let onAccent = Color(hex: 0x06131D)

    static let rightEye = Color(hex: 0xF59E0B)
    static let rightEyeSoft = Color(hex: 0xF59E0B).opacity(0.16)

    static let verified = Color(hex: 0x22C55E)
    static let caution = Color(hex: 0xFBBF24)
    static let danger = Color(hex: 0xF43F5E)
}

enum LabFont {
    static let title = Font.system(size: 22, weight: .semibold)
    static let section = Font.system(size: 15, weight: .semibold)
    static let body = Font.system(size: 14, weight: .regular)
    static let caption = Font.system(size: 12, weight: .medium)
    static let metricHero = Font.system(size: 25, weight: .bold, design: .monospaced)
    static let metricMono = Font.system(size: 14, weight: .semibold, design: .monospaced)
    static let microMono = Font.system(size: 11, weight: .medium, design: .monospaced)
}

extension Color {
    init(hex: UInt32, alpha: Double = 1.0) {
        let r = Double((hex >> 16) & 0xFF) / 255.0
        let g = Double((hex >> 8) & 0xFF) / 255.0
        let b = Double(hex & 0xFF) / 255.0
        self.init(.sRGB, red: r, green: g, blue: b, opacity: alpha)
    }
}

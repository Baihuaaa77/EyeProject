import SwiftUI
import UIKit

/// 用户参考稿的暖棕材质，所有新页面复用同一套令牌。
enum BrownStyle {
    static let sage = Color(rgb: 0xB6C6B3)
    static let terracotta = Color(rgb: 0xED9A78)
    static let rail = Color(rgb: 0x52524A)
    static let marker = Color(rgb: 0xFFE8CF)
    static let hero = LinearGradient(colors: [Color(rgb: 0x2D251D), Color(rgb: 0x211B16), Color(rgb: 0x191A16)], startPoint: .topLeading, endPoint: .bottomTrailing)
    static let trend = LinearGradient(colors: [Color(rgb: 0x232923), Color(rgb: 0x181A17)], startPoint: .top, endPoint: .bottom)
    static let consult = LinearGradient(colors: [Color(rgb: 0x503624), Color(rgb: 0x30261E)], startPoint: .topLeading, endPoint: .bottomTrailing)
    static let feeling = LinearGradient(colors: [Color(rgb: 0x443027), Color(rgb: 0x2B2420)], startPoint: .topLeading, endPoint: .bottomTrailing)
    static let profile = LinearGradient(colors: [Color(rgb: 0x3E3022), Color(rgb: 0x22221D)], startPoint: .topLeading, endPoint: .bottomTrailing)
    static let surface = LinearGradient(colors: [Color(rgb: 0x242420), Color(rgb: 0x1B1C19)], startPoint: .topLeading, endPoint: .bottomTrailing)
    static let avatar = LinearGradient(colors: [Color(rgb: 0x6E4E36), Color(rgb: 0x3A2C22)], startPoint: .topLeading, endPoint: .bottomTrailing)
    static func tile(_ index: Int) -> LinearGradient {
        let values: [UInt32] = switch index {
        case 1: [0x312721, 0x28221F]
        case 3: [0x302A26, 0x262320]
        default: [0x302920, 0x25241F]
        }
        return LinearGradient(colors: values.map { Color(rgb: $0) }, startPoint: .topLeading, endPoint: .bottomTrailing)
    }
}

struct WarmCard<Content: View>: View {
    var fill = BrownStyle.surface
    var padding: CGFloat = 18
    var spacing: CGFloat = 6
    @ViewBuilder let content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: spacing) { content }.frame(maxWidth: .infinity, alignment: .leading)
            .padding(padding).eyeGlass(cornerRadius: EyeStyle.radius, fill: AnyShapeStyle(fill))
    }
}

struct LoadEyeArtwork: View {
    let level: String
    private static let images: [String: UIImage] = {
        var result: [String: UIImage] = [:]
        for tone in ["sage", "amber", "rose", "empty"] {
            if let url = Bundle.main.url(forResource: "load_eye_\(tone)", withExtension: "png"), let image = UIImage(contentsOfFile: url.path) { result[tone] = image }
        }
        return result
    }()
    private var image: UIImage? {
        let suffix: String = switch level { case "LOW": "sage"; case "MEDIUM": "amber"; case "HIGH": "rose"; default: "empty" }
        return Self.images[suffix]
    }
    var body: some View {
        if let image { Image(uiImage: image).resizable().scaledToFit().accessibilityHidden(true) }
    }
}

struct LoadRail: View {
    let score: Int?
    var body: some View {
        Canvas { context, size in
            let y = size.height / 2
            var rail = Path(); rail.move(to: CGPoint(x: 0, y: y)); rail.addLine(to: CGPoint(x: size.width, y: y))
            context.stroke(rail, with: .color(BrownStyle.rail), style: StrokeStyle(lineWidth: 5, lineCap: .round))
            if let score {
                let x = size.width * CGFloat(min(100, max(0, score))) / 100
                var fill = Path(); fill.move(to: CGPoint(x: 0, y: y)); fill.addLine(to: CGPoint(x: x, y: y))
                context.stroke(fill, with: .color(EyeStyle.primary), style: StrokeStyle(lineWidth: 5, lineCap: .round))
            }
            for i in 1...2 {
                let x = size.width * CGFloat(i) / 3
                context.fill(Path(CGRect(x: x - 1.5, y: y - 4, width: 3, height: 8)), with: .color(EyeStyle.heroEnd))
            }
            if let score {
                let x = size.width * CGFloat(min(100, max(0, score))) / 100
                context.fill(Path(ellipseIn: CGRect(x: x - 7, y: y - 7, width: 14, height: 14)), with: .color(EyeStyle.heroEnd))
                context.fill(Path(ellipseIn: CGRect(x: x - 5.5, y: y - 5.5, width: 11, height: 11)), with: .color(EyeStyle.primary))
                context.fill(Path(ellipseIn: CGRect(x: x - 4, y: y - 4, width: 8, height: 8)), with: .color(BrownStyle.marker))
            }
        }.frame(height: 16).accessibilityLabel(score.map { "用眼负荷 \($0)，满分 100" } ?? "暂无用眼负荷数据")
    }
}

/// 有效记录按相邻日期连续绘制，空数据不生成装饰性的虚假曲线。
struct TrendRibbon: View {
    let values: [Int?]
    private var phases: [Int?] {
        [Array(values.prefix(2)), Array(values.dropFirst(2).prefix(3)), Array(values.dropFirst(5).prefix(2))].map { phase in
            let valid = phase.compactMap { $0 }; return valid.isEmpty ? nil : valid.reduce(0, +) / valid.count
        }
    }
    var body: some View {
        ZStack {
            if phases.allSatisfy({ $0 == nil }) { Text("暂无趋势记录").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary) }
            else {
                Canvas { context, size in
                    var spans: [[CGPoint]] = []; var run: [CGPoint] = []
                    for (i, value) in phases.enumerated() {
                        if let value { run.append(CGPoint(x: size.width * (CGFloat(i) + 0.5) / CGFloat(max(1, phases.count)), y: size.height * (0.76 - 0.53 * CGFloat(value) / 100))) }
                        else if !run.isEmpty { spans.append(run); run = [] }
                    }
                    if !run.isEmpty { spans.append(run) }
                    let shading = GraphicsContext.Shading.linearGradient(Gradient(colors: [BrownStyle.sage, EyeStyle.primary, BrownStyle.terracotta]), startPoint: .zero, endPoint: CGPoint(x: size.width, y: 0))
                    for span in spans {
                        guard let first = span.first, let last = span.last else { continue }
                        let left = CGPoint(x: max(0, first.x - size.width / CGFloat(phases.count) * 0.48), y: first.y + 4)
                        let right = CGPoint(x: min(size.width, last.x + size.width / CGFloat(phases.count) * 0.48), y: last.y - 2)
                        let top = [left] + span.map { CGPoint(x: $0.x, y: $0.y - 6) } + [right]
                        let bottom = [right] + span.reversed().map { CGPoint(x: $0.x, y: $0.y + 6) } + [left]
                        var path = Path(); path.move(to: left)
                        for points in [top, bottom] {
                            for i in 0..<(points.count - 1) {
                                let a = points[i], b = points[i + 1], x = (a.x + b.x) / 2
                                path.addCurve(to: b, control1: CGPoint(x: x, y: a.y), control2: CGPoint(x: x, y: b.y))
                            }
                        }
                        path.closeSubpath()
                        var glow = context; glow.addFilter(.blur(radius: 12)); glow.opacity = 0.5; glow.translateBy(x: 0, y: 12)
                        glow.fill(path, with: shading); context.fill(path, with: shading)
                    }
                }
            }
        }.frame(height: 94).accessibilityLabel("本周用眼负荷趋势")
    }
}

struct WeekUsageCard: View {
    let values: [Int?]
    let labels: [String]
    let todayIndex: Int
    @State private var selection: Int? = nil
    private var selected: Int { selection ?? min(6, max(0, todayIndex)) }
    private func amount(_ i: Int) -> Int? { values.indices.contains(i) ? values[i] : nil }
    var body: some View {
        WarmCard(fill: BrownStyle.hero) {
            HStack {
                Text("手机使用时长").font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text)
                Spacer(); Text("本周").font(EyeStyle.caption).foregroundStyle(EyeStyle.secondary).padding(.horizontal, 10).padding(.vertical, 4).background(EyeStyle.surfaceVariant, in: Capsule())
            }
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                EyeStyle.reading(amount(selected).map { "\($0 / 60)小时\(String(format: "%02d", $0 % 60))分" } ?? "—")
                Text(labels.indices.contains(selected) ? labels[selected] : "").font(EyeStyle.caption).foregroundStyle(EyeStyle.secondary)
            }
            HStack(spacing: 0) {
                ForEach(0..<7) { i in
                    Button { selection = i } label: {
                        VStack(spacing: 6) {
                            ZStack(alignment: .bottom) {
                                Capsule().fill(BrownStyle.rail)
                                if let value = amount(i), value > 0 {
                                    Capsule().fill(i == selected ? EyeStyle.primary : EyeStyle.ice.opacity(0.72))
                                        .frame(height: 64 * min(1, CGFloat(value) / CGFloat(max(60, values.compactMap { $0 }.max() ?? 60)) * 0.9))
                                }
                            }.frame(width: 22, height: 64)
                                .overlay { if i == selected { Capsule().stroke(EyeStyle.primary, lineWidth: 1.5) } }
                            Text(labels.indices.contains(i) ? labels[i] : "").font(EyeStyle.caption).foregroundStyle(i == selected ? EyeStyle.primary : EyeStyle.secondary)
                        }.padding(.vertical, 6).frame(maxWidth: .infinity)
                    }.buttonStyle(.plain).accessibilityLabel("\(labels.indices.contains(i) ? labels[i] : "")，\(amount(i).map { "\($0)分钟" } ?? "未记录")")
                }
            }
        }
    }
}

struct WarmDisclosure<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content
    @State private var expanded = false
    var body: some View {
        DisclosureGroup(isExpanded: $expanded) { content } label: {
            Text(title).font(EyeStyle.body).foregroundStyle(EyeStyle.text).frame(minHeight: 44)
        }.tint(EyeStyle.primary).padding(16).eyeGlass()
    }
}

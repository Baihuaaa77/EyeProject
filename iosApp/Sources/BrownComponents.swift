import SwiftUI
import UIKit

/// 用户参考稿的暖棕材质，所有新页面复用同一套令牌。
enum BrownStyle {
    static let loadLow = Color(rgb: 0x01FE00)
    static let loadMedium = Color(rgb: 0xFF7900)
    static let loadHigh = Color(rgb: 0xFF0000)
    static func loadColor(_ score: Int) -> Color {
        if score < 30 { return loadLow }
        if score < 60 { return loadMedium }
        return loadHigh
    }
    static func loadTrackColor(_ score: Int) -> Color { (30..<60).contains(score) ? EyeStyle.primary : loadColor(score) }

    static let sage = Color(rgb: 0xB6C6B3)
    static let terracotta = Color(rgb: 0xED9A78)
    static let rail = Color(rgb: 0x373737)
    static let marker = Color(rgb: 0xFFE8CF)
    static let surface = LinearGradient(stops: [
        .init(color: EyeStyle.heroStart, location: 0),
        .init(color: Color(rgb: 0x33261C), location: 0.64),
        .init(color: EyeStyle.heroEnd, location: 1)
    ], startPoint: .topLeading, endPoint: .bottomTrailing)
    static let pressureInk = Color(rgb: 0x20281B)
    static let pressureText = Color(rgb: 0x303B27)
    static let pressurePaper = Color(rgb: 0xEFF0D9)
    static let avatar = LinearGradient(colors: [Color(rgb: 0x6E4E36), Color(rgb: 0x3A2C22)], startPoint: .topLeading, endPoint: .bottomTrailing)
}

struct WarmCard<Content: View>: View {
    var fill = BrownStyle.surface
    var padding: CGFloat = 18
    var spacing: CGFloat = 6
    var edge: Color? = nil
    @ViewBuilder let content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: spacing) { content }.frame(maxWidth: .infinity, alignment: .leading)
            .padding(padding).eyeGlass(cornerRadius: EyeStyle.radius, fill: AnyShapeStyle(fill), edgeTop: edge ?? EyeStyle.glassEdgeTop)
    }
}

struct LoadEyeArtwork: View {
    let level: String
    var tone: Color? = nil
    private static let images: [String: UIImage] = {
        var result: [String: UIImage] = [:]
        for tone in ["sage", "amber", "rose", "empty"] {
            if let url = Bundle.main.url(forResource: "load_eye_\(tone)", withExtension: "png"), let image = UIImage(contentsOfFile: url.path) { result[tone] = image }
        }
        return result
    }()
    private var image: UIImage? {
        let suffix: String = switch level { case "LOW": "sage"; case "MEDIUM": "amber"; case "HIGH": "rose"; default: "empty" }
        return Self.images[tone == nil ? suffix : "empty"]
    }
    var body: some View {
        if let image {
            Image(uiImage: image).resizable().scaledToFit()
                .saturation(tone == nil ? 1 : 0).colorMultiply(tone ?? .white).accessibilityHidden(true)
        }
    }
}

struct LoadRail: View {
    let score: Int?
    var tone = EyeStyle.primary
    var body: some View {
        Canvas { context, size in
            let y = size.height / 2
            var rail = Path(); rail.move(to: CGPoint(x: 0, y: y)); rail.addLine(to: CGPoint(x: size.width, y: y))
            context.stroke(rail, with: .color(BrownStyle.rail), style: StrokeStyle(lineWidth: 5, lineCap: .round))
            if let score {
                let x = size.width * CGFloat(min(100, max(0, score))) / 100
                var fill = Path(); fill.move(to: CGPoint(x: 0, y: y)); fill.addLine(to: CGPoint(x: x, y: y))
                context.stroke(fill, with: .color(tone), style: StrokeStyle(lineWidth: 5, lineCap: .round))
            }
            for i in 1...2 {
                let x = size.width * CGFloat(i) / 3
                context.fill(Path(CGRect(x: x - 1.5, y: y - 4, width: 3, height: 8)), with: .color(EyeStyle.heroEnd))
            }
            if let score {
                let x = size.width * CGFloat(min(100, max(0, score))) / 100
                context.fill(Path(ellipseIn: CGRect(x: x - 7, y: y - 7, width: 14, height: 14)), with: .color(EyeStyle.heroEnd))
                context.fill(Path(ellipseIn: CGRect(x: x - 4, y: y - 4, width: 8, height: 8)), with: .color(tone))
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
        WarmCard {
            HStack {
                Text("手机使用时长").font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text)
                Spacer(); Text("本周").font(EyeStyle.caption).foregroundStyle(EyeStyle.secondary).padding(.horizontal, 10).padding(.vertical, 4).background(EyeStyle.surfaceVariant, in: Capsule())
            }
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                EyeStyle.reading(amount(selected).map { "\($0 / 60)小时\(String(format: "%02d", $0 % 60))分" } ?? "—")
                Text(labels.indices.contains(selected) ? labels[selected] : "").font(EyeStyle.caption).foregroundStyle(EyeStyle.secondary)
            }
            let recorded = values.compactMap { $0 }
            let maxMinutes = max(60, recorded.max() ?? 0)
            ZStack(alignment: .top) {
                HStack(spacing: 0) {
                    ForEach(0..<7) { i in
                        Button { selection = i } label: {
                            VStack(spacing: 6) {
                                ZStack(alignment: .bottom) {
                                    Capsule().fill(BrownStyle.rail)
                                    if let value = amount(i), value > 0 {
                                        Capsule().fill(i == selected ? EyeStyle.primary : EyeStyle.primary.opacity(0.45))
                                            .frame(height: 64 * min(1, CGFloat(value) / CGFloat(max(60, values.compactMap { $0 }.max() ?? 60)) * 0.9))
                                    }
                                }.frame(width: 22, height: 64)
                                    .overlay { if i == selected { Capsule().stroke(EyeStyle.primary, lineWidth: 1.5) } }
                                Text(labels.indices.contains(i) ? labels[i] : "").font(EyeStyle.caption).foregroundStyle(i == selected ? EyeStyle.primary : EyeStyle.secondary)
                            }.padding(.vertical, 6).frame(maxWidth: .infinity)
                        }.buttonStyle(.plain).accessibilityLabel("\(labels.indices.contains(i) ? labels[i] : "")，\(amount(i).map { "\($0)分钟" } ?? "未记录")")
                    }
                }
                Canvas { context, size in
                    let points: [CGPoint?] = (0..<7).map { i in
                        amount(i).map { value in
                            CGPoint(x: size.width * (CGFloat(i) + 0.5) / 7,
                                    y: min(size.height - 2, size.height * (1 - 0.9 * CGFloat(value) / CGFloat(maxMinutes))))
                        }
                    }
                    for (a, b) in zip(points, points.dropFirst()) {
                        if let a, let b {
                            var line = Path()
                            line.move(to: a)
                            line.addLine(to: b)
                            context.stroke(line, with: .color(EyeStyle.primary), style: StrokeStyle(lineWidth: 2, lineCap: .round))
                        }
                    }
                    for point in points.compactMap({ $0 }) {
                        context.fill(Path(ellipseIn: CGRect(x: point.x - 3, y: point.y - 3, width: 6, height: 6)), with: .color(EyeStyle.primary))
                    }
                }.frame(height: 64).padding(.top, 6).allowsHitTesting(false).accessibilityHidden(true)
            }
            if !recorded.isEmpty {
                let average = recorded.reduce(0, +) / recorded.count
                Text("已记录 \(recorded.count) 天 · 日均 \(average / 60) 小时 \(average % 60) 分")
                    .font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
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

import AVFoundation
import SwiftUI

// DESIGN.md：阴影只作用于表面形状，前景文字与图标保持清晰。
struct EyeGlassModifier: ViewModifier {
    var cornerRadius: CGFloat
    var fill: AnyShapeStyle?
    var edgeTop: Color = EyeStyle.glassEdgeTop
    var shadow = true
    var pressed = false

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        content.clipShape(shape)
            .background {
                if pressed {
                    shape.fill(EyeStyle.panel
                        .shadow(.inner(color: EyeStyle.shadow, radius: 5, x: 2, y: 2))
                        .shadow(.inner(color: EyeStyle.shadowLight, radius: 5, x: -2, y: -2)))
                } else {
                    shape.fill(fill ?? AnyShapeStyle(EyeStyle.glassFill))
                        .shadow(color: EyeStyle.shadowLight, radius: 16, x: -6, y: -6)
                        .shadow(color: EyeStyle.shadow, radius: 22, x: 8, y: 8)
                }
            }
            .overlay { shape.stroke(EyeStyle.glassEdgeTop, lineWidth: 1).allowsHitTesting(false) }
    }
}

extension View {
    func eyeGlass(cornerRadius: CGFloat = EyeStyle.radius, fill: AnyShapeStyle? = nil, edgeTop: Color = EyeStyle.glassEdgeTop,
                  shadow: Bool = true, pressed: Bool = false) -> some View {
        modifier(EyeGlassModifier(cornerRadius: cornerRadius, fill: fill, edgeTop: edgeTop, shadow: shadow, pressed: pressed))
    }
}

struct EyeBackdrop: View {
    var body: some View { EyeStyle.background.ignoresSafeArea() }
}

// MARK: - 卡片

/// 主卡：每页一张，纯色面、大圆角。
struct EyeHeroCard<Content: View>: View {
    var padding: CGFloat = EyeSpacing.heroPadding
    var spacing: CGFloat = EyeSpacing.itemGap
    @ViewBuilder let content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: spacing) { content }
            .padding(padding).frame(maxWidth: .infinity, alignment: .leading)
            .eyeGlass(cornerRadius: EyeStyle.radiusHero, fill: AnyShapeStyle(EyeStyle.heroStart))
    }
}

/// 普通卡片：平铺玻璃面。[tinted] 为 true 时用浅色块底。
struct EyeCard<Content: View>: View {
    var padding: CGFloat = EyeSpacing.cardPadding
    var spacing: CGFloat = EyeSpacing.itemGap
    var tinted = false
    @ViewBuilder let content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: spacing) { content }
            .padding(padding).frame(maxWidth: .infinity, alignment: .leading)
            .eyeGlass(fill: tinted ? AnyShapeStyle(EyeStyle.surfaceVariant) : nil)
    }
}

/// 卡片分类头：分类色小图标 + 分类色标题，右侧可放操作（安卓 EyeCategoryHeader）。
struct CategoryHeader<Trailing: View>: View {
    let title: String
    let glyph: EyeGlyph
    var tone: TileTone = .primary
    @ViewBuilder var trailing: Trailing
    var body: some View {
        // 卡片标题：主色小图标 + 卡片标题字号（18）的主文字，与正文、辅助信息拉开层级
        HStack(spacing: EyeSpacing.xs) {
            EyeGlyphView(glyph: glyph, color: tone.colors.1).frame(width: 20, height: 20)
            Text(title).font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text)
            Spacer(minLength: 8)
            trailing
        }
    }
}

extension CategoryHeader where Trailing == EmptyView {
    init(title: String, glyph: EyeGlyph, tone: TileTone = .primary) {
        self.init(title: title, glyph: glyph, tone: tone) { EmptyView() }
    }
}

/// 一项读数：标签在上，数值在下（大号圆体数字 + 小一半的灰色单位）。[attention] 时标签后带琥珀小圆点。
struct MetricView: View {
    let label: String
    let value: String
    var unit: String? = nil
    var valueColor: Color = EyeStyle.text
    var attention = false
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 6) {
                Text(label).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary).lineLimit(1)
                if attention { Circle().fill(EyeStyle.accent).frame(width: 6, height: 6) }
            }
            EyeStyle.reading(value, unit: unit, color: valueColor).lineLimit(1).minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

/// 竖向细分隔线（同卡并排读数之间）。
struct VDivider: View {
    var body: some View { Rectangle().fill(EyeStyle.borderSubtle).frame(width: 0.5).padding(.horizontal, 14) }
}

// MARK: - 圆环与 AI 标识

struct ProgressRing: View {
    let fraction: Double?
    var lineWidth: CGFloat = 9
    var tint: Color? = nil
    /// 圆环内侧的刻度数（0 不画）：每 5 根一根长刻度，进度内的刻度用光流色点亮（与安卓 drawRingTicks 一致）。
    var ticks: Int = 0
    var showEndpoint = false
    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceMotion) private var reduced
    @State private var shown: Double = 0
    var body: some View {
        let f = max(0, min(1, shown))
        ZStack {
            Circle().stroke(EyeStyle.surfaceVariant, lineWidth: lineWidth)
            if fraction != nil {
                Circle().trim(from: 0, to: f)
                    .stroke(tint.map { AnyShapeStyle($0) } ?? AnyShapeStyle(EyeStyle.ringGradient), style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                    .overlay {
                        if tint == nil {
                            Circle().trim(from: 0, to: f).stroke(Color.white.opacity(EyeStyle.glowCoreOpacity),
                                style: StrokeStyle(lineWidth: lineWidth * 0.18, lineCap: .round))
                        }
                    }
                    .shadow(color: tint == nil ? EyeStyle.primary.opacity(EyeStyle.glowOpacity) : .clear, radius: EyeStyle.glowRadius)
                    .shadow(color: tint == nil ? EyeStyle.glow : .clear, radius: 3)
                if showEndpoint && f > 0 {
                    GeometryReader { geometry in
                        let radius = min(geometry.size.width, geometry.size.height) / 2
                        Circle().fill(Color.white).frame(width: lineWidth * 0.56, height: lineWidth * 0.56)
                            .position(x: geometry.size.width / 2 + cos(f * 2 * .pi) * radius,
                                      y: geometry.size.height / 2 + sin(f * 2 * .pi) * radius)
                    }.allowsHitTesting(false).accessibilityHidden(true)
                }
            }
        }
        .rotationEffect(.degrees(-90))
        .padding(lineWidth / 2)
        .background {
            if ticks > 0 {
                Canvas { ctx, size in
                    let side = min(size.width, size.height)
                    let c = CGPoint(x: size.width / 2, y: size.height / 2)
                    let ringRadius = (side - lineWidth * 3.2) / 2 + lineWidth * 0.8
                    let outer = ringRadius - lineWidth / 2 - lineWidth * 0.9
                    for i in 0..<ticks {
                        let major = i % 5 == 0
                        let inner = outer - (major ? lineWidth * 1.1 : lineWidth * 0.6)
                        let t = Double(i) / Double(ticks)
                        let angle = (t * 360 - 90) * .pi / 180
                        let lit = fraction != nil && t <= shown
                        var line = Path()
                        line.move(to: CGPoint(x: c.x + cos(angle) * inner, y: c.y + sin(angle) * inner))
                        line.addLine(to: CGPoint(x: c.x + cos(angle) * outer, y: c.y + sin(angle) * outer))
                        let color = lit ? (t / max(shown, 0.01) < 0.5 ? EyeStyle.flowStart : EyeStyle.flowEnd)
                            : EyeStyle.tertiary.opacity(major ? 0.55 : 0.28)
                        ctx.stroke(line, with: .color(color), style: StrokeStyle(lineWidth: major ? lineWidth * 0.2 : lineWidth * 0.13, lineCap: .round))
                    }
                }
            }
        }
        .onAppear { withAnimation(reduced ? nil : .timingCurve(0.22, 1, 0.36, 1, duration: 0.9)) { shown = fraction ?? 0 } }
        .onChange(of: fraction ?? 0) { value in withAnimation(reduced ? nil : .timingCurve(0.22, 1, 0.36, 1, duration: 0.9)) { shown = value } }
    }
}

/// AI 标识：浅主色块上的主色声波线（2026-11 改版后两套主题一致，不再用发光光球）。与安卓 EyeAiMark 一致。
struct AiMark: View {
    var size: CGFloat = 40
    var body: some View {
        Image(systemName: "waveform").font(.system(size: size * 0.42, weight: .semibold)).foregroundStyle(EyeStyle.primary)
            .frame(width: size, height: size).background(EyeStyle.primarySoft, in: RoundedRectangle(cornerRadius: EyeStyle.radiusSmall, style: .continuous))
    }
}

/// 品牌标志视图（启动画面、欢迎页）。
struct BrandMark: View {
    var size: CGFloat = 88
    var body: some View {
        Canvas { context, canvas in
            let side = min(canvas.width, canvas.height)
            Cartoon.brandMark(context, origin: CGPoint(x: (canvas.width - side) / 2, y: (canvas.height - side) / 2), side: side)
        }
        .frame(width: size, height: size)
        .accessibilityLabel("眼健康助手标志")
    }
}

// MARK: - 提示音

/// 护眼操与休息页的提示音（开始、换动作、倒数、完成），复用测试提示音素材；与测试音效同一套开关与音量。
/// 不在测试中时用「环境音」类别：跟随静音键、与其他应用的声音混合，不打断音乐。
enum EyeCue: String { case start = "eye_start", phase = "eye_phase", count = "eye_count", complete = "eye_complete" }

final class EyeCueSound {
    static let shared = EyeCueSound()
    private var players: [String: AVAudioPlayer] = [:]

    func play(_ cue: EyeCue) {
        let defaults = UserDefaults.standard
        guard defaults.object(forKey: "eye.soundEnabled") as? Bool ?? true else { return }
        let session = AVAudioSession.sharedInstance()
        if session.category == .soloAmbient { try? session.setCategory(.ambient, options: [.mixWithOthers]) }
        guard let player = player(cue.rawValue) else { return }
        player.volume = Float(0.7 * (defaults.object(forKey: "eye.soundVolume") as? Double ?? 1))
        player.currentTime = 0
        player.play()
    }

    private func player(_ name: String) -> AVAudioPlayer? {
        if let cached = players[name] { return cached }
        guard let url = Bundle.main.url(forResource: name, withExtension: "wav"),
              let created = try? AVAudioPlayer(contentsOf: url) else { return nil }
        created.prepareToPlay()
        players[name] = created
        return created
    }
}

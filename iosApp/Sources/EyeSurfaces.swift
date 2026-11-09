import AVFoundation
import SwiftUI
import UIKit

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
    func eyeLiquidGlass(cornerRadius: CGFloat = EyeStyle.radius) -> some View {
        modifier(EyeLiquidGlassModifier(cornerRadius: cornerRadius))
    }
    /// 与安卓同色同强度的卡片细光边，装饰层不拦截操作。
    func eyeGlowBorder(cornerRadius: CGFloat = EyeStyle.radius, strength: Double = 1) -> some View {
        modifier(EyeGlowBorderModifier(cornerRadius: cornerRadius, strength: strength))
    }

    func eyeGlass(cornerRadius: CGFloat = EyeStyle.radius, fill: AnyShapeStyle? = nil, edgeTop: Color = EyeStyle.glassEdgeTop,
                  shadow: Bool = true, pressed: Bool = false) -> some View {
        modifier(EyeGlassModifier(cornerRadius: cornerRadius, fill: fill, edgeTop: edgeTop, shadow: shadow, pressed: pressed))
    }
}

/// iOS 26 接入原生 Liquid Glass；早期系统使用系统材质与镜片高光兼容样式。
private struct EyeLiquidGlassModifier: ViewModifier {
    let cornerRadius: CGFloat
    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceTransparency) private var reducedTransparency
    @Environment(\.accessibilityReduceMotion) private var reducedMotion
    @ViewBuilder func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        if reducedTransparency {
            content.background(EyeStyle.panel, in: shape)
                .overlay { shape.stroke(EyeStyle.borderSubtle, lineWidth: EyeStyle.liquidRimWidth).allowsHitTesting(false) }
        } else if #available(iOS 26.0, *) {
            content.glassEffect(.regular.tint(EyeStyle.primary.opacity(EyeStyle.liquidTintLight)).interactive(!reducedMotion), in: shape)
        } else {
            let reflection = scheme == .dark ? EyeStyle.liquidReflectionDark : EyeStyle.liquidReflectionLight
            let edge = scheme == .dark ? EyeStyle.liquidEdgeDark : EyeStyle.liquidEdgeLight
            content.background {
                shape.fill(.ultraThinMaterial)
                    .overlay { shape.fill(EyeStyle.primary.opacity(scheme == .dark ? EyeStyle.liquidTintDark : EyeStyle.liquidTintLight)) }
                    .overlay { shape.fill(RadialGradient(colors: [.white.opacity(reflection), .clear],
                        center: .topLeading, startRadius: 0, endRadius: EyeStyle.liquidReflectionRadius)) }
            }
            .overlay {
                shape.inset(by: EyeStyle.liquidRimWidth / 2).stroke(LinearGradient(colors: [
                    .white.opacity(edge), EyeStyle.primary.opacity(edge * 0.22), .white.opacity(edge * 0.75)
                ], startPoint: .topLeading, endPoint: .bottomTrailing), lineWidth: EyeStyle.liquidRimWidth)
                    .allowsHitTesting(false).accessibilityHidden(true)
            }
            .clipShape(shape)
            .shadow(color: EyeStyle.shadow.opacity(EyeStyle.liquidShadowOpacity), radius: EyeStyle.liquidShadowRadius, x: 0, y: EyeStyle.liquidShadowOffset)
        }
    }
}

private struct EyeGlowBorderModifier: ViewModifier {
    let cornerRadius: CGFloat
    let strength: Double
    @Environment(\.colorScheme) private var scheme
    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous).inset(by: EyeStyle.cardBorderInset)
        let gradient = EyeStyle.borderGradient
        let opacity = (scheme == .dark ? EyeStyle.cardBorderOpacityDark : EyeStyle.cardBorderOpacityLight) * strength
        content.overlay {
            ZStack {
                shape.stroke(gradient, lineWidth: EyeStyle.cardBorderWidth * 2)
                    .opacity(opacity * EyeStyle.cardGlowOpacity).blur(radius: EyeStyle.cardGlowRadius)
                shape.stroke(gradient, lineWidth: EyeStyle.cardBorderWidth).opacity(opacity)
            }.clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
                .allowsHitTesting(false).accessibilityHidden(true)
        }
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
            .eyeGlowBorder(cornerRadius: EyeStyle.radiusHero)
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
            .eyeGlowBorder()
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
                Text(label).font(EyeStyle.label).foregroundStyle(EyeStyle.secondary).lineLimit(1)
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

/// 直接使用当前分数绘制首帧；后续变化由独立的 Animatable 图层插值，不依赖 onAppear 初始化。
struct ProgressRing: View {
    let fraction: Double?
    var lineWidth: CGFloat = 9
    var tint: Color? = nil
    var ticks: Int = 0
    var showEndpoint = false
    var startAngle: Double = -90
    var sweepAngle: Double = 360
    var uniformTicks = false
    @Environment(\.accessibilityReduceMotion) private var reduced

    var body: some View {
        let value = fraction.map { $0.isFinite ? min(1, max(0, $0)) : 0 } ?? 0
        ProgressRingDrawing(value: value, lineWidth: lineWidth, tint: tint, ticks: ticks,
            showEndpoint: showEndpoint, startAngle: startAngle, sweepAngle: sweepAngle, uniformTicks: uniformTicks)
            .animation(reduced ? nil : .easeOut(duration: 0.9), value: value)
    }
}

/// 明确的可动画值随 SwiftUI 每帧传入 Canvas，刻度、轨道和进度弧均使用同一坐标系。
private struct ProgressRingDrawing: View, Animatable {
    var value: Double
    let lineWidth: CGFloat
    let tint: Color?
    let ticks: Int
    let showEndpoint: Bool
    let startAngle: Double
    let sweepAngle: Double
    let uniformTicks: Bool
    var animatableData: Double {
        get { value }
        set { value = min(1, max(0, newValue)) }
    }

    private func point(_ center: CGPoint, _ radius: CGFloat, _ degrees: Double) -> CGPoint {
        let a = degrees * .pi / 180
        return CGPoint(x: center.x + CGFloat(cos(a)) * radius, y: center.y + CGFloat(sin(a)) * radius)
    }
    private func path(_ center: CGPoint, _ radius: CGFloat, _ sweep: Double) -> Path {
        var result = Path()
        let count = max(1, Int(ceil(sweep / 2)))
        for i in 0...count {
            let p = point(center, radius, startAngle + sweep * Double(i) / Double(count))
            if i == 0 { result.move(to: p) } else { result.addLine(to: p) }
        }
        return result
    }
    private func tone(_ t: Double) -> Color {
        if let tint { return tint }
        let a = UIColor(t < 0.5 ? EyeStyle.brandStart : EyeStyle.dataCyan)
        let b = UIColor(t < 0.5 ? EyeStyle.dataCyan : EyeStyle.dataAqua)
        let u = CGFloat(t < 0.5 ? t * 2 : (t - 0.5) * 2)
        var ar: CGFloat = 0, ag: CGFloat = 0, ab: CGFloat = 0, aa: CGFloat = 0
        var br: CGFloat = 0, bg: CGFloat = 0, bb: CGFloat = 0, ba: CGFloat = 0
        a.getRed(&ar, green: &ag, blue: &ab, alpha: &aa)
        b.getRed(&br, green: &bg, blue: &bb, alpha: &ba)
        return Color(red: Double(ar + (br - ar) * u), green: Double(ag + (bg - ag) * u), blue: Double(ab + (bb - ab) * u))
    }
    var body: some View {
        let glowOutset = EyeStyle.ringGlowOutset(lineWidth: lineWidth)
        Canvas { ctx, size in
            let center = CGPoint(x: size.width / 2, y: size.height / 2)
            let radius = (min(size.width, size.height) - glowOutset * 2 - lineWidth) / 2
            ctx.stroke(path(center, radius, sweepAngle), with: .color(EyeStyle.ringTrack),
                style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
            if value > 0 {
                let sweep = sweepAngle * value
                if tint == nil {
                    var glow = ctx
                    glow.addFilter(.shadow(color: EyeStyle.primary.opacity(EyeStyle.glowOpacity), radius: EyeStyle.glowRadius))
                    glow.stroke(path(center, radius, sweep), with: .color(EyeStyle.primary.opacity(0.35)),
                        style: StrokeStyle(lineWidth: lineWidth * EyeStyle.ringGlowStrokeScale, lineCap: .round))
                }
                let count = max(1, Int(ceil(sweep / 2)))
                for i in 0..<count {
                    var segment = Path()
                    segment.move(to: point(center, radius, startAngle + sweep * Double(i) / Double(count)))
                    segment.addLine(to: point(center, radius, startAngle + sweep * Double(i + 1) / Double(count)))
                    ctx.stroke(segment, with: .color(tone(value * Double(i) / Double(count))),
                        style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                }
                if showEndpoint {
                    let p = point(center, radius, startAngle + sweep)
                    ctx.fill(Path(ellipseIn: CGRect(x: p.x - lineWidth * 0.28, y: p.y - lineWidth * 0.28,
                        width: lineWidth * 0.56, height: lineWidth * 0.56)), with: .color(.white))
                }
            }
            if ticks > 0 {
                for i in 0..<ticks {
                    let a = startAngle + sweepAngle * Double(i) / Double(uniformTicks ? max(1, ticks - 1) : ticks)
                    let outer = uniformTicks ? min(size.width, size.height) / 2 - glowOutset - lineWidth * 1.7 : radius - lineWidth * 1.6
                    let inner = outer - lineWidth * (uniformTicks ? 0.45 : 0.6)
                    var tick = Path(); tick.move(to: point(center, outer, a))
                    tick.addLine(to: point(center, inner, a))
                    if uniformTicks {
                        let color = tone(Double(i) / Double(max(1, ticks - 1)))
                        let style = StrokeStyle(lineWidth: lineWidth * 0.12, lineCap: .round)
                        var glow = ctx
                        glow.addFilter(.shadow(color: color.opacity(EyeStyle.glowOpacity), radius: EyeStyle.tickGlowRadius))
                        glow.stroke(tick, with: .color(color), style: style)
                        ctx.stroke(tick, with: .color(color), style: style)
                    } else {
                        ctx.stroke(tick, with: .color(EyeStyle.tertiary.opacity(0.55)), lineWidth: lineWidth * 0.12)
                    }
                }
            }
        }
        // 扩大 Canvas 的滤镜边界，再抵消布局尺寸；圆环、刻度和中心内容的位置保持原样。
        .padding(-glowOutset)
    }
}

enum EyeMood { case happy, calm, tired, waiting }

/// 与安卓同一组原生矢量表情，颜色及等级映射均来自已有评估结果。
struct MoodFace: View {
    let mood: EyeMood
    var body: some View {
        Canvas { ctx, size in
            let s = min(size.width, size.height)
            let c = CGPoint(x: size.width / 2, y: size.height / 2)
            let base: Color = switch mood {
            case .happy: EyeStyle.moodGold
            case .calm: EyeStyle.moodWarm
            case .tired: EyeStyle.moodSilver
            case .waiting: EyeStyle.moodSilver
            }
            var rays = Path()
            for i in 0...160 {
                let a = Double(i) * 2 * .pi / 160
                let r = s * (0.44 + 0.035 * CGFloat(cos(a * 10)))
                let p = CGPoint(x: c.x + r * CGFloat(cos(a)), y: c.y + r * CGFloat(sin(a)))
                if i == 0 { rays.move(to: p) } else { rays.addLine(to: p) }
            }
            rays.closeSubpath(); ctx.fill(rays, with: .color(base.opacity(0.52)))
            ctx.fill(Path(ellipseIn: CGRect(x: c.x - s * 0.36, y: c.y - s * 0.36, width: s * 0.72, height: s * 0.72)),
                with: .radialGradient(Gradient(colors: [.white.opacity(0.9), base]), center: CGPoint(x: c.x - s * 0.1, y: c.y - s * 0.14),
                    startRadius: 0, endRadius: s * 0.62))
            for side: CGFloat in [-1, 1] {
                let x = c.x + side * s * 0.12, y = c.y - s * 0.04
                if mood == .tired {
                    var eye = Path(); eye.move(to: CGPoint(x: x - s * 0.045, y: y)); eye.addLine(to: CGPoint(x: x + s * 0.045, y: y))
                    ctx.stroke(eye, with: .color(EyeStyle.ink), style: StrokeStyle(lineWidth: s * 0.045, lineCap: .round))
                } else {
                    ctx.fill(Path(ellipseIn: CGRect(x: x - s * 0.033, y: y - s * 0.033, width: s * 0.066, height: s * 0.066)), with: .color(EyeStyle.ink))
                }
            }
            var mouth = Path()
            if mood == .tired {
                mouth.addEllipse(in: CGRect(x: c.x - s * 0.045, y: c.y + s * 0.095, width: s * 0.09, height: s * 0.09))
            } else if mood == .waiting {
                mouth.move(to: CGPoint(x: c.x - s * 0.08, y: c.y + s * 0.14)); mouth.addLine(to: CGPoint(x: c.x + s * 0.08, y: c.y + s * 0.14))
            } else {
                let width = s * (mood == .happy ? 0.15 : 0.1)
                for i in 0...24 {
                    let a = Double(i) * .pi / 24
                    let p = CGPoint(x: c.x + width * CGFloat(cos(a)), y: c.y + s * 0.12 + s * (mood == .happy ? 0.08 : 0.04) * CGFloat(sin(a)))
                    if i == 0 { mouth.move(to: p) } else { mouth.addLine(to: p) }
                }
            }
            ctx.stroke(mouth, with: .color(EyeStyle.ink), style: StrokeStyle(lineWidth: s * 0.04, lineCap: .round))
        }.accessibilityHidden(true)
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

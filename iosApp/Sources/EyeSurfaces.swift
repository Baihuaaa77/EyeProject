import AVFoundation
import SwiftUI

/*
 * 层次与质感（与安卓 core:ui EyeSurfaces.kt 一致）：
 * - 页面为纯色底（EyeBackdrop）；颜色只用深海蓝主调
 * - 卡片是「玻璃」：系统毛玻璃材质 + 半透明填充 + 上亮下淡的描边；iOS 26 起叠加系统 Liquid Glass，低版本不出现这一层
 * - 每页最重要的一张卡用 HeroCard：更亮（深色为深蓝渐变）、更深的投影，浮在最上层；其余卡片平铺
 * - 一张卡只讲一件事；同卡多项读数用 MetricRow 竖线分隔，标签在上、数值在下
 */

// MARK: - 玻璃面

struct EyeGlassModifier: ViewModifier {
    var cornerRadius: CGFloat
    var fill: AnyShapeStyle?
    var edgeTop: Color = EyeStyle.glassEdgeTop
    var shadow = true

    @ViewBuilder func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        let surface = content
            .background {
                ZStack {
                    shape.fill(.ultraThinMaterial)
                    shape.fill(fill ?? AnyShapeStyle(EyeStyle.glassFill))
                    // 左上一道斜向柔光（液态玻璃的高光）
                    shape.fill(LinearGradient(colors: [Color.white.opacity(0.28), .clear], startPoint: .topLeading, endPoint: UnitPoint(x: 0.6, y: 0.7)))
                        .blendMode(.plusLighter).opacity(0.6)
                }
            }
            .overlay { shape.strokeBorder(LinearGradient(colors: [edgeTop, EyeStyle.glassEdgeBottom], startPoint: .top, endPoint: .bottom), lineWidth: 1) }
            // 柔和投影：卡片从底色上浮起（主卡另有更深的投影）
            .shadow(color: shadow ? EyeStyle.shadow.opacity(0.7) : .clear, radius: 8, y: 3)
        if #available(iOS 26.0, *) {
            surface.glassEffect(.regular, in: shape)
        } else {
            surface
        }
    }
}

extension View {
    /// 玻璃面（卡片、列表组、入口行共用）。[fill] 为空时用令牌 glassFill。
    func eyeGlass(cornerRadius: CGFloat = EyeStyle.radius, fill: AnyShapeStyle? = nil, edgeTop: Color = EyeStyle.glassEdgeTop, shadow: Bool = true) -> some View {
        modifier(EyeGlassModifier(cornerRadius: cornerRadius, fill: fill, edgeTop: edgeTop, shadow: shadow))
    }
}

/// 页面底色（纯色，不加渐变）。
struct EyeBackdrop: View {
    var body: some View { EyeStyle.background.ignoresSafeArea() }
}

// MARK: - 卡片

/// 主卡：每页一张，更亮的玻璃（深色为深蓝渐变）+ 深投影（深色带蓝色泛光）。
struct EyeHeroCard<Content: View>: View {
    var padding: CGFloat = 20
    var spacing: CGFloat = 12
    @ViewBuilder let content: Content
    @Environment(\.colorScheme) private var scheme
    var body: some View {
        VStack(alignment: .leading, spacing: spacing) { content }
            .padding(padding).frame(maxWidth: .infinity, alignment: .leading)
            .eyeGlass(cornerRadius: EyeStyle.radiusHero,
                      fill: scheme == .dark ? AnyShapeStyle(EyeStyle.heroGradient) : AnyShapeStyle(Color.white),
                      edgeTop: EyeStyle.heroBorder, shadow: false)
            .shadow(color: scheme == .dark ? EyeStyle.glow.opacity(0.22) : EyeStyle.shadow, radius: scheme == .dark ? 26 : 18, y: 10)
    }
}

/// 普通卡片：平铺玻璃面。[tinted] 为 true 时用浅色块底。
struct EyeCard<Content: View>: View {
    var padding: CGFloat = 18
    var spacing: CGFloat = 12
    var tinted = false
    @ViewBuilder let content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: spacing) { content }
            .padding(padding).frame(maxWidth: .infinity, alignment: .leading)
            .eyeGlass(fill: tinted ? AnyShapeStyle(EyeStyle.surfaceVariant.opacity(0.8)) : nil)
    }
}

/// 卡片分类头：分类色小图标 + 分类色标题，右侧可放操作（安卓 EyeCategoryHeader）。
struct CategoryHeader<Trailing: View>: View {
    let title: String
    let glyph: EyeGlyph
    var tone: TileTone = .primary
    @ViewBuilder var trailing: Trailing
    var body: some View {
        HStack(spacing: 6) {
            EyeGlyphView(glyph: glyph, color: tone.colors.1).frame(width: 18, height: 18)
            Text(title).font(EyeStyle.body.weight(.semibold)).foregroundStyle(tone.colors.1)
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

/// 一项读数：标签在上，数值在下（数值大号粗体、单位常规字重）。[attention] 时标签后带琥珀小圆点。
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
            (Text(value).font(EyeStyle.metricValue) + Text(unit.map { " \($0)" } ?? "").font(.system(size: 14, weight: .medium)))
                .foregroundStyle(valueColor).lineLimit(1).minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

/// 竖向细分隔线（同卡并排读数之间）。
struct VDivider: View {
    var body: some View { Rectangle().fill(EyeStyle.borderSubtle).frame(width: 1).padding(.horizontal, 14) }
}

// MARK: - 圆环与 AI 标识

/// 进度圆环：底环 + 光流渐变进度弧（主蓝 → 青蓝），深色主题下带同色泛光（与安卓 drawProgressRing 一致）。
struct ProgressRing: View {
    let fraction: Double?
    var lineWidth: CGFloat = 9
    @Environment(\.colorScheme) private var scheme
    @State private var shown: Double = 0
    var body: some View {
        let glow = scheme == .dark
        let f = max(0.02, min(1, shown))
        // 光流：从 12 点钟的主蓝沿弧线流向末端的青蓝（角度渐变随视图一起旋转）
        // 起点的圆头会往回伸进 360° 之前的一小段：那一段也接回起始色，否则起点会冒出一块末端色的半圆
        let tail = min(max(f + 0.01, 0.96), 0.999)
        let flow = AngularGradient(stops: [.init(color: EyeStyle.flowStart, location: 0), .init(color: EyeStyle.flowEnd, location: f),
                                           .init(color: EyeStyle.flowEnd, location: (f + tail) / 2), .init(color: EyeStyle.flowStart, location: tail),
                                           .init(color: EyeStyle.flowStart, location: 1)], center: .center)
        ZStack {
            Circle().stroke(EyeStyle.surfaceVariant, lineWidth: lineWidth)
            if fraction != nil {
                let arc = Circle().trim(from: 0, to: f)
                if glow {
                    arc.stroke(flow, style: StrokeStyle(lineWidth: lineWidth * 2.2, lineCap: .round)).blur(radius: 6).opacity(0.55)
                }
                arc.stroke(flow, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
            }
        }
        .rotationEffect(.degrees(-90))
        .padding(lineWidth * 1.2)
        .onAppear { withAnimation(.easeOut(duration: 0.7)) { shown = fraction ?? 0 } }
        .onChange(of: fraction ?? 0) { value in withAnimation(.easeOut(duration: 0.7)) { shown = value } }
    }
}

/// AI 标识（替代旧的卡通头像）：深色主题为发光光球（轻呼吸），浅色主题为主色声波线。
struct AiMark: View {
    var size: CGFloat = 40
    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var breathe = false
    var body: some View {
        if scheme == .dark {
            ZStack {
                Circle().fill(RadialGradient(colors: [EyeStyle.glow.opacity(0.5), .clear], center: .center, startRadius: 0, endRadius: size / 2))
                    .scaleEffect(breathe ? 1.06 : 0.9)
                Circle().fill(RadialGradient(colors: [.white, EyeStyle.brandStart, EyeStyle.primary, EyeStyle.buttonStart],
                                             center: UnitPoint(x: 0.36, y: 0.3), startRadius: 0, endRadius: size * 0.45))
                    .frame(width: size * 0.6, height: size * 0.6)
            }
            .frame(width: size, height: size)
            .onAppear { if !reduceMotion { withAnimation(.easeInOut(duration: 1.8).repeatForever(autoreverses: true)) { breathe = true } } }
        } else {
            Image(systemName: "waveform").font(.system(size: size * 0.42, weight: .semibold)).foregroundStyle(EyeStyle.primary)
                .frame(width: size, height: size).background(EyeStyle.primarySoft, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
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

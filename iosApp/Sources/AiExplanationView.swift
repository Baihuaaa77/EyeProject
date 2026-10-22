import SwiftUI

/// 「AI 解读」卡片：结果页与测试记录共用，与安卓 core:ui AiExplanationCard 同一排版与文案。
/// 状态与请求全部在共用的 CloudClient（explanationJson / explain）；风险等级由测试规则给出，模型只做通俗解释。
struct AiExplanationView: View {
    let fileName: String
    /// 嵌在测试记录行里：换用页面底色区分层级。
    var embedded = false
    @ObservedObject private var cloud = CloudModel.shared
    @State private var asking = false
    @State private var sourcesOpen = false

    private var info: [String: Any] { cloud.explanation(fileName) }
    private var stateName: String { info["state"] as? String ?? "unsupported" }
    private func text(_ key: String) -> String { info[key] as? String ?? "" }
    private func list(_ key: String) -> [String] { info[key] as? [String] ?? [] }

    var body: some View {
        if stateName != "unsupported" {
            VStack(alignment: .leading, spacing: 14) {
                header
                Group {
                    switch stateName {
                    case "locked": Text(text("message")).font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
                    case "consent", "ready": idle(consentText: stateName == "consent" ? text("consentText") : nil)
                    case "loading": AiLoadingView()
                    case "failed":
                        VStack(alignment: .leading, spacing: 12) {
                            Text(text("message")).font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
                            Button("重试") { EyeHaptics.tap(); cloud.explain(fileName, agreeFirst: false) }.buttonStyle(EyeButtonStyle(role: .secondary))
                        }
                    default: done
                    }
                }.transition(.opacity)
            }
            .padding(.horizontal, embedded ? 14 : 20).padding(.vertical, embedded ? 14 : 18)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(embedded ? EyeStyle.background : EyeStyle.panel,
                        in: RoundedRectangle(cornerRadius: embedded ? EyeStyle.radiusLarge : EyeStyle.radius))
            .animation(.easeOut(duration: 0.28), value: stateName)
            .onChange(of: stateName) { if $0 == "done" { EyeHaptics.success() } else if $0 == "failed" { EyeHaptics.failure() } }
        }
    }

    private var header: some View {
        HStack(spacing: 10) {
            AiBadge().frame(width: 30, height: 30)
            Text("AI 解读").font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text)
            Spacer()
            Text("依据眼科知识库").font(EyeStyle.chip).foregroundStyle(EyeStyle.primary)
                .padding(.horizontal, 10).padding(.vertical, 4).background(EyeStyle.primarySoft, in: Capsule())
        }
    }

    private func idle(consentText: String?) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("用大白话说说这次结果意味着什么，以及接下来可以怎么做。").font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
            if asking, let consentText {
                VStack(alignment: .leading, spacing: 12) {
                    Text("首次使用需要你的同意").font(EyeStyle.button).foregroundStyle(EyeStyle.text)
                    Text(consentText).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                    HStack(spacing: 10) {
                        Button("暂不") { EyeHaptics.tap(); withAnimation { asking = false } }.buttonStyle(EyeButtonStyle(role: .secondary))
                        Button("同意并生成") { EyeHaptics.tap(); asking = false; cloud.explain(fileName, agreeFirst: true) }
                            .buttonStyle(EyeButtonStyle()).layoutPriority(1)
                    }
                }.padding(14).background(EyeStyle.background, in: RoundedRectangle(cornerRadius: EyeStyle.radiusMedium))
                    .transition(.opacity.combined(with: .move(edge: .top)))
            } else {
                Button("生成解读") {
                    EyeHaptics.tap()
                    if consentText != nil { withAnimation(.easeOut(duration: 0.28)) { asking = true } }
                    else { cloud.explain(fileName, agreeFirst: false) }
                }.buttonStyle(EyeButtonStyle())
            }
        }
    }

    private var done: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(text("summary")).font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text)
            if !list("meaning").isEmpty { bullets("这说明", list("meaning"), dot: EyeStyle.secondary) }
            if !list("suggestions").isEmpty { bullets("可以这样做", list("suggestions"), dot: EyeStyle.primary) }
            if !text("seeDoctor").isEmpty {
                let urgent = info["urgent"] as? Bool ?? false
                let (strong, soft) = urgent ? (EyeStyle.warning, EyeStyle.warningSoft) : (EyeStyle.primary, EyeStyle.primarySoft)
                HStack(alignment: .top, spacing: 10) {
                    Circle().fill(strong).frame(width: 8, height: 8).padding(.top, 6)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("什么时候看医生").font(EyeStyle.chip).foregroundStyle(strong)
                        Text(text("seeDoctor")).font(EyeStyle.body).foregroundStyle(EyeStyle.text)
                    }
                    Spacer(minLength: 0)
                }.padding(14).background(soft, in: RoundedRectangle(cornerRadius: EyeStyle.radiusMedium))
            }
            if !list("sources").isEmpty {
                Button { EyeHaptics.tap(); withAnimation(.easeOut(duration: 0.2)) { sourcesOpen.toggle() } } label: {
                    Text(sourcesOpen ? "收起参考资料 ▴" : "参考资料（\(list("sources").count)）▾").font(EyeStyle.body).foregroundStyle(EyeStyle.primary)
                        .frame(maxWidth: .infinity, minHeight: 40, alignment: .leading).contentShape(Rectangle())
                }.buttonStyle(.plain)
                if sourcesOpen {
                    ForEach(list("sources"), id: \.self) { Text("· " + $0).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary) }
                }
            }
            Text(text("footnote")).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
        }
    }

    private func bullets(_ title: String, _ items: [String], dot: Color) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(EyeStyle.chip).foregroundStyle(EyeStyle.secondary)
            ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                HStack(alignment: .top, spacing: 10) {
                    Circle().fill(dot).frame(width: 6, height: 6).padding(.top, 8)
                    Text(item).font(EyeStyle.body).foregroundStyle(EyeStyle.text).fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }
}

/// 骨架条呼吸 + 每 1.6 秒轮换一句进度语（与安卓同文案）。
private struct AiLoadingView: View {
    private static let steps = ["正在整理本次结果…", "正在查阅眼科资料…", "正在组织通俗的说法…"]
    @State private var step = 0
    @State private var pulse = false
    private let timer = Timer.publish(every: 1.6, on: .main, in: .common).autoconnect()
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach([1.0, 0.86, 0.62], id: \.self) { fraction in
                GeometryReader { proxy in
                    Capsule().fill(EyeStyle.primarySoft).frame(width: proxy.size.width * fraction)
                }.frame(height: 14).opacity(pulse ? 0.85 : 0.35)
            }
            Text(Self.steps[step]).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                .id(step).transition(.opacity)
        }
        .accessibilityElement(children: .ignore).accessibilityLabel(Self.steps[step])
        .onAppear { withAnimation(.linear(duration: 0.9).repeatForever(autoreverses: true)) { pulse = true } }
        .onReceive(timer) { _ in withAnimation(.easeOut(duration: 0.28)) { step = (step + 1) % Self.steps.count } }
    }
}

/// 品牌渐变圆底上的四角星。
private struct AiBadge: View {
    var body: some View {
        Canvas { context, size in
            let s = min(size.width, size.height)
            func star(_ c: CGPoint, _ r: CGFloat) -> Path {
                let w = r * 0.28 * 0.3
                var p = Path()
                p.move(to: CGPoint(x: c.x, y: c.y - r))
                p.addQuadCurve(to: CGPoint(x: c.x + r, y: c.y), control: CGPoint(x: c.x + w, y: c.y - w))
                p.addQuadCurve(to: CGPoint(x: c.x, y: c.y + r), control: CGPoint(x: c.x + w, y: c.y + w))
                p.addQuadCurve(to: CGPoint(x: c.x - r, y: c.y), control: CGPoint(x: c.x - w, y: c.y + w))
                p.addQuadCurve(to: CGPoint(x: c.x, y: c.y - r), control: CGPoint(x: c.x - w, y: c.y - w))
                return p
            }
            context.fill(star(CGPoint(x: s * 0.46, y: s * 0.54), s * 0.3), with: .color(EyeStyle.panel))
            context.fill(star(CGPoint(x: s * 0.74, y: s * 0.27), s * 0.12), with: .color(EyeStyle.panel.opacity(0.85)))
        }
        .background(LinearGradient(colors: [EyeStyle.brandStart, EyeStyle.brandEnd], startPoint: .topLeading, endPoint: .bottomTrailing), in: Circle())
        .accessibilityHidden(true)
    }
}

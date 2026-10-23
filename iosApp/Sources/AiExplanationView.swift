import SwiftUI

/// 卡片文案：「AI 解读」与「AI 综合建议」共用同一张卡片（与安卓 AiCardText 一致）。
struct AiCardText {
    let title: String
    let help: String
    let intro: String
    let action: String
    let firstGroup: String
    let secondGroup: String
    /// 共用客户端 JSON 里两组要点的键
    let firstKey: String
    let secondKey: String

    static let explanation = AiCardText(
        title: "AI 解读",
        help: "依据眼科知识库，用通俗的话解释这次结果并给出建议。风险等级仍以测试规则为准，不是诊断。",
        intro: "用大白话说说这次结果意味着什么，以及接下来可以怎么做。",
        action: "生成解读", firstGroup: "这说明", secondGroup: "可以这样做", firstKey: "meaning", secondKey: "suggestions")
    static let advice = AiCardText(
        title: "AI 综合建议",
        help: "小瞳把今天的用眼概况、自测结果和你记录的感受交给大模型，结合眼科知识库给出建议。用眼负荷仍以规则为准，不是诊断。",
        intro: "结合今天的用眼情况，说说最该先做的事和值得养成的习惯。",
        action: "生成建议", firstGroup: "先做这些", secondGroup: "养成习惯", firstKey: "priorities", secondKey: "habits")
}

/// 「AI 解读」卡片：结果页与测试记录共用。状态与请求在共用的 CloudClient（explanationJson / explain）。
struct AiExplanationView: View {
    let fileName: String
    /// 嵌在测试记录行里：换用页面底色区分层级。
    var embedded = false
    @ObservedObject private var cloud = CloudModel.shared
    var body: some View {
        AiCardView(info: cloud.explanation(fileName), text: .explanation, embedded: embedded) { agree in
            cloud.explain(fileName, agreeFirst: agree)
        }
    }
}

/// 「AI 综合建议」卡片（健康建议页）：输入为共用 EyeCareAdvice 抽取的去标识概况，状态在 CloudClient（adviceJson / advise）。
struct AiAdviceView: View {
    @ObservedObject private var cloud = CloudModel.shared
    @ObservedObject private var store = EyeCareStore.shared
    var body: some View {
        if !store.adviceRequestJson.isEmpty {
            AiCardView(info: cloud.advice(store.adviceRequestJson), text: .advice, embedded: false) { agree in
                cloud.advise(store.adviceRequestJson, agreeFirst: agree)
            }
        }
    }
}

/// 卡片本体，与安卓 core:ui AiExplanationCard 同一排版：标题行（星标 + 标题 + 问号）→ 按状态显示。
struct AiCardView: View {
    let info: [String: Any]
    let text: AiCardText
    let embedded: Bool
    let generate: (Bool) -> Void
    @State private var asking = false
    @State private var sourcesOpen = false

    private var stateName: String { info["state"] as? String ?? "unsupported" }
    private func string(_ key: String) -> String { info[key] as? String ?? "" }
    private func list(_ key: String) -> [String] { info[key] as? [String] ?? [] }

    var body: some View {
        if stateName != "unsupported" {
            VStack(alignment: .leading, spacing: 14) {
                header
                Group {
                    switch stateName {
                    case "locked": Text(string("message")).font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
                    case "consent", "ready": idle(consentText: stateName == "consent" ? string("consentText") : nil)
                    case "loading": AiLoadingView()
                    case "failed":
                        VStack(alignment: .leading, spacing: 12) {
                            Text(string("message")).font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
                            Button("重试") { EyeHaptics.tap(); generate(false) }.buttonStyle(EyeButtonStyle(role: .secondary))
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
            HStack(spacing: 0) {
                Text(text.title).font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text)
                HelpButton(title: text.title, text: text.help)
            }
            Spacer(minLength: 0)
        }
    }

    private func idle(consentText: String?) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(text.intro).font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
            if asking, let consentText {
                VStack(alignment: .leading, spacing: 12) {
                    Text("首次使用需要你的同意").font(EyeStyle.button).foregroundStyle(EyeStyle.text)
                    Text(consentText).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                    HStack(spacing: 10) {
                        Button("暂不") { EyeHaptics.tap(); withAnimation { asking = false } }.buttonStyle(EyeButtonStyle(role: .secondary))
                        Button("同意并生成") { EyeHaptics.tap(); asking = false; generate(true) }
                            .buttonStyle(EyeButtonStyle()).layoutPriority(1)
                    }
                }.padding(14).background(EyeStyle.background, in: RoundedRectangle(cornerRadius: EyeStyle.radiusMedium))
                    .transition(.opacity.combined(with: .move(edge: .top)))
            } else {
                Button(text.action) {
                    EyeHaptics.tap()
                    if consentText != nil { withAnimation(.easeOut(duration: 0.28)) { asking = true } }
                    else { generate(false) }
                }.buttonStyle(EyeButtonStyle())
            }
        }
    }

    private var done: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(string("summary")).font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text)
            if !list(text.firstKey).isEmpty { bullets(text.firstGroup, list(text.firstKey), dot: EyeStyle.secondary) }
            if !list(text.secondKey).isEmpty { bullets(text.secondGroup, list(text.secondKey), dot: EyeStyle.primary) }
            if !string("seeDoctor").isEmpty {
                let urgent = info["urgent"] as? Bool ?? false
                let (strong, soft) = urgent ? (EyeStyle.warning, EyeStyle.warningSoft) : (EyeStyle.primary, EyeStyle.primarySoft)
                HStack(alignment: .top, spacing: 10) {
                    Circle().fill(strong).frame(width: 8, height: 8).padding(.top, 6)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("什么时候看医生").font(EyeStyle.chip).foregroundStyle(strong)
                        Text(string("seeDoctor")).font(EyeStyle.body).foregroundStyle(EyeStyle.text)
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
            Text(string("footnote")).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
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

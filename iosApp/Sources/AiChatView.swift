import SwiftUI

/// 首页「AI 综合建议」入口（放在「今日感受」上方）：爱科头像 + 一个按钮，点了进入对话页。与安卓 ChatEntryCard 一致。
struct ChatEntryCard: View {
    let open: () -> Void
    var body: some View {
        Button { EyeHaptics.tap(); open() } label: {
            Panel {
                HStack(spacing: 12) {
                    EyechoAvatar(size: 40)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("AI 综合建议").font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text)
                        Text("和爱科聊聊今天的用眼感受").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                    Image(systemName: "chevron.right").font(.system(size: 14, weight: .semibold)).foregroundStyle(EyeStyle.secondary)
                }
            }.contentShape(Rectangle())
        }.buttonStyle(PressScale(scale: 0.98))
    }
}

/// 「AI 综合建议」对话页：由爱尔眼科数字人「爱科 Eyecho」主持。顶部是爱科的自我介绍，接着是今天的综合建议卡片，
/// 下面和爱科自由对话；记录只存在本机，可清空。
/// 同意说明平时不展示，发送时按需弹窗（见 ChatConsentView）。
/// 状态与请求在共用 CloudClient（chatJson / sendChat / clearChat），与安卓 EyeChatScreen 同一规则与文案。
struct AiChatPage: View {
    let openAccount: () -> Void
    @ObservedObject private var cloud = CloudModel.shared
    @ObservedObject private var store = EyeCareStore.shared
    @State private var draft = ""
    @State private var confirmClear = false
    /// 本次进入对话页是否已弹过同意说明；还没同意时每次发送都要弹
    @State private var asked = false
    @State private var confirming = false
    @FocusState private var focused: Bool

    var body: some View {
        let info = cloud.chat()
        let messages = info["messages"] as? [[String: Any]] ?? []
        let sending = info["sending"] as? Bool ?? false
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        EyechoIntro()
                        AiAdviceView()
                        if messages.isEmpty { suggestions(info) }
                        ForEach(Array(messages.enumerated()), id: \.offset) { index, m in
                            ChatBubble(message: m, urgentNotice: info["urgentNotice"] as? String ?? "",
                                active: index == messages.count - 1 && !sending && (info["state"] as? String) != "locked",
                                onOption: { draft = $0 })
                        }
                        if !sending && !messages.isEmpty {
                            let pending = (messages.last?["question"] as? [String: Any]) != nil
                            ChoiceChip(label: pending ? "结束问询" : "开始新的问询", selected: false) {
                                draft = pending ? "结束问询" : "帮我一步步梳理眼部不适"
                            }
                        }
                        if sending { typing }
                        if let error = info["error"] as? String, !error.isEmpty {
                            Text(error).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.danger)
                        }
                        Text(info["disclaimer"] as? String ?? "").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                        Color.clear.frame(height: 1).id("bottom")
                    }.padding(.horizontal, 16).padding(.top, 4).padding(.bottom, 12)
                }
                .scrollDismissesKeyboard(.interactively)
                .onAppear { proxy.scrollTo("bottom", anchor: .bottom) }
                .onChange(of: messages.count) { _ in withAnimation(.easeOut(duration: 0.25)) { proxy.scrollTo("bottom", anchor: .bottom) } }
                .onChange(of: sending) { _ in withAnimation(.easeOut(duration: 0.25)) { proxy.scrollTo("bottom", anchor: .bottom) } }
            }
            inputArea(info, sending: sending)
        }
        .overlay {
            if confirming {
                ChatConsentView(info: info, agree: { quiet in
                    confirming = false; asked = true
                    send(agreeFirst: info["state"] as? String == "consent", dontAskAgain: quiet)
                }, decline: { confirming = false })
                .transition(.opacity)
            }
        }
        .animation(.easeOut(duration: 0.2), value: confirming)
        .background(EyeStyle.background.ignoresSafeArea())
        .navigationTitle("AI 综合建议").navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(EyeStyle.background, for: .navigationBar)
        .toolbar {
            if !messages.isEmpty {
                ToolbarItem(placement: .navigationBarTrailing) { Button("清空") { confirmClear = true } }
            }
        }
        .confirmationDialog("清空本机的对话记录？", isPresented: $confirmClear, titleVisibility: .visible) {
            Button("清空", role: .destructive) { cloud.clearChat() }
        }
        .onAppear { store.refresh() }
    }

    private func send(agreeFirst: Bool, dontAskAgain: Bool) {
        cloud.sendChat(draft, agreeFirst: agreeFirst, dontAskAgain: dontAskAgain)
        draft = ""
    }

    private func suggestions(_ info: [String: Any]) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("可以这样问爱科").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
            FlowLayout(spacing: 8) {
                ForEach(info["suggestions"] as? [String] ?? [], id: \.self) { text in
                    ChoiceChip(label: text, selected: false) { draft = text; focused = true }
                }
            }
        }.padding(.top, 4)
    }

    private var typing: some View {
        HStack(spacing: 8) {
            EyechoAvatar(size: 34)
            Text("爱科正在想…").font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
        }
    }

    /// 底部：未登录提示去登录；否则输入框 + 发送。
    @ViewBuilder private func inputArea(_ info: [String: Any], sending: Bool) -> some View {
        let state = info["state"] as? String ?? "locked"
        let limit = (info["maxInput"] as? NSNumber)?.intValue ?? 300
        VStack(alignment: .leading, spacing: 10) {
            if state == "locked" {
                Text(info["message"] as? String ?? "").font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
                Button("登录 / 注册") { EyeHaptics.tap(); openAccount() }.buttonStyle(EyeButtonStyle())
            } else {
                HStack(alignment: .bottom, spacing: 10) {
                    TextField("说说今天眼睛的感受…", text: $draft, axis: .vertical)
                        .lineLimit(1...4).font(EyeStyle.body).focused($focused)
                        .padding(.horizontal, 14).padding(.vertical, 11)
                        .background(EyeStyle.background, in: RoundedRectangle(cornerRadius: 22))
                        .onChange(of: draft) { value in if value.count > limit { draft = String(value.prefix(limit)) } }
                    let empty = draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    Button {
                        EyeHaptics.tap()
                        focused = false
                        if state == "consent" || (info["askConsent"] as? Bool ?? true) && !asked { confirming = true }
                        else { send(agreeFirst: false, dontAskAgain: false) }
                    } label: {
                        Text("发送").font(EyeStyle.button).foregroundStyle(.white)
                            .padding(.horizontal, 16).frame(minHeight: 44)
                            .background(empty || sending ? EyeStyle.primary.opacity(0.4) : EyeStyle.primary, in: Capsule())
                    }.buttonStyle(PressScale()).disabled(empty || sending)
                }
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
        .background(EyeStyle.panel.ignoresSafeArea(edges: .bottom))
        .overlay(alignment: .top) { Rectangle().fill(EyeStyle.borderSubtle).frame(height: 1) }
    }
}

/// 爱科 Eyecho 圆形头像（浅主色底）。与安卓 EyechoAvatar 一致。
struct EyechoAvatar: View {
    let size: CGFloat
    var body: some View {
        Image("EyechoAvatar").resizable().scaledToFill()
            .frame(width: size, height: size)
            .background(EyeStyle.primarySoft).clipShape(Circle())
            .accessibilityHidden(true)
    }
}

/// 对话页顶部：爱科半身像站在卡片底边上，右侧自我介绍。与安卓 EyechoIntro 同一文案。
private struct EyechoIntro: View {
    var body: some View {
        // 半身像最高，决定卡片高度并贴住底边；文字竖直居中
        HStack(alignment: .center, spacing: 14) {
            Image("EyechoHalf").resizable().scaledToFit().frame(height: 132)
                .accessibilityLabel("爱科 Eyecho")
            VStack(alignment: .leading, spacing: 4) {
                Text("你好，我是爱科").font(EyeStyle.title).foregroundStyle(EyeStyle.primary)
                Text("爱尔眼科 AI 助手。眼睛不舒服、用眼习惯、测试结果，都可以问我。").font(EyeStyle.bodySecondary)
                    .foregroundStyle(EyeStyle.secondary).fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.bottom, 16)
        }
        .padding(.horizontal, 16).padding(.top, 12)
        .background(EyeStyle.primarySoft, in: RoundedRectangle(cornerRadius: 22))
    }
}

/// 发送前的同意说明弹窗：还没同意时每次发送都弹；已同意但没勾「以后不再提示」时，每次进入对话页第一次发送弹一次。
/// 「同意」后发送（带上是否勾了「以后不再提示」），「不同意」只关闭、输入框里的字保留。与安卓 ChatConsentDialog 同一文案。
private struct ChatConsentView: View {
    let info: [String: Any]
    let agree: (Bool) -> Void
    let decline: () -> Void
    @State private var quiet = false
    var body: some View {
        ZStack {
            EyeStyle.ink.opacity(0.55).ignoresSafeArea().contentShape(Rectangle()).onTapGesture { decline() }
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 10) {
                    EyechoAvatar(size: 40)
                    Text(info["consentTitle"] as? String ?? "").font(EyeStyle.title).foregroundStyle(EyeStyle.text)
                }
                Text(info["consentText"] as? String ?? "").font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                Button { EyeHaptics.toggle(); quiet.toggle() } label: {
                    HStack(spacing: 12) {
                        ZStack {
                            RoundedRectangle(cornerRadius: 5).fill(quiet ? EyeStyle.primary : Color.clear)
                            RoundedRectangle(cornerRadius: 5).stroke(quiet ? EyeStyle.primary : EyeStyle.borderSubtle, lineWidth: 2)
                            if quiet { Image(systemName: "checkmark").font(.system(size: 12, weight: .bold)).foregroundStyle(EyeStyle.panel) }
                        }.frame(width: 22, height: 22)
                        Text(info["dontAskAgain"] as? String ?? "").font(EyeStyle.body).foregroundStyle(EyeStyle.text)
                        Spacer()
                    }
                    .padding(.horizontal, 16).frame(minHeight: 52)
                    .background(quiet ? EyeStyle.primarySoft : EyeStyle.surfaceVariant, in: RoundedRectangle(cornerRadius: EyeStyle.radiusMedium))
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(quiet ? [.isSelected] : [])
                Button("同意") { EyeHaptics.tap(); agree(quiet) }.buttonStyle(EyeButtonStyle())
                Button("不同意") { EyeHaptics.tap(); decline() }.buttonStyle(EyeButtonStyle(role: .secondary))
            }
            .padding(24).frame(maxWidth: .infinity, alignment: .leading)
            .background(EyeStyle.panel, in: RoundedRectangle(cornerRadius: 22))
            .padding(24)
        }
        .accessibilityAddTraits(.isModal)
    }
}

/// 一条对话：用户的靠右（浅主色底），爱科的靠左（白底，带参考资料）；提到危险信号时在提问下方醒目提示尽快就医。
private struct ChatBubble: View {
    let message: [String: Any]
    let urgentNotice: String
    let active: Bool
    let onOption: (String) -> Void
    var body: some View {
        let mine = message["role"] as? String == "user"
        let urgent = message["urgent"] as? Bool ?? false
        let sources = message["sources"] as? [String] ?? []
        VStack(alignment: mine ? .trailing : .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 8) {
                if mine { Spacer(minLength: 48) } else {
                    EyechoAvatar(size: 34)
                }
                VStack(alignment: .leading, spacing: 6) {
                    Text(message["text"] as? String ?? "").font(EyeStyle.body).foregroundStyle(EyeStyle.text)
                        .textSelection(.enabled)
                    if message["inquiryComplete"] as? Bool == true && !urgent {
                        Text("本次问询已总结").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.primary)
                    }
                    if let q = message["question"] as? [String: Any] {
                        let options = q["options"] as? [String] ?? []
                        Text(q["prompt"] as? String ?? "").font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text)
                        if active {
                            FlowLayout(spacing: 8) {
                                ForEach(options, id: \.self) { option in
                                    ChoiceChip(label: option, selected: false) { onOption(option) }
                                }
                            }
                            Text(q["allowFreeText"] as? Bool ?? true
                                ? "选择选项或输入回答，然后点发送；也可以换个话题。"
                                : "选择选项后点发送；也可以换个话题。")
                                .font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                        } else if !options.isEmpty {
                            Text(options.joined(separator: " / ")).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                        }
                    }
                    if !sources.isEmpty {
                        Text("参考：" + sources.joined(separator: "；")).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                    }
                }
                .padding(.horizontal, 14).padding(.vertical, 10)
                .background(mine ? EyeStyle.primarySoft : EyeStyle.panel, in: RoundedRectangle(cornerRadius: 18))
                if !mine { Spacer(minLength: 32) }
            }
            if mine && urgent {
                Text(urgentNotice).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.danger)
                    .padding(12).frame(maxWidth: .infinity, alignment: .leading)
                    .background(EyeStyle.dangerSoft, in: RoundedRectangle(cornerRadius: 14))
            }
        }
    }
}

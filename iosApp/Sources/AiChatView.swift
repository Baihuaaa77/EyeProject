import SwiftUI

/// 对话页标题（与安卓 CHAT_TITLE 一致）；首页入口是爱科卡（EyechoCard）。
let chatTitle = "AI 主动问诊"

/// 「AI 主动问诊」对话页：由爱尔眼科数字人「爱科 Eyecho」主持。顶部是爱科的自我介绍，接着是今天的综合建议卡片，
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
    @State private var pendingOption: String?
    @State private var pendingConsentMessage: String?
    @AppStorage("eye.chat.confirmOptionAnswers") private var confirmOptionAnswers = true
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
                                onOption: { selectOption($0, info: info) })
                        }
                        if !sending && !messages.isEmpty {
                            let pending = (messages.last?["question"] as? [String: Any]) != nil
                            ChoiceChip(label: pending ? "结束问询" : "开始新的问询", selected: false) {
                                selectOption(pending ? "结束问询" : "帮我一步步梳理眼部不适", info: info)
                            }
                        }
                        if sending { typing }
                        if let error = info["error"] as? String, !error.isEmpty {
                            Text(error).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.readableText(EyeStyle.danger))
                        }
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
            if let choice = pendingOption {
                OptionAnswerConfirmView(choice: choice, confirm: { dontAsk in
                    pendingOption = nil
                    if dontAsk { confirmOptionAnswers = false }
                    requestSend(choice, info: info)
                }, cancel: { pendingOption = nil })
            }
            if confirming {
                ChatConsentView(info: info, agree: { quiet in
                    confirming = false; asked = true
                    send(pendingConsentMessage ?? draft, agreeFirst: info["state"] as? String == "consent", dontAskAgain: quiet)
                    pendingConsentMessage = nil
                }, decline: { confirming = false; pendingConsentMessage = nil })
                .transition(.opacity)
            }
        }
        .animation(.easeOut(duration: 0.2), value: confirming)
        .background(EyeStyle.background.ignoresSafeArea())
        .navigationTitle(chatTitle).navigationBarTitleDisplayMode(.inline)
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

    private func send(_ text: String, agreeFirst: Bool, dontAskAgain: Bool) {
        cloud.sendChat(text, agreeFirst: agreeFirst, dontAskAgain: dontAskAgain)
        if text == draft { draft = "" }
    }

    private func requestSend(_ text: String, info: [String: Any]) {
        guard !(info["sending"] as? Bool ?? false), !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        let state = info["state"] as? String ?? "locked"
        if state == "locked" { openAccount(); return }
        if state == "consent" || (info["askConsent"] as? Bool ?? true) && !asked {
            pendingConsentMessage = text
            confirming = true
        } else { send(text, agreeFirst: false, dontAskAgain: false) }
    }

    private func selectOption(_ text: String, info: [String: Any]) {
        guard !(info["sending"] as? Bool ?? false) else { return }
        if confirmOptionAnswers { pendingOption = text } else { requestSend(text, info: info) }
    }

    private func suggestions(_ info: [String: Any]) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("可以这样问爱科").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
            FlowLayout(spacing: 8) {
                ForEach(info["suggestions"] as? [String] ?? [], id: \.self) { text in
                    ChoiceChip(label: text, selected: false) { selectOption(text, info: info) }
                }
            }
        }.padding(.top, 4)
    }

    private var typing: some View {
        HStack(spacing: 8) {
            EyechoAvatar(size: EyeStyle.chatAvatarSize)
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
                        .background(EyeStyle.background, in: RoundedRectangle(cornerRadius: EyeStyle.radius, style: .continuous))
                        .onChange(of: draft) { value in if value.count > limit { draft = String(value.prefix(limit)) } }
                    let empty = draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    Button {
                        EyeHaptics.tap()
                        focused = false
                        requestSend(draft, info: info)
                    } label: {
                        Text("发送").font(EyeStyle.button).foregroundStyle(.white)
                            .padding(.horizontal, 16).frame(minHeight: 44)
                            .background(empty || sending ? EyeStyle.primary.opacity(0.4) : EyeStyle.primary, in: EyeStyle.chipShape)
                    }.buttonStyle(PressScale()).disabled(empty || sending)
                }
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
        .background(EyeStyle.panel.ignoresSafeArea(edges: .bottom))
        .overlay(alignment: .top) { Rectangle().fill(EyeStyle.borderSubtle).frame(height: 1) }
    }
}

/// 点选答案的发送确认；与数据处理同意窗口独立。
private struct OptionAnswerConfirmView: View {
    let choice: String
    let confirm: (Bool) -> Void
    let cancel: () -> Void
    @State private var dontAsk = false
    var body: some View {
        ZStack {
            EyeStyle.ink.opacity(0.55).ignoresSafeArea().onTapGesture(perform: cancel)
            VStack(alignment: .leading, spacing: 16) {
                Text("确认这项回答？").font(EyeStyle.title).foregroundStyle(EyeStyle.text)
                Text(choice).font(EyeStyle.chatBody).foregroundStyle(EyeStyle.text)
                Button { dontAsk.toggle() } label: {
                    HStack(spacing: 10) {
                        Image(systemName: dontAsk ? "checkmark.square.fill" : "square").font(EyeStyle.subtitle).foregroundStyle(EyeStyle.primary)
                        Text("不再询问").font(EyeStyle.body).foregroundStyle(EyeStyle.text)
                        Spacer()
                    }.frame(minHeight: 48)
                }.buttonStyle(.plain).accessibilityAddTraits(dontAsk ? [.isSelected] : [])
                HStack(spacing: 12) {
                    Button("取消", action: cancel).buttonStyle(EyeButtonStyle(role: .secondary))
                    Button("确认") { confirm(dontAsk) }.buttonStyle(EyeButtonStyle())
                }
            }.padding(24).eyeGlass().padding(24)
        }
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

/// 对话页顶部：左侧爱科大头像 + 右侧左对齐问候与介绍，不加卡片。与安卓 EyechoIntro 同一排版与文案。
private struct EyechoIntro: View {
    var body: some View {
        HStack(alignment: .top, spacing: 16) {
            Image("EyechoHalf").resizable().scaledToFill()
                .frame(width: EyeStyle.chatIntroPortraitWidth, height: EyeStyle.chatIntroPortraitHeight, alignment: .top).clipped()
                .accessibilityLabel("爱科 Eyecho")
            VStack(alignment: .leading, spacing: 8) {
                Text("你好，我是爱科").font(EyeStyle.title).foregroundStyle(EyeStyle.text)
                Text("爱尔眼科 AI 助手，陪你聊聊眼睛的不适、用眼习惯和自测结果。")
                    .font(EyeStyle.chatBody).foregroundStyle(EyeStyle.secondary).lineSpacing(6)
                    .fixedSize(horizontal: false, vertical: true)
            }.frame(maxWidth: .infinity, alignment: .leading)
        }.frame(maxWidth: .infinity).padding(.vertical, 12)
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
                            if quiet { Image(systemName: "checkmark").font(.system(size: 12, weight: .bold)).foregroundStyle(EyeStyle.onPrimary) }
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
            .eyeGlass()
            .padding(24)
        }
        .accessibilityAddTraits(.isModal)
    }
}

/// 一条对话（信息式气泡）：用户的靠右（品牌蓝底白字、右下角收尖），爱科的靠左（卡片底、左下角收尖，带参考资料）；
/// 提到危险信号时在提问下方给出「建议尽快就医」提示块。与安卓 ChatBubble 一致。
private struct ChatBubble: View {
    let message: [String: Any]
    let urgentNotice: String
    let active: Bool
    let onOption: (String) -> Void
    var body: some View {
        let mine = message["role"] as? String == "user"
        let urgent = message["urgent"] as? Bool ?? false
        VStack(alignment: mine ? .trailing : .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 8) {
                if mine { Spacer(minLength: 48) } else {
                    EyechoAvatar(size: EyeStyle.chatAvatarSize)
                }
                VStack(alignment: .leading, spacing: 6) {
                    Text(message["text"] as? String ?? "").font(EyeStyle.chatBody).foregroundStyle(mine ? EyeStyle.chatUserText : EyeStyle.text)
                        .lineSpacing(6)
                        .textSelection(.enabled)
                    if let q = message["question"] as? [String: Any] {
                        let options = q["options"] as? [String] ?? []
                        Text(q["prompt"] as? String ?? "").font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text)
                        if active {
                            FlowLayout(spacing: 8) {
                                ForEach(options, id: \.self) { option in
                                    ChoiceChip(label: option, selected: false) { onOption(option) }
                                }
                            }
                        }
                    }
                }
                .padding(.horizontal, 14).padding(.vertical, 10)
                // 部署目标 iOS 16：不用 iOS 17 的 UnevenRoundedRectangle，气泡四角同为 18
                .background(mine ? EyeStyle.chatUserBackground : EyeStyle.panel, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                if !mine { Spacer(minLength: 32) }
            }
            if mine && urgent {
                HStack(alignment: .top, spacing: 10) {
                    EyeGlyphView(glyph: .info, color: EyeStyle.danger).frame(width: 20, height: 20).padding(.top, 1)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("建议尽快就医").font(EyeStyle.body.weight(.semibold)).foregroundStyle(EyeStyle.readableText(EyeStyle.danger))
                        Text(urgentNotice).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.text).fixedSize(horizontal: false, vertical: true)
                    }
                }
                .padding(.horizontal, 14).padding(.vertical, 12).frame(maxWidth: .infinity, alignment: .leading)
                .background(EyeStyle.dangerSoft, in: RoundedRectangle(cornerRadius: EyeStyle.radius, style: .continuous))
            }
        }
    }
}

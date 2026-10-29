import SwiftUI

/// 首页「AI 综合建议」入口（放在「今日感受」上方）：像一个输入框，点一下或点快捷提问就进入对话页。与安卓 ChatEntryCard 一致。
struct ChatEntryCard: View {
    let open: () -> Void
    @ObservedObject private var cloud = CloudModel.shared
    var body: some View {
        let info = cloud.chat()
        let suggestions = (info["suggestions"] as? [String] ?? []).prefix(3)
        Panel {
            Button { EyeHaptics.tap(); open() } label: {
                HStack(spacing: 10) {
                    IconTile(glyph: .bulb, tone: .accent, size: 32)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("AI 综合建议").font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text)
                        Text("和小瞳聊聊今天的用眼感受").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                    Image(systemName: "chevron.right").font(.system(size: 14, weight: .semibold)).foregroundStyle(EyeStyle.secondary)
                }.contentShape(Rectangle())
            }.buttonStyle(.plain)
            Button { EyeHaptics.tap(); open() } label: {
                HStack {
                    Text("说说今天眼睛的感受…").font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
                    Spacer()
                    Image(systemName: "paperplane.fill").foregroundStyle(EyeStyle.primary)
                }.padding(.horizontal, 14).frame(minHeight: 46)
                    .background(EyeStyle.background, in: RoundedRectangle(cornerRadius: 23))
            }.buttonStyle(PressScale(scale: 0.98))
            FlowLayout(spacing: 8) {
                ForEach(Array(suggestions), id: \.self) { text in
                    ChoiceChip(label: text, selected: false) { cloud.chatDraft = text; open() }
                }
            }
        }
    }
}

/// 「AI 综合建议」对话页：开场是今天的综合建议卡片，下面和小瞳自由对话；记录只存在本机，可清空。
/// 状态与请求在共用 CloudClient（chatJson / sendChat / clearChat），与安卓 EyeChatScreen 同一规则与文案。
struct AiChatPage: View {
    let openAccount: () -> Void
    @ObservedObject private var cloud = CloudModel.shared
    @ObservedObject private var store = EyeCareStore.shared
    @State private var draft = ""
    @State private var confirmClear = false
    @FocusState private var focused: Bool

    var body: some View {
        let info = cloud.chat()
        let messages = info["messages"] as? [[String: Any]] ?? []
        let sending = info["sending"] as? Bool ?? false
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        AiAdviceView()
                        if messages.isEmpty { suggestions(info) }
                        ForEach(Array(messages.enumerated()), id: \.offset) { _, m in
                            ChatBubble(message: m, urgentNotice: info["urgentNotice"] as? String ?? "")
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
        .onAppear {
            store.refresh()
            if !cloud.chatDraft.isEmpty { draft = cloud.chatDraft; cloud.chatDraft = ""; focused = true }
        }
    }

    private func suggestions(_ info: [String: Any]) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("可以这样问小瞳").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
            FlowLayout(spacing: 8) {
                ForEach(info["suggestions"] as? [String] ?? [], id: \.self) { text in
                    ChoiceChip(label: text, selected: false) { draft = text; focused = true }
                }
            }
        }.padding(.top, 4)
    }

    private var typing: some View {
        HStack(spacing: 8) {
            MascotStage(mood: .happy, interactive: false).frame(width: 34, height: 34)
            Text("小瞳正在想…").font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
        }
    }

    /// 底部：未登录提示去登录；未同意时显示说明与「同意并发送」；否则输入框 + 发送。
    @ViewBuilder private func inputArea(_ info: [String: Any], sending: Bool) -> some View {
        let state = info["state"] as? String ?? "locked"
        let limit = (info["maxInput"] as? NSNumber)?.intValue ?? 300
        VStack(alignment: .leading, spacing: 10) {
            if state == "locked" {
                Text(info["message"] as? String ?? "").font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
                Button("登录 / 注册") { EyeHaptics.tap(); openAccount() }.buttonStyle(EyeButtonStyle())
            } else {
                if state == "consent" {
                    Text(info["consentText"] as? String ?? "").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                }
                HStack(alignment: .bottom, spacing: 10) {
                    TextField("说说今天眼睛的感受…", text: $draft, axis: .vertical)
                        .lineLimit(1...4).font(EyeStyle.body).focused($focused)
                        .padding(.horizontal, 14).padding(.vertical, 11)
                        .background(EyeStyle.background, in: RoundedRectangle(cornerRadius: 22))
                        .onChange(of: draft) { value in if value.count > limit { draft = String(value.prefix(limit)) } }
                    let empty = draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    Button {
                        EyeHaptics.tap()
                        cloud.sendChat(draft, agreeFirst: state == "consent")
                        draft = ""
                    } label: {
                        Text(state == "consent" ? "同意并发送" : "发送").font(EyeStyle.button).foregroundStyle(.white)
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

/// 一条对话：用户的靠右（浅主色底），小瞳的靠左（白底，带参考资料）；提到危险信号时在提问下方醒目提示尽快就医。
private struct ChatBubble: View {
    let message: [String: Any]
    let urgentNotice: String
    var body: some View {
        let mine = message["role"] as? String == "user"
        let urgent = message["urgent"] as? Bool ?? false
        let sources = message["sources"] as? [String] ?? []
        VStack(alignment: mine ? .trailing : .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 8) {
                if mine { Spacer(minLength: 48) } else {
                    MascotStage(mood: .happy, interactive: false).frame(width: 34, height: 34)
                }
                VStack(alignment: .leading, spacing: 6) {
                    Text(message["text"] as? String ?? "").font(EyeStyle.body).foregroundStyle(EyeStyle.text)
                        .textSelection(.enabled)
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

import SwiftUI
import AVFoundation

enum GestureAnswerPreference {
    static let key = "eye.gestureAnswersEnabled"
    static let defaultEnabled = true
    static var isEnabled: Bool { UserDefaults.standard.object(forKey: key) as? Bool ?? defaultEnabled }
}

/// 「系统设置」完整页面（不用系统底部弹窗）：反馈、语音；从顶层页右上角齿轮进入时页面底部还有「关于」（应用图标、版本与免责声明）。
/// 测试进行中以全屏呈现（[inTest]），右上角「完成」返回测试。与安卓 SystemSettingsScreen / TestSettingsPage 同一排版与文案。
struct SystemSettingsPage: View {
    @ObservedObject var voice: EyeVoice
    @ObservedObject var feedback: EyeFeedback
    var inTest = false
    var testVoiceAnswers: Binding<Bool>? = nil
    var showGestureAnswers = true
    var openAccount: () -> Void = {}
    var openPermissions: () -> Void = {}
    var openCloud: () -> Void = {}
    @AppStorage(GestureAnswerPreference.key) private var gestureAnswersEnabled = GestureAnswerPreference.defaultEnabled
    @State private var editor: String?
    @Environment(\.dismiss) private var dismiss
    private let voices = EyeVoice.chineseVoices
    private var version: String {
        guard let name = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String else { return "—" }
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String
        return build.map { "\(name) (\($0))" } ?? name
    }

    private static let feedbackHelp = "作答、换阶段和测试完成时的提示音与震动；护眼操和休息的提示音也跟随这里的开关与音量。"
    private static let voiceHelp = "测试中用语音读出提示。想要更自然的声音：在系统「设置 → 辅助功能 → 朗读内容 → 声音 → 中文（中国大陆）」下载标注「增强」或「优化」的声音，再回到这里更换发声人。"
    private static let answerHelp = "测试中可以直接说「上、下、左、右」或「看不清」作答，不用点屏幕。需要麦克风与语音识别权限。"

    var body: some View {
        SubPage(title: "系统设置") {
            ListGroup(title: "反馈") {
                SwitchRow(title: "音效反馈",isOn: $feedback.soundEnabled)
                if feedback.soundEnabled { ListRow(title: "音效音量",value: "\(Int(feedback.soundVolume*100))%") { editor = "音效音量" } }
                SwitchRow(title: "震动反馈",isOn: $feedback.hapticsEnabled)
                if feedback.hapticsEnabled { ListRow(title: "震动强度",value: "\(Int(feedback.hapticStrength*100))%") { editor = "震动强度" } }
            }
            ListGroup(title: "语音") {
                SwitchRow(title: "语音辅助",isOn: Binding(get: { voice.enabled || voice.answersEnabled },set: { voice.enabled = $0; voice.answersEnabled = $0 }),subtitle: "播报与作答")
                if let testVoiceAnswers {
                    SwitchRow(title: "本次使用语音作答", isOn: Binding(
                        get: { voice.answersEnabled && testVoiceAnswers.wrappedValue },
                        set: { testVoiceAnswers.wrappedValue = $0 }
                    ))
                    .disabled(!voice.answersEnabled)
                }
                if voice.enabled || voice.answersEnabled { ListRow(title: "声音与语速",divider: false) { editor = "声音与语速" } }
            }
            if showGestureAnswers {
                ListGroup(title: "作答方式") {
                    SwitchRow(title: "手势作答", isOn: $gestureAnswersEnabled,
                        subtitle: "食指指向 E 的开口，答完放下手")
                }
            }
            if !inTest {
                ListGroup(title: "通用") {
                    ListRow(title: "账户管理",action: openAccount)
                    ListRow(title: "权限与通知",action: openPermissions)
                    ListRow(title: "云端同步",divider: false,action: openCloud)
                }
                ListGroup(title: "关于") {
                    HStack(spacing: 14) {
                        AppIconView().frame(width: 56, height: 56)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("眼健康助手").font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text)
                            }
                        Spacer()
                    }.padding(16)
                    ListRow(title: "版本", value: version)
                    ListRow(title: "切换登录",action: openAccount)
                    Text("手机初筛结果仅供参考，不能代替医院检查。").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 16).padding(.vertical, 14)
                }
            }
        }
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                if inTest { Button("完成") { EyeHaptics.tap(); dismiss() } }
            }
        }
        .sheet(isPresented: Binding(get: { editor != nil },set: { if !$0 { editor = nil } })) {
            NavigationStack {
                SubPage(title: editor ?? "设置") {
                    if editor == "音效音量" { LevelSlider(title: "音效音量",value: $feedback.soundVolume,range: 0...1) }
                    else if editor == "震动强度" { LevelSlider(title: "震动强度",value: $feedback.hapticStrength,range: 0.1...1) }
                    else {
                        Picker("发声人",selection: $voice.voiceIdentifier) {
                            Text("自动选择").tag("")
                            ForEach(voices,id: \.identifier) { Text($0.name+quality($0)).tag($0.identifier) }
                        }.pickerStyle(.menu)
                        LevelSlider(title: "播报音量",value: $voice.volume,range: 0.1...1)
                        LevelSlider(title: "播报语速",value: $voice.rate,range: 0.7...1.4)
                        Button("检查麦克风与语音权限") { voice.requestPermission() }.font(EyeStyle.body)
                        if !voice.status.isEmpty { Text(voice.status).font(EyeStyle.bodySecondary) }
                    }
                    Button("试听 / 试一下") {
                        if editor == "声音与语速" { voice.speak("请保持合适距离，看视标选方向。") }
                        else { feedback.play(name: "START",sound: "eye_start",microphonePauseMs: 310,voice: voice) }
                    }.buttonStyle(EyeButtonStyle(role: .secondary))
                }.toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { editor = nil } } }
            }.presentationDetents([.medium,.large])
        }
    }

    private func quality(_ voice: AVSpeechSynthesisVoice) -> String {
        switch voice.quality { case .premium: return "（优化）"; case .enhanced: return "（增强）"; default: return "" }
    }
}

/// 设置中的滑块：标题 + 当前百分比 + 主色滑轨，与上方开关的文字对齐。
private struct LevelSlider: View {
    let title: String
    @Binding var value: Double
    let range: ClosedRange<Double>
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(title).font(EyeStyle.body).foregroundStyle(EyeStyle.text)
                Spacer()
                Text("\(Int((value * 100).rounded()))%").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary).monospacedDigit()
            }
            Slider(value: $value, in: range).tint(EyeStyle.primary)
        }.padding(.vertical, 6)
    }
}

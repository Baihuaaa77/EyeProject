import SwiftUI
import AVFoundation

/// 「系统设置」完整页面（不用系统底部弹窗）：反馈、语音；从顶层页右上角齿轮进入时页面底部还有「关于」（应用图标、版本与免责声明）。
/// 测试进行中以全屏呈现（[inTest]），右上角「完成」返回测试。与安卓 SystemSettingsScreen / TestSettingsPage 同一排版与文案。
struct SystemSettingsPage: View {
    @ObservedObject var voice: EyeVoice
    @ObservedObject var feedback: EyeFeedback
    var inTest = false
    @Environment(\.dismiss) private var dismiss
    private let voices = EyeVoice.chineseVoices
    private let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""

    private static let feedbackHelp = "作答、换阶段和测试完成时的提示音与震动；小瞳的互动音效也跟随这里的开关与音量。"
    private static let voiceHelp = "测试中用语音读出提示。想要更自然的声音：在系统「设置 → 辅助功能 → 朗读内容 → 声音 → 中文（中国大陆）」下载标注「增强」或「优化」的声音，再回到这里更换发声人。"
    private static let answerHelp = "测试中可以直接说「上、下、左、右」或「看不清」作答，不用点屏幕。需要麦克风与语音识别权限。"

    var body: some View {
        SubPage(title: "系统设置") {
            ListGroup(title: "反馈") {
                SwitchRow(title: "音效反馈", isOn: $feedback.soundEnabled, glyph: .bell, help: Self.feedbackHelp)
                if feedback.soundEnabled { LevelSlider(title: "音效音量", value: $feedback.soundVolume, range: 0...1) }
                SwitchRow(title: "震动反馈", isOn: $feedback.hapticsEnabled, glyph: .sync, tone: .accent)
                if feedback.hapticsEnabled { LevelSlider(title: "震动强度", value: $feedback.hapticStrength, range: 0.1...1) }
                ListRow(title: "试一下反馈", titleColor: EyeStyle.primary, divider: false,
                        enabled: feedback.soundEnabled || feedback.hapticsEnabled, indent: 50) {
                    feedback.play(name: "START", sound: "eye_start", microphonePauseMs: 310, voice: voice)
                }
            }
            ListGroup(title: "语音") {
                SwitchRow(title: "语音播报", isOn: $voice.enabled, glyph: .chat, help: Self.voiceHelp)
                if voice.enabled {
                    HStack {
                        Text("发声人").font(EyeStyle.body).foregroundStyle(EyeStyle.text)
                        Spacer()
                        Picker("发声人", selection: $voice.voiceIdentifier) {
                            Text("自动选择").tag("")
                            ForEach(voices, id: \.identifier) { item in Text(item.name + quality(item)).tag(item.identifier) }
                        }.pickerStyle(.menu).tint(EyeStyle.secondary)
                    }.padding(.leading, 66).padding(.trailing, 8).frame(minHeight: 52)
                    LevelSlider(title: "播报音量", value: $voice.volume, range: 0.1...1)
                    LevelSlider(title: "播报语速", value: $voice.rate, range: 0.7...1.4)
                    ListRow(title: "试听", titleColor: EyeStyle.primary, indent: 50) { voice.speak("请保持四十厘米，看视标选方向。") }
                }
                SwitchRow(title: "语音作答", isOn: $voice.answersEnabled, glyph: .mic, tone: .success, help: Self.answerHelp,
                          divider: voice.answersEnabled)
                if voice.answersEnabled {
                    if voice.status.isEmpty {
                        ListRow(title: "检查麦克风与语音权限", titleColor: EyeStyle.primary, divider: false, indent: 50) { voice.requestPermission() }
                    } else {
                        ListRow(title: "识别状态", value: voice.status, divider: false, indent: 50)
                    }
                }
            }
            if !inTest {
                ListGroup(title: "关于") {
                    HStack(spacing: 14) {
                        AppIconView().frame(width: 56, height: 56)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("眼健康助手").font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text)
                            Text("你的主动眼健康助手").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                        }
                        Spacer()
                    }.padding(16)
                    ListRow(title: "版本", value: version.isEmpty ? "—" : version)
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
        }.padding(.leading, 66).padding(.trailing, 16).padding(.vertical, 6)
    }
}

import SwiftUI
import AVFoundation

/// 三项测试共用设置入口，状态由 EyeVoice / EyeFeedback 保存到本机偏好。
struct TestSettingsView: View {
    @ObservedObject var voice: EyeVoice
    @ObservedObject var feedback: EyeFeedback
    @Environment(\.dismiss) private var dismiss
    private let voices = EyeVoice.chineseVoices
    var body: some View {
        NavigationStack {
            Form {
                Section("反馈") {
                    Toggle("音效反馈", isOn: $feedback.soundEnabled)
                    Toggle("震动反馈", isOn: $feedback.hapticsEnabled)
                    Button("试一下反馈") {
                        feedback.play(name: "START", sound: "eye_start", microphonePauseMs: 310, voice: voice)
                    }.disabled(!feedback.soundEnabled && !feedback.hapticsEnabled)
                }
                Section {
                    Toggle("语音播报", isOn: $voice.enabled)
                    if voice.enabled {
                        Picker("发声人", selection: $voice.voiceIdentifier) {
                            Text("自动选择").tag("")
                            ForEach(voices, id: \.identifier) { item in
                                Text(item.name + quality(item)).tag(item.identifier)
                            }
                        }
                        Button("试听") { voice.speak("请保持四十厘米，看视标选方向。") }
                    }
                } header: { Text("语音播报") } footer: {
                    Text("想要更自然的声音：在系统「设置 → 辅助功能 → 朗读内容 → 声音 → 中文（中国大陆）」下载标注「增强」或「优化」的声音，再回到这里选择。")
                }
                Section("语音作答") {
                    Toggle("语音作答", isOn: $voice.answersEnabled)
                    if voice.answersEnabled {
                        if !voice.status.isEmpty { Text(voice.status).foregroundStyle(EyeStyle.secondary) }
                        Button("检查麦克风与语音权限") { voice.requestPermission() }
                    }
                }
            }.tint(EyeStyle.primary).navigationTitle("系统设置").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
        }
    }
    private func quality(_ voice: AVSpeechSynthesisVoice) -> String {
        switch voice.quality { case .premium: return "（优化）"; case .enhanced: return "（增强）"; default: return "" }
    }
}

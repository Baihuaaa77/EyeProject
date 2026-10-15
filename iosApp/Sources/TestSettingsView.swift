import SwiftUI

/// 三项测试共用设置入口，状态由 EyeVoice 保存到本机偏好。
struct TestSettingsView: View {
    @ObservedObject var voice: EyeVoice
    @ObservedObject var feedback: EyeFeedback
    let status: String
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            Form {
                Toggle("音效反馈", isOn: $feedback.soundEnabled)
                Toggle("震动反馈", isOn: $feedback.hapticsEnabled)
                Toggle("语音播报", isOn: $voice.enabled)
                Toggle("语音作答", isOn: $voice.answersEnabled)
                    .onChange(of: voice.answersEnabled) { enabled in if enabled { voice.requestPermission() } }
                if voice.answersEnabled {
                    if !status.isEmpty { Text(status).foregroundStyle(EyeStyle.secondary) }
                    Button("检查麦克风与语音权限") { voice.requestPermission() }
                }
            }.navigationTitle("系统设置").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
        }
    }
}

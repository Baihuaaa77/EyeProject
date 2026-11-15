import SwiftUI

/// 当前步骤的方法回看；关闭后由原测试继续，不触发「准备好了」或跳到下一环节。
struct TestHelpView: View {
    let motion: GuideMotion
    let title: String
    let cue: String
    let onClose: () -> Void

    var body: some View {
        VStack(spacing: EyeStyle.spacing) {
            HStack {
                Text("当前环节演示").font(EyeStyle.title)
                Spacer()
                Button(action: onClose) {
                    Image(systemName: "xmark").font(EyeStyle.body).frame(width: 48, height: 48)
                }.accessibilityLabel("关闭演示，继续测试")
            }
            ScrollView {
                TestGuideView(motion: motion, title: title, cue: cue)
            }.scrollIndicators(.hidden)
            Button("看懂了，继续", action: onClose).buttonStyle(EyeButtonStyle())
        }.padding().foregroundStyle(EyeStyle.text).tint(EyeStyle.primary)
            .background(EyeStyle.background.ignoresSafeArea())
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
    }
}

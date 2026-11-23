import SwiftUI

/// 启动时的登录引导：未登录打开 App 时先显示（启动动画之后），可以登录、注册，也可以跳过先用；
/// 登录成功自动进入首页。与安卓 WelcomeScreen 同一排版与文案。
struct WelcomeView: View {
    let done: () -> Void
    @ObservedObject private var cloud = CloudModel.shared

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                AppIconView().frame(width: 72, height: 72).padding(.top, 36)
                VStack(spacing: 8) {
                    Text("眼健康助手").font(EyeStyle.title).foregroundStyle(EyeStyle.text)
                    Text("登录后可同步记录、使用 AI 问诊。")
                        .font(EyeStyle.body).foregroundStyle(EyeStyle.secondary).multilineTextAlignment(.center)
                }.padding(.horizontal, 8)
                SignInForm()
                Button("先体验") { EyeHaptics.tap(); done() }
                    .font(EyeStyle.button).foregroundStyle(EyeStyle.primary).frame(minHeight: 48)
            }
            .padding(.horizontal, 20).padding(.bottom, 28)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(EyeBackdrop())
        .onChange(of: cloud.bool("signedIn")) { signedIn in if signedIn { EyeHaptics.success(); done() } }
    }
}

import SwiftUI

/// 启动时的登录引导：未登录打开 App 时先显示（启动动画之后），可以登录、注册，也可以跳过先用；
/// 登录成功自动进入首页。与安卓 WelcomeScreen 同一排版与文案。
struct WelcomeView: View {
    let done: () -> Void
    @ObservedObject private var cloud = CloudModel.shared

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                MascotStage(mood: .happy, interactive: false, greet: true).frame(width: 150, height: 150).padding(.top, 24)
                VStack(spacing: 8) {
                    Text("欢迎来到眼健康助手").font(EyeStyle.title).foregroundStyle(EyeStyle.text)
                    Text("登录后，测试记录可以加密保存到云端，换手机也不丢；还能和爱科聊聊今天的用眼感受。")
                        .font(EyeStyle.body).foregroundStyle(EyeStyle.secondary).multilineTextAlignment(.center)
                }.padding(.horizontal, 8)
                SignInForm()
                Button("先跳过，随便看看") { EyeHaptics.tap(); done() }
                    .font(EyeStyle.button).foregroundStyle(EyeStyle.primary).frame(minHeight: 48)
                Text("不登录也能做全部自测，记录只保存在这台手机上。以后可在首页右上角的人像里登录。")
                    .font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary).multilineTextAlignment(.center)
            }
            .padding(.horizontal, 20).padding(.bottom, 28)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(LinearGradient(colors: [EyeStyle.primarySoft, EyeStyle.background], startPoint: .top, endPoint: .center).ignoresSafeArea())
        .onChange(of: cloud.bool("signedIn")) { signedIn in if signedIn { EyeHaptics.success(); done() } }
    }
}

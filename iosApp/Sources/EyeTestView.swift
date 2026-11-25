import SwiftUI
import UIKit
import EyeScreening

struct EyeTestView: View {
    @StateObject private var runtime: EyeRuntime
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var pixelsPerMm = UserDefaults.standard.float(forKey: "eye.pixelsPerMm")
    @State private var brightness: CGFloat?
    @State private var previousIdleDisabled = false
    @State private var settingsOpen = false
    @State private var helpOpen = false
    @State private var diagnosticsOpen = false
    @State private var suspended = false
    @State private var confirmingExit = false
    @State private var darkControlsHeight: CGFloat = 230
    init(kind: String) { _runtime = StateObject(wrappedValue: EyeRuntime(kind: kind)) }
#if SCREENSHOT_CAPTURE
    private var captureOnly = false
    /// 只在专用截图构建中注入呈现快照，不启动相机、计时或记录保存。
    init(captureKind: String, snapshot: [String: Any], calibrated: Bool = true, exitDialog: Bool = false) {
        let value = EyeRuntime(kind: captureKind)
        value.controller.selectTest(name: captureKind)
        value.state = snapshot
        _runtime = StateObject(wrappedValue: value)
        _pixelsPerMm = State(initialValue: calibrated ? 18 : 0)
        _confirmingExit = State(initialValue: exitDialog)
        captureOnly = true
    }
#endif

    var body: some View {
        Group {
        if isReflectionTask { darkGazeSession } else {
        VStack(spacing: 0) {
            TestTopBar(title: runtime.string("title").isEmpty ? "眼健康自测" : runtime.string("title"),
                onBack: { if needsExitConfirm { confirmingExit = true } else { runtime.close(); dismiss() } },
                onSettings: { settingsOpen = true },
                onHelp: isProfile || ["Result", "RESULT"].contains(phase) ? nil : { runtime.setHelpPresented(true); helpOpen = true })
                .padding(.horizontal, 4)
            if let progress = runtime.state["overallProgress"] as? NSNumber, displayReady {
                TestProgressBar(fraction: progress.doubleValue, text: runtime.string("remainingText"))
                    .padding(.horizontal, 16).padding(.bottom, 8)
                    .transition(.opacity)
            }
            if showsDistance && !distanceAtBottom {
                TestDistanceBanner(runtime: runtime).padding(.horizontal, 16).padding(.top, 4)
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }
            GeometryReader { viewport in
                if isRunning {
                    // 进行中的任务：放得下时舞台伸展占满剩余高度，避免卡片上下大片留白；放不下时滚动。
                    ViewThatFits(in: .vertical) {
                        pageContent.environment(\.stageFill, true).padding()
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                        ScrollView {
                            pageContent.padding().frame(maxWidth: .infinity, minHeight: viewport.size.height)
                        }
                    }
                } else {
                    ScrollView {
                        pageContent.padding().frame(maxWidth: .infinity, minHeight: viewport.size.height, alignment: .top)
                    }
                }
            }
            // 视标类任务（近视力、移近法、多距离视力、翻转拍）：提示浮在上方淡入，不把视标卡片往下挤（与安卓 TestNoticeOverlay 一致）
            .overlay(alignment: .top) {
                if isOpticTask { TestNotice(text: noticeText, floating: true).padding(.horizontal).padding(.top, 8) }
            }
            .animation(.easeInOut(duration: 0.28), value: isOpticTask ? noticeText : "")
            // 斜视：距离条放到底部，注视目标紧贴顶栏下方（与安卓一致）
            if showsDistance && distanceAtBottom {
                TestDistanceBanner(runtime: runtime).padding(.horizontal, 16).padding(.bottom, 8)
                    .transition(.opacity.combined(with: .move(edge: .bottom)))
            }
        }
        }
        }.foregroundStyle(EyeStyle.text).tint(EyeStyle.primary)
            .overlay {
                if phase == "OpenCue" {
                    ZStack {
                        EyeStyle.background.ignoresSafeArea()
                        Panel {
                            VStack(spacing: 12) {
                                Text("持续睁眼 · 20 秒").font(EyeStyle.subtitle)
                                Text(runtime.string("instruction")).font(EyeStyle.headline)
                                    .foregroundStyle(EyeStyle.readableText(EyeStyle.warning)).padding(.vertical, 20)
                                Text("提示消失后开始计时").font(EyeStyle.body)
                            }.multilineTextAlignment(.center).frame(maxWidth: .infinity)
                        }.padding(.horizontal, 16)
                    }.accessibilityElement(children: .combine)
                }
            }
            .overlay {
                if confirmingExit {
                    TestExitConfirm(diagnosticSaved: runtime.kind == "STRABISMUS", stay: { confirmingExit = false }, exit: { confirmingExit = false; runtime.close(); dismiss() })
                }
            }
            // 干眼测试中异常暂停（方案二）：盖住整页，恢复正常后点「继续测试」
            .overlay {
                if runtime.bool("paused") {
                    DryEyePauseView(reason: runtime.string("pauseReason"), ready: runtime.bool("gateReady"),
                                    resume: { runtime.act("resume") }, stop: { runtime.act("restart") })
                }
            }
            // 全屏呈现：背景铺满到灵动岛与底部，内容留在安全区内。
            .background(EyeStyle.background.ignoresSafeArea())
            // 系统设置以完整页面盖在测试上（不用底部弹窗），右上角「完成」回到测试
            .fullScreenCover(isPresented: $settingsOpen) {
                NavigationStack {
                    SystemSettingsPage(voice: runtime.voice, feedback: runtime.feedback, inTest: true,
                        testVoiceAnswers: runtime.kind == "PRESBYOPIA" ? $runtime.voiceAnswersForThisTest : nil,
                        showGestureAnswers: ["DRY_EYE", "PRESBYOPIA"].contains(runtime.kind))
                }.tint(EyeStyle.primary)
            }
            .sheet(isPresented: $diagnosticsOpen) { StrabismusDiagnosticLibrary() }
            .sheet(isPresented: $helpOpen, onDismiss: { runtime.setHelpPresented(false) }) {
                TestHelpView(motion: .forHelp(runtime.string("helpMotion")), title: runtime.string("helpTitle"), cue: runtime.string("helpCue")) {
                    helpOpen = false; runtime.setHelpPresented(false)
                }
            }
            .onChange(of: settingsOpen) { runtime.setSettingsPresented($0) }
            .onChange(of: confirmingExit) { runtime.setExitConfirmationPresented($0) }
            .onAppear {
#if SCREENSHOT_CAPTURE
                guard !captureOnly else { return }
#endif
                brightness = UIScreen.main.brightness; UIScreen.main.brightness = 1
                previousIdleDisabled = UIApplication.shared.isIdleTimerDisabled
                UIApplication.shared.isIdleTimerDisabled = true
                if displayReady { runtime.prepare(pixelsPerMm: pixelsPerMm > 0 ? pixelsPerMm : Self.nominalPixelsPerMm) }
            }
            .onDisappear {
#if SCREENSHOT_CAPTURE
                guard !captureOnly else { return }
#endif
                runtime.close(); UIApplication.shared.isIdleTimerDisabled = previousIdleDisabled
                if let value = brightness { UIScreen.main.brightness = value }
            }
            .onChange(of: phase) { _ in UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder),to: nil,from: nil,for: nil) }
            .onChange(of: scenePhase) { phase in
#if SCREENSHOT_CAPTURE
                guard !captureOnly else { return }
#endif
                // 只在真正进入后台时中断；权限弹窗、控制中心等短暂失焦（inactive）不打断测试。
                if phase == .background {
                    if !suspended { suspended = true; runtime.interrupt() }
                    if let value = brightness { UIScreen.main.brightness = value }
                } else if phase == .active {
                    UIScreen.main.brightness = 1
                    if suspended { suspended = false; runtime.resume() }
                }
            }
    }
    private var darkStageCaption: String {
        runtime.bool("stabilityInterrupted") ? "本段已中断" : phase == "COVER" ? runtime.string("coverPrompt")
                     : phase == "FAR_FIXATION" ? "越过月夜萤火虫看 6 米外 · \(Int(runtime.number("secondsLeft")))秒"
                     : "双眼注视萤火虫 · \(Int(runtime.number("secondsLeft")))秒"
    }
    private var darkGazeSession: some View {
        ZStack(alignment: .bottom) {
            EyeStyle.Game.Firefly.skyTop.ignoresSafeArea()
            GazeTargetView(runtime: runtime, pixelsPerMm: pixelsPerMm)
                .padding(.bottom, darkControlsHeight)
            VStack(alignment: .leading, spacing: 8) {
                Text(darkStageCaption)
                    .font(EyeStyle.subtitle).foregroundStyle(EyeStyle.reflectionTarget)
                if phase == "COVER", runtime.bool("coverHolding"), runtime.number("coverHoldMs") > 0 {
                    HoldProgress(key: Int(runtime.number("coverDone")), holdMs: runtime.number("coverHoldMs"),
                                 remainingMs: runtime.number("coverRemainingMs"), label: "保持当前动作", dark: true)
                }
                if runtime.bool("stabilityInterrupted") {
                    Text(runtime.string("stabilityReason")).font(EyeStyle.body).foregroundStyle(EyeStyle.testTooClose)
                    Button(runtime.bool("stabilityReady") ? "已稳定，重新开始本段" : "请回到原位并保持稳定") { runtime.act("restartStableStage") }
                        .buttonStyle(EyeButtonStyle()).disabled(!runtime.bool("stabilityReady"))
                    Button("重新开始整个测试") { runtime.act("restart") }.buttonStyle(EyeButtonStyle(role: .secondary))
                } else if runtime.bool("farWaiting") {
                    Button("已在看远处，开始") { runtime.act("farStart") }.buttonStyle(EyeButtonStyle())
                } else if runtime.bool("coverRetryAvailable") {
                    Button("已做好动作，继续") { runtime.act("cover") }.buttonStyle(EyeButtonStyle())
                    Button("重新识别本段") { runtime.act("retryCover") }.buttonStyle(EyeButtonStyle(role: .secondary))
                }
                if phase == "COVER", !runtime.bool("coverHolding"), !runtime.string("warning").isEmpty {
                    Text(runtime.string("warning")).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.reflectionTarget)
                }
                TestDistanceBanner(runtime: runtime)
                HStack {
                    Button("退出") { confirmingExit = true }
                    Spacer()
                    Button("说明") { runtime.setHelpPresented(true); helpOpen = true }
                    Spacer()
                    Button("设置") { settingsOpen = true }
                }.font(EyeStyle.body).padding(.vertical, 8)
            }.padding(16).background(GeometryReader { geometry in Color.clear.preference(key: DarkControlsHeight.self,value: geometry.size.height) })
        }.onPreferenceChange(DarkControlsHeight.self) { darkControlsHeight = $0 }
    }
    @State private var practice: OfflineSession?
    private var pageContent: some View {
        VStack(spacing: EyeStyle.spacing) {
            if !displayReady {
                ScreenCalibrationView { value in
                    pixelsPerMm = value; UserDefaults.standard.set(value, forKey: "eye.pixelsPerMm")
                    runtime.prepare(pixelsPerMm: value)
                }
                if let session = OfflineSession(test: runtime.kind) {
                    Button("无相机试玩") { practice = session }.buttonStyle(EyeButtonStyle(role: .secondary))
                }
            } else {
                // 注视类任务的提示位放在舞台下方：舞台紧贴顶部，提示出现与否不挪动目标
                if !isGazeTask && !isOpticTask { TestNotice(text: noticeText) }
                if isProfile {
                    if runtime.kind == "STRABISMUS" { Text("诊断视频仅保存在本机，可在测试记录回看和导出。").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary) }
                    SubjectPickerView(runtime: runtime)
                }
                else if runtime.kind == "COLOR_VISION" { ColorVisionContent(runtime: runtime) }
                else if isRunning { VStack(spacing: EyeStyle.spacing) { testContent }.modifier(TestStagePanel(expanded: phase == "PURSUIT")) }
                else { testContent }
                if isGazeTask { TestNoticeSlot(text: noticeText) }
            }
        }.fullScreenCover(item: $practice) { OfflineSessionCover(session: $0) }
        .animation(.easeInOut(duration: 0.28), value: isOpticTask ? "" : noticeText)
    }
    private var noticeText: String { runtime.error ?? runtime.cameraNote ?? warningText }
    /// 视标类任务：提示浮层显示，不占布局
    private var isOpticTask: Bool {
        pixelsPerMm > 0 && !runtime.bool("guidePending") && ["NEAR_VISION","PUSH_UP","DISTANCE_CURVE","FLIPPER"].contains(phase)
    }
    private var phase: String { runtime.string("phase") }
    /// 色觉自测不需要毫米级视标尺寸，不必先用尺子校准屏幕；其余测试校准后才能开始。
    private var displayReady: Bool { pixelsPerMm > 0 || runtime.kind == "COLOR_VISION" }
    /// 未校准时的名义像素密度（约 460 ppi），只用于记录，色觉判定与之无关。
    private static let nominalPixelsPerMm: Float = Float(UIScreen.main.scale) * 163 / 25.4
    private var warningText: String {
        let text = runtime.string("warning")
        if text == "慢慢移近" { return "" }
        if runtime.number("targetDistanceCm") > 0 && (text.hasPrefix("太近") || text.hasPrefix("太远") || text == "请正对屏幕") { return "" }
        return text
    }
    private var isProfile: Bool { phase == "Profile" || phase == "PROFILE" }
    /// 注视类任务：提示位固定高度，画布位置不随提示变化
    private var isGazeTask: Bool {
        pixelsPerMm > 0 && !runtime.bool("guidePending") && ["FIXATION","FAR_FIXATION","COVER","PURSUIT"].contains(phase)
    }
    /// 映光采集使用黑底白色光源；眼动跟随使用黑底主题橙色圆点。
    private var isReflectionTask: Bool {
        pixelsPerMm > 0 && !runtime.bool("guidePending") && ["FIXATION","FAR_FIXATION","COVER"].contains(phase)
    }
    private var showsDistance: Bool { pixelsPerMm > 0 && runtime.number("targetDistanceCm") > 0 && !runtime.bool("guidePending") }
    private var distanceAtBottom: Bool { runtime.kind == "STRABISMUS" }
    /// 测试进行中（选人页、准备页、结果页以外）点返回先确认；规则与安卓各测试页一致。
    private var needsExitConfirm: Bool {
        !phase.isEmpty && !["Profile", "PROFILE", "Setup", "ENVIRONMENT", "Result", "RESULT"].contains(phase)
    }
    private var isRunning: Bool {
        pixelsPerMm > 0 && !runtime.bool("guidePending") && ["Practice","Natural","OpenCue","Open","Countdown","Rest",
            "NEAR_VISION","PUSH_UP","DISTANCE_CURVE","FLIPPER","FIXATION","FAR_FIXATION","COVER","PURSUIT"].contains(phase)
    }
    @ViewBuilder private var testContent: some View {
        if phase == "Setup" || phase == "ENVIRONMENT" {
            TestGuideView(motion: runtime.kind == "STRABISMUS" ? .fixate : .posture, title: "准备测试",
                cue: runtime.kind == "DRY_EYE" ? "戴平时看得清的眼镜，正对屏幕，距离约 40 cm。"
                    : runtime.kind == "STRABISMUS" ? "正对屏幕，约 33 厘米" : "正对屏幕，约 40 厘米")
            Button("开始测试") { runtime.act("begin") }.buttonStyle(EyeButtonStyle())
            Button("重试相机") { runtime.act("retryCamera") }.buttonStyle(EyeButtonStyle(role: .secondary))
            DisclosureGroup("测距校准") {
                Text("用尺确认眼睛到手机为 40 cm，正对手机且距离读数稳定后点按。")
                Button("此刻实际为 40 cm，校准测距") {
                    let measured = runtime.number("distanceCm")
                    if measured > 10 && measured < 100 {
                        runtime.camera.distanceScale *= Float(40 / measured)
                        UserDefaults.standard.set(true, forKey: "eye.distanceCalibrated")
                    }
                }.disabled(runtime.number("distanceCm") <= 0)
            }
        } else if phase == "PracticeGuide" {
            TestGuideView(motion: .acuity, title: "视标练习 · 约 12 秒", cue: "先练习一下：2 秒内点选 E 的开口方向；练习不计入结果") {
                runtime.act("practice")
            }
        } else if phase == "FormalGuide" {
            let seconds = max(1, Int(runtime.number("naturalSeconds")))
            if runtime.bool("afterRest") {
                Text("休息结束，下面进行复测，流程与首次相同").font(EyeStyle.body).frame(maxWidth: .infinity, alignment: .leading)
                    .padding(14).background(EyeStyle.accentSoft, in: RoundedRectangle(cornerRadius: EyeStyle.radiusMedium))
            }
            TestGuideView(motion: .blink, title: "护眼科普动画 · \(seconds) 秒 + 20 秒",
                cue: "观看多幕护眼科普动画并收听中文讲解。前 \(seconds) 秒像平时一样自然眨眼，看到提示后尽量保持睁眼 20 秒", readyTitle: "开始观看护眼动画") {
                runtime.act("formal")
            }
        } else if runtime.bool("guidePending") && runtime.bool("glassesCheck") {
            GlassesCheckView(title: runtime.string("glassesTitle"), prompt: runtime.string("guideText"),
                button: runtime.string("glassesButton")) { runtime.act("glassesConfirmed") }
        } else if runtime.bool("guidePending") {
            TestGuideView(motion: .forStep(phase, selfCover: runtime.state["coverSelf"] as? Bool ?? false), title: runtime.string("stepTitle"),
                cue: runtime.string("guideText").isEmpty ? runtime.string("instruction") : runtime.string("guideText")) {
                runtime.act("guide")
            }
            // 远近切换是可选的探索性检查
            if phase == "FLIPPER" { Button("跳过，查看结果") { runtime.act("skipFlipper") }.buttonStyle(EyeButtonStyle(role: .secondary)) }
        } else if phase == "Result" || phase == "RESULT" {
            // 结论、关键指标、判断依据、本次数据与导出：与安卓共用 ResultReport 内容
            ResultReportView(runtime: runtime)
            if runtime.kind == "STRABISMUS" {
                Button("回看诊断视频") { diagnosticsOpen = true }.buttonStyle(EyeButtonStyle(role: .secondary))
            }
        } else if phase == "FAR_FIXATION" {
            let meters = Int(runtime.number("farTargetM"))
            let waiting = runtime.bool("farWaiting")
            FarFixationPanel(seconds: Int(runtime.number("secondsLeft")), meters: meters, waiting: waiting)
            StageCaption(text: waiting ? "看远处 · 准备" : "剩余 \(Int(runtime.number("secondsLeft"))) 秒")
            if waiting {
                Button("已在看 \(meters) 米外的目标，开始") { EyeHaptics.tap(); runtime.act("farStart") }.buttonStyle(EyeButtonStyle())
            }
        } else if phase == "FIXATION" || phase == "COVER" || phase == "PURSUIT" {
            GazeTargetView(runtime: runtime, pixelsPerMm: pixelsPerMm)
            StageCaption(text: phase == "COVER" ? "交替遮盖 · \(Int(runtime.number("coverDone")))/9" : "剩余 \(Int(runtime.number("secondsLeft"))) 秒")
            if runtime.bool("stabilityInterrupted") {
                Button(runtime.bool("stabilityReady") ? "已稳定，重新开始本段" : "请回到原位并保持稳定") { runtime.act("restartStableStage") }
                    .buttonStyle(EyeButtonStyle()).disabled(!runtime.bool("stabilityReady"))
                Button("重新开始整个测试") { runtime.act("restart") }.buttonStyle(EyeButtonStyle(role: .secondary))
            }
            if phase == "COVER" { CoverPanel(runtime: runtime) }
        } else if phase == "OpenCue" {
            EmptyView()
        } else if phase == "Countdown" {
            CountdownRing(seconds: Int(runtime.number("secondsLeft")))
        } else if phase == "Rest" {
            Text("闭眼休息").font(EyeStyle.title)
            TimerRing(progress: 1 - runtime.number("progress")) {
                Text("\(Int(runtime.number("secondsLeft")))").font(.system(size: 56, weight: .semibold, design: .rounded))
                    .monospacedDigit().foregroundStyle(EyeStyle.readableText(EyeStyle.primary))
            }.frame(width: 184, height: 184).animation(.linear(duration: 0.25), value: runtime.number("progress"))
        } else if phase == "Natural" || phase == "Open" {
            DryEyeStoryTheaterView(runtime: runtime)
        } else {
            if phase == "Practice" {
                HStack {
                    Text("练习")
                    Spacer(); Text("\(Int(runtime.number("secondsLeft"))) 秒").monospacedDigit()
                }
            } else if phase == "NEAR_VISION" {
                let eye = ["RIGHT": "右眼", "LEFT": "左眼", "BOTH": "双眼"][runtime.string("eye")] ?? ""
                Text("星空邮局 · 第 \(Int(runtime.number("visionRound")))/3 轮 · \(eye)").font(EyeStyle.subtitle)
                if runtime.bool("presbyopiaCoverReady"), !runtime.bool("resolutionAdvancePending") {
                    StageCaption(text: "第 \(min(4, Int(runtime.number("visionQuestion"))))/4 封信 · 看信封上的 E 选邮筒")
                }
            } else if phase == "DISTANCE_CURVE" {
                Text("星空邮局 · 第 \(Int(runtime.number("visionRound")))/3 轮 · \(Int(runtime.number("targetDistanceCm"))) cm").font(EyeStyle.subtitle)
                if !runtime.bool("resolutionAdvancePending") {
                    StageCaption(text: "第 \(Int(runtime.number("visionQuestion")))/3 封信 · 看信封上的 E 选邮筒")
                }
            } else if phase == "FLIPPER" {
                HStack {
                    Text((runtime.bool("flipperCheck") ? "先确认看得清 · " : "星空邮局 · ") + "\(Int(runtime.number("targetDistanceCm"))) cm")
                    Spacer()
                    if phase == "FLIPPER" && !runtime.bool("flipperCheck") { Text("\(Int(runtime.number("secondsLeft"))) 秒").monospacedDigit() }
                }
            } else if phase == "PUSH_UP" {
                Text("星空邮局 · 第 \(Int(runtime.number("visionRound")))/2 轮 · \(runtime.string("pushEye"))").font(EyeStyle.subtitle)
                StageCaption(text: "本眼第 \(min(Int(runtime.number("pushDone")) + 1, 3))/3 次")
            }
            if runtime.bool("resolutionAdvancePending") {
                Text(runtime.string("resolutionLimitMessage"))
                Button("继续") { runtime.act("advanceAfterDisplayLimit") }.buttonStyle(EyeButtonStyle())
            } else if phase == "NEAR_VISION" && (runtime.bool("nearAwaitingEyeReady") || !runtime.bool("presbyopiaCoverReady")) {
                let hint = runtime.string("presbyopiaCoverHint")
                Text(hint.isEmpty ? "请按提示遮眼，等待相机确认" : hint).font(EyeStyle.subtitle)
            } else if phase == "NEAR_VISION" && runtime.bool("nearRetryRequired") {
                Button("重做本题") { runtime.act("retryNearQuestion") }.buttonStyle(EyeButtonStyle())
            } else if phase == "PUSH_UP" && runtime.string("pushStage") == "READY" {
                let hint = runtime.string("presbyopiaCoverHint")
                Text(hint.isEmpty ? (runtime.string("pushEye") == "左眼" ? "遮住右眼，用左眼看" : "遮住左眼，用右眼看") + "；先移到 45 cm 以外" : hint)
                Button("开始第 \(Int(runtime.number("pushDone")) + 1) 次") { runtime.act("pushStart") }
                    .buttonStyle(EyeButtonStyle()).disabled(!runtime.bool("presbyopiaCoverReady") ||
                        runtime.number("distanceCm") < 45)
            } else {
                if phase == "PUSH_UP" {
                    Text("缓慢移近信封，每秒一两厘米")
                    PostOfficeEnvelopeView(runtime: runtime)
                        .stageHeight(max(196, CGFloat(runtime.number("optotypePx")) / UIScreen.main.scale + 104))
                    Button("开始持续模糊了") { runtime.act("blur") }.buttonStyle(EyeButtonStyle())
                    if runtime.bool("pushStillClearAvailable") {
                        Button("仍看得清") { runtime.act("pushStillClear") }.buttonStyle(EyeButtonStyle(role: .secondary))
                    }
                } else if phase == "Practice" {
                    OptotypeAnswerPad(runtime: runtime)
                } else {
                    PostOfficeAnswerPad(runtime: runtime)
                }
            }
        }
    }
}

private struct ScreenCalibrationView: View {
    let complete: (Float) -> Void
    @State private var length: Double = 280
    var body: some View {
        VStack(spacing: EyeStyle.spacing) {
            Text("先校准屏幕尺寸").font(EyeStyle.title)
            Text("用尺量下面的线段，调整到实际长度正好为 5 cm。完成后这台手机会记住设置。")
            Rectangle().fill(EyeStyle.text).frame(width: length, height: 3).padding(.vertical, 24)
            Slider(value: $length, in: 140...Double(UIScreen.main.bounds.width - 40))
            Button("已量好：线段为 5 cm") { complete(Float(length * UIScreen.main.scale / 50)) }.buttonStyle(EyeButtonStyle())
        }
    }
}

/// 视标单独位于上方白色刺激区；下方十字作答键：「跳过」居中，上下左右四键环绕。
/// 留白环按视标对角线取径，大视标的四角也完整落在环内。
private func ringDiameter(_ edge: CGFloat) -> CGFloat { max(96, edge * 1.42 + 16) }

private struct OptotypeAnswerPad: View {
    @ObservedObject var runtime: EyeRuntime
    var body: some View {
        VStack(spacing: 14) {
            TumblingEView(runtime: runtime)
                .stageHeight(max(152, ringDiameter(CGFloat(runtime.number("optotypePx")) / UIScreen.main.scale) + 24))
                // 视标恒为白底黑字（与安卓一致，深色模式下不变）
                .background(EyeStyle.optotypeSurround, in: RoundedRectangle(cornerRadius: EyeStyle.radiusHero, style: .continuous))
            VStack(spacing: AnswerKey.gap) {
                arrow("向上", 3)
                HStack(spacing: AnswerKey.gap) {
                    arrow("向左", 2)
                    Button { runtime.answer(-1) } label: { Text("跳过") }
                        .buttonStyle(SkipKeyStyle()).accessibilityHint("看不清时跳过本题")
                    arrow("向右", 0)
                }
                arrow("向下", 1)
            }
        }.disabled(!runtime.bool("trialVisible"))
    }
    /// 基准箭头朝右，按视标方向编码旋转：0=右 1=下 2=左 3=上；手势命中时同步高亮并显示确认进度环。
    private func arrow(_ label: String, _ direction: Int) -> some View {
        let visible = runtime.bool("trialVisible")
        let matched = visible && (runtime.state["gestureDirection"] as? NSNumber)?.intValue == direction
        let needsRelease = matched && runtime.bool("gestureNeedsRelease")
        let holding = matched && !needsRelease
        let progress = holding ? max(0.08, min(1, runtime.number("gestureProgress"))) : 0
        return Button { runtime.answer(direction) } label: {
            ArrowGlyph().stroke(holding ? EyeStyle.primary : EyeStyle.text,
                style: StrokeStyle(lineWidth: 24 * 0.11, lineCap: .round, lineJoin: .round))
                .frame(width: 24, height: 24).rotationEffect(.degrees(Double(direction) * 90))
        }
        .buttonStyle(DirectionKeyStyle(highlighted: holding))
        .overlay {
            if holding {
                Circle().stroke(EyeStyle.primary.opacity(0.18), lineWidth: 3).padding(1.5).allowsHitTesting(false)
                Circle().trim(from: 0, to: CGFloat(progress))
                    .stroke(EyeStyle.primary, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .padding(1.5)
                    .animation(.easeOut(duration: 0.1), value: progress)
                    .allowsHitTesting(false)
            } else if needsRelease {
                Circle().stroke(EyeStyle.primary.opacity(0.35), lineWidth: 2).padding(1).allowsHitTesting(false)
            }
        }
        .accessibilityLabel(label)
    }
}

private struct TumblingEView: View {
    @ObservedObject var runtime: EyeRuntime
    var body: some View {
        Canvas { context, size in
            let edge = max(0, CGFloat(runtime.number("optotypePx")) / UIScreen.main.scale)
            let diameter = ringDiameter(edge)
            context.fill(Path(ellipseIn: CGRect(x: (size.width - diameter) / 2, y: (size.height - diameter) / 2,
                width: diameter, height: diameter)), with: .color(EyeStyle.optotypeField))
            guard runtime.bool("trialVisible"), edge > 0 else { return }
            context.translateBy(x: size.width / 2, y: size.height / 2)
            context.rotate(by: .degrees(Double(runtime.number("direction")) * 90))
            let bar = edge / 5
            context.fill(Path(CGRect(x: -edge / 2, y: -edge / 2, width: bar, height: edge)), with: .color(.black))
            for row in [0, 2, 4] { context.fill(Path(CGRect(x: -edge / 2, y: -edge / 2 + CGFloat(row) * bar, width: edge, height: bar)), with: .color(.black)) }
        }
            .overlay {
                let diameter = ringDiameter(CGFloat(runtime.number("optotypePx")) / UIScreen.main.scale)
                Circle().stroke(EyeStyle.primary.opacity(runtime.bool("trialVisible") ? 0.72 : 0.2), lineWidth: 2)
                    .frame(width: diameter, height: diameter).allowsHitTesting(false)
                    .animation(.easeOut(duration: 0.16), value: runtime.bool("trialVisible"))
            }
            .onChange(of: runtime.number("trialId")) { id in
                runtime.schedulePresented(Int32(id))
            }
            .onAppear { runtime.schedulePresented(Int32(runtime.number("trialId"))) }
    }
}

/// 与安卓 CoverContent 一致：当前动作的保持进度条 → 下一步 → 序列进度 → 点按键。
/// 保持期间不播报，保持结束时由震动与音效提示，并只读出下一动作。
private struct CoverPanel: View {
    @ObservedObject var runtime: EyeRuntime
    var body: some View {
        let done = Int(runtime.number("coverDone"))
        let next = runtime.string("coverNext")
        VStack(alignment: .leading, spacing: 10) {
            if runtime.state["coverHolding"] as? Bool == true, done > 0, runtime.number("coverHoldMs") > 0 {
                HoldProgress(key: done, holdMs: runtime.number("coverHoldMs"), remainingMs: runtime.number("coverRemainingMs"), label: "保持：\(runtime.string("coverCurrent"))")
            }
            if runtime.state["coverHolding"] as? Bool != true { Text(next.isEmpty ? "遮盖完成" : "下一步：\(next)").font(EyeStyle.title) }
            CoverSequenceStrip(done: done)
            Text(next.isEmpty ? "遮盖完成" : runtime.state["coverHolding"] as? Bool == true ? "相机已识别，请保持当前动作" : "等待相机识别：\(next)").font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
            if runtime.state["coverRetryAvailable"] as? Bool == true {
                Button("重新识别本段") { runtime.act("retryCover") }.buttonStyle(EyeButtonStyle(role: .secondary))
            }
        }
    }
}

/// 当前动作需保持的时长（原文 2 秒 / 交替 1 秒）：进度条走满后变为「可以进行下一步」。
private struct HoldProgress: View {
    let key: Int
    let holdMs: Double
    let remainingMs: Double
    let label: String
    var dark: Bool = false
    @State private var progress = 0.0
    @State private var finished = false
    @State private var generation = 0
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(finished ? "可以进行下一步" : label).font(EyeStyle.body)
                .foregroundStyle(dark ? EyeStyle.reflectionTarget : finished ? EyeStyle.testReady : EyeStyle.secondary)
            GeometryReader { geometry in
                ZStack(alignment: .leading) {
                    EyeStyle.chipShape.fill(EyeStyle.surfaceVariant)
                    EyeStyle.chipShape.fill(EyeStyle.primary).frame(width: geometry.size.width * progress)
                }
            }.frame(height: 6)
        }
        .onAppear(perform: restart)
        .onChange(of: key) { _ in restart() }
    }
    private func restart() {
        var snap = Transaction(); snap.disablesAnimations = true
        withTransaction(snap) { progress = min(max(1 - remainingMs / max(holdMs, 1), 0), 1); finished = false }
        generation += 1
        let token = generation
        DispatchQueue.main.async {
            withAnimation(.linear(duration: max(remainingMs, 0) / 1000)) { progress = 1 }
            // 动画数值不会触发文字切换，到点后单独标记完成；期间换到下一动作则作废。
            DispatchQueue.main.asyncAfter(deadline: .now() + max(remainingMs, 0) / 1000) { if token == generation { finished = true } }
        }
    }
}

/// 遮盖序列一览：已完成主色、当前强调色、待做浅色；2 秒动作的色块是 1 秒动作的两倍宽。
private struct CoverSequenceStrip: View {
    let done: Int
    private let weights: [CGFloat] = [2, 2, 2, 2, 1, 1, 1, 1, 1]
    var body: some View {
        GeometryReader { geometry in
            let unit = (geometry.size.width - CGFloat(weights.count - 1) * 4) / weights.reduce(0, +)
            HStack(spacing: 4) {
                ForEach(weights.indices, id: \.self) { index in
                    RoundedRectangle(cornerRadius: 4)
                        .fill(index < done ? EyeStyle.primary : index == done ? EyeStyle.accent : EyeStyle.surfaceVariant)
                        .frame(width: unit * weights[index], height: 8)
                }
            }
        }.frame(height: 8)
    }
}

/// 看远：受试者越过手机看 6 米外的目标，屏幕是给协助者看的。舞台用深色，避免整块亮屏在角膜上映出一大片光，
/// 只留顶部的映光点；大号倒计时，最后 3 秒有节拍音、结束有提示音。与安卓 FarFixationContent 一致。
private struct FarFixationPanel: View {
    let seconds: Int
    let meters: Int
    /// 等协助者确认已经在看远处：此时不计时、不采集
    let waiting: Bool
    var body: some View {
        VStack(spacing: 10) {
            EyechoGuideImage(name: "eyecho_guide_open")
                .frame(width: 64, height: 64).accessibilityLabel("爱科")
            if waiting {
                Text("看着 \(meters) 米外的目标").font(EyeStyle.title).foregroundStyle(.white).multilineTextAlignment(.center)
                Text("看好以后，点下面的按钮开始计时").font(EyeStyle.bodySecondary).foregroundStyle(.white.opacity(0.72))
                    .multilineTextAlignment(.center)
            } else {
                Text("\(seconds)").font(EyeStyle.headline).monospacedDigit().foregroundStyle(.white)
                Text("眼睛越过手机，看着远处的目标").font(EyeStyle.subtitle).foregroundStyle(.white).multilineTextAlignment(.center)
                Text("本人看远处，头和手机不动").font(EyeStyle.bodySecondary).foregroundStyle(.white.opacity(0.72))
                    .multilineTextAlignment(.center)
            }
        }
        .padding(20).frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(EyeStyle.ink, in: RoundedRectangle(cornerRadius: EyeStyle.radius))
        .stageHeight(240)
    }
}

/// 注视类舞台下方的一行说明（计时、进度）：舞台本身紧贴顶部。与安卓 StageCaption 一致。
private struct StageCaption: View {
    let text: String
    var body: some View {
        Text(text).font(EyeStyle.subtitle).foregroundStyle(EyeStyle.secondary).monospacedDigit().frame(maxWidth: .infinity)
    }
}

/// 测试中点返回：屏幕中间弹出确认框。「继续测试」关闭，「确认退出」才离开。与安卓 TestExitDialog 同一文案。
private struct TestExitConfirm: View {
    var diagnosticSaved = false
    let stay: () -> Void
    let exit: () -> Void
    var body: some View {
        ZStack {
            EyeStyle.ink.opacity(0.55).ignoresSafeArea().contentShape(Rectangle()).onTapGesture { stay() }
            VStack(spacing: 0) {
                VStack(spacing: 12) {
                    Text("确定要退出测试吗？").font(EyeStyle.title).foregroundStyle(EyeStyle.text)
                    Text(diagnosticSaved ? "本次测试需重新开始；已录制的诊断可在测试记录回看。" : "退出后这次测试不会保存，下次要从头开始。").font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
                        .multilineTextAlignment(.center)
                    Button("继续测试") { EyeHaptics.tap(); stay() }.buttonStyle(EyeButtonStyle())
                    Button("确认退出") { EyeHaptics.tap(); exit() }.buttonStyle(EyeButtonStyle(role: .secondary))
                }
                .padding(24).frame(maxWidth: .infinity)
                .background(EyeStyle.panel, in: RoundedRectangle(cornerRadius: EyeStyle.radius, style: .continuous))
            }
            .padding(24)
        }
        .accessibilityAddTraits(.isModal)
    }
}

private struct GazeTargetView: View {
    @ObservedObject var runtime: EyeRuntime
    let pixelsPerMm: Float
    private var reflection: Bool { ["FIXATION","FAR_FIXATION","COVER"].contains(runtime.string("phase")) }
    var body: some View {
        if reflection { canvas } else {
            canvas.stageHeight(EyeStyle.gazeCanvasBaseHeight(UIScreen.main.bounds.height))
                .clipShape(RoundedRectangle(cornerRadius: EyeStyle.radius))
        }
    }
    private var canvas: some View {
        GeometryReader { geometry in
            let size = geometry.size
            let phase = runtime.string("phase")
            TimelineView(.animation(minimumInterval: 1.0 / 60.0, paused: !["PURSUIT","FIXATION","FAR_FIXATION","COVER"].contains(phase) || runtime.bool("stabilityInterrupted"))) { timeline in
                let t = timeline.date.timeIntervalSinceReferenceDate
                let bgPhase = CGFloat((t.truncatingRemainder(dividingBy: 4.8)) / 4.8)
                let wingPhase = CGFloat((t.truncatingRemainder(dividingBy: 0.68)) / 0.68)
                let lightChip = runtime.controller.reflectionChip()
                let lightOn = runtime.controller.reflectionLightOn(chip: lightChip)
                let trajectory = runtime.controller.pursuitTargetNow()
                let point = target(in: size, trajectory: trajectory)
                ZStack(alignment: .topLeading) {
                    Canvas { ctx, canvasSize in
                        MoonlitFireflyRenderer.backdrop(ctx, size: canvasSize, phase: bgPhase)
                        let radius: CGFloat = reflection
                            ? min(max(CGFloat(runtime.number("targetDiameterMm") * Double(pixelsPerMm)) / UIScreen.main.scale * 0.42, 14), 24)
                            : 15
                        MoonlitFireflyRenderer.firefly(
                            ctx, center: point, radius: radius,
                            wingPhase: wingPhase,
                            glowIntensity: reflection ? (lightOn ? 1 : 0.45) : 1
                        )
                    }
                }
                    .onAppear { report(geometry, point); runtime.controller.reportReflectionLight(chip: lightChip) }
                    .onChange(of: lightChip) { newChip in
                        if reflection { report(geometry, point) }
                        runtime.controller.reportReflectionLight(chip: newChip)
                    }
                    .onChange(of: point) { value in report(geometry, value) }
                    .onChange(of: runtime.stateKeyForTarget) { _ in report(geometry, point) }
                    .onChange(of: geometry.frame(in: .global).origin) { _ in report(geometry, point) }
                    .onChange(of: geometry.size) { _ in report(geometry, point) }
            }
        }
    }
    private func target(in size: CGSize, trajectory: GazePoint) -> CGPoint {
        if runtime.string("phase") == "PURSUIT" {
            let limits = pursuitLimits(in: size)
            let offset = StrabismusProtocol.shared.pursuitDisplayOffset(
                target: trajectory, maxX: limits.x, maxY: limits.y, distanceCm: pursuitDistanceCm)
            let pointsPerMm = CGFloat(pixelsPerMm) / UIScreen.main.scale
            return CGPoint(x: size.width / 2 + CGFloat(offset.x) * pointsPerMm,
                           y: size.height / 2 - CGFloat(offset.y) * pointsPerMm)
        }
        // 注视、遮盖：目标放在画布顶部、尽量靠近顶部的映光点（专家建议），头顶嫩芽也留在画布内
        let width = CGFloat(runtime.number("targetDiameterMm") * Double(pixelsPerMm)) / UIScreen.main.scale
        return CGPoint(x: size.width / 2, y: width / 2 + 4)
    }
    private var pursuitDistanceCm: Float {
        StrabismusProtocol.shared.pursuitDisplayDistance(measuredCm: Float(runtime.number("distanceCm")))
    }
    private func pursuitLimits(in size: CGSize) -> (x: Float, y: Float) {
        let pointsPerMm = CGFloat(pixelsPerMm) / UIScreen.main.scale
        let protocolRules = StrabismusProtocol.shared
        return (protocolRules.pursuitDisplayLimit(halfSpanMm: Float((size.width / 2 - 16) / pointsPerMm), distanceCm: pursuitDistanceCm),
                protocolRules.pursuitDisplayLimit(halfSpanMm: Float((size.height / 2 - 16) / pointsPerMm), distanceCm: pursuitDistanceCm))
    }
    private func report(_ geometry: GeometryProxy,_ point: CGPoint) {
        guard geometry.size.width > 1, geometry.size.height > 1 else { return }
        let frame = geometry.frame(in: .global), scale = UIScreen.main.scale
        runtime.controller.reportTarget(xPx: Float((frame.minX + point.x) * scale), yPx: Float((frame.minY + point.y) * scale))
        if runtime.string("phase") == "PURSUIT" {
            let limits = pursuitLimits(in: geometry.size)
            runtime.controller.reportPursuitCapacity(xDeg: limits.x, yDeg: limits.y)
        }
    }
}

private extension EyeRuntime {
    var stateKeyForTarget: String {
        "\(string("phase"))-\(number("targetX"))-\(number("targetY"))"
    }
}

private struct PursuitDot: View {
    var body: some View {
        ZStack {
            Circle().fill(EyeStyle.primary.opacity(0.18)).frame(width: 32, height: 32)
            Circle().fill(EyeStyle.primary).frame(width: 16, height: 16)
        }.frame(width: 80, height: 80)
    }
}

/// 老视开始前的戴镜确认：示意人物 + 按本次戴镜状态的一句话 + 确认按钮，语音同步播报。与安卓 GlassesCheckContent 一致（文案在共用 PresbyopiaIntake）。
private struct GlassesCheckView: View {
    let title: String
    let prompt: String
    let button: String
    let confirm: () -> Void
    var body: some View {
        VStack(spacing: 16) {
            EyechoGuideImage(name: "eyecho_guide_open")
                .frame(width: 112, height: 112).accessibilityLabel("爱科")
            Text(title).font(EyeStyle.title)
            Text(prompt)
                .font(EyeStyle.body).foregroundStyle(EyeStyle.secondary).multilineTextAlignment(.center)
            Button(button, action: confirm).buttonStyle(EyeButtonStyle())
        }.padding(20).frame(maxWidth: .infinity)
            .eyeGlass()
    }
}

/// 测试中异常暂停的弹窗（方案二）：计时已停、不出题；说明原因并实时显示是否已恢复，
/// 恢复正常后点「继续测试」接着计时与作答。与安卓 PauseDialog 同一文案。
struct DryEyePauseView: View {
    let reason: String
    let ready: Bool
    let resume: () -> Void
    let stop: () -> Void
    var body: some View {
        ZStack {
            EyeStyle.ink.opacity(0.55).ignoresSafeArea().contentShape(Rectangle()).onTapGesture {}
            VStack(alignment: .leading, spacing: 14) {
                Text("测试已暂停").font(EyeStyle.title).foregroundStyle(EyeStyle.text)
                Text("计时已停止，恢复后接着测。").font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
                Text(ready ? "已回到合适位置，可以继续了" : (reason.isEmpty ? "请正对屏幕，保持约 40 cm" : reason))
                    .font(EyeStyle.subtitle).foregroundStyle(ready ? EyeStyle.testReady : EyeStyle.warning)
                Button("继续测试") { EyeHaptics.tap(); resume() }.buttonStyle(EyeButtonStyle()).disabled(!ready)
                Button("结束测试") { EyeHaptics.tap(); stop() }.buttonStyle(EyeButtonStyle(role: .secondary))
            }
            .padding(24).frame(maxWidth: .infinity, alignment: .leading)
            .eyeGlass()
            .padding(24)
        }
        .accessibilityAddTraits(.isModal)
    }
}

/// 色觉自测（与安卓 ColorVisionScreen 同一排版与文案）：准备 → 示例图 → 8 张检测图（选看到的数字或「看不出」）→ 结果。
/// 图由共用代码生成，两端完全一致；不用相机。
struct ColorVisionContent: View {
    @ObservedObject var runtime: EyeRuntime
    var body: some View {
        let phase = runtime.string("phase")
        if phase == "Setup" {
            setup
        } else if phase == "Demo" || phase == "Plates" {
            plate
        } else if phase == "Result" {
            ResultReportView(runtime: runtime)
        }
    }

    private var setup: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("准备测色觉").font(EyeStyle.title).foregroundStyle(EyeStyle.text)
            Text("关掉护眼和夜间模式，在明亮处测试。").font(EyeStyle.body).foregroundStyle(EyeStyle.text)
            Text("手机离眼睛约 40 cm，平时戴镜就戴着。").font(EyeStyle.body).foregroundStyle(EyeStyle.text)
            Text("先练一张，再认 8 张图。看不出就选「看不出」。")
                .font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
            Button("先练一张") { EyeHaptics.tap(); runtime.act("colorStart") }.buttonStyle(EyeButtonStyle())
        }
        .padding(16).frame(maxWidth: .infinity, alignment: .leading)
        .eyeGlass()
    }

    private var plate: some View {
        let plateId = runtime.string("plateId")
        let options = runtime.state["options"] as? [String] ?? []
        let demo = runtime.bool("demo")
        return VStack(spacing: 16) {
            Text(demo ? "试一试" : "第 \(Int(runtime.number("plateNumber"))) / \(Int(runtime.number("plateCount"))) 张")
                .font(EyeStyle.subtitle).foregroundStyle(EyeStyle.readableText(EyeStyle.primary))
            ColorPlateCanvas(dots: runtime.colorPlateDots(plateId))
                .aspectRatio(1, contentMode: .fit).frame(maxWidth: 360)
                .id(plateId)
                .onAppear { DispatchQueue.main.async { runtime.colorPlateShown() } }
            Text("图里是什么数字？").font(EyeStyle.title).foregroundStyle(EyeStyle.text)
            HStack(spacing: 10) {
                ForEach(options.filter { $0 != "看不出" }, id: \.self) { option in
                    Button(option) { EyeHaptics.tap(); runtime.colorAnswer(option) }.buttonStyle(EyeButtonStyle())
                }
            }
            if options.contains("看不出") {
                Button("看不出") { EyeHaptics.tap(); runtime.colorAnswer("看不出") }
                    .buttonStyle(EyeButtonStyle(role: .secondary))
            }
        }.frame(maxWidth: .infinity)
    }
}

/// 画一张假同色图：面板底上逐个画色点（颜色由共用代码给出，不随主题换色）。
struct ColorPlateCanvas: View {
    let dots: [Double]
    var body: some View {
        Canvas { context, size in
            let radius = min(size.width, size.height) / 2
            let center = CGPoint(x: size.width / 2, y: size.height / 2)
            var i = 0
            while i + 3 < dots.count {
                let r = dots[i + 2] * radius
                let x = center.x + dots[i] * radius
                let y = center.y + dots[i + 1] * radius
                let argb = UInt32(truncatingIfNeeded: Int64(dots[i + 3]))
                let color = Color(.sRGB, red: Double((argb >> 16) & 0xFF) / 255, green: Double((argb >> 8) & 0xFF) / 255,
                                  blue: Double(argb & 0xFF) / 255, opacity: 1)
                context.fill(Path(ellipseIn: CGRect(x: x - r, y: y - r, width: r * 2, height: r * 2)), with: .color(color))
                i += 4
            }
        }
        .padding(8)
        .eyeGlass()
        .accessibilityLabel("色觉检测图")
    }
}

private struct DarkControlsHeight: PreferenceKey {
    static let defaultValue: CGFloat = 230
    static func reduce(value: inout CGFloat,nextValue: () -> CGFloat) { value = nextValue() }
}

// MARK: - 三项自测趣味小游戏场景（与安卓 DryEyeCinemaTheater / ScreeningGameScenes 同情景同配色）

/// 干眼自测电影级护眼科普动画剧场（与安卓 DryEyeCinemaTheater 对齐，直接共享 KMP DryEyeStoryScript）。
private struct DryEyeStoryTheaterView: View {
    @ObservedObject var runtime: EyeRuntime
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        let isOpen = runtime.string("phase") == "Open"
        let paused = runtime.bool("paused")
        let voiceEnabled = runtime.voice.enabled
        let remaining = max(0, Int(runtime.number("secondsLeft")))
        let naturalTotal = max(1, Int(runtime.number("naturalSeconds")))
        let total = isOpen ? 20 : naturalTotal
        let progress = min(max(runtime.number("progress"), 0), 1)
        let elapsedFloat = progress * Double(total)
        let elapsed = max(0, min(total, Int(elapsedFloat)))
        let scene = DryEyeStoryScript.shared.sceneAt(elapsedSeconds: Int32(elapsed))
        let totalActs = Int(DryEyeStoryScript.shared.totalScenesFor(totalSeconds: Int32(naturalTotal)))
        let currentActNumber = min(Int(scene.index) + 1, max(1, totalActs))
        let blinks = Int(runtime.number(isOpen ? "phase2BlinkCount" : "phase1BlinkCount"))
        let subtitleHeader: String = isOpen
            ? (blinks == 0 ? "稳住晶莹水膜，尽量不要眨眼" : "已眨眼 \(blinks) 次，继续稳住视线坚持")
            : "护眼小课堂 · \(scene.title)"
        let subtitleText: String = isOpen
            ? DryEyeStoryScript.shared.openNarration(elapsedSeconds: Int32(elapsed))
            : scene.narration

        VStack(spacing: 10) {
            HStack {
                HStack(spacing: 6) {
                    Text(isOpen ? "持续睁眼" : "自然眨眼")
                        .font(EyeStyle.chip)
                        .foregroundStyle(isOpen ? EyeStyle.warning : EyeStyle.moodGold)
                        .padding(.horizontal, 11).padding(.vertical, 4)
                        .background(isOpen ? EyeStyle.warningSoft : EyeStyle.primarySoft, in: EyeStyle.chipShape)
                    if !isOpen {
                        Text("\(currentActNumber) / \(totalActs) 幕")
                            .font(EyeStyle.caption)
                            .foregroundStyle(EyeStyle.secondary)
                    }
                }
                Spacer()
                Text("已眨眼 \(blinks) 次")
                    .font(EyeStyle.chip)
                    .foregroundStyle(EyeStyle.Game.Theater.tearHighlight)
                    .padding(.horizontal, 10).padding(.vertical, 4)
                    .background(EyeStyle.surfaceVariant, in: EyeStyle.chipShape)
                    .overlay {
                        EyeStyle.chipShape.strokeBorder(EyeStyle.Game.Theater.tearCyan.opacity(0.35), lineWidth: 1)
                    }
                Text("剩余 \(remaining) 秒").font(EyeStyle.subtitle).monospacedDigit()
            }

            TimelineView(.animation(minimumInterval: 1.0 / 60.0, paused: reduceMotion || paused)) { timeline in
                let t = timeline.date.timeIntervalSinceReferenceDate
                let sceneSec = Double(DryEyeStoryScript.shared.SCENE_DURATION_SEC)
                let baseActT = (elapsedFloat.truncatingRemainder(dividingBy: sceneSec)) / sceneSec
                let actT: CGFloat = reduceMotion ? 0.58 : CGFloat(min(max(progress > 0 ? baseActT : (t.truncatingRemainder(dividingBy: sceneSec) / sceneSec), 0), 1))
                let wave: CGFloat = reduceMotion ? 0.35 : CGFloat((t.truncatingRemainder(dividingBy: 2.4)) / 2.4)
                let eqPhase: CGFloat = (reduceMotion || paused || !voiceEnabled) ? 0.4 : CGFloat((t.truncatingRemainder(dividingBy: 0.9)) / 0.9)
                let segCount = isOpen ? 3 : min(max(totalActs, 1), 12)
                let activeSeg = isOpen ? min(max(elapsed / 7, 0), 2) : (Int(scene.index) % segCount)
                let activeFill: CGFloat = isOpen ? CGFloat(min(max((elapsedFloat.truncatingRemainder(dividingBy: 7.0)) / 7.0, 0), 1)) : actT
                let factBadge: String = isOpen
                    ? (elapsed >= 14 ? "满级星盾" : elapsed >= 7 ? "暖金护盾" : "青露护盾")
                    : scene.factTag

                ZStack {
                    Canvas { ctx, size in
                        if isOpen {
                            DryEyeCinemaRenderer.drawOpenShield(ctx, size: size, progress: CGFloat(progress), elapsedSec: elapsed, wave: wave, blinkCount: blinks)
                        } else {
                            DryEyeCinemaRenderer.drawStoryScene(ctx, size: size, sceneKey: scene.sceneKey, actT: actT, wave: wave)
                        }
                    }

                    VStack(spacing: 0) {
                        // 顶部分段电影时间轴
                        HStack(spacing: 4) {
                            ForEach(0..<segCount, id: \.self) { idx in
                                let fill: CGFloat = idx < activeSeg ? 1 : (idx == activeSeg ? activeFill : 0)
                                GeometryReader { geo in
                                    ZStack(alignment: .leading) {
                                        Capsule().fill(Color.white.opacity(0.2))
                                        if fill > 0 {
                                            Capsule()
                                                .fill(isOpen ? EyeStyle.Game.Theater.tearCyan : EyeStyle.primary)
                                                .frame(width: geo.size.width * fill)
                                        }
                                    }
                                }
                                .frame(height: 3)
                            }
                        }
                        .padding(.horizontal, 12)
                        .padding(.top, 8)

                        // 左上剧目名 + 右上科普要点标签
                        HStack {
                            Text(isOpen ? "定格挑战 · 守护水膜" : scene.title)
                                .font(EyeStyle.caption)
                                .foregroundStyle(EyeStyle.text)
                                .padding(.horizontal, 10).padding(.vertical, 4)
                                .background(EyeStyle.Game.Theater.stageDeep.opacity(0.78), in: EyeStyle.chipShape)
                            Spacer()
                            Text(factBadge)
                                .font(EyeStyle.caption)
                                .foregroundStyle(EyeStyle.Game.Theater.tearHighlight)
                                .padding(.horizontal, 9).padding(.vertical, 4)
                                .background(EyeStyle.Game.Theater.stageDeep.opacity(0.78), in: EyeStyle.chipShape)
                                .overlay {
                                    EyeStyle.chipShape.strokeBorder(EyeStyle.Game.Theater.tearCyan.opacity(0.4), lineWidth: 1)
                                }
                        }
                        .padding(.horizontal, 12)
                        .padding(.top, 7)

                        Spacer()

                        // 右下角中文语音讲解动态声波指示
                        HStack {
                            Spacer()
                            HStack(spacing: 5) {
                                Canvas { ctx, size in
                                    let barW = size.width / 7
                                    for i in 0..<4 {
                                        let amp: CGFloat = (voiceEnabled && !paused)
                                            ? 0.32 + 0.68 * ((sin((eqPhase + CGFloat(i) * 0.24) * 2 * .pi) + 1) * 0.5)
                                            : 0.25
                                        let bh = size.height * amp
                                        let rect = CGRect(x: CGFloat(i) * barW * 1.9, y: (size.height - bh) * 0.5, width: barW, height: bh)
                                        ctx.fill(RoundedRectangle(cornerRadius: barW * 0.5).path(in: rect),
                                                 with: .color(voiceEnabled ? EyeStyle.Game.Theater.lipidGold : EyeStyle.tertiary))
                                    }
                                }
                                .frame(width: 16, height: 12)
                                Text(voiceEnabled ? "中文讲解中" : "静音字幕")
                                    .font(EyeStyle.caption)
                                    .foregroundStyle(voiceEnabled ? EyeStyle.Game.Theater.lipidGold : EyeStyle.secondary)
                            }
                            .padding(.horizontal, 9).padding(.vertical, 4)
                            .background(EyeStyle.Game.Theater.stageDeep.opacity(0.76), in: EyeStyle.chipShape)
                        }
                        .padding(10)
                    }
                }
                .stageHeight(250)
                .background(EyeStyle.Game.Theater.stageBg, in: RoundedRectangle(cornerRadius: EyeStyle.radiusHero, style: .continuous))
                .clipShape(RoundedRectangle(cornerRadius: EyeStyle.radiusHero, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: EyeStyle.radiusHero, style: .continuous)
                        .strokeBorder(EyeStyle.glassEdgeTop.opacity(0.45), lineWidth: 1)
                }
            }

            // 底部同步电影中文字幕栏
            VStack(alignment: .leading, spacing: 4) {
                Text(subtitleHeader).font(EyeStyle.caption).foregroundStyle(EyeStyle.moodGold)
                Text(subtitleText).font(EyeStyle.body).foregroundStyle(EyeStyle.text)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16).padding(.vertical, 12)
            .background(EyeStyle.surfaceVariant, in: RoundedRectangle(cornerRadius: EyeStyle.radiusMedium, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: EyeStyle.radiusMedium, style: .continuous)
                    .strokeBorder(EyeStyle.borderSubtle, lineWidth: 1)
            }
            .animation(.easeInOut(duration: 0.24), value: subtitleText)
        }
    }
}

/// 干眼科普 12 幕代码渲染矢量分镜 + 三阶进化睁眼护盾（与 Android DryEyeCinemaTheater 同源同绘）。
private enum DryEyeCinemaRenderer {
    static func drawStoryScene(_ ctx: GraphicsContext, size: CGSize, sceneKey: String, actT: CGFloat, wave: CGFloat) {
        let c = CGPoint(x: size.width * 0.5, y: size.height * 0.56)
        let u = min(size.width, size.height)
        let topColor: Color = switch sceneKey {
        case "RULE_20_20_20", "HYDRATION_NUTRITION": EyeStyle.Game.Theater.skyDay
        case "NIGHT_LIGHT", "AC_WIND": EyeStyle.Game.Theater.skyNight
        default: EyeStyle.Game.Theater.stageBg
        }
        ctx.fill(Path(CGRect(origin: .zero, size: size)),
                 with: .linearGradient(Gradient(colors: [topColor, EyeStyle.Game.Theater.stageBg, EyeStyle.Game.Theater.stageDeep]),
                                       startPoint: CGPoint(x: size.width * 0.5, y: 0),
                                       endPoint: CGPoint(x: size.width * 0.5, y: size.height)))
        let glowR = u * 0.72
        ctx.fill(Path(ellipseIn: CGRect(x: c.x - glowR, y: c.y - glowR, width: glowR * 2, height: glowR * 2)),
                 with: .radialGradient(Gradient(colors: [EyeStyle.Game.Theater.stageGlow.opacity(0.65), .clear]),
                                       center: c, startRadius: 0, endRadius: glowR))

        switch sceneKey {
        case "WATER_WORLD": drawWaterWorld(ctx, size: size, c: c, u: u, actT: actT, wave: wave)
        case "TEAR_LAYERS": drawTearLayers(ctx, size: size, c: c, u: u, actT: actT, wave: wave)
        case "SCREEN_VS_TALK": drawScreenVsTalk(ctx, size: size, c: c, u: u, actT: actT, wave: wave)
        case "TEAR_BREAKUP": drawTearBreakup(ctx, c: c, u: u, actT: actT)
        case "RULE_20_20_20": drawRule202020(ctx, size: size, u: u, actT: actT, wave: wave)
        case "WARM_COMPRESS": drawWarmCompress(ctx, size: size, u: u, actT: actT, wave: wave)
        case "AC_WIND": drawAcWind(ctx, size: size, c: c, u: u, actT: actT, wave: wave)
        case "SCREEN_ANGLE": drawScreenAngle(ctx, size: size, u: u, actT: actT)
        case "NIGHT_LIGHT": drawNightLight(ctx, size: size, u: u, actT: actT)
        case "COMPLETE_BLINK": drawCompleteBlink(ctx, c: c, u: u, actT: actT, wave: wave)
        case "CONTACT_LENS": drawContactLens(ctx, c: c, u: u, actT: actT, wave: wave)
        default: drawHydrationNutrition(ctx, size: size, c: c, u: u, actT: actT, wave: wave)
        }
    }

    static func drawOpenShield(_ ctx: GraphicsContext, size: CGSize, progress: CGFloat, elapsedSec: Int, wave: CGFloat, blinkCount: Int) {
        let c = CGPoint(x: size.width * 0.5, y: size.height * 0.55)
        let u = min(size.width, size.height)
        let ringR = u * 0.34
        let pulse = 0.5 + 0.5 * sin(wave * 2 * .pi)
        let shieldColor: Color = blinkCount > 0 ? EyeStyle.Game.Theater.dryCoral
            : elapsedSec >= 14 ? EyeStyle.Game.Theater.lipidGold
            : elapsedSec >= 7 ? EyeStyle.Game.Theater.warmLamp
            : EyeStyle.Game.Theater.tearCyan

        ctx.fill(Path(CGRect(origin: .zero, size: size)),
                 with: .linearGradient(Gradient(colors: [EyeStyle.Game.Theater.skyNight, EyeStyle.Game.Theater.stageDeep]),
                                       startPoint: .zero, endPoint: CGPoint(x: 0, y: size.height)))
        let haloR = ringR * 1.85
        ctx.fill(Path(ellipseIn: CGRect(x: c.x - haloR, y: c.y - haloR, width: haloR * 2, height: haloR * 2)),
                 with: .radialGradient(Gradient(colors: [shieldColor.opacity(0.35 + pulse * 0.14), .clear]),
                                       center: c, startRadius: 0, endRadius: haloR))
        for i in 0..<8 {
            let a = wave * 2 * .pi + CGFloat(i) * (.pi / 4)
            let pt = CGPoint(x: c.x + cos(a) * ringR * 1.24, y: c.y + sin(a) * ringR * 1.24)
            let pr = u * 0.016
            ctx.fill(Path(ellipseIn: CGRect(x: pt.x - pr, y: pt.y - pr, width: pr * 2, height: pr * 2)),
                     with: .color(shieldColor.opacity(0.55 + 0.35 * CGFloat(i % 2))))
        }
        ctx.stroke(Path(ellipseIn: CGRect(x: c.x - ringR, y: c.y - ringR, width: ringR * 2, height: ringR * 2)),
                   with: .color(shieldColor.opacity(0.22)), lineWidth: u * 0.032)
        var arc = Path()
        arc.addArc(center: c, radius: ringR, startAngle: .degrees(-90),
                   endAngle: .degrees(-90 + 360 * Double(min(max(progress, 0), 1))), clockwise: false)
        ctx.stroke(arc, with: .color(shieldColor), style: StrokeStyle(lineWidth: u * 0.044, lineCap: .round))
        drawSmilingDrop(ctx, center: c, radius: u * (0.15 + pulse * 0.012))
    }

    private static func drawWaterWorld(_ ctx: GraphicsContext, size: CGSize, c: CGPoint, u: CGFloat, actT: CGFloat, wave: CGFloat) {
        let blink1: CGFloat = (actT >= 0.30 && actT <= 0.42) ? sin(((actT - 0.30) / 0.12) * .pi) : 0
        let blink2: CGFloat = (actT >= 0.72 && actT <= 0.84) ? sin(((actT - 0.72) / 0.12) * .pi) : 0
        drawDetailedEye(ctx, center: c, radius: u * 0.34, lidClose: max(blink1, blink2), gazeX: sin(wave * 2 * .pi) * 0.25, dryRatio: 0)
        for i in 0..<3 {
            let r = u * (0.37 + CGFloat(i) * 0.065)
            let startDeg = -145 + Double(actT) * 360 * (i % 2 == 0 ? 1.0 : -0.7)
            var arc = Path()
            arc.addArc(center: c, radius: r, startAngle: .degrees(startDeg), endAngle: .degrees(startDeg + 82), clockwise: false)
            ctx.stroke(arc, with: .color(EyeStyle.Game.Theater.tearCyan.opacity(0.55 - Double(i) * 0.15)),
                       style: StrokeStyle(lineWidth: 3.2, lineCap: .round))
        }
        let arcAngle = -CGFloat.pi * (0.82 - actT * 0.64)
        let dropPos = CGPoint(x: c.x + cos(arcAngle) * u * 0.42, y: c.y + sin(arcAngle) * u * 0.30)
        drawSmilingDrop(ctx, center: dropPos, radius: u * 0.082)
        for i in 0..<6 {
            let px = size.width * (0.14 + CGFloat(i) * 0.145)
            let py = size.height * (0.32 + (CGFloat(i) * 0.23 + wave).truncatingRemainder(dividingBy: 0.5))
            let pr = u * 0.014
            ctx.fill(Path(ellipseIn: CGRect(x: px - pr, y: py - pr, width: pr * 2, height: pr * 2)),
                     with: .color(EyeStyle.Game.Theater.tearHighlight.opacity(0.32)))
        }
    }

    private static func drawTearLayers(_ ctx: GraphicsContext, size: CGSize, c: CGPoint, u: CGFloat, actT: CGFloat, wave: CGFloat) {
        let left = size.width * 0.10, right = size.width * 0.90, w = right - left, baseY = size.height * 0.78
        var cornea = Path()
        cornea.move(to: CGPoint(x: left, y: size.height * 0.88))
        cornea.addLine(to: CGPoint(x: left, y: baseY))
        cornea.addQuadCurve(to: CGPoint(x: right, y: baseY), control: CGPoint(x: c.x, y: baseY - u * 0.08))
        cornea.addLine(to: CGPoint(x: right, y: size.height * 0.88))
        cornea.closeSubpath()
        ctx.fill(cornea, with: .color(EyeStyle.Game.Theater.skinWarm.opacity(0.52)))

        let mucinA = min(max(actT / 0.25, 0.35), 1)
        let aqueousA = min(max((actT - 0.18) / 0.25, 0.25), 1)
        let lipidA = min(max((actT - 0.36) / 0.25, 0.25), 1)
        drawWaveBand(ctx, left: left, right: right, topY: baseY - u * 0.06, thickness: u * 0.09, phase: wave, color: EyeStyle.Game.Theater.mucinViolet.opacity(0.75 * mucinA))
        drawWaveBand(ctx, left: left, right: right, topY: baseY - u * 0.19, thickness: u * 0.15, phase: wave * 1.4 + 0.3, color: EyeStyle.Game.Theater.tearCyan.opacity(0.78 * aqueousA))
        drawWaveBand(ctx, left: left, right: right, topY: baseY - u * 0.31, thickness: u * 0.065, phase: wave * 1.1 + 0.6, color: EyeStyle.Game.Theater.lipidGold.opacity(0.92 * lipidA))

        for i in 0..<5 {
            let cycle = (wave + CGFloat(i) * 0.21).truncatingRemainder(dividingBy: 1)
            let mx = left + w * (0.16 + CGFloat(i) * 0.17)
            let my = (baseY - u * 0.14) - sin(cycle * .pi) * u * 0.12
            let mr = u * 0.022
            ctx.fill(Path(ellipseIn: CGRect(x: mx - mr, y: my - mr, width: mr * 2, height: mr * 2)),
                     with: .color(EyeStyle.Game.Theater.tearHighlight.opacity(0.9)))
        }
        let legendY = size.height * 0.29
        let items: [(Color, CGFloat, CGFloat)] = [
            (EyeStyle.Game.Theater.lipidGold, 0.24, 0.32),
            (EyeStyle.Game.Theater.tearCyan, 0.50, 0.20),
            (EyeStyle.Game.Theater.mucinViolet, 0.76, 0.08),
        ]
        for (col, rx, drop) in items {
            let lx = size.width * rx, lr = u * 0.026
            ctx.fill(Path(ellipseIn: CGRect(x: lx - lr, y: legendY - lr, width: lr * 2, height: lr * 2)), with: .color(col))
            var line = Path()
            line.move(to: CGPoint(x: lx, y: legendY + u * 0.03))
            line.addLine(to: CGPoint(x: lx, y: baseY - u * drop))
            ctx.stroke(line, with: .color(col.opacity(0.55)), style: StrokeStyle(lineWidth: 2, dash: [6, 6]))
        }
    }

    private static func drawScreenVsTalk(_ ctx: GraphicsContext, size: CGSize, c: CGPoint, u: CGFloat, actT: CGFloat, wave: CGFloat) {
        let leftC = CGPoint(x: size.width * 0.27, y: size.height * 0.50)
        let rightC = CGPoint(x: size.width * 0.73, y: size.height * 0.50)
        var divider = Path()
        divider.move(to: CGPoint(x: c.x, y: size.height * 0.24))
        divider.addLine(to: CGPoint(x: c.x, y: size.height * 0.86))
        ctx.stroke(divider, with: .color(EyeStyle.Game.Theater.steam.opacity(0.18)), lineWidth: 2)

        let leftBlink: CGFloat = (wave >= 0.42 && wave <= 0.56) ? sin(((wave - 0.42) / 0.14) * .pi) : 0
        drawDetailedEye(ctx, center: CGPoint(x: leftC.x, y: leftC.y - u * 0.04), radius: u * 0.18, lidClose: leftBlink, gazeX: 0, dryRatio: 0)
        let barW = u * 0.12, barMaxH = u * 0.26, barBottom = size.height * 0.85
        let leftFill = min(0.35 + actT * 0.58, 0.92)
        ctx.fill(RoundedRectangle(cornerRadius: 6).path(in: CGRect(x: leftC.x - barW * 0.5, y: barBottom - barMaxH, width: barW, height: barMaxH)),
                 with: .color(EyeStyle.Game.Theater.tearCyan.opacity(0.22)))
        ctx.fill(RoundedRectangle(cornerRadius: 6).path(in: CGRect(x: leftC.x - barW * 0.5, y: barBottom - barMaxH * leftFill, width: barW, height: barMaxH * leftFill)),
                 with: .color(EyeStyle.Game.Theater.leafSage))

        drawDetailedEye(ctx, center: CGPoint(x: rightC.x, y: rightC.y - u * 0.04), radius: u * 0.18, lidClose: 0, gazeX: 0, dryRatio: min(max(actT * 0.85, 0.2), 0.9))
        let rightFill = max(0.72 - actT * 0.44, 0.26)
        ctx.fill(RoundedRectangle(cornerRadius: 6).path(in: CGRect(x: rightC.x - barW * 0.5, y: barBottom - barMaxH, width: barW, height: barMaxH)),
                 with: .color(EyeStyle.Game.Theater.dryCoral.opacity(0.22)))
        ctx.fill(RoundedRectangle(cornerRadius: 6).path(in: CGRect(x: rightC.x - barW * 0.5, y: barBottom - barMaxH * rightFill, width: barW, height: barMaxH * rightFill)),
                 with: .color(EyeStyle.Game.Theater.dryCoral))
        for i in 0..<3 {
            let st = (wave + CGFloat(i) * 0.33).truncatingRemainder(dividingBy: 1)
            let sx = rightC.x + CGFloat(i - 1) * u * 0.08
            let sy = rightC.y - u * 0.18 - st * u * 0.18
            let sr = u * 0.018
            ctx.stroke(Path(ellipseIn: CGRect(x: sx - sr, y: sy - sr, width: sr * 2, height: sr * 2)),
                       with: .color(EyeStyle.Game.Theater.dryCoral.opacity((1 - st) * 0.65)), lineWidth: 2)
        }
    }

    private static func drawTearBreakup(_ ctx: GraphicsContext, c: CGPoint, u: CGFloat, actT: CGFloat) {
        let filmR = u * 0.34
        ctx.fill(Path(ellipseIn: CGRect(x: c.x - filmR, y: c.y - filmR, width: filmR * 2, height: filmR * 2)),
                 with: .radialGradient(Gradient(colors: [EyeStyle.Game.Theater.tearCyan.opacity(0.78), EyeStyle.Game.Theater.tearDeep.opacity(0.62), EyeStyle.Game.Theater.tearDeep.opacity(0.25)]),
                                       center: c, startRadius: 0, endRadius: filmR))
        ctx.stroke(Path(ellipseIn: CGRect(x: c.x - filmR, y: c.y - filmR, width: filmR * 2, height: filmR * 2)),
                   with: .color(EyeStyle.Game.Theater.tearHighlight.opacity(0.7)), lineWidth: 3)

        let drySeverity: CGFloat = actT < 0.62 ? (actT / 0.62) : actT < 0.88 ? max(0, 1 - (actT - 0.62) / 0.26) : 0
        if drySeverity > 0.05 {
            let spots: [(CGPoint, CGFloat)] = [
                (CGPoint(x: c.x - filmR * 0.34, y: c.y - filmR * 0.12), 0.22),
                (CGPoint(x: c.x + filmR * 0.28, y: c.y + filmR * 0.18), 0.26),
                (CGPoint(x: c.x + filmR * 0.06, y: c.y - filmR * 0.32), 0.17),
            ]
            for (pt, sc) in spots {
                let r = filmR * sc * drySeverity
                ctx.fill(Path(ellipseIn: CGRect(x: pt.x - r, y: pt.y - r, width: r * 2, height: r * 2)),
                         with: .color(EyeStyle.Game.Theater.dryCoral.opacity(0.72 * drySeverity)))
                let r2 = r * 1.15
                ctx.stroke(Path(ellipseIn: CGRect(x: pt.x - r2, y: pt.y - r2, width: r2 * 2, height: r2 * 2)),
                           with: .color(EyeStyle.Game.Theater.warmLamp.opacity(0.55 * drySeverity)), lineWidth: 2)
            }
        }
        if actT >= 0.60 && actT <= 0.92 {
            let barY = c.y - filmR + ((actT - 0.60) / 0.32) * filmR * 2
            var wiper = Path()
            wiper.move(to: CGPoint(x: c.x - filmR * 1.1, y: barY))
            wiper.addLine(to: CGPoint(x: c.x + filmR * 1.1, y: barY))
            ctx.stroke(wiper, with: .color(EyeStyle.Game.Theater.tearHighlight), style: StrokeStyle(lineWidth: 7, lineCap: .round))
        } else if actT >= 0.90 {
            drawSparkle(ctx, center: CGPoint(x: c.x - filmR * 0.25, y: c.y - filmR * 0.22), radius: u * 0.055, color: EyeStyle.Game.Theater.tearHighlight)
            drawSparkle(ctx, center: CGPoint(x: c.x + filmR * 0.30, y: c.y + filmR * 0.10), radius: u * 0.045, color: EyeStyle.Game.Theater.lipidGold)
        }
    }

    private static func drawRule202020(_ ctx: GraphicsContext, size: CGSize, u: CGFloat, actT: CGFloat, wave: CGFloat) {
        let winW = size.width * 0.76, winH = size.height * 0.52
        let winRect = CGRect(x: (size.width - winW) * 0.5, y: size.height * 0.26, width: winW, height: winH)
        ctx.fill(RoundedRectangle(cornerRadius: 16).path(in: winRect),
                 with: .linearGradient(Gradient(colors: [EyeStyle.Game.Theater.skyDay, EyeStyle.Game.Theater.leafSage.opacity(0.5)]),
                                       startPoint: CGPoint(x: winRect.midX, y: winRect.minY),
                                       endPoint: CGPoint(x: winRect.midX, y: winRect.maxY)))
        var mountain = Path()
        mountain.move(to: CGPoint(x: winRect.minX, y: winRect.maxY))
        mountain.addLine(to: CGPoint(x: winRect.minX, y: winRect.minY + winH * 0.66))
        mountain.addQuadCurve(to: CGPoint(x: winRect.minX + winW * 0.56, y: winRect.minY + winH * 0.62),
                              control: CGPoint(x: winRect.minX + winW * 0.28, y: winRect.minY + winH * 0.30))
        mountain.addQuadCurve(to: CGPoint(x: winRect.maxX, y: winRect.minY + winH * 0.64),
                              control: CGPoint(x: winRect.minX + winW * 0.80, y: winRect.minY + winH * 0.36))
        mountain.addLine(to: CGPoint(x: winRect.maxX, y: winRect.maxY))
        mountain.closeSubpath()
        ctx.drawLayer { layerCtx in
            layerCtx.clip(to: RoundedRectangle(cornerRadius: 16).path(in: winRect))
            layerCtx.fill(mountain, with: .color(EyeStyle.Game.Theater.leafSage.opacity(0.78)))
        }

        let sunC = CGPoint(x: winRect.minX + winW * 0.80, y: winRect.minY + winH * 0.28)
        let haloR = u * 0.11, sunR = u * 0.055
        ctx.fill(Path(ellipseIn: CGRect(x: sunC.x - haloR, y: sunC.y - haloR, width: haloR * 2, height: haloR * 2)),
                 with: .color(EyeStyle.Game.Theater.warmLamp.opacity(0.25)))
        ctx.fill(Path(ellipseIn: CGRect(x: sunC.x - sunR, y: sunC.y - sunR, width: sunR * 2, height: sunR * 2)),
                 with: .color(EyeStyle.Game.Theater.warmLamp))

        var gaze = Path()
        gaze.move(to: CGPoint(x: winRect.minX + winW * 0.18, y: winRect.minY + winH * 0.56))
        gaze.addLine(to: CGPoint(x: winRect.minX + winW * (0.25 + actT * 0.52), y: winRect.minY + winH * (0.56 - actT * 0.24)))
        ctx.stroke(gaze, with: .color(EyeStyle.Game.Theater.tearHighlight.opacity(0.7)),
                   style: StrokeStyle(lineWidth: 3, lineCap: .round, dash: [10, 8]))
        for i in 0..<3 {
            let rt = (wave + CGFloat(i) * 0.33).truncatingRemainder(dividingBy: 1)
            let rr = u * (0.04 + rt * 0.15)
            ctx.stroke(Path(ellipseIn: CGRect(x: sunC.x - rr, y: sunC.y - rr, width: rr * 2, height: rr * 2)),
                       with: .color(EyeStyle.Game.Theater.tearHighlight.opacity((1 - rt) * 0.55)), lineWidth: 2.2)
        }
        ctx.stroke(RoundedRectangle(cornerRadius: 16).path(in: winRect),
                   with: .color(EyeStyle.Game.Theater.warmLamp.opacity(0.65)), lineWidth: 3.5)

        let relax = min(max(actT, 0), 1)
        var spring = Path()
        let sx = winRect.minX + winW * 0.15, sw = winW * (0.35 + relax * 0.35), sy = size.height * 0.85
        spring.move(to: CGPoint(x: sx, y: sy))
        for i in 1...8 {
            let x = sx + sw * (CGFloat(i) / 8)
            let y = sy + (i % 2 == 0 ? -1 : 1) * u * (0.045 - relax * 0.025)
            spring.addLine(to: CGPoint(x: x, y: y))
        }
        ctx.stroke(spring, with: .color(relax > 0.45 ? EyeStyle.Game.Theater.leafSage : EyeStyle.Game.Theater.dryCoral),
                   style: StrokeStyle(lineWidth: 4, lineCap: .round, lineJoin: .round))
    }

    private static func drawWarmCompress(_ ctx: GraphicsContext, size: CGSize, u: CGFloat, actT: CGFloat, wave: CGFloat) {
        let melt = min(max((actT - 0.22) / 0.60, 0), 1)
        let baseY = size.height * 0.84, glandTop = size.height * 0.56
        for i in 0..<6 {
            let gx = size.width * (0.20 + CGFloat(i) * 0.12)
            ctx.fill(RoundedRectangle(cornerRadius: 12).path(in: CGRect(x: gx - u * 0.032, y: glandTop, width: u * 0.064, height: baseY - glandTop)),
                     with: .color(EyeStyle.Game.Theater.skinWarm.opacity(0.35)))
            let beadY = glandTop + (baseY - glandTop) * (0.55 - melt * 0.55) - melt * u * 0.10 * (wave + CGFloat(i) * 0.17).truncatingRemainder(dividingBy: 1)
            let br = u * (0.024 + melt * 0.008)
            ctx.fill(Path(ellipseIn: CGRect(x: gx - br, y: beadY - br, width: br * 2, height: br * 2)),
                     with: .color(melt > 0.35 ? EyeStyle.Game.Theater.lipidGold : EyeStyle.Game.Theater.steam.opacity(0.85)))
        }
        let towelRect = CGRect(x: size.width * 0.22, y: size.height * 0.29, width: size.width * 0.56, height: u * 0.17)
        ctx.fill(RoundedRectangle(cornerRadius: 24).path(in: towelRect),
                 with: .linearGradient(Gradient(colors: [EyeStyle.Game.Theater.dryCoral.opacity(0.72), EyeStyle.Game.Theater.warmLamp.opacity(0.88), EyeStyle.Game.Theater.dryCoral.opacity(0.72)]),
                                       startPoint: CGPoint(x: towelRect.minX, y: towelRect.midY),
                                       endPoint: CGPoint(x: towelRect.maxX, y: towelRect.midY)))
        for i in 0..<4 {
            let sx = size.width * (0.30 + CGFloat(i) * 0.13)
            let drift = sin((wave + CGFloat(i) * 0.25) * 2 * .pi) * u * 0.028
            var steam = Path()
            steam.move(to: CGPoint(x: sx, y: glandTop - u * 0.02))
            steam.addQuadCurve(to: CGPoint(x: sx - drift * 0.7, y: towelRect.maxY),
                               control: CGPoint(x: sx + drift, y: glandTop - u * 0.09))
            ctx.stroke(steam, with: .color(EyeStyle.Game.Theater.warmLamp.opacity(0.48 + melt * 0.35)),
                       style: StrokeStyle(lineWidth: 3, lineCap: .round))
        }
    }

    private static func drawAcWind(_ ctx: GraphicsContext, size: CGSize, c: CGPoint, u: CGFloat, actT: CGFloat, wave: CGFloat) {
        let fixed = actT > 0.45
        drawDetailedEye(ctx, center: CGPoint(x: c.x, y: c.y + u * 0.04), radius: u * 0.24, lidClose: 0, gazeX: 0, dryRatio: fixed ? 0 : 0.7)
        for i in 0..<3 {
            let y0 = size.height * (0.42 + CGFloat(i) * 0.10)
            let endY = fixed ? size.height * (0.24 + CGFloat(i) * 0.05) : y0
            var wind = Path()
            wind.move(to: CGPoint(x: size.width * 0.06, y: y0))
            wind.addQuadCurve(to: CGPoint(x: size.width * 0.34, y: endY),
                              control: CGPoint(x: size.width * 0.22, y: (y0 + endY) * 0.5 - u * 0.04))
            ctx.stroke(wind, with: .color(EyeStyle.Game.Theater.coldWind.opacity(0.75)),
                       style: StrokeStyle(lineWidth: 3.5, lineCap: .round, dash: [12, 8], dashPhase: -wave * 40))
        }
        if fixed {
            let humC = CGPoint(x: size.width * 0.80, y: size.height * 0.74)
            ctx.fill(RoundedRectangle(cornerRadius: 14).path(in: CGRect(x: humC.x - u * 0.09, y: humC.y - u * 0.08, width: u * 0.18, height: u * 0.18)),
                     with: .color(EyeStyle.Game.Theater.tearDeep))
            for i in 0..<5 {
                let mt = (wave + CGFloat(i) * 0.2).truncatingRemainder(dividingBy: 1)
                let mp = CGPoint(x: humC.x - mt * u * 0.22 + sin(mt * 5) * u * 0.03, y: humC.y - u * 0.12 - mt * u * 0.32)
                let mr = u * (0.022 + mt * 0.02)
                ctx.fill(Path(ellipseIn: CGRect(x: mp.x - mr, y: mp.y - mr, width: mr * 2, height: mr * 2)),
                         with: .color(EyeStyle.Game.Theater.tearHighlight.opacity((1 - mt) * 0.75)))
            }
        }
    }

    private static func drawScreenAngle(_ ctx: GraphicsContext, size: CGSize, u: CGFloat, actT: CGFloat) {
        let lowered = min(max((actT - 0.25) / 0.45, 0), 1)
        let eyePos = CGPoint(x: size.width * 0.28, y: size.height * 0.48)
        let screenPos = CGPoint(x: size.width * 0.76, y: size.height * (0.44 + lowered * 0.22))
        var horiz = Path()
        horiz.move(to: eyePos); horiz.addLine(to: CGPoint(x: size.width * 0.82, y: eyePos.y))
        ctx.stroke(horiz, with: .color(EyeStyle.Game.Theater.steam.opacity(0.28)), style: StrokeStyle(lineWidth: 2, dash: [8, 8]))
        var sight = Path()
        sight.move(to: eyePos); sight.addLine(to: screenPos)
        ctx.stroke(sight, with: .color(lowered > 0.5 ? EyeStyle.Game.Theater.leafSage : EyeStyle.Game.Theater.dryCoral),
                   style: StrokeStyle(lineWidth: 4, lineCap: .round))
        drawDetailedEye(ctx, center: eyePos, radius: u * 0.19, lidClose: lowered * 0.34, gazeX: 0.5, dryRatio: (1 - lowered) * 0.65)
        ctx.fill(RoundedRectangle(cornerRadius: 8).path(in: CGRect(x: screenPos.x - u * 0.04, y: screenPos.y - u * 0.14, width: u * 0.08, height: u * 0.28)),
                 with: .color(EyeStyle.Game.Theater.tearCyan.opacity(0.85)))
    }

    private static func drawNightLight(_ ctx: GraphicsContext, size: CGSize, u: CGFloat, actT: CGFloat) {
        let lampOn = min(max((actT - 0.35) / 0.35, 0), 1)
        let lampGlowC = CGPoint(x: size.width * 0.24, y: size.height * 0.45)
        if lampOn > 0.05 {
            let r = u * 0.72
            ctx.fill(Path(ellipseIn: CGRect(x: lampGlowC.x - r, y: lampGlowC.y - r, width: r * 2, height: r * 2)),
                     with: .radialGradient(Gradient(colors: [EyeStyle.Game.Theater.warmLamp.opacity(0.42 * lampOn), .clear]),
                                           center: lampGlowC, startRadius: 0, endRadius: r))
        }
        let lampC = CGPoint(x: size.width * 0.24, y: size.height * 0.48)
        var shade = Path()
        shade.move(to: CGPoint(x: lampC.x - u * 0.10, y: lampC.y + u * 0.06))
        shade.addLine(to: CGPoint(x: lampC.x - u * 0.06, y: lampC.y - u * 0.07))
        shade.addLine(to: CGPoint(x: lampC.x + u * 0.06, y: lampC.y - u * 0.07))
        shade.addLine(to: CGPoint(x: lampC.x + u * 0.10, y: lampC.y + u * 0.06))
        shade.closeSubpath()
        ctx.fill(shade, with: .color(lampOn > 0.2 ? EyeStyle.Game.Theater.warmLamp : EyeStyle.Game.Theater.steam.opacity(0.35)))
        var stand = Path()
        stand.move(to: CGPoint(x: lampC.x, y: lampC.y + u * 0.06)); stand.addLine(to: CGPoint(x: lampC.x, y: size.height * 0.80))
        ctx.stroke(stand, with: .color(EyeStyle.Game.Theater.steam.opacity(0.55)), lineWidth: 5)
        drawDetailedEye(ctx, center: CGPoint(x: size.width * 0.62, y: size.height * 0.54), radius: u * 0.24, lidClose: 0, gazeX: 0, dryRatio: (1 - lampOn) * 0.75)
    }

    private static func drawCompleteBlink(_ ctx: GraphicsContext, c: CGPoint, u: CGFloat, actT: CGFloat, wave: CGFloat) {
        let isComplete = actT >= 0.46
        let lid: CGFloat
        if !isComplete {
            lid = 0.5 * ((sin(wave * 4 * .pi) + 1) * 0.5)
        } else {
            let cyc = (actT - 0.46) / 0.54
            lid = (cyc >= 0.2 && cyc <= 0.7) ? sin(((cyc - 0.2) / 0.5) * .pi) : 0
        }
        drawDetailedEye(ctx, center: c, radius: u * 0.32, lidClose: lid, gazeX: 0, dryRatio: isComplete ? 0 : 0.55)
        if isComplete && lid > 0.65 {
            for i in -2...2 {
                let r = u * 0.025
                let px = c.x + CGFloat(i) * u * 0.10, py = c.y + u * 0.04
                ctx.fill(Path(ellipseIn: CGRect(x: px - r, y: py - r, width: r * 2, height: r * 2)),
                         with: .color(EyeStyle.Game.Theater.lipidGold))
            }
        }
    }

    private static func drawContactLens(_ ctx: GraphicsContext, c: CGPoint, u: CGFloat, actT: CGFloat, wave: CGFloat) {
        let removed = min(max((actT - 0.38) / 0.32, 0), 1)
        drawDetailedEye(ctx, center: c, radius: u * 0.28, lidClose: 0, gazeX: 0, dryRatio: (1 - removed) * 0.5)
        if removed < 0.95 {
            let lensC = CGPoint(x: c.x + removed * u * 0.38, y: c.y - removed * u * 0.28)
            let r = u * 0.16
            ctx.stroke(Path(ellipseIn: CGRect(x: lensC.x - r, y: lensC.y - r, width: r * 2, height: r * 2)),
                       with: .color(EyeStyle.Game.Theater.tearHighlight.opacity((1 - removed) * 0.55)), lineWidth: 3.5)
        }
        let count = removed > 0.3 ? 7 : 2
        for i in 0..<count {
            let a = CGFloat(i) * (2 * .pi / CGFloat(count)) + wave * 2 * .pi
            let dist = u * (0.44 - removed * 0.14)
            let bx = c.x + cos(a) * dist, by = c.y + sin(a) * dist * 0.72, br = u * 0.026
            ctx.stroke(Path(ellipseIn: CGRect(x: bx - br, y: by - br, width: br * 2, height: br * 2)),
                       with: .color(EyeStyle.Game.Theater.tearHighlight.opacity(0.72)), lineWidth: 2.2)
        }
    }

    private static func drawHydrationNutrition(_ ctx: GraphicsContext, size: CGSize, c: CGPoint, u: CGFloat, actT: CGFloat, wave: CGFloat) {
        let cupC = CGPoint(x: size.width * 0.30, y: size.height * 0.58)
        let cupW = u * 0.26, cupH = u * 0.32, fillH = cupH * (0.30 + actT * 0.58)
        ctx.fill(RoundedRectangle(cornerRadius: 10).path(in: CGRect(x: cupC.x - cupW * 0.45, y: cupC.y + cupH * 0.46 - fillH, width: cupW * 0.90, height: fillH)),
                 with: .color(EyeStyle.Game.Theater.tearCyan.opacity(0.78)))
        ctx.stroke(RoundedRectangle(cornerRadius: 14).path(in: CGRect(x: cupC.x - cupW * 0.5, y: cupC.y - cupH * 0.5, width: cupW, height: cupH)),
                   with: .color(EyeStyle.Game.Theater.tearHighlight.opacity(0.85)), lineWidth: 3.5)
        let rightC = CGPoint(x: size.width * 0.68, y: size.height * 0.56)
        drawDetailedEye(ctx, center: rightC, radius: u * 0.22, lidClose: 0, gazeX: 0, dryRatio: 0)
        drawSparkle(ctx, center: CGPoint(x: rightC.x + u * 0.22, y: rightC.y - u * 0.22), radius: u * 0.055, color: EyeStyle.Game.Theater.lipidGold)
        for i in 0..<4 {
            let ft = (wave + CGFloat(i) * 0.25).truncatingRemainder(dividingBy: 1)
            let px = cupC.x + cupW * 0.5 + ft * (rightC.x - cupC.x - cupW * 0.7)
            let py = c.y - sin(ft * .pi) * u * 0.16
            let pr = u * 0.024
            ctx.fill(Path(ellipseIn: CGRect(x: px - pr, y: py - pr, width: pr * 2, height: pr * 2)),
                     with: .color(i % 2 == 0 ? EyeStyle.Game.Theater.tearCyan : EyeStyle.Game.Theater.lipidGold))
        }
    }

    private static func drawDetailedEye(_ ctx: GraphicsContext, center: CGPoint, radius: CGFloat, lidClose: CGFloat, gazeX: CGFloat, dryRatio: CGFloat) {
        let open = min(max(1 - lidClose, 0), 1)
        let w = radius * 1.82
        let h = radius * 1.12 * max(open, 0.14)
        if open < 0.22 {
            var closed = Path()
            closed.move(to: CGPoint(x: center.x - w * 0.48, y: center.y))
            closed.addQuadCurve(to: CGPoint(x: center.x + w * 0.48, y: center.y),
                                control: CGPoint(x: center.x, y: center.y + radius * 0.38))
            ctx.stroke(closed, with: .color(EyeStyle.Game.Theater.warmLamp), style: StrokeStyle(lineWidth: radius * 0.11, lineCap: .round))
            return
        }
        var contour = Path()
        contour.move(to: CGPoint(x: center.x - w * 0.5, y: center.y))
        contour.addQuadCurve(to: CGPoint(x: center.x + w * 0.5, y: center.y), control: CGPoint(x: center.x, y: center.y - h))
        contour.addQuadCurve(to: CGPoint(x: center.x - w * 0.5, y: center.y), control: CGPoint(x: center.x, y: center.y + h))
        contour.closeSubpath()
        ctx.fill(contour, with: .color(EyeStyle.Game.Theater.steam))
        let rim = dryRatio > 0.4 ? EyeStyle.Game.Theater.dryCoral : EyeStyle.Game.Theater.tearCyan
        ctx.stroke(contour, with: .color(rim.opacity(0.78)), style: StrokeStyle(lineWidth: radius * 0.085, lineJoin: .round))

        let irisC = CGPoint(x: center.x + gazeX * radius * 0.22, y: center.y)
        let irisR = radius * 0.39 * max(open, 0.55)
        ctx.fill(Path(ellipseIn: CGRect(x: irisC.x - irisR, y: irisC.y - irisR, width: irisR * 2, height: irisR * 2)),
                 with: .radialGradient(Gradient(colors: [EyeStyle.Game.Theater.tearCyan, EyeStyle.Game.Theater.tearDeep, EyeStyle.Game.Theater.skyNight]),
                                       center: irisC, startRadius: 0, endRadius: irisR))
        for i in 0..<12 {
            let a = CGFloat(i) * (2 * .pi / 12)
            var ray = Path()
            ray.move(to: CGPoint(x: irisC.x + cos(a) * irisR * 0.48, y: irisC.y + sin(a) * irisR * 0.48))
            ray.addLine(to: CGPoint(x: irisC.x + cos(a) * irisR * 0.90, y: irisC.y + sin(a) * irisR * 0.90))
            ctx.stroke(ray, with: .color(EyeStyle.Game.Theater.tearHighlight.opacity(0.22)), lineWidth: 1.6)
        }
        let pr = irisR * 0.48
        ctx.fill(Path(ellipseIn: CGRect(x: irisC.x - pr, y: irisC.y - pr, width: pr * 2, height: pr * 2)),
                 with: .color(EyeStyle.Game.Firefly.bodyDark))
        let hr1 = irisR * 0.24
        ctx.fill(Path(ellipseIn: CGRect(x: irisC.x - irisR * 0.28 - hr1, y: irisC.y - irisR * 0.26 - hr1, width: hr1 * 2, height: hr1 * 2)),
                 with: .color(EyeStyle.Game.Theater.tearHighlight))
        let hr2 = irisR * 0.10
        ctx.fill(Path(ellipseIn: CGRect(x: irisC.x + irisR * 0.22 - hr2, y: irisC.y + irisR * 0.20 - hr2, width: hr2 * 2, height: hr2 * 2)),
                 with: .color(EyeStyle.Game.Theater.tearHighlight.opacity(0.65)))
    }

    private static func drawSmilingDrop(_ ctx: GraphicsContext, center: CGPoint, radius: CGFloat) {
        var drop = Path()
        drop.move(to: CGPoint(x: center.x, y: center.y - radius * 1.25))
        drop.addCurve(to: CGPoint(x: center.x + radius * 0.90, y: center.y + radius * 0.55),
                      control1: CGPoint(x: center.x + radius * 0.45, y: center.y - radius * 0.45),
                      control2: CGPoint(x: center.x + radius * 0.95, y: center.y + radius * 0.15))
        drop.addCurve(to: CGPoint(x: center.x - radius * 0.90, y: center.y + radius * 0.55),
                      control1: CGPoint(x: center.x + radius * 0.85, y: center.y + radius * 1.05),
                      control2: CGPoint(x: center.x - radius * 0.85, y: center.y + radius * 1.05))
        drop.addCurve(to: CGPoint(x: center.x, y: center.y - radius * 1.25),
                      control1: CGPoint(x: center.x - radius * 0.95, y: center.y + radius * 0.15),
                      control2: CGPoint(x: center.x - radius * 0.45, y: center.y - radius * 0.45))
        drop.closeSubpath()
        ctx.fill(drop, with: .linearGradient(Gradient(colors: [EyeStyle.Game.Theater.tearHighlight, EyeStyle.Game.Theater.tearCyan, EyeStyle.Game.Theater.tearDeep]),
                                             startPoint: CGPoint(x: center.x, y: center.y - radius * 1.2),
                                             endPoint: CGPoint(x: center.x, y: center.y + radius)))
        let er = radius * 0.11
        ctx.fill(Path(ellipseIn: CGRect(x: center.x - radius * 0.25 - er, y: center.y + radius * 0.26 - er, width: er * 2, height: er * 2)),
                 with: .color(EyeStyle.Game.Firefly.bodyDark))
        ctx.fill(Path(ellipseIn: CGRect(x: center.x + radius * 0.25 - er, y: center.y + radius * 0.26 - er, width: er * 2, height: er * 2)),
                 with: .color(EyeStyle.Game.Firefly.bodyDark))
        var mouth = Path()
        mouth.move(to: CGPoint(x: center.x - radius * 0.18, y: center.y + radius * 0.46))
        mouth.addQuadCurve(to: CGPoint(x: center.x + radius * 0.18, y: center.y + radius * 0.46),
                           control: CGPoint(x: center.x, y: center.y + radius * 0.64))
        ctx.stroke(mouth, with: .color(EyeStyle.Game.Firefly.bodyDark), style: StrokeStyle(lineWidth: radius * 0.08, lineCap: .round))
    }

    private static func drawWaveBand(_ ctx: GraphicsContext, left: CGFloat, right: CGFloat, topY: CGFloat, thickness: CGFloat, phase: CGFloat, color: Color) {
        let steps = 16, w = right - left, amp = thickness * 0.20
        var p = Path()
        p.move(to: CGPoint(x: left, y: topY + thickness))
        for i in 0...steps {
            let f = CGFloat(i) / CGFloat(steps)
            let x = left + w * f
            let y = topY + sin((f * 2.5 + phase) * 2 * .pi) * amp
            p.addLine(to: CGPoint(x: x, y: y))
        }
        p.addLine(to: CGPoint(x: right, y: topY + thickness))
        p.closeSubpath()
        ctx.fill(p, with: .color(color))
    }

    private static func drawSparkle(_ ctx: GraphicsContext, center: CGPoint, radius: CGFloat, color: Color) {
        let inner = radius * 0.28
        var p = Path()
        p.move(to: CGPoint(x: center.x, y: center.y - radius))
        p.addQuadCurve(to: CGPoint(x: center.x + radius, y: center.y), control: CGPoint(x: center.x + inner * 0.3, y: center.y - inner * 0.3))
        p.addQuadCurve(to: CGPoint(x: center.x, y: center.y + radius), control: CGPoint(x: center.x + inner * 0.3, y: center.y + inner * 0.3))
        p.addQuadCurve(to: CGPoint(x: center.x - radius, y: center.y), control: CGPoint(x: center.x - inner * 0.3, y: center.y + inner * 0.3))
        p.addQuadCurve(to: CGPoint(x: center.x, y: center.y - radius), control: CGPoint(x: center.x - inner * 0.3, y: center.y - inner * 0.3))
        p.closeSubpath()
        ctx.fill(p, with: .color(color))
    }
}

/// 老视自测：矢车菊蓝信封舞台 + 下方四方向复古墨绿邮筒作答台。
private struct PostOfficeAnswerPad: View {
    @ObservedObject var runtime: EyeRuntime
    var body: some View {
        let visible = runtime.bool("trialVisible")
        VStack(spacing: 12) {
            PostOfficeEnvelopeView(runtime: runtime)
                .stageHeight(max(196, CGFloat(runtime.number("optotypePx")) / UIScreen.main.scale + 104))
            HStack(spacing: 8) {
                postbox("向上", 3)
                postbox("向下", 1)
                postbox("向左", 2)
                postbox("向右", 0)
            }
            Button { runtime.answer(-1) } label: {
                Text("看不清 · 跳过")
                    .font(EyeStyle.bodySecondary.weight(.semibold))
                    .foregroundStyle(visible ? EyeStyle.secondary : EyeStyle.tertiary)
                    .padding(.horizontal, 24).frame(minHeight: 48)
                    .background(EyeStyle.surfaceVariant.opacity(visible ? 1 : 0.45), in: EyeStyle.chipShape)
                    .overlay { EyeStyle.chipShape.strokeBorder(EyeStyle.borderSubtle, lineWidth: 1) }
            }
            .buttonStyle(.plain)
            .disabled(!visible)
        }
    }

    private func postbox(_ label: String, _ direction: Int) -> some View {
        let visible = runtime.bool("trialVisible")
        let matched = visible && (runtime.state["gestureDirection"] as? NSNumber)?.intValue == direction
        let holding = matched && !runtime.bool("gestureNeedsRelease")
        return Button { runtime.answer(direction) } label: {
            ZStack {
                Canvas { ctx, size in
                    let w = size.width, h = size.height
                    ctx.fill(RoundedRectangle(cornerRadius: 16).path(in: CGRect(x: 0, y: 0, width: w, height: h * 0.42)),
                             with: .color(EyeStyle.Game.PostOffice.boxTop))
                    ctx.fill(RoundedRectangle(cornerRadius: 10).path(in: CGRect(x: 0, y: h * 0.24, width: w, height: h * 0.76)),
                             with: .color(EyeStyle.Game.PostOffice.boxBody))
                    ctx.fill(RoundedRectangle(cornerRadius: 4).path(in: CGRect(x: w * 0.18, y: h * 0.13, width: w * 0.64, height: h * 0.085)),
                             with: .color(EyeStyle.Game.PostOffice.boxSlot))
                    ctx.fill(RoundedRectangle(cornerRadius: 6).path(in: CGRect(x: w * 0.10, y: h * 0.34, width: w * 0.80, height: h * 0.56)),
                             with: .color(EyeStyle.Game.PostOffice.boxSlot.opacity(0.45)))
                    ctx.stroke(RoundedRectangle(cornerRadius: 12).path(in: CGRect(x: 1.5, y: 1.5, width: w - 3, height: h - 3)),
                               with: .color(holding ? EyeStyle.primary : EyeStyle.Game.PostOffice.boxSlot), lineWidth: holding ? 2.5 : 1.5)
                }
                ArrowGlyph()
                    .stroke(holding ? EyeStyle.moodGold : Color.white,
                            style: StrokeStyle(lineWidth: 26 * 0.14, lineCap: .round, lineJoin: .round))
                    .frame(width: 26, height: 26)
                    .rotationEffect(.degrees(Double(direction) * 90))
                    .padding(.top, 18)
            }
            .frame(maxWidth: .infinity).frame(height: 74)
            .opacity(visible ? 1 : 0.4)
        }
        .buttonStyle(PressScale(scale: 0.93))
        .disabled(!visible)
        .accessibilityLabel(label)
    }
}

private struct PostOfficeEnvelopeView: View {
    @ObservedObject var runtime: EyeRuntime
    var body: some View {
        let visible = runtime.bool("trialVisible")
        Canvas { ctx, size in
            let w = size.width, h = size.height
            ctx.fill(Path(CGRect(origin: .zero, size: size)),
                     with: .linearGradient(Gradient(colors: [EyeStyle.Game.PostOffice.skyTop, EyeStyle.Game.PostOffice.skyMid, EyeStyle.Game.PostOffice.skyBottom]),
                                           startPoint: .zero, endPoint: CGPoint(x: 0, y: h)))
            let envW = min(w * 0.72, h * 1.48), envH = min(h * 0.70, envW * 0.68)
            let envL = (w - envW) * 0.5, envT = (h - envH) * 0.5
            let alpha: Double = visible ? 1 : 0.52
            ctx.fill(RoundedRectangle(cornerRadius: 8).path(in: CGRect(x: envL, y: envT, width: envW, height: envH)),
                     with: .linearGradient(Gradient(colors: [EyeStyle.Game.PostOffice.envelopeBase.opacity(alpha), EyeStyle.Game.PostOffice.envelopeDeep.opacity(alpha)]),
                                           startPoint: CGPoint(x: envL, y: envT), endPoint: CGPoint(x: envL + envW, y: envT + envH)))
            ctx.stroke(RoundedRectangle(cornerRadius: 4).path(in: CGRect(x: envL + 7, y: envT + 7, width: envW - 14, height: envH - 14)),
                       with: .color(EyeStyle.Game.PostOffice.envelopeFiligree.opacity(0.52 * alpha)), lineWidth: 1.4)
            let edge = max(0, CGFloat(runtime.number("optotypePx")) / UIScreen.main.scale)
            guard visible, edge > 0 else { return }
            Cartoon.tumblingE(ctx, color: EyeStyle.Game.PostOffice.optotypePinkWhite,
                              center: CGPoint(x: w * 0.5, y: h * 0.5), edge: edge,
                              direction: Int(runtime.number("direction")))
        }
        .clipShape(RoundedRectangle(cornerRadius: EyeStyle.radiusHero, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: EyeStyle.radiusHero, style: .continuous)
                .strokeBorder(EyeStyle.glassEdgeTop.opacity(0.4), lineWidth: 1)
        }
        .onChange(of: runtime.number("trialId")) { id in runtime.schedulePresented(Int32(id)) }
        .onAppear { runtime.schedulePresented(Int32(runtime.number("trialId"))) }
    }
}

private enum MoonlitFireflyRenderer {
    static func backdrop(_ ctx: GraphicsContext, size: CGSize, phase: CGFloat) {
        let w = size.width, h = size.height
        ctx.fill(Path(CGRect(origin: .zero, size: size)),
                 with: .linearGradient(Gradient(colors: [EyeStyle.Game.Firefly.skyTop, EyeStyle.Game.Firefly.skyMid, EyeStyle.Game.Firefly.skyBottom]),
                                       startPoint: .zero, endPoint: CGPoint(x: 0, y: h)))
        let moonC = CGPoint(x: w * 0.78, y: h * 0.11)
        let moonR = min(max(min(w, h) * 0.048, 14), 28)
        let haloR = moonR * 3.8
        ctx.fill(Path(ellipseIn: CGRect(x: moonC.x - haloR, y: moonC.y - haloR, width: haloR * 2, height: haloR * 2)),
                 with: .radialGradient(Gradient(colors: [EyeStyle.Game.Firefly.moonHalo.opacity(0.42), .clear]),
                                       center: moonC, startRadius: 0, endRadius: haloR))
        var crescent = Path()
        crescent.move(to: CGPoint(x: moonC.x + moonR * 0.25, y: moonC.y - moonR))
        crescent.addCurve(to: CGPoint(x: moonC.x + moonR * 0.25, y: moonC.y + moonR),
                          control1: CGPoint(x: moonC.x - moonR * 0.95, y: moonC.y - moonR * 0.72),
                          control2: CGPoint(x: moonC.x - moonR * 0.95, y: moonC.y + moonR * 0.72))
        crescent.addCurve(to: CGPoint(x: moonC.x + moonR * 0.25, y: moonC.y - moonR),
                          control1: CGPoint(x: moonC.x - moonR * 0.28, y: moonC.y + moonR * 0.52),
                          control2: CGPoint(x: moonC.x - moonR * 0.28, y: moonC.y - moonR * 0.52))
        crescent.closeSubpath()
        ctx.fill(crescent, with: .color(EyeStyle.Game.Firefly.moon))

        var leftTree = Path()
        leftTree.move(to: .zero)
        leftTree.addLine(to: CGPoint(x: w * 0.22, y: 0))
        leftTree.addQuadCurve(to: CGPoint(x: w * 0.12, y: h * 0.65), control: CGPoint(x: w * 0.15, y: h * 0.32))
        leftTree.addQuadCurve(to: CGPoint(x: w * 0.36, y: h * 0.88), control: CGPoint(x: w * 0.18, y: h * 0.80))
        leftTree.addLine(to: CGPoint(x: w * 0.32, y: h))
        leftTree.addLine(to: CGPoint(x: 0, y: h))
        leftTree.closeSubpath()
        ctx.fill(leftTree, with: .color(EyeStyle.Game.Firefly.treeNear.opacity(0.88)))

        var rightTree = Path()
        rightTree.move(to: CGPoint(x: w, y: 0))
        rightTree.addLine(to: CGPoint(x: w * 0.78, y: 0))
        rightTree.addQuadCurve(to: CGPoint(x: w * 0.88, y: h * 0.64), control: CGPoint(x: w * 0.86, y: h * 0.30))
        rightTree.addQuadCurve(to: CGPoint(x: w * 0.64, y: h * 0.89), control: CGPoint(x: w * 0.80, y: h * 0.80))
        rightTree.addLine(to: CGPoint(x: w * 0.68, y: h))
        rightTree.addLine(to: CGPoint(x: w, y: h))
        rightTree.closeSubpath()
        ctx.fill(rightTree, with: .color(EyeStyle.Game.Firefly.treeNear.opacity(0.88)))

        var creek = Path()
        creek.move(to: CGPoint(x: w * 0.44, y: h * 0.78))
        creek.addQuadCurve(to: CGPoint(x: w * 0.38, y: h), control: CGPoint(x: w * 0.54, y: h * 0.86))
        creek.addLine(to: CGPoint(x: w * 0.66, y: h))
        creek.addQuadCurve(to: CGPoint(x: w * 0.50, y: h * 0.78), control: CGPoint(x: w * 0.58, y: h * 0.86))
        creek.closeSubpath()
        ctx.fill(creek, with: .color(EyeStyle.Game.Firefly.creek.opacity(0.65)))
    }

    static func firefly(_ ctx: GraphicsContext, center: CGPoint, radius: CGFloat, wingPhase: CGFloat, glowIntensity: Double) {
        let glowAlpha = min(1, max(0.25, glowIntensity))
        let glowR = radius * (2.6 + 0.25 * CGFloat(sin(Double(wingPhase) * 2 * .pi)))
        ctx.fill(Path(ellipseIn: CGRect(x: center.x - glowR, y: center.y - glowR, width: glowR * 2, height: glowR * 2)),
                 with: .radialGradient(Gradient(stops: [
                    .init(color: EyeStyle.Game.Firefly.glowCore.opacity(0.92 * glowAlpha), location: 0),
                    .init(color: EyeStyle.Game.Firefly.glowMid.opacity(0.58 * glowAlpha), location: 0.35),
                    .init(color: EyeStyle.Game.Firefly.glowOuter.opacity(0.22 * glowAlpha), location: 0.70),
                    .init(color: .clear, location: 1),
                 ]), center: center, startRadius: 0, endRadius: glowR))
        Cartoon.rotated(ctx, -18, around: center) { layer in
            let flap = sin(Double(wingPhase) * 2 * .pi) * 14
            for side: CGFloat in [-1, 1] {
                Cartoon.rotated(layer, Double(side) * (34 + flap * 0.6), around: CGPoint(x: center.x, y: center.y - radius * 0.35)) { wl in
                    let rect = CGRect(x: center.x + (side < 0 ? -radius * 1.15 : radius * 0.05),
                                      y: center.y - radius * 1.05, width: radius * 1.10, height: radius * 0.56)
                    wl.fill(Path(ellipseIn: rect), with: .color(EyeStyle.Game.Firefly.wing.opacity(0.78)))
                }
            }
            let hr = radius * 0.34
            layer.fill(Path(ellipseIn: CGRect(x: center.x - hr, y: center.y - radius * 0.62 - hr, width: hr * 2, height: hr * 2)),
                       with: .color(EyeStyle.Game.Firefly.bodyDark))
            layer.fill(Path(ellipseIn: CGRect(x: center.x - radius * 0.36, y: center.y - radius * 0.42, width: radius * 0.72, height: radius * 0.46)),
                       with: .color(EyeStyle.Game.Firefly.bodyWarm))
            for (scale, color) in [(0.52, EyeStyle.Game.Firefly.glowOuter), (0.38, EyeStyle.Game.Firefly.glowMid), (0.22, EyeStyle.Game.Firefly.glowCore)] {
                let r = radius * CGFloat(scale)
                layer.fill(Path(ellipseIn: CGRect(x: center.x - r, y: center.y - r, width: r * 2, height: r * 2)),
                           with: .color(color.opacity(glowAlpha)))
            }
        }
    }
}

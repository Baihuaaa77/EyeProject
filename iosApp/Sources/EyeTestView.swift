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
            if runtime.kind == "DRY_EYE" || runtime.kind == "PRESBYOPIA" {
                SproutSetupCardView(
                    title: runtime.kind == "DRY_EYE" ? "干眼自测 · 40cm 浇水挑战" : "老视自测 · 40cm 近视力浇水挑战",
                    subtitle: runtime.kind == "DRY_EYE" ? "答对给小芽浇水，距离持续偏离自动暂停复位" : "覆盖 40–67–33 cm 看清 E 字与慢慢靠近",
                    distanceCm: runtime.number("distanceCm"),
                    targetCm: 40,
                    toleranceCm: 5,
                    faceDetected: runtime.bool("faceDetected")
                )
            } else {
                TestGuideView(motion: .fixate, title: "准备测试", cue: "正对屏幕，约 33 厘米")
            }
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
            TestGuideView(motion: .acuity, title: "热身浇水练习 · 约 12 秒", cue: "先练习一下：2 秒内看清 E 字方向点击四周箭头给小芽浇水；练习不计入结果") {
                runtime.act("practice")
            }
        } else if phase == "FormalGuide" {
            let seconds = max(1, Int(runtime.number("naturalSeconds")))
            if runtime.bool("afterRest") {
                Text("休息结束，下面进行复测，流程与首次相同").font(EyeStyle.body).frame(maxWidth: .infinity, alignment: .leading)
                    .padding(14).background(EyeStyle.accentSoft, in: RoundedRectangle(cornerRadius: EyeStyle.radiusMedium))
            }
            TestGuideView(motion: .blink, title: "40cm 浇水挑战 · \(seconds) 秒 + 20 秒",
                cue: "看清 E 字方向给小芽浇水。前 \(seconds) 秒自然眨眼，看到提示后尽量保持睁眼 20 秒；距离持续偏离将弹窗暂停", readyTitle: "开始浇水挑战") {
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
        } else if phase == "Natural" || phase == "Open" || phase == "Practice" {
            let blinks = Int(runtime.number(phase == "Open" ? "phase2BlinkCount" : "phase1BlinkCount"))
            let label = phase == "Practice" ? "热身浇水" : phase == "Open" ? "持续睁眼" : "自然眨眼"
            let headline = phase == "Practice" ? "热身练习 · 40cm 看清 E 字" : phase == "Open" ? "稳住睁眼 · 40cm 看清 E 字" : "第一关 | 40cm 看清 E 字"
            HStack {
                Text(label).font(EyeStyle.subtitle).foregroundStyle(phase == "Open" ? EyeStyle.warning : EyeStyle.primary)
                Spacer()
                if phase != "Practice" {
                    Text("已眨眼 \(blinks) 次").font(EyeStyle.caption).foregroundStyle(EyeStyle.secondary)
                }
                Text("剩余 \(Int(runtime.number("secondsLeft"))) 秒").font(EyeStyle.subtitle).monospacedDigit()
            }
            SproutWateringAnswerPadView(
                runtime: runtime,
                stageBadge: runtime.bool("gateReady") ? "40cm 保持稳定" : "40cm 校准中",
                stageHeadline: headline
            )
        } else {
            if phase == "NEAR_VISION" {
                let eye = ["RIGHT": "右眼", "LEFT": "左眼", "BOTH": "双眼"][runtime.string("eye")] ?? ""
                Text("第一关 | 40cm 看清 E 字 · \(eye) (\(Int(runtime.number("visionRound")))/3)").font(EyeStyle.subtitle)
            } else if phase == "DISTANCE_CURVE" {
                Text("多距离浇水 · \(Int(runtime.number("targetDistanceCm"))) cm (\(Int(runtime.number("visionRound")))/3)").font(EyeStyle.subtitle)
            } else if phase == "FLIPPER" {
                HStack {
                    Text((runtime.bool("flipperCheck") ? "先确认看得清 · " : "远近浇水 · ") + "\(Int(runtime.number("targetDistanceCm"))) cm")
                    Spacer()
                    if !runtime.bool("flipperCheck") { Text("\(Int(runtime.number("secondsLeft"))) 秒").monospacedDigit() }
                }
            } else if phase == "PUSH_UP" {
                Text("第二关 | 慢慢靠近 · \(runtime.string("pushEye")) (\(Int(runtime.number("visionRound")))/2)").font(EyeStyle.subtitle)
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
                Text("本眼第 \(min(Int(runtime.number("pushDone")) + 1, 3))/3 次").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                Text(hint.isEmpty ? (runtime.string("pushEye") == "左眼" ? "遮住右眼，用左眼看" : "遮住左眼，用右眼看") + "；先移到 45 cm 以外" : hint)
                Button("开始第 \(Int(runtime.number("pushDone")) + 1) 次") { runtime.act("pushStart") }
                    .buttonStyle(EyeButtonStyle()).disabled(!runtime.bool("presbyopiaCoverReady") ||
                        runtime.number("distanceCm") < 45)
            } else {
                if phase == "PUSH_UP" {
                    SproutPushUpStageView(runtime: runtime)
                } else {
                    let badge: String = phase == "NEAR_VISION"
                        ? "40cm · 第 \(min(4, Int(runtime.number("visionQuestion"))))/4 题"
                        : phase == "DISTANCE_CURVE"
                            ? "\(Int(runtime.number("targetDistanceCm")))cm · 第 \(Int(runtime.number("visionQuestion")))/3 题"
                            : "\(Int(runtime.number("targetDistanceCm")))cm 切换"
                    let head: String = phase == "NEAR_VISION"
                        ? "手机放眼前或放立桌上，看清方向就作答"
                        : phase == "DISTANCE_CURVE"
                            ? "测 40–67–33 cm · \(Int(runtime.number("targetDistanceCm")))cm 看清 E 字"
                            : (runtime.bool("flipperCheck") ? "先确认 \(Int(runtime.number("targetDistanceCm")))cm 能看清" : "剩余 \(Int(runtime.number("secondsLeft"))) 秒")
                    SproutWateringAnswerPadView(runtime: runtime, stageBadge: badge, stageHeadline: head)
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

// MARK: - 三项自测趣味小游戏场景（干眼与老视：小芽浇水挑战；斜视：月夜森林萤火虫）

private enum SproutBackdropMode { case clean, scenic }

/// 准备与 40 cm 校准卡（对齐安卓 SproutSetupCard 与甲方参考图 1 & 图 2）。
private struct SproutSetupCardView: View {
    let title: String
    let subtitle: String
    let distanceCm: Double
    let targetCm: Double
    let toleranceCm: Double
    let faceDetected: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let inRange = faceDetected && distanceCm > 0 && abs(distanceCm - targetCm) <= toleranceCm
        let distanceLabel: String = (!faceDetected || distanceCm <= 0)
            ? "等待相机识别距离"
            : inRange
                ? "当前距离：\(Int(distanceCm)) cm · 保持稳定"
                : distanceCm < targetCm - toleranceCm
                    ? "当前距离：\(Int(distanceCm)) cm · 请微调远一点至 \(Int(targetCm)) cm"
                    : "当前距离：\(Int(distanceCm)) cm · 请微调近一点至 \(Int(targetCm)) cm"
        let ratio = (faceDetected && distanceCm > 0) ? min(max((distanceCm - 20) / 40, 0.04), 0.96) : 0.5

        VStack(spacing: 0) {
            TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: reduceMotion)) { timeline in
                let wave: CGFloat = reduceMotion ? 0.5 : CGFloat(timeline.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 2.6) / 2.6)
                ZStack(alignment: .top) {
                    Canvas { ctx, size in
                        SproutSceneRenderer.drawBackdrop(ctx, size: size, mode: .scenic, wave: wave)
                        let sc = min(size.width, size.height) / 170
                        SproutSceneRenderer.drawPottedSprout(ctx, center: CGPoint(x: size.width * 0.44, y: size.height * 0.78), scale: sc, growthStage: 1, wilted: false, wave: wave)
                        SproutSceneRenderer.drawDropletMascot(ctx, center: CGPoint(x: size.width * 0.74, y: size.height * 0.72 + sin(wave * 2 * .pi) * 4), radius: min(size.width, size.height) * 0.14)
                        SproutSceneRenderer.drawWaterDrop(ctx, center: CGPoint(x: size.width * 0.63, y: size.height * 0.38 + sin(wave * 2 * .pi) * 5), radius: 8)
                        SproutSceneRenderer.drawWaterDrop(ctx, center: CGPoint(x: size.width * 0.70, y: size.height * 0.50 - sin(wave * 2 * .pi) * 4), radius: 6)
                    }
                    VStack(spacing: 2) {
                        Text(title).font(EyeStyle.title).foregroundStyle(EyeStyle.Game.Sprout.cloudWhite)
                        Text(subtitle).font(EyeStyle.caption).foregroundStyle(EyeStyle.Game.Sprout.cloudWhite.opacity(0.92))
                    }
                    .multilineTextAlignment(.center)
                    .padding(.top, 12).padding(.horizontal, 14)
                }
                .frame(height: 172)
            }
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text("标准测试距离：\(Int(targetCm)) cm").font(EyeStyle.chip).foregroundStyle(EyeStyle.Game.Sprout.cloudWhite)
                    Spacer()
                    Text("可手持，也可放立桌上").font(EyeStyle.caption).foregroundStyle(EyeStyle.Game.Sprout.dropLight)
                }
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Capsule().fill(EyeStyle.Game.Sprout.cloudWhite.opacity(0.22))
                        Capsule()
                            .fill(inRange ? EyeStyle.Game.Sprout.badgeStable : EyeStyle.Game.Sprout.dropBlue)
                            .frame(width: geo.size.width * CGFloat(ratio))
                    }
                }
                .frame(height: 8)
                Text(distanceLabel)
                    .font(EyeStyle.bodySecondary)
                    .foregroundStyle(inRange ? EyeStyle.Game.Sprout.leafBright : EyeStyle.Game.Sprout.cloudWhite)
            }
            .padding(.horizontal, 16).padding(.vertical, 12)
            .background(EyeStyle.Game.Sprout.cardSoilBase.opacity(0.92))
        }
        .clipShape(RoundedRectangle(cornerRadius: EyeStyle.radiusHero, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: EyeStyle.radiusHero, style: .continuous)
                .strokeBorder(EyeStyle.glassEdgeTop.opacity(0.45), lineWidth: 1)
        }
    }
}

/// 看 E 浇水挑战主舞台（干眼与老视共用）：E 字四周紧贴环绕四方向箭头，不保留底部四向按钮行。
private struct SproutWateringAnswerPadView: View {
    @ObservedObject var runtime: EyeRuntime
    let stageBadge: String
    let stageHeadline: String
    @State private var mode: SproutBackdropMode = .clean
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let visible = runtime.bool("trialVisible")
        let edge = max(0, CGFloat(runtime.number("optotypePx")) / UIScreen.main.scale)
        let direction = Int(runtime.number("direction"))
        let correctCount = Int(runtime.number("correctCount"))
        let wrongCount = Int(runtime.number("wrongCount"))
        let lastCorrect = runtime.state["lastAnswerCorrect"] as? Bool
        let growthStage = correctCount >= 12 ? 4 : correctCount >= 8 ? 3 : correctCount >= 4 ? 2 : correctCount >= 1 ? 1 : 0
        let wilted = (lastCorrect == false) && wrongCount > correctCount
        let isClean = mode == .clean
        let optotypeColor = isClean ? EyeStyle.Game.Sprout.optotypeInk : EyeStyle.Game.Sprout.cloudWhite
        let headlineColor = isClean ? EyeStyle.Game.Sprout.arrowDark : EyeStyle.Game.Sprout.cloudWhite
        let statusText: String = lastCorrect == true
            ? "浇水成功！已浇 \(correctCount) 滴，小芽正在长大"
            : lastCorrect == false
                ? "水滴蒸发了，看清 E 开口方向再给小芽浇水"
                : "答对浇水让小芽长大 · 可手持或放立桌上"
        let centerBox = max(82, edge + 20)

        VStack(spacing: 10) {
            ZStack {
                TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: reduceMotion)) { timeline in
                    let wave: CGFloat = reduceMotion ? 0.25 : CGFloat(timeline.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 3.4) / 3.4)
                    Canvas { ctx, size in
                        SproutSceneRenderer.drawBackdrop(ctx, size: size, mode: mode, wave: wave)
                        let sc = min(max(min(size.width, size.height) / 310, 0.8), 1.35)
                        let sproutCenter = CGPoint(x: size.width * (isClean ? 0.50 : 0.44), y: size.height * 0.87)
                        if isClean {
                            SproutSceneRenderer.drawLakeIslandSprout(ctx, center: sproutCenter, scale: sc, growthStage: growthStage, wilted: wilted, wave: wave)
                            SproutSceneRenderer.drawWaterDrop(ctx, center: CGPoint(x: sproutCenter.x, y: sproutCenter.y - 50 + sin(wave * 2 * .pi) * 3), radius: 8.5)
                        } else {
                            SproutSceneRenderer.drawPottedSprout(ctx, center: sproutCenter, scale: sc, growthStage: growthStage, wilted: wilted, wave: wave)
                            SproutSceneRenderer.drawDropletMascot(ctx, center: CGPoint(x: size.width * 0.74, y: size.height * 0.84 + sin(wave * 2 * .pi) * 3), radius: min(size.width, size.height) * 0.082)
                        }
                    }
                }

                VStack(spacing: 6) {
                    HStack {
                        HStack(spacing: 6) {
                            Text(stageBadge)
                                .font(EyeStyle.chip)
                                .foregroundStyle(EyeStyle.Game.Sprout.cloudWhite)
                                .padding(.horizontal, 10).padding(.vertical, 4)
                                .background(EyeStyle.Game.Sprout.badgeStable, in: EyeStyle.chipShape)
                            Text("浇水 \(correctCount) 滴")
                                .font(EyeStyle.caption)
                                .foregroundStyle(EyeStyle.Game.Sprout.dropLight)
                                .padding(.horizontal, 9).padding(.vertical, 4)
                                .background(EyeStyle.Game.Sprout.cardSoilBase.opacity(0.72), in: EyeStyle.chipShape)
                        }
                        Spacer()
                        Button {
                            mode = isClean ? .scenic : .clean
                        } label: {
                            Text(isClean ? "切湖景背景" : "切清爽背景")
                                .font(EyeStyle.caption)
                                .foregroundStyle(EyeStyle.Game.Sprout.cloudWhite)
                                .padding(.horizontal, 10).padding(.vertical, 4)
                                .background(EyeStyle.Game.Sprout.cardSoilBase.opacity(0.72), in: EyeStyle.chipShape)
                                .overlay { EyeStyle.chipShape.strokeBorder(EyeStyle.Game.Sprout.cloudWhite.opacity(0.35), lineWidth: 1) }
                        }
                        .buttonStyle(.plain)
                    }

                    if !stageHeadline.isEmpty {
                        Text(stageHeadline).font(EyeStyle.subtitle).foregroundStyle(headlineColor).multilineTextAlignment(.center)
                    }

                    // 中央 E 字周围紧贴环绕上/下/左/右四向大箭头
                    VStack(spacing: 4) {
                        arrowButton("向上", 3, visible: visible)
                        HStack(spacing: 8) {
                            arrowButton("向左", 2, visible: visible)
                            ZStack {
                                if !isClean {
                                    Circle().fill(EyeStyle.Game.Sprout.arrowDark.opacity(visible ? 0.22 : 0.10))
                                }
                                if visible && edge > 0 {
                                    Canvas { ctx, sz in
                                        Cartoon.tumblingE(ctx, color: optotypeColor, center: CGPoint(x: sz.width * 0.5, y: sz.height * 0.5), edge: edge, direction: direction)
                                    }
                                    .frame(width: centerBox, height: centerBox)
                                } else {
                                    Text("方向?").font(EyeStyle.caption).foregroundStyle(headlineColor.opacity(0.65))
                                }
                            }
                            .frame(width: centerBox, height: centerBox)
                            arrowButton("向右", 0, visible: visible)
                        }
                        arrowButton("向下", 1, visible: visible)
                    }

                    Spacer(minLength: 70)

                    Text(statusText)
                        .font(EyeStyle.caption)
                        .foregroundStyle(lastCorrect == false ? EyeStyle.Game.Sprout.daisyCenter : EyeStyle.Game.Sprout.cloudWhite)
                        .padding(.horizontal, 12).padding(.vertical, 4)
                        .background(EyeStyle.Game.Sprout.cardSoilBase.opacity(0.78), in: EyeStyle.chipShape)
                }
                .padding(.horizontal, 12).padding(.vertical, 10)
            }
            .stageHeight(max(316, edge + 236))
            .clipShape(RoundedRectangle(cornerRadius: EyeStyle.radiusHero, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: EyeStyle.radiusHero, style: .continuous)
                    .strokeBorder(EyeStyle.glassEdgeTop.opacity(0.45), lineWidth: 1)
            }
            .onChange(of: runtime.number("trialId")) { id in runtime.schedulePresented(Int32(id)) }
            .onAppear { runtime.schedulePresented(Int32(runtime.number("trialId"))) }

            // 不保留底部四方向键，仅保留次要「看不清 · 跳过」胶囊
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

    private func arrowButton(_ label: String, _ direction: Int, visible: Bool) -> some View {
        let matched = visible && (runtime.state["gestureDirection"] as? NSNumber)?.intValue == direction
        let holding = matched && !runtime.bool("gestureNeedsRelease")
        let progress = holding ? max(0.12, min(1, runtime.number("gestureProgress"))) : 0
        return Button { runtime.answer(direction) } label: {
            ZStack {
                Circle()
                    .fill(holding ? EyeStyle.primary : EyeStyle.Game.Sprout.arrowSurface.opacity(0.94))
                    .overlay {
                        Circle().strokeBorder(holding ? EyeStyle.primary : EyeStyle.Game.Sprout.arrowDark.opacity(0.22), lineWidth: holding ? 2.5 : 1.5)
                    }
                if holding {
                    Circle().trim(from: 0, to: CGFloat(progress))
                        .stroke(EyeStyle.Game.Sprout.cloudWhite, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                        .padding(3)
                }
                ArrowGlyph()
                    .stroke(holding ? EyeStyle.Game.Sprout.cloudWhite : EyeStyle.Game.Sprout.arrowDark,
                            style: StrokeStyle(lineWidth: 26 * 0.16, lineCap: .round, lineJoin: .round))
                    .frame(width: 26, height: 26)
                    .rotationEffect(.degrees(Double(direction) * 90))
            }
            .frame(width: 52, height: 52)
            .opacity(visible ? 1 : 0.42)
        }
        .buttonStyle(PressScale(scale: 0.90))
        .disabled(!visible)
        .accessibilityLabel(label)
    }
}

/// 第二关「慢慢靠近」移近法互动舞台（对齐安卓 SproutPushUpStage 与参考图 4）。
private struct SproutPushUpStageView: View {
    @ObservedObject var runtime: EyeRuntime
    @State private var mode: SproutBackdropMode = .scenic
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let visible = runtime.bool("trialVisible")
        let edge = max(0, CGFloat(runtime.number("optotypePx")) / UIScreen.main.scale)
        let currentCm = runtime.number("distanceCm")
        let rawStart = runtime.number("pushStartCm")
        let startCm = rawStart > 25 ? rawStart : 67
        let progress = currentCm > 0 ? min(max((startCm - currentCm) / max(startCm - 10, 10), 0.05), 1) : 0.05
        let speed = (runtime.state["pushSpeedCmPerSec"] as? NSNumber)?.doubleValue
        let speedText: String = {
            guard let s = speed, s > 0.15 else { return "速度提示：缓慢平稳靠近（建议约 1–2 cm/s）" }
            if s > 3.2 { return String(format: "速度偏快：约 %.1f cm/s，请慢一点", s) }
            return String(format: "速度正常：约 %.1f cm/s", s)
        }()
        let isClean = mode == .clean
        let textColor = isClean ? EyeStyle.Game.Sprout.arrowDark : EyeStyle.Game.Sprout.cloudWhite
        let boxSize = max(116, edge + 36)

        VStack(spacing: 10) {
            ZStack {
                TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: reduceMotion)) { timeline in
                    let wave: CGFloat = reduceMotion ? 0.25 : CGFloat(timeline.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 3.4) / 3.4)
                    Canvas { ctx, size in
                        SproutSceneRenderer.drawBackdrop(ctx, size: size, mode: mode, wave: wave)
                        SproutSceneRenderer.drawLakeIslandSprout(ctx, center: CGPoint(x: size.width * 0.50, y: size.height * 0.67), scale: min(max(min(size.width, size.height) / 315, 0.75), 1.18), growthStage: 2, wilted: false, wave: wave)
                    }
                }
                VStack(spacing: 8) {
                    HStack {
                        Text("起始 \(Int(startCm)) cm").font(EyeStyle.caption).foregroundStyle(textColor)
                        Spacer()
                        Text(currentCm > 0 ? "当前 \(Int(currentCm)) cm" : "测距中…").font(EyeStyle.chip).foregroundStyle(textColor)
                        Spacer()
                        Button { mode = isClean ? .scenic : .clean } label: {
                            Text(isClean ? "切湖景" : "切清爽")
                                .font(EyeStyle.caption).foregroundStyle(EyeStyle.Game.Sprout.cloudWhite)
                                .padding(.horizontal, 9).padding(.vertical, 3)
                                .background(EyeStyle.Game.Sprout.cardSoilBase.opacity(0.72), in: EyeStyle.chipShape)
                        }
                        .buttonStyle(.plain)
                    }
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            Capsule().fill(EyeStyle.Game.Sprout.cardSoilBase.opacity(0.35))
                            Capsule().fill(EyeStyle.Game.Sprout.leafBright).frame(width: geo.size.width * CGFloat(progress))
                        }
                    }
                    .frame(height: 6)

                    Text("慢慢靠近 · \(runtime.string("pushEye")) 第 \(min(Int(runtime.number("pushDone")) + 1, 3))/3 次")
                        .font(EyeStyle.subtitle).foregroundStyle(textColor)

                    ZStack {
                        Canvas { ctx, sz in
                            let c = isClean ? EyeStyle.Game.Sprout.arrowDark.opacity(0.45) : EyeStyle.Game.Sprout.cloudWhite.opacity(0.75)
                            let len: CGFloat = 18
                            var brackets = Path()
                            brackets.move(to: CGPoint(x: 0, y: len)); brackets.addLine(to: .zero); brackets.addLine(to: CGPoint(x: len, y: 0))
                            brackets.move(to: CGPoint(x: sz.width - len, y: 0)); brackets.addLine(to: CGPoint(x: sz.width, y: 0)); brackets.addLine(to: CGPoint(x: sz.width, y: len))
                            brackets.move(to: CGPoint(x: 0, y: sz.height - len)); brackets.addLine(to: CGPoint(x: 0, y: sz.height)); brackets.addLine(to: CGPoint(x: len, y: sz.height))
                            brackets.move(to: CGPoint(x: sz.width - len, y: sz.height)); brackets.addLine(to: CGPoint(x: sz.width, y: sz.height)); brackets.addLine(to: CGPoint(x: sz.width, y: sz.height - len))
                            ctx.stroke(brackets, with: .color(c), style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                            if visible && edge > 0 {
                                Cartoon.tumblingE(ctx, color: isClean ? EyeStyle.Game.Sprout.optotypeInk : EyeStyle.Game.Sprout.cloudWhite,
                                                  center: CGPoint(x: sz.width * 0.5, y: sz.height * 0.5), edge: edge, direction: 0)
                            }
                        }
                    }
                    .frame(width: boxSize, height: boxSize)

                    Text("像给小芽送水一样，慢慢把手机靠近脸")
                        .font(EyeStyle.caption).foregroundStyle(textColor.opacity(0.92))

                    Spacer(minLength: 48)

                    Text("努力后仍看不清，就停下").font(EyeStyle.caption).foregroundStyle(EyeStyle.Game.Sprout.cloudWhite)
                    Button { runtime.act("blur") } label: {
                        Text("持续模糊，点这里")
                            .font(EyeStyle.subtitle)
                            .foregroundStyle(EyeStyle.Game.Sprout.cardSoilBase)
                            .frame(maxWidth: .infinity, minHeight: 50)
                            .background(EyeStyle.Game.Sprout.cardCream, in: EyeStyle.chipShape)
                    }
                    .buttonStyle(.plain)

                    Text(speedText)
                        .font(EyeStyle.caption)
                        .foregroundStyle((speed ?? 0) > 3.2 ? EyeStyle.Game.Sprout.daisyCenter : EyeStyle.Game.Sprout.leafBright)
                        .padding(.horizontal, 12).padding(.vertical, 4)
                        .background(EyeStyle.Game.Sprout.cardSoilBase.opacity(0.78), in: EyeStyle.chipShape)
                }
                .padding(.horizontal, 16).padding(.vertical, 12)
            }
            .stageHeight(max(324, edge + 220))
            .clipShape(RoundedRectangle(cornerRadius: EyeStyle.radiusHero, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: EyeStyle.radiusHero, style: .continuous)
                    .strokeBorder(EyeStyle.glassEdgeTop.opacity(0.45), lineWidth: 1)
            }
            .onChange(of: runtime.number("trialId")) { id in runtime.schedulePresented(Int32(id)) }
            .onAppear { runtime.schedulePresented(Int32(runtime.number("trialId"))) }

            if runtime.bool("pushStillClearAvailable") {
                Button("仍看得清") { runtime.act("pushStillClear") }.buttonStyle(EyeButtonStyle(role: .secondary))
            }
        }
    }
}

/// 干眼/老视结果页顶部小芽开花/缺水成长卡（对齐安卓 SproutBloomResultCard 与参考图 5）。
struct SproutBloomResultCardView: View {
    let testTitle: String
    let report: ReportModel

    var body: some View {
        let wilted = !report.valid || report.level == "HIGH"
        let bloomed = report.valid && (report.level == "LOW" || report.level == "UNCERTAIN")
        let growthStage = bloomed ? 4 : wilted ? 1 : 3
        let growthMessage = bloomed
            ? "小芽喝饱水，开出了健康小雏菊！"
            : wilted
                ? "小芽有点缺水萎蔫，记得多眨眼与及时护眼休息"
                : "小芽正在积蓄水分，平时注意定时远眺与润眼"
        let shortName = testTitle.hasSuffix("完成") ? String(testTitle.dropLast(2)) : testTitle

        ZStack {
            Canvas { ctx, size in
                SproutSceneRenderer.drawBackdrop(ctx, size: size, mode: .scenic, wave: 0.3)
            }
            VStack(spacing: 8) {
                Text(testTitle).font(EyeStyle.title).foregroundStyle(EyeStyle.Game.Sprout.cloudWhite)
                Canvas { ctx, size in
                    SproutSceneRenderer.drawPottedSprout(ctx, center: CGPoint(x: size.width * 0.5, y: size.height * 0.96), scale: 1.05, growthStage: growthStage, wilted: wilted, wave: 0.25, showPot: false)
                }
                .frame(width: 140, height: 96)
                VStack(spacing: 0) {
                    VStack(spacing: 4) {
                        Text("\(report.badge) · \(report.title)")
                            .font(EyeStyle.subtitle).foregroundStyle(EyeStyle.Game.Sprout.cardSoilBase)
                            .multilineTextAlignment(.center)
                        Text(growthMessage)
                            .font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.Game.Sprout.soilDark)
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.horizontal, 14).padding(.vertical, 12)
                    .background(EyeStyle.Game.Sprout.cardCream, in: RoundedRectangle(cornerRadius: EyeStyle.radiusMedium, style: .continuous))
                    .padding(.horizontal, 10).padding(.top, 10)
                    Text("我完成了\(shortName)，你也来试试？")
                        .font(EyeStyle.caption)
                        .foregroundStyle(EyeStyle.Game.Sprout.cloudWhite.opacity(0.90))
                        .padding(.vertical, 10)
                }
                .background(EyeStyle.Game.Sprout.cardSoilBase, in: RoundedRectangle(cornerRadius: EyeStyle.radiusLarge, style: .continuous))
            }
            .padding(.horizontal, 18).padding(.vertical, 16)
        }
        .clipShape(RoundedRectangle(cornerRadius: EyeStyle.radiusHero, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: EyeStyle.radiusHero, style: .continuous)
                .strokeBorder(EyeStyle.glassEdgeTop.opacity(0.45), lineWidth: 1)
        }
    }
}

/// 小芽浇水挑战共用矢量绘制引擎（清爽晴空 / 湖光山色草地 + 成长小芽 + 小雏菊 + 水滴精灵）。
private enum SproutSceneRenderer {
    static func drawBackdrop(_ ctx: GraphicsContext, size: CGSize, mode: SproutBackdropMode, wave: CGFloat) {
        let w = size.width, h = size.height
        if mode == .clean {
            ctx.fill(Path(CGRect(origin: .zero, size: size)),
                     with: .linearGradient(Gradient(stops: [
                        .init(color: EyeStyle.Game.Sprout.skyCleanTop, location: 0),
                        .init(color: EyeStyle.Game.Sprout.skyCleanMid, location: 0.62),
                        .init(color: EyeStyle.Game.Sprout.lakeTop, location: 1),
                     ]), startPoint: .zero, endPoint: CGPoint(x: 0, y: h)))
            let horizon = h * 0.68
            var hill = Path()
            hill.move(to: CGPoint(x: 0, y: horizon))
            hill.addQuadCurve(to: CGPoint(x: w * 0.36, y: horizon), control: CGPoint(x: w * 0.18, y: horizon - h * 0.08))
            hill.addQuadCurve(to: CGPoint(x: w * 0.84, y: horizon - h * 0.09), control: CGPoint(x: w * 0.65, y: horizon - h * 0.04))
            hill.addLine(to: CGPoint(x: w, y: horizon - h * 0.02))
            hill.addLine(to: CGPoint(x: w, y: horizon))
            hill.closeSubpath()
            ctx.fill(hill, with: .color(EyeStyle.Game.Sprout.mountainFar.opacity(0.45)))
            ctx.fill(Path(CGRect(x: 0, y: horizon, width: w, height: h - horizon)),
                     with: .linearGradient(Gradient(colors: [EyeStyle.Game.Sprout.lakeTop.opacity(0.72), EyeStyle.Game.Sprout.lakeDeep.opacity(0.88)]),
                                           startPoint: CGPoint(x: 0, y: horizon), endPoint: CGPoint(x: 0, y: h)))
            for i in 0..<3 {
                let ry = horizon + (h - horizon) * (0.28 + CGFloat(i) * 0.24)
                let rx = w * (0.5 + sin((wave + CGFloat(i) * 0.3) * 2 * .pi) * 0.03)
                var line = Path()
                line.move(to: CGPoint(x: rx - w * (0.26 - CGFloat(i) * 0.05), y: ry))
                line.addLine(to: CGPoint(x: rx + w * (0.26 - CGFloat(i) * 0.05), y: ry))
                ctx.stroke(line, with: .color(EyeStyle.Game.Sprout.lakeRipple.opacity(0.48 - Double(i) * 0.1)), style: StrokeStyle(lineWidth: 2, lineCap: .round))
            }
            return
        }

        ctx.fill(Path(CGRect(origin: .zero, size: size)),
                 with: .linearGradient(Gradient(stops: [
                    .init(color: EyeStyle.Game.Sprout.skySceneTop, location: 0),
                    .init(color: EyeStyle.Game.Sprout.skySceneMid, location: 0.48),
                    .init(color: EyeStyle.Game.Sprout.lakeTop, location: 1),
                 ]), startPoint: .zero, endPoint: CGPoint(x: 0, y: h)))
        drawCloud(ctx, center: CGPoint(x: w * 0.14, y: h * 0.25), r: min(w, h) * 0.08)
        drawCloud(ctx, center: CGPoint(x: w * 0.87, y: h * 0.27), r: min(w, h) * 0.07)
        let horizon = h * 0.58
        var farM = Path()
        farM.move(to: CGPoint(x: 0, y: horizon)); farM.addLine(to: CGPoint(x: 0, y: horizon - h * 0.10))
        farM.addQuadCurve(to: CGPoint(x: w * 0.46, y: horizon - h * 0.06), control: CGPoint(x: w * 0.22, y: horizon - h * 0.24))
        farM.addQuadCurve(to: CGPoint(x: w, y: horizon - h * 0.08), control: CGPoint(x: w * 0.74, y: horizon - h * 0.26))
        farM.addLine(to: CGPoint(x: w, y: horizon)); farM.closeSubpath()
        ctx.fill(farM, with: .color(EyeStyle.Game.Sprout.mountainFar.opacity(0.78)))
        var nearM = Path()
        nearM.move(to: CGPoint(x: 0, y: horizon))
        nearM.addQuadCurve(to: CGPoint(x: w * 0.34, y: horizon), control: CGPoint(x: w * 0.16, y: horizon - h * 0.14))
        nearM.addLine(to: CGPoint(x: w * 0.66, y: horizon))
        nearM.addQuadCurve(to: CGPoint(x: w, y: horizon - h * 0.03), control: CGPoint(x: w * 0.85, y: horizon - h * 0.16))
        nearM.addLine(to: CGPoint(x: w, y: horizon)); nearM.closeSubpath()
        ctx.fill(nearM, with: .color(EyeStyle.Game.Sprout.mountainNear.opacity(0.85)))

        let grassTop = h * 0.74
        ctx.fill(Path(CGRect(x: 0, y: horizon, width: w, height: grassTop - horizon)),
                 with: .linearGradient(Gradient(colors: [EyeStyle.Game.Sprout.lakeTop, EyeStyle.Game.Sprout.lakeDeep]),
                                       startPoint: CGPoint(x: 0, y: horizon), endPoint: CGPoint(x: 0, y: grassTop)))
        var meadow = Path()
        meadow.move(to: CGPoint(x: 0, y: h)); meadow.addLine(to: CGPoint(x: 0, y: grassTop))
        meadow.addQuadCurve(to: CGPoint(x: w, y: grassTop), control: CGPoint(x: w * 0.5, y: grassTop - h * 0.07))
        meadow.addLine(to: CGPoint(x: w, y: h)); meadow.closeSubpath()
        ctx.fill(meadow, with: .linearGradient(Gradient(colors: [EyeStyle.Game.Sprout.grassLight, EyeStyle.Game.Sprout.grassDeep]),
                                               startPoint: CGPoint(x: 0, y: grassTop - h * 0.05), endPoint: CGPoint(x: 0, y: h)))
    }

    private static func drawCloud(_ ctx: GraphicsContext, center: CGPoint, r: CGFloat) {
        let c = EyeStyle.Game.Sprout.cloudWhite.opacity(0.55)
        for (dx, dy, scale): (CGFloat, CGFloat, CGFloat) in [(-0.65, 0.12, 0.72), (0, 0, 1.0), (0.70, 0.14, 0.68)] {
            let rr = r * scale
            ctx.fill(Path(ellipseIn: CGRect(x: center.x + dx * r - rr, y: center.y + dy * r - rr, width: rr * 2, height: rr * 2)), with: .color(c))
        }
    }

    static func drawLakeIslandSprout(_ ctx: GraphicsContext, center: CGPoint, scale s: CGFloat, growthStage: Int, wilted: Bool, wave: CGFloat) {
        for i in 0..<2 {
            let rw = (44 + CGFloat(i) * 20 + sin(wave * 2 * .pi) * 3) * s
            let rh = (12 + CGFloat(i) * 5) * s
            ctx.stroke(Path(ellipseIn: CGRect(x: center.x - rw * 0.5, y: center.y - rh * 0.5 + 4 * s, width: rw, height: rh)),
                       with: .color(EyeStyle.Game.Sprout.lakeRipple.opacity(0.52 - Double(i) * 0.2)), lineWidth: 2)
        }
        ctx.fill(Path(ellipseIn: CGRect(x: center.x - 20 * s, y: center.y - 6 * s, width: 40 * s, height: 14 * s)), with: .color(EyeStyle.Game.Sprout.soilDark))
        drawPlant(ctx, base: CGPoint(x: center.x, y: center.y - 2 * s), s: s, growthStage: growthStage, wilted: wilted, wave: wave)
    }

    static func drawPottedSprout(_ ctx: GraphicsContext, center: CGPoint, scale s: CGFloat, growthStage: Int, wilted: Bool, wave: CGFloat, showPot: Bool = true) {
        if showPot {
            let potW = 46 * s, potH = 34 * s, potTop = center.y - potH
            var body = Path()
            body.move(to: CGPoint(x: center.x - potW * 0.44, y: potTop + 6 * s))
            body.addLine(to: CGPoint(x: center.x + potW * 0.44, y: potTop + 6 * s))
            body.addLine(to: CGPoint(x: center.x + potW * 0.34, y: center.y))
            body.addLine(to: CGPoint(x: center.x - potW * 0.34, y: center.y))
            body.closeSubpath()
            ctx.fill(body, with: .color(EyeStyle.Game.Sprout.potBody))
            ctx.fill(RoundedRectangle(cornerRadius: 4 * s).path(in: CGRect(x: center.x - potW * 0.52, y: potTop, width: potW * 1.04, height: 10 * s)),
                     with: .color(EyeStyle.Game.Sprout.potRim))
            ctx.fill(Path(ellipseIn: CGRect(x: center.x - potW * 0.42, y: potTop - 3 * s, width: potW * 0.84, height: 8 * s)),
                     with: .color(EyeStyle.Game.Sprout.soilDark))
            drawPlant(ctx, base: CGPoint(x: center.x, y: potTop), s: s, growthStage: growthStage, wilted: wilted, wave: wave)
        } else {
            ctx.fill(Path(ellipseIn: CGRect(x: center.x - 24 * s, y: center.y - 8 * s, width: 48 * s, height: 14 * s)),
                     with: .color(EyeStyle.Game.Sprout.soilDark))
            drawPlant(ctx, base: CGPoint(x: center.x, y: center.y - 4 * s), s: s, growthStage: growthStage, wilted: wilted, wave: wave)
        }
    }

    private static func drawPlant(_ ctx: GraphicsContext, base: CGPoint, s: CGFloat, growthStage: Int, wilted: Bool, wave: CGFloat) {
        let sway = sin(wave * 2 * .pi) * 2.5 * s
        let stemH = (24 + CGFloat(growthStage) * 7.5) * s
        let bend = wilted ? 10 * s : sway
        let top = CGPoint(x: base.x + bend, y: base.y - stemH * (wilted ? 0.78 : 1.0))
        let leafPrimary = wilted ? EyeStyle.Game.Sprout.leafWilt : EyeStyle.Game.Sprout.leafBright
        let leafSecondary = wilted ? EyeStyle.Game.Sprout.leafWilt.opacity(0.82) : EyeStyle.Game.Sprout.leafMid

        var stem = Path()
        stem.move(to: base)
        stem.addQuadCurve(to: top, control: CGPoint(x: base.x + bend * 0.35, y: base.y - stemH * 0.5))
        ctx.stroke(stem, with: .color(leafSecondary), style: StrokeStyle(lineWidth: 4.2 * s, lineCap: .round))

        let leafLen = (16 + CGFloat(growthStage) * 2.2) * s
        let droop = wilted ? 9 * s : -8 * s
        drawLeaf(ctx, anchor: CGPoint(x: top.x, y: top.y + 4 * s), dx: -leafLen, dy: droop, width: 9 * s, color: leafPrimary)
        drawLeaf(ctx, anchor: CGPoint(x: top.x, y: top.y + 4 * s), dx: leafLen, dy: droop, width: 9 * s, color: leafSecondary)

        if growthStage >= 2 {
            let mid = CGPoint(x: base.x + bend * 0.45, y: base.y - stemH * 0.46)
            drawLeaf(ctx, anchor: mid, dx: -leafLen * 0.85, dy: droop * 0.6, width: 7.5 * s, color: leafSecondary)
            drawLeaf(ctx, anchor: mid, dx: leafLen * 0.85, dy: droop * 0.6, width: 7.5 * s, color: leafPrimary)
        }
        if growthStage == 3 && !wilted {
            let r1 = 6.5 * s, r2 = 3.5 * s
            ctx.fill(Path(ellipseIn: CGRect(x: top.x - r1, y: top.y - 4 * s - r1, width: r1 * 2, height: r1 * 2)), with: .color(EyeStyle.Game.Sprout.daisyCenter))
            ctx.fill(Path(ellipseIn: CGRect(x: top.x - r2, y: top.y - 7 * s - r2, width: r2 * 2, height: r2 * 2)), with: .color(EyeStyle.Game.Sprout.daisyPetal))
        } else if growthStage >= 4 && !wilted {
            let fc = CGPoint(x: top.x, y: top.y - 8 * s)
            let pd = 11.5 * s, pr = 5.8 * s
            for i in 0..<10 {
                let a = CGFloat(i) * (2 * .pi / 10)
                let px = fc.x + cos(a) * pd, py = fc.y + sin(a) * pd
                ctx.fill(Path(ellipseIn: CGRect(x: px - pr, y: py - pr, width: pr * 2, height: pr * 2)), with: .color(EyeStyle.Game.Sprout.daisyPetal))
            }
            let cr = 8.2 * s
            ctx.fill(Path(ellipseIn: CGRect(x: fc.x - cr, y: fc.y - cr, width: cr * 2, height: cr * 2)), with: .color(EyeStyle.Game.Sprout.daisyCenter))
        }
    }

    private static func drawLeaf(_ ctx: GraphicsContext, anchor: CGPoint, dx: CGFloat, dy: CGFloat, width: CGFloat, color: Color) {
        let tip = CGPoint(x: anchor.x + dx, y: anchor.y + dy)
        var p = Path()
        p.move(to: anchor)
        p.addQuadCurve(to: tip, control: CGPoint(x: anchor.x + dx * 0.5, y: anchor.y + dy - width))
        p.addQuadCurve(to: anchor, control: CGPoint(x: anchor.x + dx * 0.5, y: anchor.y + width * 0.6))
        p.closeSubpath()
        ctx.fill(p, with: .color(color))
    }

    static func drawDropletMascot(_ ctx: GraphicsContext, center: CGPoint, radius: CGFloat) {
        drawWaterDrop(ctx, center: center, radius: radius)
        ctx.fill(Path(ellipseIn: CGRect(x: center.x - radius * 0.54, y: center.y - radius * 0.08, width: radius * 1.08, height: radius * 0.78)),
                 with: .color(EyeStyle.Game.Sprout.cloudWhite.opacity(0.92)))
        let er = radius * 0.11
        ctx.fill(Path(ellipseIn: CGRect(x: center.x - radius * 0.22 - er, y: center.y + radius * 0.24 - er, width: er * 2, height: er * 2)),
                 with: .color(EyeStyle.Game.Sprout.arrowDark))
        ctx.fill(Path(ellipseIn: CGRect(x: center.x + radius * 0.22 - er, y: center.y + radius * 0.24 - er, width: er * 2, height: er * 2)),
                 with: .color(EyeStyle.Game.Sprout.arrowDark))
        var smile = Path()
        smile.move(to: CGPoint(x: center.x - radius * 0.16, y: center.y + radius * 0.42))
        smile.addQuadCurve(to: CGPoint(x: center.x + radius * 0.16, y: center.y + radius * 0.42),
                           control: CGPoint(x: center.x, y: center.y + radius * 0.58))
        ctx.stroke(smile, with: .color(EyeStyle.Game.Sprout.arrowDark), style: StrokeStyle(lineWidth: radius * 0.08, lineCap: .round))
    }

    static func drawWaterDrop(_ ctx: GraphicsContext, center: CGPoint, radius: CGFloat) {
        var drop = Path()
        drop.move(to: CGPoint(x: center.x, y: center.y - radius * 1.25))
        drop.addCurve(to: CGPoint(x: center.x + radius * 0.90, y: center.y + radius * 0.56),
                      control1: CGPoint(x: center.x + radius * 0.48, y: center.y - radius * 0.42),
                      control2: CGPoint(x: center.x + radius * 0.95, y: center.y + radius * 0.18))
        drop.addCurve(to: CGPoint(x: center.x - radius * 0.90, y: center.y + radius * 0.56),
                      control1: CGPoint(x: center.x + radius * 0.85, y: center.y + radius * 1.05),
                      control2: CGPoint(x: center.x - radius * 0.85, y: center.y + radius * 1.05))
        drop.addCurve(to: CGPoint(x: center.x, y: center.y - radius * 1.25),
                      control1: CGPoint(x: center.x - radius * 0.95, y: center.y + radius * 0.18),
                      control2: CGPoint(x: center.x - radius * 0.48, y: center.y - radius * 0.42))
        drop.closeSubpath()
        ctx.fill(drop, with: .linearGradient(Gradient(colors: [EyeStyle.Game.Sprout.dropLight, EyeStyle.Game.Sprout.dropBlue, EyeStyle.Game.Sprout.dropDeep]),
                                             startPoint: CGPoint(x: center.x, y: center.y - radius * 1.2),
                                             endPoint: CGPoint(x: center.x, y: center.y + radius)))
        let hr = radius * 0.18
        ctx.fill(Path(ellipseIn: CGRect(x: center.x - radius * 0.28 - hr, y: center.y + radius * 0.24 - hr, width: hr * 2, height: hr * 2)),
                 with: .color(EyeStyle.Game.Sprout.cloudWhite.opacity(0.78)))
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

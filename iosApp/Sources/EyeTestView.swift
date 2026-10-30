import SwiftUI
import UIKit

struct EyeTestView: View {
    @StateObject private var runtime: EyeRuntime
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var pixelsPerMm = UserDefaults.standard.float(forKey: "eye.pixelsPerMm")
    @State private var brightness: CGFloat?
    @State private var previousIdleDisabled = false
    @State private var settingsOpen = false
    @State private var suspended = false
    @State private var confirmingExit = false
    init(kind: String) { _runtime = StateObject(wrappedValue: EyeRuntime(kind: kind)) }
    var body: some View {
        VStack(spacing: 0) {
            TestTopBar(title: runtime.string("title").isEmpty ? "眼健康自测" : runtime.string("title"),
                onBack: { if needsExitConfirm { confirmingExit = true } else { runtime.close(); dismiss() } },
                onSettings: { settingsOpen = true })
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
            // 斜视：距离条放到底部，注视目标紧贴顶栏下方（与安卓一致）
            if showsDistance && distanceAtBottom {
                TestDistanceBanner(runtime: runtime).padding(.horizontal, 16).padding(.bottom, 8)
                    .transition(.opacity.combined(with: .move(edge: .bottom)))
            }
        }.foregroundStyle(EyeStyle.text).tint(EyeStyle.primary)
            .overlay {
                if confirmingExit {
                    TestExitConfirm(stay: { confirmingExit = false }, exit: { confirmingExit = false; runtime.close(); dismiss() })
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
                NavigationStack { SystemSettingsPage(voice: runtime.voice, feedback: runtime.feedback, inTest: true) }.tint(EyeStyle.primary)
            }
            .onChange(of: settingsOpen) { runtime.setSettingsPresented($0) }
            .onAppear {
                brightness = UIScreen.main.brightness; UIScreen.main.brightness = 1
                previousIdleDisabled = UIApplication.shared.isIdleTimerDisabled
                UIApplication.shared.isIdleTimerDisabled = true
                if displayReady { runtime.prepare(pixelsPerMm: pixelsPerMm > 0 ? pixelsPerMm : Self.nominalPixelsPerMm) }
            }
            .onDisappear {
                runtime.close(); UIApplication.shared.isIdleTimerDisabled = previousIdleDisabled
                if let value = brightness { UIScreen.main.brightness = value }
            }
            .onChange(of: scenePhase) { phase in
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
    private var pageContent: some View {
        VStack(spacing: EyeStyle.spacing) {
            if !displayReady {
                ScreenCalibrationView { value in
                    pixelsPerMm = value; UserDefaults.standard.set(value, forKey: "eye.pixelsPerMm")
                    runtime.prepare(pixelsPerMm: value)
                }
            } else {
                // 注视类任务的提示位放在舞台下方：舞台紧贴顶部，提示出现与否不挪动目标
                if !isGazeTask { TestNotice(text: runtime.error ?? runtime.cameraNote ?? warningText) }
                if isProfile { SubjectPickerView(runtime: runtime) }
                else if runtime.kind == "COLOR_VISION" { ColorVisionContent(runtime: runtime) }
                else if isRunning { VStack(spacing: EyeStyle.spacing) { testContent }.modifier(TestStagePanel()) }
                else { testContent }
                if isGazeTask { TestNoticeSlot(text: runtime.error ?? runtime.cameraNote ?? warningText) }
            }
        }.animation(.easeInOut(duration: 0.28), value: runtime.error ?? runtime.cameraNote ?? warningText)
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
        pixelsPerMm > 0 && !runtime.bool("guidePending") && ["GAZE_CALIBRATION","FIXATION","FAR_FIXATION","COVER","PURSUIT"].contains(phase)
    }
    private var showsDistance: Bool { pixelsPerMm > 0 && runtime.number("targetDistanceCm") > 0 && !runtime.bool("guidePending") }
    private var distanceAtBottom: Bool { runtime.kind == "STRABISMUS" }
    /// 测试进行中（选人页、准备页、结果页以外）点返回先确认；规则与安卓各测试页一致。
    private var needsExitConfirm: Bool {
        !phase.isEmpty && !["Profile", "PROFILE", "Setup", "ENVIRONMENT", "Result", "RESULT"].contains(phase)
    }
    private var isRunning: Bool {
        pixelsPerMm > 0 && !runtime.bool("guidePending") && ["Practice","Natural","Open","Countdown","Rest",
            "NEAR_VISION","PUSH_UP","DISTANCE_CURVE","FLIPPER","GAZE_CALIBRATION","FIXATION","FAR_FIXATION","COVER","PURSUIT"].contains(phase)
    }
    @ViewBuilder private var testContent: some View {
        if phase == "Setup" || phase == "ENVIRONMENT" {
            // 与安卓准备页一致：干眼先提示最佳矫正，再示范姿势
            if runtime.kind == "DRY_EYE" {
                Text("建议佩戴最佳矫正视力眼镜进行测试").font(EyeStyle.body).frame(maxWidth: .infinity, alignment: .leading)
                    .padding(14).background(Cartoon.accentSoft, in: RoundedRectangle(cornerRadius: EyeStyle.radiusMedium))
            }
            TestGuideView(motion: .posture, title: runtime.kind == "DRY_EYE" ? "调整姿势 · 约 40 cm" : "调整姿势",
                cue: runtime.kind == "DRY_EYE" ? "正对屏幕，手机距离眼睛约 40 厘米，光线均匀"
                    : runtime.kind == "STRABISMUS" ? "正对屏幕，约 33 厘米；请协助者在旁准备" : "正对屏幕，约 40 厘米")
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
                    .padding(14).background(Cartoon.accentSoft, in: RoundedRectangle(cornerRadius: EyeStyle.radiusMedium))
            }
            TestGuideView(motion: .fva, title: "正式测试 · \(seconds) 秒 + 20 秒",
                cue: "全程看 E 选方向。前 \(seconds) 秒自然眨眼，随后不停顿，直接尽量保持睁眼 20 秒", readyTitle: "开始正式测试") {
                runtime.act("formal")
            }
        } else if runtime.bool("guidePending") && runtime.bool("glassesCheck") {
            GlassesCheckView { runtime.act("glassesRemoved") }
        } else if runtime.bool("guidePending") {
            TestGuideView(motion: .forStep(phase), title: runtime.string("stepTitle"),
                cue: runtime.string("guideText").isEmpty ? runtime.string("instruction") : runtime.string("guideText")) {
                runtime.act("guide")
            }
        } else if phase == "Result" || phase == "RESULT" {
            // 结论、关键指标、判断依据、本次数据与导出：与安卓共用 ResultReport 内容
            ResultReportView(runtime: runtime)
        } else if phase == "FAR_FIXATION" {
            let meters = Int(runtime.number("farTargetM"))
            let waiting = runtime.bool("farWaiting")
            FarFixationPanel(seconds: Int(runtime.number("secondsLeft")), meters: meters, waiting: waiting)
            StageCaption(text: waiting ? "看远处 · 准备" : "剩余 \(Int(runtime.number("secondsLeft"))) 秒")
            if waiting {
                Button("已在看 \(meters) 米外的目标，开始") { EyeHaptics.tap(); runtime.act("farStart") }.buttonStyle(EyeButtonStyle())
            }
        } else if phase == "GAZE_CALIBRATION" || phase == "FIXATION" || phase == "COVER" || phase == "PURSUIT" {
            GazeTargetView(runtime: runtime, pixelsPerMm: pixelsPerMm)
            StageCaption(text: phase == "GAZE_CALIBRATION"
                ? "注视校准 · \(Int(runtime.number("gazeCalibrationIndex")) + 1)/\(Int(runtime.number("calibrationCount")))"
                : phase == "COVER" ? "交替遮盖 · \(Int(runtime.number("coverDone")))/9" : "剩余 \(Int(runtime.number("secondsLeft"))) 秒")
            if runtime.bool("gazeCalibrationFailed") { Button("重试注视校准") { runtime.act("calibrate") }.buttonStyle(EyeButtonStyle()) }
            if phase == "COVER" { CoverPanel(runtime: runtime) }
        } else if phase == "Countdown" {
            CountdownRing(seconds: Int(runtime.number("secondsLeft")))
        } else if phase == "Rest" {
            Text("闭眼休息").font(EyeStyle.title)
            TimerRing(progress: 1 - runtime.number("progress")) {
                Text("\(Int(runtime.number("secondsLeft")))").font(.system(size: 56, weight: .semibold, design: .rounded))
                    .monospacedDigit().foregroundStyle(EyeStyle.primary)
            }.frame(width: 184, height: 184).animation(.linear(duration: 0.25), value: runtime.number("progress"))
        } else {
            if phase == "Natural" || phase == "Open" || phase == "Practice" {
                HStack {
                    Text(phase == "Natural" ? "自然眨眼" : phase == "Open" ? "持续睁眼" : "练习")
                    Spacer(); Text("\(Int(runtime.number("secondsLeft"))) 秒").monospacedDigit()
                }
            } else if phase == "NEAR_VISION" {
                Text(["RIGHT": "右眼", "LEFT": "左眼", "BOTH": "双眼"][runtime.string("eye")] ?? "")
            } else if phase == "FLIPPER" || phase == "DISTANCE_CURVE" {
                HStack {
                    Text("\(Int(runtime.number("targetDistanceCm"))) cm")
                    Spacer()
                    if runtime.number("secondsLeft") > 0 { Text("\(Int(runtime.number("secondsLeft"))) 秒").monospacedDigit() }
                }
            }
            if runtime.bool("nearAwaitingEyeReady") {
                Text(runtime.string("instruction"))
                Button("遮盖准备好了") { runtime.act("eyeReady") }.buttonStyle(EyeButtonStyle())
            } else if phase == "PUSH_UP" && runtime.string("pushStage") == "READY" {
                Text("先移到 45 cm 以外")
                Button("开始移近") { runtime.act("pushStart") }.buttonStyle(EyeButtonStyle())
            } else {
                if phase == "PUSH_UP" {
                    Text("缓慢移近")
                    TumblingEView(runtime: runtime).stageHeight(max(180, ringDiameter(CGFloat(runtime.number("optotypePx")) / UIScreen.main.scale) + 24))
                    Button("开始持续模糊了") { runtime.act("blur") }.buttonStyle(EyeButtonStyle())
                } else {
                    OptotypeAnswerPad(runtime: runtime)
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
                .background(EyeStyle.panel, in: RoundedRectangle(cornerRadius: EyeStyle.radiusLarge))
                .overlay { RoundedRectangle(cornerRadius: EyeStyle.radiusLarge).stroke(EyeStyle.borderSubtle, lineWidth: 1) }
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
    /// 基准箭头朝右，按视标方向编码旋转：0=右 1=下 2=左 3=上。
    private func arrow(_ label: String, _ direction: Int) -> some View {
        Button { runtime.answer(direction) } label: {
            ArrowGlyph().stroke(EyeStyle.primary, style: StrokeStyle(lineWidth: 24 * 0.13, lineCap: .round, lineJoin: .round))
                .frame(width: 24, height: 24).rotationEffect(.degrees(Double(direction) * 90))
        }.buttonStyle(DirectionKeyStyle()).accessibilityLabel(label)
    }
}

private struct TumblingEView: View {
    @ObservedObject var runtime: EyeRuntime
    var body: some View {
        Canvas { context, size in
            guard runtime.bool("trialVisible") else { return }
            let edge = CGFloat(runtime.number("optotypePx")) / UIScreen.main.scale
            guard edge > 0 else { return }
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
            if done > 0, runtime.number("coverHoldMs") > 0 {
                HoldProgress(key: done, holdMs: runtime.number("coverHoldMs"), label: "保持：\(runtime.string("coverCurrent"))")
            }
            Text(next.isEmpty ? "遮盖完成" : "下一步：\(next)").font(EyeStyle.title)
            CoverSequenceStrip(done: done)
            Button(next.isEmpty ? "已完成" : "执行「\(next)」时点按") { runtime.act("cover") }
                .buttonStyle(EyeButtonStyle()).disabled(next.isEmpty)
        }
    }
}

/// 当前动作需保持的时长（原文 2 秒 / 交替 1 秒）：进度条走满后变为「可以进行下一步」。
private struct HoldProgress: View {
    let key: Int
    let holdMs: Double
    let label: String
    @State private var progress = 0.0
    @State private var finished = false
    @State private var generation = 0
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(finished ? "可以进行下一步" : label).font(EyeStyle.body)
                .foregroundStyle(finished ? EyeStyle.success : EyeStyle.secondary)
            GeometryReader { geometry in
                ZStack(alignment: .leading) {
                    Capsule().fill(EyeStyle.surfaceVariant)
                    Capsule().fill(EyeStyle.primary).frame(width: geometry.size.width * progress)
                }
            }.frame(height: 6)
        }
        .onAppear(perform: restart)
        .onChange(of: key) { _ in restart() }
    }
    private func restart() {
        var snap = Transaction(); snap.disablesAnimations = true
        withTransaction(snap) { progress = 0; finished = false }
        generation += 1
        let token = generation
        DispatchQueue.main.async {
            withAnimation(.linear(duration: holdMs / 1000)) { progress = 1 }
            // 动画数值不会触发文字切换，到点后单独标记完成；期间换到下一动作则作废。
            DispatchQueue.main.asyncAfter(deadline: .now() + holdMs / 1000) { if token == generation { finished = true } }
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
            Canvas { ctx, size in
                Cartoon.mascot(ctx, center: CGPoint(x: size.width / 2, y: size.height * 0.58), size: min(size.width, size.height) * 0.8,
                    gaze: CGPoint(x: 0, y: -0.6))
            }.frame(width: 64, height: 64)
            if waiting {
                Text("看着 \(meters) 米外的目标").font(EyeStyle.title).foregroundStyle(.white).multilineTextAlignment(.center)
                Text("看好以后，协助者点下面的按钮开始计时").font(EyeStyle.bodySecondary).foregroundStyle(.white.opacity(0.72))
                    .multilineTextAlignment(.center)
            } else {
                Text("\(seconds)").font(EyeStyle.headline).monospacedDigit().foregroundStyle(.white)
                Text("眼睛越过手机，看着远处的目标").font(EyeStyle.subtitle).foregroundStyle(.white).multilineTextAlignment(.center)
                Text("协助者：确认一直看着远处，头和手机不动").font(EyeStyle.bodySecondary).foregroundStyle(.white.opacity(0.72))
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

/// 测试中点返回：屏幕中间弹出确认框，小瞳在上方冒气泡挽留。「继续测试」关闭，「确认退出」才离开。与安卓 TestExitDialog 同一文案。
private struct TestExitConfirm: View {
    let stay: () -> Void
    let exit: () -> Void
    var body: some View {
        ZStack {
            EyeStyle.ink.opacity(0.55).ignoresSafeArea().contentShape(Rectangle()).onTapGesture { stay() }
            VStack(spacing: 0) {
                MascotBubble(line: MascotLine(text: "很快就要测完了哦，再坚持一下～"))
                MascotStage(mood: .concerned, interactive: false).frame(width: 96, height: 96)
                VStack(spacing: 12) {
                    Text("确定要退出测试吗？").font(EyeStyle.title).foregroundStyle(EyeStyle.text)
                    Text("退出后这次测试不会保存，下次要从头开始。").font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
                        .multilineTextAlignment(.center)
                    Button("继续测试") { EyeHaptics.tap(); stay() }.buttonStyle(EyeButtonStyle())
                    Button("确认退出") { EyeHaptics.tap(); exit() }.buttonStyle(EyeButtonStyle(role: .secondary))
                }
                .padding(24).frame(maxWidth: .infinity)
                .background(EyeStyle.panel, in: RoundedRectangle(cornerRadius: 22))
            }
            .padding(24)
        }
        .accessibilityAddTraits(.isModal)
    }
}

private struct GazeTargetView: View {
    @ObservedObject var runtime: EyeRuntime
    let pixelsPerMm: Float
    var body: some View {
        GeometryReader { geometry in
            let size = geometry.size
            let point = target(in: size)
            let phase = runtime.string("phase")
            ZStack(alignment: .topLeading) {
                EyeStyle.panel
                if phase == "GAZE_CALIBRATION" {
                    // 补测轮重看同一点时编号变化，外圈动效重播。
                    CalibrationDot(index: Int(runtime.number("gazeCalibrationIndex") + runtime.number("gazeCalibrationRound") * 100))
                        // 暂停时圆点变淡：位置不变，提示受试者校准在等待
                        .opacity(runtime.bool("calibrationPaused") ? 0.35 : 1).position(point)
                } else {
                    let width = phase == "PURSUIT" ? CGFloat(5 * pixelsPerMm / 0.65) / UIScreen.main.scale : CGFloat(runtime.number("targetDiameterMm") * Double(pixelsPerMm)) / UIScreen.main.scale
                    // 与安卓一致：头宽 = 目标半径 / 0.65，头部中心即上报的目标位置。
                    MascotView(scale: 1 / 1.3, centerY: 0.5).frame(width: width, height: width)
                        .opacity(phase == "FIXATION" && runtime.number("targetX") <= 0 ? 0.3 : 1).position(point)
                }
            }.clipShape(RoundedRectangle(cornerRadius: EyeStyle.radius))
                .onAppear { report(geometry, point) }
                .onChange(of: runtime.stateKeyForTarget) { _ in report(geometry, point) }
                .onChange(of: geometry.frame(in: .global).origin) { _ in report(geometry, point) }
        }.stageHeight(["PURSUIT", "GAZE_CALIBRATION"].contains(runtime.string("phase")) ? 360 : 240)
    }
    private func target(in size: CGSize) -> CGPoint {
        if runtime.string("phase") == "GAZE_CALIBRATION" {
            // 点位由共用核心给出，两端一致。
            return CGPoint(x: size.width * runtime.number("calibrationX"), y: size.height * runtime.number("calibrationY"))
        }
        if runtime.string("phase") == "PURSUIT" {
            let ppm = Double(pixelsPerMm) / Double(UIScreen.main.scale)
            let distance = max(runtime.number("distanceCm"), 1) * 10
            let radius = 5 * ppm
            let xMax = atan(max(0,(size.width / 2 - radius)) / (distance * ppm)) * 180 / .pi
            let yMax = atan(max(0,(size.height / 2 - radius)) / (distance * ppm)) * 180 / .pi
            let scale = min(1,min(xMax / 10,yMax / 10))
            return CGPoint(x: size.width / 2 + tan(runtime.number("targetX") * scale * .pi / 180) * distance * ppm,
                y: size.height / 2 - tan(runtime.number("targetY") * scale * .pi / 180) * distance * ppm)
        }
        // 注视、遮盖：目标放在画布顶部、尽量靠近顶部的映光点（专家建议），头顶嫩芽也留在画布内
        let width = CGFloat(runtime.number("targetDiameterMm") * Double(pixelsPerMm)) / UIScreen.main.scale
        return CGPoint(x: size.width / 2, y: width * 0.6 + 12)
    }
    private func report(_ geometry: GeometryProxy,_ point: CGPoint) {
        let frame = geometry.frame(in: .global), scale = UIScreen.main.scale
        runtime.controller.reportTarget(xPx: Float((frame.minX + point.x) * scale), yPx: Float((frame.minY + point.y) * scale))
        if runtime.string("phase") == "PURSUIT" {
            let ppm = Double(pixelsPerMm) / Double(scale), distance = max(runtime.number("distanceCm"),1) * 10
            runtime.controller.reportPursuitCapacity(xDeg: Float(atan(max(0,geometry.size.width / 2 - 5 * ppm) / (distance * ppm)) * 180 / .pi),
                yDeg: Float(atan(max(0,geometry.size.height / 2 - 5 * ppm) / (distance * ppm)) * 180 / .pi))
        }
    }
}

private extension EyeRuntime {
    var stateKeyForTarget: String {
        "\(string("phase"))-\(number("targetX"))-\(number("targetY"))-\(number("gazeCalibrationIndex"))-\(number("gazeCalibrationRound"))"
    }
}

/// 换点时外圈从大收拢，帮助视线找到新位置；圆点中心即上报位置，立即到位。
private struct CalibrationDot: View {
    let index: Int
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var halo: CGFloat = 1
    var body: some View {
        ZStack {
            Circle().fill(EyeStyle.primary.opacity(0.18)).frame(width: 32, height: 32).scaleEffect(halo)
            Circle().fill(EyeStyle.primary).frame(width: 16, height: 16)
            Circle().fill(EyeStyle.panel).frame(width: 5, height: 5)
        }.frame(width: 80, height: 80)
            .onAppear { pop() }
            .onChange(of: index) { _ in pop() }
    }
    private func pop() {
        guard !reduceMotion else { halo = 1; return }
        var snap = Transaction(); snap.disablesAnimations = true
        withTransaction(snap) { halo = 2.4 }
        DispatchQueue.main.async { withAnimation(.spring(response: 0.45, dampingFraction: 0.65)) { halo = 1 } }
    }
}

/// 老视标准模式开始前的摘镜提醒：小瞳 + 一句话 + 确认按钮，语音同步播报。与安卓 GlassesCheckContent 一致。
private struct GlassesCheckView: View {
    let confirm: () -> Void
    var body: some View {
        VStack(spacing: 16) {
            MascotView(scale: 0.8, centerY: 0.58).frame(width: 112, height: 112)
            Text("先摘下老花镜").font(EyeStyle.title)
            Text("这项测试要看眼睛本身的调节能力。请摘下老花镜或渐进镜；看远用的近视眼镜可以继续戴。")
                .font(EyeStyle.body).foregroundStyle(EyeStyle.secondary).multilineTextAlignment(.center)
            Button("已摘下，继续", action: confirm).buttonStyle(EyeButtonStyle())
        }.padding(20).frame(maxWidth: .infinity)
            .background(EyeStyle.panel, in: RoundedRectangle(cornerRadius: EyeStyle.radius))
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
                    .font(EyeStyle.subtitle).foregroundStyle(ready ? EyeStyle.success : EyeStyle.warning)
                Button("继续测试") { EyeHaptics.tap(); resume() }.buttonStyle(EyeButtonStyle()).disabled(!ready)
                Button("结束测试") { EyeHaptics.tap(); stop() }.buttonStyle(EyeButtonStyle(role: .secondary))
            }
            .padding(24).frame(maxWidth: .infinity, alignment: .leading)
            .background(EyeStyle.panel, in: RoundedRectangle(cornerRadius: 22))
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
            HStack {
                Text("开始前").font(EyeStyle.title).foregroundStyle(EyeStyle.text)
                Spacer()
                MascotStage(mood: .happy).frame(width: 96, height: 96)
            }
            ForEach(Array(["关掉手机的护眼模式、夜间模式（会改变屏幕颜色）", "在明亮的室内，屏幕亮度已自动调到最高",
                           "手机离眼睛约 40 cm，平时戴眼镜就戴着"].enumerated()), id: \.offset) { i, text in
                Text("\(i + 1). \(text)").font(EyeStyle.body).foregroundStyle(EyeStyle.text)
            }
            Text("先看一张示例图，再看 8 张检测图，选出看到的数字；看不出就选「看不出」，不要猜。")
                .font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
            Button("准备好了") { EyeHaptics.tap(); runtime.act("colorStart") }.buttonStyle(EyeButtonStyle())
        }
        .padding(16).frame(maxWidth: .infinity, alignment: .leading)
        .background(EyeStyle.panel, in: RoundedRectangle(cornerRadius: 22))
    }

    private var plate: some View {
        let plateId = runtime.string("plateId")
        let options = runtime.state["options"] as? [String] ?? []
        let demo = runtime.bool("demo")
        return VStack(spacing: 16) {
            Text(demo ? "示例图" : "第 \(Int(runtime.number("plateNumber"))) / \(Int(runtime.number("plateCount"))) 张")
                .font(EyeStyle.subtitle).foregroundStyle(EyeStyle.primary)
            ColorPlateCanvas(dots: runtime.colorPlateDots(plateId))
                .aspectRatio(1, contentMode: .fit).frame(maxWidth: 360)
                .id(plateId)
                .onAppear { DispatchQueue.main.async { runtime.colorPlateShown() } }
            Text("图里是什么数字？").font(EyeStyle.title).foregroundStyle(EyeStyle.text)
            HStack(spacing: 10) {
                ForEach(options, id: \.self) { option in
                    Button(option) { EyeHaptics.tap(); runtime.colorAnswer(option) }
                        .buttonStyle(EyeButtonStyle(role: option == "看不出" ? .secondary : .primary))
                }
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
        .background(EyeStyle.panel, in: RoundedRectangle(cornerRadius: 22))
        .accessibilityLabel("色觉检测图")
    }
}

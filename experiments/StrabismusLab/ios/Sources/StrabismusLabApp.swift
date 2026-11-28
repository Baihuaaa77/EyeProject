import AVKit
import EyeScreening
import SwiftUI
import UIKit

@main
struct StrabismusLabApp: App {
    @StateObject private var store = StrabismusLabStore()

    var body: some Scene {
        WindowGroup {
            LabRootView()
                .environmentObject(store)
                .preferredColorScheme(.dark)
        }
    }
}

struct LabRootView: View {
    @EnvironmentObject var store: StrabismusLabStore

    var body: some View {
        switch store.destination {
        case .console:
            LabConsoleView()
        case .capture:
            LabCaptureStageView()
        case .dashboard:
            if let report = store.activeReport {
                LabDashboardView(report: report)
            } else {
                LabConsoleView()
            }
        case .history:
            LabHistoryView()
        }
    }
}

/// 控制台：实时 0.80R 光学取景仪 + 受试者标签 + 光码/3D 离轴参数 + 诊断视频录制开关。
struct LabConsoleView: View {
    @EnvironmentObject var store: StrabismusLabStore

    private let schemes: [LabCodingScheme] = [.mSequence, .manchester, .sparsePulse, .steady, .dark]

    var body: some View {
        ZStack(alignment: .bottom) {
            LabPalette.canvas.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    // 顶栏
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("StrabismusLab").font(LabFont.title).foregroundColor(LabPalette.inkPrimary)
                            Text("3D CODED CORNEAL LAB · v0.9.5").font(LabFont.microMono).foregroundColor(LabPalette.accent)
                        }
                        Spacer()
                        Button(action: store.openHistory) {
                            HStack(spacing: 6) {
                                Image(systemName: "list.bullet")
                                Text("记录 \(store.history.count)").font(LabFont.caption)
                            }
                            .foregroundColor(LabPalette.inkPrimary)
                            .padding(.horizontal, 12).padding(.vertical, 8)
                            .overlay(Capsule().stroke(LabPalette.hairlineStrong, lineWidth: 1))
                        }
                    }

                    // 光学取景仪
                    VStack(spacing: 10) {
                        HStack {
                            Circle()
                                .fill(store.live.faceDetected ? LabPalette.verified : LabPalette.caution)
                                .frame(width: 8, height: 8)
                            Text("光学取景仪 · \(Int(store.config.searchRadiusFraction * 100))%R")
                                .font(LabFont.section).foregroundColor(LabPalette.inkPrimary)
                            Spacer()
                            Toggle("标注", isOn: $store.showPreviewOverlay)
                                .labelsHidden()
                                .tint(LabPalette.accent)
                        }

                        ZStack {
                            if store.cameraAuthorized {
                                LabCameraPreviewView(session: store.camera.captureSession)
                                if store.showPreviewOverlay {
                                    LabViewfinderOverlay(live: store.live)
                                }
                                VStack {
                                    HStack {
                                        hudPill(
                                            store.live.distanceCm > 0 ? String(format: "%.1f cm", store.live.distanceCm) : "-- cm",
                                            store.live.distanceOk ? LabPalette.verified : LabPalette.caution
                                        )
                                        Spacer()
                                        hudPill(
                                            String(format: "%.0f fps · L%d/R%d", store.live.fps, store.live.leftCandidates.count, store.live.rightCandidates.count),
                                            LabPalette.accent
                                        )
                                    }
                                    Spacer()
                                }
                                .padding(8)
                            } else {
                                VStack(spacing: 10) {
                                    Text("需要前置相机权限进行 4K 光学采集").font(LabFont.body).foregroundColor(LabPalette.inkSecondary)
                                    Button("授权前置相机", action: store.checkPermission)
                                        .padding(.horizontal, 16).padding(.vertical, 10)
                                        .background(LabPalette.accent)
                                        .foregroundColor(LabPalette.onAccent)
                                        .clipShape(RoundedRectangle(cornerRadius: 10))
                                }
                            }
                        }
                        .aspectRatio(4.0 / 3.0, contentMode: .fit)
                        .background(Color.black)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(LabPalette.hairline, lineWidth: 1))

                        HStack(spacing: 8) {
                            miniMetric("虹膜 L/R", "\(Int(store.live.leftIrisDiameterPx))/\(Int(store.live.rightIrisDiameterPx)) px")
                            miniMetric("YAW", store.live.yawDeg.map { String(format: "%+.0f°", $0.floatValue) } ?? "—")
                            miniMetric("PITCH", store.live.pitchDeg.map { String(format: "%+.0f°", $0.floatValue) } ?? "—")
                            miniMetric("对比度", "\(Int(store.live.leftVisibilityContrast?.floatValue ?? 0))/\(Int(store.live.rightVisibilityContrast?.floatValue ?? 0))")
                        }
                    }
                    .labCard()

                    // 受试者与录制选项
                    VStack(alignment: .leading, spacing: 10) {
                        HStack {
                            Text("受试者与条件标签").font(LabFont.section).foregroundColor(LabPalette.inkPrimary)
                            Spacer()
                            Toggle("录制叠加诊断视频", isOn: $store.recordVideo)
                                .font(LabFont.caption)
                                .foregroundColor(LabPalette.inkSecondary)
                                .tint(LabPalette.verified)
                                .fixedSize()
                        }
                        HStack(spacing: 10) {
                            TextField("匿名编号", text: Binding(
                                get: { store.config.subjectId },
                                set: { v in store.updateConfig { copyConfig($0, subjectId: v) } }
                            ))
                            .textFieldStyle(.plain)
                            .padding(10)
                            .background(LabPalette.surfaceElevated)
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                            .overlay(RoundedRectangle(cornerRadius: 10).stroke(LabPalette.hairline, lineWidth: 1))

                            TextField("PACT 真值备注", text: Binding(
                                get: { store.config.clinicalNote },
                                set: { v in store.updateConfig { copyConfig($0, clinicalNote: v) } }
                            ))
                            .textFieldStyle(.plain)
                            .padding(10)
                            .background(LabPalette.surfaceElevated)
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                            .overlay(RoundedRectangle(cornerRadius: 10).stroke(LabPalette.hairline, lineWidth: 1))
                        }
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                ForEach(["裸眼", "框架眼镜", "隐形眼镜"], id: \.self) { g in
                                    pillButton(g, active: store.config.glassesState == g) {
                                        store.feedback.toggle()
                                        store.updateConfig { copyConfig($0, glassesState: g) }
                                    }
                                }
                            }
                        }

                        Divider().background(LabPalette.hairline)
                        LabFeedbackControlBar(feedback: store.feedback)
                    }
                    .labCard()

                    // 光码、搜索半径与 3D 离轴光源参数
                    VStack(alignment: .leading, spacing: 10) {
                        Text("光码协议与 3D 光学参数").font(LabFont.section).foregroundColor(LabPalette.inkPrimary)
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                ForEach(schemes, id: \.name) { s in
                                    pillButton(s.label, active: store.config.codingScheme == s) {
                                        // 曼彻斯特双相每码元含前后两个半周期：默认推荐 500ms（半周期 250ms），仍可手动切回 250ms 做消融对照
                                        let nextChip: Int64 = (s == .manchester && store.config.chipDurationMs < 500) ? 500 : store.config.chipDurationMs
                                        store.updateConfig { copyConfig($0, codingScheme: s, chipDurationMs: nextChip) }
                                    }
                                }
                            }
                        }
                        HStack {
                            Text("虹膜搜索半径").font(LabFont.caption).foregroundColor(LabPalette.inkSecondary)
                            Spacer()
                            Text(String(format: "%.2f R (%.0f%% 面积)", store.config.searchRadiusFraction, store.config.searchRadiusFraction * store.config.searchRadiusFraction * 100))
                                .font(LabFont.microMono).foregroundColor(LabPalette.accent)
                        }
                        Slider(
                            value: Binding(
                                get: { Double(store.config.searchRadiusFraction) },
                                set: { v in store.updateConfig { copyConfig($0, searchRadiusFraction: Float((v * 20).rounded() / 20)) } }
                            ),
                            in: 0.60...0.90,
                            step: 0.05
                        )
                        .tint(LabPalette.accent)

                        HStack {
                            Text("白点-前摄垂直离轴 Δy").font(LabFont.caption).foregroundColor(LabPalette.inkSecondary)
                            Spacer()
                            Text(String(format: "%.0f mm", store.config.lightOffsetYMm))
                                .font(LabFont.microMono).foregroundColor(LabPalette.accent)
                        }
                        Slider(
                            value: Binding(
                                get: { store.config.lightOffsetYMm },
                                set: { v in store.updateConfig { copyConfig($0, lightOffsetYMm: v.rounded()) } }
                            ),
                            in: 8.0...40.0,
                            step: 1.0
                        )
                        .tint(LabPalette.accent)

                        HStack {
                            Text("码元时长").font(LabFont.caption).foregroundColor(LabPalette.inkSecondary)
                            Spacer()
                            ForEach([Int64(125), 250, 500], id: \.self) { ms in
                                pillButton("\(ms)ms", active: store.config.chipDurationMs == ms) {
                                    store.updateConfig { copyConfig($0, chipDurationMs: ms) }
                                }
                            }
                        }

                        HStack(spacing: 8) {
                            pillButton("看近", active: store.config.includeNear) {
                                store.updateConfig { copyConfig($0, includeNear: !$0.includeNear) }
                            }
                            pillButton("看远", active: store.config.includeFar) {
                                store.updateConfig { copyConfig($0, includeFar: !$0.includeFar) }
                            }
                            pillButton("遮盖", active: store.config.includeCover) {
                                store.updateConfig { copyConfig($0, includeCover: !$0.includeCover) }
                            }
                            pillButton("追踪", active: store.config.includePursuit) {
                                store.updateConfig { copyConfig($0, includePursuit: !$0.includePursuit) }
                            }
                        }
                    }
                    .labCard()
                }
                .padding(.horizontal, 16)
                .padding(.top, 12)
                .padding(.bottom, 96)
            }

            // 底部主 CTA
            VStack(spacing: 0) {
                Divider().background(LabPalette.hairline)
                Button(action: store.startCapture) {
                    HStack(spacing: 8) {
                        Image(systemName: store.recordVideo ? "record.circle.fill" : "play.fill")
                        Text("开始采集（\(store.config.activeStages.count) 阶段\(store.recordVideo ? " · 录视频" : "")）").font(LabFont.section)
                    }
                    .frame(maxWidth: .infinity, minHeight: 50)
                    .background(store.cameraAuthorized && !store.config.activeStages.isEmpty ? LabPalette.accent : LabPalette.surfaceElevated)
                    .foregroundColor(store.cameraAuthorized ? LabPalette.onAccent : LabPalette.inkTertiary)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                }
                .disabled(!store.cameraAuthorized || store.config.activeStages.isEmpty)
                .padding(.horizontal, 16).padding(.vertical, 12)
            }
            .background(LabPalette.canvas.opacity(0.95))
        }
    }
}

/// 纯黑 #000000 光学采集舞台（60Hz TimelineView 同步更新光码白点；遮盖阶段强制白点常亮消除暗码盲区）。
struct LabCaptureStageView: View {
    @EnvironmentObject var store: StrabismusLabStore

    var body: some View {
        TimelineView(.animation) { context in
            ZStack {
                Color.black.ignoresSafeArea()
                    .onAppear { store.onDisplayTick() }
                    .onChange(of: context.date) { _ in store.onDisplayTick() }

                // 顶部贴近前摄的纯白实心编码白点（外围细环引导注视，绝不在核心区画大红十字挖暗峰值！）
                if store.live.stage.usesOpticalStimulus && !store.live.stageWaitingConfirm {
                    let lum = store.live.stage.forcesSteadyStimulus ? 1.0 : Double(min(1, max(0, store.renderedIntensity)))
                    let dotPt = CGFloat(min(140, max(28, store.config.stimulusDiameterMm * 6.3)))
                    VStack {
                        ZStack {
                            if store.live.stage != .farFixation {
                                Circle()
                                    .stroke(LabPalette.accent.opacity(0.45), lineWidth: 1.5)
                                    .frame(width: dotPt + 10, height: dotPt + 10)
                            }
                            Circle()
                                .fill(Color(white: lum))
                                .frame(width: dotPt, height: dotPt)
                                .transaction { $0.animation = nil }
                            if lum > 0.5 && store.live.stage != .farFixation {
                                Circle().fill(LabPalette.accent.opacity(0.45)).frame(width: 2.5, height: 2.5)
                            }
                        }
                        .padding(.top, 4)
                        .animation(nil, value: lum)
                        Spacer()
                    }
                }

                // 追踪目标（仅确认开始后显示）
                if store.live.stage == .pursuit && !store.live.stageWaitingConfirm {
                    GeometryReader { geo in
                        let cx = geo.size.width * CGFloat(min(0.92, max(0.08, store.live.pursuitTargetX)))
                        let cy = geo.size.height * CGFloat(min(0.85, max(0.12, store.live.pursuitTargetY)))
                        ZStack {
                            Circle().fill(LabPalette.rightEye.opacity(0.25)).frame(width: 34, height: 34)
                            Circle().fill(LabPalette.rightEye).frame(width: 16, height: 16)
                            Circle().fill(LabPalette.inkPrimary).frame(width: 5, height: 5)
                        }
                        .position(x: cx, y: cy)
                    }
                }

                // 中央阶段说明确认页 / 遮盖实时指令卡片
                VStack(spacing: 14) {
                    if store.analyzing {
                        ProgressView().tint(LabPalette.accent)
                        Text("正在执行 v0.9.5 3D 光学反演与遮盖拐点回溯…").font(LabFont.body).foregroundColor(LabPalette.inkSecondary)
                    } else if store.live.stageWaitingConfirm {
                        stageBriefingCard
                            .padding(.horizontal, 20)
                    } else if store.live.stage == .coverTest {
                        VStack(spacing: 12) {
                            HStack(spacing: 8) {
                                Circle()
                                    .fill(store.live.coverHolding ? LabPalette.verified : LabPalette.accent)
                                    .frame(width: 9, height: 9)
                                Text(store.live.coverHolding ? "已识别，请保持" : "等待遮盖动作")
                                    .font(LabFont.caption)
                                    .foregroundColor(store.live.coverHolding ? LabPalette.verified : LabPalette.accent)
                            }
                            .padding(.horizontal, 12).padding(.vertical, 5)
                            .background((store.live.coverHolding ? LabPalette.verified : LabPalette.accent).opacity(0.15))
                            .clipShape(Capsule())

                            Text(store.live.coverInstruction)
                                .font(LabFont.title)
                                .multilineTextAlignment(.center)
                                .foregroundColor(store.live.coverHolding ? LabPalette.verified : LabPalette.inkPrimary)
                            Text("遮盖动作 \(store.live.coverStepIndex)/\(store.live.coverStepTotal) · 白点常亮防盲区")
                                .font(LabFont.microMono).foregroundColor(LabPalette.inkSecondary)
                            Button("手动确认本步遮盖", action: store.manualAdvanceCover)
                                .font(LabFont.caption)
                                .foregroundColor(LabPalette.inkSecondary)
                                .padding(.horizontal, 16).padding(.vertical, 10)
                                .overlay(Capsule().stroke(LabPalette.hairlineStrong, lineWidth: 1))
                        }
                        .padding(20)
                        .background(LabPalette.surface.opacity(0.92))
                        .clipShape(RoundedRectangle(cornerRadius: 18))
                        .overlay(
                            RoundedRectangle(cornerRadius: 18)
                                .stroke(store.live.coverHolding ? LabPalette.verified : LabPalette.hairlineStrong, lineWidth: store.live.coverHolding ? 2 : 1)
                        )
                        .padding(.horizontal, 24)
                    }
                }

                // 底部极简遥测控制条
                VStack {
                    Spacer()
                    VStack(spacing: 8) {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                HStack(spacing: 6) {
                                    if store.recordVideo {
                                        Circle().fill(LabPalette.danger).frame(width: 7, height: 7)
                                    }
                                    Text("\(store.live.stageIndex)/\(store.live.totalStages) \(store.live.stage.title)")
                                        .font(LabFont.section).foregroundColor(LabPalette.inkPrimary)
                                }
                                Text(store.live.warning ?? store.live.prompt)
                                    .font(LabFont.caption)
                                    .foregroundColor(store.live.warning != nil ? LabPalette.caution : LabPalette.inkSecondary)
                            }
                            Spacer()
                            if store.live.remainingSec > 0 {
                                Text("\(store.live.remainingSec)s").font(LabFont.metricHero).foregroundColor(LabPalette.accent)
                            }
                        }
                        GeometryReader { g in
                            ZStack(alignment: .leading) {
                                Capsule().fill(LabPalette.surfaceElevated)
                                Capsule().fill(LabPalette.accent).frame(width: g.size.width * CGFloat(min(1, max(0, store.live.stageProgress))))
                            }
                        }
                        .frame(height: 4)

                        HStack {
                            Text(String(format: "%.1f cm · %.0f fps · %d 帧", store.live.distanceCm, store.live.fps, store.live.capturedFrames))
                                .font(LabFont.microMono)
                                .foregroundColor(store.live.distanceOk ? LabPalette.verified : LabPalette.caution)
                            Spacer()
                            Button("跳过", action: store.skipStage).font(LabFont.caption).foregroundColor(LabPalette.inkSecondary)
                            Button("提前分析", action: store.finishEarlyAndAnalyze).font(LabFont.caption).foregroundColor(LabPalette.accent)
                            Button(action: store.cancelCapture) {
                                Image(systemName: "xmark").foregroundColor(LabPalette.inkSecondary)
                            }
                        }
                    }
                    .padding(14)
                    .background(LabPalette.surface.opacity(0.9))
                    .overlay(Rectangle().stroke(LabPalette.hairline, lineWidth: 1))
                }
            }
        }
    }

    private var stageSymbol: String {
        switch store.live.stage {
        case .nearFixation: return "dot.scope"
        case .farFixation: return "binoculars"
        case .coverTest: return "hand.raised"
        case .pursuit: return "point.topleft.down.to.point.bottomright.curvepath"
        default: return "eye"
        }
    }

    private var stageBriefingCard: some View {
        let targetDist = store.live.stage == .pursuit ? "25 cm" : "33 cm"
        let yawOk = abs(store.live.yawDeg?.floatValue ?? 0) <= 15
        let pitchOk = abs(store.live.pitchDeg?.floatValue ?? 0) <= 18
        let poseOk = store.live.faceDetected && yawOk && pitchOk

        return VStack(alignment: .leading, spacing: 14) {
            HStack {
                HStack(spacing: 6) {
                    Image(systemName: stageSymbol)
                        .foregroundColor(LabPalette.accent)
                    Text("阶段 \(store.live.stageIndex) / \(store.live.totalStages)")
                        .font(LabFont.microMono)
                        .foregroundColor(LabPalette.accent)
                }
                .padding(.horizontal, 10).padding(.vertical, 4)
                .background(LabPalette.accentSoft)
                .clipShape(Capsule())
                Spacer()
                Button {
                    store.feedback.tap()
                    store.feedback.speak(store.live.stageBriefingBody, interrupt: true)
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "speaker.wave.2")
                        Text("重播语音").font(LabFont.caption)
                    }
                    .foregroundColor(LabPalette.inkSecondary)
                    .padding(.horizontal, 10).padding(.vertical, 6)
                    .overlay(Capsule().stroke(LabPalette.hairlineStrong, lineWidth: 1))
                }
            }

            Text(store.live.stageBriefingTitle.isEmpty ? store.live.stage.title : store.live.stageBriefingTitle)
                .font(LabFont.title)
                .foregroundColor(LabPalette.inkPrimary)

            Text(store.live.stageBriefingBody.isEmpty ? store.live.prompt : store.live.stageBriefingBody)
                .font(LabFont.body)
                .foregroundColor(LabPalette.inkSecondary)
                .fixedSize(horizontal: false, vertical: true)

            // 实时就绪状态条（受试者可在点击确认前先调好距离与头姿）
            HStack(spacing: 8) {
                hudPill(
                    store.live.faceDetected ? "双眼已就绪" : "未检测到双眼",
                    store.live.faceDetected ? LabPalette.verified : LabPalette.caution
                )
                hudPill(
                    store.live.distanceCm > 0 ? "\(String(format: "%.1f", store.live.distanceCm)) cm · 目标 \(targetDist)" : "测距中…",
                    store.live.distanceOk ? LabPalette.verified : LabPalette.caution
                )
                if store.live.faceDetected {
                    hudPill(poseOk ? "头姿正" : "请摆正头", poseOk ? LabPalette.verified : LabPalette.caution)
                }
            }

            Button(action: store.confirmStageReady) {
                HStack(spacing: 8) {
                    Image(systemName: "checkmark.circle.fill")
                    Text("我已准备好，开始本阶段").font(LabFont.section)
                }
                .frame(maxWidth: .infinity, minHeight: 50)
                .background(LabPalette.accent)
                .foregroundColor(LabPalette.onAccent)
                .clipShape(RoundedRectangle(cornerRadius: 12))
            }
        }
        .padding(18)
        .background(LabPalette.surface)
        .clipShape(RoundedRectangle(cornerRadius: 18))
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(LabPalette.hairlineStrong, lineWidth: 1))
    }
}

/// 5-Tab 全景数据可视化、叠加视频回放与 Debug 工作台。
struct LabDashboardView: View {
    @EnvironmentObject var store: StrabismusLabStore
    let report: LabSessionReport

    @State private var tab = 0
    @State private var stageChoice = 0
    @State private var eyeChoice = 0
    @State private var videoMode = 0 // 0 = 四联科研拼接 annotated.mp4 (16:9), 1 = 原始高清 original.mp4 (9:16)
    @State private var shareItem: URL?

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button(action: {
                    store.backToConsole()
                }) {
                    Image(systemName: "chevron.left").foregroundColor(LabPalette.inkPrimary).frame(width: 44, height: 44)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(report.config.subjectId).font(LabFont.section).foregroundColor(LabPalette.inkPrimary)
                    Text("\(report.algorithmVersion) · \(report.sourceWidth)×\(report.sourceHeight)")
                        .font(LabFont.microMono).foregroundColor(LabPalette.inkSecondary)
                }
                Spacer()
                Menu {
                    ForEach(LabExportFormat.allCases) { fmt in
                        if let url = store.exportURL(for: report, format: fmt) {
                            Button(fmt.label) { shareItem = url }
                        }
                    }
                } label: {
                    HStack(spacing: 5) {
                        Image(systemName: "square.and.arrow.up")
                        Text("导出").font(LabFont.caption)
                    }
                    .foregroundColor(LabPalette.onAccent)
                    .padding(.horizontal, 12).padding(.vertical, 8)
                    .background(LabPalette.accent)
                    .clipShape(Capsule())
                }
            }
            .padding(.horizontal, 12).padding(.vertical, 6)

            if store.renderingVideo {
                HStack(spacing: 8) {
                    ProgressView(value: store.videoRenderProgress).tint(LabPalette.accent)
                    Text("合成叠加视频 \(Int(store.videoRenderProgress * 100))%")
                        .font(LabFont.microMono).foregroundColor(LabPalette.accent)
                }
                .padding(.horizontal, 16).padding(.bottom, 6)
            }

            Picker("Tab", selection: $tab) {
                Text("总览").tag(0)
                Text("光码").tag(1)
                Text("时序").tag(2)
                Text("视频").tag(3)
                Text("逐帧").tag(4)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 14).padding(.bottom, 8)

            ScrollView {
                VStack(spacing: 14) {
                    if tab == 0 {
                        overviewContent
                    } else if tab == 1 {
                        codedContent
                    } else if tab == 2 {
                        debugContent
                    } else if tab == 3 {
                        videoContent
                    } else {
                        framesContent
                    }
                }
                .padding(16)
            }
        }
        .background(LabPalette.canvas.ignoresSafeArea())
        .sheet(isPresented: Binding(get: { shareItem != nil }, set: { if !$0 { shareItem = nil } })) {
            if let url = shareItem {
                LabShareSheet(items: [url])
            }
        }
    }

    private var overviewContent: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text(report.riskGrade.label)
                        .font(LabFont.caption)
                        .foregroundColor(gradeColor(report.riskGrade))
                        .padding(.horizontal, 10).padding(.vertical, 4)
                        .background(gradeColor(report.riskGrade).opacity(0.16))
                        .clipShape(Capsule())
                    Spacer()
                    Text("置信 \(Int(report.confidenceScore * 100))%").font(LabFont.microMono).foregroundColor(LabPalette.inkSecondary)
                }
                Text(report.headline).font(LabFont.section).foregroundColor(LabPalette.inkPrimary)
                if let cls = report.coverClassification {
                    Text("遮盖分型：\(cls)").font(LabFont.caption).foregroundColor(LabPalette.verified)
                }
                ForEach(report.summaryNotes, id: \.self) { note in
                    Text("· \(note)").font(LabFont.caption).foregroundColor(LabPalette.inkSecondary)
                }
            }
            .labCard()

            HStack(spacing: 10) {
                let nearH3d = report.nearMetrics?.hde3dPrism ?? report.nearMetrics?.hdePrism
                let nearV3d = report.nearMetrics?.vde3dPrism ?? report.nearMetrics?.vdePrism
                let nearLin = report.nearMetrics?.hdePrism
                metricBox(
                    "看近 3D HDE",
                    nearH3d.map { String(format: "%+.1fΔ", $0.doubleValue) } ?? "—",
                    "V \(nearV3d.map { String(format: "%+.1fΔ", $0.doubleValue) } ?? "—") · 线性 \(nearLin.map { String(format: "%+.1f", $0.doubleValue) } ?? "—")",
                    LabPalette.accent
                )
                let farH3d = report.farMetrics?.hde3dPrism ?? report.farMetrics?.hdePrism
                metricBox(
                    "看远 3D HDE",
                    farH3d.map { String(format: "%+.1fΔ", $0.doubleValue) } ?? "—",
                    "\(report.farMetrics?.validBinocularFrames ?? 0) 帧\(report.farMetrics?.stableOnOnly == true ? " · ON" : "")",
                    LabPalette.rightEye
                )
            }
            HStack(spacing: 10) {
                metricBox(
                    "遮盖 显斜/隐斜",
                    report.coverPhoriaMedianPrism.map { String(format: "%.1fΔ", $0.doubleValue) } ?? (report.coverTropiaMedianPrism.map { String(format: "%.1fΔ", $0.doubleValue) } ?? "—"),
                    "对侧 \(report.coverTropiaMedianPrism.map { String(format: "%.1fΔ", $0.doubleValue) } ?? "—") · 噪声 \(report.coverNoiseFloorPrism.map { String(format: "%.1fΔ", $0.doubleValue) } ?? "—")",
                    LabPalette.verified
                )
                metricBox(
                    "追踪 BSI (400ms)",
                    report.pursuitMetrics?.bsi.map { String(format: "%.2f", $0.doubleValue) } ?? "—",
                    "rH \(report.pursuitMetrics?.rHorizontal.map { String(format: "%.2f", $0.doubleValue) } ?? "—") · rV \(report.pursuitMetrics?.rVertical.map { String(format: "%.2f", $0.doubleValue) } ?? "—")",
                    LabPalette.caution
                )
            }

            if let nm = report.nearMetrics, nm.hdeVergence3dPrism != nil {
                VStack(alignment: .leading, spacing: 6) {
                    Text("3D 球面光学消融对照（vs 线性 175.5）").font(LabFont.section).foregroundColor(LabPalette.inkPrimary)
                    auditRow("3D 不对称 HDE / 3D 聚散 HDE", "\(nm.hde3dPrism.map { String(format: "%+.1fΔ", $0.doubleValue) } ?? "—") / \(nm.hdeVergence3dPrism.map { String(format: "%+.1fΔ", $0.doubleValue) } ?? "—")")
                    auditRow("线性 175.5 HDE / 3D SD", "\(nm.hdePrism.map { String(format: "%+.1fΔ", $0.doubleValue) } ?? "—") / ±\(nm.hde3dSdPrism.map { String(format: "%.1fΔ", $0.doubleValue) } ?? "—")")
                    if let intr = report.cameraIntrinsics {
                        auditRow("相机内参 fx/fy · 离轴 Δy", "\(String(format: "%.0f/%.0f px", intr.fx, intr.fy)) · \(String(format: "%.0f mm", intr.lightOffsetYMm))")
                    }
                }
                .labCard()
            }

            VStack(alignment: .leading, spacing: 10) {
                Text("双目虹膜归一化角膜映光靶图").font(LabFont.section).foregroundColor(LabPalette.inkPrimary)
                LabBinocularTargetMap(
                    leftPoints: report.leftScatter,
                    rightPoints: report.rightScatter,
                    leftAnchor: (report.nearLeftCode?.referenceRelX?.doubleValue, report.nearLeftCode?.referenceRelY?.doubleValue),
                    rightAnchor: (report.nearRightCode?.referenceRelX?.doubleValue, report.nearRightCode?.referenceRelY?.doubleValue),
                    searchRadius: report.config.searchRadiusFraction
                )
            }
            .labCard()
        }
    }

    private var codedContent: some View {
        let active: CornealCodeV8Result? = stageChoice == 0
            ? (eyeChoice == 0 ? report.nearLeftCode : report.nearRightCode)
            : (eyeChoice == 0 ? report.farLeftCode : report.farRightCode)
        let tint = eyeChoice == 0 ? LabPalette.accent : LabPalette.rightEye

        return VStack(spacing: 14) {
            HStack(spacing: 8) {
                pillButton("看近", active: stageChoice == 0) { stageChoice = 0 }
                pillButton("看远", active: stageChoice == 1) { stageChoice = 1 }
                pillButton("左眼 L", active: eyeChoice == 0) { eyeChoice = 0 }
                pillButton("右眼 R", active: eyeChoice == 1) { eyeChoice = 1 }
            }
            if let res = active {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text(res.codeVerified ? "光码验证通过" : "未通过光码显著性门槛")
                            .font(LabFont.section)
                            .foregroundColor(res.codeVerified ? LabPalette.verified : LabPalette.caution)
                        Spacer()
                        Text("lag \(res.lagMs.map { String(format: "%.0f ms", $0.doubleValue) } ?? "—")")
                            .font(LabFont.microMono).foregroundColor(LabPalette.accent)
                    }
                    HStack(spacing: 8) {
                        metricBox("训练 r", res.trainCorrelation.map { String(format: "%.3f", $0.doubleValue) } ?? "—", "≥0.35", tint)
                        metricBox("留出 r", res.heldOutCorrelation.map { String(format: "%.3f", $0.doubleValue) } ?? "—", "≥0.30", res.codeVerified ? LabPalette.verified : LabPalette.caution)
                        metricBox("错码 |r|", res.maxWrongCodeCorrelation.map { String(format: "%.3f", $0.doubleValue) } ?? "—", "63 错码", LabPalette.inkSecondary)
                    }
                }
                .labCard()

                LabCodedWaveformChart(waveform: res.waveform, tint: tint)
                LabShiftNullChart(result: res, tint: tint)

                if let audit = res.emissionAudit {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("亮灭发射审计").font(LabFont.section).foregroundColor(LabPalette.inkPrimary)
                        auditRow("亮灯 / 灭灯 / 低电平", "\(audit.stableOnFrames) / \(audit.stableOffFrames) / \(audit.stableDimFrames)")
                        auditRow("过渡 / 未知帧", "\(audit.transitionFrames) / \(audit.unknownFrames)")
                        auditRow("门控前独立灭灯检出", "\(audit.independentOffMeasurements) 帧")
                        auditRow("抑制数 / 最终灭灯检出", "\(audit.suppressedMeasurements) / \(audit.finalOffMeasurements)")
                    }
                    .labCard()
                }
            } else {
                Text("该阶段/眼别暂无光码观测数据").font(LabFont.body).foregroundColor(LabPalette.inkTertiary).labCard()
            }
        }
    }

    private var debugContent: some View {
        let frames = report.frames
        let hde3d = frames.compactMap { f in f.hde3dPrism.map { (f.elapsedSec, $0.doubleValue) } }
        let hdeLin = frames.compactMap { f in f.hdePrism.map { (f.elapsedSec, $0.doubleValue) } }
        let vde3d = frames.compactMap { f in (f.vde3dPrism ?? f.vdePrism).map { (f.elapsedSec, $0.doubleValue) } }
        let dist = frames.filter { $0.distanceCm > 0 }.map { ($0.elapsedSec, Double($0.distanceCm)) }
        let lVis = frames.compactMap { f in f.leftVisibilityContrast.map { (f.elapsedSec, Double($0.floatValue)) } }
        let rVis = frames.compactMap { f in f.rightVisibilityContrast.map { (f.elapsedSec, Double($0.floatValue)) } }
        let markers = report.coverRefixations.map(\.uncoveredAtSec)

        return VStack(spacing: 14) {
            LabTimeSeriesChart(
                title: "双目眼位 3D HDE vs 线性 HDE / 3D VDE 时序",
                unit: "Δ",
                series: [
                    LabLineSeries(name: "3D HDE", color: LabPalette.accent, points: hde3d.isEmpty ? hdeLin : hde3d),
                    LabLineSeries(name: "线性 HDE", color: LabPalette.inkSecondary, points: hdeLin),
                    LabLineSeries(name: "3D VDE", color: LabPalette.rightEye, points: vde3d),
                ],
                referenceLines: [(10, LabPalette.danger), (-10, LabPalette.danger), (0, LabPalette.hairlineStrong)],
                eventMarkersSec: markers
            )

            if !report.coverRefixations.isEmpty {
                VStack(alignment: .leading, spacing: 10) {
                    Text("遮盖/去遮盖毫秒级再注视动力学曲线（共 \(report.coverRefixations.count) 次）")
                        .font(LabFont.section).foregroundColor(LabPalette.inkPrimary)
                    ForEach(Array(report.coverRefixations.enumerated()), id: \.offset) { _, ev in
                        LabCoverKinematicsChart(event: ev)
                    }
                }
                .labCard()
            }

            LabTimeSeriesChart(
                title: "虹膜测距稳定性（33 ± 5 cm）",
                unit: "cm",
                series: [LabLineSeries(name: "距离", color: LabPalette.verified, points: dist)],
                referenceLines: [(28, LabPalette.caution), (33, LabPalette.accent), (38, LabPalette.caution)]
            )
            LabTimeSeriesChart(
                title: "左右眼像素可见性对比度（遮盖拐点信号）",
                unit: "gray",
                series: [
                    LabLineSeries(name: "左眼", color: LabPalette.accent, points: lVis),
                    LabLineSeries(name: "右眼", color: LabPalette.rightEye, points: rVis),
                ],
                eventMarkersSec: markers
            )
        }
    }

    private var videoContent: some View {
        let activeURL = (videoMode == 0 ? store.annotatedVideoURL : store.originalVideoURL)
            ?? store.annotatedVideoURL ?? store.originalVideoURL
        let isQuad = (activeURL == store.annotatedVideoURL)

        return VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 8) {
                pillButton("四联科研拼接 (16:9)", active: videoMode == 0) { videoMode = 0 }
                pillButton("原始高清视频 (9:16)", active: videoMode == 1) { videoMode = 1 }
            }

            if store.renderingVideo {
                VStack(spacing: 10) {
                    ProgressView(value: store.videoRenderProgress).tint(LabPalette.accent)
                    Text("正在合成 1280×720 四联科研对比视频（\(Int(store.videoRenderProgress * 100))%）…")
                        .font(LabFont.caption).foregroundColor(LabPalette.inkSecondary)
                }
                .labCard()
            } else if let url = activeURL {
                LabZoomableVideoPlayer(url: url, aspectRatio: isQuad ? (16.0 / 9.0) : (9.0 / 16.0))
                    .id(url)
                    .labCard()

                HStack(spacing: 10) {
                    Button {
                        store.saveVideoToPhotos(url)
                    } label: {
                        HStack(spacing: 6) {
                            Image(systemName: "photo.on.rectangle.angled")
                            Text("保存到相册").font(LabFont.caption)
                        }
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(LabPalette.accent)
                        .foregroundColor(LabPalette.onAccent)
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                    }
                    Button {
                        shareItem = url
                    } label: {
                        HStack(spacing: 6) {
                            Image(systemName: "square.and.arrow.up")
                            Text("分享 MP4").font(LabFont.caption)
                        }
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(LabPalette.surfaceElevated)
                        .foregroundColor(LabPalette.inkPrimary)
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                        .overlay(RoundedRectangle(cornerRadius: 10).stroke(LabPalette.hairline, lineWidth: 1))
                    }
                }
                if let banner = store.photoSaveBanner {
                    Text(banner).font(LabFont.caption).foregroundColor(LabPalette.verified)
                }
            } else {
                VStack(alignment: .leading, spacing: 6) {
                    Text("本次记录暂无可回放的视频文件").font(LabFont.section).foregroundColor(LabPalette.inkPrimary)
                    Text("请在控制台开启「录制叠加诊断视频」开关后再开始采集。").font(LabFont.caption).foregroundColor(LabPalette.inkSecondary)
                }
                .labCard()
            }

            VStack(alignment: .leading, spacing: 6) {
                Text("四联科研拼接视频图例").font(LabFont.section).foregroundColor(LabPalette.inkPrimary)
                auditRow("中上 / 中下两排", "原始纯净眼区放大 vs 同帧映光叠加对照")
                auditRow("左右格顺序", "与主画面左右完全一致（不交叉错位）")
                auditRow("绿十字 / 白小圈 / 琥珀虚圈", "实测映光 / 单帧候选池 / 灭灯预测")
                auditRow("交互操作", "双指 1–6× 缩放 · 拖动 · 双击 · ±1帧单步")
            }
            .labCard()
        }
    }

    private var framesContent: some View {
        VStack(alignment: .leading, spacing: 10) {
            VStack(alignment: .leading, spacing: 8) {
                Text("导出科研文件与视频").font(LabFont.section).foregroundColor(LabPalette.inkPrimary)
                VStack(spacing: 8) {
                    ForEach(LabExportFormat.allCases) { fmt in
                        if let url = store.exportURL(for: report, format: fmt) {
                            Button {
                                shareItem = url
                            } label: {
                                HStack {
                                    Text(fmt.label).font(LabFont.caption).foregroundColor(LabPalette.inkPrimary)
                                    Spacer()
                                    Image(systemName: "square.and.arrow.up").foregroundColor(LabPalette.accent)
                                }
                                .padding(10)
                                .background(LabPalette.surfaceElevated)
                                .clipShape(RoundedRectangle(cornerRadius: 8))
                                .overlay(RoundedRectangle(cornerRadius: 8).stroke(LabPalette.hairline, lineWidth: 1))
                            }
                        }
                    }
                }
            }
            .labCard()

            ForEach(Array(report.frames.prefix(150)), id: \.index) { f in
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text("#\(f.index) · \(String(format: "%.2fs", f.elapsedSec)) · \(f.stage.title)")
                            .font(LabFont.microMono).foregroundColor(LabPalette.inkPrimary)
                        Spacer()
                        let dispHde = f.hde3dPrism ?? f.hdePrism
                        Text(dispHde.map { String(format: "3D %+.1fΔ", $0.doubleValue) } ?? (f.rejectionReason ?? f.leftEmissionPhase.label))
                            .font(LabFont.microMono).foregroundColor(dispHde != nil ? LabPalette.accent : LabPalette.inkSecondary)
                    }
                    Text("d=\(String(format: "%.1f", f.distanceCm))cm · L:\(f.leftGlintSource.label) R:\(f.rightGlintSource.label) · 候选:\(f.leftCandidateCount)/\(f.rightCandidateCount)")
                        .font(LabFont.microMono).foregroundColor(LabPalette.inkTertiary)
                }
                .padding(10)
                .background(LabPalette.surface)
                .clipShape(RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(f.valid ? LabPalette.hairline : LabPalette.danger.opacity(0.45), lineWidth: 1))
            }
        }
    }
}

struct LabHistoryView: View {
    @EnvironmentObject var store: StrabismusLabStore

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button(action: store.backToConsole) {
                    Image(systemName: "chevron.left").foregroundColor(LabPalette.inkPrimary).frame(width: 44, height: 44)
                }
                Text("科研实验记录 (\(store.history.count))").font(LabFont.title).foregroundColor(LabPalette.inkPrimary)
                Spacer()
            }
            .padding(.horizontal, 12)
            Divider().background(LabPalette.hairline)

            if store.history.isEmpty {
                Spacer()
                Text("暂无科研采集记录").font(LabFont.body).foregroundColor(LabPalette.inkTertiary)
                Spacer()
            } else {
                List {
                    ForEach(store.history) { item in
                        Button {
                            store.openReport(item.report)
                        } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                HStack {
                                    Text(item.report.config.subjectId).font(LabFont.section).foregroundColor(LabPalette.inkPrimary)
                                    if item.hasAnnotatedVideo {
                                        Text("MP4").font(LabFont.microMono).foregroundColor(LabPalette.verified)
                                            .padding(.horizontal, 6).padding(.vertical, 2)
                                            .background(LabPalette.verified.opacity(0.15))
                                            .clipShape(Capsule())
                                    }
                                    Spacer()
                                    Text(item.report.riskGrade.label).font(LabFont.microMono).foregroundColor(gradeColor(item.report.riskGrade))
                                }
                                Text("\(item.report.config.codingScheme.label) · \(Int(item.report.config.searchRadiusFraction * 100))%R · \(item.report.validFrames)/\(item.report.totalFrames) 帧")
                                    .font(LabFont.microMono).foregroundColor(LabPalette.inkSecondary)
                            }
                            .padding(.vertical, 4)
                        }
                        .listRowBackground(LabPalette.surface)
                    }
                    .onDelete { offsets in
                        for i in offsets { store.deleteSession(store.history[i].id) }
                    }
                }
                .scrollContentBackground(.hidden)
            }
        }
        .background(LabPalette.canvas.ignoresSafeArea())
    }
}

struct LabFeedbackControlBar: View {
    @ObservedObject var feedback: StrabismusLabFeedback

    var body: some View {
        HStack(spacing: 12) {
            Toggle(isOn: $feedback.voiceEnabled) {
                HStack(spacing: 5) {
                    Image(systemName: feedback.voiceEnabled ? "speaker.wave.2.fill" : "speaker.slash")
                        .foregroundColor(feedback.voiceEnabled ? LabPalette.accent : LabPalette.inkTertiary)
                    Text("语音提示").font(LabFont.caption).foregroundColor(LabPalette.inkSecondary)
                }
            }
            .tint(LabPalette.accent)

            Toggle(isOn: $feedback.soundHapticsEnabled) {
                HStack(spacing: 5) {
                    Image(systemName: feedback.soundHapticsEnabled ? "waveform.circle.fill" : "waveform.circle")
                        .foregroundColor(feedback.soundHapticsEnabled ? LabPalette.verified : LabPalette.inkTertiary)
                    Text("音效震动").font(LabFont.caption).foregroundColor(LabPalette.inkSecondary)
                }
            }
            .tint(LabPalette.verified)
        }
        .onChange(of: feedback.voiceEnabled) { _ in feedback.toggle() }
        .onChange(of: feedback.soundHapticsEnabled) { on in if on { feedback.play(.tick) } }
    }
}

struct LabShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

// MARK: - Helpers

private extension View {
    func labCard() -> some View {
        self
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .background(LabPalette.surface)
            .clipShape(RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(LabPalette.hairline, lineWidth: 1))
    }
}

private func hudPill(_ text: String, _ color: Color) -> some View {
    Text(text)
        .font(LabFont.microMono)
        .foregroundColor(color)
        .padding(.horizontal, 10).padding(.vertical, 4)
        .background(LabPalette.canvas.opacity(0.8))
        .clipShape(Capsule())
        .overlay(Capsule().stroke(color.opacity(0.5), lineWidth: 1))
}

private func miniMetric(_ label: String, _ value: String) -> some View {
    VStack(alignment: .leading, spacing: 2) {
        Text(label).font(LabFont.microMono).foregroundColor(LabPalette.inkTertiary)
        Text(value).font(LabFont.metricMono).foregroundColor(LabPalette.inkPrimary)
    }
    .frame(maxWidth: .infinity, alignment: .leading)
    .padding(8)
    .background(LabPalette.surfaceElevated)
    .clipShape(RoundedRectangle(cornerRadius: 10))
    .overlay(RoundedRectangle(cornerRadius: 10).stroke(LabPalette.hairline, lineWidth: 1))
}

private func metricBox(_ title: String, _ primary: String, _ sub: String, _ tint: Color) -> some View {
    VStack(alignment: .leading, spacing: 4) {
        Text(title).font(LabFont.caption).foregroundColor(LabPalette.inkSecondary)
        Text(primary).font(LabFont.metricHero).foregroundColor(tint)
        Text(sub).font(LabFont.microMono).foregroundColor(LabPalette.inkSecondary)
    }
    .frame(maxWidth: .infinity, alignment: .leading)
    .padding(12)
    .background(LabPalette.surface)
    .clipShape(RoundedRectangle(cornerRadius: 14))
    .overlay(RoundedRectangle(cornerRadius: 14).stroke(LabPalette.hairline, lineWidth: 1))
}

private func pillButton(_ label: String, active: Bool, action: @escaping () -> Void) -> some View {
    Button(action: action) {
        Text(label)
            .font(LabFont.caption)
            .foregroundColor(active ? LabPalette.accent : LabPalette.inkSecondary)
            .padding(.horizontal, 12).padding(.vertical, 7)
            .background(active ? LabPalette.accentSoft : LabPalette.surfaceElevated)
            .clipShape(Capsule())
            .overlay(Capsule().stroke(active ? LabPalette.accent : LabPalette.hairline, lineWidth: 1))
    }
}

private func auditRow(_ label: String, _ value: String) -> some View {
    HStack {
        Text(label).font(LabFont.caption).foregroundColor(LabPalette.inkSecondary)
        Spacer()
        Text(value).font(LabFont.microMono).foregroundColor(LabPalette.inkPrimary)
    }
}

private func gradeColor(_ grade: LabRiskGrade) -> Color {
    switch grade {
    case .normal: return LabPalette.verified
    case .suspect: return LabPalette.caution
    case .highRisk: return LabPalette.danger
    default: return LabPalette.inkSecondary
    }
}

private func copyConfig(
    _ base: LabProtocolConfig,
    subjectId: String? = nil,
    glassesState: String? = nil,
    lightingCondition: String? = nil,
    clinicalNote: String? = nil,
    codingScheme: LabCodingScheme? = nil,
    searchRadiusFraction: Float? = nil,
    chipDurationMs: Int64? = nil,
    includeNear: Bool? = nil,
    includeFar: Bool? = nil,
    includeCover: Bool? = nil,
    includePursuit: Bool? = nil,
    lightOffsetXMm: Double? = nil,
    lightOffsetYMm: Double? = nil
) -> LabProtocolConfig {
    LabProtocolConfig(
        subjectId: subjectId ?? base.subjectId,
        glassesState: glassesState ?? base.glassesState,
        lightingCondition: lightingCondition ?? base.lightingCondition,
        clinicalNote: clinicalNote ?? base.clinicalNote,
        codingScheme: codingScheme ?? base.codingScheme,
        searchRadiusFraction: searchRadiusFraction ?? base.searchRadiusFraction,
        chipDurationMs: chipDurationMs ?? base.chipDurationMs,
        stimulusDiameterMm: base.stimulusDiameterMm,
        nearDurationSec: base.nearDurationSec,
        farDurationSec: base.farDurationSec,
        pursuitDurationSec: base.pursuitDurationSec,
        includeNear: includeNear ?? base.includeNear,
        includeFar: includeFar ?? base.includeFar,
        includeCover: includeCover ?? base.includeCover,
        includePursuit: includePursuit ?? base.includePursuit,
        lightOffsetXMm: lightOffsetXMm ?? base.lightOffsetXMm,
        lightOffsetYMm: lightOffsetYMm ?? base.lightOffsetYMm
    )
}

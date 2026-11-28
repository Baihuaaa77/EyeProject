import EyeScreening
import SwiftUI
import UIKit

/// 快捷数据集采集控制台与样本管理页：
/// 严格仅保留「受试者 ID」「距离（25 cm / 33 cm）」「戴镜（裸眼 / 戴镜）」三项最小标签，
/// 采集数据直接落盘至 `Documents/StrabismusDataset/`，支持 USB 文件共享直拷与一键整包 ZIP 导出。
struct LabDatasetSetupView: View {
    @EnvironmentObject var store: StrabismusLabStore
    @State private var shareURL: URL?

    var body: some View {
        ZStack(alignment: .bottom) {
            LabPalette.canvas.ignoresSafeArea()
            VStack(spacing: 0) {
                // 顶栏
                HStack {
                    Button(action: store.backToConsole) {
                        Image(systemName: "chevron.left")
                            .foregroundColor(LabPalette.inkPrimary)
                            .frame(width: 44, height: 44)
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text("快捷数据集采集").font(LabFont.title).foregroundColor(LabPalette.inkPrimary)
                        Text("8s · 64 码元 m-序列 · ±2 cm 严控").font(LabFont.microMono).foregroundColor(LabPalette.accent)
                    }
                    Spacer()
                    Text("\(store.datasetSamples.count) 条")
                        .font(LabFont.metricMono)
                        .foregroundColor(LabPalette.verified)
                        .padding(.horizontal, 10).padding(.vertical, 5)
                        .background(LabPalette.verified.opacity(0.14))
                        .clipShape(Capsule())
                }
                .padding(.horizontal, 12).padding(.vertical, 6)

                Divider().background(LabPalette.hairline)

                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        // 实时预览与对距预览窗
                        previewCard

                        // 最小必要采集标签（无环境标签、无偏角真值标签）
                        configCard

                        // 已采集数据集管理与迁移导出
                        datasetInventoryCard
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 12)
                    .padding(.bottom, 100)
                }
            }

            // 底部主 CTA
            VStack(spacing: 0) {
                Divider().background(LabPalette.hairline)
                Button(action: store.startDatasetArming) {
                    HStack(spacing: 8) {
                        Image(systemName: "record.circle.fill")
                        Text("开始 8 秒快捷采集（\(store.datasetTargetDistance.label) · \(store.datasetGlasses.label)）")
                            .font(LabFont.section)
                    }
                    .frame(maxWidth: .infinity, minHeight: 50)
                    .background(store.cameraAuthorized ? LabPalette.accent : LabPalette.surfaceElevated)
                    .foregroundColor(store.cameraAuthorized ? LabPalette.onAccent : LabPalette.inkTertiary)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                }
                .disabled(!store.cameraAuthorized)
                .padding(.horizontal, 16).padding(.vertical, 12)
            }
            .background(LabPalette.canvas.opacity(0.95))
        }
        .sheet(isPresented: Binding(get: { shareURL != nil }, set: { if !$0 { shareURL = nil } })) {
            if let url = shareURL {
                LabShareSheet(items: [url])
            }
        }
    }

    private var previewCard: some View {
        let dist = store.datasetLiveDistanceCm
        let inRange = dist.map { abs($0 - store.datasetTargetDistance.cm) <= StrabismusLabStore.datasetDistanceToleranceCm } ?? false
        let distColor = dist == nil ? LabPalette.inkSecondary : (inRange ? LabPalette.verified : LabPalette.caution)

        return ZStack {
            if store.cameraAuthorized {
                LabCameraPreviewView(session: store.camera.captureSession)
                LabViewfinderOverlay(live: store.live)
                VStack {
                    HStack {
                        dsPill(
                            dist.map { String(format: "%.1f cm / 目标 %d±2", $0, store.datasetTargetDistance.rawValue) } ?? "未检测到双眼",
                            distColor
                        )
                        Spacer()
                        dsPill(String(format: "%.0f fps", store.live.fps), LabPalette.accent)
                    }
                    Spacer()
                }
                .padding(8)
            } else {
                VStack(spacing: 10) {
                    Text("需要前置相机权限").font(LabFont.body).foregroundColor(LabPalette.inkSecondary)
                    Button("授权相机", action: store.checkPermission)
                        .padding(.horizontal, 16).padding(.vertical, 10)
                        .background(LabPalette.accent)
                        .foregroundColor(LabPalette.onAccent)
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                }
            }
        }
        .aspectRatio(4.0 / 3.0, contentMode: .fit)
        .background(Color.black)
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(inRange ? LabPalette.verified : LabPalette.hairline, lineWidth: inRange ? 1.5 : 1))
    }

    private var configCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            // 受试者编号
            HStack(spacing: 8) {
                Text("受试者").font(LabFont.caption).foregroundColor(LabPalette.inkSecondary).frame(width: 52, alignment: .leading)
                TextField("SUBJ-001", text: $store.datasetSubjectId)
                    .font(LabFont.metricMono)
                    .foregroundColor(LabPalette.inkPrimary)
                    .textFieldStyle(.plain)
                    .padding(.horizontal, 10).padding(.vertical, 9)
                    .background(LabPalette.surfaceElevated)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                    .overlay(RoundedRectangle(cornerRadius: 10).stroke(LabPalette.hairline, lineWidth: 1))
                Button(action: store.incrementDatasetSubjectId) {
                    Text("+1")
                        .font(LabFont.metricMono)
                        .foregroundColor(LabPalette.accent)
                        .frame(minWidth: 44, minHeight: 38)
                        .background(LabPalette.accentSoft)
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                        .overlay(RoundedRectangle(cornerRadius: 10).stroke(LabPalette.accent, lineWidth: 1))
                }
            }

            // 目标距离（仅 25 cm / 33 cm）
            HStack(spacing: 8) {
                Text("距离").font(LabFont.caption).foregroundColor(LabPalette.inkSecondary).frame(width: 52, alignment: .leading)
                ForEach(DatasetTargetDistance.allCases) { opt in
                    dsChoiceButton(opt.label, active: store.datasetTargetDistance == opt) {
                        store.feedback.toggle()
                        store.datasetTargetDistance = opt
                    }
                }
            }

            // 戴镜状态（仅 裸眼 / 戴镜）
            HStack(spacing: 8) {
                Text("戴镜").font(LabFont.caption).foregroundColor(LabPalette.inkSecondary).frame(width: 52, alignment: .leading)
                ForEach(DatasetGlassesOption.allCases) { opt in
                    dsChoiceButton(opt.label, active: store.datasetGlasses == opt) {
                        store.feedback.toggle()
                        store.datasetGlasses = opt
                    }
                }
            }

            Divider().background(LabPalette.hairline)
            LabFeedbackControlBar(feedback: store.feedback)
        }
        .dsCard()
    }

    private var datasetInventoryCard: some View {
        let count25 = store.datasetSamples.filter { $0.meta.targetDistanceCm == 25 }.count
        let count33 = store.datasetSamples.filter { $0.meta.targetDistanceCm == 33 }.count
        let countGlasses = store.datasetSamples.filter { $0.meta.glasses == DatasetGlassesOption.glasses.rawValue }.count
        let totalMB = Double(store.datasetSamples.reduce(0) { $0 + $1.totalBytes }) / (1024.0 * 1024.0)

        return VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("本机数据集").font(LabFont.section).foregroundColor(LabPalette.inkPrimary)
                Spacer()
                Text(String(format: "%.1f MB", totalMB))
                    .font(LabFont.microMono).foregroundColor(LabPalette.inkSecondary)
            }

            HStack(spacing: 8) {
                dsStatBox("25 cm", "\(count25)")
                dsStatBox("33 cm", "\(count33)")
                dsStatBox("戴镜", "\(countGlasses)")
                dsStatBox("裸眼", "\(store.datasetSamples.count - countGlasses)")
            }

            Button {
                store.exportAllDatasetZip { url in
                    if let url = url { shareURL = url }
                }
            } label: {
                HStack(spacing: 8) {
                    if store.datasetExportingZip {
                        ProgressView().tint(LabPalette.onAccent)
                        Text("正在打包 ZIP…").font(LabFont.section)
                    } else {
                        Image(systemName: "shippingbox.fill")
                        Text("打包导出全部数据集 (.zip)").font(LabFont.section)
                    }
                }
                .frame(maxWidth: .infinity, minHeight: 46)
                .background(!store.datasetSamples.isEmpty && !store.datasetExportingZip ? LabPalette.verified : LabPalette.surfaceElevated)
                .foregroundColor(!store.datasetSamples.isEmpty && !store.datasetExportingZip ? LabPalette.onAccent : LabPalette.inkTertiary)
                .clipShape(RoundedRectangle(cornerRadius: 10))
            }
            .disabled(store.datasetSamples.isEmpty || store.datasetExportingZip)

            if let err = store.datasetExportError {
                Text(err).font(LabFont.caption).foregroundColor(LabPalette.danger)
            }

            Text("USB 迁移路径：文件 App / Apple 设备 → StrabismusLab → StrabismusDataset")
                .font(LabFont.microMono)
                .foregroundColor(LabPalette.inkTertiary)

            if !store.datasetSamples.isEmpty {
                Divider().background(LabPalette.hairline)
                VStack(spacing: 8) {
                    ForEach(store.datasetSamples) { sample in
                        HStack(spacing: 10) {
                            VStack(alignment: .leading, spacing: 3) {
                                HStack(spacing: 6) {
                                    Text(sample.meta.subjectId).font(LabFont.section).foregroundColor(LabPalette.inkPrimary)
                                    dsTag("\(sample.meta.targetDistanceCm)cm", LabPalette.accent)
                                    dsTag(sample.meta.glassesLabel, sample.meta.glasses == "glasses" ? LabPalette.rightEye : LabPalette.verified)
                                }
                                Text(String(
                                    format: "%d 帧 · %.1f–%.1f cm (最大偏差 %.1f) · %.1f MB",
                                    sample.meta.videoFrameCount,
                                    sample.meta.distanceStats.minCm,
                                    sample.meta.distanceStats.maxCm,
                                    sample.meta.distanceStats.maxAbsErrorCm,
                                    Double(sample.totalBytes) / (1024.0 * 1024.0)
                                ))
                                .font(LabFont.microMono)
                                .foregroundColor(LabPalette.inkSecondary)
                            }
                            Spacer()
                            Button {
                                store.exportSingleDatasetZip(sample) { url in
                                    if let url = url { shareURL = url }
                                }
                            } label: {
                                Image(systemName: "square.and.arrow.up")
                                    .foregroundColor(LabPalette.accent)
                                    .frame(width: 36, height: 36)
                            }
                            Button {
                                store.deleteDatasetSample(sample.id)
                            } label: {
                                Image(systemName: "trash")
                                    .foregroundColor(LabPalette.danger.opacity(0.85))
                                    .frame(width: 36, height: 36)
                            }
                        }
                        .padding(10)
                        .background(LabPalette.surfaceElevated)
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                        .overlay(RoundedRectangle(cornerRadius: 10).stroke(LabPalette.hairline, lineWidth: 1))
                    }
                }
            }
        }
        .dsCard()
    }
}

/// 纯黑 #000000 8 秒严格距离监督采集舞台：
/// - `.arming`：未到位绝不开录，距离进入 `target ± 2 cm` 并连续稳定 0.6s 自动启动 8 秒录制；
/// - `.recording`：顶部 60Hz 纯白 m-序列编码白点闪烁；任何时候距离偏差 > 2 cm 自动中止丢弃脏数据并回到 `.arming` 重来；
/// - `.completed`：展示本条采集校验结果，一键继续下一条或返回导出。
struct LabDatasetCaptureView: View {
    @EnvironmentObject var store: StrabismusLabStore

    var body: some View {
        TimelineView(.animation) { context in
            ZStack {
                Color.black.ignoresSafeArea()
                    .onAppear { store.onDisplayTick() }
                    .onChange(of: context.date) { _ in store.onDisplayTick() }

                // 顶部编码白点（仅在 `.recording` 阶段按 60Hz 严格同步亮灭）
                if store.datasetPhase == .recording {
                    let lum = Double(min(1, max(0, store.renderedIntensity)))
                    let dotPt = CGFloat(min(140, max(28, store.config.stimulusDiameterMm * 6.3)))
                    VStack {
                        ZStack {
                            Circle()
                                .stroke(LabPalette.accent.opacity(0.45), lineWidth: 1.5)
                                .frame(width: dotPt + 10, height: dotPt + 10)
                            Circle()
                                .fill(Color(white: lum))
                                .frame(width: dotPt, height: dotPt)
                                .transaction { $0.animation = nil }
                            if lum > 0.5 {
                                Circle().fill(LabPalette.accent.opacity(0.45)).frame(width: 2.5, height: 2.5)
                            }
                        }
                        .padding(.top, 4)
                        .animation(nil, value: lum)
                        Spacer()
                    }
                }

                // 中央状态主卡
                Group {
                    switch store.datasetPhase {
                    case .arming:
                        armingCenterCard
                            .padding(.horizontal, 20)
                    case .recording:
                        EmptyView()
                    case .saving:
                        VStack(spacing: 12) {
                            ProgressView().tint(LabPalette.accent)
                            Text("正在写入高码率视频与时间线…")
                                .font(LabFont.body).foregroundColor(LabPalette.inkSecondary)
                        }
                    case .completed:
                        completedCenterCard
                            .padding(.horizontal, 20)
                    }
                }

                // 底部紧凑距离监督遥测条
                if store.datasetPhase == .arming || store.datasetPhase == .recording {
                    VStack {
                        Spacer()
                        bottomTelemetryBar
                    }
                }
            }
        }
    }

    private var armingCenterCard: some View {
        let target = store.datasetTargetDistance.cm
        let dist = store.datasetLiveDistanceCm
        let inRange = store.datasetDistanceInRange
        let accentColor = dist == nil ? LabPalette.inkSecondary : (inRange ? LabPalette.verified : LabPalette.caution)

        return VStack(spacing: 16) {
            HStack {
                dsTag("\(store.datasetSubjectId)", LabPalette.accent)
                dsTag("\(store.datasetTargetDistance.label) ±2", LabPalette.verified)
                dsTag(store.datasetGlasses.label, LabPalette.rightEye)
                Spacer()
                if store.datasetRestartCount > 0 {
                    dsTag("重来 \(store.datasetRestartCount)", LabPalette.danger)
                }
            }

            if let abortReason = store.datasetLastAbortReason {
                HStack(spacing: 6) {
                    Image(systemName: "arrow.counterclockwise.circle.fill").foregroundColor(LabPalette.danger)
                    Text(abortReason).font(LabFont.caption).foregroundColor(LabPalette.danger)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(10)
                .background(LabPalette.danger.opacity(0.14))
                .clipShape(RoundedRectangle(cornerRadius: 10))
            }

            VStack(spacing: 4) {
                Text(dist.map { String(format: "%.1f cm", $0) } ?? "--.- cm")
                    .font(.system(size: 46, weight: .bold, design: .monospaced))
                    .foregroundColor(accentColor)
                Text(store.datasetHint)
                    .font(LabFont.section)
                    .foregroundColor(inRange ? LabPalette.verified : LabPalette.inkPrimary)
                    .multilineTextAlignment(.center)
            }

            // 可视化 ±2 cm 距离游标尺（范围：target - 8 ... target + 8 cm）
            distanceGauge(currentCm: dist, targetCm: target, toleranceCm: StrabismusLabStore.datasetDistanceToleranceCm)

            // 到位稳定 0.6s 自动触发进度条
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text(inRange ? "保持稳定，即将自动开始 8 秒录制" : "进入绿区并保持稳定即自动开始")
                        .font(LabFont.microMono)
                        .foregroundColor(inRange ? LabPalette.verified : LabPalette.inkSecondary)
                    Spacer()
                    Text("\(Int(store.datasetHoldProgress * 100))%")
                        .font(LabFont.microMono)
                        .foregroundColor(inRange ? LabPalette.verified : LabPalette.inkTertiary)
                }
                GeometryReader { g in
                    ZStack(alignment: .leading) {
                        Capsule().fill(LabPalette.surfaceElevated)
                        Capsule()
                            .fill(LabPalette.verified)
                            .frame(width: g.size.width * CGFloat(min(1, max(0, store.datasetHoldProgress))))
                    }
                }
                .frame(height: 6)
            }
        }
        .padding(18)
        .background(LabPalette.surface)
        .clipShape(RoundedRectangle(cornerRadius: 18))
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(inRange ? LabPalette.verified : LabPalette.hairlineStrong, lineWidth: inRange ? 2 : 1))
    }

    private var completedCenterCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 8) {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 24))
                    .foregroundColor(LabPalette.verified)
                Text("样本采集完成")
                    .font(LabFont.title)
                    .foregroundColor(LabPalette.inkPrimary)
                Spacer()
            }

            if let sample = store.datasetCompletedSample {
                VStack(alignment: .leading, spacing: 6) {
                    dsInfoRow("样本 ID", sample.id)
                    dsInfoRow("目标距离 / 戴镜", "\(sample.meta.targetDistanceCm) cm · \(sample.meta.glassesLabel)")
                    dsInfoRow(
                        "全程实测距离",
                        String(format: "%.1f – %.1f cm (最大偏差 %.1f cm)", sample.meta.distanceStats.minCm, sample.meta.distanceStats.maxCm, sample.meta.distanceStats.maxAbsErrorCm)
                    )
                    dsInfoRow("视频帧 / 光码事件", "\(sample.meta.videoFrameCount) 帧 · \(sample.meta.displayEventCount) 事件")
                    dsInfoRow("分辨率 / 重来次数", "\(sample.meta.videoWidth)×\(sample.meta.videoHeight) · \(sample.meta.restartCount) 次")
                }
                .padding(12)
                .background(LabPalette.surfaceElevated)
                .clipShape(RoundedRectangle(cornerRadius: 12))
            }

            HStack(spacing: 10) {
                Button(action: store.finishDatasetAndReturnToSetup) {
                    Text("返回数据集")
                        .font(LabFont.section)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .background(LabPalette.surfaceElevated)
                        .foregroundColor(LabPalette.inkPrimary)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(LabPalette.hairlineStrong, lineWidth: 1))
                }
                Button(action: store.startDatasetArming) {
                    Text("继续采下一条")
                        .font(LabFont.section)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .background(LabPalette.accent)
                        .foregroundColor(LabPalette.onAccent)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                }
            }
        }
        .padding(18)
        .background(LabPalette.surface)
        .clipShape(RoundedRectangle(cornerRadius: 18))
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(LabPalette.verified.opacity(0.6), lineWidth: 1.5))
    }

    private var bottomTelemetryBar: some View {
        let dist = store.datasetLiveDistanceCm
        let target = store.datasetTargetDistance.cm
        let inRange = store.datasetDistanceInRange

        return VStack(spacing: 8) {
            HStack {
                HStack(spacing: 6) {
                    Circle()
                        .fill(store.datasetPhase == .recording ? LabPalette.danger : LabPalette.caution)
                        .frame(width: 8, height: 8)
                    Text(store.datasetPhase == .recording ? "采集中 · 注视顶部白点" : "对距就绪")
                        .font(LabFont.section)
                        .foregroundColor(LabPalette.inkPrimary)
                }
                Spacer()
                if store.datasetPhase == .recording {
                    Text("\(max(1, store.live.remainingSec))s")
                        .font(LabFont.metricHero)
                        .foregroundColor(LabPalette.accent)
                }
            }

            if store.datasetPhase == .recording {
                GeometryReader { g in
                    ZStack(alignment: .leading) {
                        Capsule().fill(LabPalette.surfaceElevated)
                        Capsule()
                            .fill(LabPalette.accent)
                            .frame(width: g.size.width * CGFloat(min(1, max(0, store.live.stageProgress))))
                    }
                }
                .frame(height: 4)
            }

            HStack {
                Text(
                    dist.map { String(format: "%.1f cm (目标 %d±2) · %.0f fps", $0, Int(target), store.live.fps) }
                    ?? String(format: "--.- cm (目标 %d±2) · %.0f fps", Int(target), store.live.fps)
                )
                .font(LabFont.microMono)
                .foregroundColor(inRange ? LabPalette.verified : LabPalette.danger)
                Spacer()
                Button(action: store.cancelDatasetCapture) {
                    HStack(spacing: 4) {
                        Image(systemName: "xmark")
                        Text("退出").font(LabFont.caption)
                    }
                    .foregroundColor(LabPalette.inkSecondary)
                    .padding(.horizontal, 10).padding(.vertical, 6)
                    .overlay(Capsule().stroke(LabPalette.hairlineStrong, lineWidth: 1))
                }
            }
        }
        .padding(14)
        .background(LabPalette.surface.opacity(0.92))
        .overlay(Rectangle().stroke(LabPalette.hairline, lineWidth: 1))
    }

    private func distanceGauge(currentCm: Float?, targetCm: Float, toleranceCm: Float) -> some View {
        let span: Float = 8.0
        let lo = targetCm - span
        let hi = targetCm + span
        return VStack(spacing: 4) {
            GeometryReader { g in
                let w = g.size.width
                let greenStart = CGFloat((targetCm - toleranceCm - lo) / (hi - lo)) * w
                let greenWidth = CGFloat((toleranceCm * 2) / (hi - lo)) * w
                ZStack(alignment: .leading) {
                    Capsule().fill(LabPalette.surfaceElevated).frame(height: 10)
                    RoundedRectangle(cornerRadius: 4)
                        .fill(LabPalette.verified.opacity(0.32))
                        .overlay(RoundedRectangle(cornerRadius: 4).stroke(LabPalette.verified, lineWidth: 1))
                        .frame(width: greenWidth, height: 14)
                        .offset(x: greenStart)
                    // 中心目标刻线
                    Rectangle()
                        .fill(LabPalette.inkPrimary.opacity(0.7))
                        .frame(width: 2, height: 18)
                        .offset(x: w * 0.5 - 1)
                    if let d = currentCm {
                        let clamped = min(hi, max(lo, d))
                        let x = CGFloat((clamped - lo) / (hi - lo)) * w
                        let ok = abs(d - targetCm) <= toleranceCm
                        Circle()
                            .fill(ok ? LabPalette.verified : LabPalette.danger)
                            .frame(width: 14, height: 14)
                            .overlay(Circle().stroke(Color.white, lineWidth: 1.5))
                            .offset(x: min(w - 14, max(0, x - 7)))
                    }
                }
            }
            .frame(height: 18)
            HStack {
                Text("\(Int(lo)) cm").font(LabFont.microMono).foregroundColor(LabPalette.inkTertiary)
                Spacer()
                Text("\(Int(targetCm - toleranceCm))–\(Int(targetCm + toleranceCm)) cm").font(LabFont.microMono).foregroundColor(LabPalette.verified)
                Spacer()
                Text("\(Int(hi)) cm").font(LabFont.microMono).foregroundColor(LabPalette.inkTertiary)
            }
        }
    }
}

// MARK: - 独立样式辅助

private extension View {
    func dsCard() -> some View {
        self
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .background(LabPalette.surface)
            .clipShape(RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(LabPalette.hairline, lineWidth: 1))
    }
}

private func dsPill(_ text: String, _ color: Color) -> some View {
    Text(text)
        .font(LabFont.microMono)
        .foregroundColor(color)
        .padding(.horizontal, 10).padding(.vertical, 4)
        .background(LabPalette.canvas.opacity(0.84))
        .clipShape(Capsule())
        .overlay(Capsule().stroke(color.opacity(0.55), lineWidth: 1))
}

private func dsTag(_ text: String, _ color: Color) -> some View {
    Text(text)
        .font(LabFont.microMono)
        .foregroundColor(color)
        .padding(.horizontal, 7).padding(.vertical, 2)
        .background(color.opacity(0.15))
        .clipShape(Capsule())
}

private func dsChoiceButton(_ title: String, active: Bool, action: @escaping () -> Void) -> some View {
    Button(action: action) {
        Text(title)
            .font(LabFont.section)
            .frame(maxWidth: .infinity, minHeight: 40)
            .foregroundColor(active ? LabPalette.onAccent : LabPalette.inkSecondary)
            .background(active ? LabPalette.accent : LabPalette.surfaceElevated)
            .clipShape(RoundedRectangle(cornerRadius: 10))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(active ? LabPalette.accent : LabPalette.hairline, lineWidth: 1))
    }
}

private func dsStatBox(_ label: String, _ value: String) -> some View {
    VStack(spacing: 2) {
        Text(value).font(LabFont.metricMono).foregroundColor(LabPalette.inkPrimary)
        Text(label).font(LabFont.microMono).foregroundColor(LabPalette.inkTertiary)
    }
    .frame(maxWidth: .infinity)
    .padding(.vertical, 8)
    .background(LabPalette.surfaceElevated)
    .clipShape(RoundedRectangle(cornerRadius: 10))
}

private func dsInfoRow(_ label: String, _ value: String) -> some View {
    HStack {
        Text(label).font(LabFont.caption).foregroundColor(LabPalette.inkSecondary)
        Spacer()
        Text(value).font(LabFont.microMono).foregroundColor(LabPalette.inkPrimary).lineLimit(1)
    }
}

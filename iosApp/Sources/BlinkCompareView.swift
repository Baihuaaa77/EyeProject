import AVFoundation
import SwiftUI
import UIKit

/// 实验页：EAR 与 OCEC 两种眨眼判据同屏实时对比（同一路相机帧）。
/// 预览上叠加 EAR 的 6 个眼睑点与 OCEC 的裁片框；两张卡片各自计数、亮灯；
/// 下方 8 秒曲线标出每次判定，两者都判到为绿、只有一方判到为红，便于逐次看漏检 / 误检。
struct BlinkCompareView: View {
    @StateObject private var model = BlinkCompareModel()
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var kind = BlinkCompareModel.roundKinds[0]
    @State private var target = 10
    @State private var previousIdleDisabled = false

    var body: some View {
        VStack(spacing: 0) {
            header
            ScrollView {
                VStack(spacing: EyeStyle.spacing) {
                    if let error = model.error {
                        Text(error).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.danger)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    live
                    BlinkCompareCard(title: "最近 8 秒", trailing: "绿 = 两者都判到 · 红 = 仅一方") {
                        BlinkTraceChart(model: model).frame(height: 220)
                    }
                    roundPanel
                    eventList
                    if !model.rounds.isEmpty { history }
                    notes
                }.padding(.horizontal, 16).padding(.bottom, 24)
            }
        }.foregroundStyle(EyeStyle.text)
            .background(EyeStyle.background.ignoresSafeArea())
            .onAppear {
                previousIdleDisabled = UIApplication.shared.isIdleTimerDisabled
                UIApplication.shared.isIdleTimerDisabled = true
                model.start()
            }
            .onDisappear { model.stop(); UIApplication.shared.isIdleTimerDisabled = previousIdleDisabled }
            .onChange(of: scenePhase) { phase in
                if phase == .background { model.stop() } else if phase == .active { model.start() }
            }
    }

    private var header: some View {
        HStack(spacing: 8) {
            Button { EyeHaptics.tap(); model.stop(); dismiss() } label: {
                Image(systemName: "xmark").font(.system(size: 17, weight: .semibold)).frame(width: 44, height: 44)
            }.buttonStyle(.plain)
            Text("眨眼检测对比").font(EyeStyle.subtitle)
            Spacer()
            Text(String(format: "%.0f 帧/秒 · OCEC %.1f ms", model.fps, model.latest?.ocecCostMs ?? 0))
                .font(EyeStyle.chip).foregroundStyle(EyeStyle.secondary).monospacedDigit()
        }.padding(.horizontal, 8).padding(.vertical, 4)
    }

    // MARK: 实时

    private var live: some View {
        let frame = model.latest
        let aspect = frame.map { $0.imageSize.width / max($0.imageSize.height, 1) } ?? 720.0 / 1280.0
        return VStack(spacing: 10) {
            HStack(alignment: .top, spacing: 10) {
                ZStack {
                    CameraPreview(session: model.camera.captureSession)
                    EyeOverlay(frame: frame)
                    if frame?.eyesFound != true {
                        Text(frame?.faceFound == true ? "未检测到双眼" : "未检测到人脸").font(EyeStyle.chip)
                            .foregroundStyle(EyeStyle.onStatus).padding(.horizontal, 8).padding(.vertical, 4)
                            .background(EyeStyle.danger, in: Capsule())
                    }
                }.aspectRatio(aspect, contentMode: .fit).frame(width: 140)
                    .clipShape(RoundedRectangle(cornerRadius: EyeStyle.radiusMedium))
                VStack(spacing: 10) {
                    MethodCard(method: .ear, model: model)
                    MethodCard(method: .ocec, model: model)
                }
            }
            HStack(spacing: 10) {
                // 预览是镜像的：被测者右眼显示在左边，眼片按同一左右顺序排列
                EyeCropTile(label: "右眼", reading: frame?.right)
                EyeCropTile(label: "左眼", reading: frame?.left)
            }
        }
    }

    // MARK: 分轮测试

    private var roundPanel: some View {
        let round = model.round
        let running = round != nil && round?.finished != true
        return BlinkCompareCard(title: "分轮测试", trailing: nil) {
            Text("选好眨眼方式和计划次数，点「开始本轮」后按计划眨眼，再点「结束本轮」，看两种方法各数到几次。每轮逐帧数据自动保存为 CSV。")
                .font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(BlinkCompareModel.roundKinds, id: \.self) { name in
                        Button {
                            EyeHaptics.tap(); kind = name
                            target = name == "不眨眼" ? 0 : max(target, 5)
                        } label: {
                            Text(name).font(EyeStyle.chip).padding(.horizontal, 12).padding(.vertical, 8)
                                .foregroundStyle(kind == name ? EyeStyle.panel : EyeStyle.text)
                                .background(kind == name ? EyeStyle.primary : EyeStyle.surfaceVariant, in: Capsule())
                        }.buttonStyle(.plain).disabled(running)
                    }
                }
            }
            Stepper(value: $target, in: 0...50) {
                Text("计划眨眼 \(target) 次").font(EyeStyle.body)
            }.disabled(running)
            if let round = round {
                RoundResult(round: round, live: !round.finished)
            }
            HStack(spacing: 10) {
                if running {
                    Button(round?.endedAt == nil ? "结束本轮" : "统计中…") { EyeHaptics.tap(); model.stopRound() }
                        .buttonStyle(EyeButtonStyle(role: .danger)).disabled(round?.endedAt != nil)
                } else {
                    Button("开始本轮") { EyeHaptics.tap(); model.startRound(kind: kind, target: target) }
                        .buttonStyle(EyeButtonStyle()).disabled(model.latest == nil)
                }
                Button("清空曲线") { EyeHaptics.tap(); model.clear() }
                    .buttonStyle(EyeButtonStyle(role: .secondary)).disabled(running)
            }
        }
    }

    // MARK: 事件

    private var eventList: some View {
        let start = model.sessionStart ?? 0
        let rows = Self.rows(model.events, model: model).prefix(15)
        return BlinkCompareCard(title: "眨眼事件", trailing: "最近 \(rows.count) 次") {
            if rows.isEmpty {
                Text("还没有判到眨眼").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
            }
            ForEach(Array(rows)) { row in
                HStack(spacing: 8) {
                    Text(String(format: "%.1f s", Double(row.time - start) / 1000)).font(EyeStyle.bodySecondary)
                        .monospacedDigit().frame(width: 64, alignment: .leading)
                    StatusChip(status: row.status, method: row.only)
                    Spacer()
                    Text(row.detail).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary).monospacedDigit()
                }
            }
        }
    }

    private struct EventRow: Identifiable {
        let id: Int, time: Int64, status: BlinkEventStatus, only: BlinkMethod?, detail: String
    }

    /// 配对事件合成一行（取先触发的时间），单方事件各占一行；新的在前。
    private static func rows(_ events: [BlinkEvent], model: BlinkCompareModel) -> [EventRow] {
        let byId = Dictionary(uniqueKeysWithValues: events.map { ($0.id, $0) })
        var rows: [EventRow] = []
        for event in events.reversed() {
            if let partnerId = event.partner {
                guard let partner = byId[partnerId], partner.id < event.id else { continue }
                let ear = event.method == .ear ? event : partner, ocec = event.method == .ocec ? event : partner
                rows.append(EventRow(id: partner.id, time: min(event.time, partner.time), status: .both, only: nil,
                                     detail: "EAR 闭 \(ear.closedMs) ms · OCEC 闭 \(ocec.closedMs) ms"))
            } else {
                rows.append(EventRow(id: event.id, time: event.time, status: model.status(event), only: event.method,
                                     detail: "\(event.method.rawValue) 闭 \(event.closedMs) ms"))
            }
        }
        return rows
    }

    private var history: some View {
        BlinkCompareCard(title: "已完成的轮次", trailing: nil) {
            ForEach(model.rounds) { round in
                VStack(alignment: .leading, spacing: 6) {
                    RoundResult(round: round, live: false)
                    if let file = round.file {
                        ShareLink(item: file) { Label("分享本轮 CSV", systemImage: "square.and.arrow.up").font(EyeStyle.bodySecondary) }
                    }
                }
                if round.id != model.rounds.last?.id { Divider() }
            }
        }
    }

    private var notes: some View {
        BlinkCompareCard(title: "判定规则（与应用一致）", trailing: nil) {
            Text("EAR：与干眼测试相同。双眼 6 点 EAR 取平均；阈值为近 2 秒睁眼中位数 × 0.72（限 0.14–0.26，前 15 帧建立基线不判定）；低于阈值持续 ≥ 60 ms、再回到阈值以上时记一次。双眼任一丢失即中断本次闭眼。")
            Text("OCEC：与 newpart 干眼 OCEC 方案相同。按每只眼 6 个眼睑点外扩 15% 裁片（高度不低于眼宽 0.33）、拉伸到 40×24 送 OCEC-P，双眼睁眼概率取平均；连续 3 帧低于 0.5、再回到 0.5 以上时记一次。裁片参数与 0.5 阈值均未标定。")
            Text("两种方法都在「睁开」那一帧计数，前后 0.5 秒内的两次判定视为同一次眨眼。每轮数据保存在「文件」App → 我的 iPhone → 眼健康助手 → BlinkCompare。")
        }.font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
    }
}

// MARK: 组件

private extension BlinkMethod {
    /// EAR 用测距「过远」的蓝、OCEC 用强调橙，与绿 / 红的判定结果色区分开。
    var color: Color { self == .ear ? EyeStyle.tooFar : EyeStyle.accent }
}

private struct BlinkCompareCard<Content: View>: View {
    let title: String
    let trailing: String?
    @ViewBuilder let content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(title).font(EyeStyle.subtitle)
                Spacer()
                if let trailing = trailing { Text(trailing).font(EyeStyle.chip).foregroundStyle(EyeStyle.secondary) }
            }
            content
        }.padding(14).frame(maxWidth: .infinity, alignment: .leading)
            .background(EyeStyle.panel, in: RoundedRectangle(cornerRadius: EyeStyle.radius))
            .overlay { RoundedRectangle(cornerRadius: EyeStyle.radius).stroke(EyeStyle.borderSubtle, lineWidth: 1) }
    }
}

/// 一种判据的实时卡片：当前读数与阈值、闭眼灯、累计次数；刚判到眨眼时整卡闪一下。
private struct MethodCard: View {
    let method: BlinkMethod
    @ObservedObject var model: BlinkCompareModel
    var body: some View {
        let frame = model.latest
        let signal = method == .ear ? frame?.ear : frame?.ocec
        let count = model.events.filter { $0.method == method }.count
        let lastEvent = model.events.last { $0.method == method }
        let flash = lastEvent.map { (frame?.time ?? 0) - $0.time < 400 } ?? false
        let (stateText, stateColor): (String, Color) = {
            guard let signal = signal, frame?.eyesFound == true, signal.value != nil else { return ("无读数", EyeStyle.secondary) }
            if !signal.ready { return ("建立基线", EyeStyle.warning) }
            return signal.below ? ("闭眼", EyeStyle.danger) : ("睁眼", EyeStyle.success)
        }()
        return VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 6) {
                Circle().fill(method.color).frame(width: 8, height: 8)
                Text(method.title).font(EyeStyle.chip)
                Spacer()
                Text(stateText).font(EyeStyle.chip).foregroundStyle(EyeStyle.onStatus)
                    .padding(.horizontal, 8).padding(.vertical, 3).background(stateColor, in: Capsule())
            }
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text("\(count)").font(EyeStyle.headline).monospacedDigit()
                Text("次").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                Spacer()
                if let ms = lastEvent?.closedMs, flash {
                    Text("闭 \(ms) ms").font(EyeStyle.chip).foregroundStyle(method.color)
                }
            }
            Text(valueText(signal)).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary).monospacedDigit()
                .lineLimit(1).minimumScaleFactor(0.7)
        }.padding(10).frame(maxWidth: .infinity, alignment: .leading)
            .background(flash ? method.color.opacity(0.22) : EyeStyle.panel, in: RoundedRectangle(cornerRadius: EyeStyle.radiusMedium))
            .overlay { RoundedRectangle(cornerRadius: EyeStyle.radiusMedium).stroke(method.color.opacity(0.6), lineWidth: 1.5) }
            .animation(.easeOut(duration: 0.15), value: flash)
    }
    private func valueText(_ signal: BlinkSignal?) -> String {
        let value = signal?.value.map { String(format: method == .ear ? "%.3f" : "%.2f", $0) } ?? "—"
        let threshold = signal.map { String(format: method == .ear ? "%.3f" : "%.2f", $0.threshold) } ?? "—"
        return method == .ear ? "EAR \(value) / 阈值 \(threshold)" : "睁眼概率 \(value) / 阈值 \(threshold)"
    }
}

/// OCEC 实际看到的 40×24 眼片（原图方向、未镜像）与这只眼的两种读数。
private struct EyeCropTile: View {
    let label: String
    let reading: BlinkEyeReading?
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ZStack {
                RoundedRectangle(cornerRadius: 8).fill(EyeStyle.surfaceVariant)
                if let image = reading?.image {
                    Image(decorative: image, scale: 1).resizable().interpolation(.none)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                }
            }.aspectRatio(40.0 / 24.0, contentMode: .fit)
            Text("\(label) · p \(reading?.probability.map { String(format: "%.2f", $0) } ?? "—") · EAR \(reading?.ear.map { String(format: "%.3f", $0) } ?? "—")")
                .font(EyeStyle.chip).foregroundStyle(EyeStyle.secondary).monospacedDigit().lineLimit(1).minimumScaleFactor(0.7)
        }.frame(maxWidth: .infinity)
    }
}

private struct StatusChip: View {
    let status: BlinkEventStatus
    let method: BlinkMethod?
    var body: some View {
        let (text, color): (String, Color) = switch status {
        case .both: ("两者都判到", EyeStyle.success)
        case .only: ("仅 \(method?.rawValue ?? "")", EyeStyle.danger)
        case .pending: ("\(method?.rawValue ?? "") 待配对", EyeStyle.secondary)
        }
        return Text(text).font(EyeStyle.chip).foregroundStyle(EyeStyle.onStatus)
            .padding(.horizontal, 8).padding(.vertical, 3).background(color, in: Capsule())
    }
}

/// 一轮的计数：计划次数对比两种方法各自的计数，以及配对情况。
private struct RoundResult: View {
    let round: BlinkRound
    let live: Bool
    var body: some View {
        let c = round.counts
        VStack(alignment: .leading, spacing: 6) {
            Text("第 \(round.id) 轮 · \(round.kind) · 计划 \(round.target) 次\(live ? "（进行中）" : "")").font(EyeStyle.body)
            HStack(spacing: 10) {
                tally("EAR", c.ear, BlinkMethod.ear.color)
                tally("OCEC", c.ocec, BlinkMethod.ocec.color)
            }
            Text("两者都判到 \(c.both) · 仅 EAR \(c.earOnly) · 仅 OCEC \(c.ocecOnly)")
                .font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
        }
    }
    private func tally(_ name: String, _ count: Int, _ color: Color) -> some View {
        let diff = count - round.target
        let note = diff == 0 ? "与计划一致" : diff < 0 ? "少 \(-diff)（漏检？）" : "多 \(diff)（误检？）"
        return VStack(alignment: .leading, spacing: 2) {
            Text("\(name) \(count)").font(EyeStyle.subtitle).foregroundStyle(color).monospacedDigit()
            Text(note).font(EyeStyle.chip).foregroundStyle(diff == 0 ? EyeStyle.success : EyeStyle.warning)
        }.frame(maxWidth: .infinity, alignment: .leading)
            .padding(8).background(EyeStyle.surfaceVariant, in: RoundedRectangle(cornerRadius: 10))
    }
}

/// 前置相机预览（系统自动镜像，呈自拍视角）。
private struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession
    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        return view
    }
    func updateUIView(_ uiView: PreviewView, context: Context) {}
    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }
}

/// 预览上叠加：EAR 的 6 个眼睑点（蓝）与 OCEC 裁片框（橙）。预览与原图同宽高比，只需水平镜像。
private struct EyeOverlay: View {
    let frame: BlinkFrame?
    var body: some View {
        Canvas { context, size in
            guard let frame = frame, frame.imageSize.width > 0, frame.imageSize.height > 0 else { return }
            func map(_ p: CGPoint) -> CGPoint {
                CGPoint(x: (1 - p.x / frame.imageSize.width) * size.width, y: p.y / frame.imageSize.height * size.height)
            }
            for eye in [frame.left, frame.right] {
                if let crop = eye.crop {
                    let a = map(CGPoint(x: crop.maxX, y: crop.minY)), b = map(CGPoint(x: crop.minX, y: crop.maxY))
                    let color = frame.ocec.below ? EyeStyle.danger : BlinkMethod.ocec.color
                    context.stroke(Path(CGRect(x: a.x, y: a.y, width: b.x - a.x, height: b.y - a.y)), with: .color(color), lineWidth: 1.5)
                }
                let color = frame.ear.below ? EyeStyle.danger : BlinkMethod.ear.color
                for point in eye.points {
                    let q = map(point)
                    context.fill(Path(ellipseIn: CGRect(x: q.x - 1.5, y: q.y - 1.5, width: 3, height: 3)), with: .color(color))
                }
            }
        }.allowsHitTesting(false)
    }
}

/// 两条泳道：上 EAR（0–0.45，虚线为当帧自适应阈值），下 OCEC 睁眼概率（0–1，虚线 0.5）。
/// 低于阈值的时段打底色；每次判定画一条竖线：绿 = 另一方也判到、红 = 仅本方、灰 = 待配对。
private struct BlinkTraceChart: View {
    @ObservedObject var model: BlinkCompareModel
    var body: some View {
        Canvas { context, size in
            let trace = model.trace
            guard let now = trace.last?.time else {
                context.draw(Text("等待相机画面…").font(EyeStyle.bodySecondary).foregroundColor(EyeStyle.secondary),
                             at: CGPoint(x: size.width / 2, y: size.height / 2))
                return
            }
            let window = CGFloat(BlinkCompareModel.traceWindowMs)
            func x(_ t: Int64) -> CGFloat { size.width * (1 - CGFloat(now - t) / window) }
            let gap: CGFloat = 10, laneHeight = (size.height - gap) / 2
            for (index, method) in BlinkMethod.allCases.enumerated() {
                let lane = CGRect(x: 0, y: CGFloat(index) * (laneHeight + gap), width: size.width, height: laneHeight)
                let top: Float = method == .ear ? 0.45 : 1
                func y(_ v: Float) -> CGFloat { lane.maxY - CGFloat(min(max(v, 0), top) / top) * lane.height }
                func signal(_ f: BlinkFrame) -> BlinkSignal { method == .ear ? f.ear : f.ocec }
                context.fill(Path(roundedRect: lane, cornerRadius: 8), with: .color(EyeStyle.background))
                // 每秒一条竖格线
                var second = now - now % 1_000
                while CGFloat(now - second) < window {
                    context.stroke(Path { p in p.move(to: CGPoint(x: x(second), y: lane.minY)); p.addLine(to: CGPoint(x: x(second), y: lane.maxY)) },
                                   with: .color(EyeStyle.borderSubtle), lineWidth: 1)
                    second -= 1_000
                }
                // 低于阈值的时段
                for (i, f) in trace.enumerated() where signal(f).below && signal(f).ready {
                    let end = i + 1 < trace.count ? trace[i + 1].time : f.time + 33
                    context.fill(Path(CGRect(x: x(f.time), y: lane.minY, width: max(x(end) - x(f.time), 1), height: lane.height)),
                                 with: .color(EyeStyle.dangerSoft))
                }
                // 阈值（EAR 为逐帧自适应值）
                var threshold = Path()
                for (i, f) in trace.enumerated() {
                    let point = CGPoint(x: x(f.time), y: y(signal(f).threshold))
                    if i == 0 { threshold.move(to: point) } else { threshold.addLine(to: point) }
                }
                context.stroke(threshold, with: .color(EyeStyle.secondary), style: StrokeStyle(lineWidth: 1, dash: [4, 3]))
                // 信号，缺读数处断开
                var line = Path(), open = false
                for f in trace {
                    guard let v = signal(f).value else { open = false; continue }
                    let point = CGPoint(x: x(f.time), y: y(v))
                    if open { line.addLine(to: point) } else { line.move(to: point); open = true }
                }
                context.stroke(line, with: .color(method.color), lineWidth: 2)
                // 判定竖线
                for event in model.events where event.method == method && CGFloat(now - event.time) < window {
                    let color: Color = switch model.status(event) {
                    case .both: EyeStyle.success
                    case .only: EyeStyle.danger
                    case .pending: EyeStyle.secondary
                    }
                    let ex = x(event.time)
                    context.stroke(Path { p in p.move(to: CGPoint(x: ex, y: lane.minY + 2)); p.addLine(to: CGPoint(x: ex, y: lane.maxY - 2)) },
                                   with: .color(color), lineWidth: 2.5)
                    context.fill(Path(ellipseIn: CGRect(x: ex - 4, y: lane.minY + 2, width: 8, height: 8)), with: .color(color))
                }
                context.draw(Text(method == .ear ? "EAR" : "OCEC 睁眼概率").font(EyeStyle.chip).foregroundColor(method.color),
                             at: CGPoint(x: lane.minX + 8, y: lane.minY + 6), anchor: .topLeading)
            }
        }
    }
}

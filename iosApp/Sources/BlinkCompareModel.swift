import CoreGraphics
import CoreVideo
import Foundation
import EyeScreening

/// 眨眼判据：EAR（关键点几何，干眼测试现用）与 OCEC（眼片分类模型，newpart 干眼方案）。
enum BlinkMethod: String, CaseIterable {
    case ear = "EAR", ocec = "OCEC"
    var title: String { self == .ear ? "EAR 关键点" : "OCEC 模型" }
}

/// 单只眼在一帧上的读数；坐标为相机原图像素（转正、未镜像）。
struct BlinkEyeReading {
    var points: [CGPoint] = []
    var crop: CGRect?
    var ear: Float?
    var probability: Float?
    /// 送进 OCEC 的 40×24 眼片
    var image: CGImage?
}

/// 一种判据在一帧上的信号：数值、当帧阈值、是否低于阈值（闭眼侧）、是否已开始判定。
struct BlinkSignal {
    var value: Float?
    var threshold: Float
    var below: Bool { value.map { $0 < threshold } ?? false }
    var ready = true
}

struct BlinkFrame {
    let time: Int64
    let imageSize: CGSize
    var faceFound = false
    var eyesFound = false
    /// 被测者自己的左眼 / 右眼（MediaPipe 362… / 33…）
    var left = BlinkEyeReading(), right = BlinkEyeReading()
    var ear = BlinkSignal(value: nil, threshold: 0)
    var ocec = BlinkSignal(value: nil, threshold: 0)
    /// 本帧完成一次眨眼时，该判据从低于阈值到睁开的时长（毫秒）
    var earBlinkMs: Int64?, ocecBlinkMs: Int64?
    var ocecCostMs: Double = 0
}

/// 一次眨眼判定事件（在睁开那一帧触发）；两种判据在 ±[BlinkCompareModel.pairWindowMs] 内的事件互相配对。
struct BlinkEvent: Identifiable {
    let id: Int
    let method: BlinkMethod
    let time: Int64
    let closedMs: Int64
    var partner: Int?
}

enum BlinkEventStatus { case both, only, pending }

/// 分轮测试：按选定的眨眼方式与计划次数做一轮，结束后对比两种判据各数到几次。
struct BlinkRound: Identifiable {
    let id: Int
    let kind: String
    let target: Int
    let startedAt: Int64
    var endedAt: Int64?
    var counts = BlinkCounts()
    var file: URL?
    var finished = false
}

struct BlinkCounts {
    var ear = 0, ocec = 0, both = 0, earOnly = 0, ocecOnly = 0
}

/// 同一路相机帧同时喂两种判据，保证对比的输入完全相同：
/// - EAR：共用 `LandmarkGeometry` + `BlinkDetector.dryEyeEar()`，喂法与 `DryEyeEngine.handleFrame` 一致
///   （双眼都在才更新、否则 interrupt；双眼平均 EAR；按帧时间判定 ≥60 ms；自适应阈值）
/// - OCEC：同一帧像素按眼睑 6 点裁眼片 → 手写 OCEC-P 推理 → 双眼概率平均 → `BlinkDetector.ocecOpenProbability()`
///   （与 newpart DryEyeTestViewModel 一致：双眼都在才更新，连续 3 帧 < 0.5 判闭眼）
final class BlinkCompareModel: ObservableObject {
    static let traceWindowMs: Int64 = 8_000
    static let pairWindowMs: Int64 = 500
    static let roundKinds = ["自然眨眼", "快眨", "慢眨", "用力大眨", "轻微小眨", "不眨眼"]

    // 逐帧变化的数据不用 @Published：界面经 [publish] 最多每 80 ms 刷新一次（逐帧重绘整页会占满主线程、手机发热）
    private(set) var latest: BlinkFrame?
    private(set) var trace: [BlinkFrame] = []
    private(set) var events: [BlinkEvent] = []
    private(set) var fps: Double = 0
    @Published private(set) var round: BlinkRound?
    @Published private(set) var rounds: [BlinkRound] = []
    @Published private(set) var error: String?
    /// 相机因发热降帧或可能暂停时的说明
    @Published private(set) var cameraNote: String?
    /// 主线程来不及处理而丢弃的帧数（正常为 0）
    var droppedFrames: Int { frames?.dropped ?? 0 }
    static let publishIntervalNs: UInt64 = 80_000_000
    private var frames: FrameMailbox<BlinkFrame>!
    private var lastPublish: UInt64 = 0
    private var publishScheduled = false

    let camera = EyeCamera()
    /// 相机队列独占：两个判据、分类器与闭眼起点
    private let classifier = OcecEyeClassifier()
    private let earDetector = BlinkDetector.Companion.shared.dryEyeEar()
    private let ocecDetector = BlinkDetector.Companion.shared.ocecOpenProbability()
    private var earSamples = 0
    private var earBelowSince: Int64?
    private var ocecBelowSince: Int64?
    /// 主线程
    private var nextEventId = 1
    private var roundFrames: [BlinkFrame] = []
    private(set) var sessionStart: Int64?

    init() {
        if classifier == nil { error = "缺少 OCEC 模型权重，OCEC 一栏不会有读数。" }
        camera.measureOptics = false
        // 判定在相机队列完成，事件随帧带出；主线程短暂繁忙时最多积压约 3 秒的帧，不丢事件也不无限滞后
        frames = FrameMailbox<BlinkFrame>(limit: 90) { [weak self] batch in self?.ingest(batch) }
        camera.onAnalyzedFrame = { [weak self] buffer, face, time in
            guard let self = self else { return }
            self.frames.post(self.analyze(buffer, face, time))
        }
        camera.onError = { [weak self] message in DispatchQueue.main.async { self?.error = message } }
        camera.onPressure = { [weak self] note in DispatchQueue.main.async { self?.cameraNote = note } }
    }

    func start() { camera.start() }
    func stop() { camera.stop(); frames.clear() }

    // MARK: 相机队列

    private func analyze(_ buffer: CVPixelBuffer, _ face: FaceMetrics?, _ time: Int64) -> BlinkFrame {
        let width = CVPixelBufferGetWidth(buffer), height = CVPixelBufferGetHeight(buffer)
        var frame = BlinkFrame(time: time, imageSize: CGSize(width: width, height: height))
        let left = face?.leftEye, right = face?.rightEye
        let eyes = left != nil && right != nil
        frame.faceFound = face != nil; frame.eyesFound = eyes

        // EAR：与 DryEyeEngine.handleFrame 同一喂法
        let earThreshold = earDetector.threshold
        let ear = eyes ? BlinkDetector.Companion.shared.averageEar(left: left, right: right) : nil
        if eyes {
            let before = earDetector.blinkCount
            earDetector.update(ear: ear, timeMs: KotlinLong(value: time))
            if ear != nil { earSamples += 1 }
            frame.ear = BlinkSignal(value: ear?.floatValue, threshold: earThreshold, ready: earSamples >= 15)
            frame.earBlinkMs = Self.track(&earBelowSince, below: frame.ear.below, blinked: earDetector.blinkCount != before, time: time)
        } else {
            earDetector.interrupt(); earBelowSince = nil
            frame.ear = BlinkSignal(value: nil, threshold: earThreshold, ready: earSamples >= 15)
        }

        // OCEC：每只眼单独裁片分类，双眼取算术平均
        let started = DispatchTime.now().uptimeNanoseconds
        var probabilities: [Float] = []
        for (index, eye) in [left, right].enumerated() {
            guard let eye = eye else { continue }
            var reading = BlinkEyeReading()
            reading.points = eye.earPoints.map { CGPoint(x: CGFloat($0.x), y: CGFloat($0.y)) }
            reading.ear = BlinkDetector.Companion.shared.ear(points: eye.earPoints)?.floatValue
            if let classifier = classifier,
               let crop = OcecEyeGeometry.crop(points: eye.earPoints, imageWidth: width, imageHeight: height),
               let rgb = OcecEyeClassifier.sample(buffer, crop: crop) {
                let probability = classifier.probability(rgb: rgb)
                probabilities.append(probability)
                reading.crop = CGRect(x: crop.left, y: crop.top, width: crop.width, height: crop.height)
                reading.probability = probability
                reading.image = Self.image(rgb)
            }
            if index == 0 { frame.left = reading } else { frame.right = reading }
        }
        frame.ocecCostMs = Double(DispatchTime.now().uptimeNanoseconds - started) / 1_000_000
        let probability = probabilities.isEmpty ? nil : probabilities.reduce(0, +) / Float(probabilities.count)
        let ocecThreshold = ocecDetector.threshold
        frame.ocec = BlinkSignal(value: eyes ? probability : nil, threshold: ocecThreshold)
        if eyes {
            let before = ocecDetector.blinkCount
            ocecDetector.update(ear: probability.map { KotlinFloat(value: $0) }, timeMs: nil)
            frame.ocecBlinkMs = Self.track(&ocecBelowSince, below: frame.ocec.below, blinked: ocecDetector.blinkCount != before, time: time)
        }
        return frame
    }

    /// 记录低于阈值的起点；本帧判定为一次眨眼时返回闭眼时长。
    private static func track(_ since: inout Int64?, below: Bool, blinked: Bool, time: Int64) -> Int64? {
        if below && since == nil { since = time }
        let duration = blinked ? time - (since ?? time) : nil
        if !below { since = nil }
        return duration
    }

    private static func image(_ rgb: [UInt8]) -> CGImage? {
        guard let provider = CGDataProvider(data: Data(rgb) as CFData) else { return nil }
        return CGImage(width: OcecEyeClassifier.inputWidth, height: OcecEyeClassifier.inputHeight, bitsPerComponent: 8, bitsPerPixel: 24,
                       bytesPerRow: OcecEyeClassifier.inputWidth * 3, space: CGColorSpaceCreateDeviceRGB(),
                       bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.none.rawValue), provider: provider,
                       decode: nil, shouldInterpolate: false, intent: .defaultIntent)
    }

    // MARK: 主线程

    private func ingest(_ batch: [BlinkFrame]) {
        guard let newest = batch.last else { return }
        let recording = round.map { $0.endedAt == nil } ?? false
        for frame in batch {
            if sessionStart == nil { sessionStart = frame.time }
            trace.append(frame)
            if let ms = frame.earBlinkMs { addEvent(.ear, time: frame.time, closedMs: ms) }
            if let ms = frame.ocecBlinkMs { addEvent(.ocec, time: frame.time, closedMs: ms) }
            if recording {
                // CSV 只用数值：不留眼片图像，长时间一轮也不占内存
                var kept = frame; kept.left.image = nil; kept.right.image = nil
                roundFrames.append(kept)
            }
        }
        latest = newest
        if let stale = trace.firstIndex(where: { $0.time >= newest.time - Self.traceWindowMs }), stale > 0 { trace.removeFirst(stale) }
        publishSoon()
    }

    /// 合并刷新：距上次刷新不足 80 ms 时延到届时再刷一次。
    private func publishSoon() {
        guard !publishScheduled else { return }
        publishScheduled = true
        let now = DispatchTime.now().uptimeNanoseconds
        let wait = lastPublish + Self.publishIntervalNs > now ? lastPublish + Self.publishIntervalNs - now : 0
        DispatchQueue.main.asyncAfter(deadline: .now() + .nanoseconds(Int(wait))) { [weak self] in self?.publish() }
    }

    private func publish() {
        publishScheduled = false
        lastPublish = DispatchTime.now().uptimeNanoseconds
        if let newest = latest { fps = Double(trace.filter { $0.time > newest.time - 1_000 }.count) }
        if var current = round, current.endedAt == nil {
            current.counts = counts(from: current.startedAt, to: nil)
            round = current
        }
        objectWillChange.send()
    }

    private func addEvent(_ method: BlinkMethod, time: Int64, closedMs: Int64) {
        var event = BlinkEvent(id: nextEventId, method: method, time: time, closedMs: closedMs)
        nextEventId += 1
        // 与另一判据最早的未配对事件配对（两者都在睁开帧触发，时间差通常只有一两帧）
        if let i = events.firstIndex(where: { $0.method != method && $0.partner == nil && abs($0.time - time) <= Self.pairWindowMs }) {
            events[i].partner = event.id; event.partner = events[i].id
        }
        events.append(event)
        if events.count > 3_000 { events.removeFirst(events.count - 3_000) }
    }

    func status(_ event: BlinkEvent) -> BlinkEventStatus {
        if event.partner != nil { return .both }
        return (latest?.time ?? event.time) - event.time > Self.pairWindowMs ? .only : .pending
    }

    func counts(from start: Int64, to end: Int64?) -> BlinkCounts {
        var counts = BlinkCounts()
        for event in events where event.time >= start && event.time <= (end ?? .max) {
            let paired = event.partner != nil
            switch event.method {
            case .ear: counts.ear += 1; if paired { counts.both += 1 } else { counts.earOnly += 1 }
            case .ocec: counts.ocec += 1; if !paired { counts.ocecOnly += 1 }
            }
        }
        return counts
    }

    func startRound(kind: String, target: Int) {
        guard round == nil || round?.finished == true, let now = latest?.time else { return }
        roundFrames = []
        round = BlinkRound(id: (rounds.first?.id ?? 0) + 1, kind: kind, target: target, startedAt: now)
    }

    /// 结束后再等一个配对窗口，让末尾的事件配上对再统计、落盘。
    func stopRound() {
        guard var current = round, current.endedAt == nil, let now = latest?.time else { return }
        current.endedAt = now
        round = current
        DispatchQueue.main.asyncAfter(deadline: .now() + Double(Self.pairWindowMs + 150) / 1000) { [weak self] in
            guard let self = self, var done = self.round, done.id == current.id else { return }
            done.counts = self.counts(from: done.startedAt, to: done.endedAt)
            done.finished = true
            self.round = done
            self.rounds.insert(done, at: 0)
            self.save(done, frames: self.roundFrames)
        }
    }

    /// 清空曲线与事件（进行中的一轮先结束再清）。
    func clear() {
        guard round == nil || round?.finished == true else { return }
        events = []; trace = []; nextEventId = 1
        publish()
    }

    // MARK: 落盘（文件 App →「我的 iPhone / 眼健康助手 / BlinkCompare」可取）

    static var directory: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("BlinkCompare", isDirectory: true)
    }

    private func save(_ round: BlinkRound, frames: [BlinkFrame]) {
        let roundEvents = events.filter { $0.time >= round.startedAt && $0.time <= (round.endedAt ?? .max) }
        DispatchQueue.global(qos: .utility).async { [weak self] in
            let formatter = DateFormatter(); formatter.dateFormat = "yyyyMMdd-HHmmss"
            let stamp = formatter.string(from: Date())
            func text(_ value: Float?) -> String { value.map { String(format: "%.4f", $0) } ?? "" }
            var lines = ["t_ms,eyes,ear_left,ear_right,ear,ear_threshold,ear_ready,ear_blink_ms,prob_left,prob_right,prob,ocec_blink_ms,ocec_cost_ms"]
            for f in frames {
                lines.append([String(f.time - round.startedAt), f.eyesFound ? "1" : "0", text(f.left.ear), text(f.right.ear),
                              text(f.ear.value), text(f.ear.threshold), f.ear.ready ? "1" : "0", f.earBlinkMs.map { String($0) } ?? "",
                              text(f.left.probability), text(f.right.probability), text(f.ocec.value),
                              f.ocecBlinkMs.map { String($0) } ?? "", String(format: "%.2f", f.ocecCostMs)].joined(separator: ","))
            }
            lines.append("")
            lines.append("# events: method,t_ms,closed_ms,paired")
            for e in roundEvents { lines.append("# \(e.method.rawValue),\(e.time - round.startedAt),\(e.closedMs),\(e.partner != nil ? 1 : 0)") }
            let c = round.counts
            let summaryHeader = "start,kind,target,duration_s,ear,ocec,both,ear_only,ocec_only,file"
            let name = "\(stamp)_\(round.kind).csv"
            let summary = [stamp, round.kind, String(round.target), String(format: "%.1f", Double((round.endedAt ?? round.startedAt) - round.startedAt) / 1000),
                           String(c.ear), String(c.ocec), String(c.both), String(c.earOnly), String(c.ocecOnly), name].joined(separator: ",")
            do {
                let directory = Self.directory
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                let url = directory.appendingPathComponent(name)
                try lines.joined(separator: "\n").write(to: url, atomically: true, encoding: .utf8)
                let summaryURL = directory.appendingPathComponent("summary.csv")
                let old = (try? String(contentsOf: summaryURL, encoding: .utf8)) ?? summaryHeader + "\n"
                try (old + summary + "\n").write(to: summaryURL, atomically: true, encoding: .utf8)
                DispatchQueue.main.async {
                    guard let self = self, let i = self.rounds.firstIndex(where: { $0.id == round.id }) else { return }
                    self.rounds[i].file = url
                }
            } catch {
                DispatchQueue.main.async { self?.error = "保存本轮数据失败：\(error.localizedDescription)" }
            }
        }
    }
}

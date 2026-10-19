import Foundation
import UIKit
import SwiftUI
import EyeScreening

/// 快照只用于界面呈现；所有作答、阶段切换和分级事件进入同一 Kotlin 引擎。
final class EyeRuntime: ObservableObject {
    @Published var state: [String: Any] = [:]
    @Published var error: String?
    let camera = EyeCamera()
    let voice = EyeVoice()
    let feedback = EyeFeedback()
    private var timer: Timer?
    private var lastSnapshot = ""
    private var lastPrompt = ""
    private var lastBase = ""
    private var lastWasDistance = false
    private var lastWasMove = false
    private var distanceRepeat = 0
    private var lastDistanceAt = Date.distantPast
    private var promptWork: DispatchWorkItem?
    private var prepared = false
    private var closed = false
    private var isForeground = true
    private var settingsPresented = false
    private lazy var presentation = FramePresentationMarker { [weak self] id in
        guard let self = self, !self.closed, self.isForeground else { return }
        self.controller.markPresented(trialId: id)
    }
    let kind: String
    private(set) lazy var controller = MobileScreeningController(systemEvent: { [weak self] event in
        DispatchQueue.main.async {
            guard let self = self, !self.closed else { return }
            if event == "camera:start" { self.camera.start() }
            else if event == "camera:stop" { self.camera.stop() }
        }
    }, recordSaved: { [weak self] name, text in
        do {
            let directory = Self.recordsDirectory
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            var record = Self.object(text)
            let savedScale = UserDefaults.standard.float(forKey: "eye.distanceScale")
            record["deviceCalibration"] = ["distanceScale": savedScale > 0 ? savedScale : 1,
                "distanceCalibrated": UserDefaults.standard.bool(forKey: "eye.distanceCalibrated"),
                "pixelsPerMm": UserDefaults.standard.float(forKey: "eye.pixelsPerMm"), "systemVersion": UIDevice.current.systemVersion]
            let data = try JSONSerialization.data(withJSONObject: record, options: [.sortedKeys])
            try data.write(to: directory.appendingPathComponent(name), options: .atomic)
        } catch { DispatchQueue.main.async { self?.error = "保存记录失败：\(error.localizedDescription)" } }
    })
    static var recordsDirectory: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Screening", isDirectory: true)
    }
    init(kind: String) {
        self.kind = kind
        camera.onFrame = { [weak self] face, width, height, focal, time, brightness in
            guard let self = self else { return }
            DispatchQueue.main.async {
                guard !self.closed else { return }
                self.controller.cameraFace(face: face, imageWidth: width, imageHeight: height,
                    focalLengthPx: focal, timeMs: time, brightness: brightness)
            }
        }
        camera.onError = { [weak self] message in
            DispatchQueue.main.async { self?.error = message; self?.controller.cameraFailed(message: message) }
        }
        voice.onAnswer = { [weak self] text, echo in Int(self?.controller.voiceAnswer(text: text, echoOf: echo) ?? 0) }
    }
    func prepare(pixelsPerMm: Float) {
        guard !prepared else { return }
        prepared = true
        let screen = UIScreen.main
        controller.configureDisplay(widthPixels: Int32(screen.bounds.width * screen.scale),
            heightPixels: Int32(screen.bounds.height * screen.scale), pixelsPerMm: pixelsPerMm)
        controller.selectTest(name: kind)
        voice.prepareForTest(); feedback.prepare()
        let task = Timer(timeInterval: 1.0 / 30.0, repeats: true) { [weak self] _ in self?.refresh() }
        timer = task; RunLoop.main.add(task, forMode: .common)
        refresh()
    }
    func refresh() {
        guard prepared, !closed else { return }
        let cues = controller.feedbackJson(enabled: isForeground && !settingsPresented && (feedback.soundEnabled || feedback.hapticsEnabled))
        if let data = cues.data(using: .utf8), let events = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] {
            for event in events {
                if let name = event["name"] as? String, let sound = event["sound"] as? String,
                    let pause = event["microphonePauseMs"] as? Int {
                    feedback.play(name: name, sound: sound, microphonePauseMs: pause, voice: voice)
                }
            }
        }
        let snapshot = controller.snapshotJson()
        if snapshot != lastSnapshot {
            lastSnapshot = snapshot
            state = Self.object(snapshot)
        }
        speakPrompts()
        voice.setListening(isForeground && !settingsPresented && bool("voiceActive"))
    }
    /// 与安卓同一套播报：距离偏离优先，持续偏离每 6 秒换一种说法；回到合适距离先肯定一句，主提示有变化再接着说。
    private func speakPrompts() {
        let phase = string("phase")
        let base: String
        if phase == "Profile" || phase == "PROFILE" { base = kind == "STRABISMUS" ? "这次给谁测？" : "这次测谁？" }
        else if phase == "Setup" || phase == "ENVIRONMENT" {
            base = kind == "STRABISMUS" ? "请正对屏幕，保持三十三厘米。" : "请正对屏幕，保持四十厘米。"
        } else if bool("calibrationPaused") {
            // 与安卓一致：校准暂停时播报暂停原因
            base = string("warning")
        } else if phase == "COVER" && !bool("guidePending") {
            // 与安卓同一读法：保持期间不重复播报，保持结束只读下一动作。
            base = string("coverPrompt")
        } else { base = string("instruction") }
        let zone = (state["distanceZone"] as? NSNumber)?.int32Value ?? 0
        // 换目标距离途中：播报「请移动到 xx 厘米处」（持续未到位每 6 秒重复），到位后由提示音代替肯定
        let moveTo = (state["distanceMoveToCm"] as? NSNumber)?.intValue
        let distanceText = moveTo.map { "请移动到 \($0) 厘米处。" }
            ?? (zone != 0 ? controller.distancePrompt(zone: zone, count: 0) : nil)
        let prompt = distanceText ?? base
        if !prompt.isEmpty && prompt != lastPrompt {
            lastPrompt = prompt; promptWork?.cancel()
            let isDistance = distanceText != nil
            let isMove = moveTo != nil
            // 「请移动到 xx 厘米处」到位后不说肯定（提示音代替），主提示没变也不重读：远近切换每题都换距离
            if !isDistance && lastWasMove && base == lastBase { lastWasMove = false; return }
            let text = isDistance ? prompt
                : lastWasDistance ? controller.distanceOkPrompt + (base != lastBase ? base : "") : base
            schedule(text, interrupt: isDistance) { [weak self] in
                guard let self = self else { return }
                self.lastWasDistance = isDistance && !isMove
                self.lastWasMove = isMove
                if isDistance { self.distanceRepeat = 1; self.lastDistanceAt = Date() } else { self.lastBase = base }
            }
        } else if let current = distanceText, lastWasDistance || lastWasMove, Date().timeIntervalSince(lastDistanceAt) >= 6 {
            lastDistanceAt = Date()
            if let again = moveTo != nil ? Optional(current) : controller.distancePrompt(zone: zone, count: Int32(distanceRepeat)) {
                distanceRepeat += 1; schedule(again, delay: 0) {}
            }
        }
    }
    /// 状态稳定 650 ms 后才开口，避免测距波动不断打断；后台、设置菜单期间不播报。
    private func schedule(_ text: String, delay: Double = 0.65, interrupt: Bool = true, spoken: @escaping () -> Void) {
        promptWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self = self, self.isForeground, !self.closed, !self.settingsPresented else { return }
            self.voice.speak(self.controller.speechText(text: text), interrupt: interrupt); spoken()
        }
        promptWork = work; DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
    }
    func string(_ key: String) -> String { state[key] as? String ?? "" }
    func bool(_ key: String) -> Bool { state[key] as? Bool ?? false }
    func number(_ key: String) -> Double { (state[key] as? NSNumber)?.doubleValue ?? 0 }
    func act(_ name: String) { controller.action(name: name); refresh() }
    func answer(_ direction: Int) { controller.answer(direction: Int32(direction), byVoice: false); refresh() }
    func schedulePresented(_ id: Int32) { if id > 0 { presentation.schedule(id) } }
    /// 这位成员做当前测试还缺的资料项（共用规则）。
    func missing(_ s: SubjectProfileItem) -> [String] {
        (try? JSONSerialization.jsonObject(with: Data(controller.missingJson(profileJson: s.jsonText).utf8))) as? [String] ?? []
    }
    /// 选定被测者：资料带入共用引擎；老视还需选戴镜方式后 [proceed]。
    func useSubject(_ s: SubjectProfileItem) {
        error = nil
        _ = controller.useSubject(profileJson: s.jsonText)
        refresh()
    }
    func proceed(mode: String) {
        controller.setMode(mode: mode)
        if !controller.proceedFromProfile() { error = "请先在资料里填好必需项，并选择这次戴哪副眼镜。" }
        refresh()
    }
    func interrupt() {
        isForeground = false
        feedback.stop(); _ = controller.feedbackJson(enabled: false)
        promptWork?.cancel(); presentation.cancel(); voice.stop(); camera.stop(); controller.interrupt(); refresh()
    }
    func resume() {
        isForeground = true; _ = controller.feedbackJson(enabled: false); lastPrompt = ""
        voice.prepareForTest(); feedback.prepare(); controller.resume(); refresh()
    }
    func setSettingsPresented(_ value: Bool) {
        settingsPresented = value
        if value { promptWork?.cancel(); voice.stop(); feedback.stop() }
        else { lastPrompt = ""; lastSnapshot = "" }
        _ = controller.feedbackJson(enabled: false)
        refresh()
    }
    func close() {
        guard !closed else { return }
        closed = true; timer?.invalidate(); promptWork?.cancel(); presentation.cancel(); voice.close(); feedback.stop(); camera.stop(); controller.close()
    }
    static func object(_ text: String) -> [String: Any] {
        guard let data = text.data(using: .utf8) else { return [:] }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    }
}

/// 与安卓的两次 withFrameNanos 对齐：经过两个显示刷新回调后才标记题目已呈现。
private final class FramePresentationMarker: NSObject {
    private let presented: (Int32) -> Void
    private var link: CADisplayLink?
    private var id: Int32 = 0
    private var ticks = 0
    init(_ callback: @escaping (Int32) -> Void) { presented = callback; super.init() }
    func schedule(_ id: Int32) {
        cancel(); self.id = id; ticks = 0
        let next = CADisplayLink(target: self, selector: #selector(tick))
        link = next; next.add(to: .main, forMode: .common)
    }
    @objc private func tick() { ticks += 1; if ticks >= 2 { let trial = id; cancel(); presented(trial) } }
    func cancel() { link?.invalidate(); link = nil }
}

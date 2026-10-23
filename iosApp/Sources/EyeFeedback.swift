import Foundation
import Combine
import AVFoundation
import CoreHaptics
import UIKit

/// 测试期间唯一的音频会话策略：播报、音效、收听共用，测试中途不切换类别，避免切换时打断正在播放的语音。
/// 与安卓一致：音效与播报走媒体音量，不被静音键阻断；收听时仍允许触觉与音效。
enum EyeAudioSession {
    private static var recording: Bool?
    /// 停止录音引擎、停用会话这类可能阻塞数秒的操作放在后台串行队列，退出测试时主线程不被卡住。
    static let teardown = DispatchQueue(label: "eye.audio.teardown", qos: .userInitiated)
    /// 每次激活加一：排在后台的停用若晚于新的激活（刚退出又马上进入下一项测试），就不再执行。
    private static let lock = NSLock()
    private static var activation = 0

    static func activate(recording wanted: Bool) {
        lock.lock(); activation += 1; lock.unlock()
        let session = AVAudioSession.sharedInstance()
        do {
            if recording != wanted {
                if wanted {
                    try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .mixWithOthers, .allowBluetoothA2DP])
                } else {
                    try session.setCategory(.playback, mode: .spokenAudio, options: [.mixWithOthers])
                }
                recording = wanted
            }
            try session.setAllowHapticsAndSystemSoundsDuringRecording(true)
            try session.setActive(true)
        } catch { recording = nil }
    }

    /// 退出测试时调用：在后台停用会话，让其他应用的声音恢复。[engine] 为要先停下的录音引擎（停用前必须停止 I/O，否则会阻塞）。
    static func deactivate(after engine: AVAudioEngine? = nil, removeTap: Bool = false) {
        recording = nil
        lock.lock(); let ticket = activation; lock.unlock()
        teardown.async {
            if let engine = engine {
                if engine.isRunning { engine.stop() }
                if removeTap { engine.inputNode.removeTap(onBus: 0) }
            }
            lock.lock(); let current = activation; lock.unlock()
            guard current == ticket else { return }
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }
    }
}

/// 触觉与安卓同一分级：作答与节拍最轻、阶段切换次之、提醒两下、完成渐强三下。
/// 用 Core Haptics 播放（相机与收听期间也能感到），音量按事件分级：高频作答 0.5、其余 0.7。
final class EyeFeedback: ObservableObject {
    @Published var soundEnabled = UserDefaults.standard.object(forKey: "eye.soundEnabled") as? Bool ?? true {
        didSet { UserDefaults.standard.set(soundEnabled, forKey: "eye.soundEnabled"); if !soundEnabled { stop() } }
    }
    @Published var hapticsEnabled = UserDefaults.standard.object(forKey: "eye.hapticsEnabled") as? Bool ?? true {
        didSet { UserDefaults.standard.set(hapticsEnabled, forKey: "eye.hapticsEnabled"); if hapticsEnabled { prepare() } }
    }
    /// 音效音量 0–1，乘在按事件分级的音量上。
    @Published var soundVolume = UserDefaults.standard.object(forKey: "eye.soundVolume") as? Double ?? 1 {
        didSet { UserDefaults.standard.set(soundVolume, forKey: "eye.soundVolume") }
    }
    /// 震动强度 0.1–1，默认 0.6 即原有分级；调高时按比例加强（上限 1）。
    @Published var hapticStrength = UserDefaults.standard.object(forKey: "eye.hapticStrength") as? Double ?? EyeFeedback.defaultStrength {
        didSet { UserDefaults.standard.set(hapticStrength, forKey: "eye.hapticStrength") }
    }
    static let defaultStrength = 0.6
    private var players: [String: AVAudioPlayer] = [:]
    private var engine: CHHapticEngine?
    private let fallback = UIImpactFeedbackGenerator(style: .medium)

    init() { prepare() }

    /// 预热触觉引擎，避免第一下延迟；引擎被系统停掉或重置后在下次播放时自动重启。
    func prepare() {
        guard hapticsEnabled else { return }
        guard CHHapticEngine.capabilitiesForHardware().supportsHaptics else { fallback.prepare(); return }
        if engine == nil {
            engine = try? CHHapticEngine()
            engine?.playsHapticsOnly = true
            engine?.isAutoShutdownEnabled = true
            engine?.resetHandler = { [weak self] in try? self?.engine?.start() }
        }
        try? engine?.start()
    }

    func play(name: String, sound: String, microphonePauseMs: Int, voice: EyeVoice) {
        if hapticsEnabled { haptic(name) }
        // 音效期间不再暂停收听：停止再启动会丢掉这段时间说的话
        guard soundEnabled, let player = player(sound) else { return }
        player.volume = Float((name == "ANSWER" || name == "TICK" ? 0.5 : 0.7) * soundVolume)
        player.currentTime = 0
        player.play()
    }

    private func player(_ sound: String) -> AVAudioPlayer? {
        if let cached = players[sound] { return cached }
        guard let url = Bundle.main.url(forResource: sound, withExtension: "wav"),
              let created = try? AVAudioPlayer(contentsOf: url) else { return nil }
        created.prepareToPlay()
        players[sound] = created
        return created
    }

    /// （相对时刻秒, 强度, 锐度）；与安卓的振动分级逐项对应。
    private static func pattern(_ name: String) -> [(TimeInterval, Float, Float)] {
        switch name {
        case "TICK": return [(0, 0.45, 0.6)]
        case "ANSWER": return [(0, 0.55, 0.5)]
        case "TARGET": return [(0, 0.6, 0.7)]
        case "PHASE": return [(0, 0.7, 0.5), (0.07, 0.5, 0.7)]
        case "RECOVER": return [(0, 0.5, 0.4), (0.06, 0.7, 0.5)]
        case "START": return [(0, 0.9, 0.6)]
        case "WARNING", "RETRY": return [(0, 0.9, 0.8), (0.14, 0.9, 0.8)]
        case "COMPLETE": return [(0, 0.5, 0.4), (0.11, 0.75, 0.5), (0.22, 1, 0.6)]
        default: return [(0, 0.5, 0.5)]
        }
    }

    private func haptic(_ name: String) {
        let scale = Float(hapticStrength / Self.defaultStrength)
        let steps = Self.pattern(name).map { ($0.0, min(1, max(0.05, $0.1 * scale)), $0.2) }
        guard let engine = engine else {
            fallback.impactOccurred(intensity: CGFloat(steps.map { $0.1 }.max() ?? 0.5)); fallback.prepare(); return
        }
        let events = steps.map { time, intensity, sharpness in
            CHHapticEvent(eventType: .hapticTransient, parameters: [
                CHHapticEventParameter(parameterID: .hapticIntensity, value: intensity),
                CHHapticEventParameter(parameterID: .hapticSharpness, value: sharpness),
            ], relativeTime: time)
        }
        do {
            try engine.start()
            try engine.makePlayer(with: CHHapticPattern(events: events, parameters: [])).start(atTime: CHHapticTimeImmediate)
        } catch { fallback.impactOccurred(); fallback.prepare() }
    }

    func stop() {
        players.values.forEach { $0.stop() }
        engine?.stop(completionHandler: nil)
    }
    deinit { engine?.stop(completionHandler: nil) }
}

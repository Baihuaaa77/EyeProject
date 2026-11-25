import AVFoundation
import CoreHaptics
import Foundation
import UIKit

/// StrabismusLab 独立中文语音提示、清脆提示音与分级触觉反馈引擎。
/// 复用仓库 `shared/feedback/res/raw/*.wav` 统一音频资产，走 `.playback + .spokenAudio` 音频会话，不受侧边静音键阻断。
@MainActor
final class StrabismusLabFeedback: NSObject, ObservableObject, AVSpeechSynthesizerDelegate {
    @Published var voiceEnabled: Bool = UserDefaults.standard.object(forKey: "lab.voiceEnabled") as? Bool ?? true {
        didSet {
            UserDefaults.standard.set(voiceEnabled, forKey: "lab.voiceEnabled")
            if !voiceEnabled { stopSpeech() }
        }
    }
    @Published var soundHapticsEnabled: Bool = UserDefaults.standard.object(forKey: "lab.soundHapticsEnabled") as? Bool ?? true {
        didSet {
            UserDefaults.standard.set(soundHapticsEnabled, forKey: "lab.soundHapticsEnabled")
            if soundHapticsEnabled { prepareHaptics() }
        }
    }

    enum Cue {
        case start, phase, confirm, tick, count, warning, complete

        var wavName: String {
            switch self {
            case .start: return "eye_start"
            case .phase: return "eye_phase"
            case .confirm: return "eye_answer"
            case .tick: return "eye_tick"
            case .count: return "eye_count"
            case .warning: return "eye_warning"
            case .complete: return "eye_complete"
            }
        }

        var volume: Float {
            switch self {
            case .tick, .count: return 0.55
            default: return 0.80
            }
        }
    }

    private let synthesizer = AVSpeechSynthesizer()
    private var players: [String: AVAudioPlayer] = [:]
    private var hapticEngine: CHHapticEngine?
    private let impactLight = UIImpactFeedbackGenerator(style: .light)
    private let impactMedium = UIImpactFeedbackGenerator(style: .medium)
    private let selectionGen = UISelectionFeedbackGenerator()
    private let notifyGen = UINotificationFeedbackGenerator()
    private var queuedText: String?

    override init() {
        super.init()
        synthesizer.delegate = self
        prepareHaptics()
    }

    func activateSession() {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playback, mode: .spokenAudio, options: [.mixWithOthers, .duckOthers])
            try session.setActive(true)
        } catch {}
        prepareHaptics()
    }

    func deactivateSession() {
        stopSpeech()
        DispatchQueue.global(qos: .utility).async {
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }
    }

    // MARK: - 轻量 UI 交互微反馈

    func tap() {
        guard soundHapticsEnabled else { return }
        impactLight.impactOccurred()
        impactLight.prepare()
    }

    func toggle() {
        guard soundHapticsEnabled else { return }
        selectionGen.selectionChanged()
        selectionGen.prepare()
    }

    func success() {
        guard soundHapticsEnabled else { return }
        notifyGen.notificationOccurred(.success)
        notifyGen.prepare()
    }

    // MARK: - 科研流程音效 + CoreHaptics 触觉

    func play(_ cue: Cue) {
        guard soundHapticsEnabled else { return }
        playHaptic(cue)
        guard let player = audioPlayer(named: cue.wavName) else { return }
        player.volume = cue.volume
        player.currentTime = 0
        player.play()
    }

    // MARK: - 中文语音提示

    func speak(_ text: String, interrupt: Bool = true) {
        guard voiceEnabled, !text.isEmpty else { return }
        if !interrupt && synthesizer.isSpeaking {
            queuedText = text
            return
        }
        queuedText = nil
        synthesizer.stopSpeaking(at: .immediate)
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = bestChineseVoice()
        utterance.rate = min(AVSpeechUtteranceMaximumSpeechRate, AVSpeechUtteranceDefaultSpeechRate * 1.06)
        utterance.volume = 1.0
        utterance.preUtteranceDelay = 0.04
        synthesizer.speak(utterance)
    }

    func stopSpeech() {
        queuedText = nil
        synthesizer.stopSpeaking(at: .immediate)
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        Task { @MainActor [weak self] in
            guard let self = self, let next = self.queuedText else { return }
            self.queuedText = nil
            self.speak(next, interrupt: true)
        }
    }

    private func bestChineseVoice() -> AVSpeechSynthesisVoice? {
        let candidates = AVSpeechSynthesisVoice.speechVoices()
            .filter { $0.language == "zh-CN" && !$0.identifier.contains("eloquence") }
            .sorted { a, b in
                func rank(_ v: AVSpeechSynthesisVoice) -> Int {
                    switch v.quality { case .premium: return 3; case .enhanced: return 2; default: return 1 }
                }
                return rank(a) > rank(b)
            }
        return candidates.first ?? AVSpeechSynthesisVoice(language: "zh-CN")
    }

    private func audioPlayer(named name: String) -> AVAudioPlayer? {
        if let cached = players[name] { return cached }
        guard let url = Bundle.main.url(forResource: name, withExtension: "wav"),
              let p = try? AVAudioPlayer(contentsOf: url) else { return nil }
        p.prepareToPlay()
        players[name] = p
        return p
    }

    private func prepareHaptics() {
        guard soundHapticsEnabled else { return }
        guard CHHapticEngine.capabilitiesForHardware().supportsHaptics else {
            impactMedium.prepare()
            return
        }
        if hapticEngine == nil {
            hapticEngine = try? CHHapticEngine()
            hapticEngine?.playsHapticsOnly = true
            hapticEngine?.isAutoShutdownEnabled = true
            hapticEngine?.resetHandler = { [weak self] in try? self?.hapticEngine?.start() }
        }
        try? hapticEngine?.start()
    }

    private func playHaptic(_ cue: Cue) {
        let steps: [(TimeInterval, Float, Float)] = {
            switch cue {
            case .tick, .count: return [(0, 0.42, 0.6)]
            case .confirm: return [(0, 0.65, 0.65)]
            case .start: return [(0, 0.90, 0.65)]
            case .phase: return [(0, 0.70, 0.55), (0.08, 0.55, 0.7)]
            case .warning: return [(0, 0.90, 0.85), (0.14, 0.90, 0.85)]
            case .complete: return [(0, 0.55, 0.45), (0.11, 0.78, 0.55), (0.22, 1.0, 0.65)]
            }
        }()
        guard let engine = hapticEngine else {
            impactMedium.impactOccurred()
            return
        }
        let events = steps.map { t, intensity, sharpness in
            CHHapticEvent(eventType: .hapticTransient, parameters: [
                CHHapticEventParameter(parameterID: .hapticIntensity, value: intensity),
                CHHapticEventParameter(parameterID: .hapticSharpness, value: sharpness),
            ], relativeTime: t)
        }
        do {
            try engine.start()
            try engine.makePlayer(with: CHHapticPattern(events: events, parameters: [])).start(atTime: CHHapticTimeImmediate)
        } catch {
            impactMedium.impactOccurred()
        }
    }
}

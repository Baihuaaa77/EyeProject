import Foundation
import Combine
import UIKit
import AudioToolbox

/// 系统 UI 音效沿用静音与系统音量；触觉使用轻量原生反馈，不播放长震动。
final class EyeFeedback: ObservableObject {
    @Published var soundEnabled = UserDefaults.standard.object(forKey: "eye.soundEnabled") as? Bool ?? true {
        didSet { UserDefaults.standard.set(soundEnabled, forKey: "eye.soundEnabled"); if !soundEnabled { stop() } }
    }
    @Published var hapticsEnabled = UserDefaults.standard.object(forKey: "eye.hapticsEnabled") as? Bool ?? true {
        didSet { UserDefaults.standard.set(hapticsEnabled, forKey: "eye.hapticsEnabled") }
    }
    private var sounds: [String: SystemSoundID] = [:]
    private let soft = UIImpactFeedbackGenerator(style: .soft)
    private let light = UIImpactFeedbackGenerator(style: .light)
    private let warning = UIImpactFeedbackGenerator(style: .medium)
    func play(name: String, sound: String, microphonePauseMs: Int, voice: EyeVoice) {
        if hapticsEnabled {
            switch name {
            case "WARNING", "RETRY": warning.impactOccurred(intensity: 0.4)
            case "START", "COMPLETE", "RECOVER": light.impactOccurred(intensity: 0.55)
            default: soft.impactOccurred(intensity: 0.3)
            }
        }
        guard soundEnabled else { return }
        if sounds[sound] == nil, let url = Bundle.main.url(forResource: sound, withExtension: "wav") {
            var id: SystemSoundID = 0
            if AudioServicesCreateSystemSoundID(url as CFURL, &id) == kAudioServicesNoError { sounds[sound] = id }
        }
        guard let id = sounds[sound] else { return }
        voice.pauseForFeedback(milliseconds: microphonePauseMs)
        AudioServicesPlaySystemSound(id)
    }
    func stop() { for id in sounds.values { AudioServicesDisposeSystemSoundID(id) }; sounds.removeAll() }
    deinit { stop() }
}

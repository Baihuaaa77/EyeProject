import Foundation
import Combine
import UIKit
import AudioToolbox

/// 系统 UI 音效沿用静音与系统音量；触觉与安卓同一分级：作答与节拍最轻、阶段切换次之、提醒两下、完成渐强。
final class EyeFeedback: ObservableObject {
    @Published var soundEnabled = UserDefaults.standard.object(forKey: "eye.soundEnabled") as? Bool ?? true {
        didSet { UserDefaults.standard.set(soundEnabled, forKey: "eye.soundEnabled"); if !soundEnabled { stop() } }
    }
    @Published var hapticsEnabled = UserDefaults.standard.object(forKey: "eye.hapticsEnabled") as? Bool ?? true {
        didSet { UserDefaults.standard.set(hapticsEnabled, forKey: "eye.hapticsEnabled"); if hapticsEnabled { prepare() } }
    }
    private var sounds: [String: SystemSoundID] = [:]
    private var pendingHaptics: [DispatchWorkItem] = []
    private let selection = UISelectionFeedbackGenerator()
    private let soft = UIImpactFeedbackGenerator(style: .soft)
    private let light = UIImpactFeedbackGenerator(style: .light)
    private let rigid = UIImpactFeedbackGenerator(style: .rigid)
    private let notice = UINotificationFeedbackGenerator()

    init() { prepare() }

    /// 预热触觉引擎，避免第一下延迟。
    func prepare() {
        guard hapticsEnabled else { return }
        selection.prepare(); soft.prepare(); light.prepare(); rigid.prepare(); notice.prepare()
    }

    func play(name: String, sound: String, microphonePauseMs: Int, voice: EyeVoice) {
        if hapticsEnabled { haptic(name) }
        guard soundEnabled else { return }
        if sounds[sound] == nil, let url = Bundle.main.url(forResource: sound, withExtension: "wav") {
            var id: SystemSoundID = 0
            if AudioServicesCreateSystemSoundID(url as CFURL, &id) == kAudioServicesNoError { sounds[sound] = id }
        }
        guard let id = sounds[sound] else { return }
        voice.pauseForFeedback(milliseconds: microphonePauseMs)
        AudioServicesPlaySystemSound(id)
    }

    private func haptic(_ name: String) {
        cancelHaptics()
        switch name {
        case "TICK": selection.selectionChanged()
        case "ANSWER": soft.impactOccurred(intensity: 0.5)
        case "TARGET": rigid.impactOccurred(intensity: 0.45)
        case "PHASE": light.impactOccurred(intensity: 0.6); later(0.06) { $0.rigid.impactOccurred(intensity: 0.4) }
        case "RECOVER": soft.impactOccurred(intensity: 0.4); later(0.05) { $0.light.impactOccurred(intensity: 0.6) }
        case "START": light.impactOccurred(intensity: 0.85)
        case "WARNING", "RETRY": notice.notificationOccurred(.warning)
        case "COMPLETE": notice.notificationOccurred(.success)
        default: soft.impactOccurred(intensity: 0.4)
        }
        prepare()
    }

    private func later(_ seconds: Double, _ action: @escaping (EyeFeedback) -> Void) {
        let work = DispatchWorkItem { [weak self] in if let self = self, self.hapticsEnabled { action(self) } }
        pendingHaptics.append(work)
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: work)
    }

    private func cancelHaptics() { pendingHaptics.forEach { $0.cancel() }; pendingHaptics.removeAll() }

    func stop() { cancelHaptics(); for id in sounds.values { AudioServicesDisposeSystemSoundID(id) }; sounds.removeAll() }
    deinit { stop() }
}

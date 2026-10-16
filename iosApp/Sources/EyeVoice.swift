import Foundation
import Combine
import AVFoundation
import Speech

/// 三项测试使用同一发声人；播报期间与结束后 600 ms 停止收听，丢弃旧识别会话。
final class EyeVoice: NSObject, ObservableObject, AVSpeechSynthesizerDelegate {
    @Published var enabled = UserDefaults.standard.object(forKey: "eye.speechEnabled") as? Bool ?? true {
        didSet { UserDefaults.standard.set(enabled, forKey: "eye.speechEnabled"); if !enabled { stop() } }
    }
    /// 与安卓一致默认开启；首次进入测试时申请麦克风与语音识别权限，未授权则回退按钮作答。
    @Published var answersEnabled = UserDefaults.standard.object(forKey: "eye.answersEnabled") as? Bool ?? true {
        didSet {
            UserDefaults.standard.set(answersEnabled, forKey: "eye.answersEnabled")
            if answersEnabled { requestPermission() } else { stopListening(); EyeAudioSession.activate(recording: false) }
        }
    }
    /// 用户在设置里选的发声人；为空时自动挑选。
    @Published var voiceIdentifier = UserDefaults.standard.string(forKey: "eye.voiceIdentifier") ?? "" {
        didSet { UserDefaults.standard.set(voiceIdentifier, forKey: "eye.voiceIdentifier") }
    }
    @Published var volume = UserDefaults.standard.object(forKey: "eye.speechVolume") as? Double ?? 1 {
        didSet { UserDefaults.standard.set(volume, forKey: "eye.speechVolume") }
    }
    /// 语速倍率 0.7–1.4，乘在系统默认语速上。
    @Published var rate = UserDefaults.standard.object(forKey: "eye.speechRate") as? Double ?? 1.05 {
        didSet { UserDefaults.standard.set(rate, forKey: "eye.speechRate") }
    }
    @Published private(set) var status = ""
    /// 正在说话时到来的非紧急提示：等这句说完再说最新的一条，避免逐句打断。
    private var queued: String?
    var onAnswer: ((String) -> Bool)?
    private let synthesizer = AVSpeechSynthesizer()
    private let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "zh-CN"))
    private let audio = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var hasTap = false
    private var authorized = false
    private var wanted = false
    private var generation = 0
    private var cooldown = Date.distantPast
    private var retryAfter = Date.distantPast

    override init() {
        super.init(); synthesizer.delegate = self
        authorized = SFSpeechRecognizer.authorizationStatus() == .authorized && AVAudioSession.sharedInstance().recordPermission == .granted
    }

    /// 测试开始时调用：按是否语音作答确定会话类别，未决定的权限此时申请。
    func prepareForTest() {
        EyeAudioSession.activate(recording: answersEnabled && authorized)
        if answersEnabled && !authorized { requestPermission() } else { describeRecognizer() }
    }

    func requestPermission() {
        SFSpeechRecognizer.requestAuthorization { [weak self] speech in
            AVAudioSession.sharedInstance().requestRecordPermission { microphone in
                DispatchQueue.main.async {
                    guard let self = self else { return }
                    self.authorized = speech == .authorized && microphone
                    EyeAudioSession.activate(recording: self.answersEnabled && self.authorized)
                    if self.authorized { self.describeRecognizer() }
                    else { self.status = "未获得麦克风或语音识别权限，请在系统设置中允许，或用方向按钮作答。" }
                }
            }
        }
    }

    private func describeRecognizer() {
        guard answersEnabled, authorized else { return }
        status = recognizer?.supportsOnDeviceRecognition == true ? "语音作答已就绪（离线识别，录音不离开手机）。"
            : "本机未下载离线中文识别，将使用苹果系统在线识别；也可以直接用方向按钮。"
    }

    func pauseForFeedback(milliseconds: Int) {
        stopListening()
        cooldown = max(cooldown, Date().addingTimeInterval(Double(milliseconds) / 1000))
    }

    /// [interrupt] 为 true 时（距离提醒）立即打断当前播报；否则等当前这句说完。
    func speak(_ text: String, interrupt: Bool = true) {
        guard enabled, !text.isEmpty else { return }
        if !interrupt && synthesizer.isSpeaking { queued = text; return }
        queued = nil
        stopListening()
        synthesizer.stopSpeaking(at: .immediate)
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = selectedVoice
        utterance.rate = min(AVSpeechUtteranceMaximumSpeechRate, AVSpeechUtteranceDefaultSpeechRate * Float(rate))
        utterance.volume = Float(volume)
        utterance.pitchMultiplier = 1
        utterance.preUtteranceDelay = 0.05
        synthesizer.speak(utterance)
    }

    /// 可选的普通话发声人：去掉 Eloquence 合成器与趣味声音（机器腔、怪声），高品质排在前面。
    static var chineseVoices: [AVSpeechSynthesisVoice] {
        AVSpeechSynthesisVoice.speechVoices()
            .filter { $0.language == "zh-CN" && !$0.identifier.contains("eloquence") && !isNovelty($0) }
            .sorted { Self.rank($0) != Self.rank($1) ? Self.rank($0) > Self.rank($1) : $0.name < $1.name }
    }

    private var selectedVoice: AVSpeechSynthesisVoice? {
        if !voiceIdentifier.isEmpty, let chosen = AVSpeechSynthesisVoice(identifier: voiceIdentifier) { return chosen }
        let voices = Self.chineseVoices
        // 同等音质下优先系统默认的普通话声音（通常为婷婷），其次排序第一的声音。
        let system = AVSpeechSynthesisVoice(language: "zh-CN")
        if let best = voices.first, let system = system, Self.rank(system) >= Self.rank(best),
           voices.contains(where: { $0.identifier == system.identifier }) {
            return system
        }
        return voices.first ?? system
    }

    static func rank(_ voice: AVSpeechSynthesisVoice) -> Int {
        switch voice.quality { case .premium: return 3; case .enhanced: return 2; default: return 1 }
    }

    private static func isNovelty(_ voice: AVSpeechSynthesisVoice) -> Bool {
        if #available(iOS 17.0, *) { return voice.voiceTraits.contains(.isNoveltyVoice) || voice.voiceTraits.contains(.isPersonalVoice) }
        return false
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        cooldown = Date().addingTimeInterval(0.6)
        if let next = queued { queued = nil; DispatchQueue.main.async { self.speak(next) } }
    }
    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) { cooldown = Date().addingTimeInterval(0.6) }

    func setListening(_ value: Bool) {
        wanted = value
        guard wanted, answersEnabled, authorized, !synthesizer.isSpeaking, Date() >= cooldown, Date() >= retryAfter else {
            if task != nil { stopListening() }; return
        }
        if task == nil { startListening() }
    }

    private func startListening() {
        guard let recognizer = recognizer, recognizer.isAvailable else { return }
        do {
            EyeAudioSession.activate(recording: true)
            let next = SFSpeechAudioBufferRecognitionRequest()
            next.shouldReportPartialResults = true
            // 优先设备端识别；本机没有离线中文模型时使用系统在线识别（与安卓回退系统识别服务一致）。
            next.requiresOnDeviceRecognition = recognizer.supportsOnDeviceRecognition
            next.taskHint = .confirmation
            next.contextualStrings = ["上", "下", "左", "右", "看不清", "跳过", "模糊", "开始", "好了"]
            request = next
            let input = audio.inputNode
            let format = input.outputFormat(forBus: 0)
            guard format.sampleRate > 0 && format.channelCount > 0 else { throw NSError(domain: "EyeVoice", code: 1) }
            input.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in next.append(buffer) }
            hasTap = true; audio.prepare(); try audio.start()
            generation += 1; let ticket = generation
            task = recognizer.recognitionTask(with: next) { [weak self] result, error in
                DispatchQueue.main.async {
                    guard let self = self, ticket == self.generation, self.wanted, !self.synthesizer.isSpeaking else { return }
                    if let result = result {
                        let text = result.bestTranscription.formattedString
                        // 由共同解析器判断完整短指令，部分结果先完成一次作答，再启动新会话。
                        if self.onAnswer?(text) == true {
                            self.stopListening(); self.retryAfter = Date().addingTimeInterval(0.3)
                            self.status = "听到：\(text)"
                            return
                        }
                    }
                    if error != nil || result?.isFinal == true {
                        self.stopListening(); self.retryAfter = Date().addingTimeInterval(error == nil ? 0.2 : 1)
                        if error != nil { self.status = "语音暂未识别，请重说或用按钮" }
                    }
                }
            }
            status = "正在听：说上、下、左、右；看不清就说「跳过」"
        } catch { stopListening(); retryAfter = Date().addingTimeInterval(2); status = "麦克风启动失败，请使用按钮或重试" }
    }

    func stopListening() {
        generation += 1; task?.cancel(); task = nil; request?.endAudio(); request = nil
        if audio.isRunning { audio.stop() }
        if hasTap { audio.inputNode.removeTap(onBus: 0); hasTap = false }
    }
    func stop() { queued = nil; synthesizer.stopSpeaking(at: .immediate); stopListening(); cooldown = Date().addingTimeInterval(0.6) }
    func close() { wanted = false; stop(); EyeAudioSession.deactivate() }
}

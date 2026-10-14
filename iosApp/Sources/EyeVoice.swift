import Foundation
import AVFoundation
import Speech

/// 三项测试使用同一发声人；播报期间与结束后 600 ms 停止收听，丢弃旧识别会话。
final class EyeVoice: NSObject, ObservableObject, AVSpeechSynthesizerDelegate {
    @Published var enabled = true
    @Published var answersEnabled = false
    var onAnswer: ((String) -> Bool)?
    var onStatus: ((String) -> Void)?
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
    override init() { super.init(); synthesizer.delegate = self }
    func requestPermission() {
        SFSpeechRecognizer.requestAuthorization { [weak self] status in
            AVAudioSession.sharedInstance().requestRecordPermission { microphone in
                DispatchQueue.main.async {
                    guard let self = self else { return }
                    self.authorized = status == .authorized && microphone
                    if !self.authorized { self.onStatus?("请在系统设置中允许麦克风和语音识别权限。") }
                    else if self.recognizer?.supportsOnDeviceRecognition != true {
                        self.authorized = false
                        self.onStatus?("本机暂不支持离线中文识别，请使用方向按钮。")
                    }
                }
            }
        }
    }
    func speak(_ text: String) {
        guard enabled, !text.isEmpty else { return }
        stopListening()
        synthesizer.stopSpeaking(at: .immediate)
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = AVSpeechSynthesisVoice(language: "zh-CN")
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate
        utterance.pitchMultiplier = 1
        synthesizer.speak(utterance)
    }
    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) { cooldown = Date().addingTimeInterval(0.6) }
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
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .mixWithOthers])
            try session.setActive(true)
            let next = SFSpeechAudioBufferRecognitionRequest()
            next.shouldReportPartialResults = true; next.requiresOnDeviceRecognition = true
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
                            self.onStatus?("听到：\(text)")
                            return
                        }
                    }
                    if error != nil || result?.isFinal == true {
                        self.stopListening(); self.retryAfter = Date().addingTimeInterval(1)
                        self.onStatus?(error == nil ? "准备继续收听" : "语音暂未识别，请重说或用按钮")
                    }
                }
            }
            onStatus?("正在听：说上、下、左、右；看不清就说看不清")
        } catch { stopListening(); retryAfter = Date().addingTimeInterval(2); onStatus?("麦克风启动失败，请使用按钮或重试") }
    }
    func stopListening() {
        generation += 1; task?.cancel(); task = nil; request?.endAudio(); request = nil
        if audio.isRunning { audio.stop() }
        if hasTap { audio.inputNode.removeTap(onBus: 0); hasTap = false }
    }
    func stop() { synthesizer.stopSpeaking(at: .immediate); stopListening(); cooldown = Date().addingTimeInterval(0.6) }
    func close() { wanted = false; stop(); try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation) }
}

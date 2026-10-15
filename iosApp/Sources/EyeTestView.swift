import SwiftUI
import UIKit

struct EyeTestView: View {
    @StateObject private var runtime: EyeRuntime
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var pixelsPerMm = UserDefaults.standard.float(forKey: "eye.pixelsPerMm")
    @State private var brightness: CGFloat?
    @State private var previousIdleDisabled = false
    @State private var settingsOpen = false
    init(kind: String) { _runtime = StateObject(wrappedValue: EyeRuntime(kind: kind)) }
    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if hasReflexStimulus {
                    ZStack { Color.black; Circle().fill(.white).frame(width: 8, height: 8) }.frame(height: 20).clipShape(RoundedRectangle(cornerRadius: 8))
                }
                if pixelsPerMm > 0 && runtime.number("targetDistanceCm") > 0 && !runtime.bool("guidePending") {
                    TestDistanceBanner(runtime: runtime).padding(.horizontal, 16)
                }
                GeometryReader { viewport in
                    ScrollView {
                        VStack(spacing: EyeStyle.spacing) {
                            if pixelsPerMm <= 0 {
                                ScreenCalibrationView { value in
                                    pixelsPerMm = value; UserDefaults.standard.set(value, forKey: "eye.pixelsPerMm")
                                    runtime.prepare(pixelsPerMm: value)
                                }
                            } else {
                                if let error = runtime.error { Text(error).foregroundStyle(EyeStyle.warning) }
                                if !warningText.isEmpty {
                                    Text(warningText).foregroundStyle(EyeStyle.warning)
                                }
                                if isProfile { QuestionnaireView(runtime: runtime) }
                                else if isRunning { VStack(spacing: EyeStyle.spacing) { testContent }.modifier(TestStagePanel()) }
                                else { testContent }
                            }
                        }.padding().frame(maxWidth: .infinity, minHeight: viewport.size.height, alignment: isRunning ? .center : .top)
                    }
                }
            }.background(EyeStyle.background).navigationTitle(runtime.string("title").isEmpty ? "眼健康自测" : runtime.string("title"))
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("返回") { runtime.close(); dismiss() } }
                    ToolbarItem(placement: .navigationBarTrailing) {
                        Button { settingsOpen = true } label: { Image(systemName: "gearshape.fill").foregroundStyle(EyeStyle.primary) }.accessibilityLabel("系统设置")
                    }
                }
                .sheet(isPresented: $settingsOpen) { TestSettingsView(voice: runtime.voice, status: runtime.speechStatus) }
                .onChange(of: settingsOpen) { runtime.setSettingsPresented($0) }
                .onAppear {
                    brightness = UIScreen.main.brightness; UIScreen.main.brightness = 1
                    previousIdleDisabled = UIApplication.shared.isIdleTimerDisabled
                    UIApplication.shared.isIdleTimerDisabled = true
                    if pixelsPerMm > 0 { runtime.prepare(pixelsPerMm: pixelsPerMm) }
                }
                .onDisappear {
                    runtime.close(); UIApplication.shared.isIdleTimerDisabled = previousIdleDisabled
                    if let value = brightness { UIScreen.main.brightness = value }
                }
                .onChange(of: scenePhase) { phase in
                    if phase != .active { runtime.interrupt(); if let value = brightness { UIScreen.main.brightness = value } }
                    else { UIScreen.main.brightness = 1; runtime.resume() }
                }
        }
    }
    private var phase: String { runtime.string("phase") }
    private var warningText: String {
        let text = runtime.string("warning")
        if text == "慢慢移近" { return "" }
        if runtime.number("targetDistanceCm") > 0 && (text.hasPrefix("太近") || text.hasPrefix("太远") || text == "请正对屏幕") { return "" }
        return text
    }
    private var isProfile: Bool { phase == "Profile" || phase == "PROFILE" }
    private var hasReflexStimulus: Bool {
        pixelsPerMm > 0 && runtime.kind == "STRABISMUS" && !isProfile && !phase.isEmpty && phase != "RESULT"
    }
    private var isRunning: Bool {
        pixelsPerMm > 0 && !runtime.bool("guidePending") && ["Practice","Natural","Open","Countdown","Rest",
            "NEAR_VISION","PUSH_UP","DISTANCE_CURVE","FLIPPER","GAZE_CALIBRATION","FIXATION","COVER","PURSUIT"].contains(phase)
    }
    @ViewBuilder private var testContent: some View {
        if phase == "Setup" || phase == "ENVIRONMENT" {
            Text(runtime.kind == "STRABISMUS" ? "保持约 33 cm，后续遮眼由协助者完成。" : "保持约 40 cm，建议佩戴最佳矫正眼镜。")
            Button("开始测试") { runtime.act("begin") }.buttonStyle(EyeButtonStyle())
            Button("重试相机") { runtime.act("retryCamera") }
            DisclosureGroup("测距校准") {
                Text("用尺确认眼睛到手机为 40 cm，正对手机且距离读数稳定后点按。")
                Button("此刻实际为 40 cm，校准测距") {
                    let measured = runtime.number("distanceCm")
                    if measured > 10 && measured < 100 {
                        runtime.camera.distanceScale *= Float(40 / measured)
                        UserDefaults.standard.set(true, forKey: "eye.distanceCalibrated")
                    }
                }.disabled(runtime.number("distanceCm") <= 0)
            }
        } else if phase == "PracticeGuide" || phase == "FormalGuide" {
            Text(phase == "PracticeGuide" ? "先练习 12 秒：看 E 的开口方向，在 2 秒内作答。" : "自然眨眼 60 秒后继续尽量睁眼 20 秒。两段全程看 E 并选择方向，中途没有休息。")
            Button("准备好了") { runtime.act(phase == "PracticeGuide" ? "practice" : "formal") }.buttonStyle(EyeButtonStyle())
        } else if runtime.bool("guidePending") {
            Text(runtime.string("stepTitle")).font(EyeStyle.title)
            Text(runtime.string("guideText").isEmpty ? runtime.string("instruction") : runtime.string("guideText"))
            Button("准备好了") { runtime.act("guide") }.buttonStyle(EyeButtonStyle())
        } else if phase == "Result" || phase == "RESULT" {
            Text(runtime.string("category").isEmpty ? riskLabel(runtime.string("risk")) : runtime.string("category")).font(EyeStyle.title)
            ForEach(Array((runtime.state["findings"] as? [String] ?? []).enumerated()), id: \.offset) { _, line in Text(line).frame(maxWidth: .infinity, alignment: .leading) }
            if !runtime.string("advice").isEmpty { Text(runtime.string("advice")) }
            if !runtime.string("savedFile").isEmpty {
                ShareLink(item: EyeRuntime.recordsDirectory.appendingPathComponent(runtime.string("savedFile"))) { Label("导出本次记录", systemImage: "square.and.arrow.up") }
            }
            if runtime.kind == "DRY_EYE" {
                Button("延长测试至 3 分钟") { runtime.act("extended") }
                Button("闭眼休息 1 分钟后复测") { runtime.act("rest") }
            }
        } else if phase == "GAZE_CALIBRATION" || phase == "FIXATION" || phase == "COVER" || phase == "PURSUIT" {
            if phase == "GAZE_CALIBRATION" {
                Text("注视校准 · \(Int(runtime.number("gazeCalibrationIndex")) + 1)/5").font(EyeStyle.title)
            } else if phase != "COVER" { Text("剩余 \(Int(runtime.number("secondsLeft"))) 秒").monospacedDigit() }
            GazeTargetView(runtime: runtime, pixelsPerMm: pixelsPerMm)
            if runtime.bool("gazeCalibrationFailed") { Button("重试注视校准") { runtime.act("calibrate") }.buttonStyle(EyeButtonStyle()) }
            if phase == "COVER", !runtime.string("coverNext").isEmpty {
                Text(runtime.number("coverRemainingMs") > 0 ? "保持：剩余 \(Int(ceil(runtime.number("coverRemainingMs") / 1000))) 秒" : "可以执行下一步：\(runtime.string("coverNext"))")
                Button("执行「\(runtime.string("coverNext"))」时点按") { runtime.act("cover") }.buttonStyle(EyeButtonStyle())
            }
        } else if phase == "Countdown" {
            Text("\(Int(runtime.number("secondsLeft")))").font(.largeTitle).monospacedDigit()
        } else if phase == "Rest" {
            Text("闭眼休息")
            Text("\(Int(runtime.number("secondsLeft"))) 秒").font(.largeTitle).monospacedDigit()
        } else {
            if phase == "Natural" || phase == "Open" || phase == "Practice" {
                HStack {
                    Text(phase == "Natural" ? "自然眨眼" : phase == "Open" ? "持续睁眼" : "练习")
                    Spacer(); Text("\(Int(runtime.number("secondsLeft"))) 秒").monospacedDigit()
                }
            } else if phase == "NEAR_VISION" {
                Text(["RIGHT": "右眼", "LEFT": "左眼", "BOTH": "双眼"][runtime.string("eye")] ?? "")
            } else if phase == "FLIPPER" || phase == "DISTANCE_CURVE" {
                HStack {
                    Text("\(Int(runtime.number("targetDistanceCm"))) cm")
                    Spacer()
                    if runtime.number("secondsLeft") > 0 { Text("\(Int(runtime.number("secondsLeft"))) 秒").monospacedDigit() }
                }
            }
            if runtime.bool("nearAwaitingEyeReady") {
                Text(runtime.string("instruction"))
                Button("遮盖准备好了") { runtime.act("eyeReady") }.buttonStyle(EyeButtonStyle())
            } else if phase == "PUSH_UP" && runtime.string("pushStage") == "READY" {
                Text("先移到 45 cm 以外")
                Button("开始移近") { runtime.act("pushStart") }.buttonStyle(EyeButtonStyle())
            } else {
                if phase == "PUSH_UP" {
                    Text("缓慢移近")
                    TumblingEView(runtime: runtime).frame(height: max(180, CGFloat(runtime.number("optotypePx")) / UIScreen.main.scale + 30))
                    Button("开始持续模糊了") { runtime.act("blur") }.buttonStyle(EyeButtonStyle())
                } else {
                    OptotypeAnswerPad(runtime: runtime)
                }
            }
        }
    }
    private func riskLabel(_ value: String) -> String {
        switch value { case "LOW": return "低风险"; case "MEDIUM": return "中风险"; case "HIGH": return "高风险"; default: return "数据不足，请重新测试" }
    }
}

private struct ScreenCalibrationView: View {
    let complete: (Float) -> Void
    @State private var length: Double = 280
    var body: some View {
        VStack(spacing: EyeStyle.spacing) {
            Text("先校准屏幕尺寸").font(EyeStyle.title)
            Text("用尺量下面的线段，调整到实际长度正好为 5 cm。完成后这台手机会记住设置。")
            Rectangle().fill(EyeStyle.text).frame(width: length, height: 3).padding(.vertical, 24)
            Slider(value: $length, in: 140...Double(UIScreen.main.bounds.width - 40))
            Button("已量好：线段为 5 cm") { complete(Float(length * UIScreen.main.scale / 50)) }.buttonStyle(EyeButtonStyle())
        }
    }
}

private struct QuestionnaireView: View {
    @ObservedObject var runtime: EyeRuntime
    @State private var index = 0
    @State private var mode = "标准模式"
    var body: some View {
        if !runtime.questions.isEmpty {
            let q = runtime.questions[min(index, runtime.questions.count - 1)]
            let key = q["key"] as? String ?? ""
            let value = runtime.fields[key] ?? ""
            VStack(alignment: .leading, spacing: EyeStyle.spacing) {
                Text("\(index + 1)/\(runtime.questions.count)").foregroundStyle(EyeStyle.secondary)
                Text(q["title"] as? String ?? key).font(EyeStyle.title)
                if let options = q["options"] as? [String] {
                    ForEach(options, id: \.self) { option in
                        let selected = ("、" + value + "、").contains("、" + option + "、")
                        Button {
                            if q["multiple"] as? Bool == true {
                                let exclusive = Set(q["exclusive"] as? [String] ?? [])
                                var chosen = options.filter { ("、" + value + "、").contains("、" + $0 + "、") }
                                if selected { chosen.removeAll { $0 == option } }
                                else if exclusive.contains(option) { chosen = [option] }
                                else { chosen.removeAll { exclusive.contains($0) }; chosen.append(option) }
                                runtime.setField(key, chosen.joined(separator: "、"))
                            } else { runtime.setField(key, option); if index < runtime.questions.count - 1 { index += 1 } }
                        } label: {
                            HStack { Text(option); Spacer(); if selected { Image(systemName: "checkmark") } }
                                .padding().frame(maxWidth: .infinity).background(EyeStyle.panel, in: RoundedRectangle(cornerRadius: 14))
                        }.foregroundStyle(EyeStyle.text)
                    }
                } else {
                    TextField("请输入", text: Binding(get: { runtime.fields[key] ?? "" }, set: { runtime.setField(key, $0) }))
                        .keyboardType(q["numeric"] as? Bool == true ? .numberPad : .default).textFieldStyle(.roundedBorder)
                }
                if index > 0 { Button("上一题") { index -= 1 } }
                if index == runtime.questions.count - 1 && runtime.kind == "PRESBYOPIA" {
                    Text("这次测试戴哪副眼镜？")
                    Picker("测试模式", selection: $mode) {
                        Text("标准：摘下老花镜／渐进镜，戴看远的眼镜或不戴镜").tag("标准模式")
                        Text("日常：戴平时看近的眼镜").tag("日常模式")
                    }.pickerStyle(.menu).onChange(of: mode) { value in runtime.controller.setMode(mode: value) }
                }
                Button(index < runtime.questions.count - 1 ? "下一题" : "检查测试条件") {
                    if index < runtime.questions.count - 1 { index += 1 }
                    else { if runtime.kind == "PRESBYOPIA" { runtime.controller.setMode(mode: mode) }; runtime.finishProfile() }
                }.buttonStyle(EyeButtonStyle()).disabled(q["valid"] as? Bool != true)
            }
        }
    }
}

private struct OptotypeAnswerPad: View {
    @ObservedObject var runtime: EyeRuntime
    var body: some View {
        VStack(spacing: EyeStyle.spacing) {
            ZStack {
                TumblingEView(runtime: runtime)
                VStack { arrow("arrow.up", "向上", 3); Spacer(); arrow("arrow.down", "向下", 1) }
                HStack { arrow("arrow.left", "向左", 2); Spacer(); arrow("arrow.right", "向右", 0) }
            }.frame(height: max(256, CGFloat(runtime.number("optotypePx")) / UIScreen.main.scale + 168))
            Button("看不清 / 跳过") { runtime.answer(-1) }
        }.buttonStyle(DirectionAnswerButtonStyle()).disabled(!runtime.bool("trialVisible"))
    }
    private func arrow(_ symbol: String, _ label: String, _ direction: Int) -> some View {
        Button { runtime.answer(direction) } label: { Image(systemName: symbol).font(.system(size: 22, weight: .semibold)) }
            .frame(width: 56, height: 56).accessibilityLabel(label)
    }
}

private struct DirectionAnswerButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.frame(maxWidth: .infinity, minHeight: 56).foregroundStyle(EyeStyle.text)
            .background(EyeStyle.surfaceVariant, in: RoundedRectangle(cornerRadius: 14))
            .overlay { RoundedRectangle(cornerRadius: 14).stroke(EyeStyle.primary.opacity(0.2), lineWidth: 1) }
            .shadow(color: EyeStyle.primary.opacity(0.06), radius: 2, x: 0, y: 1)
            .opacity(enabled ? (configuration.isPressed ? 0.7 : 1) : 0.5)
    }
}

private struct TumblingEView: View {
    @ObservedObject var runtime: EyeRuntime
    var body: some View {
        Canvas { context, size in
            guard runtime.bool("trialVisible") else { return }
            let edge = CGFloat(runtime.number("optotypePx")) / UIScreen.main.scale
            guard edge > 0 else { return }
            context.translateBy(x: size.width / 2, y: size.height / 2)
            context.rotate(by: .degrees(Double(runtime.number("direction")) * 90))
            let bar = edge / 5
            context.fill(Path(CGRect(x: -edge / 2, y: -edge / 2, width: bar, height: edge)), with: .color(.black))
            for row in [0, 2, 4] { context.fill(Path(CGRect(x: -edge / 2, y: -edge / 2 + CGFloat(row) * bar, width: edge, height: bar)), with: .color(.black)) }
        }.background(EyeStyle.panel, in: RoundedRectangle(cornerRadius: EyeStyle.radius))
            .overlay {
                let diameter = max(96, CGFloat(runtime.number("optotypePx")) / UIScreen.main.scale + 40)
                Circle().stroke(EyeStyle.primary.opacity(0.72), lineWidth: 2).frame(width: diameter, height: diameter)
                    .allowsHitTesting(false)
            }
            .onChange(of: runtime.number("trialId")) { id in
                runtime.schedulePresented(Int32(id))
            }
            .onAppear { runtime.schedulePresented(Int32(runtime.number("trialId"))) }
    }
}

private struct GazeTargetView: View {
    @ObservedObject var runtime: EyeRuntime
    let pixelsPerMm: Float
    var body: some View {
        GeometryReader { geometry in
            let size = geometry.size
            let point = target(in: size)
            let phase = runtime.string("phase")
            ZStack(alignment: .topLeading) {
                EyeStyle.panel
                if phase == "GAZE_CALIBRATION" {
                    Circle().fill(EyeStyle.primary).frame(width: 16, height: 16).position(point)
                } else {
                    let width = phase == "PURSUIT" ? CGFloat(5 * pixelsPerMm / 0.65) / UIScreen.main.scale : CGFloat(runtime.number("targetDiameterMm") * Double(pixelsPerMm)) / UIScreen.main.scale
                    MascotView().frame(width: width, height: width * 1.22)
                        .opacity(phase == "FIXATION" && runtime.number("targetX") <= 0 ? 0.3 : 1).position(point)
                }
            }.clipShape(RoundedRectangle(cornerRadius: EyeStyle.radius))
                .onAppear { report(geometry, point) }
                .onChange(of: runtime.stateKeyForTarget) { _ in report(geometry, point) }
                .onChange(of: geometry.frame(in: .global).origin) { _ in report(geometry, point) }
        }.frame(height: runtime.string("phase") == "PURSUIT" ? 360 : 240)
    }
    private func target(in size: CGSize) -> CGPoint {
        if runtime.string("phase") == "GAZE_CALIBRATION" {
            let positions = [(0.5,0.5),(0.15,0.5),(0.85,0.5),(0.5,0.15),(0.5,0.85)]
            let p = positions[min(4,Int(runtime.number("gazeCalibrationIndex")))]
            return CGPoint(x: size.width * p.0,y: size.height * p.1)
        }
        if runtime.string("phase") == "PURSUIT" {
            let ppm = Double(pixelsPerMm) / Double(UIScreen.main.scale)
            let distance = max(runtime.number("distanceCm"), 1) * 10
            let radius = 5 * ppm
            let xMax = atan(max(0,(size.width / 2 - radius)) / (distance * ppm)) * 180 / .pi
            let yMax = atan(max(0,(size.height / 2 - radius)) / (distance * ppm)) * 180 / .pi
            let scale = min(1,min(xMax / 10,yMax / 10))
            return CGPoint(x: size.width / 2 + tan(runtime.number("targetX") * scale * .pi / 180) * distance * ppm,
                y: size.height / 2 - tan(runtime.number("targetY") * scale * .pi / 180) * distance * ppm)
        }
        return CGPoint(x: size.width / 2,y: size.height / 2)
    }
    private func report(_ geometry: GeometryProxy,_ point: CGPoint) {
        let frame = geometry.frame(in: .global), scale = UIScreen.main.scale
        runtime.controller.reportTarget(xPx: Float((frame.minX + point.x) * scale), yPx: Float((frame.minY + point.y) * scale))
        if runtime.string("phase") == "PURSUIT" {
            let ppm = Double(pixelsPerMm) / Double(scale), distance = max(runtime.number("distanceCm"),1) * 10
            runtime.controller.reportPursuitCapacity(xDeg: Float(atan(max(0,geometry.size.width / 2 - 5 * ppm) / (distance * ppm)) * 180 / .pi),
                yDeg: Float(atan(max(0,geometry.size.height / 2 - 5 * ppm) / (distance * ppm)) * 180 / .pi))
        }
    }
}

private extension EyeRuntime {
    var stateKeyForTarget: String { "\(string("phase"))-\(number("targetX"))-\(number("targetY"))-\(number("gazeCalibrationIndex"))" }
}

import Foundation
import UIKit
import SwiftUI

struct BrightnessSample: Codable {
    var hostTime: Double
    var requested: Double
    var actual: Double
    var reason: String
}

/// 百分比为系统亮度属性读回值，不冒充屏幕光度计读数。
final class ScreenBrightnessController: ObservableObject {
    @Published private(set) var actual: Double
    @Published private(set) var requested: Double
    @Published private(set) var confirmed = true
    var onExternalChange: ((Double) -> Void)?
    private let read: () -> Double
    private let write: (Double) -> Void
    private var timer: Timer?
    private var observer: NSObjectProtocol?
    private var original: Double?
    private var capturing = false
    private var releasing = false
    private(set) var samples: [BrightnessSample] = []

    init(read: @escaping () -> Double = { Double(UIScreen.main.brightness) },write: @escaping (Double) -> Void = { UIScreen.main.brightness = CGFloat($0) },monitor: Bool = true) {
        let initial = read()
        self.read = read; self.write = write; actual = initial; requested = initial
        if monitor {
            observer = NotificationCenter.default.addObserver(forName: UIScreen.brightnessDidChangeNotification,object: nil,queue: .main) { [weak self] _ in self?.refresh(reason: "系统亮度通知") }
            let timer = Timer(timeInterval: 0.25,repeats: true) { [weak self] _ in self?.refresh(reason: "定时读回") }
            self.timer = timer; RunLoop.main.add(timer,forMode: .common)
        }
    }
    deinit { timer?.invalidate(); if let observer = observer { NotificationCenter.default.removeObserver(observer) } }
    func apply(_ value: Double) {
        if original == nil { original = read() }
        requested = max(0.1,min(1,value)); write(requested)
        refresh(reason: "程序设定")
    }
    func refresh(reason: String) {
        let previous = actual,value = max(0,min(1,read()))
        actual = value; confirmed = abs(value-requested)<=0.02
        if capturing && (samples.isEmpty || abs(value-(samples.last?.actual ?? value))>0.004 || reason == "程序设定" || reason == "采集结束") {
            samples.append(BrightnessSample(hostTime: labHostTime(),requested: requested,actual: value,reason: reason))
        }
        if abs(value-previous)>0.004 && !confirmed && !releasing { onExternalChange?(value) }
    }
    func beginCapture() {
        samples = []; capturing = true; refresh(reason: "开始采集")
    }
    func adoptReadback() { requested = actual; confirmed = true }
    func endCapture() -> [BrightnessSample] {
        refresh(reason: "采集结束"); capturing = false; return samples
    }
    func releaseControl() {
        capturing = false
        releasing = true; defer { releasing = false }
        if let original = original { write(original); self.original = nil }
        refresh(reason: "离开应用后恢复")
    }
}

struct BrightnessControlView: View {
    @ObservedObject var controller: ScreenBrightnessController
    @Binding var requested: Double
    let set: (Double) -> Void
    var body: some View {
        VStack(alignment: .leading,spacing: 8) {
            HStack {
                Text("系统屏幕亮度")
                Slider(value: Binding(get: { requested },set: { requested = $0; set($0) }),in: 0.1...1)
                Text("\(Int(controller.actual*100))%").monospacedDigit()
            }
            HStack {
                Button("设为100%") { requested = 1; set(1) }.frame(minHeight: 44)
                Spacer()
                Text(abs(controller.actual-requested)<=0.02 ? "已读回确认" : "目标\(Int(requested*100))%，尚未一致").font(LabStyle.detail)
            }
        }
    }
}

enum LabKeyboard {
    static func dismiss() {
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder),to: nil,from: nil,for: nil)
        for scene in UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }) {
            for window in scene.windows { window.endEditing(true) }
        }
    }
}

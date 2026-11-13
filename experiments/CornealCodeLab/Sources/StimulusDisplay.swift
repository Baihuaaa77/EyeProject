import UIKit
import QuartzCore
import CoreMedia
import SwiftUI

func labHostTime() -> Double { CMTimeGetSeconds(CMClockGetTime(CMClockGetHostTimeClock())) }

/// 采集时唯一可见的屏幕对象。回调日志记录计划合成时间；物理发光延迟交由解调搜索，不假定硬同步。
final class StimulusDriver: NSObject {
    var configuration = TrialConfiguration()
    var events: [DisplayEvent] = []
    var onEnd: (() -> Void)?
    private weak var view: UIView?
    private let dot = CALayer()
    private var link: CADisplayLink?
    private var start: Double?
    private var lastChip = -999
    private var clockOffset = 0.0

    func attach(_ view: UIView) {
        self.view = view
        view.backgroundColor = LabStyle.stimulusBackground
        dot.actions = ["backgroundColor": NSNull(), "position": NSNull(), "bounds": NSNull()]
        view.layer.addSublayer(dot)
        render(configuration.mean)
    }
    func warmup(_ c: TrialConfiguration) {
        stop(); configuration = c; events = []; start = nil; lastChip = -999
        clockOffset = labHostTime()-CACurrentMediaTime()
        link = CADisplayLink(target: self, selector: #selector(tick(_:)))
        link?.preferredFrameRateRange = CAFrameRateRange(minimum: 30, maximum: 60, preferred: 60)
        link?.add(to: .main, forMode: .common)
    }
    func begin(at time: Double) { events = []; start = time; lastChip = -999 }
    func stop() { link?.invalidate(); link = nil }
    @objc private func tick(_ sender: CADisplayLink) {
        let planned = sender.targetTimestamp+clockOffset
        guard let start = start, planned >= start else {
            render(configuration.mode == .dark ? 0 : configuration.mean); return
        }
        let elapsed = planned-start
        if elapsed >= configuration.duration { stop(); render(0); onEnd?(); return }
        let chip = Int(elapsed/configuration.chipSeconds)
        let intensity = StimulusCode.intensity(at: elapsed, configuration: configuration)
        render(intensity)
        if chip != lastChip {
            // 常亮/全黑轮也记下同一参考码，但实际显示强度恒定，供伪码阴性对照分析。
            events.append(DisplayEvent(hostTime: planned,intensity: intensity,
                code: StimulusCode.value(at: elapsed,configuration: configuration),chip: chip,callbackHostTime: labHostTime(),
                screenBrightness: Double(UIScreen.main.brightness)))
            lastChip = chip
        }
    }
    private func render(_ intensity: Double) {
        guard let view = view else { return }
        let diameter = configuration.dotDiameter
        CATransaction.begin(); CATransaction.setDisableActions(true)
        dot.bounds = CGRect(x: 0,y: 0,width: diameter,height: diameter)
        dot.cornerRadius = diameter/2
        dot.position = CGPoint(x: Double(view.bounds.width)/2,y: Double(view.bounds.height)*configuration.dotY)
        dot.backgroundColor = UIColor(white: intensity,alpha: 1).cgColor
        CATransaction.commit()
    }
}

struct StimulusSurface: UIViewRepresentable {
    let driver: StimulusDriver
    func makeUIView(context: Context) -> UIView { let v = UIView(); driver.attach(v); return v }
    func updateUIView(_ view: UIView, context: Context) {}
}

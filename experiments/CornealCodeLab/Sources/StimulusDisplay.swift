import UIKit
import QuartzCore
import CoreMedia
import SwiftUI

func labHostTime() -> Double { CMTimeGetSeconds(CMClockGetTime(CMClockGetHostTimeClock())) }

/// 仅显示实验光点。运动轮固定注视白点，外围光源独立运动，逐显示帧记实际位置。
final class StimulusDriver: NSObject {
    var configuration = TrialConfiguration()
    var events: [DisplayEvent] = []
    var onEnd: (() -> Void)?
    private weak var view: UIView?
    private let dot = CALayer()
    private var probeLayers: [CALayer] = []
    private var link: CADisplayLink?
    private var start: Double?
    private var lastChip = -999
    private var clockOffset = 0.0

    func attach(_ view: UIView) {
        self.view = view
        view.backgroundColor = LabStyle.stimulusBackground
        dot.actions = ["backgroundColor": NSNull(), "position": NSNull(), "bounds": NSNull()]
        view.layer.addSublayer(dot)
        for _ in 0..<3 {
            let layer = CALayer(); layer.actions = dot.actions
            view.layer.addSublayer(layer); probeLayers.append(layer)
        }
        render(StimulusCode.intensity(at: 0,configuration: configuration))
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
            render(StimulusCode.intensity(at: 0,configuration: configuration)); return
        }
        let elapsed = planned-start
        if elapsed >= configuration.duration { stop(); render(0); onEnd?(); return }
        let chip = Int(elapsed/configuration.chipSeconds)
        let intensity = StimulusCode.intensity(at: elapsed, configuration: configuration)
        let probes = render(intensity,time: elapsed)
        if chip != lastChip || configuration.mode == .motion {
            // 常亮/全黑轮也记下同一参考码，但实际显示强度恒定，供伪码阴性对照分析。
            events.append(DisplayEvent(hostTime: planned,intensity: intensity,
                code: StimulusCode.value(at: elapsed,configuration: configuration),chip: chip,callbackHostTime: labHostTime(),
                screenBrightness: Double(UIScreen.main.brightness),probes: configuration.mode == .motion ? probes : nil))
            lastChip = chip
        }
    }
    @discardableResult private func render(_ intensity: Double,time: Double = 0) -> [ProbeEvent] {
        guard let view = view else { return [] }
        let moving = configuration.mode == .motion
        let diameter = moving ? 24 : configuration.dotDiameter
        let probes = moving && intensity>0 ? MotionStimulus.probes(at: time,configuration: configuration,width: Double(view.bounds.width),height: Double(view.bounds.height)) : []
        CATransaction.begin(); CATransaction.setDisableActions(true)
        dot.bounds = CGRect(x: 0,y: 0,width: diameter,height: diameter)
        dot.cornerRadius = diameter/2
        dot.position = CGPoint(x: Double(view.bounds.width)/2,y: Double(view.bounds.height)*configuration.dotY)
        dot.backgroundColor = UIColor(white: intensity,alpha: 1).cgColor
        for (index,layer) in probeLayers.enumerated() {
            layer.isHidden = index>=probes.count
            if index<probes.count {
                let probe = probes[index], size = configuration.dotDiameter
                layer.bounds = CGRect(x: 0,y: 0,width: size,height: size); layer.cornerRadius = size/2
                layer.position = CGPoint(x: probe.position.x*Double(view.bounds.width),y: probe.position.y*Double(view.bounds.height))
                // 这是刺激通道值，不是界面主题色；白平衡锁定后保存原始 RGB 供复核。
                let color: UIColor = probe.color == "red" ? UIColor(red: 1,green: 0,blue: 0,alpha: 1) : probe.color == "green" ? UIColor(red: 0,green: 1,blue: 0,alpha: 1) : probe.color == "blue" ? UIColor(red: 0,green: 0,blue: 1,alpha: 1) : .white
                layer.backgroundColor = color.cgColor
            }
        }
        CATransaction.commit()
        return probes
    }
}

struct StimulusSurface: UIViewRepresentable {
    let driver: StimulusDriver
    func makeUIView(context: Context) -> UIView { let v = UIView(); driver.attach(v); return v }
    func updateUIView(_ view: UIView, context: Context) {}
}

import Foundation

enum CodingScheme: String, Codable, CaseIterable, Identifiable {
    case mSequence, manchester, sparsePulse
    var id: String { rawValue }
    var title: String {
        switch self {
        case .mSequence: return "平衡随机明灭"
        case .manchester: return "曼彻斯特双相"
        case .sparsePulse: return "稀疏短脉冲"
        }
    }
    func value(bit: Double,phase: Double) -> Double {
        switch self {
        case .mSequence: return bit
        case .manchester: return phase<0.5 ? bit : -bit
        case .sparsePulse: return bit>0 && phase<0.35 ? 1 : -1
        }
    }
    var meanReference: Double { self == .sparsePulse ? 0.175 : 0.5 }
    var detail: String {
        switch self {
        case .mSequence: return "按平衡伪随机序列亮灭，使用错码对照核查光源身份。"
        case .manchester: return "每个码元内交换亮灭相位，亮灯时间占50%。"
        case .sparsePulse: return "短亮、长灭，亮灯时间约占17.5%，增加阴性检查时段。"
        }
    }
}

enum DotPlacement: String, Codable, CaseIterable, Identifiable {
    case nearCamera, upperQuarter, upperThird, center, legacyFraction
    var id: String { rawValue }
    var title: String {
        switch self {
        case .nearCamera: return "贴近前摄（完整可见）"
        case .upperQuarter: return "上四分之一"
        case .upperThird: return "上部居中"
        case .center: return "屏幕中央"
        case .legacyFraction: return "历史相对位置"
        }
    }
    static let available: [DotPlacement] = [.nearCamera,.upperQuarter,.upperThird,.center]
}

enum StimulusGeometry {
    static func center(configuration c: TrialConfiguration,width: Double,height: Double,safeTop: Double,safeBottom: Double) -> LabPoint {
        let radius = c.dotDiameter/2
        let minimum = max(radius+2,safeTop+radius+2)
        let maximum = max(minimum,height-safeBottom-radius-2)
        let y: Double
        switch c.dotPlacement {
        case .nearCamera: y = minimum
        case .upperQuarter: y = max(minimum,min(maximum,height*0.25))
        case .upperThird: y = max(minimum,min(maximum,height*0.35))
        case .center: y = max(minimum,min(maximum,height*0.5))
        case .legacyFraction: y = height*c.dotY
        }
        return LabPoint(x: width/2,y: y)
    }
}

enum EmissionPhase: String, Codable {
    case on, dim, off, transition, unknown
    var title: String {
        switch self {
        case .on: return "亮灯"
        case .dim: return "低电平"
        case .off: return "灭灯"
        case .transition: return "切换/曝光混合"
        case .unknown: return "时序未知"
        }
    }
    var inhibitsMeasurement: Bool { self == .off || self == .dim }
}

struct EmissionWindow: Codable {
    var phase: EmissionPhase
    var intensity: Double?
}

struct EmissionAudit: Codable {
    var stableOnFrames: Int
    var stableDimFrames: Int
    var stableOffFrames: Int
    var transitionFrames: Int
    var unknownFrames: Int
    var independentDimMeasurements: Int
    var independentOffMeasurements: Int
    var suppressedMeasurements: Int
    var finalDimMeasurements: Int
    var finalOffMeasurements: Int
    var lagUsed: Double?
    var note: String
    static func build(_ frames: [GlintEstimate],lag: Double?) -> EmissionAudit {
        func count(_ phase: EmissionPhase) -> Int { frames.filter { $0.emissionPhase == phase }.count }
        return EmissionAudit(stableOnFrames: count(.on),stableDimFrames: count(.dim),stableOffFrames: count(.off),
            transitionFrames: count(.transition),unknownFrames: count(.unknown),
            independentDimMeasurements: frames.filter { $0.emissionPhase == .dim && $0.independentSource?.measured == true }.count,
            independentOffMeasurements: frames.filter { $0.emissionPhase == .off && $0.independentSource?.measured == true }.count,
            suppressedMeasurements: frames.filter { $0.timingSuppressed == true }.count,
            finalDimMeasurements: frames.filter { $0.emissionPhase == .dim && $0.source.measured }.count,
            finalOffMeasurements: frames.filter { $0.emissionPhase == .off && $0.source.measured }.count,lagUsed: lag,
            note: "保留加入亮灭先验前的图像结果；被时序抑制的数量不是人工确认误检数。5%低电平不等同零光源；切换与曝光混合帧不作灭灯阴性。编码验证仍使用独立图像轨迹的信号，不能用门控后的信号提高验证相关。")
    }
}

/// 只在整个曝光与延迟容差窗口都处于同一状态时判定稳定亮/灭；不把边界帧当作灭灯阴性。
struct EmissionTimeline {
    private let events: [(hostTime: Double,intensity: Double)]
    init(events: [DisplayEvent]) {
        self.events = events.filter { $0.hostTime.isFinite && $0.callbackHostTime.isFinite && $0.intensity.isFinite }
            .map { (max($0.hostTime,$0.callbackHostTime),$0.intensity) }.sorted { $0.0<$1.0 }
    }
    private func index(_ time: Double) -> Int {
        var low = 0,high = events.count
        while low<high { let middle = (low+high)/2; if events[middle].hostTime<=time { low = middle+1 } else { high = middle } }
        return low-1
    }
    func window(time: Double,exposure: Double,fps: Int,lag: Double?,chipSeconds: Double) -> EmissionWindow {
        guard !events.isEmpty else { return EmissionWindow(phase: .unknown,intensity: nil) }
        let frame = 1/Double(max(1,fps))
        let margin = max(0,exposure)/2+frame/2+1.0/60
        let lowLag = lag.map { $0-frame/2 } ?? 0
        let highLag = lag.map { $0+frame/2 } ?? 0.15
        let from = time-highLag-margin,to = time-lowLag+margin
        let first = index(from),last = index(to)
        guard first>=0,last>=first,to<=events.last!.hostTime+chipSeconds+frame else { return EmissionWindow(phase: .unknown,intensity: nil) }
        func phase(_ value: Double) -> EmissionPhase { value<=0.001 ? .off : value<=0.10 ? .dim : .on }
        let state = phase(events[first].intensity)
        for i in first...last where phase(events[i].intensity) != state { return EmissionWindow(phase: .transition,intensity: nil) }
        return EmissionWindow(phase: state,intensity: events[first].intensity)
    }
}

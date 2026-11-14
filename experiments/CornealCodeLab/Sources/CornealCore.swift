import Foundation

enum TrialMode: String, Codable, CaseIterable, Identifiable {
    case steady, coded, dark, motion
    var id: String { rawValue }
    var title: String { switch self { case .steady: return "常亮白点（均值对照）"; case .coded: return "明暗编码白点"; case .dark: return "全黑阴性对照"; case .motion: return "固定注视＋移动光点" } }
}

struct TrialConfiguration: Codable {
    var mode: TrialMode = .steady
    var dotDiameter: Double = 64
    var dotY: Double = 0.25
    var screenBrightness: Double = 1
    var mean: Double = 0.525
    var modulation: Double = 0.475
    var chipSeconds: Double = 0.5
    var distanceCM: Double = 33
    var glasses: String = "未戴镜"
    var lighting: String = "室内普通光"
    var reuseExposure: Bool = true
    var probeDiameter: Double = 64
    var probeCount: Int = 1
    var colorfulProbes: Bool = false
    var motionScale: Double = 1
    var duration: Double { Double(StimulusCode.bits.count) * chipSeconds }
    init() {}
    enum CodingKeys: String, CodingKey { case mode, dotDiameter, dotY, screenBrightness, mean, modulation, chipSeconds, distanceCM, glasses, lighting, reuseExposure, probeCount, colorfulProbes, motionScale, probeDiameter }
    /// 旧轮次缺少运动参数时仍可打开；保留旧强度，不能用新默认值改写旧实验条件。
    init(from decoder: Decoder) throws {
        let v = try decoder.container(keyedBy: CodingKeys.self)
        let rawMode = try v.decodeIfPresent(String.self,forKey: .mode) ?? "steady"
        mode = rawMode == "peak" ? .steady : (TrialMode(rawValue: rawMode) ?? .steady)
        dotDiameter = try v.decodeIfPresent(Double.self,forKey: .dotDiameter) ?? 32
        dotY = try v.decodeIfPresent(Double.self,forKey: .dotY) ?? 0.25
        screenBrightness = try v.decodeIfPresent(Double.self,forKey: .screenBrightness) ?? 0.8
        mean = try v.decodeIfPresent(Double.self,forKey: .mean) ?? 0.8
        modulation = try v.decodeIfPresent(Double.self,forKey: .modulation) ?? 0.15
        chipSeconds = try v.decodeIfPresent(Double.self,forKey: .chipSeconds) ?? 0.5
        distanceCM = try v.decodeIfPresent(Double.self,forKey: .distanceCM) ?? 33
        glasses = try v.decodeIfPresent(String.self,forKey: .glasses) ?? "未戴镜"
        lighting = try v.decodeIfPresent(String.self,forKey: .lighting) ?? "室内普通光"
        reuseExposure = try v.decodeIfPresent(Bool.self,forKey: .reuseExposure) ?? true
        probeCount = try v.decodeIfPresent(Int.self,forKey: .probeCount) ?? 1
        colorfulProbes = try v.decodeIfPresent(Bool.self,forKey: .colorfulProbes) ?? false
        motionScale = try v.decodeIfPresent(Double.self,forKey: .motionScale) ?? 1
        probeDiameter = try v.decodeIfPresent(Double.self,forKey: .probeDiameter) ?? dotDiameter
        if mode == .motion && !v.contains(.probeDiameter) { dotDiameter = 24 }
        if rawMode == "peak" { mean = 1 }
    }
}

enum StimulusCode {
    /// 6 位最大长度序列加平衡位，64 码元、正负各 32；默认 32 秒，仅改变小白点灰度。
    static let bits: [Double] = {
        var state = 0x3F
        var result: [Double] = []
        for _ in 0..<63 {
            result.append(state & 1 == 1 ? 1 : -1)
            let feedback = (state ^ (state >> 1)) & 1
            state = (state >> 1) | (feedback << 5)
        }
        result.append(-result.reduce(0, +))
        return result
    }()
    static func value(at seconds: Double, configuration: TrialConfiguration, shift: Int = 0) -> Double {
        guard seconds >= 0, seconds < configuration.duration else { return 0 }
        let index = Int(seconds / configuration.chipSeconds)
        return bits[(index + shift + bits.count) % bits.count]
    }
    static func intensity(at seconds: Double, configuration c: TrialConfiguration) -> Double {
        switch c.mode {
        case .dark: return 0
        case .motion: return 1
        case .steady: return c.mean
        case .coded: return c.mean + c.modulation * value(at: seconds, configuration: c)
        }
    }
}

struct DisplayEvent: Codable {
    let hostTime: Double
    let intensity: Double
    let code: Double
    let chip: Int
    let callbackHostTime: Double
    var screenBrightness: Double = 0
    var probes: [ProbeEvent]?
}

struct LabPoint: Codable { var x: Double; var y: Double }
struct LabRect: Codable { var x: Double; var y: Double; var width: Double; var height: Double }

struct PatchSample {
    let eye: String
    let hostTime: Double
    let pixels: [Float]
    let roi: LabRect
    let pupil: LabPoint
    let rawCandidate: LabPoint?
    let saturation: Double
    var iris: IrisRegion? = nil
    var rgb: [UInt8]? = nil
    var nuisance: [Double]? = nil
    var baselinePupil: LabPoint? = nil
    var baselineReason: String? = nil
}

struct FrameRecord: Codable {
    var index: Int
    var pts: Double
    var hostTime: Double
    var exposureSeconds: Double
    var iso: Float
    var width: Int
    var height: Int
    var status: String
    var eye: String?
    var roi: LabRect?
    var pupil: LabPoint?
    var rawCandidate: LabPoint?
    var saturation: Double?
    var patchOffset: Int?
    var iris: IrisRegion?
    var rgbPatchOffset: Int?
    var nuisance: [Double]?
    var baselinePupil: LabPoint?
    var baselineReason: String?
}

struct EyeAnalysis: Codable {
    let eye: String
    let samples: Int
    let rawCandidateRate: Double
    let point: LabPoint?
    let trainCorrelation: Double?
    let heldOutCorrelation: Double?
    let wrongCodeCorrelation: Double?
    let amplitude: Double?
    let lagSeconds: Double?
    let codedEvidence: Bool
    let message: String
    let heatmap: [Double]
    let traceTime: [Double]
    let traceSignal: [Double]
    let traceCode: [Double]
    var codingApplied: Bool? = nil
    var referenceIris: IrisRegion? = nil
    var motionResults: [MotionAnalysis]? = nil
    var patchWidth: Int? = nil
    var referenceHostTime: Double? = nil
}

enum CornealAnalyzer {
    static let width = 128
    static let height = 64
    static let count = width * height

    /// 眼区配准后的局部高通，降低整个脸随屏幕一起变亮造成的伪相关。
    static func highPass(_ pixels: [Float]) -> [Float] {
        guard pixels.count == count else { return [] }
        var integral = [Double](repeating: 0, count: (width + 1) * (height + 1))
        for y in 0..<height {
            var row = 0.0
            for x in 0..<width {
                row += Double(pixels[y * width + x])
                integral[(y+1)*(width+1)+x+1] = integral[y*(width+1)+x+1] + row
            }
        }
        var result = pixels
        for y in 0..<height { for x in 0..<width {
            let x0 = max(0, x-3), x1 = min(width, x+4), y0 = max(0, y-3), y1 = min(height, y+4)
            let sum = integral[y1*(width+1)+x1] - integral[y0*(width+1)+x1] - integral[y1*(width+1)+x0] + integral[y0*(width+1)+x0]
            result[y*width+x] -= Float(sum / Double((x1-x0)*(y1-y0)))
        } }
        return result
    }

    static func correlation(_ x: [Double], _ y: [Double]) -> Double? {
        guard x.count == y.count, x.count >= 8 else { return nil }
        let mx = x.reduce(0,+)/Double(x.count), my = y.reduce(0,+)/Double(y.count)
        var xx = 0.0, yy = 0.0, xy = 0.0
        for i in x.indices { let a = x[i]-mx, b = y[i]-my; xx += a*a; yy += b*b; xy += a*b }
        guard xx > 1e-7, yy > 1e-7 else { return nil }
        return xy / sqrt(xx*yy)
    }

    static func analyze(eye: String, samples input: [PatchSample], events: [DisplayEvent],
                        start: Double, configuration c: TrialConfiguration) -> EyeAnalysis {
        if c.mode == .motion { return MotionDecoder.analyze(eye: eye,samples: input,events: events,start: start,configuration: c) }
        let samples = input.filter { $0.eye == eye && $0.pixels.count == count && $0.saturation <= 0.15 && $0.iris != nil }
        let rawRate = samples.isEmpty ? 0 : Double(samples.filter { $0.rawCandidate != nil }.count) / Double(samples.count)
        func empty(_ message: String) -> EyeAnalysis {
            var result = EyeAnalysis(eye: eye, samples: samples.count, rawCandidateRate: rawRate, point: nil, trainCorrelation: nil,
                        heldOutCorrelation: nil, wrongCodeCorrelation: nil, amplitude: nil, lagSeconds: nil,
                        codedEvidence: false, message: message, heatmap: [], traceTime: [], traceSignal: [], traceCode: [])
            result.referenceIris = samples.last?.iris; result.referenceHostTime = samples.last?.hostTime; result.patchWidth = width
            return result
        }
        guard samples.count >= 60 else { return empty("有效眼区不足 60 帧，请检查距离、光照和头部稳定性。") }
        let train = samples.indices.filter { samples[$0].hostTime-start < c.duration/2 }
        let test = samples.indices.filter { samples[$0].hostTime-start >= c.duration/2 }
        guard train.count >= 25, test.count >= 25, events.count > 20 else { return empty("前后半段或显示日志不足，无法做独立验证。") }
        let filtered = samples.map { highPass($0.pixels) }
        func reference(_ time: Double, shift: Int = 0) -> Double {
            // 使用显示回调日志；它是计划合成时刻，不是光电二极管测得的物理发光时刻。
            var lo = 0, hi = events.count
            while lo < hi { let m = (lo+hi)/2; if events[m].hostTime <= time { lo = m+1 } else { hi = m } }
            guard lo > 0 else { return 0 }
            return shift == 0 ? events[lo-1].code : StimulusCode.bits[(events[lo-1].chip+shift)%StimulusCode.bits.count]
        }
        let searchable = (0..<count).filter { p in
            let point = LabPoint(x: (Double(p%width)+0.5)/Double(width),y: (Double(p/width)+0.5)/Double(height))
            return train.allSatisfy { samples[$0].iris?.contains(point) == true }
        }
        var bestIndex: Int?, bestLag = 0.0, bestScore = -Double.infinity, bestR = 0.0, bestAmplitude = 0.0
        var heat = [Double](repeating: 0, count: count)
        for step in -3...9 {
            let lag = Double(step)/30
            let codes = train.map { reference(samples[$0].hostTime-lag) }
            let meanC = codes.reduce(0,+)/Double(codes.count)
            let cc = codes.map { ($0-meanC)*($0-meanC) }.reduce(0,+)
            guard cc > 10 else { continue }
            for p in searchable {
                var sum = 0.0, sum2 = 0.0, cross = 0.0
                for (j, i) in train.enumerated() { let y = Double(filtered[i][p]); sum += y; sum2 += y*y; cross += (codes[j]-meanC)*y }
                let variance = sum2 - sum*sum/Double(train.count)
                guard variance > 1e-5 else { continue }
                let r = cross/sqrt(cc*variance), amplitude = cross/cc
                let score = max(0,r)*max(0,amplitude)
                heat[p] = max(heat[p],score)
                if score > bestScore { bestScore = score; bestIndex = p; bestLag = lag; bestR = r; bestAmplitude = amplitude }
            }
        }
        guard let p = bestIndex else { return empty("未找到可解调位置。") }
        let point = LabPoint(x: (Double(p%width)+0.5)/Double(width),y: (Double(p/width)+0.5)/Double(height))
        let heldIndices = test.filter { samples[$0].iris?.contains(point) == true }
        guard heldIndices.count>=25,Double(heldIndices.count)>=Double(test.count)*0.6 else { return empty("后半段候选位于虹膜内区的有效覆盖不足。") }
        let heldSignal = heldIndices.map { Double(filtered[$0][p]) }
        let heldCode = heldIndices.map { reference(samples[$0].hostTime-bestLag) }
        let held = correlation(heldSignal, heldCode)
        // 空间位置和延迟只用前半段选择；后半段固定验证，并与错码对照。
        let nullScores = [7,13,23,31].compactMap { shift in
            correlation(heldSignal, heldIndices.map { reference(samples[$0].hostTime-bestLag,shift: shift) })
        }
        let wrong = nullScores.map(abs).max() ?? 1
        let evidence = c.mode == .coded && bestR >= 0.35 && (held ?? -1) >= 0.30 && (held ?? -1) > wrong + 0.15 && bestAmplitude >= 0.5
        var result = EyeAnalysis(eye: eye, samples: samples.count, rawCandidateRate: rawRate,
            point: LabPoint(x: (Double(p % width)+0.5)/Double(width), y: (Double(p/width)+0.5)/Double(height)),
            trainCorrelation: bestR, heldOutCorrelation: held, wrongCodeCorrelation: wrong, amplitude: bestAmplitude,
            lagSeconds: bestLag, codedEvidence: evidence,
            message: c.mode != .coded ? "本轮未发出编码；伪码相关仅用于检查偶然相关，亮点候选需人工确认。" : evidence ? "有独立后半段编码响应，仍需人工确认位于角膜而非镜片或眼睑。" : "编码证据不足；不能把热图最高点当作已定位映光。",
            heatmap: heat, traceTime: samples.map { $0.hostTime-start }, traceSignal: filtered.map { Double($0[p]) },
            traceCode: samples.map { reference($0.hostTime-bestLag) })
        result.codingApplied = c.mode == .coded
        result.patchWidth = width; let referenceSample = samples.last { $0.iris?.contains(point) == true }
        result.referenceIris = referenceSample?.iris; result.referenceHostTime = referenceSample?.hostTime
        return result
    }
}

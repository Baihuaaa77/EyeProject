import Foundation

enum TrialMode: String, Codable, CaseIterable, Identifiable {
    case steady, coded, dark, motion, screenTarget, screenBlank, pattern, patternCoded
    var id: String { rawValue }
    var title: String { switch self { case .steady: return "常亮白点（均值对照）"; case .coded: return "明暗编码白点"; case .dark: return "全黑阴性对照"; case .motion: return "固定注视＋移动光点（历史）"; case .screenTarget: return "恒亮全屏＋黑色注视点（历史）"; case .screenBlank: return "恒亮全屏（历史对照）"; case .pattern: return "结构图案（常亮）"; case .patternCoded: return "结构图案（明暗编码）" } }
    var isScreen: Bool { self == .screenTarget || self == .screenBlank }
    var isPattern: Bool { self == .pattern || self == .patternCoded }
    var usesTemporalCode: Bool { self == .coded || self == .patternCoded }
    static let available: [TrialMode] = [.pattern,.patternCoded,.coded,.steady,.dark]
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
    var prefer4K: Bool = true
    var captureFPS: Int = 30
    var exposureMS: Double = 20
    var exposureISO: Double = 100
    var chipCount: Int = 64
    var transitionGuardMS: Double = 0
    var colorCode: Bool = false
    var screenLevel: Double = 0.7
    var displayWidth: Double = 390
    var displayHeight: Double = 844
    var automaticDistance: Bool = true
    var screenWidthMM: Double = 65
    var screenHeightMM: Double = 140
    var screenSizeSource: String = "未识别，使用屏幕尺寸假设"
    var cornealRadiusMM: Double = 7.8
    var targetPattern: FixationPattern = .ringDot
    var centerDiameter: Double { mode.isPattern ? dotDiameter*FixationPattern.centerFraction : dotDiameter }
    static var patternDefault: TrialConfiguration {
        var c = TrialConfiguration(); c.mode = .pattern; c.dotDiameter = 288; c.dotY = 0.35; c.mean = 1; return c
    }
    mutating func selectMode(_ value: TrialMode) {
        mode = value
        if value.isPattern {
            if dotDiameter<192 { dotDiameter = 288 }
            if dotY<0.25 { dotY = 0.35 }
            colorCode = false
        } else { dotDiameter = min(160,dotDiameter) }
        if value == .pattern { mean = 1 }
        else if value.usesTemporalCode || value == .steady { mean = modulation == 0.15 ? 0.8 : 1-modulation }
    }
    var validationError: String? {
        let values = [dotDiameter,dotY,screenBrightness,mean,modulation,chipSeconds,distanceCM,probeDiameter,motionScale,screenLevel,displayWidth,displayHeight,screenWidthMM,screenHeightMM,cornealRadiusMM]
        guard values.allSatisfy({ $0.isFinite }) else { return "实验参数包含无效数值" }
        guard (8...(mode.isPattern ? 320 : 160)).contains(dotDiameter),(0.1...0.9).contains(dotY),(0.1...1).contains(screenBrightness),
              (0.1...2).contains(chipSeconds),(15...100).contains(distanceCM),(1...3).contains(probeCount),
              (8...160).contains(probeDiameter),(0.1...1.5).contains(motionScale),(0.2...1).contains(screenLevel),
              displayWidth>=200,displayHeight>displayWidth,(0...1).contains(mean),modulation>=0,
              screenWidthMM>0,screenHeightMM>0,(6...10).contains(cornealRadiusMM) else { return "请检查距离（15–100 cm）、尺寸、亮度及编码参数" }
        guard [30,60].contains(captureFPS),(1...30).contains(exposureMS),exposureMS<=1000/Double(captureFPS),
              exposureISO.isFinite,(20...1600).contains(exposureISO),(64...1024).contains(chipCount),chipCount%64 == 0,
              transitionGuardMS.isFinite,(0...50).contains(transitionGuardMS) else { return "曝光时长须小于帧周期，码元数须为64的倍数，ISO需在20–1600内" }
        if mode.usesTemporalCode && (mean-modulation<0 || mean+modulation>1) { return "编码亮度超出 0–100%" }
        if mode.isPattern && (dotDiameter>displayWidth*0.9 || dotY*displayHeight<dotDiameter/2+64 || (1-dotY)*displayHeight<dotDiameter/2+34) { return "图案超出安全显示范围，请缩小图案或向屏幕中央移动" }
        return nil
    }
    var duration: Double { Double(chipCount) * chipSeconds }
    init() {}
    enum CodingKeys: String, CodingKey { case mode, dotDiameter, dotY, screenBrightness, mean, modulation, chipSeconds, distanceCM, glasses, lighting, reuseExposure, probeCount, colorfulProbes, motionScale, probeDiameter, screenLevel, displayWidth, displayHeight, prefer4K, captureFPS, exposureMS, exposureISO, chipCount, transitionGuardMS, colorCode, automaticDistance, screenWidthMM, screenHeightMM, screenSizeSource, cornealRadiusMM, targetPattern }
    /// 旧轮次缺少运动参数时仍可打开；保留旧强度，不能用新默认值改写旧实验条件。
    init(from decoder: Decoder) throws {
        let v = try decoder.container(keyedBy: CodingKeys.self)
        let rawMode = try v.decodeIfPresent(String.self,forKey: .mode) ?? "steady"
        guard rawMode == "peak" || TrialMode(rawValue: rawMode) != nil else { throw DecodingError.dataCorruptedError(forKey: .mode,in: v,debugDescription: "未知实验模式，不能按常亮对照解释") }
        mode = rawMode == "peak" ? .steady : TrialMode(rawValue: rawMode)!
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
        prefer4K = try v.decodeIfPresent(Bool.self,forKey: .prefer4K) ?? false
        captureFPS = try v.decodeIfPresent(Int.self,forKey: .captureFPS) ?? 30
        exposureMS = try v.decodeIfPresent(Double.self,forKey: .exposureMS) ?? 20
        exposureISO = try v.decodeIfPresent(Double.self,forKey: .exposureISO) ?? 100
        chipCount = try v.decodeIfPresent(Int.self,forKey: .chipCount) ?? 64
        transitionGuardMS = try v.decodeIfPresent(Double.self,forKey: .transitionGuardMS) ?? 0
        colorCode = try v.decodeIfPresent(Bool.self,forKey: .colorCode) ?? false
        screenLevel = try v.decodeIfPresent(Double.self,forKey: .screenLevel) ?? 0.7
        displayWidth = try v.decodeIfPresent(Double.self,forKey: .displayWidth) ?? 390
        displayHeight = try v.decodeIfPresent(Double.self,forKey: .displayHeight) ?? 844
        automaticDistance = try v.decodeIfPresent(Bool.self,forKey: .automaticDistance) ?? false
        screenWidthMM = try v.decodeIfPresent(Double.self,forKey: .screenWidthMM) ?? 65
        screenHeightMM = try v.decodeIfPresent(Double.self,forKey: .screenHeightMM) ?? 140
        screenSizeSource = try v.decodeIfPresent(String.self,forKey: .screenSizeSource) ?? "旧记录的屏幕尺寸假设"
        cornealRadiusMM = try v.decodeIfPresent(Double.self,forKey: .cornealRadiusMM) ?? 7.8
        targetPattern = try v.decodeIfPresent(FixationPattern.self,forKey: .targetPattern) ?? .ringDot
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
        guard seconds.isFinite,configuration.chipSeconds.isFinite,configuration.chipSeconds>0,seconds >= 0, seconds < configuration.duration else { return 0 }
        let index = Int(seconds / configuration.chipSeconds)
        return bits[((index + shift % bits.count) % bits.count + bits.count) % bits.count]
    }
    static func intensity(at seconds: Double, configuration c: TrialConfiguration) -> Double {
        switch c.mode {
        case .dark: return 0
        case .motion: return 1
        case .screenTarget, .screenBlank: return c.screenLevel
        case .steady,.pattern: return c.mean
        case .coded,.patternCoded: return c.mean + c.modulation * value(at: seconds, configuration: c)
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
    var nativeOffset: Int?
    var nativeROI: LabRect?
    var landmarkFresh: Bool? = nil
    var landmarkAgeSeconds: Double? = nil
    var distanceCM: Double? = nil
    var focalLengthPx: Double? = nil
    var distanceSource: String? = nil
    var processingMS: Double? = nil
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
    var shape: SpotShape? = nil
    var heldOutShape: SpotShape? = nil
    var shapeCoverage: Double? = nil
    var screenResult: ScreenAnalysis? = nil
    var validFrameCoverage: Double? = nil
    var rejectionCounts: [String:Int]? = nil
    var shiftNullCorrelations: [Double]? = nil
    var shiftRankFraction: Double? = nil
    var subpixelPoint: LabPoint? = nil
    var heldOutSubpixelPoint: LabPoint? = nil
    var opticalPrediction: OpticalPrediction? = nil
    var tracking: GlintTrackingResult? = nil
}

enum CornealAnalyzer {
    static let width = 128
    static let height = 64
    static let count = width * height

    static func validSamples(_ input: [PatchSample],eye: String,start: Double,duration: Double) -> [PatchSample] {
        var times = Set<Double>()
        return input.filter { s in
            s.eye == eye && s.hostTime.isFinite && s.hostTime>=start && s.hostTime<start+duration &&
            s.pixels.count == count && s.pixels.allSatisfy({ $0.isFinite }) && s.saturation.isFinite && s.saturation<=0.15 &&
            s.iris != nil && s.roi.width>0 && s.roi.height>0 && times.insert(s.hostTime).inserted
        }.sorted { $0.hostTime<$1.hostTime }
    }

    static func meanImage(_ samples: [PatchSample],indices: [Int]) -> [Float] {
        var mean = [Float](repeating: 0,count: count)
        guard !indices.isEmpty else { return mean }
        for i in indices { for p in 0..<count { mean[p] += samples[i].pixels[p]/Float(indices.count) } }
        return mean
    }

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
        guard x.count == y.count, x.count >= 8,x.allSatisfy({ $0.isFinite }),y.allSatisfy({ $0.isFinite }) else { return nil }
        let mx = x.reduce(0,+)/Double(x.count), my = y.reduce(0,+)/Double(y.count)
        var xx = 0.0, yy = 0.0, xy = 0.0
        for i in x.indices { let a = x[i]-mx, b = y[i]-my; xx += a*a; yy += b*b; xy += a*b }
        guard xx > 1e-7, yy > 1e-7 else { return nil }
        return xy / sqrt(xx*yy)
    }

    static func analyze(eye: String, samples input: [PatchSample], events: [DisplayEvent],
                        start: Double, configuration c: TrialConfiguration) -> EyeAnalysis {
        let input = input.filter { sample in
            guard c.mode == .coded,c.transitionGuardMS>0 else { return true }
            let t = sample.hostTime-start
            return !events.contains { event in abs(sample.hostTime-event.hostTime)<c.transitionGuardMS/1000 } && t>=0
        }
        if c.mode == .motion { return MotionDecoder.analyze(eye: eye,samples: input,events: events,start: start,configuration: c) }
        if c.mode.isScreen { return ScreenReflection.analyze(eye: eye,samples: input,start: start,configuration: c) }
        let samples = validSamples(input,eye: eye,start: start,duration: c.duration)
        let events = events.filter { $0.hostTime.isFinite && $0.code.isFinite && (0..<c.chipCount).contains($0.chip) }.sorted { $0.hostTime<$1.hostTime }
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
        guard Set(train.map { Int((samples[$0].hostTime-start)/c.chipSeconds) }).count>=16,
              Set(test.map { Int((samples[$0].hostTime-start)/c.chipSeconds) }).count>=16 else { return empty("有效帧集中在少数码元，时间覆盖不足。") }
        let filtered = samples.map { sample -> [Float] in
            if c.colorCode,let rgb = sample.rgb,rgb.count == count*3 {
                return highPass((0..<count).map { Float(rgb[$0*3])-Float(rgb[$0*3+1]) })
            }
            return highPass(sample.pixels)
        }
        let anchor = samples[train[train.count/2]]
        let trainShapes = SpotGeometry.shapes(pixels: meanImage(samples,indices: train),iris: anchor.iris!,roi: anchor.roi).filter(\.accepted)
        guard !trainShapes.isEmpty else { return empty("前半段没有满足近圆形、局部饱和度和原生分辨率的候选。") }
        func reference(_ time: Double, shift: Int = 0) -> Double {
            // 使用显示回调日志；它是计划合成时刻，不是光电二极管测得的物理发光时刻。
            var lo = 0, hi = events.count
            while lo < hi { let m = (lo+hi)/2; if events[m].hostTime <= time { lo = m+1 } else { hi = m } }
            guard lo > 0,time-events[lo-1].hostTime<=c.chipSeconds+0.1 else { return 0 }
            return shift == 0 ? events[lo-1].code : StimulusCode.bits[(events[lo-1].chip+shift)%StimulusCode.bits.count]
        }
        let searchable = (0..<count).filter { p in
            let point = LabPoint(x: (Double(p%width)+0.5)/Double(width),y: (Double(p/width)+0.5)/Double(height))
            return SpotGeometry.nearest(trainShapes,to: point) != nil && Double(train.filter { samples[$0].iris?.contains(point) == true }.count)>=Double(train.count)*0.8
        }
        var bestIndex: Int?, bestLag = 0.0, bestScore = -Double.infinity, bestR = 0.0, bestAmplitude = 0.0
        var heat = [Double](repeating: 0, count: count)
        for step in -3...9 {
            let lag = Double(step)/30
            let codes = train.map { reference(samples[$0].hostTime-lag) }
            for p in searchable {
                let location = LabPoint(x: (Double(p%width)+0.5)/Double(width),y: (Double(p/width)+0.5)/Double(height))
                let selected = train.indices.filter { codes[$0] != 0 && samples[train[$0]].iris?.contains(location) == true }
                guard selected.count>=25 else { continue }
                let meanC = selected.map { codes[$0] }.reduce(0,+)/Double(selected.count)
                let cc = selected.map { pow(codes[$0]-meanC,2) }.reduce(0,+)
                guard cc>10 else { continue }
                var sum = 0.0, sum2 = 0.0, cross = 0.0
                for j in selected { let y = Double(filtered[train[j]][p]); sum += y; sum2 += y*y; cross += (codes[j]-meanC)*y }
                let variance = sum2 - sum*sum/Double(selected.count)
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
        let trainShape = SpotGeometry.nearest(trainShapes,to: point)
        let heldAnchor = samples[heldIndices[heldIndices.count/2]]
        let heldShape = SpotGeometry.nearest(SpotGeometry.shapes(pixels: meanImage(samples,indices: heldIndices),iris: heldAnchor.iris!,roi: heldAnchor.roi),to: point)
        let shapeFrames = heldIndices.filter { i in
            SpotGeometry.nearest(SpotGeometry.shapes(pixels: samples[i].pixels,iris: samples[i].iris!,roi: samples[i].roi,highPass: filtered[i]),to: point)?.accepted == true
        }.count
        let shapeCoverage = Double(shapeFrames)/Double(heldIndices.count)
        // 空间位置和延迟只用前半段选择；后半段固定验证，并与错码对照。
        let nullScores = Array(1..<64).compactMap { shift in
            correlation(heldSignal, heldIndices.map { reference(samples[$0].hostTime-bestLag,shift: shift) })
        }
        let wrong = nullScores.map(abs).max() ?? 1
        let validReferences = heldCode.filter { abs($0) == 1 }.count
        let evidence = c.mode == .coded && bestR >= 0.35 && (held ?? -1) >= 0.30 && (held ?? -1) > wrong + 0.15 && bestAmplitude >= 0.5 && heldShape?.accepted == true && Double(validReferences)>=Double(heldCode.count)*0.9
        var result = EyeAnalysis(eye: eye, samples: samples.count, rawCandidateRate: rawRate,
            point: LabPoint(x: (Double(p % width)+0.5)/Double(width), y: (Double(p/width)+0.5)/Double(height)),
            trainCorrelation: bestR, heldOutCorrelation: held, wrongCodeCorrelation: wrong, amplitude: bestAmplitude,
            lagSeconds: bestLag, codedEvidence: evidence,
            message: c.mode != .coded ? "本轮未发出编码；伪码相关仅用于检查偶然相关，亮点候选需人工确认。" : evidence ? "平均PSF候选通过后半段编码验证，仍需人工确认角膜来源。" : "未同时通过编码、平均PSF形态、局部曝光和参考覆盖门槛，不能把热图最高点当作已定位映光。",
            heatmap: heat, traceTime: samples.map { $0.hostTime-start }, traceSignal: filtered.map { Double($0[p]) },
            traceCode: samples.map { reference($0.hostTime-bestLag) })
        result.codingApplied = c.mode == .coded
        result.shiftNullCorrelations = nullScores
        result.shiftRankFraction = Double(1+nullScores.filter { abs($0)>=abs(held ?? 0) }.count)/Double(1+nullScores.count)
        // 在固定训练位置周围积累有符号响应，利用PSF得到亚像素质心；不验证光源几何。
        func lockInCenter(_ indices: [Int]) -> LabPoint? {
            let codes = indices.map { reference(samples[$0].hostTime-bestLag) }
            guard let mean = codes.isEmpty ? nil : codes.reduce(0,+)/Double(codes.count) else { return nil }
            let variance = codes.map { pow($0-mean,2) }.reduce(0,+)
            guard variance>1 else { return nil }
            var response: [(Double,Double,Double)] = []
            for y in max(0,p/width-3)...min(height-1,p/width+3) { for x in max(0,p%width-3)...min(width-1,p%width+3) {
                var cross = 0.0
                for (j,i) in indices.enumerated() { cross += (codes[j]-mean)*Double(filtered[i][y*width+x]) }
                response.append((Double(x)+0.5,Double(y)+0.5,max(0,cross/variance)))
            } }
            let threshold = (response.map { $0.2 }.max() ?? 0)*0.5
            let selected = response.filter { $0.2>threshold },weight = response.filter { $0.2>threshold }.map { $0.2 }.reduce(0,+)
            guard weight>0 else { return nil }
            return LabPoint(x: selected.map { $0.0*$0.2 }.reduce(0,+)/weight/Double(width),y: selected.map { $0.1*$0.2 }.reduce(0,+)/weight/Double(height))
        }
        result.subpixelPoint = lockInCenter(train)
        result.heldOutSubpixelPoint = lockInCenter(heldIndices)
        result.shape = trainShape; result.heldOutShape = heldShape; result.shapeCoverage = shapeCoverage
        result.patchWidth = width; let referenceSample = samples.last { $0.iris?.contains(point) == true }
        result.referenceIris = referenceSample?.iris; result.referenceHostTime = referenceSample?.hostTime
        return result
    }
}

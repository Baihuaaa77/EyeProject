import Foundation

enum NativeGlintTracking {
    /// BGRA用于相机原生临时文件，gray8用于原视频解码后的离线复算。
    static func track(records: [FrameRecord],pixels: Data,bytesPerPixel: Int,configuration c: TrialConfiguration,
                      events: [DisplayEvent],start: Double) -> [GlintTrackingResult] {
        let rows = records.sorted { $0.hostTime<$1.hostTime }
        var trackers = ["image_left":GlintTracker(),"image_right":GlintTracker()]
        var estimates: [String:[GlintEstimate]] = [:]
        struct Seed { var relative: LabPoint; var candidate: GlintTracker.Candidate; var weight: Double }
        var seeds: [String:[Seed]] = [:]
        var references: [String:LabPoint] = [:]
        // 用前八秒建立持续亮点身份。光码轮以训练段亮暗差抵消恒定环境反光；后半段不参与选点。
        for row in rows where row.index%3 == 0 && row.hostTime-start>=0.4 && row.hostTime-start<min(8,c.duration/2) {
            guard let eye = row.eye,let extracted = extract(row: row,pixels: pixels,bytesPerPixel: bytesPerPixel,configuration: c) else { continue }
            let (gray,w,h,_,search,expected) = extracted
            let radius = search.radiusX*Double(w)
            let candidates = GlintTracker.candidates(pixels: gray,width: w,height: h,iris: search,expected: expected)
            for b in candidates where b.snr>=5 && b.shape>=0.6 {
                let relative = LabPoint(x: (b.point.x-search.center.x*Double(w))/radius,y: (b.point.y-search.center.y*Double(h))/radius)
                let size = exp(-pow(log(max(0.5,b.diameter)/max(1,expected)),2)/0.8)
                let prior = exp(-0.5*(relative.x*relative.x/0.36+pow((relative.y+0.3)/0.45,2)))
                var weight = min(1,b.snr/15)*pow(b.shape,2)*size*prior
                if c.mode.isPattern {
                    let match = FixationPatternMatcher.match(pixels: gray,width: w,height: h,center: b.point,expectedDiameter: expected/FixationPattern.centerFraction,pattern: c.targetPattern)
                    weight *= 0.05+3*max(0,(match?.correlation ?? 0)-(match?.alternativeCorrelation ?? 1)+0.15)
                }
                if c.mode.usesTemporalCode {
                    let code = events.last(where: { $0.hostTime<=row.hostTime-1.0/30 })?.code ?? 0
                    weight *= code*min(2,b.signal/60)
                }
                if c.mode == .motion,let probes = events.last(where: { $0.hostTime<=row.hostTime-1.0/30 })?.probes,!probes.isEmpty {
                    // 用已知外围光源的相对布局辅助区分固定白点；允许成像方向和尺度存在误差。
                    var layout = 0.0
                    for sx in [-1.0,1.0] { for sy in [-1.0,1.0] { for scale in [0.8,1.0,1.2] {
                        var sum = 0.0
                        for probe in probes {
                            let x = b.point.x+sx*scale*expected*(probe.position.x-0.5)*c.displayWidth/c.dotDiameter
                            let y = b.point.y+sy*scale*expected*(probe.position.y-c.dotY)*c.displayHeight/c.dotDiameter
                            let distance = candidates.filter { hypot($0.point.x-b.point.x,$0.point.y-b.point.y)>2 }.map { hypot($0.point.x-x,$0.point.y-y) }.min() ?? 100
                            sum += exp(-0.5*pow(distance/2,2))
                        }
                        layout = max(layout,sum/Double(probes.count))
                    } } }
                    weight *= 0.1+0.9*pow(layout,2)
                }
                if abs(weight)>0.15 { seeds[eye,default: []].append(Seed(relative: relative,candidate: b,weight: weight)) }
            }
        }
        for (eye,input) in seeds where input.count>=8 {
            var best = 0.0,reference: LabPoint?
            for seed in input {
                let sum = input.reduce(0.0) { sum,other in
                    let d = hypot(seed.relative.x-other.relative.x,seed.relative.y-other.relative.y)
                    return sum+other.weight*exp(-0.5*pow(d/0.065,2))
                }
                if sum>best { best = sum; reference = seed.relative }
            }
            guard best>=5,let reference = reference else { continue }
            let cluster = input.filter { $0.weight>0 && hypot($0.relative.x-reference.x,$0.relative.y-reference.y)<0.1 }
            let weight = cluster.map(\.weight).reduce(0,+)
            let center = LabPoint(x: cluster.map { $0.relative.x*$0.weight }.reduce(0,+)/weight,y: cluster.map { $0.relative.y*$0.weight }.reduce(0,+)/weight)
            guard let seed = cluster.max(by: { $0.weight<$1.weight }) else { continue }
            trackers[eye,default: GlintTracker()].setReference(relative: center,profile: seed.candidate.profile,signal: seed.candidate.signal)
            references[eye] = center
        }
        let initialTrackers = trackers
        for row in rows {
            guard let eye = row.eye,let extracted = extract(row: row,pixels: pixels,bytesPerPixel: bytesPerPixel,configuration: c) else { continue }
            let (gray,w,h,native,search,expected) = extracted
            let result = trackers[eye,default: GlintTracker()].update(pixels: gray,width: w,height: h,roi: native,iris: search,row: row,expectedDiameter: expected,
                pattern: c.mode.isPattern ? c.targetPattern : nil,expectedPatternDiameter: c.mode.isPattern ? expected/FixationPattern.centerFraction : nil)
            estimates[eye,default: []].append(result)
        }
        var reverseTrackers = initialTrackers,backward: [String:[Int:GlintEstimate]] = [:]
        for original in rows.reversed() {
            guard let eye = original.eye,let extracted = extract(row: original,pixels: pixels,bytesPerPixel: bytesPerPixel,configuration: c) else { continue }
            let (gray,w,h,native,search,expected) = extracted
            var row = original; row.hostTime = -original.hostTime
            var result = reverseTrackers[eye,default: GlintTracker()].update(pixels: gray,width: w,height: h,roi: native,iris: search,row: row,expectedDiameter: expected,
                pattern: c.mode.isPattern ? c.targetPattern : nil,expectedPatternDiameter: c.mode.isPattern ? expected/FixationPattern.centerFraction : nil)
            result.hostTime = original.hostTime
            backward[eye,default: [:]][original.index] = result
        }
        return ["image_left","image_right"].map { eye in
            let joint = (estimates[eye] ?? []).map { forward in backward[eye]?[forward.frameIndex].map { fuse(forward,$0) } ?? forward }
            var result = GlintTrackingResult.make(eye: eye,frames: complete(joint,records: records))
            result.referenceRelative = references[eye]
            validateCode(&result,events: events,start: start,configuration: c)
            return result
        }
    }

    /// 前后向关联一致时才保留较高置信度；分歧帧不把单向跳点当作稳定观测。
    static func fuse(_ a: GlintEstimate,_ b: GlintEstimate) -> GlintEstimate {
        guard let pa = a.imagePoint,let pb = b.imagePoint else { return a.imagePoint == nil ? b : a }
        let distance = hypot(pa.x-pb.x,pa.y-pb.y),gate = max(3,(a.expectedDiameterPixels ?? 3)*1.2)
        var result = a.confidence>=b.confidence ? a : b
        if a.source.measured && b.source.measured {
            if distance<=gate {
                let weight = max(0.001,a.confidence+b.confidence)
                result.imagePoint = LabPoint(x: (pa.x*a.confidence+pb.x*b.confidence)/weight,y: (pa.y*a.confidence+pb.y*b.confidence)/weight)
                if let ra = a.irisRelative,let rb = b.irisRelative {
                    result.irisRelative = LabPoint(x: (ra.x*a.confidence+rb.x*b.confidence)/weight,y: (ra.y*a.confidence+rb.y*b.confidence)/weight)
                }
                result.confidence = sqrt(a.confidence*b.confidence)*exp(-0.15*pow(distance/gate,2))
            } else {
                result.confidence *= 0.4
                result.source = .predicted; result.measurementPoint = nil; result.observedDiameterPixels = nil; result.patternMatch = nil
            }
        } else if result.source.measured {
            result.confidence *= distance<=gate ? 0.8 : 0.5
        }
        if result.source.measured { result.source = result.confidence>=0.6 ? .observed : .weak }
        else { result.confidence = min(0.25,result.confidence) }
        result.uncertaintyPixels = max(result.uncertaintyPixels,distance/2)
        return result
    }

    private static func extract(row: FrameRecord,pixels: Data,bytesPerPixel: Int,configuration c: TrialConfiguration) -> ([Float],Int,Int,LabRect,IrisRegion,Double)? {
        guard let roi = row.roi,let native = row.nativeROI,let iris = row.iris,
              let offset = row.nativeOffset,offset>=0,native.width>=8,native.height>=8,
              native.width<=4096,native.height<=4096 else { return nil }
        let w = Int(native.width),h = Int(native.height)
        guard [1,4].contains(bytesPerPixel),offset<=pixels.count-w*h*bytesPerPixel else { return nil }
        var gray = [Float](repeating: 0,count: w*h)
        pixels.withUnsafeBytes { bytes in
            let p = bytes.bindMemory(to: UInt8.self)
            for i in gray.indices {
                if bytesPerPixel == 1 { gray[i] = Float(p[offset+i]) }
                else { let j = offset+i*4; gray[i] = 0.2126*Float(p[j+2])+0.7152*Float(p[j+1])+0.0722*Float(p[j]) }
            }
        }
        func convert(_ p: LabPoint) -> LabPoint {
            LabPoint(x: (roi.x+p.x*roi.width-native.x)/native.width,y: (roi.y+p.y*roi.height-native.y)/native.height)
        }
        let rx = (iris.boundaryRadiusX ?? iris.radiusX)*roi.width/native.width
        let ry = (iris.boundaryRadiusY ?? iris.radiusY)*roi.height/native.height
        let search = IrisRegion(center: convert(iris.center),radiusX: rx*0.8,radiusY: ry*0.8,confidence: iris.confidence,
            eyelid: iris.eyelid.map(convert),boundaryRadiusX: rx,boundaryRadiusY: ry)
        let diameter = rx*native.width*2
        let expected = OpticalPrediction.diameter(distanceCM: row.distanceCM ?? c.distanceCM,irisPixels: diameter,
            sourceMM: c.screenWidthMM*c.centerDiameter/c.displayWidth,radiusMM: c.cornealRadiusMM) ?? 3
        return (gray,w,h,native,search,expected)
    }

    /// 每个实际交付视频帧都有记录；缺少眼区时只能短时预测，不能计为图像检出。
    static func complete(_ input: [GlintEstimate],records: [FrameRecord]) -> [GlintEstimate] {
        var unique: [Int:FrameRecord] = [:]
        for row in records { if unique[row.index] == nil { unique[row.index] = row } }
        let rows = unique.values.sorted { $0.hostTime<$1.hostTime }
        let estimates = input.sorted { $0.hostTime<$1.hostTime }
        let byFrame = Dictionary(input.map { ($0.frameIndex,$0) },uniquingKeysWith: { a,_ in a })
        var cursor = 0
        return rows.map { row in
            if let estimate = byFrame[row.index] { return estimate }
            while cursor<estimates.count,estimates[cursor].hostTime<row.hostTime { cursor += 1 }
            let previous = cursor>0 ? estimates[cursor-1] : nil
            let next = cursor<estimates.count ? estimates[cursor] : nil
            var point = previous?.imagePoint ?? next?.imagePoint
            var confidence = 0.0,uncertainty = 100.0
            if let a = previous,let b = next,let pa = a.imagePoint,let pb = b.imagePoint,
               b.hostTime-a.hostTime<=0.35,b.hostTime>a.hostTime {
                let t = (row.hostTime-a.hostTime)/(b.hostTime-a.hostTime)
                point = LabPoint(x: pa.x+(pb.x-pa.x)*t,y: pa.y+(pb.y-pa.y)*t)
                confidence = min(a.confidence,b.confidence)*0.5
                uncertainty = max(a.uncertaintyPixels,b.uncertaintyPixels)+2
            } else if let a = previous,row.hostTime-a.hostTime<=0.35 {
                confidence = a.confidence*exp(-(row.hostTime-a.hostTime)/0.12)*0.5
                uncertainty = a.uncertaintyPixels+10*(row.hostTime-a.hostTime)
            }
            return GlintEstimate(frameIndex: row.index,pts: row.pts,hostTime: row.hostTime,imagePoint: point,measurementPoint: nil,
                irisRelative: nil,confidence: confidence,source: confidence>0 ? .predicted : .unavailable,uncertaintyPixels: uncertainty,
                signal: nil,snr: nil,observedDiameterPixels: nil,expectedDiameterPixels: nil,ambiguity: 1)
        }
    }

    static func validateCode(_ result: inout GlintTrackingResult,events input: [DisplayEvent],start: Double,configuration c: TrialConfiguration) {
        guard c.mode.usesTemporalCode else { return }
        let events = input.filter { $0.hostTime.isFinite && $0.code.isFinite && $0.chip>=0 }.sorted { $0.hostTime<$1.hostTime }
        func code(_ time: Double,_ shift: Int = 0) -> Double {
            var low = 0,high = events.count
            while low<high { let middle = (low+high)/2; if events[middle].hostTime<=time { low = middle+1 } else { high = middle } }
            guard low>0,time-events[low-1].hostTime<=c.chipSeconds+0.1 else { return 0 }
            return shift == 0 ? events[low-1].code : StimulusCode.bits[(events[low-1].chip%64+shift)%64]
        }
        let train = result.frames.filter { $0.hostTime-start<c.duration/2 && $0.signal != nil }
        let test = result.frames.filter { $0.hostTime-start>=c.duration/2 && $0.signal != nil }
        guard train.count>=25,test.count>=25 else { return }
        var best = -Double.infinity,lag = 0.0
        for step in -3...9 {
            let delay = Double(step)/30
            if let r = CornealAnalyzer.correlation(train.map { $0.signal! },train.map { code($0.hostTime-delay) }),r>best { best = r; lag = delay }
        }
        let held = CornealAnalyzer.correlation(test.map { $0.signal! },test.map { code($0.hostTime-lag) })
        let wrong = (1..<64).compactMap { shift in CornealAnalyzer.correlation(test.map { $0.signal! },test.map { code($0.hostTime-lag,shift) }) }.map(abs).max() ?? 1
        result.codeTrainCorrelation = best.isFinite ? best : nil; result.codeHeldOutCorrelation = held
        result.codeWrongCorrelation = wrong; result.codeLagSeconds = lag
        result.codeVerified = best>=0.35 && (held ?? -1)>=0.3 && (held ?? -1)>wrong+0.15
    }
}

public enum GlintReplay {
    public static func run(trial: URL,nativeDirectory: URL,output: URL) throws {
        struct Header: Decodable { var configuration: TrialConfiguration; var startHostTime: Double }
        guard !FileManager.default.fileExists(atPath: output.path) else { throw NSError(domain: "跟踪复算",code: 1,userInfo: [NSLocalizedDescriptionKey:"输出已存在，避免覆盖复核证据"]) }
        let decoder = JSONDecoder()
        let header = try decoder.decode(Header.self,from: Data(contentsOf: trial.appendingPathComponent("summary.json")))
        let events = try decoder.decode([DisplayEvent].self,from: Data(contentsOf: trial.appendingPathComponent("display_events.json")))
        let rows = try String(contentsOf: nativeDirectory.appendingPathComponent("native_frames.jsonl"),encoding: .utf8).split(whereSeparator: \.isNewline)
            .map { try decoder.decode(FrameRecord.self,from: Data($0.utf8)) }
        let data = try Data(contentsOf: nativeDirectory.appendingPathComponent("native_eyes.u8"),options: .mappedIfSafe)
        let results = NativeGlintTracking.track(records: rows,pixels: data,bytesPerPixel: 1,configuration: header.configuration,events: events,start: header.startHostTime)
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        try encoder.encode(results).write(to: output,options: .atomic)
    }
}

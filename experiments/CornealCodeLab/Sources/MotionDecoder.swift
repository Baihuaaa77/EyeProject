import Foundation

struct ProbeEvent: Codable {
    var id: Int
    var color: String
    var position: LabPoint
    var intensity: Double
}

enum MotionStimulus {
    /// 注视点保持不动；外围三个分区用不同伪随机相位，余弦过渡避免瞬移。
    static func probes(at time: Double, configuration c: TrialConfiguration, width: Double = 390, height: Double = 844, topInset: Double = 64, bottomInset: Double = 34) -> [ProbeEvent] {
        let centers = [LabPoint(x: 0.22,y: 0.75), LabPoint(x: 0.78,y: 0.75), LabPoint(x: 0.78,y: 0.17)]
        let colors = ["red","green","blue"]
        let u = max(0,time)/0.8, k = Int(u), blend = (1-cos((u-Double(k)) * .pi))/2
        func wave(_ phase: Int) -> Double {
            let a = StimulusCode.bits[(k+phase)%64], b = StimulusCode.bits[(k+phase+1)%64]
            return a+(b-a)*blend
        }
        return (0..<max(1,min(3,c.probeCount))).map { id in
            let marginX = (c.dotDiameter/2+4)/max(width,1), marginY = (c.dotDiameter/2+4)/max(height,1)
            var p = LabPoint(x: centers[id].x+0.13*c.motionScale*wave(7+id*17),y: centers[id].y+0.10*c.motionScale*wave(29+id*11))
            p.x = max(marginX,min(1-marginX,p.x)); p.y = max(marginY+topInset/max(height,1),min(1-marginY-bottomInset/max(height,1),p.y))
            // 第三个分区可能靠近上方注视点，沿横向推出固定白点，避免重叠。
            let exclusion = c.dotDiameter/2+12+8
            if hypot((p.x-0.5)*width,(p.y-c.dotY)*height)<exclusion {
                p.x = min(1-marginX,0.5+exclusion/width)
            }
            return ProbeEvent(id: id,color: c.colorfulProbes ? colors[id] : "white",position: p,intensity: 1)
        }
    }
}

struct MotionObservation: Codable {
    var time: Double
    /// 单位为眼区采样像素；不是屏幕坐标，也不是相机原始像素。
    var point: LabPoint
    var nuisance: [Double]
}

struct MotionAnalysis: Codable {
    var sourceID: Int
    var color: String
    var accepted: Bool
    var message: String
    var lagSeconds: Double
    var trainGain: Double
    var heldOutGain: Double
    var wrongTrajectoryGain: Double
    var heldOutErrorPixels: Double
    var amplitudePixels: Double
    var coverage: Double
    var confounded: Bool
    var observations: [MotionObservation]
    var predicted: [LabPoint]
    var trainMean: [Double]
    var trainScale: [Double]
    var coefficientsX: [Double]
    var coefficientsY: [Double]
    var baselineX: [Double]
    var baselineY: [Double]
}

enum MotionDecoder {
    struct Track { var observations: [MotionObservation] }
    struct Fit {
        var mean: [Double]; var scale: [Double]; var x: [Double]; var y: [Double]
        func predict(_ row: [Double]) -> LabPoint {
            let z = [1.0]+row.indices.map { (row[$0]-mean[$0])/scale[$0] }
            return LabPoint(x: zip(z,x).map(*).reduce(0,+),y: zip(z,y).map(*).reduce(0,+))
        }
    }
    static func fit(_ rows: [[Double]], _ points: [LabPoint]) -> Fit? {
        guard rows.count >= 20, let first = rows.first else { return nil }
        let n = first.count
        let mean = (0..<n).map { j in rows.map { $0[j] }.reduce(0,+)/Double(rows.count) }
        let scale = (0..<n).map { j in max(1e-6,sqrt(rows.map { pow($0[j]-mean[j],2) }.reduce(0,+)/Double(rows.count))) }
        let design = rows.map { row in [1.0]+(0..<n).map { (row[$0]-mean[$0])/scale[$0] } }
        let d = n+1
        var matrix = Array(repeating: Array(repeating: 0.0,count: d+2),count: d)
        for (r,p) in zip(design,points) { for j in 0..<d {
            for k in 0..<d { matrix[j][k] += r[j]*r[k] }
            matrix[j][d] += r[j]*p.x; matrix[j][d+1] += r[j]*p.y
        } }
        for j in 1..<d { matrix[j][j] += 0.001 }
        for j in 0..<d {
            let pivot = (j..<d).max { abs(matrix[$0][j])<abs(matrix[$1][j]) } ?? j
            matrix.swapAt(j,pivot)
            guard abs(matrix[j][j])>1e-12 else { return nil }
            let divisor = matrix[j][j]
            for k in j..<(d+2) { matrix[j][k] /= divisor }
            for i in 0..<d where i != j {
                let factor = matrix[i][j]
                for k in j..<(d+2) { matrix[i][k] -= factor*matrix[j][k] }
            }
        }
        return Fit(mean: mean,scale: scale,x: matrix.map { $0[d] },y: matrix.map { $0[d+1] })
    }
    static func squaredError(_ p: LabPoint,_ q: LabPoint) -> Double { pow(p.x-q.x,2)+pow(p.y-q.y,2) }
    static func reference(_ time: Double, id: Int, events: [DisplayEvent]) -> LabPoint? {
        guard let first = events.first, let last = events.last,time>=first.hostTime,time<=last.hostTime+0.05 else { return nil }
        var lo = 0, hi = events.count
        while lo<hi { let m = (lo+hi)/2; if events[m].hostTime<=time { lo=m+1 } else { hi=m } }
        return events[max(0,lo-1)].probes?.first { $0.id == id }?.position
    }
    /// 从局部峰独立建轨迹，不用屏幕码挑选每帧的峰，避免把随机亮斑拼成目标。
    static func tracks(samples: [PatchSample], color: String, start: Double) -> [Track] {
        var tracks: [Track] = []
        for sample in samples {
            let pixels: [Float]
            if color != "white", let rgb = sample.rgb, rgb.count == CornealAnalyzer.count*3 {
                let channel = color == "red" ? 0 : color == "green" ? 1 : 2
                pixels = (0..<CornealAnalyzer.count).map { i in
                    Float(rgb[i*3+channel])-(Float(rgb[i*3+(channel+1)%3])+Float(rgb[i*3+(channel+2)%3]))/2
                }
            } else if color == "white" { pixels = sample.pixels } else { continue }
            let hp = CornealAnalyzer.highPass(pixels), w = CornealAnalyzer.width, h = CornealAnalyzer.height
            var peaks: [(Int,Float)] = []
            for y in (h/6)..<(h*5/6) { for x in (w/5)..<(w*4/5) {
                let i = y*w+x, v = hp[i]
                if sample.iris?.contains(LabPoint(x: (Double(x)+0.5)/Double(w),y: (Double(y)+0.5)/Double(h))) == true && v>4 && v>=hp[i-1] && v>=hp[i+1] && v>=hp[i-w] && v>=hp[i+w] { peaks.append((i,v)) }
            } }
            peaks.sort { $0.1>$1.1 }
            var points: [LabPoint] = []
            for (i,_) in peaks {
                let x = i%w, y = i/w
                if points.contains(where: { hypot($0.x-Double(x),$0.y-Double(y))<3 }) { continue }
                var sx = 0.0, sy = 0.0, weight = 0.0
                for yy in (y-1)...(y+1) { for xx in (x-1)...(x+1) {
                    let v = Double(max(0,hp[yy*w+xx])); weight += v; sx += (Double(xx)+0.5)*v; sy += (Double(yy)+0.5)*v
                } }
                let point = LabPoint(x: sx/max(weight,1e-6),y: sy/max(weight,1e-6))
                guard sample.iris?.contains(LabPoint(x: point.x/Double(w),y: point.y/Double(h))) == true else { continue }
                points.append(point)
                if points.count == 20 { break }
            }
            let time = sample.hostTime-start
            var matches: [(Double,Int,Int)] = []
            for (id,track) in tracks.enumerated() {
                guard let last = track.observations.last, time-last.time<0.4 else { continue }
                for (j,p) in points.enumerated() {
                    let distance = hypot(p.x-last.point.x,p.y-last.point.y)
                    if distance<4 { matches.append((distance,id,j)) }
                }
            }
            matches.sort { $0.0<$1.0 }
            var usedTracks = Set<Int>(), usedPoints = Set<Int>()
            for (_,id,j) in matches where !usedTracks.contains(id) && !usedPoints.contains(j) {
                tracks[id].observations.append(MotionObservation(time: time,point: points[j],nuisance: sample.nuisance ?? []))
                usedTracks.insert(id); usedPoints.insert(j)
            }
            for j in points.indices where !usedPoints.contains(j) { tracks.append(Track(observations: [MotionObservation(time: time,point: points[j],nuisance: sample.nuisance ?? [])])) }
        }
        return tracks.filter { $0.observations.count>=60 }
    }

    static func decode(track: Track, id: Int, color: String, events: [DisplayEvent], start: Double, duration: Double, lag: Double, expectedSamples: Int) -> MotionAnalysis? {
        let obs = track.observations.filter { reference(start+$0.time-lag,id: id,events: events) != nil }
        let train = obs.filter { $0.time<duration/2 }, test = obs.filter { $0.time>=duration/2 }
        guard train.count>=30,test.count>=30 else { return nil }
        func row(_ o: MotionObservation,shift: Double = 0) -> [Double] {
            var t = o.time-lag+shift
            if shift != 0 { t = t.truncatingRemainder(dividingBy: duration); if t<0 { t += duration } }
            let p = reference(start+t,id: id,events: events) ?? LabPoint(x: 0.5,y: 0.5)
            return [p.x,p.y]+o.nuisance
        }
        guard let full = fit(train.map { row($0) },train.map(\.point)), let base = fit(train.map(\.nuisance),train.map(\.point)) else { return nil }
        func gain(_ segment: [MotionObservation],shift: Double = 0) -> (Double,Double) {
            let a = segment.map { squaredError($0.point,full.predict(row($0,shift: shift))) }.reduce(0,+)
            let b = segment.map { squaredError($0.point,base.predict($0.nuisance)) }.reduce(0,+)
            return (max(-10,min(1,1-a/max(b,1e-9))),sqrt(a/Double(segment.count)))
        }
        let training = gain(train).0, held = gain(test), wrong = [3.5,6.5,11.5,15.5].map { gain(test,shift: $0).0 }.max() ?? 1
        let sourcePoints = train.map { o -> LabPoint in let r = row(o); return LabPoint(x: r[0],y: r[1]) }
        let sourceMean = LabPoint(x: sourcePoints.map(\.x).reduce(0,+)/Double(train.count),y: sourcePoints.map(\.y).reduce(0,+)/Double(train.count))
        let sourceVariance = sourcePoints.map { squaredError($0,sourceMean) }.reduce(0,+)
        var confounded = train.contains { $0.nuisance.count != 4 }
        if let nuisanceFit = fit(train.map(\.nuisance),sourcePoints) {
            let error = zip(train,sourcePoints).map { squaredError($0.1,nuisanceFit.predict($0.0.nuisance)) }.reduce(0,+)
            if 1-error/max(sourceVariance,1e-9)>0.85 { confounded = true }
        }
        let sourceOnly = sourcePoints.map { full.predict([$0.x,$0.y]+Array(full.mean.dropFirst(2))) }
        let center = LabPoint(x: sourceOnly.map(\.x).reduce(0,+)/Double(sourceOnly.count),y: sourceOnly.map(\.y).reduce(0,+)/Double(sourceOnly.count))
        let amplitude = sqrt(sourceOnly.map { squaredError($0,center) }.reduce(0,+)/Double(sourceOnly.count))
        let coverage = Double(obs.count)/Double(max(expectedSamples,1))
        let accepted = training>=0.45 && held.0>=0.30 && held.0>wrong+0.20 && amplitude>=0.18 && coverage>=0.60 && !confounded
        let message = accepted ? "轨迹响应通过独立验证；请人工确认是否位于角膜。" : confounded ? "注视/头动与光点轨迹混杂，或缺少运动记录，不能确认独立光学响应。" : "未通过后半段、错轨迹、位移或覆盖率门槛。"
        return MotionAnalysis(sourceID: id,color: color,accepted: accepted,message: message,lagSeconds: lag,trainGain: training,heldOutGain: held.0,
            wrongTrajectoryGain: wrong,heldOutErrorPixels: held.1,amplitudePixels: amplitude,coverage: coverage,confounded: confounded,
            observations: obs,predicted: obs.map { full.predict(row($0)) },trainMean: full.mean,trainScale: full.scale,coefficientsX: full.x,coefficientsY: full.y,baselineX: base.x,baselineY: base.y)
    }

    static func analyze(eye: String, samples input: [PatchSample], events: [DisplayEvent], start: Double, configuration c: TrialConfiguration) -> EyeAnalysis {
        let samples = input.filter { $0.eye == eye && $0.saturation<=0.15 && $0.pixels.count == CornealAnalyzer.count && $0.iris != nil }.sorted { $0.hostTime<$1.hostTime }
        var results: [MotionAnalysis] = []
        var cache: [String:[Track]] = [:]
        for source in (events.first?.probes ?? []) {
            if cache[source.color] == nil { cache[source.color] = tracks(samples: samples,color: source.color,start: start) }
            var best: MotionAnalysis?
            // 锁定前半段得分最高的轨迹与延迟，后半段不参与挑选。
            for track in cache[source.color] ?? [] { for step in -2...8 {
                if let candidate = decode(track: track,id: source.id,color: source.color,events: events,start: start,duration: c.duration,lag: Double(step)/30,expectedSamples: samples.count),
                   candidate.amplitudePixels>=0.18,
                   candidate.observations.filter({ $0.time<c.duration/2 }).count>=max(30,Int(Double(samples.filter { $0.hostTime-start<c.duration/2 }.count)*0.60)),
                   best == nil || candidate.trainGain>(best?.trainGain ?? -10) { best = candidate }
            } }
            if let best = best { results.append(best) }
        }
        let reference = results.first?.observations.last
        let rate = samples.isEmpty ? 0 : Double(samples.filter { $0.rawCandidate != nil }.count)/Double(samples.count)
        var result = EyeAnalysis(eye: eye,samples: samples.count,rawCandidateRate: rate,
            point: reference.map { LabPoint(x: $0.point.x/Double(CornealAnalyzer.width),y: $0.point.y/Double(CornealAnalyzer.height)) },
            trainCorrelation: nil,heldOutCorrelation: nil,wrongCodeCorrelation: nil,amplitude: nil,lagSeconds: nil,codedEvidence: results.contains { $0.accepted },
            message: results.isEmpty ? "没有覆盖前后半段的稳定亮点轨迹；不能据此认定没有映光。" : "\(results.filter { $0.accepted }.count)/\(c.probeCount) 个移动光源通过轨迹验证。固定白点不参与移动解调。",
            heatmap: [],traceTime: [],traceSignal: [],traceCode: [])
        let referenceTime = reference.map { start+$0.time } ?? samples.last?.hostTime ?? start
        result.referenceIris = samples.min(by: { abs($0.hostTime-referenceTime)<abs($1.hostTime-referenceTime) })?.iris
        result.motionResults = results; result.patchWidth = CornealAnalyzer.width; result.referenceHostTime = samples.isEmpty ? nil : referenceTime
        return result
    }
}

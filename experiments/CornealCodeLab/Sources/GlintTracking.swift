import Foundation

enum GlintSource: String, Codable {
    case observed, weak, predicted, unavailable
    var title: String {
        switch self {
        case .observed: return "图像观测"
        case .weak: return "弱观测"
        case .predicted: return "短时预测"
        case .unavailable: return "无可靠观测"
        }
    }
    var measured: Bool { self == .observed || self == .weak }
}

/// 图像中的映光坐标，不能直接解释为屏幕注视位置。置信度为未校准的质量分。
struct GlintEstimate: Codable {
    var frameIndex: Int
    var pts: Double
    var hostTime: Double
    var imagePoint: LabPoint?
    var measurementPoint: LabPoint?
    var irisRelative: LabPoint?
    var confidence: Double
    var source: GlintSource
    var uncertaintyPixels: Double
    var signal: Double?
    var snr: Double?
    var observedDiameterPixels: Double?
    var expectedDiameterPixels: Double?
    var ambiguity: Double
    var patternMatch: PatternMatch? = nil
}

struct GlintTrackingResult: Codable {
    var eye: String
    var frames: [GlintEstimate]
    var observedFrames: Int
    var weakFrames: Int
    var predictedFrames: Int
    var unavailableFrames: Int
    var medianConfidence: Double
    var codeTrainCorrelation: Double?
    var codeHeldOutCorrelation: Double?
    var codeWrongCorrelation: Double?
    var codeLagSeconds: Double?
    var codeVerified: Bool
    var note: String
    var referenceRelative: LabPoint? = nil
    var patternMatchedFrames: Int { frames.filter { $0.source.measured && $0.patternMatch?.resolved == true }.count }

    static func make(eye: String,frames: [GlintEstimate]) -> GlintTrackingResult {
        let confidence = frames.map(\.confidence).sorted()
        return GlintTrackingResult(eye: eye,frames: frames,
            observedFrames: frames.filter { $0.source == .observed }.count,
            weakFrames: frames.filter { $0.source == .weak }.count,
            predictedFrames: frames.filter { $0.source == .predicted }.count,
            unavailableFrames: frames.filter { $0.source == .unavailable }.count,
            medianConfidence: confidence.isEmpty ? 0 : confidence[confidence.count/2],codeTrainCorrelation: nil,
            codeHeldOutCorrelation: nil,codeWrongCorrelation: nil,codeLagSeconds: nil,codeVerified: false,
            note: "坐标为相机图像像素。置信度为局部信噪比、形态、外观与关联歧义的工程质量分，未经概率校准；预测与无观测帧不计为检出。")
    }
    func nearest(hostTime: Double) -> GlintEstimate? {
        guard !frames.isEmpty else { return nil }
        var low = 0,high = frames.count
        while low<high { let middle = (low+high)/2; if frames[middle].hostTime<hostTime { low = middle+1 } else { high = middle } }
        let a = frames[min(low,frames.count-1)],b = frames[max(0,low-1)]
        return abs(a.hostTime-hostTime)<abs(b.hostTime-hostTime) ? a : b
    }
    func nearest(pts: Double) -> GlintEstimate? {
        guard !frames.isEmpty else { return nil }
        var low = 0,high = frames.count
        while low<high { let middle = (low+high)/2; if frames[middle].pts<pts { low = middle+1 } else { high = middle } }
        let a = frames[min(low,frames.count-1)],b = frames[max(0,low-1)]
        return abs(a.pts-pts)<abs(b.pts-pts) ? a : b
    }
}

/// 原生眼区上的多尺度局部亮斑与连续关联。单帧关联不读取光码，身份参考由训练段建立。
struct GlintTracker {
    struct Candidate {
        var point: LabPoint
        var signal: Double
        var snr: Double
        var diameter: Double
        var shape: Double
        var profile: [Double]
        var rank: Double
        var patternMatch: PatternMatch? = nil
    }
    private var point: LabPoint?
    private var previousIris: LabPoint?
    private var previousRadius = 1.0
    private var lastObservation = -Double.infinity
    private var lastConfidence = 0.0
    private var profile: [Double]?
    private var referenceProfile: [Double]?
    private var referenceSignal = 0.0
    private var anchor: LabPoint?

    mutating func setReference(relative: LabPoint,profile: [Double],signal: Double) {
        anchor = relative; referenceProfile = profile; referenceSignal = signal
    }
    private var previousPixels: [Float] = []
    private var previousWidth = 0
    private var previousHeight = 0
    private var previousROI: LabRect?

    /// 用亮点外围纹理做局部平移配准，避开中心亮度随光码变化的区域。
    private func register(pixels: [Float],width: Int,height: Int,roi: LabRect,guess: LabPoint,expected: Double,exclusion: Double? = nil) -> LabPoint? {
        guard let old = point,let oldROI = previousROI,!previousPixels.isEmpty else { return nil }
        let px = Int((old.x-oldROI.x).rounded()),py = Int((old.y-oldROI.y).rounded())
        let gx = Int((guess.x-roi.x).rounded()),gy = Int((guess.y-roi.y).rounded())
        let extent = max(12,min(32,Int(max(expected*4,(exclusion ?? 0)*1.6))))
        var samples: [(Int,Int,Double)] = []
        for y in stride(from: -extent,through: extent,by: 2) { for x in stride(from: -extent,through: extent,by: 2) {
            guard hypot(Double(x),Double(y))>max(expected*1.5,exclusion ?? 0),px+x>=0,py+y>=0,px+x<previousWidth,py+y<previousHeight else { continue }
            samples.append((x,y,Double(previousPixels[(py+y)*previousWidth+px+x])))
        } }
        guard samples.count>=50 else { return nil }
        let mean = samples.map { $0.2 }.reduce(0,+)/Double(samples.count)
        let variance = samples.map { pow($0.2-mean,2) }.reduce(0,+)
        guard variance/Double(samples.count)>4 else { return nil }
        func score(_ dx: Int,_ dy: Int) -> Double {
            guard gx+dx-extent>=0,gy+dy-extent>=0,gx+dx+extent<width,gy+dy+extent<height else { return -1 }
            var sum = 0.0,squared = 0.0,product = 0.0
            for (x,y,v) in samples {
                let value = Double(pixels[(gy+dy+y)*width+gx+dx+x])
                sum += value; squared += value*value; product += (v-mean)*value
            }
            return product/sqrt(max(1,variance*(squared-sum*sum/Double(samples.count))))
        }
        var dx = 0,dy = 0,best = -1.0
        for y in stride(from: -12,through: 12,by: 2) { for x in stride(from: -12,through: 12,by: 2) {
            let s = score(x,y); if s>best { best = s; dx = x; dy = y }
        } }
        let coarseX = dx,coarseY = dy
        for y in (coarseY-1)...(coarseY+1) { for x in (coarseX-1)...(coarseX+1) {
            let s = score(x,y); if s>best { best = s; dx = x; dy = y }
        } }
        guard best>=0.75 else { return nil }
        return LabPoint(x: roi.x+Double(gx+dx),y: roi.y+Double(gy+dy))
    }

    static func clamp(_ value: Double,_ low: Double = 0,_ high: Double = 1) -> Double { min(high,max(low,value)) }
    static func profile(_ pixels: [Float],width: Int,height: Int,x: Double,y: Double) -> [Double] {
        var result: [Double] = []; result.reserveCapacity(81)
        for yy in -4...4 { for xx in -4...4 {
            let px = clamp(x+Double(xx),0,Double(width-1)),py = clamp(y+Double(yy),0,Double(height-1))
            let ix = Int(px),iy = Int(py),fx = px-Double(ix),fy = py-Double(iy)
            let right = min(width-1,ix+1),bottom = min(height-1,iy+1)
            let upper = Double(pixels[iy*width+ix])*(1-fx)+Double(pixels[iy*width+right])*fx
            let lower = Double(pixels[bottom*width+ix])*(1-fx)+Double(pixels[bottom*width+right])*fx
            result.append(upper*(1-fy)+lower*fy)
        } }
        return result
    }

    static func candidates(pixels: [Float],width w: Int,height h: Int,iris: IrisRegion,expected: Double) -> [Candidate] {
        guard w>=8,h>=8,pixels.count == w*h,pixels.allSatisfy({ $0.isFinite }) else { return [] }
        var integral = [Double](repeating: 0,count: (w+1)*(h+1))
        var mask = [Bool](repeating: false,count: w*h)
        var histogram = [Int](repeating: 0,count: 256),n = 0
        for y in 0..<h {
            var sum = 0.0
            for x in 0..<w {
                let i = y*w+x
                sum += Double(pixels[i]); integral[(y+1)*(w+1)+x+1] = integral[y*(w+1)+x+1]+sum
                mask[i] = iris.contains(LabPoint(x: (Double(x)+0.5)/Double(w),y: (Double(y)+0.5)/Double(h)))
                if mask[i],x>0,y>0,x<w-1,y<h-1 {
                    let residual = abs(Double(pixels[i])-(Double(pixels[i-1])+Double(pixels[i+1])+Double(pixels[i-w])+Double(pixels[i+w]))/4)
                    histogram[min(255,Int(residual.rounded()))] += 1; n += 1
                }
            }
        }
        var accumulated = 0,median = 0
        for i in histogram.indices { accumulated += histogram[i]; if accumulated>=n/2 { median = i; break } }
        let noise = max(1.25,Double(median)*1.4826)
        func box(_ x: Int,_ y: Int,_ radius: Int) -> (Double,Int) {
            let x0 = max(0,x-radius),y0 = max(0,y-radius),x1 = min(w,x+radius+1),y1 = min(h,y+radius+1)
            return (integral[y1*(w+1)+x1]-integral[y0*(w+1)+x1]-integral[y1*(w+1)+x0]+integral[y0*(w+1)+x0],(x1-x0)*(y1-y0))
        }
        var response = [Double](repeating: 0,count: w*h)
        for y in 1..<(h-1) { for x in 1..<(w-1) where mask[y*w+x] {
            for radius in 0...3 {
                let inner = box(x,y,radius),outer = box(x,y,radius*2+3)
                let contrast = inner.0/Double(inner.1)-(outer.0-inner.0)/Double(max(1,outer.1-inner.1))
                let scale = exp(-0.25*abs(log(Double(radius*2+1)/max(1,expected))))
                response[y*w+x] = max(response[y*w+x],contrast/noise*sqrt(Double(radius*2+1))*scale)
            }
        } }
        var peaks: [(Int,Double)] = []
        for y in 1..<(h-1) { for x in 1..<(w-1) {
            let i = y*w+x,value = response[i]
            guard mask[i],value>=2 else { continue }
            var maximum = true
            for yy in -1...1 { for xx in -1...1 where response[(y+yy)*w+x+xx]>value { maximum = false } }
            if maximum { peaks.append((i,value)) }
        } }
        peaks.sort { $0.1>$1.1 }
        var results: [Candidate] = []
        let radius = max(3,min(10,Int(ceil(expected*1.2))))
        for (i,score) in peaks {
            let x = i%w,y = i/w
            if results.contains(where: { hypot($0.point.x-Double(x),$0.point.y-Double(y))<2.5 }) { continue }
            var border: [Double] = []
            for yy in max(0,y-radius-2)...min(h-1,y+radius+2) { for xx in max(0,x-radius-2)...min(w-1,x+radius+2) {
                if max(abs(xx-x),abs(yy-y))>=radius { border.append(Double(pixels[yy*w+xx])) }
            } }
            border.sort(); guard !border.isEmpty else { continue }
            let background = border[border.count/2]
            var peakX = x,peakY = y
            for yy in max(0,y-1)...min(h-1,y+1) { for xx in max(0,x-1)...min(w-1,x+1) {
                if pixels[yy*w+xx]>pixels[peakY*w+peakX] { peakX = xx; peakY = yy }
            } }
            let signal = Double(pixels[peakY*w+peakX])-background
            guard signal>=3 else { continue }
            let x0 = max(0,peakX-radius),x1 = min(w-1,peakX+radius),y0 = max(0,peakY-radius),y1 = min(h-1,peakY+radius)
            var stack = [peakY*w+peakX],seen = Set<Int>(stack),component: [(Double,Double,Double)] = []
            while let p = stack.popLast() {
                let xx = p%w,yy = p/w,value = Double(pixels[p])-background
                guard value>=signal*0.35,mask[p] else { continue }
                component.append((Double(xx)+0.5,Double(yy)+0.5,value))
                for ny in max(y0,yy-1)...min(y1,yy+1) { for nx in max(x0,xx-1)...min(x1,xx+1) {
                    let q = ny*w+nx
                    if seen.insert(q).inserted { stack.append(q) }
                } }
            }
            let weight = component.map { $0.2 }.reduce(0,+)
            guard weight>0 else { continue }
            let cx = component.map { $0.0*$0.2 }.reduce(0,+)/weight,cy = component.map { $0.1*$0.2 }.reduce(0,+)/weight
            var vx = 1.0/12,vy = 1.0/12,xy = 0.0
            for (xx,yy,v) in component { vx += (xx-cx)*(xx-cx)*v/weight; vy += (yy-cy)*(yy-cy)*v/weight; xy += (xx-cx)*(yy-cy)*v/weight }
            let delta = sqrt((vx-vy)*(vx-vy)+4*xy*xy)
            let axis = sqrt(max(0,(vx+vy-delta)/max(1e-8,vx+vy+delta)))
            let half = component.filter { $0.2>=signal*0.5 }.count
            let diameter = 2*sqrt(Double(half)/Double.pi)
            let sizeQuality = exp(-0.35*abs(log(max(0.5,diameter)/max(1,expected))))
            let shape = clamp(axis/0.6)*sizeQuality
            results.append(Candidate(point: LabPoint(x: cx,y: cy),signal: signal,snr: signal/noise,diameter: diameter,shape: shape,
                profile: profile(pixels,width: w,height: h,x: cx-0.5,y: cy-0.5),rank: score*(0.2+0.8*shape)))
            if results.count>=20 { break }
        }
        return results
    }

    mutating func update(pixels: [Float],width: Int,height: Int,roi: LabRect,iris: IrisRegion,row: FrameRecord,expectedDiameter: Double,pattern: FixationPattern? = nil,expectedPatternDiameter: Double? = nil) -> GlintEstimate {
        let center = LabPoint(x: roi.x+iris.center.x*roi.width,y: roi.y+iris.center.y*roi.height)
        let radius = max(1,iris.radiusX*roi.width)
        var prediction: LabPoint? = point.map { old in
            let origin = previousIris ?? center,scale = Self.clamp(radius/max(1,previousRadius),0.8,1.25)
            let dx = Self.clamp(center.x-origin.x,-radius*0.12,radius*0.12)
            let dy = Self.clamp(center.y-origin.y,-radius*0.12,radius*0.12)
            return LabPoint(x: old.x*scale+dx+(1-scale)*origin.x,y: old.y*scale+dy+(1-scale)*origin.y)
        }
        if let guess = prediction,let registered = register(pixels: pixels,width: width,height: height,roi: roi,guess: guess,expected: expectedDiameter,exclusion: expectedPatternDiameter.map { $0*0.65 }) {
            prediction = registered
        }
        let anchored = anchor.map { LabPoint(x: center.x+$0.x*radius,y: center.y+$0.y*radius) }
        if row.hostTime-lastObservation>0.6 { prediction = anchored ?? prediction }
        var candidates = Self.candidates(pixels: pixels,width: width,height: height,iris: iris,expected: expectedDiameter)
        let gate = max(4,expectedDiameter*1.2,radius*0.075)
        for i in candidates.indices {
            if let pattern = pattern,let diameter = expectedPatternDiameter {
                let match = FixationPatternMatcher.match(pixels: pixels,width: width,height: height,center: candidates[i].point,expectedDiameter: diameter,pattern: pattern)
                candidates[i].patternMatch = match
                let margin = (match?.correlation ?? 0)-(match?.alternativeCorrelation ?? 1)
                candidates[i].rank *= 0.05+3*max(0,margin+0.15)
            }
            let p = LabPoint(x: roi.x+candidates[i].point.x,y: roi.y+candidates[i].point.y)
            if let prediction = prediction {
                let distance = hypot(p.x-prediction.x,p.y-prediction.y)
                let continuity = exp(-distance*distance/(2*gate*gate))
                candidates[i].rank *= continuity
            } else {
                // 固定注视亮点在屏幕上方。仅作首次关联的软先验，搜索仍覆盖整个虹膜。
                let x = (p.x-center.x)/radius,y = (p.y-center.y)/radius
                candidates[i].rank *= exp(-0.5*(x*x/0.36+pow((y+0.35)/0.35,2)))
            }
            if let anchored = anchored {
                let distance = hypot(p.x-anchored.x,p.y-anchored.y)
                candidates[i].rank *= exp(-0.5*pow(distance/max(5,radius*0.12),2))
            }
            if let profile = referenceProfile ?? profile {
                let similarity = max(0,CornealAnalyzer.correlation(profile,candidates[i].profile) ?? 0)
                candidates[i].rank *= pow(similarity,2)
            }
        }
        candidates.sort { $0.rank>$1.rank }
        let best = candidates.first
        let ambiguity = best.map { b in min(1,(candidates.dropFirst().filter { hypot($0.point.x-b.point.x,$0.point.y-b.point.y)>2 }.first?.rank ?? 0)/max(b.rank,1e-8)) } ?? 1
        let strength = best.map { 1-exp(-max(0,$0.snr-1.5)/5) } ?? 0
        let confidence = best.map { b -> Double in
            let similarity = (referenceProfile ?? profile).map { max(0,CornealAnalyzer.correlation($0,b.profile) ?? 0) } ?? 1
            let displacement = prediction.map { hypot(roi.x+b.point.x-$0.x,roi.y+b.point.y-$0.y) } ?? 0
            let continuity = exp(-0.5*pow(displacement/gate,2))
            let amplitude = referenceSignal>0 ? min(1,b.signal/(referenceSignal*0.4)) : 1
            let identity = anchored.map { exp(-0.5*pow(hypot(roi.x+b.point.x-$0.x,roi.y+b.point.y-$0.y)/max(5,radius*0.12),2)) } ?? 1
            return strength*(0.3+0.7*b.shape)*(1-0.35*ambiguity)*similarity*continuity*amplitude*identity
        } ?? 0
        var estimate = GlintEstimate(frameIndex: row.index,pts: row.pts,hostTime: row.hostTime,imagePoint: prediction ?? center,
            measurementPoint: nil,irisRelative: nil,confidence: 0,source: .unavailable,uncertaintyPixels: radius,
            signal: nil,snr: best?.snr,observedDiameterPixels: nil,expectedDiameterPixels: expectedDiameter,ambiguity: ambiguity)
        if let best = best,confidence>=0.25,best.snr>=3 {
            let measured = LabPoint(x: roi.x+best.point.x,y: roi.y+best.point.y)
            let distance = prediction.map { hypot(measured.x-$0.x,measured.y-$0.y) } ?? 0
            if prediction == nil || distance<=gate*1.5 {
                let alpha = confidence>=0.6 ? 0.95 : 0.8
                let p = prediction.map { LabPoint(x: alpha*measured.x+(1-alpha)*$0.x,y: alpha*measured.y+(1-alpha)*$0.y) } ?? measured
                estimate.imagePoint = p; estimate.measurementPoint = measured; estimate.confidence = confidence
                estimate.source = confidence>=0.6 ? .observed : .weak
                estimate.observedDiameterPixels = best.diameter
                estimate.patternMatch = best.patternMatch
                estimate.uncertaintyPixels = max(0.3+best.diameter/max(2,sqrt(best.snr))+ambiguity,expectedDiameter*ambiguity)
                lastObservation = row.hostTime; lastConfidence = confidence
                if confidence>=0.6 { profile = best.profile }
                if referenceProfile == nil,confidence>=0.65 { referenceProfile = best.profile; referenceSignal = best.signal }
            }
        }
        if !estimate.source.measured {
            let age = row.hostTime-lastObservation
            estimate.confidence = age<=0.6 ? lastConfidence*exp(-max(0,age)/0.18) : 0
            estimate.source = age<=0.6 ? .predicted : .unavailable
            estimate.uncertaintyPixels = min(radius,2+max(0,age)*radius)
        }
        if let p = estimate.imagePoint {
            let localX = p.x-roi.x,localY = p.y-roi.y
            let values = Self.profile(pixels,width: width,height: height,x: localX-0.5,y: localY-0.5)
            let centerValues = [values[30],values[31],values[32],values[39],values[40],values[41],values[48],values[49],values[50]]
            let ring = values.enumerated().filter { i,_ in i/9 == 0 || i/9 == 8 || i%9 == 0 || i%9 == 8 }.map(\.element).sorted()
            estimate.signal = centerValues.reduce(0,+)/9-(ring.isEmpty ? 0 : ring[ring.count/2])
            estimate.irisRelative = LabPoint(x: (p.x-center.x)/radius,y: (p.y-center.y)/radius)
        }
        point = estimate.imagePoint; previousIris = center; previousRadius = radius
        previousPixels = pixels; previousWidth = width; previousHeight = height; previousROI = roi
        return estimate
    }
}

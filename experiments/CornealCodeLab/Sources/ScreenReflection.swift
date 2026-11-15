import Foundation

struct ScreenObservation: Codable {
    var hostTime: Double
    var corners: [LabPoint]
    var center: LabPoint
    var aspectRatio: Double
    var rectangularity: Double
    var contrast: Double
    var darkPoint: LabPoint?
    var darkDiameterNative: Double?
    var targetError: Double?
    var score: Double
    var reason: String
}

struct ScreenAnalysis: Codable {
    var rectangleCoverage: Double
    var targetCoverage: Double
    var trainTargetCoverage: Double
    var heldOutTargetCoverage: Double
    var jitterPixels: Double?
    var structuralEvidence: Bool
    var observations: [ScreenObservation]
}

/// 恒亮屏幕的空间模板实验。不发明暗码，不把暗斑或屏幕中心称为眼睛的真实注视位置。
enum ScreenReflection {
    static func detect(_ sample: PatchSample, configuration c: TrialConfiguration) -> ScreenObservation? {
        let w = CornealAnalyzer.width,h = CornealAnalyzer.height
        guard let iris = sample.iris,sample.pixels.count == w*h,sample.pixels.allSatisfy({ $0.isFinite }),
              c.displayWidth>0,c.displayHeight>c.displayWidth else { return nil }
        var boundary = iris
        boundary.radiusX = iris.boundaryRadiusX ?? iris.radiusX
        boundary.radiusY = iris.boundaryRadiusY ?? iris.radiusY
        let inside = sample.pixels.indices.map { boundary.contains(LabPoint(x: (Double($0%w)+0.5)/Double(w),y: (Double($0/w)+0.5)/Double(h))) }
        let levels = sample.pixels.indices.filter { inside[$0] }.map { Double(sample.pixels[$0]) }.sorted()
        guard levels.count>=25 else { return nil }
        let background = levels[levels.count/2],peak = levels[min(levels.count-1,Int(Double(levels.count)*0.98))]
        guard peak-background>=12 else { return nil }
        let expectedAspect = c.displayHeight/c.displayWidth
        var candidates: [ScreenObservation] = []
        for fraction in [0.35,0.50,0.65] {
            let threshold = background+(peak-background)*fraction
            let mask = sample.pixels.indices.map { inside[$0] && Double(sample.pixels[$0])>=threshold }
            for blob in SpotGeometry.components(mask,width: w,height: h) {
                guard blob.indices.count>=20,blob.minor>=4,blob.major>=7,!blob.touchesEdge else { continue }
                let aspect = blob.major/blob.minor
                // 曲面与透视会扭曲屏幕轮廓，所以采用有界容差，保留实际宽高比供人工复核。
                guard aspect>=1.25,abs(log(aspect/expectedAspect))<=0.45,blob.fill>=0.68 else { continue }
                guard blob.corners.allSatisfy({ boundary.contains(LabPoint(x: $0.x/Double(w),y: $0.y/Double(h))) }) else { continue }
                let ca = cos(blob.angle),sa = sin(blob.angle)
                let center = LabPoint(x: blob.corners.map(\.x).reduce(0,+)/4,y: blob.corners.map(\.y).reduce(0,+)/4)
                func local(_ p: Int) -> (Double,Double) {
                    let dx = Double(p%w)+0.5-center.x,dy = Double(p/w)+0.5-center.y
                    return (dx*ca+dy*sa,-dx*sa+dy*ca)
                }
                let darkMask = sample.pixels.indices.map { p -> Bool in
                    let (u,v) = local(p)
                    return abs(u)<blob.major/2 && abs(v)<blob.minor/2 && Double(sample.pixels[p])<threshold
                }
                var holes: [(SpotGeometry.Component,Double,Double)] = []
                for hole in SpotGeometry.components(darkMask,width: w,height: h) {
                    guard hole.indices.count>=3,hole.axisRatio>=0.55,hole.circularity>=0.40,hole.fill>=0.45 else { continue }
                    guard hole.indices.allSatisfy({ p in let (u,v) = local(p); return abs(u)<blob.major/2-1 && abs(v)<blob.minor/2-1 }) else { continue }
                    let diameter = 2*sqrt(Double(hole.indices.count)/Double.pi)
                    let native = diameter*sqrt(sample.roi.width/Double(w)*sample.roi.height/Double(h))
                    let expectedDiameter = blob.minor*c.dotDiameter/c.displayWidth
                    guard native>=2,diameter>=expectedDiameter*0.45,diameter<=expectedDiameter*2.0 else { continue }
                    let dx = hole.center.x-center.x,dy = hole.center.y-center.y
                    let u = (dx*ca+dy*sa)/blob.major,v = (-dx*sa+dy*ca)/blob.minor
                    // 不预先猜测角膜成像翻转方向；两个方向均公开计入模板自由度。
                    let error = hypot(abs(u)-abs(c.dotY-0.5),v)
                    let darkMean = hole.indices.map { Double(sample.pixels[$0]) }.reduce(0,+)/Double(hole.indices.count)
                    guard error<=0.16,peak-darkMean>=12 else { continue }
                    holes.append((hole,native,error))
                }
                let unique = holes.count == 1 ? holes[0] : nil
                let clipped = Double(blob.indices.filter { sample.pixels[$0]>=250 }.count)/Double(blob.indices.count)
                let reason = clipped>0.25 ? "屏幕轮廓局部过曝，不能确认暗点" : holes.count>1 ? "内部有多个匹配暗斑，位置有歧义" : unique == nil ? "找到屏幕状光斑，黑点未达到位置、形状或原生分辨率要求" : "屏幕轮廓及内部暗圆斑符合模板"
                let hole = clipped<=0.25 ? unique : nil
                candidates.append(ScreenObservation(hostTime: sample.hostTime,
                    corners: blob.corners.map { LabPoint(x: $0.x/Double(w),y: $0.y/Double(h)) },
                    center: LabPoint(x: center.x/Double(w),y: center.y/Double(h)),aspectRatio: aspect,
                    rectangularity: blob.fill,contrast: peak-background,
                    darkPoint: hole.map { LabPoint(x: $0.0.center.x/Double(w),y: $0.0.center.y/Double(h)) },
                    darkDiameterNative: hole?.1,targetError: hole?.2,
                    score: blob.fill-abs(log(aspect/expectedAspect))*0.25+(hole == nil ? 0 : 1),reason: reason))
            }
        }
        let sorted = candidates.sorted { $0.score>$1.score }
        guard var best = sorted.first else { return nil }
        if sorted.dropFirst().contains(where: { hypot(($0.center.x-best.center.x)*Double(w),($0.center.y-best.center.y)*Double(h))>4 && $0.score>best.score-0.15 }) {
            best.darkPoint = nil; best.reason = "存在多个近似屏幕轮廓，拒绝唯一定位"
        }
        return best
    }

    static func analyze(eye: String,samples input: [PatchSample],start: Double,configuration c: TrialConfiguration) -> EyeAnalysis {
        let samples = CornealAnalyzer.validSamples(input,eye: eye,start: start,duration: c.duration)
        let observations = samples.compactMap { detect($0,configuration: c) }
        let targets = observations.filter { $0.darkPoint != nil }
        let train = samples.filter { $0.hostTime<start+c.duration/2 },test = samples.filter { $0.hostTime>=start+c.duration/2 }
        let trainRate = Double(targets.filter { $0.hostTime<start+c.duration/2 }.count)/Double(max(1,train.count))
        let testRate = Double(targets.filter { $0.hostTime>=start+c.duration/2 }.count)/Double(max(1,test.count))
        // 在屏幕轮廓中心的相对坐标中计算抖动，避免把整体平移直接当成黑点不稳定。
        let relative = targets.map { o in LabPoint(x: (o.darkPoint!.x-o.center.x)*128,y: (o.darkPoint!.y-o.center.y)*64) }
        let mx = relative.map(\.x).reduce(0,+)/Double(max(1,relative.count)),my = relative.map(\.y).reduce(0,+)/Double(max(1,relative.count))
        let jitter = relative.isEmpty ? nil : sqrt(relative.map { pow($0.x-mx,2)+pow($0.y-my,2) }.reduce(0,+)/Double(relative.count))
        let structural = c.mode == .screenTarget && train.count>=30 && test.count>=30 && trainRate>=0.6 && testRate>=0.6 && (jitter ?? 100)<=2
        let reference = targets.last ?? observations.last
        let report = ScreenAnalysis(rectangleCoverage: Double(observations.count)/Double(max(1,samples.count)),targetCoverage: Double(targets.count)/Double(max(1,samples.count)),
            trainTargetCoverage: trainRate,heldOutTargetCoverage: testRate,jitterPixels: jitter,structuralEvidence: structural,observations: observations)
        let message = c.mode == .screenBlank ? "亮屏无黑点对照：检出的暗斑计为假候选，不构成注视目标证据。" : structural ? "屏幕轮廓与暗圆斑在前后半段持续出现；仅支持空间模板匹配，需人工确认角膜来源。" : "空间模板证据不足。黑点可能低于相机分辨率，不能用矩形中心代替。"
        var result = EyeAnalysis(eye: eye,samples: samples.count,rawCandidateRate: Double(samples.filter { $0.rawCandidate != nil }.count)/Double(max(1,samples.count)),point: reference?.darkPoint,
            trainCorrelation: nil,heldOutCorrelation: nil,wrongCodeCorrelation: nil,amplitude: nil,lagSeconds: nil,codedEvidence: false,message: message,
            heatmap: [],traceTime: [],traceSignal: [],traceCode: [])
        result.codingApplied = false; result.patchWidth = CornealAnalyzer.width; result.screenResult = report
        result.referenceHostTime = reference?.hostTime ?? samples.last?.hostTime
        result.referenceIris = samples.min { abs($0.hostTime-(result.referenceHostTime ?? start))<abs($1.hostTime-(result.referenceHostTime ?? start)) }?.iris
        return result
    }
}

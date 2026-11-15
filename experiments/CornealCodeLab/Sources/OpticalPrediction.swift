import Foundation

/// 近轴凸面镜预算，只提供几何核查，不作为通过或拒绝映光的条件。
/// 角膜前表面以半径 R 的球面近似，虚像缩率 m=(R/2)/(d+R/2)。
struct OpticalPrediction: Codable {
    var distanceCM: Double
    var distanceMinCM: Double
    var distanceMaxCM: Double
    var distanceSource: String
    var irisDiameterPixels: Double
    var screenWidthMM: Double
    var screenHeightMM: Double
    var screenSizeSource: String
    var cornealRadiusMM: Double
    var stimulusDiameterMM: Double
    var reflectedScreenWidthPixels: Double
    var reflectedScreenHeightPixels: Double
    var expectedDiameterPixels: Double
    var expectedMinPixels: Double
    var expectedMaxPixels: Double
    var observedDiameterPixels: Double?
    var observedToExpected: Double?
    var distanceSamples: Int
    var note: String

    static func diameter(distanceCM: Double,irisPixels: Double,sourceMM: Double,radiusMM: Double = 7.8) -> Double? {
        guard [distanceCM,irisPixels,sourceMM,radiusMM].allSatisfy({ $0.isFinite && $0>0 }) else { return nil }
        let f = radiusMM/2
        // 主 App 使用虹膜平面像素尺度；忽略数毫米虚像深度差，保留为近轴估计。
        return sourceMM*f/(distanceCM*10+f)*irisPixels/11.7
    }

    static func median(_ values: [Double]) -> Double? {
        let sorted = values.filter { $0.isFinite && $0>0 }.sorted()
        guard !sorted.isEmpty else { return nil }
        let i = sorted.count/2
        return sorted.count%2 == 0 ? (sorted[i-1]+sorted[i])/2 : sorted[i]
    }

    static func build(eye: String,records: [FrameRecord],configuration c: TrialConfiguration,result: EyeAnalysis) -> OpticalPrediction? {
        let rows = records.filter { $0.eye == eye && $0.status == "valid" && $0.distanceCM != nil }
        let distances = rows.compactMap(\.distanceCM).filter { $0.isFinite && $0>0 }
        let diameters = rows.compactMap { r -> Double? in
            guard let iris = r.iris,let roi = r.roi else { return nil }
            return (iris.boundaryRadiusX ?? iris.radiusX)*2*roi.width
        }
        guard let distance = median(distances),let iris = median(diameters),
              let low = distances.min(),let high = distances.max() else { return nil }
        let sourceMM = c.screenWidthMM*(c.mode == .motion ? c.probeDiameter : c.dotDiameter)/c.displayWidth
        guard let expected = diameter(distanceCM: distance,irisPixels: iris,sourceMM: sourceMM,radiusMM: c.cornealRadiusMM),
              let screenW = diameter(distanceCM: distance,irisPixels: iris,sourceMM: c.screenWidthMM,radiusMM: c.cornealRadiusMM),
              let screenH = diameter(distanceCM: distance,irisPixels: iris,sourceMM: c.screenHeightMM,radiusMM: c.cornealRadiusMM) else { return nil }
        // 范围只做距离变化及曲率假设 ±0.6 mm 的敏感性分析，不是统计置信区间。
        let minimum = diameter(distanceCM: high,irisPixels: iris,sourceMM: sourceMM,radiusMM: c.cornealRadiusMM-0.6)!
        let maximum = diameter(distanceCM: low,irisPixels: iris,sourceMM: sourceMM,radiusMM: c.cornealRadiusMM+0.6)!
        let observed = c.mode.isScreen ? result.screenResult.flatMap { median($0.observations.compactMap(\.darkDiameterNative)) }
            : result.heldOutShape?.nativeDiameter ?? result.shape?.nativeDiameter
        return OpticalPrediction(distanceCM: distance,distanceMinCM: low,distanceMaxCM: high,
            distanceSource: rows.last?.distanceSource ?? "自动虹膜测距",irisDiameterPixels: iris,
            screenWidthMM: c.screenWidthMM,screenHeightMM: c.screenHeightMM,screenSizeSource: c.screenSizeSource,
            cornealRadiusMM: c.cornealRadiusMM,stimulusDiameterMM: sourceMM,reflectedScreenWidthPixels: screenW,
            reflectedScreenHeightPixels: screenH,expectedDiameterPixels: expected,expectedMinPixels: minimum,
            expectedMaxPixels: maximum,observedDiameterPixels: observed,observedToExpected: observed.map { $0/expected },
            distanceSamples: distances.count,
            note: "虹膜直径假设11.7mm；曲率为群体球面假设，未测得个人曲率。范围是距离变化与曲率±0.6mm的敏感性分析；不含失焦、像差、离轴、ISP及屏幕/镜头位置差，不作为检测门槛。")
    }
}

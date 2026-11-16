import Foundation

enum FixationPattern: String, Codable, CaseIterable, Identifiable {
    case ringDot, crossDot
    var id: String { rawValue }
    var title: String { self == .ringDot ? "缺口圆环＋中心点" : "短尾十字＋中心点" }
    static let centerFraction = 0.2

    /// 归一化外径为1；外围与中心留暗隙，避免再次退化为一个实心亮斑。
    func intensity(x: Double,y: Double) -> Double {
        let radius = hypot(x,y)
        if radius<=0.1 { return 1 }
        switch self {
        case .ringDot:
            let angle = atan2(y,x)
            return radius>=0.34 && radius<=0.5 && !(angle>(-5 * .pi/12) && angle<(-.pi/12)) ? 1 : 0
        case .crossDot:
            let horizontal = abs(y)<=0.09 && abs(x)>=0.18 && abs(x)<=0.5
            let vertical = abs(x)<=0.09 && (y>=(-0.5) && y<=(-0.18) || y>=0.18 && y<=0.38)
            return horizontal || vertical ? 1 : 0
        }
    }
}

struct PatternMatch: Codable {
    var correlation: Double
    var alternativeCorrelation: Double
    var diameterPixels: Double
    var angleRadians: Double
    var resolved: Bool
}

/// 中心点提供精定位，外围模板只作独立形态核查，实心点及另一图案作为竞争解释。
enum FixationPatternMatcher {
    private struct Template { var values: [Double]; var energy: Double; var angle: Double }
    private static let side = 13
    private static func template(pattern: FixationPattern?,angle: Double) -> Template {
        var values: [Double] = []
        let ca = cos(angle),sa = sin(angle)
        for y in 0..<side { for x in 0..<side {
            let px = (Double(x)-6)/10,py = (Double(y)-6)/10
            var value = 0.0
            // 对模板作有限模糊，容纳约1像素PSF；真实光学/ISP仍需真机验证。
            for dy in [-0.05,0,0.05] { for dx in [-0.05,0,0.05] {
                let u = (px+dx)*ca+(py+dy)*sa,v = -(px+dx)*sa+(py+dy)*ca
                value += pattern?.intensity(x: u,y: v) ?? (hypot(u,v)<=0.1 ? 1 : 0)
            } }
            values.append(value/9)
        } }
        let mean = values.reduce(0,+)/Double(values.count)
        values = values.map { $0-mean }
        return Template(values: values,energy: values.map { $0*$0 }.reduce(0,+),angle: angle)
    }
    private static let templates = Dictionary(uniqueKeysWithValues: FixationPattern.allCases.map { pattern in
        (pattern,(0..<8).map { template(pattern: pattern,angle: Double($0) * .pi/4) })
    })
    private static let spot = template(pattern: nil,angle: 0)

    static func match(pixels: [Float],width: Int,height: Int,center: LabPoint,expectedDiameter: Double,pattern: FixationPattern) -> PatternMatch? {
        guard pixels.count == width*height,expectedDiameter>=3,expectedDiameter<=150 else { return nil }
        var best = -1.0,alternative = -1.0,bestDiameter = expectedDiameter,bestAngle = 0.0
        for scale in [0.7,0.85,1.0,1.2,1.45] {
            let diameter = expectedDiameter*scale
            var values: [Double] = []; values.reserveCapacity(side*side)
            var valid = true
            for y in 0..<side { for x in 0..<side {
                let px = center.x-0.5+(Double(x)-6)/10*diameter,py = center.y-0.5+(Double(y)-6)/10*diameter
                guard px>=0,py>=0,px<Double(width-1),py<Double(height-1) else { valid = false; continue }
                let ix = Int(px),iy = Int(py),fx = px-Double(Int(px)),fy = py-Double(Int(py))
                let a = Double(pixels[iy*width+ix])*(1-fx)+Double(pixels[iy*width+ix+1])*fx
                let b = Double(pixels[(iy+1)*width+ix])*(1-fx)+Double(pixels[(iy+1)*width+ix+1])*fx
                values.append(a*(1-fy)+b*fy)
            } }
            guard valid,values.count == side*side else { continue }
            let mean = values.reduce(0,+)/Double(values.count)
            values = values.map { $0-mean }
            let energy = values.map { $0*$0 }.reduce(0,+)
            guard energy>Double(values.count)*2 else { continue }
            func correlation(_ t: Template) -> Double {
                zip(values,t.values).map(*).reduce(0,+)/sqrt(max(1e-8,energy*t.energy))
            }
            for t in templates[pattern] ?? [] {
                let value = correlation(t)
                if value>best { best = value; bestDiameter = diameter; bestAngle = t.angle }
            }
            alternative = max(alternative,correlation(spot))
            for other in FixationPattern.allCases where other != pattern {
                for t in templates[other] ?? [] { alternative = max(alternative,correlation(t)) }
            }
        }
        guard best>(-1) else { return nil }
        return PatternMatch(correlation: best,alternativeCorrelation: alternative,diameterPixels: bestDiameter,angleRadians: bestAngle,
            resolved: bestDiameter>=8 && best>=0.60 && best-alternative>=0.08)
    }
}

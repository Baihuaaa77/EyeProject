import Foundation

/// 保守虹膜内区，不宣称是经验证的虹膜分割。找不到双侧虹膜/巩膜过渡即弃帧。
struct IrisRegion: Codable {
    var center: LabPoint
    var radiusX: Double
    var radiusY: Double
    var confidence: Double
    var eyelid: [LabPoint]
    func contains(_ p: LabPoint) -> Bool {
        guard radiusX>0,radiusY>0,pow((p.x-center.x)/radiusX,2)+pow((p.y-center.y)/radiusY,2)<1 else { return false }
        guard eyelid.count>=3 else { return false }
        var inside = false, j = eyelid.count-1
        for i in eyelid.indices {
            let a = eyelid[i], b = eyelid[j]
            if (a.y>p.y) != (b.y>p.y), p.x<(b.x-a.x)*(p.y-a.y)/(b.y-a.y)+a.x { inside.toggle() }
            j = i
        }
        return inside
    }
    static func estimate(pixels: [Float], eyelid: [LabPoint]) -> IrisRegion? {
        let w = CornealAnalyzer.width, h = CornealAnalyzer.height
        guard pixels.count == w*h,eyelid.count>=4 else { return nil }
        let cx = w/2, cy = h/2, eyeWidth = Double(w)/1.4
        func level(_ x: Int) -> Double {
            var values: [Float] = []
            for y in (cy-3)...(cy+3) { for xx in (x-1)...(x+1) { values.append(pixels[y*w+xx]) } }
            values.sort(); return Double(values[values.count/2])
        }
        func boundary(_ sign: Int) -> (Double,Double) {
            var bestRadius = 0.0, bestScore = 0.0
            for r in Int(eyeWidth*0.13)...Int(eyeWidth*0.26) {
                let inner = (level(cx+sign*(r-3))+level(cx+sign*(r-2)))/2
                let outer = (level(cx+sign*(r+2))+level(cx+sign*(r+3)))/2
                if outer-inner>bestScore { bestScore = outer-inner; bestRadius = Double(r) }
            }
            return (bestRadius,bestScore)
        }
        let left = boundary(-1), right = boundary(1)
        guard left.1>=8,right.1>=8, min(left.0,right.0)/max(left.0,right.0)>=0.65 else { return nil }
        let offset = (right.0-left.0)/2
        guard abs(offset)<=eyeWidth*0.05 else { return nil }
        // 只取双侧边缘以内的 80%，再留 1 个采样像素，降低边界误差和眼角假峰。
        let radius = min(left.0,right.0)*0.8-1
        let result = IrisRegion(center: LabPoint(x: (Double(cx)+offset+0.5)/Double(w),y: (Double(cy)+0.5)/Double(h)),
            radiusX: radius/Double(w),radiusY: radius/Double(h),confidence: min(left.1,right.1),eyelid: eyelid)
        return result.contains(result.center) ? result : nil
    }
}

import Foundation

/// 来自主 App 的虹膜几何；新实验搜索完整虹膜与眼睑可见区的交集，旧记录保留当时半径。
struct IrisRegion: Codable {
    var center: LabPoint
    var radiusX: Double
    var radiusY: Double
    var confidence: Double
    var eyelid: [LabPoint]
    var boundaryRadiusX: Double? = nil
    var boundaryRadiusY: Double? = nil
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
}

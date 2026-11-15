import Foundation

/// 形状筛选在采样图上计算，分辨率门槛换回相机原生像素，避免把插值当成细节。
struct SpotShape: Codable {
    var center: LabPoint
    var area: Int
    var axisRatio: Double
    var circularity: Double
    var fill: Double
    var contrast: Double
    var nativeDiameter: Double
    var saturatedFraction: Double
    var accepted: Bool
    var reason: String
}

enum SpotGeometry {
    struct Component {
        var indices: [Int]
        var center: LabPoint
        var axisRatio: Double
        var circularity: Double
        var fill: Double
        var angle: Double
        var major: Double
        var minor: Double
        var corners: [LabPoint]
        var touchesEdge: Bool
    }

    /// 八连通区域；形状使用二阶矩及旋转包围盒，允许小幅透视压扁。
    static func components(_ mask: [Bool], width w: Int, height h: Int) -> [Component] {
        guard w>2,h>2,mask.count == w*h else { return [] }
        var seen = [Bool](repeating: false,count: mask.count), result: [Component] = []
        for seed in mask.indices where mask[seed] && !seen[seed] {
            var queue = [seed], head = 0
            seen[seed] = true
            while head<queue.count {
                let p = queue[head]; head += 1
                let x = p%w,y = p/w
                for yy in max(0,y-1)...min(h-1,y+1) { for xx in max(0,x-1)...min(w-1,x+1) {
                    let q = yy*w+xx
                    if mask[q] && !seen[q] { seen[q] = true; queue.append(q) }
                } }
            }
            let n = Double(queue.count)
            let cx = queue.map { Double($0%w)+0.5 }.reduce(0,+)/n
            let cy = queue.map { Double($0/w)+0.5 }.reduce(0,+)/n
            var xx = 1.0/12,yy = 1.0/12,xy = 0.0, perimeter = 0.0, edge = false
            for p in queue {
                let x = p%w,y = p/w,dx = Double(x)+0.5-cx,dy = Double(y)+0.5-cy
                xx += dx*dx/n; yy += dy*dy/n; xy += dx*dy/n
                if x == 0 || x == w-1 || y == 0 || y == h-1 { edge = true }
                for (nx,ny) in [(x-1,y),(x+1,y),(x,y-1),(x,y+1)] {
                    if nx<0 || nx>=w || ny<0 || ny>=h { perimeter += 1 }
                    else if !mask[ny*w+nx] { perimeter += 1 }
                }
            }
            let delta = sqrt(pow(xx-yy,2)+4*xy*xy)
            let ratio = sqrt(max(1e-8,(xx+yy-delta)/(xx+yy+delta)))
            let angle = 0.5*atan2(2*xy,xx-yy), ca = cos(angle), sa = sin(angle)
            var u0 = Double.infinity,u1 = -Double.infinity,v0 = Double.infinity,v1 = -Double.infinity
            for p in queue {
                let dx = Double(p%w)+0.5-cx,dy = Double(p/w)+0.5-cy
                let u = dx*ca+dy*sa,v = -dx*sa+dy*ca
                u0 = min(u0,u); u1 = max(u1,u); v0 = min(v0,v); v1 = max(v1,v)
            }
            let pad = (abs(ca)+abs(sa))/2
            u0 -= pad; u1 += pad; v0 -= pad; v1 += pad
            let major = u1-u0,minor = v1-v0
            let corners = [(u0,v0),(u1,v0),(u1,v1),(u0,v1)].map { u,v in LabPoint(x: cx+u*ca-v*sa,y: cy+u*sa+v*ca) }
            result.append(Component(indices: queue,center: LabPoint(x: cx,y: cy),axisRatio: ratio,
                circularity: 4 * .pi*n/max(1,perimeter*perimeter),fill: n/max(1,major*minor),angle: angle,
                major: major,minor: minor,corners: corners,touchesEdge: edge))
        }
        return result
    }

    static func shapes(pixels: [Float], iris: IrisRegion, roi: LabRect, highPass supplied: [Float]? = nil) -> [SpotShape] {
        let w = CornealAnalyzer.width,h = CornealAnalyzer.height
        guard pixels.count == w*h,pixels.allSatisfy({ $0.isFinite }),roi.width>0,roi.height>0 else { return [] }
        let hp = supplied ?? CornealAnalyzer.highPass(pixels)
        guard hp.count == pixels.count else { return [] }
        let inside = pixels.indices.map { iris.contains(LabPoint(x: (Double($0%w)+0.5)/Double(w),y: (Double($0/w)+0.5)/Double(h))) }
        let noise = hp.indices.filter { inside[$0] }.map { abs(Double(hp[$0])) }.sorted()
        guard !noise.isEmpty else { return [] }
        let threshold = max(3,noise[noise.count/2]*1.4826*3)
        let mask = hp.indices.map { inside[$0] && Double(hp[$0])>=threshold }
        var results: [SpotShape] = []
        for blob in components(mask,width: w,height: h) where blob.indices.count>=2 {
            let peak = blob.indices.map { Double(hp[$0]) }.max() ?? 0
            let clipped = Double(blob.indices.filter { pixels[$0]>=250 }.count)/Double(blob.indices.count)
            let nativePixelArea = (roi.width/Double(w))*(roi.height/Double(h))
            let diameter = 2*sqrt(Double(blob.indices.count)*nativePixelArea/Double.pi)
            var nearBoundary = false
            for p in blob.indices {
                let x = p%w,y = p/w
                if x<=0 || x>=w-1 || y<=0 || y>=h-1 { nearBoundary = true; break }
                if !inside[p-1] || !inside[p+1] || !inside[p-w] || !inside[p+w] { nearBoundary = true; break }
            }
            let reason: String
            if nearBoundary || blob.touchesEdge { reason = "候选接触虹膜或眼睑搜索边界" }
            else if diameter<1.5 { reason = "原生像素不足，不能验证圆形" }
            else if blob.axisRatio<0.55 || blob.circularity<0.40 || blob.fill<0.45 { reason = "候选过长、破碎或不近似圆形" }
            else if clipped>0.25 { reason = "候选局部过曝" }
            else { reason = "近圆形、对比度及原生分辨率通过" }
            var weight = 0.0,sx = 0.0,sy = 0.0
            for p in blob.indices { let v = Double(max(0,hp[p])); weight += v; sx += (Double(p%w)+0.5)*v; sy += (Double(p/w)+0.5)*v }
            results.append(SpotShape(center: LabPoint(x: sx/max(weight,1e-8)/Double(w),y: sy/max(weight,1e-8)/Double(h)),area: blob.indices.count,
                axisRatio: blob.axisRatio,circularity: blob.circularity,fill: blob.fill,contrast: peak,nativeDiameter: diameter,
                saturatedFraction: clipped,accepted: reason == "近圆形、对比度及原生分辨率通过",reason: reason))
        }
        return results
    }

    static func nearest(_ shapes: [SpotShape], to point: LabPoint, radius: Double = 3) -> SpotShape? {
        shapes.filter { hypot(($0.center.x-point.x)*128,($0.center.y-point.y)*64)<=radius }
            .min { hypot(($0.center.x-point.x)*128,($0.center.y-point.y)*64)<hypot(($1.center.x-point.x)*128,($1.center.y-point.y)*64) }
    }
}

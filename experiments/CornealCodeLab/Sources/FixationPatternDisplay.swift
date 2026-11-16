import UIKit
import SwiftUI

enum FixationPatternDisplay {
    /// 与纯算法中的归一化图案同几何；向量绘制不使用图片缩放或外部素材。
    static func path(_ pattern: FixationPattern,diameter: Double,center: CGPoint) -> CGPath {
        let path = CGMutablePath()
        func rect(_ x: Double,_ y: Double,_ w: Double,_ h: Double) {
            path.addRect(CGRect(x: center.x+x*diameter,y: center.y+y*diameter,width: w*diameter,height: h*diameter))
        }
        path.addEllipse(in: CGRect(x: center.x-diameter*0.1,y: center.y-diameter*0.1,width: diameter*0.2,height: diameter*0.2))
        switch pattern {
        case .ringDot:
            let start = -Double.pi/12,end = 19*Double.pi/12
            for i in 0...72 {
                let angle = start+(end-start)*Double(i)/72
                let p = CGPoint(x: center.x+cos(angle)*diameter*0.5,y: center.y+sin(angle)*diameter*0.5)
                if i == 0 { path.move(to: p) } else { path.addLine(to: p) }
            }
            for i in (0...72).reversed() {
                let angle = start+(end-start)*Double(i)/72
                path.addLine(to: CGPoint(x: center.x+cos(angle)*diameter*0.34,y: center.y+sin(angle)*diameter*0.34))
            }
            path.closeSubpath()
        case .crossDot:
            rect(-0.5,-0.09,0.32,0.18); rect(0.18,-0.09,0.32,0.18)
            rect(-0.09,-0.5,0.18,0.32); rect(-0.09,0.18,0.18,0.2)
        }
        return path
    }
    private static let previews = Dictionary(uniqueKeysWithValues: FixationPattern.allCases.map { ($0,renderPreview($0)) })
    static func preview(_ pattern: FixationPattern) -> UIImage { previews[pattern]! }
    private static func renderPreview(_ pattern: FixationPattern) -> UIImage {
        let size = CGSize(width: 160,height: 160)
        let format = UIGraphicsImageRendererFormat(); format.scale = 2
        return UIGraphicsImageRenderer(size: size,format: format).image { graphics in
            LabStyle.stimulusBackground.setFill(); graphics.fill(CGRect(origin: .zero,size: size))
            let ctx = graphics.cgContext; ctx.setFillColor(LabStyle.stimulusForeground.cgColor)
            ctx.addPath(path(pattern,diameter: 128,center: CGPoint(x: 80,y: 80))); ctx.fillPath()
        }
    }
}

struct PatternSetupView: View {
    @Binding var configuration: TrialConfiguration
    let distance: Double?
    let irisPixels: Double?
    var body: some View {
        VStack(alignment: .leading,spacing: 8) {
            Picker("目标图案",selection: $configuration.targetPattern) {
                ForEach(FixationPattern.allCases) { pattern in Text(pattern.title).tag(pattern) }
            }
            HStack { Spacer(); FixationPatternPreview(pattern: configuration.targetPattern); Spacer() }
            Text("注视中心点。外环缺口或十字短尾用于辨认方向；外径改变时，中心点与线宽同比例改变。").font(LabStyle.detail)
            if let distance = distance,let iris = irisPixels,
               let diameter = OpticalPrediction.diameter(distanceCM: distance,irisPixels: iris,sourceMM: DeviceOptics.screen(UIScreen.main).0*configuration.dotDiameter/Double(UIScreen.main.bounds.width),radiusMM: configuration.cornealRadiusMM) {
                Text("预计反射外径 \(String(format: "%.1f",diameter)) px · 中心点 \(String(format: "%.1f",diameter*0.2)) px · 细线约 \(String(format: "%.1f",diameter*0.16)) px").font(LabStyle.detail)
                if diameter<10 { Text("外围细节可能难以分辨，可放大图案或适当靠近后重试。").font(LabStyle.detail).foregroundStyle(LabStyle.candidate) }
            }
        }
    }
}

struct FixationPatternPreview: View {
    let pattern: FixationPattern
    var body: some View {
        Image(uiImage: FixationPatternDisplay.preview(pattern)).resizable().interpolation(.high).frame(width: 160,height: 160)
            .accessibilityLabel(pattern.title+"，请注视正中央的小点")
    }
}

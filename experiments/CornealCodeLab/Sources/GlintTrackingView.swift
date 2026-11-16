import SwiftUI

struct GlintTrackingView: View {
    let result: GlintTrackingResult
    var body: some View {
        VStack(alignment: .leading,spacing: 8) {
            Text("逐帧映光轨迹").font(LabStyle.subheading)
            Text("\(result.frames.count) 帧记录 · 图像观测 \(result.observedFrames) · 弱观测 \(result.weakFrames)").font(LabStyle.detail)
            Text("短时预测 \(result.predictedFrames) · 无可靠观测 \(result.unavailableFrames)").font(LabStyle.detail)
            GlintQualityTimeline(result: result).frame(height: 64)
                .accessibilityLabel("逐帧质量分；实线表示图像观测，虚线表示预测")
            Text("实线：图像观测 / 弱观测；虚线：预测。质量分0–1，未校准为正确概率。").font(LabStyle.detail)
            if result.frames.contains(where: { $0.patternMatch != nil }) {
                Text("外围图案可分辨且优于竞争模板：\(result.patternMatchedFrames) 帧").font(LabStyle.detail)
                Text("同时比较另一图案与单独圆点；结构不清晰时只保留中心候选，不计为图案验证。").font(LabStyle.detail)
            }
            if let held = result.codeHeldOutCorrelation {
                Text("光码验证 \(result.codeVerified ? "通过" : "未通过") · 后半段 r=\(String(format: "%.3f",held)) · 错码最大 |r|=\(String(format: "%.3f",result.codeWrongCorrelation ?? 0))").font(LabStyle.detail)
            }
        }
    }
}

struct GlintQualityTimeline: View {
    let result: GlintTrackingResult
    var body: some View {
        Canvas { context,size in
            guard let first = result.frames.first,let last = result.frames.last,last.pts>first.pts else { return }
            var baseline = Path(); baseline.move(to: CGPoint(x: 0,y: size.height-1)); baseline.addLine(to: CGPoint(x: size.width,y: size.height-1))
            context.stroke(baseline,with: .color(LabStyle.secondary),lineWidth: 1)
            for i in 1..<result.frames.count {
                let a = result.frames[i-1],b = result.frames[i]
                func point(_ f: GlintEstimate) -> CGPoint { CGPoint(x: (f.pts-first.pts)/(last.pts-first.pts)*size.width,y: (1-f.confidence)*(size.height-4)+2) }
                var path = Path(); path.move(to: point(a)); path.addLine(to: point(b))
                let color = b.source == .observed ? LabStyle.coded : b.source == .weak ? LabStyle.candidate : LabStyle.secondary
                context.stroke(path,with: .color(color),style: StrokeStyle(lineWidth: 1.5,dash: b.source.measured ? [] : [3,3]))
            }
        }
    }
}

struct TrackingPlaybackReadout: View {
    @ObservedObject var playback: MoviePlayback
    var body: some View {
        VStack(alignment: .leading,spacing: 6) {
            ForEach(playback.results.filter { $0.tracking != nil },id: \.eye) { result in
                if let sample = result.tracking?.nearest(pts: playback.videoStartPTS+playback.time),let p = sample.imagePoint {
                    Text("\(result.eye == "image_left" ? "画面左" : "画面右") · \(sample.source.title) · Q \(String(format: "%.2f",sample.confidence))\n(\(String(format: "%.1f",p.x)), \(String(format: "%.1f",p.y))) px · 不确定度约 \(String(format: "%.1f",sample.uncertaintyPixels)) px")
                        .font(LabStyle.code).foregroundStyle(LabStyle.text)
                    if let match = sample.patternMatch {
                        Text("图案 \(match.resolved ? "结构支持" : "待核查") · r \(String(format: "%.2f",match.correlation)) · 竞争 r \(String(format: "%.2f",match.alternativeCorrelation))")
                            .font(LabStyle.detail)
                    }
                }
            }
        }
    }
}

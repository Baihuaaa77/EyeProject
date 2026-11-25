import EyeScreening
import SwiftUI

/// 实时光学取景仪叠加层：绘出双眼虹膜外圈、0.80R 虚线搜索边界与候选亮点十字。
struct LabViewfinderOverlay: View {
    let live: LabLiveState

    var body: some View {
        Canvas { ctx, size in
            let sw = CGFloat(live.sourceWidth)
            let sh = CGFloat(live.sourceHeight)
            guard sw > 0, sh > 0 else { return }
            let scale = max(size.width / sw, size.height / sh)
            let dx = (size.width - sw * scale) * 0.5
            let dy = (size.height - sh * scale) * 0.5
            func map(_ x: Float, _ y: Float) -> CGPoint {
                CGPoint(x: size.width - (CGFloat(x) * scale + dx), y: CGFloat(y) * scale + dy)
            }

            func drawEye(center: Point2f?, diameter: Float, cands: [LabCandidateSummary], tint: Color) {
                guard let center = center, diameter > 0 else { return }
                let c = map(center.x, center.y)
                let r = CGFloat(diameter) * 0.5 * scale
                let irisRect = CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2)
                ctx.stroke(Path(ellipseIn: irisRect), with: .color(LabPalette.hairlineStrong), lineWidth: 1.5)

                let sr = r * CGFloat(live.searchRadiusFraction)
                let searchRect = CGRect(x: c.x - sr, y: c.y - sr, width: sr * 2, height: sr * 2)
                ctx.stroke(
                    Path(ellipseIn: searchRect),
                    with: .color(tint.opacity(0.85)),
                    style: StrokeStyle(lineWidth: 2, dash: [8, 6])
                )

                for (idx, cand) in cands.enumerated() {
                    let p = map(cand.x, cand.y)
                    let isTop = idx == 0
                    let color = isTop ? LabPalette.verified : LabPalette.inkSecondary.opacity(0.7)
                    let arm: CGFloat = isTop ? 8 : 5
                    var cross = Path()
                    cross.move(to: CGPoint(x: p.x - arm, y: p.y))
                    cross.addLine(to: CGPoint(x: p.x + arm, y: p.y))
                    cross.move(to: CGPoint(x: p.x, y: p.y - arm))
                    cross.addLine(to: CGPoint(x: p.x, y: p.y + arm))
                    ctx.stroke(cross, with: .color(color), lineWidth: isTop ? 2 : 1.2)
                    if isTop {
                        let dotRect = CGRect(x: p.x - 4, y: p.y - 4, width: 8, height: 8)
                        ctx.stroke(Path(ellipseIn: dotRect), with: .color(color), lineWidth: 1.5)
                    }
                }
            }

            drawEye(center: live.leftIrisCenter, diameter: live.leftIrisDiameterPx, cands: live.leftCandidates, tint: LabPalette.accent)
            drawEye(center: live.rightIrisCenter, diameter: live.rightIrisDiameterPx, cands: live.rightCandidates, tint: LabPalette.rightEye)
        }
        .allowsHitTesting(false)
    }
}

/// 双目虹膜归一化角膜映光靶图。
struct LabBinocularTargetMap: View {
    let leftPoints: [LabGlintScatterPoint]
    let rightPoints: [LabGlintScatterPoint]
    let leftAnchor: (Double?, Double?)
    let rightAnchor: (Double?, Double?)
    let searchRadius: Float

    var body: some View {
        HStack(spacing: 12) {
            singlePlot(title: "图像左眼 · L", points: leftPoints, anchor: leftAnchor, tint: LabPalette.accent)
            singlePlot(title: "图像右眼 · R", points: rightPoints, anchor: rightAnchor, tint: LabPalette.rightEye)
        }
    }

    private func singlePlot(title: String, points: [LabGlintScatterPoint], anchor: (Double?, Double?), tint: Color) -> some View {
        VStack(spacing: 8) {
            HStack {
                Text(title).font(LabFont.caption).foregroundColor(tint)
                Spacer()
                Text("\(points.count) pts").font(LabFont.microMono).foregroundColor(LabPalette.inkSecondary)
            }
            Canvas { ctx, size in
                let cx = size.width * 0.5
                let cy = size.height * 0.5
                let r = min(size.width, size.height) * 0.44
                let irisRect = CGRect(x: cx - r, y: cy - r, width: r * 2, height: r * 2)
                ctx.fill(Path(ellipseIn: irisRect), with: .color(LabPalette.surfaceSubtle))
                ctx.stroke(Path(ellipseIn: irisRect), with: .color(LabPalette.hairlineStrong), lineWidth: 1.5)

                let sr = r * CGFloat(searchRadius)
                let searchRect = CGRect(x: cx - sr, y: cy - sr, width: sr * 2, height: sr * 2)
                ctx.stroke(Path(ellipseIn: searchRect), with: .color(tint.opacity(0.55)), style: StrokeStyle(lineWidth: 1.5, dash: [6, 5]))

                var axes = Path()
                axes.move(to: CGPoint(x: cx - r, y: cy)); axes.addLine(to: CGPoint(x: cx + r, y: cy))
                axes.move(to: CGPoint(x: cx, y: cy - r)); axes.addLine(to: CGPoint(x: cx, y: cy + r))
                ctx.stroke(axes, with: .color(LabPalette.hairline), lineWidth: 1)

                for pt in points {
                    let px = cx + CGFloat(min(1, max(-1, pt.relX))) * r
                    let py = cy + CGFloat(min(1, max(-1, pt.relY))) * r
                    let isObs = pt.source == .observed
                    let rad: CGFloat = isObs ? 3.2 : 2.3
                    let alpha = min(1.0, max(0.3, 0.35 + 0.65 * Double(pt.confidence)))
                    let col = (isObs ? tint : LabPalette.caution).opacity(alpha)
                    ctx.fill(Path(ellipseIn: CGRect(x: px - rad, y: py - rad, width: rad * 2, height: rad * 2)), with: .color(col))
                }

                if let ax = anchor.0, let ay = anchor.1 {
                    let px = cx + CGFloat(min(1, max(-1, ax))) * r
                    let py = cy + CGFloat(min(1, max(-1, ay))) * r
                    var cross = Path()
                    cross.move(to: CGPoint(x: px - 7, y: py)); cross.addLine(to: CGPoint(x: px + 7, y: py))
                    cross.move(to: CGPoint(x: px, y: py - 7)); cross.addLine(to: CGPoint(x: px, y: py + 7))
                    ctx.stroke(cross, with: .color(LabPalette.verified), lineWidth: 2)
                    ctx.stroke(Path(ellipseIn: CGRect(x: px - 5, y: py - 5, width: 10, height: 10)), with: .color(LabPalette.verified), lineWidth: 1.4)
                }
            }
            .aspectRatio(1, contentMode: .fit)
            Text("虚线 = \(Int(searchRadius * 100))%R · 绿十字 = 锚点")
                .font(LabFont.microMono)
                .foregroundColor(LabPalette.inkTertiary)
        }
        .padding(12)
        .background(LabPalette.surfaceElevated)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(LabPalette.hairline, lineWidth: 1))
    }
}

/// 采样信号 vs 延迟对齐参考光码波形图。
struct LabCodedWaveformChart: View {
    let waveform: [LabWaveformPoint]
    let tint: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("时域亮斑信号 vs 延迟对齐参考码").font(LabFont.caption).foregroundColor(LabPalette.inkSecondary)
                Spacer()
                HStack(spacing: 10) {
                    legendDot("实测信号", tint)
                    legendDot("参考光码", LabPalette.inkSecondary)
                }
            }
            if waveform.count < 4 {
                Text("缺少可用于波形绘制的光码采样").font(LabFont.caption).foregroundColor(LabPalette.inkTertiary)
                    .frame(maxWidth: .infinity, minHeight: 80)
            } else {
                Canvas { ctx, size in
                    let tMin = waveform.first!.tSec
                    let tMax = max(tMin + 0.5, waveform.last!.tSec)
                    let maxSig = max(10.0, waveform.map(\.signal).max() ?? 10.0)
                    let padTop: CGFloat = 8
                    let h = size.height - 16

                    for g in 0...2 {
                        let y = padTop + h * CGFloat(g) / 2
                        var line = Path()
                        line.move(to: CGPoint(x: 0, y: y)); line.addLine(to: CGPoint(x: size.width, y: y))
                        ctx.stroke(line, with: .color(LabPalette.hairline), lineWidth: 1)
                    }

                    let stepW = size.width / CGFloat(max(1, waveform.count - 1))
                    for (i, pt) in waveform.enumerated() where pt.phase == .on {
                        ctx.fill(Path(CGRect(x: CGFloat(i) * stepW, y: padTop, width: stepW + 1, height: h)), with: .color(LabPalette.verified.opacity(0.08)))
                    }

                    var codePath = Path()
                    var sigPath = Path()
                    for (i, pt) in waveform.enumerated() {
                        let x = CGFloat(min(1, max(0, (pt.tSec - tMin) / (tMax - tMin)))) * size.width
                        let codeNorm = min(1, max(0, (pt.alignedCode + 1) * 0.5))
                        let codeY = padTop + h * CGFloat(1 - (0.15 + 0.70 * codeNorm))
                        let sigNorm = min(1, max(0, pt.signal / maxSig))
                        let sigY = padTop + h * CGFloat(1 - sigNorm)
                        if i == 0 {
                            codePath.move(to: CGPoint(x: x, y: codeY))
                            sigPath.move(to: CGPoint(x: x, y: sigY))
                        } else {
                            codePath.addLine(to: CGPoint(x: x, y: codeY))
                            sigPath.addLine(to: CGPoint(x: x, y: sigY))
                        }
                    }
                    ctx.stroke(codePath, with: .color(LabPalette.inkSecondary.opacity(0.65)), lineWidth: 1.5)
                    ctx.stroke(sigPath, with: .color(tint), style: StrokeStyle(lineWidth: 2.2, lineCap: .round))
                }
                .frame(height: 140)
            }
        }
        .padding(12)
        .background(LabPalette.surfaceElevated)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(LabPalette.hairline, lineWidth: 1))
    }
}

/// 0 移位真码 vs 63 错位码 |r| 分布柱状图。
struct LabShiftNullChart: View {
    let result: CornealCodeV8Result
    let tint: Color

    var body: some View {
        let nulls = result.shiftNullCorrelations.map { $0.doubleValue }
        let held = result.heldOutCorrelation?.doubleValue
        return VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("0 移位真码 vs 63 错位码 |r| 分布").font(LabFont.caption).foregroundColor(LabPalette.inkSecondary)
                Spacer()
                if let margin = result.codeMargin?.doubleValue {
                    Text(String(format: "margin %+.2f", margin))
                        .font(LabFont.microMono)
                        .foregroundColor(result.codeVerified ? LabPalette.verified : LabPalette.caution)
                }
            }
            if nulls.isEmpty || held == nil {
                Text("未形成留出段与 63 错位码分布").font(LabFont.caption).foregroundColor(LabPalette.inkTertiary)
                    .frame(maxWidth: .infinity, minHeight: 72)
            } else {
                Canvas { ctx, size in
                    let total = nulls.count + 1
                    let barW = max(2, size.width / CGFloat(total))
                    let padTop: CGFloat = 8
                    let h = size.height - 12

                    let y030 = padTop + h * 0.70
                    var ref1 = Path(); ref1.move(to: CGPoint(x: 0, y: y030)); ref1.addLine(to: CGPoint(x: size.width, y: y030))
                    ctx.stroke(ref1, with: .color(LabPalette.hairlineStrong), style: StrokeStyle(lineWidth: 1, dash: [5, 5]))

                    let trueH = CGFloat(min(1, max(0, held!))) * h
                    ctx.fill(
                        Path(roundedRect: CGRect(x: 0, y: padTop + h - trueH, width: max(barW * 1.4, 6), height: trueH), cornerRadius: 3),
                        with: .color(result.codeVerified ? LabPalette.verified : tint)
                    )
                    for (idx, v) in nulls.enumerated() {
                        let mag = CGFloat(min(1, max(0, abs(v)))) * h
                        let x = CGFloat(idx + 1) * barW + 2
                        ctx.fill(Path(CGRect(x: x, y: padTop + h - mag, width: max(1.5, barW - 1.2), height: mag)), with: .color(LabPalette.inkTertiary.opacity(0.65)))
                    }
                }
                .frame(height: 110)
            }
        }
        .padding(12)
        .background(LabPalette.surfaceElevated)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(LabPalette.hairline, lineWidth: 1))
    }
}

struct LabLineSeries {
    let name: String
    let color: Color
    let points: [(Double, Double)]
}

/// 通用科研多曲线时序图。
struct LabTimeSeriesChart: View {
    let title: String
    let unit: String
    let series: [LabLineSeries]
    var referenceLines: [(Double, Color)] = []
    var eventMarkersSec: [Double] = []

    var body: some View {
        let all = series.flatMap(\.points)
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(title).font(LabFont.caption).foregroundColor(LabPalette.inkSecondary)
                Spacer()
                if let lo = all.map(\.1).min(), let hi = all.map(\.1).max() {
                    Text("\(String(format: "%.1f .. %.1f", lo, hi)) \(unit)").font(LabFont.microMono).foregroundColor(LabPalette.inkSecondary)
                }
            }
            HStack(spacing: 12) {
                ForEach(series.indices, id: \.self) { i in
                    legendDot(series[i].name, series[i].color)
                }
            }
            if all.count < 2 {
                Text("有效时序采样不足").font(LabFont.caption).foregroundColor(LabPalette.inkTertiary)
                    .frame(maxWidth: .infinity, minHeight: 80)
            } else {
                let xMin = all.map(\.0).min() ?? 0
                let xMax = max(xMin + 0.5, all.map(\.0).max() ?? 1)
                let rawMin = min(all.map(\.1).min() ?? 0, referenceLines.map(\.0).min() ?? .infinity)
                let rawMax = max(all.map(\.1).max() ?? 1, referenceLines.map(\.0).max() ?? -.infinity)
                let pad = max(1.0, (rawMax - rawMin) * 0.12)
                let yMin = rawMin - pad
                let yMax = rawMax + pad

                Canvas { ctx, size in
                    let top: CGFloat = 6
                    let h = size.height - 12
                    func mx(_ x: Double) -> CGFloat { CGFloat(min(1, max(0, (x - xMin) / (xMax - xMin)))) * size.width }
                    func my(_ y: Double) -> CGFloat { top + h * CGFloat(1 - min(1, max(0, (y - yMin) / (yMax - yMin)))) }

                    for g in 0...2 {
                        let y = top + h * CGFloat(g) / 2
                        var p = Path(); p.move(to: CGPoint(x: 0, y: y)); p.addLine(to: CGPoint(x: size.width, y: y))
                        ctx.stroke(p, with: .color(LabPalette.hairline), lineWidth: 1)
                    }
                    for (v, col) in referenceLines {
                        let y = my(v)
                        var p = Path(); p.move(to: CGPoint(x: 0, y: y)); p.addLine(to: CGPoint(x: size.width, y: y))
                        ctx.stroke(p, with: .color(col.opacity(0.65)), style: StrokeStyle(lineWidth: 1.3, dash: [6, 5]))
                    }
                    for t in eventMarkersSec {
                        let x = mx(t)
                        var p = Path(); p.move(to: CGPoint(x: x, y: top)); p.addLine(to: CGPoint(x: x, y: top + h))
                        ctx.stroke(p, with: .color(LabPalette.verified.opacity(0.65)), style: StrokeStyle(lineWidth: 1.5, dash: [6, 5]))
                    }
                    for s in series where s.points.count >= 2 {
                        var path = Path()
                        for (idx, pt) in s.points.enumerated() {
                            let cg = CGPoint(x: mx(pt.0), y: my(pt.1))
                            if idx == 0 { path.move(to: cg) } else { path.addLine(to: cg) }
                        }
                        ctx.stroke(path, with: .color(s.color), style: StrokeStyle(lineWidth: 2, lineCap: .round))
                    }
                }
                .frame(height: 140)
            }
        }
        .padding(12)
        .background(LabPalette.surfaceElevated)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(LabPalette.hairline, lineWidth: 1))
    }
}

private func legendDot(_ title: String, _ color: Color) -> some View {
    HStack(spacing: 5) {
        Circle().fill(color).frame(width: 7, height: 7)
        Text(title).font(LabFont.microMono).foregroundColor(LabPalette.inkSecondary)
    }
}

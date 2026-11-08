import SwiftUI

/// 底部导航图标：按安卓 core/designsystem 的 ic_nav_* 矢量（24 视口、1.8 线宽）逐笔移植。
enum NavIcon { case home, services, mine }

struct NavIconView: View {
    let icon: NavIcon
    var selected = false
    var body: some View {
        Canvas { context, size in
            let k = min(size.width, size.height) / 24
            func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x * k, y: y * k) }
            let line = StrokeStyle(lineWidth: 1.8 * k, lineCap: .round, lineJoin: .round)
            let ink = GraphicsContext.Shading.foreground
            switch icon {
            case .home:
                // 房屋线稿（与安卓 ic_nav_home 一致）
                var house = Path()
                house.move(to: p(4, 10.5)); house.addLine(to: p(12, 4)); house.addLine(to: p(20, 10.5)); house.addLine(to: p(20, 20))
                house.addLine(to: p(14.8, 20)); house.addLine(to: p(14.8, 14.5)); house.addLine(to: p(9.2, 14.5)); house.addLine(to: p(9.2, 20))
                house.addLine(to: p(4, 20)); house.closeSubpath()
                if selected { context.fill(house, with: ink) } else { context.stroke(house, with: ink, style: line) }
            case .services:
                // 自测清单：圆角板 + 勾 + 两条线
                let board = Path(roundedRect: CGRect(x: 4 * k, y: 3.5 * k, width: 16 * k, height: 17 * k), cornerRadius: 2 * k)
                if selected { context.fill(board, with: ink) } else { context.stroke(board, with: ink, style: line) }
                var check = Path(); check.move(to: p(7.5, 9)); check.addLine(to: p(9.2, 10.7)); check.addLine(to: p(12, 7.8))
                context.stroke(check, with: selected ? .color(EyeStyle.panel) : ink, style: line)
                var lines = Path(); lines.move(to: p(14, 9.3)); lines.addLine(to: p(16.5, 9.3)); lines.move(to: p(7.5, 15)); lines.addLine(to: p(16.5, 15))
                context.stroke(lines, with: selected ? .color(EyeStyle.panel) : ink, style: line)
            case .mine:
                let head = Path(ellipseIn: CGRect(x: 8.2 * k, y: 4.7 * k, width: 7.6 * k, height: 7.6 * k))
                if selected { context.fill(head, with: ink) } else { context.stroke(head, with: ink, style: line) }
                var body = Path(); body.move(to: p(4.5, 20))
                body.addCurve(to: p(12, 14), control1: p(5.5, 15.8), control2: p(8.5, 14))
                body.addCurve(to: p(19.5, 20), control1: p(15.5, 14), control2: p(18.5, 15.8))
                if selected { body.closeSubpath(); context.fill(body, with: ink) } else { context.stroke(body, with: ink, style: line) }
            }
        }
    }
}

/// 服务入口图标：水滴（干眼）/ 双眼对位（眼位）/ 视标 E（老视），与安卓 ServiceGlyph 同一画法。
enum ServiceGlyph { case dryEye, alignment, presbyopia, color }

struct ServiceGlyphView: View {
    let glyph: ServiceGlyph
    /// 图标色（随入口卡的分类色调）。
    var color: Color = EyeStyle.primary
    var body: some View {
        Canvas { context, size in
            let s = min(size.width, size.height)
            func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x * s, y: y * s) }
            let primary = GraphicsContext.Shading.color(color)
            switch glyph {
            case .dryEye:
                var drop = Path(); drop.move(to: p(0.5, 0.08))
                drop.addCurve(to: p(0.84, 0.64), control1: p(0.62, 0.3), control2: p(0.84, 0.48))
                drop.addCurve(to: p(0.5, 0.94), control1: p(0.84, 0.84), control2: p(0.68, 0.94))
                drop.addCurve(to: p(0.16, 0.64), control1: p(0.32, 0.94), control2: p(0.16, 0.84))
                drop.addCurve(to: p(0.5, 0.08), control1: p(0.16, 0.48), control2: p(0.38, 0.3))
                drop.closeSubpath()
                context.stroke(drop, with: primary, style: StrokeStyle(lineWidth: s * 0.09, lineCap: .round, lineJoin: .round))
                // 水滴内的高光弧：安卓 drawArc(起 100°、扫 70°)，椭圆框 (0.3,0.5)-(0.64,0.8)
                var shine = Path()
                let rect = CGRect(x: 0.3 * s, y: 0.5 * s, width: 0.34 * s, height: 0.3 * s)
                for step in 0...14 {
                    let angle = (100 + 70 * Double(step) / 14) * .pi / 180
                    let point = CGPoint(x: rect.midX + rect.width / 2 * cos(angle), y: rect.midY + rect.height / 2 * sin(angle))
                    if step == 0 { shine.move(to: point) } else { shine.addLine(to: point) }
                }
                context.stroke(shine, with: primary, style: StrokeStyle(lineWidth: s * 0.08, lineCap: .round))
            case .color:
                // 一圈主色点、中间一竖暖色点：像一张极简的色觉图（与安卓 ServiceKind.COLOR 一致）
                context.stroke(Path(ellipseIn: CGRect(x: 0.08 * s, y: 0.08 * s, width: 0.84 * s, height: 0.84 * s)), with: primary, lineWidth: s * 0.07)
                for i in 0..<6 {
                    let a = (Double(i) * 60 + 30) * .pi / 180
                    let c = CGPoint(x: s * 0.5 + s * 0.24 * cos(a), y: s * 0.5 + s * 0.24 * sin(a))
                    context.fill(Path(ellipseIn: CGRect(x: c.x - s * 0.07, y: c.y - s * 0.07, width: s * 0.14, height: s * 0.14)), with: primary)
                }
                for y in [0.38, 0.62] as [CGFloat] {
                    context.fill(Path(ellipseIn: CGRect(x: s * 0.42, y: y * s - s * 0.08, width: s * 0.16, height: s * 0.16)), with: .color(color))
                }
            case .alignment:
                for side in [-1.0, 1.0] as [CGFloat] {
                    let cx = s * 0.5 + side * s * 0.24
                    context.stroke(leafEye(left: cx - s * 0.2, right: cx + s * 0.2, centerY: s * 0.5, halfHeight: s * 0.13),
                        with: primary, lineWidth: s * 0.08)
                    context.fill(Path(ellipseIn: CGRect(x: cx - s * 0.06, y: s * 0.44, width: s * 0.12, height: s * 0.12)), with: primary)
                }
                var base = Path(); base.move(to: p(0.1, 0.86)); base.addLine(to: p(0.9, 0.86))
                context.stroke(base, with: .color(color), style: StrokeStyle(lineWidth: s * 0.08, lineCap: .round))
            case .presbyopia:
                let edge = s * 0.62, bar = edge / 5
                let origin = CGPoint(x: (s - edge) / 2, y: (s - edge) / 2)
                context.fill(Path(CGRect(x: origin.x, y: origin.y, width: bar, height: edge)), with: primary)
                for row in [0, 2, 4] as [CGFloat] {
                    context.fill(Path(CGRect(x: origin.x, y: origin.y + row * bar, width: edge, height: bar)), with: primary)
                }
            }
        }
    }

    /// 叶形眼轮廓：左右尖端 + 上下两段三次曲线；控制点取 1.33 倍半高时曲线顶点约等于半高。
    private func leafEye(left: CGFloat, right: CGFloat, centerY: CGFloat, halfHeight: CGFloat) -> Path {
        let w = right - left, k = halfHeight * 1.33
        var path = Path(); path.move(to: CGPoint(x: left, y: centerY))
        path.addCurve(to: CGPoint(x: right, y: centerY), control1: CGPoint(x: left + w * 0.22, y: centerY - k), control2: CGPoint(x: right - w * 0.3, y: centerY - k))
        path.addCurve(to: CGPoint(x: left, y: centerY), control1: CGPoint(x: right - w * 0.22, y: centerY + k), control2: CGPoint(x: left + w * 0.3, y: centerY + k))
        path.closeSubpath()
        return path
    }
}

/// 品牌标志：主色渐变圆角底 + 白色同心环与中心点（安卓 drawBrandMark，绘制见 Cartoon.brandMark）。
struct BrandMarkView: View {
    var body: some View {
        Canvas { context, size in
            let s = min(size.width, size.height)
            Cartoon.brandMark(context, origin: CGPoint(x: (size.width - s) / 2, y: (size.height - s) / 2), side: s)
        }.accessibilityLabel("眼健康助手标志")
    }
}

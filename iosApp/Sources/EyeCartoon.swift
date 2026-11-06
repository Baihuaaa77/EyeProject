import SwiftUI

/// 示意图绘制原语：逐项移植安卓 core:ui EyeBrand.kt（drawFigure / drawHand / drawPhone / drawFixationTarget /
/// drawBrandMark / drawTumblingE），测试演示、注视目标与标志共用，保证两端一致。
/// 2026-11-06 起：演示人物为平面上半身短发男孩（沿用原吉祥物的演示动画），手为肤色手掌，手机为扁平面，注视目标为准星。
enum Cartoon {
    /// 在 [pivot] 处旋转后绘制。
    static func rotated(_ ctx: GraphicsContext, _ degrees: Double, around pivot: CGPoint, _ draw: (GraphicsContext) -> Void) {
        var layer = ctx
        layer.translateBy(x: pivot.x, y: pivot.y); layer.rotate(by: .degrees(degrees)); layer.translateBy(x: -pivot.x, y: -pivot.y)
        draw(layer)
    }

    static func oval(_ origin: CGPoint, _ size: CGSize) -> Path { Path(ellipseIn: CGRect(origin: origin, size: size)) }

    /// 叶形眼轮廓：左右尖端 + 上下两段三次曲线，[halfHeight] 为最大半高（安卓 leafEyePath）。
    static func leafEye(left: CGFloat, right: CGFloat, centerY: CGFloat, halfHeight: CGFloat) -> Path {
        let w = right - left, k = halfHeight * 1.33
        var path = Path()
        path.move(to: CGPoint(x: left, y: centerY))
        path.addCurve(to: CGPoint(x: right, y: centerY), control1: CGPoint(x: left + w * 0.22, y: centerY - k),
                      control2: CGPoint(x: right - w * 0.30, y: centerY - k))
        path.addCurve(to: CGPoint(x: left, y: centerY), control1: CGPoint(x: right - w * 0.22, y: centerY + k),
                      control2: CGPoint(x: left + w * 0.30, y: centerY + k))
        path.closeSubpath()
        return path
    }

    /// 示意人物某只眼的中心（side = −1 画面左侧，+1 画面右侧）。
    static func figureEyeCenter(_ center: CGPoint, _ size: CGFloat, _ side: CGFloat) -> CGPoint {
        CGPoint(x: center.x + side * size * 0.19, y: center.y - size * 0.02)
    }

    /// 示意人物：平面上半身短发男孩（安卓 drawFigure）。[size] 为头宽；[gaze] −1…1 豆豆眼平移；
    /// [lid] 0 睁开…1 闭合（<0.2 睁开度画闭眼弧线）；[wide] >1 睁大、眉毛上扬；[bottom] 上衣下沿 y（缺省头中心下 0.8 倍头宽）。
    static func figure(_ ctx: GraphicsContext, center c: CGPoint, size s: CGFloat, gaze: CGPoint = .zero,
                       lid: CGFloat = 0, wide: CGFloat = 1, bottom: CGFloat? = nil) {
        typealias I = EyeStyle.Illustration
        func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: c.x + x * s, y: c.y + y * s) }
        func dot(_ center: CGPoint, _ r: CGFloat) -> Path { Path(ellipseIn: CGRect(x: center.x - r, y: center.y - r, width: r * 2, height: r * 2)) }
        // 上衣：圆肩，一直画到 bottom
        let y0 = c.y + s * 0.5
        let hem = max(bottom ?? c.y + s * 0.8, y0 + s * 0.3)
        var shirt = Path()
        shirt.move(to: CGPoint(x: c.x - s * 0.62, y: hem))
        shirt.addLine(to: CGPoint(x: c.x - s * 0.62, y: y0 + s * 0.3))
        shirt.addQuadCurve(to: CGPoint(x: c.x - s * 0.32, y: y0), control: CGPoint(x: c.x - s * 0.62, y: y0))
        shirt.addLine(to: CGPoint(x: c.x + s * 0.32, y: y0))
        shirt.addQuadCurve(to: CGPoint(x: c.x + s * 0.62, y: y0 + s * 0.3), control: CGPoint(x: c.x + s * 0.62, y: y0))
        shirt.addLine(to: CGPoint(x: c.x + s * 0.62, y: hem))
        shirt.closeSubpath()
        ctx.fill(shirt, with: .color(I.shirt))
        // 脖子 + 圆领
        ctx.fill(Path(CGRect(origin: p(-0.1, 0.28), size: CGSize(width: s * 0.2, height: s * 0.26))), with: .color(I.skinShade))
        var collar = Path()
        collar.move(to: CGPoint(x: c.x - s * 0.16, y: y0))
        collar.addQuadCurve(to: CGPoint(x: c.x + s * 0.16, y: y0), control: CGPoint(x: c.x, y: y0 + s * 0.15))
        ctx.fill(collar, with: .color(I.skinShade))
        ctx.stroke(collar, with: .color(I.shirtLight), style: StrokeStyle(lineWidth: s * 0.04, lineCap: .round))
        // 耳朵（在脸后面）
        for side: CGFloat in [-1, 1] {
            ctx.fill(dot(p(side * 0.4, 0.04), s * 0.08), with: .color(I.skin))
            ctx.fill(dot(p(side * 0.405, 0.04), s * 0.035), with: .color(I.skinShade))
        }
        // 脸
        ctx.fill(oval(p(-0.4, -0.43), CGSize(width: s * 0.8, height: s * 0.86)), with: .color(I.skin))
        // 侧分短发：盖住头顶，刘海从左向右扫开，露出额头
        var hair = Path()
        hair.move(to: p(-0.43, 0.04))
        hair.addCurve(to: p(0.04, -0.54), control1: p(-0.47, -0.36), control2: p(-0.22, -0.56))
        hair.addCurve(to: p(0.43, 0.04), control1: p(0.32, -0.53), control2: p(0.5, -0.36))
        hair.addCurve(to: p(0.24, -0.25), control1: p(0.4, -0.1), control2: p(0.34, -0.2))
        hair.addCurve(to: p(-0.3, -0.23), control1: p(0.1, -0.17), control2: p(-0.12, -0.15))
        hair.addCurve(to: p(-0.43, 0.04), control1: p(-0.37, -0.15), control2: p(-0.4, -0.06))
        hair.closeSubpath()
        ctx.fill(hair, with: .color(I.hair))
        // 眉毛 + 豆豆眼
        let open = min(1, max(0, 1 - lid))
        for side: CGFloat in [-1, 1] {
            let base = figureEyeCenter(c, s, side)
            let browY = base.y - s * 0.13 - (wide - 1) * s * 0.08
            var brow = Path()
            brow.move(to: CGPoint(x: base.x - s * 0.055, y: browY + s * 0.01))
            brow.addQuadCurve(to: CGPoint(x: base.x + s * 0.055, y: browY + s * 0.01), control: CGPoint(x: base.x, y: browY - s * 0.025))
            ctx.stroke(brow, with: .color(I.hair), style: StrokeStyle(lineWidth: s * 0.024, lineCap: .round))
            if open < 0.2 {
                var closed = Path(); closed.move(to: CGPoint(x: base.x - s * 0.055, y: base.y))
                closed.addQuadCurve(to: CGPoint(x: base.x + s * 0.055, y: base.y), control: CGPoint(x: base.x, y: base.y + s * 0.045))
                ctx.stroke(closed, with: .color(EyeStyle.ink), style: StrokeStyle(lineWidth: s * 0.026, lineCap: .round))
                continue
            }
            let e = CGPoint(x: base.x + gaze.x * s * 0.035, y: base.y + gaze.y * s * 0.03)
            let rx = s * 0.052 * wide, ry = s * 0.072 * wide * open
            ctx.fill(oval(CGPoint(x: e.x - rx, y: e.y - ry), CGSize(width: rx * 2, height: ry * 2)), with: .color(EyeStyle.ink))
            if open > 0.5 { ctx.fill(dot(CGPoint(x: e.x + rx * 0.4, y: e.y - ry * 0.45), s * 0.02 * wide), with: .color(.white)) }
        }
        // 鼻子、腮红、微笑
        var nose = Path(); nose.move(to: p(-0.022, 0.075)); nose.addQuadCurve(to: p(0.022, 0.075), control: p(0, 0.095))
        ctx.stroke(nose, with: .color(I.skinLine), style: StrokeStyle(lineWidth: s * 0.018, lineCap: .round))
        for side: CGFloat in [-1, 1] {
            ctx.fill(oval(p(side * 0.26 - 0.055, 0.14 - 0.032), CGSize(width: s * 0.11, height: s * 0.064)), with: .color(I.blush.opacity(0.6)))
        }
        var mouth = Path(); mouth.move(to: p(-0.06, 0.19)); mouth.addQuadCurve(to: p(0.06, 0.19), control: p(0, 0.245))
        ctx.stroke(mouth, with: .color(I.mouth), style: StrokeStyle(lineWidth: s * 0.024, lineCap: .round))
    }

    /// 品牌标志：主色渐变圆角底 + 白色同心环与中心点（安卓 drawBrandMark）。
    static func brandMark(_ ctx: GraphicsContext, origin: CGPoint, side s: CGFloat, corner: CGFloat = 0.28) {
        let rect = CGRect(origin: origin, size: CGSize(width: s, height: s))
        ctx.fill(RoundedRectangle(cornerRadius: s * corner, style: .continuous).path(in: rect), with: .linearGradient(
            Gradient(colors: [EyeStyle.brandMarkStart, EyeStyle.buttonStart, EyeStyle.primaryDark]), startPoint: rect.origin,
            endPoint: CGPoint(x: rect.maxX, y: rect.maxY)))
        let c = CGPoint(x: rect.midX, y: rect.midY)
        func ring(_ r: CGFloat) -> Path { Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2)) }
        ctx.stroke(ring(s * 0.31), with: .color(.white.opacity(0.3)), lineWidth: s * 0.035)
        ctx.stroke(ring(s * 0.2), with: .color(.white), lineWidth: s * 0.07)
        ctx.fill(ring(s * 0.065), with: .color(.white))
    }

    /// 注视目标（斜视测试）：同心环 + 十字 + 中心点，[size] 为外环直径；[color] 默认主色（白底画布上传固定深蓝）。
    static func fixationTarget(_ ctx: GraphicsContext, center c: CGPoint, size: CGFloat, color: Color = EyeStyle.primary, pulse: CGFloat = 0) {
        let r = size / 2
        func ring(_ radius: CGFloat) -> Path { Path(ellipseIn: CGRect(x: c.x - radius, y: c.y - radius, width: radius * 2, height: radius * 2)) }
        ctx.stroke(ring(r * (0.9 + pulse * 0.1)), with: .color(color.opacity(0.35)), lineWidth: r * 0.1)
        ctx.stroke(ring(r * 0.55), with: .color(color), lineWidth: r * 0.12)
        let gap = r * 0.22
        for (dx, dy) in [(1.0, 0.0), (-1.0, 0.0), (0.0, 1.0), (0.0, -1.0)] as [(CGFloat, CGFloat)] {
            var line = Path()
            line.move(to: CGPoint(x: c.x + dx * gap, y: c.y + dy * gap))
            line.addLine(to: CGPoint(x: c.x + dx * r * 0.78, y: c.y + dy * r * 0.78))
            ctx.stroke(line, with: .color(color), style: StrokeStyle(lineWidth: r * 0.08, lineCap: .round))
        }
        ctx.fill(ring(r * 0.14), with: .color(color))
    }

    /// 示意手（肤色手掌 + 拇指 + 指缝 + 蓝色袖子，安卓 drawHand）：[angle] 手掌旋转角度，[armFrom] 手臂起点。
    static func hand(_ ctx: GraphicsContext, center c: CGPoint, size: CGFloat, angle: Double = 0, armFrom: CGPoint? = nil) {
        typealias I = EyeStyle.Illustration
        let outline = size * 0.05
        if let from = armFrom {
            var arm = Path(); arm.move(to: from); arm.addLine(to: CGPoint(x: c.x, y: c.y + size * 0.3))
            ctx.stroke(arm, with: .color(I.shirt), style: StrokeStyle(lineWidth: size * 0.56, lineCap: .round))
        }
        rotated(ctx, angle, around: c) { layer in
            let palm = CGSize(width: size * 0.8, height: size)
            let tl = CGPoint(x: c.x - palm.width / 2, y: c.y - palm.height / 2)
            let thumb = oval(CGPoint(x: c.x - palm.width * 0.5 - size * 0.16, y: c.y + size * 0.08 - size * 0.24), CGSize(width: size * 0.32, height: size * 0.48))
            layer.fill(thumb, with: .color(I.skin))
            layer.stroke(thumb, with: .color(I.skinLine), lineWidth: outline)
            let palmPath = Path(roundedRect: CGRect(origin: tl, size: palm), cornerRadius: size * 0.4)
            layer.fill(palmPath, with: .color(I.skin))
            layer.stroke(palmPath, with: .color(I.skinLine), lineWidth: outline)
            // 指缝：三道短线
            for i in 1...3 {
                let x = tl.x + palm.width * CGFloat(i) / 4
                var gap = Path(); gap.move(to: CGPoint(x: x, y: tl.y + size * 0.08)); gap.addLine(to: CGPoint(x: x, y: tl.y + size * 0.36))
                layer.stroke(gap, with: .color(I.skinLine), style: StrokeStyle(lineWidth: size * 0.04, lineCap: .round))
            }
        }
    }

    /// 示意手机：返回屏幕区域左上角与尺寸。屏幕恒为白底，与正式视标的黑白对比一致。
    @discardableResult
    static func phone(_ ctx: GraphicsContext, center c: CGPoint, height: CGFloat) -> (CGPoint, CGSize) {
        let w = height * 0.5
        let tl = CGPoint(x: c.x - w / 2, y: c.y - height / 2)
        ctx.fill(Path(roundedRect: CGRect(origin: tl, size: CGSize(width: w, height: height)), cornerRadius: w * 0.18), with: .color(EyeStyle.ink))
        let inset = w * 0.05
        let screenTl = CGPoint(x: tl.x + inset, y: tl.y + inset)
        let screen = CGSize(width: w - inset * 2, height: height - inset * 2)
        ctx.fill(Path(roundedRect: CGRect(origin: screenTl, size: screen), cornerRadius: w * 0.14), with: .color(.white))
        let r = w * 0.03
        ctx.fill(oval(CGPoint(x: c.x - r, y: screenTl.y + w * 0.07 - r), CGSize(width: r * 2, height: r * 2)), with: .color(EyeStyle.ink))
        return (screenTl, screen)
    }

    /// 视标 E：[direction] 0 右 1 下 2 左 3 上（开口方向）。
    static func tumblingE(_ ctx: GraphicsContext, color: Color, center c: CGPoint, edge: CGFloat, direction: Int, alpha: Double = 1) {
        var layer = ctx
        layer.opacity = alpha
        rotated(layer, Double(direction) * 90, around: c) { l in
            let bar = edge / 5
            let origin = CGPoint(x: c.x - edge / 2, y: c.y - edge / 2)
            l.fill(Path(CGRect(x: origin.x, y: origin.y, width: bar, height: edge)), with: .color(color))
            for row in [0, 2, 4] as [CGFloat] {
                l.fill(Path(CGRect(x: origin.x, y: origin.y + row * bar, width: edge, height: bar)), with: .color(color))
            }
        }
    }
}

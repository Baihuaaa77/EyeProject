import SwiftUI

/// 示意图绘制原语：逐项移植安卓 core:ui EyeBrand.kt（drawFigure / drawHand / drawPhone / drawFixationTarget /
/// drawBrandMark / drawTumblingE），测试演示、注视目标与标志共用，保证两端一致。
/// 2026-11 视觉改版：去掉吉祥物，人物为中性线面头像（只画两只眼），手与手机为扁平面，注视目标为准星。
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

    /// 示意人物（圆顶肩膀 + 头 + 两只眼）：[size] 为参考宽度；[gaze] −1…1 虹膜平移；[lid] 0 睁开…1 闭合（<0.2 睁开度画闭眼弧线）；[wide] >1 睁大。
    static func figure(_ ctx: GraphicsContext, center c: CGPoint, size s: CGFloat, gaze: CGPoint = .zero,
                       lid: CGFloat = 0, wide: CGFloat = 1) {
        let top = c.y - s * 0.46
        // 肩膀：头后方一段圆顶半身（类似系统人像图标）
        var shoulders = Path()
        shoulders.move(to: CGPoint(x: c.x - s * 0.5, y: c.y + s * 0.58))
        shoulders.addCurve(to: CGPoint(x: c.x, y: c.y + s * 0.28), control1: CGPoint(x: c.x - s * 0.5, y: c.y + s * 0.36),
                           control2: CGPoint(x: c.x - s * 0.28, y: c.y + s * 0.28))
        shoulders.addCurve(to: CGPoint(x: c.x + s * 0.5, y: c.y + s * 0.58), control1: CGPoint(x: c.x + s * 0.28, y: c.y + s * 0.28),
                           control2: CGPoint(x: c.x + s * 0.5, y: c.y + s * 0.36))
        shoulders.closeSubpath()
        ctx.fill(shoulders, with: .color(EyeStyle.brandStart.opacity(0.4)))
        // 头：竖椭圆，浅主色面 + 细描边
        let head = CGRect(x: c.x - s * 0.39, y: top, width: s * 0.78, height: s * 0.84)
        let headPath = Path(ellipseIn: head)
        ctx.fill(headPath, with: .color(EyeStyle.primarySoft))
        ctx.stroke(headPath, with: .color(EyeStyle.primary.opacity(0.35)), lineWidth: s * 0.016)
        let open = min(1, max(0, 1 - lid))
        for side: CGFloat in [-1, 1] {
            let base = figureEyeCenter(c, s, side)
            let half = s * 0.085 * wide
            if open < 0.2 {
                var closed = Path(); closed.move(to: CGPoint(x: base.x - half, y: base.y))
                closed.addQuadCurve(to: CGPoint(x: base.x + half, y: base.y), control: CGPoint(x: base.x, y: base.y + s * 0.04))
                ctx.stroke(closed, with: .color(EyeStyle.ink), style: StrokeStyle(lineWidth: s * 0.02, lineCap: .round))
                continue
            }
            let eye = leafEye(left: base.x - half, right: base.x + half, centerY: base.y, halfHeight: s * 0.048 * wide * open)
            ctx.fill(eye, with: .color(.white))
            var iris = ctx
            iris.clip(to: eye)
            let e = CGPoint(x: base.x + gaze.x * s * 0.04, y: base.y + gaze.y * s * 0.025)
            let r = s * 0.042 * wide
            iris.fill(oval(CGPoint(x: e.x - r, y: e.y - r), CGSize(width: r * 2, height: r * 2)), with: .color(EyeStyle.ink))
            let g = s * 0.012 * wide
            iris.fill(oval(CGPoint(x: e.x + s * 0.014 - g, y: e.y - s * 0.014 - g), CGSize(width: g * 2, height: g * 2)), with: .color(.white))
            ctx.stroke(eye, with: .color(EyeStyle.ink), lineWidth: s * 0.012)
        }
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

    /// 示意手（扁平手掌 + 手臂）：[angle] 手掌旋转角度，[armFrom] 手臂起点。
    static func hand(_ ctx: GraphicsContext, center c: CGPoint, size: CGFloat, angle: Double = 0, armFrom: CGPoint? = nil) {
        let fill = EyeStyle.brandStart.opacity(0.55)
        if let from = armFrom {
            var arm = Path(); arm.move(to: from); arm.addLine(to: c)
            ctx.stroke(arm, with: .color(fill), style: StrokeStyle(lineWidth: size * 0.46, lineCap: .round))
        }
        rotated(ctx, angle, around: c) { layer in
            let palm = CGSize(width: size * 0.78, height: size)
            let tl = CGPoint(x: c.x - palm.width / 2, y: c.y - palm.height / 2)
            let thumb = CGPoint(x: c.x - palm.width * 0.5, y: c.y + size * 0.08)
            layer.fill(oval(CGPoint(x: thumb.x - size * 0.14, y: thumb.y - size * 0.22), CGSize(width: size * 0.28, height: size * 0.44)), with: .color(fill))
            let palmPath = Path(roundedRect: CGRect(origin: tl, size: palm), cornerRadius: size * 0.36)
            layer.fill(palmPath, with: .color(fill))
            layer.stroke(palmPath, with: .color(EyeStyle.primary.opacity(0.4)), lineWidth: size * 0.03)
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

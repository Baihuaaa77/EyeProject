import SwiftUI

/// 卡通绘制原语：逐项移植安卓 core:ui EyeBrand.kt（drawMascot / drawMitten / drawCartoonPhone / drawTumblingE），
/// 小瞳形象、测试演示与注视目标共用，保证两端一致。
enum Cartoon {
    static let accentSoft = Color(rgb: 0xFFF1DC)
    static let gray = Color(rgb: 0x8A9896)

    /// 在 [pivot] 处旋转后绘制。
    static func rotated(_ ctx: GraphicsContext, _ degrees: Double, around pivot: CGPoint, _ draw: (GraphicsContext) -> Void) {
        var layer = ctx
        layer.translateBy(x: pivot.x, y: pivot.y); layer.rotate(by: .degrees(degrees)); layer.translateBy(x: -pivot.x, y: -pivot.y)
        draw(layer)
    }

    static func oval(_ origin: CGPoint, _ size: CGSize) -> Path { Path(ellipseIn: CGRect(origin: origin, size: size)) }

    /// 吉祥物某只眼的中心（side = −1 画面左侧，+1 画面右侧）。
    static func mascotEyeCenter(_ center: CGPoint, _ size: CGFloat, _ side: CGFloat) -> CGPoint {
        CGPoint(x: center.x + side * size * 0.19, y: center.y - size * 0.02)
    }

    /// 小瞳：[size] 为头宽；[gaze] −1…1 平移豆豆眼；[lid] 0 睁开…1 闭合（<0.2 睁开度画闭眼弧线）；[wide] >1 睁大。
    static func mascot(_ ctx: GraphicsContext, center c: CGPoint, size s: CGFloat, gaze: CGPoint = .zero,
                       lid: CGFloat = 0, wide: CGFloat = 1, smile: Bool = true) {
        let top = c.y - s * 0.42
        // 双叶嫩芽
        var stem = Path(); stem.move(to: CGPoint(x: c.x, y: top + s * 0.02))
        stem.addQuadCurve(to: CGPoint(x: c.x + s * 0.015, y: top - s * 0.1), control: CGPoint(x: c.x + s * 0.005, y: top - s * 0.05))
        ctx.stroke(stem, with: .color(EyeStyle.brandStart), style: StrokeStyle(lineWidth: s * 0.035, lineCap: .round))
        rotated(ctx, 25, around: CGPoint(x: c.x - s * 0.06, y: top - s * 0.11)) {
            $0.fill(oval(CGPoint(x: c.x - s * 0.15, y: top - s * 0.155), CGSize(width: s * 0.18, height: s * 0.09)), with: .color(EyeStyle.brandStart))
        }
        rotated(ctx, -30, around: CGPoint(x: c.x + s * 0.09, y: top - s * 0.14)) {
            $0.fill(oval(CGPoint(x: c.x - s * 0.01, y: top - s * 0.19), CGSize(width: s * 0.2, height: s * 0.1)), with: .color(EyeStyle.brandStart))
        }
        // 团子头 + 顶部柔光
        let head = CGRect(x: c.x - s / 2, y: top, width: s, height: s * 0.84)
        ctx.fill(Path(roundedRect: head, cornerRadius: s * 0.4), with: .linearGradient(
            Gradient(colors: [EyeStyle.brandStart, EyeStyle.brandEnd]), startPoint: head.origin, endPoint: CGPoint(x: head.maxX, y: head.maxY)))
        rotated(ctx, -18, around: CGPoint(x: c.x - s * 0.2, y: top + s * 0.14)) {
            $0.fill(oval(CGPoint(x: c.x - s * 0.33, y: top + s * 0.09), CGSize(width: s * 0.26, height: s * 0.1)), with: .color(.white.opacity(0.16)))
        }
        // 豆豆眼
        let open = min(1, max(0, 1 - lid))
        for side: CGFloat in [-1, 1] {
            let base = mascotEyeCenter(c, s, side)
            if open < 0.2 {
                var closed = Path(); closed.move(to: CGPoint(x: base.x - s * 0.055, y: base.y))
                closed.addQuadCurve(to: CGPoint(x: base.x + s * 0.055, y: base.y), control: CGPoint(x: base.x, y: base.y + s * 0.045))
                ctx.stroke(closed, with: .color(EyeStyle.ink), style: StrokeStyle(lineWidth: s * 0.026, lineCap: .round))
                continue
            }
            let e = CGPoint(x: base.x + gaze.x * s * 0.035, y: base.y + gaze.y * s * 0.03)
            let rx = s * 0.052 * wide, ry = s * 0.072 * wide * open
            ctx.fill(oval(CGPoint(x: e.x - rx, y: e.y - ry), CGSize(width: rx * 2, height: ry * 2)), with: .color(EyeStyle.ink))
            if open > 0.5 {
                let r = s * 0.02 * wide, g = CGPoint(x: e.x + rx * 0.4, y: e.y - ry * 0.45)
                ctx.fill(oval(CGPoint(x: g.x - r, y: g.y - r), CGSize(width: r * 2, height: r * 2)), with: .color(.white))
            }
        }
        // 腮红
        for side: CGFloat in [-1, 1] {
            let b = CGPoint(x: c.x + side * s * 0.31, y: c.y + s * 0.11)
            ctx.fill(oval(CGPoint(x: b.x - s * 0.055, y: b.y - s * 0.032), CGSize(width: s * 0.11, height: s * 0.064)),
                with: .color(EyeStyle.accent.opacity(0.75)))
        }
        // 嘴
        let mouthY = c.y + s * 0.1
        if smile {
            var mouth = Path(); mouth.move(to: CGPoint(x: c.x - s * 0.05, y: mouthY))
            mouth.addQuadCurve(to: CGPoint(x: c.x + s * 0.05, y: mouthY), control: CGPoint(x: c.x, y: mouthY + s * 0.05))
            ctx.stroke(mouth, with: .color(EyeStyle.ink), style: StrokeStyle(lineWidth: s * 0.024, lineCap: .round))
        } else {
            let r = s * 0.024
            ctx.fill(oval(CGPoint(x: c.x - r, y: mouthY + s * 0.02 - r), CGSize(width: r * 2, height: r * 2)), with: .color(EyeStyle.ink))
        }
    }

    /// 卡通手（连指手套 + 手臂）：[angle] 手掌旋转角度，[armFrom] 手臂起点。
    static func mitten(_ ctx: GraphicsContext, center c: CGPoint, size: CGFloat, angle: Double = 0, armFrom: CGPoint? = nil) {
        let outline = size * 0.07
        if let from = armFrom {
            var arm = Path(); arm.move(to: from); arm.addLine(to: c)
            ctx.stroke(arm, with: .color(EyeStyle.accent), style: StrokeStyle(lineWidth: size * 0.52, lineCap: .round))
            ctx.stroke(arm, with: .color(accentSoft), style: StrokeStyle(lineWidth: size * 0.52 - outline * 2, lineCap: .round))
        }
        rotated(ctx, angle, around: c) { layer in
            let palm = CGSize(width: size * 0.8, height: size)
            let tl = CGPoint(x: c.x - palm.width / 2, y: c.y - palm.height / 2)
            let thumb = CGPoint(x: c.x - palm.width * 0.5, y: c.y + size * 0.08)
            let thumbOval = oval(CGPoint(x: thumb.x - size * 0.16, y: thumb.y - size * 0.24), CGSize(width: size * 0.32, height: size * 0.48))
            layer.fill(thumbOval, with: .color(accentSoft))
            layer.stroke(thumbOval, with: .color(EyeStyle.accent), lineWidth: outline)
            let palmPath = Path(roundedRect: CGRect(origin: tl, size: palm), cornerRadius: size * 0.4)
            layer.fill(palmPath, with: .color(accentSoft))
            layer.stroke(palmPath, with: .color(EyeStyle.accent), lineWidth: outline)
            for i in 1...3 {
                let x = tl.x + palm.width * CGFloat(i) / 4
                var gap = Path(); gap.move(to: CGPoint(x: x, y: tl.y + size * 0.08)); gap.addLine(to: CGPoint(x: x, y: tl.y + size * 0.36))
                layer.stroke(gap, with: .color(EyeStyle.accent), style: StrokeStyle(lineWidth: outline * 0.8, lineCap: .round))
            }
        }
    }

    /// 卡通手机：返回屏幕区域左上角与尺寸。屏幕恒为白底，与正式视标的黑白对比一致。
    @discardableResult
    static func phone(_ ctx: GraphicsContext, center c: CGPoint, height: CGFloat) -> (CGPoint, CGSize) {
        let w = height * 0.5
        let tl = CGPoint(x: c.x - w / 2, y: c.y - height / 2)
        ctx.fill(Path(roundedRect: CGRect(origin: tl, size: CGSize(width: w, height: height)), cornerRadius: w * 0.2), with: .color(EyeStyle.ink))
        let inset = w * 0.08
        let screenTl = CGPoint(x: tl.x + inset, y: tl.y + inset * 1.6)
        let screen = CGSize(width: w - inset * 2, height: height - inset * 3.2)
        ctx.fill(Path(roundedRect: CGRect(origin: screenTl, size: screen), cornerRadius: w * 0.12), with: .color(.white))
        let r = w * 0.035
        ctx.fill(oval(CGPoint(x: c.x - r, y: tl.y + inset * 0.8 - r), CGSize(width: r * 2, height: r * 2)), with: .color(gray))
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

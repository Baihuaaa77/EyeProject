import SwiftUI

/// 小瞳的眼睛：豆豆眼 / 笑眯眯（弯弯的 ^ ^）/ 眨一只眼（画面右侧那只弯起）/ 转圈圈（晕乎乎）。
enum MascotEyes { case dot, happy, wink, spiral }

/// 小瞳的嘴：微笑 / 小圆口（惊讶、思考）/ 张嘴大笑 / 一字 / 波浪（担心）/ 打哈欠（张大的椭圆）。
enum MascotMouth { case smile, round, grin, flat, wobbly, yawn }

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
    /// [eyes] / [mouth] 为互动表情（与安卓 MascotEyes / MascotMouth 一致，mouth 为 nil 时按 smile 画微笑或小圆口）；
    /// [blush] 腮红浓度，[leafSway] 嫩芽额外摆动角度（度），[wilt] 嫩芽打蔫程度 0 精神…1 枯萎（茎弯、叶片下垂变小、颜色发黄）。
    static func mascot(_ ctx: GraphicsContext, center c: CGPoint, size s: CGFloat, gaze: CGPoint = .zero,
                       lid: CGFloat = 0, wide: CGFloat = 1, smile: Bool = true, eyes: MascotEyes = .dot,
                       mouth: MascotMouth? = nil, blush: CGFloat = 0.75, leafSway: Double = 0, wilt: CGFloat = 0) {
        let top = c.y - s * 0.42
        let w = min(1, max(0, wilt))
        let dx = w * s * 0.035, dy = w * s * 0.035
        let shrink = 1 - 0.15 * w
        // 叶色：主色上叠一层琥珀色，越蔫越黄（与安卓 lerp(brandStart, warning, 0.7·wilt) 一致）
        func paint(_ layer: GraphicsContext, _ path: Path) {
            layer.fill(path, with: .color(EyeStyle.brandStart))
            if w > 0 { layer.fill(path, with: .color(EyeStyle.warning.opacity(Double(w * 0.7)))) }
        }
        // 双叶嫩芽（随 leafSway 绕茎根摆动；打蔫时整株歪向一侧、茎弯下、两片叶子耷拉）
        rotated(ctx, leafSway + Double(w * 20), around: CGPoint(x: c.x, y: top + s * 0.02)) { sprout in
            var stem = Path(); stem.move(to: CGPoint(x: c.x, y: top + s * 0.02))
            stem.addQuadCurve(to: CGPoint(x: c.x + s * 0.015 + dx, y: top - s * 0.1 + dy), control: CGPoint(x: c.x + s * 0.005, y: top - s * 0.05 - w * s * 0.01))
            let stroke = StrokeStyle(lineWidth: s * 0.035, lineCap: .round)
            sprout.stroke(stem, with: .color(EyeStyle.brandStart), style: stroke)
            if w > 0 { sprout.stroke(stem, with: .color(EyeStyle.warning.opacity(Double(w * 0.7))), style: stroke) }
            let left = CGPoint(x: c.x - s * 0.06 + dx, y: top - s * 0.11 + dy)
            leaf(sprout, pivot: left, degrees: Double(25 - w * 60), scale: shrink, offset: CGSize(width: dx, height: dy)) {
                paint($0, oval(CGPoint(x: c.x - s * 0.15, y: top - s * 0.155), CGSize(width: s * 0.18, height: s * 0.09)))
            }
            let right = CGPoint(x: c.x + s * 0.09 + dx, y: top - s * 0.14 + dy)
            leaf(sprout, pivot: right, degrees: Double(-30 + w * 65), scale: shrink, offset: CGSize(width: dx, height: dy)) {
                paint($0, oval(CGPoint(x: c.x - s * 0.01, y: top - s * 0.19), CGSize(width: s * 0.2, height: s * 0.1)))
            }
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
            if eyes == .spiral {
                // 晕乎乎：由内向外转三圈的螺旋
                var spiral = Path()
                for i in 0...36 {
                    let a = CGFloat(i) * 0.52 * side
                    let r = s * (0.006 + 0.0015 * CGFloat(i))
                    let q = CGPoint(x: base.x + r * cos(a), y: base.y + r * sin(a))
                    if i == 0 { spiral.move(to: q) } else { spiral.addLine(to: q) }
                }
                ctx.stroke(spiral, with: .color(EyeStyle.ink), style: StrokeStyle(lineWidth: s * 0.018, lineCap: .round, lineJoin: .round))
                continue
            }
            let happy = eyes == .happy || (eyes == .wink && side == 1)
            if happy {
                // 笑眯眯：向上弯的弧线 ^
                var arc = Path(); arc.move(to: CGPoint(x: base.x - s * 0.055, y: base.y + s * 0.015))
                arc.addQuadCurve(to: CGPoint(x: base.x + s * 0.055, y: base.y + s * 0.015), control: CGPoint(x: base.x, y: base.y - s * 0.055))
                ctx.stroke(arc, with: .color(EyeStyle.ink), style: StrokeStyle(lineWidth: s * 0.03, lineCap: .round))
                continue
            }
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
        // 腮红（害羞时加深、变大）
        let blushScale = 1 + max(0, blush - 0.75) * 0.8
        for side: CGFloat in [-1, 1] {
            let b = CGPoint(x: c.x + side * s * 0.31, y: c.y + s * 0.11)
            let w = s * 0.11 * blushScale, h = s * 0.064 * blushScale
            ctx.fill(oval(CGPoint(x: b.x - w / 2, y: b.y - h / 2), CGSize(width: w, height: h)),
                with: .color(EyeStyle.accent.opacity(Double(min(1, max(0, blush))))))
        }
        // 嘴
        let mouthY = c.y + s * 0.1
        switch mouth ?? (smile ? .smile : .round) {
        case .smile:
            var path = Path(); path.move(to: CGPoint(x: c.x - s * 0.05, y: mouthY))
            path.addQuadCurve(to: CGPoint(x: c.x + s * 0.05, y: mouthY), control: CGPoint(x: c.x, y: mouthY + s * 0.05))
            ctx.stroke(path, with: .color(EyeStyle.ink), style: StrokeStyle(lineWidth: s * 0.024, lineCap: .round))
        case .round:
            let r = s * 0.024
            ctx.fill(oval(CGPoint(x: c.x - r, y: mouthY + s * 0.02 - r), CGSize(width: r * 2, height: r * 2)), with: .color(EyeStyle.ink))
        case .grin:
            // 张嘴大笑：上沿平、下沿圆的半月，里面一点舌头
            var path = Path(); path.move(to: CGPoint(x: c.x - s * 0.07, y: mouthY - s * 0.005))
            path.addQuadCurve(to: CGPoint(x: c.x + s * 0.07, y: mouthY - s * 0.005), control: CGPoint(x: c.x, y: mouthY + s * 0.11))
            path.closeSubpath()
            ctx.fill(path, with: .color(EyeStyle.ink))
            var inner = ctx
            inner.clip(to: path)
            inner.fill(oval(CGPoint(x: c.x - s * 0.035, y: mouthY + s * 0.025), CGSize(width: s * 0.07, height: s * 0.05)), with: .color(EyeStyle.accent))
        case .yawn:
            // 打哈欠：张大的竖椭圆，里面一点舌头
            let path = Path(ellipseIn: CGRect(x: c.x - s * 0.05, y: mouthY - s * 0.025, width: s * 0.1, height: s * 0.125))
            ctx.fill(path, with: .color(EyeStyle.ink))
            var inner = ctx
            inner.clip(to: path)
            inner.fill(oval(CGPoint(x: c.x - s * 0.035, y: mouthY + s * 0.05), CGSize(width: s * 0.07, height: s * 0.06)), with: .color(EyeStyle.accent))
        case .flat:
            var path = Path(); path.move(to: CGPoint(x: c.x - s * 0.04, y: mouthY + s * 0.02)); path.addLine(to: CGPoint(x: c.x + s * 0.04, y: mouthY + s * 0.02))
            ctx.stroke(path, with: .color(EyeStyle.ink), style: StrokeStyle(lineWidth: s * 0.022, lineCap: .round))
        case .wobbly:
            var path = Path(); path.move(to: CGPoint(x: c.x - s * 0.06, y: mouthY + s * 0.025))
            path.addQuadCurve(to: CGPoint(x: c.x, y: mouthY + s * 0.025), control: CGPoint(x: c.x - s * 0.03, y: mouthY))
            path.addQuadCurve(to: CGPoint(x: c.x + s * 0.06, y: mouthY + s * 0.025), control: CGPoint(x: c.x + s * 0.03, y: mouthY + s * 0.05))
            ctx.stroke(path, with: .color(EyeStyle.ink), style: StrokeStyle(lineWidth: s * 0.022, lineCap: .round))
        }
    }

    /// 叶片变换：先随弯下的茎平移 [offset]，再绕 [pivot] 旋转并缩放。
    private static func leaf(_ ctx: GraphicsContext, pivot: CGPoint, degrees: Double, scale: CGFloat, offset: CGSize,
                             _ draw: (GraphicsContext) -> Void) {
        var layer = ctx
        layer.translateBy(x: offset.width, y: offset.height)
        let p = CGPoint(x: pivot.x - offset.width, y: pivot.y - offset.height)
        layer.translateBy(x: p.x, y: p.y); layer.rotate(by: .degrees(degrees)); layer.scaleBy(x: scale, y: scale); layer.translateBy(x: -p.x, y: -p.y)
        draw(layer)
    }

    /// 四角闪光（小瞳开心时的点缀），[size] 为半径。
    static func sparkle(_ ctx: GraphicsContext, color: Color, center c: CGPoint, size r: CGFloat, alpha: Double = 1) {
        let w = r * 0.28
        var path = Path(); path.move(to: CGPoint(x: c.x, y: c.y - r))
        path.addQuadCurve(to: CGPoint(x: c.x + r, y: c.y), control: CGPoint(x: c.x + w * 0.3, y: c.y - w * 0.3))
        path.addQuadCurve(to: CGPoint(x: c.x, y: c.y + r), control: CGPoint(x: c.x + w * 0.3, y: c.y + w * 0.3))
        path.addQuadCurve(to: CGPoint(x: c.x - r, y: c.y), control: CGPoint(x: c.x - w * 0.3, y: c.y + w * 0.3))
        path.addQuadCurve(to: CGPoint(x: c.x, y: c.y - r), control: CGPoint(x: c.x - w * 0.3, y: c.y - w * 0.3))
        path.closeSubpath()
        ctx.fill(path, with: .color(color.opacity(min(1, max(0, alpha)))))
    }

    /// 应用图标（与桌面图标同一构图，见 scripts/generate_app_icon.py）：浅薄荷绿圆角底上的彩色小瞳。
    static func appIcon(_ ctx: GraphicsContext, origin: CGPoint, side: CGFloat) {
        let rect = CGRect(origin: origin, size: CGSize(width: side, height: side))
        ctx.fill(RoundedRectangle(cornerRadius: side * 0.2237, style: .continuous).path(in: rect), with: .color(EyeStyle.primarySoft))
        mascot(ctx, center: CGPoint(x: origin.x + side * 0.5, y: origin.y + side * 0.5375), size: side * 0.675)
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

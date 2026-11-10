import SwiftUI

/// 示意图绘制原语：逐项移植安卓 core:ui 的 EyeFigure.kt（drawFigure / drawFigureArm）与 EyeBrand.kt（drawPhone /
/// drawFixationTarget / drawBrandMark / drawTumblingE），测试演示、注视目标与标志共用，保证两端一致。
/// 2026-11-10 起：演示人物是与卡片同一种材质的「柔和浮雕人偶」——中性色的头、手、身体靠成对阴影浮起，
/// 眼睛是眼窝里一颗蓝 → 青 → 水绿渐变、带荧光的虹膜；手是人物自己的（肩 → 肘 → 腕两段袖子接手掌）。
enum Cartoon {
    /// 在 [pivot] 处旋转后绘制。
    static func rotated(_ ctx: GraphicsContext, _ degrees: Double, around pivot: CGPoint, _ draw: (GraphicsContext) -> Void) {
        var layer = ctx
        layer.translateBy(x: pivot.x, y: pivot.y); layer.rotate(by: .degrees(degrees)); layer.translateBy(x: -pivot.x, y: -pivot.y)
        draw(layer)
    }

    static func oval(_ origin: CGPoint, _ size: CGSize) -> Path { Path(ellipseIn: CGRect(origin: origin, size: size)) }
    static func dot(_ center: CGPoint, _ r: CGFloat) -> Path { Path(ellipseIn: CGRect(x: center.x - r, y: center.y - r, width: r * 2, height: r * 2)) }
    static func segment(_ a: CGPoint, _ b: CGPoint) -> Path { var path = Path(); path.move(to: a); path.addLine(to: b); return path }

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

    /// 柔和浮雕（DESIGN.md 的成对阴影，安卓 softRelief）：在本体下面先画右下暗影、左上高光两份模糊副本，
    /// 清晰的本体由调用方随后再画。[depth] 为阴影偏移，不足半个点时不画；[draw] 用给定填色把形状画一遍。
    static func softRelief(_ ctx: GraphicsContext, depth: CGFloat, _ draw: (GraphicsContext, GraphicsContext.Shading) -> Void) {
        guard depth >= 0.5 else { return }
        let passes: [(Color, CGFloat)] = [(EyeStyle.shadow, depth), (EyeStyle.shadowLight, -depth * 0.8)]
        for (tint, shift) in passes {
            var layer = ctx
            layer.addFilter(.blur(radius: depth * 0.9))
            layer.translateBy(x: shift, y: shift)
            draw(layer, .color(tint))
        }
    }

    /// 上衣与袖子共用的渐变：袖子叠在身体上时颜色连续，只靠浮雕阴影分出前后。
    static func garmentShading(_ c: CGPoint, _ s: CGFloat) -> GraphicsContext.Shading {
        .linearGradient(Gradient(colors: [EyeStyle.Illustration.garmentLight, EyeStyle.Illustration.garment]),
                        startPoint: CGPoint(x: c.x - s * 0.62, y: c.y + s * 0.5), endPoint: CGPoint(x: c.x + s * 0.4, y: c.y + s * 1.4))
    }

    /// 示意人物某只眼的中心（side = −1 画面左侧，+1 画面右侧）。
    static func figureEyeCenter(_ center: CGPoint, _ size: CGFloat, _ side: CGFloat) -> CGPoint {
        CGPoint(x: center.x + side * size * 0.19, y: center.y - size * 0.02)
    }

    /// 示意人物：上半身浮雕人偶（安卓 drawFigure）。[size] 为头宽；[gaze] −1…1 虹膜在眼窝里平移；
    /// [lid] 0 睁开…1 闭合（<0.2 睁开度画闭眼弧线）；[wide] >1 睁大、眉毛上扬；[bottom] 上衣下沿 y（缺省头中心下 0.8 倍头宽）；
    /// [relief] 是否画浮雕阴影与虹膜荧光（深色底上的小图标传 false）。
    static func figure(_ ctx: GraphicsContext, center c: CGPoint, size s: CGFloat, gaze: CGPoint = .zero,
                       lid: CGFloat = 0, wide: CGFloat = 1, bottom: CGFloat? = nil, relief: Bool = true) {
        typealias I = EyeStyle.Illustration
        let depth: CGFloat = relief ? s * 0.035 : 0
        func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: c.x + x * s, y: c.y + y * s) }
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
        softRelief(ctx, depth: depth) { layer, tint in layer.fill(shirt, with: tint) }
        ctx.fill(shirt, with: garmentShading(c, s))
        // 脖子 + 圆领
        ctx.fill(Path(CGRect(origin: p(-0.1, 0.28), size: CGSize(width: s * 0.2, height: s * 0.26))), with: .color(I.clayShade))
        var collar = Path()
        collar.move(to: CGPoint(x: c.x - s * 0.16, y: y0))
        collar.addQuadCurve(to: CGPoint(x: c.x + s * 0.16, y: y0), control: CGPoint(x: c.x, y: y0 + s * 0.15))
        ctx.fill(collar, with: .color(I.clayShade))
        ctx.stroke(collar, with: .color(I.garmentLight), style: StrokeStyle(lineWidth: s * 0.035, lineCap: .round))
        // 耳朵（在脸后面）
        for side: CGFloat in [-1, 1] {
            ctx.fill(dot(p(side * 0.4, 0.04), s * 0.08), with: .color(I.clay))
            ctx.fill(dot(p(side * 0.405, 0.04), s * 0.035), with: .color(I.clayShade))
        }
        // 脸：左上受光的径向渐变
        let face = oval(p(-0.4, -0.43), CGSize(width: s * 0.8, height: s * 0.86))
        softRelief(ctx, depth: depth) { layer, tint in layer.fill(face, with: tint) }
        ctx.fill(face, with: .radialGradient(Gradient(colors: [I.clayLight, I.clay]), center: p(-0.14, -0.18), startRadius: 0, endRadius: s * 0.72))
        // 侧分短发：盖住头顶，刘海从左向右扫开，露出额头
        var hair = Path()
        hair.move(to: p(-0.43, 0.04))
        hair.addCurve(to: p(0.04, -0.54), control1: p(-0.47, -0.36), control2: p(-0.22, -0.56))
        hair.addCurve(to: p(0.43, 0.04), control1: p(0.32, -0.53), control2: p(0.5, -0.36))
        hair.addCurve(to: p(0.24, -0.25), control1: p(0.4, -0.1), control2: p(0.34, -0.2))
        hair.addCurve(to: p(-0.3, -0.23), control1: p(0.1, -0.17), control2: p(-0.12, -0.15))
        hair.addCurve(to: p(-0.43, 0.04), control1: p(-0.37, -0.15), control2: p(-0.4, -0.06))
        hair.closeSubpath()
        ctx.fill(hair, with: .linearGradient(Gradient(colors: [I.hairLight, I.hair]), startPoint: p(-0.3, -0.56), endPoint: p(0.3, 0)))
        // 眉毛 + 眼睛（眼窝 + 渐变虹膜）
        let open = min(1, max(0, 1 - lid))
        for side: CGFloat in [-1, 1] {
            let base = figureEyeCenter(c, s, side)
            let browY = base.y - s * 0.135 - (wide - 1) * s * 0.08
            var brow = Path()
            brow.move(to: CGPoint(x: base.x - s * 0.06, y: browY + s * 0.01))
            brow.addQuadCurve(to: CGPoint(x: base.x + s * 0.06, y: browY + s * 0.01), control: CGPoint(x: base.x, y: browY - s * 0.025))
            ctx.stroke(brow, with: .color(I.hair), style: StrokeStyle(lineWidth: s * 0.024, lineCap: .round))
            if open < 0.2 {
                var closed = Path(); closed.move(to: CGPoint(x: base.x - s * 0.07, y: base.y))
                closed.addQuadCurve(to: CGPoint(x: base.x + s * 0.07, y: base.y), control: CGPoint(x: base.x, y: base.y + s * 0.05))
                ctx.stroke(closed, with: .color(I.lineStrong), style: StrokeStyle(lineWidth: s * 0.024, lineCap: .round))
                continue
            }
            let rx = s * 0.08, ry = s * 0.074 * wide * open
            let well = Path(ellipseIn: CGRect(x: base.x - rx, y: base.y - ry, width: rx * 2, height: ry * 2))
            ctx.fill(well, with: .color(I.eyeWell))
            let iris = CGPoint(x: base.x + gaze.x * s * 0.028, y: base.y + gaze.y * s * 0.022)
            let ir = s * 0.05
            var eye = ctx
            eye.clip(to: well)
            if relief {
                var glow = eye
                glow.addFilter(.blur(radius: ir * 0.35))
                glow.fill(dot(iris, ir * 1.25), with: .color(EyeStyle.glow))
            }
            eye.fill(dot(iris, ir), with: .linearGradient(Gradient(colors: [EyeStyle.brandStart, EyeStyle.dataCyan, EyeStyle.dataAqua]),
                startPoint: CGPoint(x: iris.x, y: iris.y - ir), endPoint: CGPoint(x: iris.x, y: iris.y + ir)))
            eye.fill(dot(iris, ir * 0.44), with: .color(EyeStyle.ink))
            eye.fill(dot(CGPoint(x: iris.x + ir * 0.36, y: iris.y - ir * 0.4), ir * 0.24), with: .color(.white))
            // 上眼睑：沿眼窝上沿的一道深色弧线（198° 起扫 144°，经过正上方），眼睛在浅色脸上也立得住
            var upperLid = Path()
            for i in 0...12 {
                let angle = (198 + 144 * Double(i) / 12) * Double.pi / 180
                let point = CGPoint(x: base.x + rx * CGFloat(cos(angle)), y: base.y + ry * CGFloat(sin(angle)))
                if i == 0 { upperLid.move(to: point) } else { upperLid.addLine(to: point) }
            }
            ctx.stroke(upperLid, with: .color(I.lineStrong), style: StrokeStyle(lineWidth: s * 0.02, lineCap: .round, lineJoin: .round))
        }
        // 鼻子、微笑
        var nose = Path(); nose.move(to: p(-0.022, 0.085)); nose.addQuadCurve(to: p(0.022, 0.085), control: p(0, 0.105))
        ctx.stroke(nose, with: .color(I.line), style: StrokeStyle(lineWidth: s * 0.018, lineCap: .round))
        var mouth = Path(); mouth.move(to: p(-0.06, 0.2)); mouth.addQuadCurve(to: p(0.06, 0.2), control: p(0, 0.25))
        ctx.stroke(mouth, with: .color(I.lineStrong), style: StrokeStyle(lineWidth: s * 0.022, lineCap: .round))
    }

    // 手臂骨骼（以头宽为单位）：上臂、前臂、手腕到掌心
    static let armUpper: CGFloat = 0.66
    static let armFore: CGFloat = 0.58
    static let armHand: CGFloat = 0.22

    /// 手臂垂在身侧时手腕的位置（相对头部中心、以头宽为单位；side = −1 人物右臂，在画面左侧）。
    static func armRest(_ side: CGFloat) -> CGPoint { CGPoint(x: side * 0.56, y: 1.86) }

    /// 手掌盖住某只眼时手腕的位置：同侧的手直接抬起，另一侧的手越过鼻梁伸过去。
    static func armCover(_ side: CGFloat, eye: CGFloat) -> CGPoint {
        side == eye ? CGPoint(x: eye * 0.24, y: 0.22) : CGPoint(x: eye * 0.11, y: 0.21)
    }

    /// 示意人物自己的手臂（袖子 + 手，安卓 drawFigureArm）：肩固定在身体上，上臂、前臂长度不变；先在三维里由手腕位置
    /// 反解出肘，再正投影到画面——手抬到脸前时肘自然收在胸前，放下时垂在身侧，手始终连着身体。手的方向顺着前臂。
    /// [side] −1 人物右臂（画面左侧）；[wrist] 手腕位置（相对头部中心、以头宽为单位）；[depth] 手腕离身体平面的前伸量。
    static func arm(_ ctx: GraphicsContext, center c: CGPoint, size s: CGFloat, side: CGFloat, wrist: CGPoint, depth: CGFloat,
                    relief: Bool = true) {
        // 肩 → 腕的三维向量；够不着时把手腕拉回到手臂伸直的位置
        let sx = side * 0.5, sy: CGFloat = 0.66
        var dx = wrist.x - sx, dy = wrist.y - sy, dz = depth
        var reach = max(0.001, (dx * dx + dy * dy + dz * dz).squareRoot())
        let longest = (armUpper + armFore) * 0.999
        if reach > longest {
            let k = longest / reach
            dx *= k; dy *= k; dz *= k; reach = longest
        }
        let along = (armUpper * armUpper - armFore * armFore + reach * reach) / (2 * reach)
        let bend = max(0, armUpper * armUpper - along * along).squareRoot()
        let nx = dx / reach, ny = dy / reach, nz = dz / reach
        // 肘偏向「下、略向外、略向前」：去掉沿肩腕连线的分量后归一
        let px = side * 0.25, py: CGFloat = 1, pz: CGFloat = 0.3
        let along2 = px * nx + py * ny + pz * nz
        var mx = px - along2 * nx, my = py - along2 * ny
        let mz = pz - along2 * nz
        let ml = (mx * mx + my * my + mz * mz).squareRoot()
        if ml > 0.0001 { mx /= ml; my /= ml } else { mx = side; my = 0 }
        func at(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: c.x + x * s, y: c.y + y * s) }
        let shoulder = at(sx, sy)
        let elbow = at(sx + nx * along + mx * bend, sy + ny * along + my * bend)
        let wristAt = at(sx + dx, sy + dy)
        let foreLen = ((wristAt.x - elbow.x) * (wristAt.x - elbow.x) + (wristAt.y - elbow.y) * (wristAt.y - elbow.y)).squareRoot()
        let dir = foreLen > s * 0.001 ? CGPoint(x: (wristAt.x - elbow.x) / foreLen, y: (wristAt.y - elbow.y) / foreLen) : CGPoint(x: 0, y: -1)
        // 袖子：肩 → 肘 → 腕，圆头圆角
        let sleeveWidth = s * 0.25
        var sleeve = Path(); sleeve.move(to: shoulder); sleeve.addLine(to: elbow); sleeve.addLine(to: wristAt)
        let sleeveStyle = StrokeStyle(lineWidth: sleeveWidth, lineCap: .round, lineJoin: .round)
        softRelief(ctx, depth: relief ? s * 0.03 : 0) { layer, tint in layer.stroke(sleeve, with: tint, style: sleeveStyle) }
        ctx.stroke(sleeve, with: garmentShading(c, s), style: sleeveStyle)
        // 袖口：一圈浅色滚边
        ctx.stroke(segment(CGPoint(x: wristAt.x - dir.x * s * 0.05, y: wristAt.y - dir.y * s * 0.05), wristAt),
                   with: .color(EyeStyle.Illustration.garmentLight), style: StrokeStyle(lineWidth: sleeveWidth, lineCap: .butt))
        hand(ctx, center: CGPoint(x: wristAt.x + dir.x * s * armHand, y: wristAt.y + dir.y * s * armHand), size: s * 0.44,
             up: dir, thumbSide: -side, relief: relief)
    }

    /// 示意手（手背朝外，安卓 drawHand）：掌 + 四指 + 拇指，都由圆头粗线拼成，带浅描边与指缝。
    /// [up] 指尖方向（单位向量）；[thumbSide] 指尖朝上时拇指在哪一侧（−1 画面左，+1 画面右）。
    static func hand(_ ctx: GraphicsContext, center c: CGPoint, size: CGFloat, up: CGPoint, thumbSide: CGFloat, relief: Bool) {
        typealias I = EyeStyle.Illustration
        let right = CGPoint(x: -up.y, y: up.x)
        // 手的局部坐标：x 沿 right，y 指向手腕，单位为 size
        func h(_ x: CGFloat, _ y: CGFloat) -> CGPoint {
            CGPoint(x: c.x + right.x * x * size - up.x * y * size, y: c.y + right.y * x * size - up.y * y * size)
        }
        softRelief(ctx, depth: relief ? size * 0.07 : 0) { layer, tint in
            layer.stroke(segment(h(0, -0.14), h(0, 0.14)), with: tint, style: StrokeStyle(lineWidth: size * 0.74, lineCap: .round))
        }
        // 拇指、掌、四指：先整体画一遍略粗的描边色，再画本色，重叠处的描边自然消失
        let parts: [(CGPoint, CGPoint, CGFloat)] = [
            (h(thumbSide * 0.33, 0.24), h(thumbSide * 0.47, 0.02), 0.2),
            (h(-0.19, 0.25), h(0.19, 0.25), 0.42),
            (h(-0.29, -0.26), h(-0.29, 0.12), 0.185),
            (h(-0.097, -0.385), h(-0.097, 0.12), 0.185),
            (h(0.097, -0.345), h(0.097, 0.12), 0.185),
            (h(0.29, -0.2), h(0.29, 0.12), 0.185),
        ]
        let edge = size * 0.035
        for (a, b, w) in parts {
            ctx.stroke(segment(a, b), with: .color(I.line), style: StrokeStyle(lineWidth: w * size + edge * 2, lineCap: .round))
        }
        let fill = GraphicsContext.Shading.radialGradient(Gradient(colors: [I.clayLight, I.clay]), center: h(-0.15, -0.2),
                                                           startRadius: 0, endRadius: size * 0.9)
        for (a, b, w) in parts { ctx.stroke(segment(a, b), with: fill, style: StrokeStyle(lineWidth: w * size, lineCap: .round)) }
        // 指缝
        for x: CGFloat in [-0.193, 0, 0.193] {
            ctx.stroke(segment(h(x, -0.16), h(x, 0.1)), with: .color(I.line), style: StrokeStyle(lineWidth: size * 0.03, lineCap: .round))
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

    /// 示意手机：返回屏幕区域左上角与尺寸。机身带一层柔和投影；屏幕恒为白底，与正式视标的黑白对比一致。
    @discardableResult
    static func phone(_ ctx: GraphicsContext, center c: CGPoint, height: CGFloat) -> (CGPoint, CGSize) {
        let w = height * 0.5
        let tl = CGPoint(x: c.x - w / 2, y: c.y - height / 2)
        let body = Path(roundedRect: CGRect(origin: tl, size: CGSize(width: w, height: height)), cornerRadius: w * 0.18)
        softRelief(ctx, depth: w * 0.06) { layer, tint in layer.fill(body, with: tint) }
        ctx.fill(body, with: .color(EyeStyle.Illustration.device))
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

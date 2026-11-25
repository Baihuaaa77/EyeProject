package com.zkyjss.eyeproject.feature.home

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.zkyjss.eyeproject.core.designsystem.theme.EyeBrown
import com.zkyjss.eyeproject.core.designsystem.theme.EyeColors
import com.zkyjss.eyeproject.core.domain.EyeExerciseCoach
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 护眼操互动小游戏「暖夜寻星」的纯矢量场景绘制与情景文案。
 * 所有配色全部取自 [EyeColors] / [EyeBrown] 令牌，不使用 Emoji 或写死颜色。
 */
internal object StarOwlCopy {
    const val INTRO_TITLE = "暖夜寻星"
    const val INTRO_HEADLINE = "帮小猫头鹰寻回 8 颗星"
    const val INTRO_BODY = "头保持不动，只转眼睛看发光的星星；集齐星图后一起闭眼小憩。"
    const val START_BUTTON = "出发寻星"

    fun moveTitle(move: EyeExerciseCoach.Move, round: Int): String = when (move) {
        EyeExerciseCoach.Move.LEFT -> if (round == 0) "向左看 · 寻西边星" else "向左看 · 续亮西星"
        EyeExerciseCoach.Move.RIGHT -> if (round == 0) "向右看 · 寻东边星" else "向右看 · 续亮东星"
        EyeExerciseCoach.Move.UP -> if (round == 0) "向上看 · 寻北极星" else "向上看 · 续亮北星"
        EyeExerciseCoach.Move.DOWN -> if (round == 0) "向下看 · 寻山谷星" else "向下看 · 续亮南星"
    }

    fun caughtFeedback(move: EyeExerciseCoach.Move?, streak: Int): String {
        if (streak >= 3) return "连亮 $streak 颗星，目光真灵巧！"
        return when (move) {
            EyeExerciseCoach.Move.LEFT -> "收下西边的星光啦"
            EyeExerciseCoach.Move.RIGHT -> "收下东边的星光啦"
            EyeExerciseCoach.Move.UP -> "摘下高空的北极星啦"
            EyeExerciseCoach.Move.DOWN -> "捡起山谷里的星光啦"
            null -> "星光已收入星盘"
        }
    }

    fun rankTitle(total: Int, seen: Int, usedCamera: Boolean): String = when {
        !usedCamera -> "星夜漫步者"
        total > 0 && seen >= total -> "观星大师"
        seen >= (total * 3) / 4 -> "星夜向导"
        seen >= total / 2 -> "追星旅人"
        else -> "悠闲赏星人"
    }

    fun rankSummary(total: Int, seen: Int, bestStreak: Int, usedCamera: Boolean): String = when {
        !usedCamera -> "跟着星光活动了一整圈，眼睛轻松多了"
        total > 0 && seen >= total -> "8 颗星全部亲眼点亮，眼部活动非常充分"
        bestStreak >= 3 -> "最高连亮 $bestStreak 颗星，眼肌舒展得很顺畅"
        else -> "眼睛已经跟着星光热身放松啦"
    }
}

/** 固定排布的背景微星坐标 (归一化 x, y, 半径系数)，保持宁静不刺眼。 */
private val BG_STARS = listOf(
    Triple(0.11f, 0.10f, 1.4f), Triple(0.24f, 0.07f, 1.0f), Triple(0.38f, 0.13f, 1.2f),
    Triple(0.66f, 0.08f, 1.3f), Triple(0.84f, 0.12f, 1.5f), Triple(0.91f, 0.24f, 1.0f),
    Triple(0.08f, 0.30f, 1.1f), Triple(0.19f, 0.44f, 0.9f), Triple(0.87f, 0.42f, 1.2f),
    Triple(0.14f, 0.68f, 1.1f), Triple(0.81f, 0.65f, 1.0f), Triple(0.29f, 0.76f, 1.3f),
    Triple(0.72f, 0.74f, 1.2f), Triple(0.51f, 0.05f, 1.0f),
)

/** 绘制暖夜山丘星空背景（含安静的微星与柔和远山剪影）。 */
internal fun DrawScope.drawStarryNightBackdrop(
    colors: EyeColors,
    twinkle: Float,
    resting: Boolean,
) {
    val w = size.width
    val h = size.height
    // 顶部深咖啡夜空向下方暖棕山谷过渡
    drawRect(
        brush = Brush.verticalGradient(
            0f to colors.appBackground,
            0.72f to colors.appBackground,
            1f to colors.panelBackground.copy(alpha = if (resting) 0.45f else 0.72f),
        ),
    )
    // 中央柔和的暖夜星云微光
    val glowAlpha = if (resting) 0.08f else 0.16f
    drawCircle(
        brush = Brush.radialGradient(
            0f to colors.glow.copy(alpha = glowAlpha),
            0.65f to colors.panelBackground.copy(alpha = glowAlpha * 0.4f),
            1f to Color.Transparent,
            center = Offset(w * 0.5f, h * 0.48f),
            radius = min(w, h) * 0.52f,
        ),
        radius = min(w, h) * 0.52f,
        center = Offset(w * 0.5f, h * 0.48f),
    )
    // 静态微星（仅极轻微呼吸，避免令人分心）
    BG_STARS.forEachIndexed { idx, (nx, ny, rScale) ->
        val phase = ((idx * 0.37f) + twinkle) * 2f * PI.toFloat()
        val a = (if (resting) 0.18f else 0.32f) + 0.14f * ((sin(phase) + 1f) * 0.5f)
        drawCircle(
            color = if (idx % 3 == 0) colors.moodGold.copy(alpha = a) else colors.cardBorder.copy(alpha = a),
            radius = rScale * (min(w, h) * 0.0042f).coerceIn(1.5f, 3.6f),
            center = Offset(w * nx, h * ny),
        )
    }
    // 底部双层柔和远山剪影
    val backHill = Path().apply {
        moveTo(0f, h)
        lineTo(0f, h * 0.87f)
        quadraticTo(w * 0.28f, h * 0.78f, w * 0.56f, h * 0.86f)
        quadraticTo(w * 0.80f, h * 0.92f, w, h * 0.83f)
        lineTo(w, h)
        close()
    }
    drawPath(backHill, colors.panelBackground.copy(alpha = if (resting) 0.42f else 0.65f))
    val frontHill = Path().apply {
        moveTo(0f, h)
        lineTo(0f, h * 0.92f)
        quadraticTo(w * 0.32f, h * 0.86f, w * 0.64f, h * 0.93f)
        quadraticTo(w * 0.85f, h * 0.97f, w, h * 0.90f)
        lineTo(w, h)
        close()
    }
    drawPath(frontHill, colors.surfaceVariant.copy(alpha = if (resting) 0.35f else 0.52f))
}

/**
 * 绘制中央的八星星盘：显示已点亮的星座进度与当前校准/收集光环。
 */
internal fun DrawScope.drawConstellationRing(
    colors: EyeColors,
    center: Offset,
    radius: Float,
    totalSteps: Int,
    stepResults: List<Boolean>,
    usedCamera: Boolean,
    calibrateProgress: Float,
    showCalibrateRing: Boolean,
    resting: Boolean,
    restPulse: Float,
) {
    val count = totalSteps.coerceAtLeast(1)
    // 底环虚线星轨
    drawCircle(
        color = colors.borderSubtle.copy(alpha = if (resting) 0.25f else 0.48f),
        radius = radius,
        center = center,
        style = Stroke(
            width = (radius * 0.022f).coerceAtLeast(1.5f),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(radius * 0.08f, radius * 0.07f)),
        ),
    )
    // 校准阶段：暖橙充能弧线
    if (showCalibrateRing) {
        drawArc(
            color = colors.primary,
            startAngle = -90f,
            sweepAngle = 360f * calibrateProgress.coerceIn(0f, 1f),
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(width = (radius * 0.055f).coerceAtLeast(3.5f), cap = StrokeCap.Round),
        )
    }
    // 闭眼休息阶段：随倒计时轻柔扩散的呼吸波纹
    if (resting) {
        val rippleR = radius * (1.0f + restPulse * 0.42f)
        drawCircle(
            color = colors.moodGold.copy(alpha = (1f - restPulse).coerceIn(0f, 1f) * 0.38f),
            radius = rippleR,
            center = center,
            style = Stroke(width = (radius * 0.032f).coerceAtLeast(2f)),
        )
    }
    // 连结已点亮星位的星座细线
    val positions = List(count) { i ->
        val angle = -PI / 2.0 + (2.0 * PI * i / count)
        Offset(
            center.x + (radius * cos(angle)).toFloat(),
            center.y + (radius * sin(angle)).toFloat(),
        )
    }
    for (i in 0 until stepResults.size.coerceAtMost(count) - 1) {
        val litA = stepResults[i] || !usedCamera
        val litB = stepResults[i + 1] || !usedCamera
        if (litA && litB) {
            drawLine(
                color = colors.cardBorder.copy(alpha = if (resting) 0.28f else 0.45f),
                start = positions[i],
                end = positions[i + 1],
                strokeWidth = (radius * 0.02f).coerceAtLeast(1.5f),
                cap = StrokeCap.Round,
            )
        }
    }
    // 绘制每颗星位
    positions.forEachIndexed { idx, pt ->
        val done = idx < stepResults.size
        val caught = done && stepResults[idx]
        val guidedLit = done && !usedCamera
        when {
            caught -> {
                drawCircle(colors.primary.copy(alpha = if (resting) 0.14f else 0.28f), radius * 0.13f, pt)
                drawFourPointStar(pt, radius * 0.105f, colors.moodGold.copy(alpha = if (resting) 0.6f else 1f))
            }
            guidedLit -> {
                drawCircle(colors.cardBorder.copy(alpha = if (resting) 0.12f else 0.22f), radius * 0.11f, pt)
                drawFourPointStar(pt, radius * 0.09f, colors.moodWarm.copy(alpha = if (resting) 0.55f else 0.92f))
            }
            done -> {
                // 相机模式下超时未捕获的星：保留淡轮廓
                drawCircle(
                    color = colors.borderSubtle.copy(alpha = 0.7f),
                    radius = radius * 0.055f,
                    center = pt,
                    style = Stroke(width = (radius * 0.02f).coerceAtLeast(1.5f)),
                )
            }
            else -> {
                drawCircle(colors.borderSubtle.copy(alpha = 0.5f), radius * 0.036f, pt)
            }
        }
    }
}

/**
 * 绘制主角「观星小猫头鹰」：纯矢量几何暖棕风格，双眼瞳孔可实时跟随视线 [gazeX]/[gazeY] 转动。
 */
internal fun DrawScope.drawStarOwl(
    colors: EyeColors,
    center: Offset,
    size: Float,
    gazeX: Float = 0f,
    gazeY: Float = 0f,
    sleeping: Boolean = false,
    happy: Boolean = false,
    excited: Boolean = false,
) {
    val r = size * 0.5f
    // 脚下暖光托底
    drawOval(
        color = colors.panelBackground.copy(alpha = 0.85f),
        topLeft = Offset(center.x - r * 0.62f, center.y + r * 0.64f),
        size = Size(r * 1.24f, r * 0.22f),
    )
    // 小翅膀（开心或庆祝时微微张开）
    val wingSpread = if (happy) 0.24f else if (excited) 0.12f else 0f
    for (side in listOf(-1f, 1f)) {
        val wing = Path().apply {
            val topX = center.x + side * r * 0.62f
            val topY = center.y - r * 0.06f
            moveTo(topX, topY)
            cubicTo(
                center.x + side * r * (0.98f + wingSpread), center.y + r * (0.04f - wingSpread * 0.7f),
                center.x + side * r * (0.94f + wingSpread * 0.7f), center.y + r * 0.50f,
                center.x + side * r * 0.52f, center.y + r * 0.56f,
            )
            close()
        }
        drawPath(wing, colors.surfaceVariant)
        drawPath(wing, colors.cardBorder.copy(alpha = 0.55f), style = Stroke(r * 0.045f, join = StrokeJoin.Round))
    }
    // 身体（圆润的暖棕小蛋形）
    drawOval(
        color = colors.panelBackground,
        topLeft = Offset(center.x - r * 0.72f, center.y - r * 0.68f),
        size = Size(r * 1.44f, r * 1.40f),
    )
    drawOval(
        color = colors.cardBorder.copy(alpha = 0.72f),
        topLeft = Offset(center.x - r * 0.72f, center.y - r * 0.68f),
        size = Size(r * 1.44f, r * 1.40f),
        style = Stroke(r * 0.045f),
    )
    // 两只俏皮耳羽
    for (side in listOf(-1f, 1f)) {
        val ear = Path().apply {
            moveTo(center.x + side * r * 0.26f, center.y - r * 0.56f)
            lineTo(center.x + side * r * 0.62f, center.y - r * 0.88f)
            lineTo(center.x + side * r * 0.60f, center.y - r * 0.34f)
            close()
        }
        drawPath(ear, colors.surfaceVariant)
        drawPath(ear, colors.cardBorder.copy(alpha = 0.72f), style = Stroke(r * 0.04f, join = StrokeJoin.Round))
    }
    // 胸前浅暖色肚腹 + 小羽纹
    drawOval(
        color = colors.surfaceVariant.copy(alpha = 0.9f),
        topLeft = Offset(center.x - r * 0.44f, center.y + r * 0.04f),
        size = Size(r * 0.88f, r * 0.60f),
    )
    for (row in 0..1) {
        val y = center.y + r * (0.28f + row * 0.16f)
        for (col in -1..1) {
            if (row == 1 && col != 0) continue
            val x = center.x + col * r * 0.22f
            drawArc(
                color = colors.cardBorder.copy(alpha = 0.42f),
                startAngle = 20f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(x - r * 0.09f, y - r * 0.06f),
                size = Size(r * 0.18f, r * 0.12f),
                style = Stroke(r * 0.035f, cap = StrokeCap.Round),
            )
        }
    }
    // 眉心小星标
    drawFourPointStar(
        center = Offset(center.x, center.y - r * 0.44f),
        radius = r * 0.09f,
        color = colors.moodGold.copy(alpha = if (sleeping) 0.45f else 0.9f),
    )
    // 两只大眼睛（带眼周浅框与实时转动的瞳孔）
    val eyeR = r * 0.27f
    val eyeY = center.y - r * 0.10f
    for (side in listOf(-1f, 1f)) {
        val eyeC = Offset(center.x + side * r * 0.30f, eyeY)
        // 外圈绒毛框
        drawCircle(colors.cardBorder.copy(alpha = 0.28f), eyeR * 1.18f, eyeC)
        drawCircle(colors.appBackground, eyeR, eyeC)
        drawCircle(colors.cardBorder.copy(alpha = 0.8f), eyeR, eyeC, style = Stroke(r * 0.036f))
        when {
            sleeping -> {
                // 安详闭上的弯弧睡眼
                val closed = Path().apply {
                    moveTo(eyeC.x - eyeR * 0.56f, eyeC.y + eyeR * 0.04f)
                    quadraticTo(eyeC.x, eyeC.y + eyeR * 0.46f, eyeC.x + eyeR * 0.56f, eyeC.y + eyeR * 0.04f)
                }
                drawPath(closed, colors.moodWarm, style = Stroke(r * 0.055f, cap = StrokeCap.Round))
            }
            happy -> {
                // 捕获成功时的开心弯弯笑眼
                val smile = Path().apply {
                    moveTo(eyeC.x - eyeR * 0.54f, eyeC.y + eyeR * 0.12f)
                    quadraticTo(eyeC.x, eyeC.y - eyeR * 0.44f, eyeC.x + eyeR * 0.54f, eyeC.y + eyeR * 0.12f)
                }
                drawPath(smile, colors.moodGold, style = Stroke(r * 0.06f, cap = StrokeCap.Round))
            }
            else -> {
                // 灵动睁眼：大琥珀虹膜 + 瞳孔随 gazeX / gazeY 移动
                drawCircle(colors.white.copy(alpha = 0.94f), eyeR * 0.86f, eyeC)
                val maxShift = eyeR * 0.34f
                val pupilC = Offset(
                    eyeC.x + gazeX.coerceIn(-1f, 1f) * maxShift,
                    eyeC.y + gazeY.coerceIn(-1f, 1f) * maxShift,
                )
                val irisR = eyeR * (if (excited) 0.58f else 0.52f)
                drawCircle(colors.primary, irisR, pupilC)
                drawCircle(EyeBrown.pupil, irisR * 0.62f, pupilC)
                // 瞳孔里的暖星高光
                drawCircle(colors.white, irisR * 0.24f, Offset(pupilC.x - irisR * 0.22f, pupilC.y - irisR * 0.22f))
                drawCircle(colors.moodGold, irisR * 0.11f, Offset(pupilC.x + irisR * 0.20f, pupilC.y + irisR * 0.18f))
            }
        }
    }
    // 橙色小菱形尖嘴
    val beak = Path().apply {
        moveTo(center.x, center.y - r * 0.02f)
        lineTo(center.x + r * 0.11f, center.y + r * 0.10f)
        lineTo(center.x, center.y + r * 0.22f)
        lineTo(center.x - r * 0.11f, center.y + r * 0.10f)
        close()
    }
    drawPath(beak, colors.primary)
    // 闭眼休息时在头顶右上绘制柔和弯月（留出耳羽呼吸空间）
    if (sleeping) {
        drawCrescentMoon(
            colors = colors,
            center = Offset(center.x + r * 0.94f, center.y - r * 0.96f),
            radius = r * 0.22f,
        )
    }
}

/**
 * 绘制当前方位的「迷路暖星」目标 + 眼动充能捕获环 + 中央星轨引导线。
 */
internal fun DrawScope.drawTargetStar(
    colors: EyeColors,
    origin: Offset,
    target: Offset,
    move: EyeExerciseCoach.Move,
    ringRadius: Float,
    starRadius: Float,
    holdProgress: Float,
    pulse: Float,
    guideAlpha: Float = 1f,
) {
    val active = holdProgress.coerceIn(0f, 1f)
    // 从星盘外缘到目标星的轻柔导向虚线，避免穿过星盘上已点亮的星星
    val dir = Offset(move.dx.toFloat(), move.dy.toFloat())
    val lineStart = Offset(origin.x + dir.x * (ringRadius + starRadius * 0.6f), origin.y + dir.y * (ringRadius + starRadius * 0.6f))
    val lineEnd = Offset(target.x - dir.x * starRadius * 1.65f, target.y - dir.y * starRadius * 1.65f)
    if ((lineEnd - lineStart).getDistance() > starRadius * 0.5f) {
        drawLine(
            color = (if (active > 0.25f) colors.primary else colors.cardBorder)
                .copy(alpha = (0.28f + active * 0.45f) * guideAlpha),
            start = lineStart,
            end = lineEnd,
            strokeWidth = (starRadius * 0.10f).coerceAtLeast(2f),
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(starRadius * 0.36f, starRadius * 0.30f)),
        )
        // 导向小箭头（在靠近星星一侧，提示视线方向）
        val tip = lineEnd
        val perp = Offset(-dir.y, dir.x)
        val wingBack = starRadius * 0.34f
        val wingSide = starRadius * 0.24f
        val arrow = Path().apply {
            moveTo(tip.x - dir.x * wingBack + perp.x * wingSide, tip.y - dir.y * wingBack + perp.y * wingSide)
            lineTo(tip.x, tip.y)
            lineTo(tip.x - dir.x * wingBack - perp.x * wingSide, tip.y - dir.y * wingBack - perp.y * wingSide)
        }
        drawPath(
            arrow,
            color = (if (active > 0.25f) colors.moodGold else colors.cardBorder)
                .copy(alpha = (0.55f + active * 0.4f) * guideAlpha),
            style = Stroke(width = (starRadius * 0.11f).coerceAtLeast(2.2f), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
    // 星星柔光晕
    val auraR = starRadius * (1.75f + pulse * 0.24f + active * 0.32f)
    drawCircle(
        brush = Brush.radialGradient(
            0f to colors.primary.copy(alpha = (0.36f + active * 0.28f) * guideAlpha),
            0.55f to colors.moodGold.copy(alpha = (0.14f + active * 0.14f) * guideAlpha),
            1f to Color.Transparent,
            center = target,
            radius = auraR,
        ),
        radius = auraR,
        center = target,
    )
    // 外圈捕获底环 + 实时充能进度弧
    val ringR = starRadius * 1.24f
    drawCircle(
        color = colors.cardBorder.copy(alpha = 0.28f * guideAlpha),
        radius = ringR,
        center = target,
        style = Stroke(width = (starRadius * 0.12f).coerceAtLeast(2.5f)),
    )
    if (active > 0.02f) {
        drawArc(
            color = colors.primary.copy(alpha = guideAlpha),
            startAngle = -90f,
            sweepAngle = 360f * active,
            useCenter = false,
            topLeft = Offset(target.x - ringR, target.y - ringR),
            size = Size(ringR * 2f, ringR * 2f),
            style = Stroke(width = (starRadius * 0.16f).coerceAtLeast(3.5f), cap = StrokeCap.Round),
        )
    }
    // 四芒主星（充能时略微放大并更明亮）
    val starScale = 1f + pulse * 0.07f + active * 0.14f
    drawFourPointStar(target, starRadius * starScale, colors.moodGold.copy(alpha = guideAlpha))
    drawCircle(colors.white.copy(alpha = 0.92f * guideAlpha), starRadius * 0.24f * starScale, target)
}

/** 绘制捕获后的「流星飞回星盘」与到达中央时的星尘绽放。 */
internal fun DrawScope.drawCapturedComet(
    colors: EyeColors,
    from: Offset,
    to: Offset,
    progress: Float,
    starRadius: Float,
) {
    val t = progress.coerceIn(0f, 1f)
    // 0..0.68：流星沿微弯弧线飞回中心；0.68..1：在中心绽放 8 颗柔光星尘
    if (t < 0.72f) {
        val u = (t / 0.72f).coerceIn(0f, 1f)
        val eased = 1f - (1f - u) * (1f - u)
        val mid = Offset((from.x + to.x) * 0.5f, (from.y + to.y) * 0.5f - starRadius * 1.4f)
        val inv = 1f - eased
        val pos = Offset(
            inv * inv * from.x + 2f * inv * eased * mid.x + eased * eased * to.x,
            inv * inv * from.y + 2f * inv * eased * mid.y + eased * eased * to.y,
        )
        // 流星尾迹
        for (i in 1..4) {
            val tu = (eased - i * 0.06f).coerceAtLeast(0f)
            val ti = 1f - tu
            val tail = Offset(
                ti * ti * from.x + 2f * ti * tu * mid.x + tu * tu * to.x,
                ti * ti * from.y + 2f * ti * tu * mid.y + tu * tu * to.y,
            )
            drawCircle(
                color = colors.primary.copy(alpha = (0.55f - i * 0.11f).coerceAtLeast(0.08f)),
                radius = starRadius * (0.42f - i * 0.07f),
                center = tail,
            )
        }
        drawFourPointStar(pos, starRadius * 0.95f, colors.moodGold)
        drawCircle(colors.white, starRadius * 0.22f, pos)
    } else {
        val burst = ((t - 0.72f) / 0.28f).coerceIn(0f, 1f)
        val alpha = (1f - burst).coerceIn(0f, 1f)
        val spread = starRadius * (1.2f + burst * 2.4f)
        for (i in 0 until 8) {
            val a = i * (PI.toFloat() / 4f) + burst * 0.35f
            val pt = Offset(to.x + cos(a) * spread, to.y + sin(a) * spread)
            drawFourPointStar(
                pt,
                starRadius * (0.36f * (1f - burst * 0.4f)),
                (if (i % 2 == 0) colors.moodGold else colors.primary).copy(alpha = alpha),
            )
        }
    }
}

/** 通用四芒星矢量绘制。 */
internal fun DrawScope.drawFourPointStar(center: Offset, radius: Float, color: Color) {
    val inner = radius * 0.32f
    val path = Path().apply {
        moveTo(center.x, center.y - radius)
        quadraticTo(center.x + inner * 0.25f, center.y - inner * 0.25f, center.x + radius, center.y)
        quadraticTo(center.x + inner * 0.25f, center.y + inner * 0.25f, center.x, center.y + radius)
        quadraticTo(center.x - inner * 0.25f, center.y + inner * 0.25f, center.x - radius, center.y)
        quadraticTo(center.x - inner * 0.25f, center.y - inner * 0.25f, center.x, center.y - radius)
        close()
    }
    drawPath(path, color)
}

/** 柔和弯月绘制（闭眼休息阶段）。 */
private fun DrawScope.drawCrescentMoon(colors: EyeColors, center: Offset, radius: Float) {
    val moon = Path().apply {
        moveTo(center.x + radius * 0.2f, center.y - radius)
        cubicTo(
            center.x - radius * 0.95f, center.y - radius * 0.75f,
            center.x - radius * 0.95f, center.y + radius * 0.75f,
            center.x + radius * 0.2f, center.y + radius,
        )
        cubicTo(
            center.x - radius * 0.32f, center.y + radius * 0.55f,
            center.x - radius * 0.32f, center.y - radius * 0.55f,
            center.x + radius * 0.2f, center.y - radius,
        )
        close()
    }
    drawPath(moon, colors.moodGold.copy(alpha = 0.88f))
}

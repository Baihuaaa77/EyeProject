package com.zkyjss.eyeproject.feature.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.designsystem.theme.vivid
import com.zkyjss.eyeproject.core.domain.LiveEyeMonitor
import com.zkyjss.eyeproject.core.model.Point2f
import com.zkyjss.eyeproject.core.ui.component.*

/**
 * 首页最后一张卡「实时检测」：始终展开，整宽正方形取景框 + 「开始检测 / 停止检测」按钮。未检测时取景框是浅主色底 +
 * 人头形虚线框占位；检测中显示镜像预览（裁成正方形），下方是眨眼次数、近 1 分钟眨眼、观看距离与睁闭眼状态，
 * 「显示眼部关键点」开关（默认关闭、持久保存）决定是否叠加双眼关键点（眼睑 6 点 + 虹膜圈），只影响画面；
 * 再下方是最近 8 秒 EAR 曲线，每次眨眼画一条绿线。离开首页、退到后台后摘下预览、在后台继续检测
 * （通知栏常驻），直到点「停止检测」、点通知里的「停止」或从最近任务划掉应用；统计按段记入主动护眼账本。
 * 与苹果端 LiveEyeSection 同一排版（苹果端退到后台即停止）。[startRequests] 每增加一次（气泡里点了「去检测」）就打开一次。
 */
@Composable
internal fun LiveEyeSection(startRequests: Int = 0, viewModel: LiveEyeViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val preview = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val begin = {
        viewModel.start(owner, preview)
        // 后台检测靠常驻通知提示；Android 13 起需要通知权限（不给也照常检测，只是通知栏不显示）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok; denied = !ok; if (ok) begin()
    }
    val start = { if (granted) begin() else permission.launch(Manifest.permission.CAMERA) }
    LaunchedEffect(startRequests) { if (startRequests > 0) start() }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.attach(owner, preview)
                Lifecycle.Event.ON_PAUSE -> viewModel.detach()
                else -> Unit
            }
        }
        // 加入观察者时会立即补发到当前状态（已在前台且正在检测即接上预览）
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); viewModel.detach() }
    }
    LiveEyeContent(
        state = state,
        note = if (denied) "未获得相机权限，请在系统设置中允许后再开启" else state.error,
        onStart = start,
        onPause = viewModel::stop,
        onShowLandmarksChange = viewModel::setShowLandmarks,
    ) { AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize()) }
}

/** 无副作用的检测区内容，供界面检查复用（[preview] 为相机预览）。 */
@Composable
internal fun LiveEyeContent(
    state: LiveEyeUiState,
    note: String?,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onShowLandmarksChange: (Boolean) -> Unit = {},
    preview: @Composable () -> Unit,
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val haptics = rememberEyeHaptics()
    val s = state.snapshot
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 标题行：图标 + 标题 + 问号紧挨着，状态标签靠右
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            EyeGlyphIcon(EyeGlyph.CAMERA, Modifier.size(20.dp), EyeTileTone.PRIMARY.colors().second)
            Spacer(Modifier.width(8.dp))
            EyeText("实时检测", style = typography.subtitle, color = colors.textPrimary)
            EyeHelpButton("实时检测", LIVE_HELP)
            Spacer(Modifier.weight(1f))
            if (state.running) {
                val (label, tone) = status(state)
                EyeStatusChip(label, tone, live = tone == StatusTone.SUCCESS)
            }
        }
        Viewfinder(state.running, Modifier.fillMaxWidth().aspectRatio(1f)) {
            preview()
            if (state.showLandmarks) LandmarkOverlay(s, Modifier.matchParentSize())
        }
        if (state.running) {
            EyeMetricRow(items = listOf(
                { m -> EyeMetric("眨眼", "${s?.blinkCount ?: 0}", m, unit = "次") },
                { m -> EyeMetric("近 1 分钟", "${s?.blinksLastMinute ?: 0}", m, unit = "次") },
                { m -> EyeMetric("距离", s?.distanceCm?.let { "%.0f".format(it) } ?: "—", m, unit = "厘米") },
            ))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EyeStateChip(s)
                EyeText("EAR ${s?.ear?.let { "%.2f".format(it) } ?: "—"} · ${"%.0f".format(s?.fps ?: 0f)} 帧/秒",
                    style = typography.bodySecondary, color = colors.textSecondary)
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp)
                .toggleable(state.showLandmarks, role = Role.Switch) { on -> haptics.toggle(on); onShowLandmarksChange(on) },
                verticalAlignment = Alignment.CenterVertically) {
                EyeText(SHOW_LANDMARKS, Modifier.weight(1f), style = typography.body, color = colors.textSecondary)
                EyeSwitch(checked = state.showLandmarks, onCheckedChange = null)
            }
            EarTraceChart(s, Modifier.fillMaxWidth().height(116.dp))
        }
        EyeButton(if (state.running) "停止检测" else "开始检测", if (state.running) EyeButtonRole.SECONDARY else EyeButtonRole.PRIMARY,
            { haptics.toggle(!state.running); if (state.running) onPause() else onStart() }, Modifier.fillMaxWidth())
        note?.let { EyeText(it, style = typography.bodySecondary, color = colors.warning) }
    }
}

/**
 * 正方形取景框：四角白色 / 主色取景角标。检测中铺满相机画面（[live]），未检测时浅主色底 + 人头肩形虚线框占位。
 */
@Composable
private fun Viewfinder(running: Boolean, modifier: Modifier, live: @Composable BoxScope.() -> Unit) {
    val colors = LocalEyeColors.current
    Box(modifier.clip(EyeShapes.hero).background(if (running) colors.ink else colors.primarySoft)) {
        if (running) live() else Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val dash = Stroke(2.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx())))
            val ink = colors.primary.copy(alpha = 0.55f)
            // 头：竖椭圆
            drawOval(ink, Offset(w * 0.32f, h * 0.2f), Size(w * 0.36f, h * 0.44f), style = dash)
            // 肩：从左右两侧圆滑升到脖子
            drawPath(Path().apply {
                moveTo(w * 0.12f, h)
                cubicTo(w * 0.12f, h * 0.8f, w * 0.3f, h * 0.72f, w * 0.42f, h * 0.7f)
                moveTo(w * 0.58f, h * 0.7f)
                cubicTo(w * 0.7f, h * 0.72f, w * 0.88f, h * 0.8f, w * 0.88f, h)
            }, ink, style = dash)
        }
        Canvas(Modifier.matchParentSize()) {
            val inset = 18.dp.toPx()
            val arm = 26.dp.toPx()
            val stroke = 3.dp.toPx()
            val corner = if (running) colors.vivid.onVivid.copy(alpha = 0.9f) else colors.primary
            listOf(Offset(inset, inset) to Offset(1f, 1f), Offset(size.width - inset, inset) to Offset(-1f, 1f),
                Offset(inset, size.height - inset) to Offset(1f, -1f), Offset(size.width - inset, size.height - inset) to Offset(-1f, -1f),
            ).forEach { (o, d) ->
                drawLine(corner, o, Offset(o.x + d.x * arm, o.y), stroke, StrokeCap.Round)
                drawLine(corner, o, Offset(o.x, o.y + d.y * arm), stroke, StrokeCap.Round)
            }
        }
    }
}

internal const val SHOW_LANDMARKS = "显示眼部关键点"

internal const val LIVE_HELP = "用前置摄像头实时检测双眼关键点：数眨眼次数、估计眼睛到屏幕的距离和头部姿态，结果计入「视觉行为」。" +
    "「显示眼部关键点」只决定预览上是否画出眼睑轮廓和虹膜圈，不影响检测。" +
    "开始后离开首页或退到后台也会继续检测，通知栏会一直显示；点「停止检测」、点通知里的「停止」或从最近任务划掉应用即停止，熄屏时自动暂停。" +
    "画面只在本机实时处理，不保存、不上传。"

private fun status(state: LiveEyeUiState): Pair<String, StatusTone> {
    val s = state.snapshot
    return when {
        !state.running -> "已暂停" to StatusTone.NEUTRAL
        s == null || s.waiting -> "启动中" to StatusTone.NEUTRAL
        s.stale -> "画面中断" to StatusTone.WARNING
        !s.faceFound -> "未检测到人脸" to StatusTone.WARNING
        !s.eyesFound -> "未检测到双眼" to StatusTone.WARNING
        else -> "运行正常" to StatusTone.SUCCESS
    }
}

@Composable
private fun EyeStateChip(s: LiveEyeMonitor.Snapshot?) {
    val (label, tone) = when {
        s == null || !s.eyesFound || s.stale -> "无读数" to StatusTone.NEUTRAL
        !s.ready -> "建立基线" to StatusTone.WARNING
        s.closed -> "闭眼" to StatusTone.DANGER
        else -> "睁眼" to StatusTone.SUCCESS
    }
    EyeStatusChip(label, tone)
}

/** 预览上叠加双眼关键点：眼睑 6 点连线 + 虹膜圈；闭眼时变红。预览按 FILL_CENTER 把原图裁进正方形，坐标同样居中放大后水平镜像。 */
@Composable
private fun LandmarkOverlay(s: LiveEyeMonitor.Snapshot?, modifier: Modifier) {
    val colors = LocalEyeColors.current
    Canvas(modifier) {
        if (s == null || s.stale || s.imageWidth <= 0 || s.imageHeight <= 0) return@Canvas
        val scale = maxOf(size.width / s.imageWidth, size.height / s.imageHeight)
        val shown = Size(s.imageWidth * scale, s.imageHeight * scale)
        val origin = Offset((size.width - shown.width) / 2, (size.height - shown.height) / 2)
        fun toView(p: Point2f) = Offset(origin.x + (1 - p.x / s.imageWidth) * shown.width, origin.y + p.y / s.imageHeight * shown.height)
        val color = if (s.closed) colors.danger else colors.brandStart
        listOfNotNull(s.leftEye, s.rightEye).forEach { eye ->
            val points = eye.earPoints.map(::toView)
            if (points.size == 6) {
                drawPath(Path().apply {
                    moveTo(points[0].x, points[0].y)
                    for (i in 1..5) lineTo(points[i].x, points[i].y)
                    close()
                }, color, style = Stroke(1.2.dp.toPx()))
            }
            points.forEach { drawCircle(color, 1.8.dp.toPx(), it) }
            eye.irisPoints.firstOrNull()?.let { center ->
                drawCircle(colors.accent, eye.irisDiameterPx / 2 * scale, toView(center), style = Stroke(1.2.dp.toPx()))
            }
        }
    }
}

/** 最近 8 秒 EAR 曲线：虚线为当帧自适应阈值，低于阈值的时段打底色，每次眨眼画一条绿线。 */
@Composable
private fun EarTraceChart(s: LiveEyeMonitor.Snapshot?, modifier: Modifier) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Box(modifier) {
        Canvas(Modifier.matchParentSize()) {
            drawRoundRect(colors.appBackground, cornerRadius = CornerRadius(10.dp.toPx()))  // 同 EyeShapes.medium
            val samples = s?.samples.orEmpty()
            val now = samples.lastOrNull()?.timeMs ?: return@Canvas
            val window = LiveEyeMonitor.DEFAULT_WINDOW_MS.toFloat()
            val top = 0.45f
            fun x(t: Long) = size.width * (1 - (now - t) / window)
            fun y(v: Float) = size.height - 6.dp.toPx() - v.coerceIn(0f, top) / top * (size.height - 12.dp.toPx())
            var second = now - now % 1_000
            while (now - second < window) {
                drawLine(colors.borderSubtle, Offset(x(second), 0f), Offset(x(second), size.height), 1.dp.toPx())
                second -= 1_000
            }
            samples.forEachIndexed { i, f ->
                if (!f.closed) return@forEachIndexed
                val end = if (i + 1 < samples.size) samples[i + 1].timeMs else f.timeMs + 33
                drawRect(colors.dangerSoft, Offset(x(f.timeMs), 0f), Size((x(end) - x(f.timeMs)).coerceAtLeast(1f), size.height))
            }
            val threshold = Path()
            val line = Path()
            var open = false
            samples.forEachIndexed { i, f ->
                if (i == 0) threshold.moveTo(x(f.timeMs), y(f.threshold)) else threshold.lineTo(x(f.timeMs), y(f.threshold))
                val v = f.ear
                if (v == null) { open = false; return@forEachIndexed }
                if (open) line.lineTo(x(f.timeMs), y(v)) else { line.moveTo(x(f.timeMs), y(v)); open = true }
            }
            drawPath(threshold, colors.textSecondary, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
            drawPath(line, colors.primary, style = Stroke(2.dp.toPx()))
            s?.blinkTimesMs?.forEach { t ->
                val ex = x(t)
                drawLine(colors.success, Offset(ex, 4.dp.toPx()), Offset(ex, size.height - 4.dp.toPx()), 2.5.dp.toPx())
                drawCircle(colors.success, 4.dp.toPx(), Offset(ex, 8.dp.toPx()))
            }
        }
        EyeText(if (s?.samples.isNullOrEmpty()) "等待相机画面…" else "EAR · 近 8 秒",
            Modifier.align(if (s?.samples.isNullOrEmpty()) Alignment.Center else Alignment.TopStart).padding(8.dp),
            style = typography.chip, color = colors.textSecondary)
    }
}

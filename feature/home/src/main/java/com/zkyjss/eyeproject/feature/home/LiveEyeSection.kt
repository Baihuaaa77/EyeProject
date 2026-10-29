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
import com.zkyjss.eyeproject.core.domain.LiveEyeMonitor
import com.zkyjss.eyeproject.core.model.Point2f
import com.zkyjss.eyeproject.core.ui.component.*

/**
 * 首页「视觉行为」卡里的实时检测：默认关闭，打开后镜像预览叠加双眼关键点（眼睑 6 点 + 虹膜圈），旁边是眨眼次数、
 * 观看距离与当前状态，下方是最近 8 秒 EAR 曲线，每次眨眼画一条绿线。离开首页、退到后台后摘下预览、在后台继续检测
 * （通知栏常驻），直到关掉开关、点通知里的「停止」或从最近任务划掉应用；统计按段记入主动护眼账本。
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
    ) { AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize()) }
}

/** 无副作用的检测区内容，供界面检查复用（[preview] 为相机预览）。 */
@Composable
internal fun LiveEyeContent(
    state: LiveEyeUiState,
    note: String?,
    onStart: () -> Unit,
    onPause: () -> Unit,
    preview: @Composable () -> Unit,
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val haptics = rememberEyeHaptics()
    val s = state.snapshot
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(state.running, role = Role.Switch) { on -> haptics.toggle(on); if (on) onStart() else onPause() },
            verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                EyeText("实时检测", style = typography.body, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
                EyeHelpButton("实时检测", LIVE_HELP)
            }
            if (state.running) {
                val (label, tone) = status(state)
                EyeStatusChip(label, tone, live = tone == StatusTone.SUCCESS)
                Spacer(Modifier.width(8.dp))
            }
            EyeSwitch(checked = state.running, onCheckedChange = null)
        }
        if (state.running) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val aspect = if (s != null && s.imageWidth > 0 && s.imageHeight > 0) s.imageWidth.toFloat() / s.imageHeight else 3f / 4f
                Box(Modifier.width(120.dp).aspectRatio(aspect).clip(EyeShapes.medium).background(colors.ink)) {
                    preview()
                    LandmarkOverlay(s, Modifier.matchParentSize())
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        EyeText("${s?.blinkCount ?: 0}", style = typography.headline, color = colors.textPrimary)
                        EyeText("次眨眼", Modifier.padding(bottom = 6.dp), style = typography.body, color = colors.textSecondary)
                    }
                    EyeText("近 1 分钟 ${s?.blinksLastMinute ?: 0} 次", style = typography.body, color = colors.textSecondary)
                    EyeStateChip(s)
                    EyeText("距离 ${s?.distanceCm?.let { "%.0f 厘米".format(it) } ?: "—"}", style = typography.body, color = colors.textSecondary)
                    EyeText("EAR ${s?.ear?.let { "%.2f".format(it) } ?: "—"} · ${"%.0f".format(s?.fps ?: 0f)} 帧/秒",
                        style = typography.bodySecondary, color = colors.textSecondary)
                }
            }
            EarTraceChart(s, Modifier.fillMaxWidth().height(116.dp))
        }
        note?.let { EyeText(it, style = typography.bodySecondary, color = colors.warning) }
    }
}

internal const val LIVE_HELP = "用前置摄像头实时检测双眼关键点：数眨眼次数、估计眼睛到屏幕的距离和头部姿态，结果计入「视觉行为」。" +
    "打开后离开首页或退到后台也会继续检测，通知栏会一直显示；关掉开关、点通知里的「停止」或从最近任务划掉应用即停止，熄屏时自动暂停。" +
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

/** 预览上叠加双眼关键点：眼睑 6 点连线 + 虹膜圈；闭眼时变红。预览与原图同宽高比，只需水平镜像。 */
@Composable
private fun LandmarkOverlay(s: LiveEyeMonitor.Snapshot?, modifier: Modifier) {
    val colors = LocalEyeColors.current
    Canvas(modifier) {
        if (s == null || s.stale || s.imageWidth <= 0 || s.imageHeight <= 0) return@Canvas
        fun toView(p: Point2f) = Offset((1 - p.x / s.imageWidth) * size.width, p.y / s.imageHeight * size.height)
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
                drawCircle(colors.accent, eye.irisDiameterPx / 2 / s.imageWidth * size.width, toView(center), style = Stroke(1.2.dp.toPx()))
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
            drawRoundRect(colors.appBackground, cornerRadius = CornerRadius(12.dp.toPx()))
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

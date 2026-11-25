package com.zkyjss.strabismuslab

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zkyjss.eyeproject.core.camera.CameraController
import com.zkyjss.eyeproject.strabismuslab.LabCodingScheme
import com.zkyjss.eyeproject.strabismuslab.LabLiveState
import com.zkyjss.eyeproject.strabismuslab.LabProtocolConfig
import com.zkyjss.eyeproject.strabismuslab.LabRiskGrade
import com.zkyjss.eyeproject.strabismuslab.LabSessionReport
import com.zkyjss.eyeproject.strabismuslab.LabStage
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LabActivity : ComponentActivity() {
    private val viewModel by viewModels<LabViewModel>()
    private lateinit var camera: CameraController
    private var cameraGeneration: Int? = null
    private var previewViewRef: PreviewView? = null
    private var lastSearchRadius: Float = 0.80f
    private var lastBoundWithPreview: Boolean = true
    private var hasPermission by mutableStateOf(false)
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        camera = CameraController(applicationContext)

        setContent {
            StrabismusLabTheme {
                val ui by viewModel.ui.collectAsStateWithLifecycle()
                val live by viewModel.live.collectAsStateWithLifecycle()
                var requested by rememberSaveable { mutableStateOf(false) }
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { refreshCamera(ui.destination, ui.config.searchRadiusFraction) }

                // 进入纯黑光学采集舞台时自动将屏幕亮度推至 100%，退出后恢复系统设置
                DisposableEffect(ui.destination) {
                    val lp = window.attributes
                    if (ui.destination == LabDestination.CAPTURE) {
                        lp.screenBrightness = 1.0f
                        window.attributes = lp
                    } else {
                        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                        window.attributes = lp
                    }
                    onDispose {
                        val restore = window.attributes
                        restore.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                        window.attributes = restore
                    }
                }

                // 目的页或搜索半径变化时动态重绑前摄（控制台带预览，采集舞台无预览全速 4K 分析）
                LaunchedEffect(ui.destination, ui.config.searchRadiusFraction, hasPermission, resumed) {
                    refreshCamera(ui.destination, ui.config.searchRadiusFraction)
                }

                when (ui.destination) {
                    LabDestination.CONSOLE -> ConsoleScreen(
                        ui = ui,
                        live = live,
                        hasPermission = hasPermission,
                        onRequestPermission = {
                            if (requested && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                                startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", packageName, null),
                                    ),
                                )
                            } else {
                                requested = true
                                permissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                        onAttachPreview = { pv ->
                            previewViewRef = pv
                            refreshCamera(ui.destination, ui.config.searchRadiusFraction, force = true)
                        },
                        onUpdateConfig = viewModel::updateConfig,
                        onToggleOverlay = viewModel::setPreviewOverlay,
                        onStart = viewModel::startCapture,
                        onOpenHistory = viewModel::openHistory,
                    )

                    LabDestination.CAPTURE -> CaptureStageScreen(
                        live = live,
                        config = ui.config,
                        analyzing = ui.analyzing,
                        onDisplayTick = { viewModel.onDisplayFrameTick(1.0) },
                        onConfirmFar = viewModel::confirmFarReady,
                        onManualCover = viewModel::manualAdvanceCover,
                        onSkipStage = viewModel::skipStage,
                        onFinishEarly = viewModel::finishEarlyAndAnalyze,
                        onCancel = viewModel::cancelCapture,
                    )

                    LabDestination.DASHBOARD -> {
                        val report = ui.activeReport
                        if (report != null) {
                            LabDashboardScreen(
                                report = report,
                                onBack = viewModel::backToConsole,
                                onExport = { fmt ->
                                    val intent = viewModel.buildExportIntent(report, fmt)
                                    startActivity(Intent.createChooser(intent, fmt.label))
                                },
                            )
                        } else {
                            viewModel.backToConsole()
                        }
                    }

                    LabDestination.HISTORY -> HistoryScreen(
                        entries = ui.history,
                        onBack = viewModel::backToConsole,
                        onOpen = { viewModel.openReport(it.report) },
                        onDelete = { viewModel.deleteSession(it.sessionId) },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        hasPermission = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        refreshCamera(viewModel.ui.value.destination, viewModel.ui.value.config.searchRadiusFraction)
    }

    override fun onPause() {
        resumed = false
        stopCamera()
        super.onPause()
    }

    private fun refreshCamera(destination: LabDestination, searchRadius: Float, force: Boolean = false) {
        hasPermission = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val needsCamera = destination == LabDestination.CONSOLE || destination == LabDestination.CAPTURE
        if (!resumed || !hasPermission || !needsCamera) {
            stopCamera()
            return
        }
        val wantPreview = destination == LabDestination.CONSOLE
        if (wantPreview && previewViewRef == null) return
        if (!force && cameraGeneration != null && lastBoundWithPreview == wantPreview && lastSearchRadius == searchRadius) {
            return
        }
        stopCamera()
        lastBoundWithPreview = wantPreview
        lastSearchRadius = searchRadius
        cameraGeneration = camera.start(
            lifecycleOwner = this,
            previewView = if (wantPreview) previewViewRef else null,
            onFrame = viewModel::onCameraFrame,
            onError = { err -> runOnUiThread { viewModel.onCameraError(err.message ?: "相机错误") } },
            opticalPrecision = true,
            measureOptics = true,
            screenGlintSearchRadius = searchRadius,
        )
    }

    private fun stopCamera() {
        cameraGeneration?.let(camera::stopIfGeneration)
        cameraGeneration = null
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConsoleScreen(
    ui: LabAppUiState,
    live: LabLiveState,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onAttachPreview: (PreviewView) -> Unit,
    onUpdateConfig: ((LabProtocolConfig) -> LabProtocolConfig) -> Unit,
    onToggleOverlay: (Boolean) -> Unit,
    onStart: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val cfg = ui.config
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LabColors.canvas)
            .safeDrawingPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 顶栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("StrabismusLab", style = LabType.title)
                    Text("ACTIVE CODED CORNEAL LAB · v0.8", style = LabType.microMono, color = LabColors.accent)
                }
                OutlinedButton(
                    onClick = onOpenHistory,
                    shape = LabShapes.pill,
                    border = androidx.compose.foundation.BorderStroke(1.dp, LabColors.hairlineStrong),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    LabVectorIcon(LabGlyph.LIST, tint = LabColors.inkPrimary, size = 16.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("记录 ${ui.history.size}", style = LabType.caption, color = LabColors.inkPrimary)
                }
            }

            // 实时光学取景仪卡片
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LabColors.surface, LabShapes.card)
                    .border(1.dp, LabColors.hairline, LabShapes.card)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    if (live.faceDetected) LabColors.verified else LabColors.caution,
                                    LabShapes.pill,
                                ),
                        )
                        Text("光学取景仪 · ${(cfg.searchRadiusFraction * 100).toInt()}%R", style = LabType.section)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("叠加标注", style = LabType.microMono)
                        Switch(
                            checked = ui.showPreviewOverlay,
                            onCheckedChange = onToggleOverlay,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = LabColors.onAccent,
                                checkedTrackColor = LabColors.accent,
                                uncheckedThumbColor = LabColors.inkSecondary,
                                uncheckedTrackColor = LabColors.surfaceElevated,
                            ),
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 3f)
                        .clip(LabShapes.control)
                        .background(LabColors.stimulusBlack)
                        .border(1.dp, LabColors.hairline, LabShapes.control),
                    contentAlignment = Alignment.Center,
                ) {
                    if (hasPermission) {
                        AndroidView(
                            factory = { ctx ->
                                PreviewView(ctx).apply {
                                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                    scaleType = PreviewView.ScaleType.FILL_CENTER
                                    onAttachPreview(this)
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (ui.showPreviewOverlay) {
                            ViewfinderOpticsOverlay(live = live, modifier = Modifier.fillMaxSize())
                        }
                        // 顶部精简光学 HUD
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            HudBadge(
                                text = if (live.distanceCm > 0f) String.format("%.1f cm", live.distanceCm) else "-- cm",
                                color = if (live.distanceOk) LabColors.verified else LabColors.caution,
                            )
                            HudBadge(
                                text = String.format("%.0f fps · L%d/R%d", live.fps, live.leftCandidates.size, live.rightCandidates.size),
                                color = LabColors.accent,
                            )
                        }
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(20.dp),
                        ) {
                            Text("需要前置相机权限进行 4K 光学采集", style = LabType.body, color = LabColors.inkSecondary)
                            Button(
                                onClick = onRequestPermission,
                                colors = ButtonDefaults.buttonColors(containerColor = LabColors.accent, contentColor = LabColors.onAccent),
                                shape = LabShapes.control,
                            ) { Text("授权前置相机") }
                        }
                    }
                }

                // 实时四格遥测条
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniTelemetry("虹膜 L/R", "${live.leftIrisDiameterPx.toInt()}/${live.rightIrisDiameterPx.toInt()} px", Modifier.weight(1f))
                    MiniTelemetry("偏航 YAW", live.yawDeg?.let { String.format("%+.0f°", it) } ?: "—", Modifier.weight(1f))
                    MiniTelemetry("俯仰 PITCH", live.pitchDeg?.let { String.format("%+.0f°", it) } ?: "—", Modifier.weight(1f))
                    MiniTelemetry("可见对比", "${live.leftVisibilityContrast?.toInt() ?: 0}/${live.rightVisibilityContrast?.toInt() ?: 0}", Modifier.weight(1f))
                }
            }

            // 受试者与临床真值元数据
            LabCard("受试者与条件标签") {
                val fieldColors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = LabColors.accent,
                    unfocusedBorderColor = LabColors.hairline,
                    focusedTextColor = LabColors.inkPrimary,
                    unfocusedTextColor = LabColors.inkPrimary,
                    focusedLabelColor = LabColors.accent,
                    unfocusedLabelColor = LabColors.inkSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = cfg.subjectId,
                        onValueChange = { v -> onUpdateConfig { it.copy(subjectId = v) } },
                        label = { Text("匿名编号", style = LabType.caption) },
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = cfg.clinicalNote,
                        onValueChange = { v -> onUpdateConfig { it.copy(clinicalNote = v) } },
                        label = { Text("PACT 真值备注", style = LabType.caption) },
                        placeholder = { Text("如 XT 20Δ", style = LabType.caption, color = LabColors.inkTertiary) },
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier.weight(1f),
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("裸眼", "框架眼镜", "隐形眼镜").forEach { g ->
                        SelectPill(g, cfg.glassesState == g) { onUpdateConfig { it.copy(glassesState = g) } }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("室内普通光", "背窗强反光", "诊室强光", "暗室").forEach { l ->
                        SelectPill(l, cfg.lightingCondition == l) { onUpdateConfig { it.copy(lightingCondition = l) } }
                    }
                }
            }

            // 光码与算法参数（v0.8 + 0.80R）
            LabCard("光码协议与光学参数") {
                Text("编码方案（CornealCodeLab v0.8）", style = LabType.caption)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabCodingScheme.entries.forEach { scheme ->
                        SelectPill(scheme.label, cfg.codingScheme == scheme) {
                            onUpdateConfig { it.copy(codingScheme = scheme) }
                        }
                    }
                }

                HorizontalDivider(color = LabColors.hairline)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("虹膜搜索半径", style = LabType.caption)
                    Text(String.format("%.2f R（覆盖 %.0f%% 面积）", cfg.searchRadiusFraction, cfg.searchRadiusFraction * cfg.searchRadiusFraction * 100), style = LabType.microMono, color = LabColors.accent)
                }
                Slider(
                    value = cfg.searchRadiusFraction,
                    onValueChange = { r -> onUpdateConfig { it.copy(searchRadiusFraction = (r * 20).toInt() / 20f) } },
                    valueRange = 0.60f..0.90f,
                    steps = 5,
                    colors = SliderDefaults.colors(thumbColor = LabColors.accent, activeTrackColor = LabColors.accent, inactiveTrackColor = LabColors.hairline),
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("码元时长", style = LabType.caption)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(125L, 250L, 500L).forEach { ms ->
                            SelectPill("${ms}ms", cfg.chipDurationMs == ms) { onUpdateConfig { it.copy(chipDurationMs = ms) } }
                        }
                    }
                }

                HorizontalDivider(color = LabColors.hairline)
                Text("采集阶段开关", style = LabType.caption)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectPill("看近 ${cfg.nearDurationSec}s", cfg.includeNear) { onUpdateConfig { it.copy(includeNear = !it.includeNear) } }
                    SelectPill("看远 ${cfg.farDurationSec}s", cfg.includeFar) { onUpdateConfig { it.copy(includeFar = !it.includeFar) } }
                    SelectPill("交替遮盖", cfg.includeCover) { onUpdateConfig { it.copy(includeCover = !it.includeCover) } }
                    SelectPill("平滑追踪 ${cfg.pursuitDurationSec}s", cfg.includePursuit) { onUpdateConfig { it.copy(includePursuit = !it.includePursuit) } }
                }
            }

            ui.cameraError?.let { err ->
                Text(err, style = LabType.caption, color = LabColors.danger)
            }
            ui.statusBanner?.let { msg ->
                Text(msg, style = LabType.caption, color = LabColors.caution)
            }
        }

        // 底部悬浮主 CTA
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(LabColors.canvas.copy(alpha = 0.94f))
                .border(1.dp, LabColors.hairline)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Button(
                onClick = onStart,
                enabled = hasPermission && cfg.activeStages.isNotEmpty(),
                shape = LabShapes.control,
                colors = ButtonDefaults.buttonColors(
                    containerColor = LabColors.accent,
                    contentColor = LabColors.onAccent,
                    disabledContainerColor = LabColors.surfaceElevated,
                    disabledContentColor = LabColors.inkTertiary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                LabVectorIcon(LabGlyph.PLAY, tint = LabColors.onAccent, size = 18.dp)
                Spacer(Modifier.width(8.dp))
                Text("开始斜视光码采集（${cfg.activeStages.size} 阶段）", style = LabType.section, color = LabColors.onAccent)
            }
        }
    }
}

/** 纯黑 #000000 光学采集舞台：贴近前摄 60Hz 光码白点发射 + 极简科研遥测底栏。 */
@Composable
private fun CaptureStageScreen(
    live: LabLiveState,
    config: LabProtocolConfig,
    analyzing: Boolean,
    onDisplayTick: () -> Pair<Float, Double>,
    onConfirmFar: () -> Unit,
    onManualCover: () -> Unit,
    onSkipStage: () -> Unit,
    onFinishEarly: () -> Unit,
    onCancel: () -> Unit,
) {
    BackHandler(onBack = onCancel)
    var renderedIntensity by remember { mutableFloatStateOf(1f) }
    val density = LocalDensity.current

    // 60Hz 显示帧同步驱动白点明暗，并将真实绘制时间写入 v0.8 显示事件序列
    LaunchedEffect(live.stage, live.farWaitingConfirm) {
        while (isActive) {
            withFrameNanos {
                val (intensity, _) = onDisplayTick()
                renderedIntensity = intensity
            }
        }
    }

    // 根据物理直径（mm）换算屏幕逻辑像素直径（近似 160dp/inch = 6.3 dp/mm）
    val dotDiameterDp = (config.stimulusDiameterMm * 6.3).coerceIn(28.0, 140.0).dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LabColors.stimulusBlack)
            .safeDrawingPadding(),
    ) {
        // 顶部贴近前置摄像头的光学编码白点（纯黑画布，真实 0% 灭灯）
        if (live.stage.usesOpticalStimulus && !live.farWaitingConfirm) {
            val lum = renderedIntensity.coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 6.dp)
                    .size(dotDiameterDp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    if (lum > 0.005f) {
                        drawCircle(color = Color(lum, lum, lum, 1f))
                    }
                    // 极微弱中心固视准星（仅亮灯时显示，辅助受试者稳固注视）
                    if (lum > 0.5f && live.stage != LabStage.FAR_FIXATION) {
                        drawCircle(color = LabColors.accent.copy(alpha = 0.65f), radius = 4f)
                    }
                }
            }
        }

        // 平滑追踪阶段：纯黑画布上的高精度橙金移动目标点
        if (live.stage == LabStage.PURSUIT) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 56.dp, bottom = 156.dp),
            ) {
                val cx = size.width * live.pursuitTargetX.coerceIn(0.08f, 0.92f)
                val cy = size.height * live.pursuitTargetY.coerceIn(0.08f, 0.92f)
                drawCircle(color = LabColors.rightEye.copy(alpha = 0.25f), radius = with(density) { 18.dp.toPx() }, center = Offset(cx, cy))
                drawCircle(color = LabColors.rightEye, radius = with(density) { 8.dp.toPx() }, center = Offset(cx, cy))
                drawCircle(color = LabColors.inkPrimary, radius = with(density) { 2.5.dp.toPx() }, center = Offset(cx, cy))
            }
        }

        // 中央阶段提示与交互按钮（仅在需要人工确认/遮盖引导时出现）
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (analyzing) {
                CircularProgressIndicator(color = LabColors.accent)
                Text("正在执行 v0.8 双向光码解调与 63 错码核验…", style = LabType.body, color = LabColors.inkSecondary)
            } else if (live.farWaitingConfirm) {
                Text("阶段 ${live.stageIndex}/${live.totalStages} · 看远光码映光", style = LabType.section)
                Text("保持手机在 33 cm 略低于视线，越过屏幕盯住 6 m 远目标", style = LabType.body, color = LabColors.inkSecondary)
                Button(
                    onClick = onConfirmFar,
                    shape = LabShapes.control,
                    colors = ButtonDefaults.buttonColors(containerColor = LabColors.accent, contentColor = LabColors.onAccent),
                    modifier = Modifier.height(48.dp),
                ) { Text("已注视远目标，开始采集", style = LabType.section, color = LabColors.onAccent) }
            } else if (live.stage == LabStage.COVER_TEST) {
                Text(live.coverInstruction, style = LabType.title, color = if (live.coverHolding) LabColors.verified else LabColors.inkPrimary)
                Text("遮盖动作 ${live.coverStepIndex}/${live.coverStepTotal}", style = LabType.microMono)
                OutlinedButton(
                    onClick = onManualCover,
                    shape = LabShapes.pill,
                    border = androidx.compose.foundation.BorderStroke(1.dp, LabColors.hairlineStrong),
                ) { Text("手动确认本步遮盖", style = LabType.caption, color = LabColors.inkSecondary) }
            }
        }

        // 底部极简实验室遥测与控制条
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(LabColors.surface.copy(alpha = 0.88f))
                .border(1.dp, LabColors.hairline)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "${live.stageIndex}/${live.totalStages} ${live.stage.title}",
                        style = LabType.section,
                    )
                    Text(
                        text = live.warning ?: live.prompt,
                        style = LabType.caption,
                        color = if (live.warning != null) LabColors.caution else LabColors.inkSecondary,
                    )
                }
                if (live.remainingSec > 0) {
                    Text("${live.remainingSec}s", style = LabType.metricHero, color = LabColors.accent)
                }
            }

            // 细进度轨道
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(LabColors.surfaceElevated, LabShapes.pill),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(live.stageProgress.coerceIn(0f, 1f))
                        .height(4.dp)
                        .background(LabColors.accent, LabShapes.pill),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = String.format(
                        "%.1f cm · %.0f fps · %d 帧 · L%d/R%d",
                        live.distanceCm,
                        live.fps,
                        live.capturedFrames,
                        live.leftCandidates.size,
                        live.rightCandidates.size,
                    ),
                    style = LabType.microMono,
                    color = if (live.distanceOk) LabColors.verified else LabColors.caution,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "跳过阶段",
                        style = LabType.caption,
                        color = LabColors.inkSecondary,
                        modifier = Modifier
                            .clip(LabShapes.pill)
                            .clickable(onClick = onSkipStage)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                    Text(
                        text = "提前分析",
                        style = LabType.caption,
                        color = LabColors.accent,
                        modifier = Modifier
                            .clip(LabShapes.pill)
                            .clickable(onClick = onFinishEarly)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                    IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                        LabVectorIcon(LabGlyph.CLOSE, tint = LabColors.inkSecondary, size = 18.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryScreen(
    entries: List<LabSessionEntry>,
    onBack: () -> Unit,
    onOpen: (LabSessionEntry) -> Unit,
    onDelete: (LabSessionEntry) -> Unit,
) {
    BackHandler(onBack = onBack)
    val dateFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LabColors.canvas)
            .safeDrawingPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                LabVectorIcon(LabGlyph.BACK, tint = LabColors.inkPrimary, size = 20.dp)
            }
            Text("科研实验记录 (${entries.size})", style = LabType.title)
        }
        HorizontalDivider(color = LabColors.hairline)

        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无科研采集记录", style = LabType.body, color = LabColors.inkTertiary)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(entries, key = { it.sessionId }) { entry ->
                    val r = entry.report
                    val gradeColor = riskColor(r.riskGrade)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(LabShapes.card)
                            .background(LabColors.surface)
                            .border(1.dp, LabColors.hairline, LabShapes.card)
                            .clickable { onOpen(entry) }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(r.config.subjectId, style = LabType.section)
                                Text(
                                    r.riskGrade.label,
                                    style = LabType.microMono,
                                    color = gradeColor,
                                    modifier = Modifier
                                        .background(gradeColor.copy(alpha = 0.16f), LabShapes.pill)
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                            Text(
                                text = "${dateFmt.format(Date(r.createdAtMs))} · ${r.config.codingScheme.label} · ${(r.config.searchRadiusFraction * 100).toInt()}%R",
                                style = LabType.caption,
                            )
                            Text(
                                text = "看近 HDE ${r.nearMetrics?.hdePrism?.let { String.format("%.1fΔ", it) } ?: "—"} · " +
                                    "光码 L:${if (r.nearLeftCode?.codeVerified == true) "✓" else "×"} R:${if (r.nearRightCode?.codeVerified == true) "✓" else "×"} · ${r.validFrames}/${r.totalFrames} 帧",
                                style = LabType.microMono,
                                color = LabColors.inkSecondary,
                            )
                        }
                        IconButton(onClick = { onDelete(entry) }) {
                            LabVectorIcon(LabGlyph.DELETE, tint = LabColors.inkTertiary, size = 18.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun LabCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(LabColors.surface, LabShapes.card)
            .border(1.dp, LabColors.hairline, LabShapes.card)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = LabType.section)
        content()
    }
}

@Composable
private fun SelectPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) LabColors.accentSoft else LabColors.surfaceElevated
    val border = if (selected) LabColors.accent else LabColors.hairline
    val ink = if (selected) LabColors.accent else LabColors.inkSecondary
    Box(
        modifier = Modifier
            .clip(LabShapes.pill)
            .background(bg)
            .border(1.dp, border, LabShapes.pill)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, style = LabType.caption, color = ink)
    }
}

@Composable
private fun HudBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(LabColors.canvas.copy(alpha = 0.78f), LabShapes.pill)
            .border(1.dp, color.copy(alpha = 0.5f), LabShapes.pill)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, style = LabType.microMono, color = color)
    }
}

@Composable
private fun MiniTelemetry(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(LabColors.surfaceElevated, LabShapes.control)
            .border(1.dp, LabColors.hairline, LabShapes.control)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = LabType.microMono, color = LabColors.inkTertiary)
        Text(value, style = LabType.metricMono)
    }
}

internal fun riskColor(grade: LabRiskGrade): Color = when (grade) {
    LabRiskGrade.NORMAL -> LabColors.verified
    LabRiskGrade.SUSPECT -> LabColors.caution
    LabRiskGrade.HIGH_RISK -> LabColors.danger
    LabRiskGrade.INCONCLUSIVE -> LabColors.inkSecondary
}

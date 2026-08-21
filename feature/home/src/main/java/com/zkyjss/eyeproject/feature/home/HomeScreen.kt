package com.zkyjss.eyeproject.feature.home

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zkyjss.eyeproject.core.common.formatLux
import com.zkyjss.eyeproject.core.common.luxDetail
import com.zkyjss.eyeproject.core.data.UsageAccessHelper
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.ui.component.EyeButton
import com.zkyjss.eyeproject.core.ui.component.EyeButtonRole
import com.zkyjss.eyeproject.core.ui.component.EyeMetricCard
import com.zkyjss.eyeproject.core.ui.component.EyeSectionCard
import com.zkyjss.eyeproject.core.ui.component.EyeSegmentedRow
import com.zkyjss.eyeproject.core.ui.component.EyeStatusChip
import com.zkyjss.eyeproject.core.ui.component.EyeText
import com.zkyjss.eyeproject.core.ui.component.EyeTopBar
import com.zkyjss.eyeproject.core.ui.component.SegmentedOption
import com.zkyjss.eyeproject.core.ui.component.StatusTone
import com.zkyjss.eyeproject.core.model.AnalysisResolution
import com.zkyjss.eyeproject.core.model.ColorTemperatureMode
import com.zkyjss.eyeproject.core.model.DistanceState
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onNavigateToTestModel: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    var pendingColorMode by remember { mutableStateOf<ColorTemperatureMode?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.onCameraPermissionResult(granted) }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.onNotificationPermissionResult(granted) }

    val overlayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val mode = pendingColorMode
        pendingColorMode = null
        if (mode != null) viewModel.applyPendingColorTemperature(mode)
    }

    LaunchedEffect(Unit) {
        viewModel.permissionEvents.collect { event ->
            when (event) {
                PermissionEvent.Camera -> cameraLauncher.launch(Manifest.permission.CAMERA)
                PermissionEvent.Notification ->
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                PermissionEvent.UsageAccess ->
                    context.startActivity(UsageAccessHelper.createUsageAccessSettingsIntent())
                is PermissionEvent.Overlay -> {
                    pendingColorMode = event.mode
                    overlayLauncher.launch(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }
                PermissionEvent.ExactAlarm -> context.startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            }
        }
    }

    val colors = LocalEyeColors.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.appBackground)
            .verticalScroll(rememberScrollState())
            .systemBarsPadding()
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        EyeTopBar(
            title = "眼健康助手",
            subtitle = state.statusMessage,
            statusText = if (state.cameraRunning) "检测中" else "待机",
            statusTone = if (state.cameraRunning) StatusTone.SUCCESS else StatusTone.NEUTRAL
        )

        EyeButton(
            text = "模型测试（L2CS-Net 注视估计）",
            role = EyeButtonRole.SECONDARY,
            onClick = onNavigateToTestModel,
            modifier = Modifier.fillMaxWidth()
        )

        PreviewSection(
            state = state,
            previewView = previewView
        )

        MetricsSection(state = state)

        ControlsSection(
            state = state,
            onToggleCamera = { viewModel.startDetection(lifecycleOwner, previewView) },
            onStopCamera = { viewModel.stopDetection() },
            onCalibrate = { viewModel.calibrateDistance() },
            onSelectResolution = { viewModel.selectResolution(it) },
            onSelectColorTemperature = { viewModel.selectColorTemperature(it) },
            onToggleReminder = { viewModel.toggleReminder() },
            onSelectReminderInterval = { viewModel.selectReminderInterval(it) }
        )
    }
}

@Composable
private fun PreviewSection(
    state: HomeUiState,
    previewView: PreviewView
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    EyeSectionCard(title = "前置摄像头") {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(EyeShapes.card)
                .background(colors.cameraSurface)
        ) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )
            EyeOverlay(
                detections = state.detections,
                sourceWidth = state.sourceWidth,
                sourceHeight = state.sourceHeight,
                modifier = Modifier.fillMaxSize()
            )
            if (!state.cameraRunning || state.faceCount == 0) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    EyeText(
                        text = if (state.cameraRunning) "未检测到人脸" else "点击一键授权并检测，开启前置摄像头",
                        style = typography.subtitle,
                        color = colors.textInverse
                    )
                }
            }
        }
        if (state.cameraRunning) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                EyeText(
                    text = "画面 ${state.sourceWidth}x${state.sourceHeight} · ${state.faceCount} 张人脸",
                    style = typography.bodySecondary,
                    color = colors.textSecondary
                )
                EyeStatusChip(
                    text = "%.1f 帧".format(state.fps),
                    tone = StatusTone.NEUTRAL
                )
            }
        }
    }
}

@Composable
private fun MetricsSection(state: HomeUiState) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    EyeSectionCard(title = "实时用眼状态") {
        val distance = state.distanceEstimate
        val eyeDistancePx = distance?.eyeDistancePx
        val distanceValue = distance?.estimatedDistanceCm?.let { "${it.roundToInt()} cm" } ?: "--"
        val distanceDetail = when {
            distance?.state == DistanceState.NEEDS_CALIBRATION -> "未标定：保持约 40cm 并点击标定"
            eyeDistancePx != null -> "双眼像素距离 ${eyeDistancePx.roundToInt()} px"
            else -> "等待检测双眼"
        }
        val distanceColor = when (distance?.state) {
            DistanceState.TOO_CLOSE -> colors.danger
            DistanceState.NORMAL -> colors.success
            else -> colors.textPrimary
        }
        val brightnessDetail = when {
            state.brightnessPercent == null -> "无法读取亮度设置"
            state.brightnessAutomatic == true -> "自动亮度"
            else -> "手动亮度"
        }
        val lux = state.ambientLux
        val lightValue = lux?.let { formatLux(it) } ?: if (state.lightSensorAvailable) "--" else "不可用"
        val lightDetail = when {
            !state.lightSensorAvailable -> "未检测到光线传感器"
            lux == null -> "等待光照数据"
            else -> luxDetail(lux)
        }

        val cards = listOf(
            Triple("距离", distanceValue, distanceDetail) to distanceColor,
            Triple("时长", state.screenUsageText, state.screenUsageDetail) to colors.textPrimary,
            Triple("亮度", state.brightnessPercent?.let { "$it%" } ?: "--", brightnessDetail) to colors.textPrimary,
            Triple("光照", lightValue, lightDetail) to colors.textPrimary
        )
        val rows = cards.chunked(2)
        for ((rowIndex, rowCards) in rows.withIndex()) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for ((index, entry) in rowCards.withIndex()) {
                    val (triple, color) = entry
                    EyeMetricCard(
                        label = triple.first,
                        value = triple.second,
                        detail = triple.third,
                        valueColor = color,
                        modifier = Modifier
                            .weight(1f)
                            .then(
                                if (index < rowCards.lastIndex) {
                                    Modifier.padding(end = 10.dp)
                                } else {
                                    Modifier
                                }
                            )
                    )
                }
            }
            if (rowIndex < rows.size - 1) {
                Spacer(Modifier.height(10.dp))
            }
        }

        if (state.distanceTooClose) {
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(EyeShapes.medium)
                    .background(colors.dangerSoft)
                    .padding(12.dp)
            ) {
                EyeText(
                    text = "距离过近：${distanceValue}，请将屏幕拿远一些。",
                    style = typography.subtitle,
                    color = colors.danger
                )
            }
        }
    }
}

@Composable
private fun ControlsSection(
    state: HomeUiState,
    onToggleCamera: () -> Unit,
    onStopCamera: () -> Unit,
    onCalibrate: () -> Unit,
    onSelectResolution: (AnalysisResolution) -> Unit,
    onSelectColorTemperature: (ColorTemperatureMode) -> Unit,
    onToggleReminder: () -> Unit,
    onSelectReminderInterval: (Int) -> Unit
) {
    val typography = LocalEyeTypography.current
    val colors = LocalEyeColors.current
    EyeSectionCard(title = "快捷操作") {
        EyeButton(
            text = if (state.cameraRunning) "停止检测" else "一键授权并检测",
            role = EyeButtonRole.PRIMARY,
            onClick = onToggleCamera,
            modifier = Modifier.fillMaxWidth()
        )
        if (state.cameraRunning) {
            Spacer(Modifier.height(10.dp))
            EyeButton(
                text = "释放摄像头",
                role = EyeButtonRole.DANGER,
                onClick = onStopCamera,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(10.dp))
        EyeButton(
            text = "40cm 标定",
            role = EyeButtonRole.SECONDARY,
            onClick = onCalibrate,
            enabled = state.cameraRunning,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(14.dp))
        EyeText(text = "分析分辨率", style = typography.subtitle, color = colors.textPrimary)
        Spacer(Modifier.height(8.dp))
        EyeSegmentedRow(
            options = AnalysisResolution.entries.map { SegmentedOption(it, resolutionLabel(it)) },
            selected = state.analysisResolution,
            onSelect = onSelectResolution
        )

        Spacer(Modifier.height(14.dp))
        EyeText(text = "全局护眼色温：${state.colorTemperatureMode.label}", style = typography.subtitle, color = colors.textPrimary)
        Spacer(Modifier.height(8.dp))
        EyeSegmentedRow(
            options = ColorTemperatureMode.entries.map { SegmentedOption(it, it.label) },
            selected = state.colorTemperatureMode,
            onSelect = onSelectColorTemperature
        )

        Spacer(Modifier.height(14.dp))
        EyeText(text = reminderStatusText(state), style = typography.subtitle, color = colors.textPrimary)
        Spacer(Modifier.height(8.dp))
        EyeSegmentedRow(
            options = listOf(
                SegmentedOption(20, "20分钟"),
                SegmentedOption(30, "30分钟"),
                SegmentedOption(60, "60分钟")
            ),
            selected = state.reminderIntervalMinutes,
            onSelect = onSelectReminderInterval
        )
        Spacer(Modifier.height(8.dp))
        EyeButton(
            text = if (state.reminderEnabled) "关闭提醒" else "开启提醒",
            role = if (state.reminderEnabled) EyeButtonRole.DANGER else EyeButtonRole.PRIMARY,
            onClick = onToggleReminder,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun resolutionLabel(resolution: AnalysisResolution): String = when (resolution) {
    AnalysisResolution.LOW_POWER -> "省电"
    AnalysisResolution.BALANCED -> "均衡"
    AnalysisResolution.HIGH_QUALITY -> "高清"
}

private fun reminderStatusText(state: HomeUiState): String =
    if (state.reminderEnabled) {
        "每 ${state.reminderIntervalMinutes} 分钟提醒，下次约 ${state.reminderNextTimeText}"
    } else {
        "定时护眼提醒（已关闭）"
    }

package com.zkyjss.eyeproject.feature.fatigue

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.designsystem.util.getTopStatusBarPadding
import com.zkyjss.eyeproject.core.designsystem.util.screenHorizontalPaddingLarge
import com.zkyjss.eyeproject.core.designsystem.util.screenHorizontalPaddingMedium
import com.zkyjss.eyeproject.core.designsystem.util.screenHorizontalPaddingSmall
import com.zkyjss.eyeproject.core.model.ColorTemperatureMode
import com.zkyjss.eyeproject.core.ui.component.EyeButton
import com.zkyjss.eyeproject.core.ui.component.EyeButtonRole
import com.zkyjss.eyeproject.core.ui.component.EyeSectionCard
import com.zkyjss.eyeproject.core.ui.component.EyeSegmentedRow
import com.zkyjss.eyeproject.core.ui.component.EyeText
import com.zkyjss.eyeproject.core.ui.component.EyeTopBar
import com.zkyjss.eyeproject.core.ui.component.SegmentedOption
import com.zkyjss.eyeproject.core.ui.component.StatusTone
import java.util.Locale

@Composable
fun FatigueScreen(
    modifier: Modifier = Modifier,
    onNavigateToTestModel: () -> Unit = {},
    viewModel: FatigueViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = LocalEyeColors.current

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

    // 收集后续（用户触发）的权限请求
    LaunchedEffect(Unit) {
        viewModel.permissionEvents.collect { event ->
            when (event) {
                FatiguePermissionEvent.Camera ->
                    cameraLauncher.launch(Manifest.permission.CAMERA)
                FatiguePermissionEvent.Notification ->
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                is FatiguePermissionEvent.Overlay -> {
                    pendingColorMode = event.mode
                    overlayLauncher.launch(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }
                FatiguePermissionEvent.ExactAlarm -> context.startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            }
        }
    }

    // 首次进入：标准权限流程（无权限才请求），有权限则直接开始监测
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            cameraLauncher.launch(Manifest.permission.CAMERA)
        } else {
            viewModel.startMonitoring()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.appBackground)
            .verticalScroll(rememberScrollState())
            .padding(top = getTopStatusBarPadding())
            .screenHorizontalPaddingLarge(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        EyeTopBar(
            title = "眼健康助手",
            subtitle = state.statusMessage,
            statusText = if (state.monitoring) "监测中" else "待机",
            statusTone = if (state.monitoring) StatusTone.SUCCESS else StatusTone.NEUTRAL
        )

        RiskSection(state = state)

        ChartsSection(state = state)

        ControlsSection(
            state = state,
            onToggleMonitoring = {
                if (state.monitoring) viewModel.stopMonitoring() else viewModel.startMonitoring()
            },
            onSelectColorTemperature = { viewModel.selectColorTemperature(it) },
            onToggleReminder = { viewModel.toggleReminder() },
            onSelectReminderInterval = { viewModel.selectReminderInterval(it) }
        )

        EyeButton(
            text = "模型测试（L2CS-Net 注视估计）",
            role = EyeButtonRole.SECONDARY,
            onClick = onNavigateToTestModel,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun RiskSection(state: FatigueUiState) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val realtime = state.realtime
    EyeSectionCard {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            RiskGauge(
                risk = realtime.totalRisk,
                modifier = Modifier.size(190.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        EyeText(
            text = realtime.advice.ifEmpty { "等待监测数据…" },
            style = typography.body,
            color = colors.textSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        if (state.error != null) {
            Spacer(Modifier.height(8.dp))
            EyeText(text = state.error, style = typography.body, color = colors.danger)
        }
    }
}

@Composable
private fun ChartsSection(state: FatigueUiState) {
    val colors = LocalEyeColors.current
    val samples = state.samples
    val rt = state.realtime
    EyeSectionCard {
        EyeLineChart(
            samples = samples,
            valueOf = { it.distanceCm },
            color = colors.success,
            title = "用眼距离",
            valueText = formatDistance(rt.distanceCm)
        )
        Spacer(Modifier.height(16.dp))
        EyeLineChart(
            samples = samples,
            valueOf = { it.blinkCount.toFloat() },
            color = colors.warning,
            title = "眨眼次数",
            valueText = "${rt.blinkCount} 次"
        )
        Spacer(Modifier.height(16.dp))
        EyeLineChart(
            samples = samples,
            valueOf = { it.ambientLux },
            color = colors.primary,
            title = "环境光照",
            valueText = String.format(Locale.US, "%.0f lx", rt.ambientLux)
        )
        Spacer(Modifier.height(16.dp))
        EyeLineChart(
            samples = samples,
            valueOf = { it.durationMin },
            color = colors.primaryDark,
            title = "连续用眼",
            valueText = String.format(Locale.US, "%.0f 分", rt.durationMin)
        )
        Spacer(Modifier.height(16.dp))
        EyeLineChart(
            samples = samples,
            valueOf = { it.screenBrightness.toFloat() },
            color = colors.textSecondary,
            title = "屏幕亮度",
            valueText = "${rt.screenBrightness}%"
        )
    }
}

@Composable
private fun ControlsSection(
    state: FatigueUiState,
    onToggleMonitoring: () -> Unit,
    onSelectColorTemperature: (ColorTemperatureMode) -> Unit,
    onToggleReminder: () -> Unit,
    onSelectReminderInterval: (Int) -> Unit
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    EyeSectionCard(title = "控制") {
        EyeButton(
            text = if (state.monitoring) "停止监测" else "开始监测",
            role = if (state.monitoring) EyeButtonRole.DANGER else EyeButtonRole.PRIMARY,
            onClick = onToggleMonitoring,
            modifier = Modifier.fillMaxWidth()
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

private fun formatDistance(cm: Float): String =
    if (cm > 0f) String.format(Locale.US, "%.0f cm", cm) else "--"

private fun reminderStatusText(state: FatigueUiState): String =
    if (state.reminderEnabled) {
        "每 ${state.reminderIntervalMinutes} 分钟提醒，下次约 ${state.reminderNextTimeText}"
    } else {
        "定时护眼提醒（已关闭）"
    }

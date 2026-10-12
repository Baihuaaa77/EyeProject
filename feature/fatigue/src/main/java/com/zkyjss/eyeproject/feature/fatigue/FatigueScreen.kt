package com.zkyjss.eyeproject.feature.fatigue

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zkyjss.eyeproject.core.common.formatDistanceCm
import com.zkyjss.eyeproject.core.designsystem.theme.EyeMotion
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.designsystem.util.getTopStatusBarPadding
import com.zkyjss.eyeproject.core.designsystem.util.screenHorizontalPaddingLarge
import com.zkyjss.eyeproject.core.model.ColorTemperatureMode
import com.zkyjss.eyeproject.core.ui.component.EyeButton
import com.zkyjss.eyeproject.core.ui.component.EyeBrandMark
import com.zkyjss.eyeproject.core.ui.component.EyeButtonRole
import com.zkyjss.eyeproject.core.ui.component.EyeDisclosure
import com.zkyjss.eyeproject.core.ui.component.EyeSectionCard
import com.zkyjss.eyeproject.core.ui.component.EyeSegmentedRow
import com.zkyjss.eyeproject.core.ui.component.EyeSwitch
import com.zkyjss.eyeproject.core.ui.component.EyeText
import com.zkyjss.eyeproject.core.ui.component.EyeTopBar
import com.zkyjss.eyeproject.core.ui.component.SegmentedOption
import com.zkyjss.eyeproject.core.ui.component.StatusTone
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun FatigueScreen(
    modifier: Modifier = Modifier,
    onNavigateToTestModel: () -> Unit = {},
    viewModel: FatigueViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
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
                // 部分厂商 ROM 没有该设置页，打不开时不崩溃，界面已有文字提示
                FatiguePermissionEvent.ExactAlarm -> runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }
            }
        }
    }

    // 首次进入：标准权限流程（无权限才请求），有权限则直接开始监测。
    // 只在 ViewModel 生命周期内做一次，切换 Tab 回来不重复弹权限、不重启已手动停止的监测。
    LaunchedEffect(Unit) {
        if (!viewModel.consumeAutoStart()) return@LaunchedEffect
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            cameraLauncher.launch(Manifest.permission.CAMERA)
        } else {
            viewModel.startMonitoring()
        }
    }

    // 回到前台时同步系统设置页 / 通知栏里发生的变化
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    FatigueDashboard(
        state = state,
        modifier = modifier,
        onNavigateToTestModel = onNavigateToTestModel,
        onToggleMonitoring = { if (state.monitoring) viewModel.stopMonitoring() else viewModel.startMonitoring() },
        onSelectColorTemperature = viewModel::selectColorTemperature,
        onToggleReminder = viewModel::toggleReminder,
        onSelectReminderInterval = viewModel::selectReminderInterval,
        onOpenAppSettings = {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                )
            )
        }
    )
}

/** 无副作用的页面内容，供状态驱动及本地界面检查复用。 */
@Composable
internal fun FatigueDashboard(
    state: FatigueUiState,
    modifier: Modifier = Modifier,
    onNavigateToTestModel: () -> Unit,
    onToggleMonitoring: () -> Unit,
    onSelectColorTemperature: (ColorTemperatureMode) -> Unit,
    onToggleReminder: () -> Unit,
    onSelectReminderInterval: (Int) -> Unit,
    onOpenAppSettings: () -> Unit = {},
) {
    val colors = LocalEyeColors.current
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
            title = "今日用眼",
            subtitle = remember { todayText() },
            leading = { EyeBrandMark(size = 44.dp) },
            statusLive = state.monitoring,
            statusText = when {
                state.monitoring -> "监测中"
                state.error != null -> "异常"
                else -> "待机"
            },
            statusTone = when {
                state.monitoring -> StatusTone.SUCCESS
                state.error != null -> StatusTone.DANGER
                else -> StatusTone.NEUTRAL
            }
        )

        RiskSection(
            state = state,
            onToggleMonitoring = onToggleMonitoring,
            onOpenAppSettings = onOpenAppSettings
        )

        ControlsSection(
            state = state,
            onSelectColorTemperature = onSelectColorTemperature,
            onToggleReminder = onToggleReminder,
            onSelectReminderInterval = onSelectReminderInterval
        )

        EyeDisclosure("用眼趋势") { ChartsSection(state = state) }
        EyeDisclosure("开发者工具") {
            EyeButton(
                text = "模型测试",
                role = EyeButtonRole.SECONDARY,
                onClick = onNavigateToTestModel,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RiskSection(
    state: FatigueUiState,
    onToggleMonitoring: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val realtime = state.realtime
    val live = state.monitoring && realtime.faceDetected
    EyeSectionCard {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            RiskGauge(
                risk = realtime.totalRisk.takeIf { live && state.samples.isNotEmpty() },
                live = state.monitoring,
                modifier = Modifier.size(210.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        val hint = when {
            state.cameraPermissionDenied -> "需要摄像头权限才能测量用眼距离与眨眼"
            !state.monitoring -> "开始监测后，这里会实时显示用眼风险"
            !realtime.faceDetected -> "未检测到人脸，请正对屏幕"
            state.samples.isEmpty() -> "正在采集，约 30 秒后给出首次评估"
            else -> realtime.advice.ifEmpty { "正在采集" }
        }
        Crossfade(targetState = hint, animationSpec = tween(EyeMotion.MEDIUM), label = "提示切换") { text ->
            EyeText(
                text = text,
                style = typography.body,
                color = if (state.cameraPermissionDenied) colors.danger else colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (state.error != null) {
            Spacer(Modifier.height(8.dp))
            EyeText(
                text = state.error,
                style = typography.bodySecondary,
                color = colors.danger,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        AnimatedVisibility(
            visible = state.monitoring,
            enter = expandVertically(tween(EyeMotion.MEDIUM, easing = EyeMotion.Standard)) + fadeIn(tween(EyeMotion.MEDIUM)),
            exit = shrinkVertically(tween(EyeMotion.MEDIUM, easing = EyeMotion.Standard)) + fadeOut(tween(EyeMotion.SHORT)),
        ) {
            Column {
                Spacer(Modifier.height(16.dp))
                LiveMetricsRow(state = state, live = live)
            }
        }

        Spacer(Modifier.height(16.dp))
        EyeButton(
            text = if (state.monitoring) "停止监测" else "开始监测",
            role = if (state.monitoring) EyeButtonRole.DANGER else EyeButtonRole.PRIMARY,
            onClick = onToggleMonitoring,
            modifier = Modifier.fillMaxWidth()
        )
        if (state.cameraPermissionDenied) {
            Spacer(Modifier.height(8.dp))
            EyeButton(
                text = "打开系统设置授权",
                role = EyeButtonRole.SECONDARY,
                onClick = onOpenAppSettings,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** 监测中的三项实时读数：距离 / 眨眼 / 时长。未检测到人脸时显示「—」，不展示过期数值。 */
@Composable
private fun LiveMetricsRow(state: FatigueUiState, live: Boolean) {
    val colors = LocalEyeColors.current
    val realtime = state.realtime
    val distanceValid = live && realtime.distanceCm > 0f
    val near = distanceValid && realtime.distanceCm < NEAR_DISTANCE_CM
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MetricTile(
            label = "距离",
            value = if (distanceValid) formatDistanceCm(realtime.distanceCm) else "—",
            hint = when {
                !distanceValid -> "待测"
                near -> "偏近"
                else -> "适宜"
            },
            hintColor = when {
                !distanceValid -> colors.textSecondary
                near -> colors.warning
                else -> colors.success
            },
            modifier = Modifier.weight(1f)
        )
        MetricTile(
            label = "眨眼",
            value = if (live) "${realtime.blinkCount} 次" else "—",
            hint = "每 30 秒计",
            hintColor = colors.textSecondary,
            modifier = Modifier.weight(1f)
        )
        MetricTile(
            label = "时长",
            value = formatMinutes(realtime.durationMin),
            hint = "本次监测",
            hintColor = colors.textSecondary,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    hint: String,
    hintColor: Color,
    modifier: Modifier = Modifier,
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Column(
        modifier = modifier
            .clip(EyeShapes.medium)
            .background(colors.surfaceVariant)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        EyeText(text = label, style = typography.bodySecondary, color = colors.textSecondary)
        AnimatedContent(
            targetState = value,
            transitionSpec = {
                (fadeIn(tween(EyeMotion.MEDIUM)) + slideInVertically(tween(EyeMotion.MEDIUM)) { it / 3 }) togetherWith
                    (fadeOut(tween(EyeMotion.SHORT)) + slideOutVertically(tween(EyeMotion.SHORT)) { -it / 3 })
            },
            label = "读数切换"
        ) { shown ->
            EyeText(text = shown, style = typography.subtitle, color = colors.textPrimary, maxLines = 1)
        }
        EyeText(text = hint, style = typography.chip, color = hintColor, maxLines = 1)
    }
}

@Composable
private fun ChartsSection(state: FatigueUiState) {
    val colors = LocalEyeColors.current
    val samples = state.samples
    // 距离/眨眼只在检测到人脸的样本里有意义；无人脸样本（距离 0）画进去会把曲线拉到谷底
    val activeSamples = remember(samples) { samples.filter { it.distanceCm > 0f } }
    val rt = state.realtime
    val live = state.monitoring && rt.faceDetected
    EyeSectionCard {
        EyeLineChart(
            samples = activeSamples,
            valueOf = { it.distanceCm },
            color = colors.success,
            title = "用眼距离",
            valueText = if (live) formatDistanceCm(rt.distanceCm) else "--"
        )
        Spacer(Modifier.height(16.dp))
        EyeLineChart(
            samples = activeSamples,
            valueOf = { it.blinkCount.toFloat() },
            color = colors.warning,
            title = "眨眼次数",
            valueText = if (live) "${rt.blinkCount} 次" else "--"
        )
        Spacer(Modifier.height(16.dp))
        EyeLineChart(
            samples = samples,
            valueOf = { it.ambientLux },
            color = colors.primary,
            title = "环境光照",
            valueText = if (state.monitoring) String.format(Locale.US, "%.0f lx", rt.ambientLux) else "--"
        )
        Spacer(Modifier.height(16.dp))
        EyeLineChart(
            samples = samples,
            valueOf = { it.durationMin },
            color = colors.primaryDark,
            title = "监测时长",
            valueText = if (state.monitoring) formatMinutes(rt.durationMin) else "--"
        )
        Spacer(Modifier.height(16.dp))
        EyeLineChart(
            samples = samples,
            valueOf = { it.screenBrightness.toFloat() },
            color = colors.textSecondary,
            title = "屏幕亮度",
            valueText = if (state.monitoring) "${rt.screenBrightness}%" else "--"
        )
    }
}

@Composable
private fun ControlsSection(
    state: FatigueUiState,
    onSelectColorTemperature: (ColorTemperatureMode) -> Unit,
    onToggleReminder: () -> Unit,
    onSelectReminderInterval: (Int) -> Unit
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    EyeSectionCard(title = "护眼设置") {
        EyeText(text = "屏幕色温", style = typography.body, color = colors.textPrimary)
        Spacer(Modifier.height(8.dp))
        EyeSegmentedRow(
            options = ColorTemperatureMode.entries.map { SegmentedOption(it, it.label) },
            selected = state.colorTemperatureMode,
            onSelect = onSelectColorTemperature
        )

        Spacer(Modifier.height(20.dp))
        // 整行可点（48dp 触控区），开关本身只做展示，避免出现两个点击目标
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(
                    value = state.reminderEnabled,
                    role = Role.Switch,
                    onValueChange = { onToggleReminder() }
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                EyeText(text = "休息提醒", style = typography.body, color = colors.textPrimary)
                EyeText(
                    text = if (state.reminderEnabled && state.reminderNextTimeText.isNotEmpty()) {
                        "每 ${state.reminderIntervalMinutes} 分钟，下次约 ${state.reminderNextTimeText}"
                    } else {
                        "按间隔提醒你远眺放松"
                    },
                    style = typography.bodySecondary,
                    color = colors.textSecondary
                )
            }
            EyeSwitch(checked = state.reminderEnabled, onCheckedChange = null)
        }
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
        if (state.notice != null) {
            Spacer(Modifier.height(12.dp))
            EyeText(text = state.notice, style = typography.bodySecondary, color = colors.warning)
        }
    }
}

/** 低于该距离视为近距离用眼（与 C++ 评分的 distOptLow 一致）。 */
private const val NEAR_DISTANCE_CM = 30f

private fun formatMinutes(minutes: Float): String = when {
    minutes < 1f -> "<1 分钟"
    minutes < 60f -> String.format(Locale.US, "%.0f 分钟", minutes)
    else -> String.format(Locale.US, "%d 时 %d 分", minutes.toInt() / 60, minutes.toInt() % 60)
}

private fun todayText(): String =
    LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA))

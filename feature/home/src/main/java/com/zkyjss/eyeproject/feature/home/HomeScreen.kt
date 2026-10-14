package com.zkyjss.eyeproject.feature.home

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.designsystem.util.getTopStatusBarPadding
import com.zkyjss.eyeproject.core.designsystem.util.screenHorizontalPaddingLarge
import com.zkyjss.eyeproject.core.ui.component.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onNavigateToTestModel: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onNotificationPermissionResult(it)
    }
    LaunchedEffect(Unit) {
        viewModel.permissionEvents.collect { event ->
            when (event) {
                HomePermissionEvent.Notification -> notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                HomePermissionEvent.ExactAlarm -> runCatching {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                }
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    HomeContent(state, onNavigateToTestModel, viewModel::toggleReminder, viewModel::selectReminderInterval, modifier)
}

@Composable
internal fun HomeContent(
    state: HomeUiState,
    onNavigateToTestModel: () -> Unit,
    onToggleReminder: () -> Unit,
    onSelectReminderInterval: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalEyeColors.current
    Column(
        modifier.fillMaxSize().background(colors.appBackground).verticalScroll(rememberScrollState())
            .padding(top = getTopStatusBarPadding()).screenHorizontalPaddingLarge(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EyeTopBar(title = "眼健康助手", statusText = "首页", statusTone = StatusTone.NEUTRAL,
            subtitle = remember { LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)) },
            leading = { EyeBrandMark(size = 44.dp) })
        ReminderSection(state, onToggleReminder, onSelectReminderInterval)
        EyeDisclosure("开发者工具") {
            EyeButton(text = "模型测试", role = EyeButtonRole.SECONDARY,
                onClick = onNavigateToTestModel, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ReminderSection(state: HomeUiState, onToggleReminder: () -> Unit, onSelectReminderInterval: (Int) -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    EyeSectionCard(title = "护眼设置") {
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

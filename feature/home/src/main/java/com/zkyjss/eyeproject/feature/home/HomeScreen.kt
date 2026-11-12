package com.zkyjss.eyeproject.feature.home

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zkyjss.eyeproject.core.designsystem.theme.EyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.EyeMotion
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.eyecare.EyeCareEngine
import com.zkyjss.eyeproject.core.eyecare.EyeSignal
import com.zkyjss.eyeproject.core.ui.component.*

/** 首页的跳转与系统入口，由 app 的 NavHost 接线（feature 之间不直接依赖）。 */
data class HomeNavigation(
    val onOpenAccount: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    /** 开发者工具（模型测试）；为 null 时首页不显示开发者工具（正式包）。 */
    val onNavigateToTestModel: (() -> Unit)? = null,
    /** 打开某项自测（DRY_EYE / PRESBYOPIA / STRABISMUS） */
    val onOpenTest: (String) -> Unit = {},
    /** 编辑本人资料（id 为空时由资料页新建） */
    val onEditProfile: (String?) -> Unit = {},
    val onOpenTrend: () -> Unit = {},
    val onOpenAdvice: () -> Unit = {},
    /** AI 综合建议对话页 */
    val onOpenChat: () -> Unit = {},
    val onOpenBrightness: () -> Unit = {},
)

/** 首页的数据操作，由 ViewModel 与系统设置入口处理。 */
internal data class HomeActions(
    val toggleReminder: () -> Unit = {},
    val selectReminderInterval: (Int) -> Unit = {},
    val enableReminder: (Int) -> Unit = {},
    val checkIn: (List<String>) -> Unit = {},
    val openUsageAccess: () -> Unit = {},
)

internal const val USAGE_HELP_ANDROID = "需要在系统设置里允许「使用情况访问」，才能统计每天用屏多久、连续用了多久和夜间使用；统计只在本机进行。"

/**
 * 首页（2026-11-08 视觉改版）：日期 +「今日」与右上角账户头像 → 主卡（实心圆环包住的眼健康指数、一句结论、「做 1 分钟护眼操」）→ 今日数据（用屏时长整行带近 7
 * 天小柱图，其余读数两列小卡）→ 护眼工具（护眼操、用眼趋势、亮度与蓝光三张白卡）→ 爱科卡（半身像 +「今天眼睛感觉怎么样？」，快捷记下今日感受）→ 实时检测（未开始时一行，开始后展开取景框）→
 * 休息提醒一行。 与苹果端 HomePage 同一顺序、排版与文案（苹果端没有开发者工具）。
 */
@Composable
fun HomeScreen(
    navigation: HomeNavigation,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val notificationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            viewModel.onNotificationPermissionResult(it)
        }
    LaunchedEffect(Unit) {
        viewModel.permissionEvents.collect { event ->
            when (event) {
                HomePermissionEvent.Notification ->
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                HomePermissionEvent.ExactAlarm ->
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.parse("package:${context.packageName}"),
                            )
                        )
                    }
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val actions =
        HomeActions(
            viewModel::toggleReminder,
            viewModel::selectReminderInterval,
            viewModel::enableReminder,
            viewModel::checkIn,
            openUsageAccess = {
                runCatching { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            },
        )
    HomeContent(state, navigation, actions, modifier) { startRequests ->
        LiveEyeSection(startRequests)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeContent(
    state: HomeUiState,
    navigation: HomeNavigation,
    actions: HomeActions,
    modifier: Modifier = Modifier,
    liveEye: @Composable (startRequests: Int) -> Unit = {},
) {
    BrownHomeOverview(state, navigation, actions, modifier, liveEye)
}

/** 等级配色（文字用）：轻松绿、留意琥珀、偏累红、待感知灰。 */
internal fun EyeColors.levelTone(level: String?): Pair<Color, Color> =
    when (level) {
        "LOW" -> success to successSoft
        "MEDIUM" -> warning to warningSoft
        "HIGH" -> danger to dangerSoft
        else -> textSecondary to neutralSoft
    }

/** 一格读数：名称在上、数值在下（需要留意时名称后带琥珀小圆点）。亮度页等处共用。 */
@Composable
internal fun SignalCell(
    signal: EyeSignal?,
    modifier: Modifier = Modifier,
    label: String = signal?.label.orEmpty(),
) {
    val colors = LocalEyeColors.current
    val attention = signal?.status == "ATTENTION"
    EyeMetric(
        label,
        signal?.value ?: "—",
        modifier,
        attention = attention,
        valueColor =
            if (signal == null || signal.status == "UNAVAILABLE") colors.textTertiary
            else colors.textPrimary,
    )
}

private const val NO_DISCOMFORT = "没有不适"

/** 选择今天的感受：「没有不适」占一整行，其余等宽两列；选了才能保存。与苹果端 FeelingSheet 一致。 */
@Composable
internal fun FeelingDialog(
    initial: List<String>,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) { FeelingPicker(initial, onSave) }
}

/** 选择框本体（弹窗内容，界面检查可单独渲染）。 */
@Composable
internal fun FeelingPicker(initial: List<String>, onSave: (List<String>) -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val haptics = rememberEyeHaptics()
    var selected by remember { mutableStateOf(initial) }
    fun toggle(label: String) {
        val on = label in selected
        selected =
            when {
                label == NO_DISCOMFORT -> if (on) emptyList() else listOf(NO_DISCOMFORT)
                on -> selected - label
                else -> selected - NO_DISCOMFORT + label
            }
    }
    Column(
        Modifier.fillMaxWidth()
            .clip(EyeShapes.hero)
            .background(colors.panelBackground)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        EyeText(
            "今天眼睛感觉怎么样？",
            Modifier.padding(bottom = 6.dp),
            style = typography.title,
            color = colors.textPrimary,
        )
        FeelingOption(NO_DISCOMFORT, NO_DISCOMFORT in selected, Modifier.fillMaxWidth()) {
            toggle(NO_DISCOMFORT)
        }
        EyeCareEngine.SYMPTOMS.chunked(2).forEach { row ->
            // 同一行两格等高：长选项折行时另一格跟着变高
            Row(
                Modifier.height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { label ->
                    FeelingOption(label, label in selected, Modifier.weight(1f).fillMaxHeight()) {
                        toggle(label)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(4.dp))
        EyeButton(
            "保存",
            EyeButtonRole.PRIMARY,
            {
                haptics.success()
                onSave(selected)
            },
            Modifier.fillMaxWidth(),
            enabled = selected.isNotEmpty(),
        )
    }
}

/** 选择框里的一格：等高圆角块，选中时浅主色底 + 主色描边 + 主色字。 */
@Composable
private fun FeelingOption(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalEyeColors.current
    val haptics = rememberEyeHaptics()
    Box(
        modifier
            .heightIn(min = 52.dp)
            .clip(EyeShapes.medium)
            .background(if (selected) colors.primarySoft else colors.surfaceVariant)
            .border(
                1.5.dp,
                if (selected) colors.primary else colors.surfaceVariant,
                EyeShapes.medium,
            )
            .toggleable(selected, role = Role.Checkbox) {
                haptics.toggle(it)
                onClick()
            }
            .padding(horizontal = 6.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        EyeText(
            label,
            style = LocalEyeTypography.current.body,
            color = if (selected) colors.primary else colors.textPrimary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
        )
    }
}

/** 可多选的块：选中时浅主色底 + 主色字。 */
@Composable
internal fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalEyeColors.current
    val haptics = rememberEyeHaptics()
    EyeText(
        label,
        Modifier.clip(EyeShapes.chip)
            .background(if (selected) colors.primarySoft else colors.panelBackground)
            .border(1.dp, if (selected) colors.primary else colors.panelBackground, EyeShapes.chip)
            .toggleable(selected, role = Role.Checkbox) {
                haptics.toggle(it)
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        style = LocalEyeTypography.current.bodySecondary,
        color = if (selected) colors.primary else colors.textPrimary,
    )
}

// endregion

// region 干预（图标与小号按钮）

internal fun glyphFor(kind: String): Pair<EyeGlyph, EyeTileTone> =
    when (kind) {
        "BREAK" -> EyeGlyph.COFFEE to EyeTileTone.SUCCESS
        "REMINDER" -> EyeGlyph.BELL to EyeTileTone.ACCENT
        "BLINK" -> EyeGlyph.EYE to EyeTileTone.BLUE
        "DISTANCE" -> EyeGlyph.RULER to EyeTileTone.PRIMARY
        "BRIGHTNESS" -> EyeGlyph.SUN to EyeTileTone.ACCENT
        "NIGHT" -> EyeGlyph.MOON to EyeTileTone.VIOLET
        "OUTDOOR" -> EyeGlyph.SUN to EyeTileTone.SUCCESS
        "TEST" -> EyeGlyph.DOCUMENT to EyeTileTone.PRIMARY
        "PROFILE" -> EyeGlyph.PERSON to EyeTileTone.PRIMARY
        "USAGE_ACCESS" -> EyeGlyph.HOURGLASS to EyeTileTone.VIOLET
        "LIVE" -> EyeGlyph.CAMERA to EyeTileTone.BLUE
        else -> EyeGlyph.INFO to EyeTileTone.NEUTRAL
    }

/** 小号胶囊按钮（行右侧的操作）：[primary] 为浅主色底 + 主色字，否则浅填充底 + 正文色。 */
@Composable
internal fun PillButton(label: String, primary: Boolean = true, onClick: () -> Unit) {
    val colors = LocalEyeColors.current
    val haptics = rememberEyeHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by
        animateFloatAsState(if (pressed) 0.94f else 1f, EyeMotion.pressSpring(), label = "胶囊按压")
    EyeText(
        label,
        Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(if (primary) colors.primarySoft else colors.surfaceVariant)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) {
                haptics.tap()
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 7.dp),
        style = LocalEyeTypography.current.bodySecondary,
        fontWeight = FontWeight.SemiBold,
        color = if (primary) colors.primary else colors.textPrimary,
    )
}

// endregion

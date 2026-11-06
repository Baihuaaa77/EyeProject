package com.zkyjss.eyeproject.feature.home

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
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
import com.zkyjss.eyeproject.core.designsystem.theme.EyeSpacing
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.designsystem.theme.VividTone
import com.zkyjss.eyeproject.core.designsystem.theme.vivid
import com.zkyjss.eyeproject.core.designsystem.util.getTopStatusBarPadding
import com.zkyjss.eyeproject.core.designsystem.util.screenHorizontalPaddingLarge
import com.zkyjss.eyeproject.core.eyecare.EyeTip
import com.zkyjss.eyeproject.core.eyecare.EyeCareEngine
import com.zkyjss.eyeproject.core.eyecare.EyeCareReport
import com.zkyjss.eyeproject.core.eyecare.EyeSignal
import com.zkyjss.eyeproject.core.eyecare.LoopStage
import com.zkyjss.eyeproject.core.ui.component.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 首页的跳转与系统入口，由 app 的 NavHost 接线（feature 之间不直接依赖）。 */
data class HomeNavigation(
    val onOpenAccount: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onNavigateToTestModel: () -> Unit = {},
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
 * 首页：「今日」与日期（右上角账户、白天 / 夜晚、系统设置）→ 大号仪表圈包住眼健康指数（不加卡片框）→ 基础数据 →
 * 功能卡（护眼操、用眼趋势、亮度与蓝光三个彩色方块 + 整行 AI 主动问诊）→ 今日感受 → 休息提醒 → 实时检测（始终展开）。不分小节标题。
 * 与苹果端 HomePage 同一顺序、排版与文案（苹果端没有开发者工具）。
 */
@Composable
fun HomeScreen(navigation: HomeNavigation, modifier: Modifier = Modifier, viewModel: HomeViewModel = hiltViewModel()) {
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
    val actions = HomeActions(viewModel::toggleReminder, viewModel::selectReminderInterval, viewModel::enableReminder,
        viewModel::checkIn,
        openUsageAccess = { runCatching { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } })
    HomeContent(state, navigation, actions, modifier) { startRequests -> LiveEyeSection(startRequests) }
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
    val colors = LocalEyeColors.current
    val report = state.report
    var exerciseOpen by rememberSaveable { mutableStateOf(false) }
    var liveRequests by rememberSaveable { mutableIntStateOf(0) }
    val live = remember { BringIntoViewRequester() }
    val basic = remember { BringIntoViewRequester() }
    var scrollTarget by remember { mutableStateOf<BringIntoViewRequester?>(null) }
    LaunchedEffect(scrollTarget) { scrollTarget?.bringIntoView(); scrollTarget = null }
    Column(
        modifier.fillMaxSize().background(colors.appBackground).verticalScroll(rememberScrollState()).eyeBackdrop(colors)
            .padding(top = getTopStatusBarPadding()).screenHorizontalPaddingLarge(),
        verticalArrangement = Arrangement.spacedBy(com.zkyjss.eyeproject.core.designsystem.theme.EyeSpacing.cardGap),
    ) {
        EyePageHeader("今日", navigation.onOpenSettings, onOpenAccount = navigation.onOpenAccount,
            subtitle = remember { LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)) })
        EyeCareHero(report)
        BasicDataCard(state, actions, Modifier.bringIntoViewRequester(basic))
        FeatureCard(navigation) { exerciseOpen = true }
        CheckInCard(report, actions.checkIn)
        ReminderCard(state, actions)
        LiveCard(Modifier.bringIntoViewRequester(live)) { liveEye(liveRequests) }
        EyeDisclosure("开发者工具") {
            EyeButton(text = "模型测试", role = EyeButtonRole.SECONDARY, onClick = navigation.onNavigateToTestModel, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(24.dp))
    }
    if (exerciseOpen) EyeExercisePage({ exerciseOpen = false })
}


// region 今日用眼

/** 等级配色：轻松绿、留意琥珀、偏累红、待感知灰。 */
internal fun EyeColors.levelTone(level: String?): Pair<Color, Color> = when (level) {
    "LOW" -> success to successSoft
    "MEDIUM" -> warning to warningSoft
    "HIGH" -> danger to dangerSoft
    else -> textSecondary to neutralSoft
}

/** 负荷分圆环用更明亮的等级色（文字用的 [levelTone] 偏深）。 */
internal fun EyeColors.ringTone(level: String?): Color = when (level) {
    "LOW" -> success
    "MEDIUM" -> accent
    "HIGH" -> danger
    else -> borderSubtle
}

/**
 * 今日用眼：不加卡片框，页面正中一只大号光流仪表圈包住眼健康指数，是首页的视觉中心；
 * 眼健康指数 = 100 − 用眼负荷分，圈越满越健康。圈内自上而下居中：「眼健康指数」、大号分数、等级徽标。
 * 与苹果端 HeroCard 一致。
 */
@Composable
private fun EyeCareHero(report: EyeCareReport?) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val (strong, _) = colors.levelTone(report?.level)
    val index = report?.score?.let(::healthIndex)
    Box(Modifier.fillMaxWidth().padding(vertical = EyeSpacing.xs), contentAlignment = Alignment.Center) {
        EyeProgressRing(index?.let { it / 100f }, Modifier.size(296.dp), strokeWidth = 16.dp,
            description = index?.let { "今日眼健康指数 $it 分，${report.label}" } ?: "今日眼健康指数暂无数据") {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(EyeSpacing.xs)) {
                // 自上而下居中：「眼健康指数」→ 大号分数 → 等级徽标
                EyeText("眼健康指数", style = typography.subtitle, color = colors.textSecondary)
                EyeText(buildAnnotatedString {
                    append(index?.toString() ?: "—")
                    withStyle(SpanStyle(fontSize = typography.title.fontSize, fontWeight = FontWeight.SemiBold, color = colors.textTertiary,
                        letterSpacing = 0.sp)) { append("/100") }
                }, style = typography.display, color = colors.textPrimary, maxLines = 1)
                val (fill, ink) = colors.statusFill(strong)
                EyeText(report?.label ?: "读取中", style = typography.body, color = ink, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(EyeShapes.chip).background(fill).padding(horizontal = 14.dp, vertical = 4.dp))
            }
        }
    }
}

// endregion

// region 今日感受（反馈）

private const val NO_DISCOMFORT = "没有不适"

/** 今日感受：卡片只显示已记下的感受（标签）；点「记录」/「修改」弹出选择框。与苹果端 CheckInCard 一致。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CheckInCard(report: EyeCareReport?, onCheckIn: (List<String>) -> Unit) {
    val colors = LocalEyeColors.current
    val checked = report?.checkedInToday == true
    val symptoms = report?.todaySymptoms.orEmpty()
    var editing by rememberSaveable { mutableStateOf(false) }
    EyeCard(Modifier.animateContentSize()) {
        CardHeader("今日感受", EyeGlyph.HEART, EyeTileTone.PRIMARY) {
            PillButton(if (checked) "修改" else "记录", primary = !checked) { editing = true }
        }
        if (checked) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            symptoms.ifEmpty { listOf(NO_DISCOMFORT) }.forEach { FeelingTag(it, attention = symptoms.isNotEmpty()) }
        }
    }
    if (editing) FeelingDialog(
        initial = if (checked) symptoms.ifEmpty { listOf(NO_DISCOMFORT) } else emptyList(),
        onSave = { onCheckIn(it - NO_DISCOMFORT); editing = false },
        onDismiss = { editing = false },
    )
}

/** 已记下的一项感受：主色浅底胶囊。 */
@Composable
private fun FeelingTag(label: String, attention: Boolean) {
    val colors = LocalEyeColors.current
    EyeText(label, Modifier.clip(EyeShapes.chip).background(colors.primarySoft)
        .padding(horizontal = 12.dp, vertical = 6.dp),
        style = LocalEyeTypography.current.body, color = colors.primary, fontWeight = FontWeight.SemiBold)
}

/** 选择今天的感受：「没有不适」占一整行，其余等宽两列；选了才能保存。与苹果端 FeelingSheet 一致。 */
@Composable
internal fun FeelingDialog(initial: List<String>, onSave: (List<String>) -> Unit, onDismiss: () -> Unit) {
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
        selected = when {
            label == NO_DISCOMFORT -> if (on) emptyList() else listOf(NO_DISCOMFORT)
            on -> selected - label
            else -> selected - NO_DISCOMFORT + label
        }
    }
    run {
        Column(Modifier.fillMaxWidth().clip(EyeShapes.panel).background(colors.panelBackground).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            EyeText("今天眼睛感觉怎么样？", Modifier.padding(bottom = 6.dp), style = typography.title, color = colors.textPrimary)
            FeelingOption(NO_DISCOMFORT, NO_DISCOMFORT in selected, Modifier.fillMaxWidth()) { toggle(NO_DISCOMFORT) }
            EyeCareEngine.SYMPTOMS.chunked(2).forEach { row ->
                // 同一行两格等高：长选项折行时另一格跟着变高
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { label -> FeelingOption(label, label in selected, Modifier.weight(1f).fillMaxHeight()) { toggle(label) } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(4.dp))
            EyeButton("保存", EyeButtonRole.PRIMARY, { haptics.success(); onSave(selected) }, Modifier.fillMaxWidth(),
                enabled = selected.isNotEmpty())
        }
    }
}

/** 选择框里的一格：等高圆角块，选中时浅主色底 + 主色描边 + 主色字。 */
@Composable
private fun FeelingOption(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalEyeColors.current
    val haptics = rememberEyeHaptics()
    Box(modifier.heightIn(min = 52.dp).clip(EyeShapes.medium)
        .background(if (selected) colors.primarySoft else colors.appBackground)
        .border(1.5.dp, if (selected) colors.primary else colors.appBackground, EyeShapes.medium)
        .toggleable(selected, role = Role.Checkbox) { haptics.toggle(it); onClick() }
        .padding(horizontal = 6.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        EyeText(label, style = LocalEyeTypography.current.body, color = if (selected) colors.primary else colors.textPrimary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, textAlign = TextAlign.Center)
    }
}

/** 可多选的胶囊：选中时浅主色底 + 主色字。 */
@Composable
internal fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalEyeColors.current
    val haptics = rememberEyeHaptics()
    EyeText(label, Modifier.clip(EyeShapes.chip)
        .background(if (selected) colors.primarySoft else colors.surfaceVariant)
        .border(1.dp, if (selected) colors.primary else colors.surfaceVariant, EyeShapes.chip)
        .toggleable(selected, role = Role.Checkbox) { haptics.toggle(it); onClick() }
        .padding(horizontal = 14.dp, vertical = 9.dp),
        style = LocalEyeTypography.current.body, color = if (selected) colors.primary else colors.textPrimary)
}

// endregion

// region 干预（图标与小号按钮）

internal fun glyphFor(kind: String): Pair<EyeGlyph, EyeTileTone> = when (kind) {
    "BREAK" -> EyeGlyph.COFFEE to EyeTileTone.SUCCESS
    "REMINDER" -> EyeGlyph.BELL to EyeTileTone.ACCENT
    "BLINK" -> EyeGlyph.EYE to EyeTileTone.PRIMARY
    "DISTANCE" -> EyeGlyph.RULER to EyeTileTone.BLUE
    "BRIGHTNESS" -> EyeGlyph.SUN to EyeTileTone.ACCENT
    "NIGHT" -> EyeGlyph.MOON to EyeTileTone.BLUE
    "OUTDOOR" -> EyeGlyph.SUN to EyeTileTone.SUCCESS
    "TEST" -> EyeGlyph.DOCUMENT to EyeTileTone.PRIMARY
    "PROFILE" -> EyeGlyph.PERSON to EyeTileTone.PRIMARY
    "USAGE_ACCESS" -> EyeGlyph.HOURGLASS to EyeTileTone.BLUE
    "LIVE" -> EyeGlyph.CAMERA to EyeTileTone.PRIMARY
    else -> EyeGlyph.INFO to EyeTileTone.NEUTRAL
}

/** 小号胶囊按钮（列表行右侧的操作）。 */
@Composable
internal fun PillButton(label: String, primary: Boolean = true, onClick: () -> Unit) {
    val colors = LocalEyeColors.current
    val haptics = rememberEyeHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, EyeMotion.pressSpring(), label = "胶囊按压")
    EyeText(label, Modifier.graphicsLayer { scaleX = scale; scaleY = scale }.clip(EyeShapes.chip)
        .then(if (primary) Modifier.background(colors.buttonBrush) else Modifier.background(colors.surfaceVariant))
        .clickable(interactionSource = interaction, indication = null, role = Role.Button) { haptics.tap(); onClick() }
        .padding(horizontal = 14.dp, vertical = 8.dp),
        style = LocalEyeTypography.current.chip, color = if (primary) colors.onPrimary else colors.textPrimary, fontWeight = FontWeight.Bold)
}

// endregion

// region 基础数据与实时检测

/** 一格读数：名称在上、数值在下（需要留意时名称后带琥珀小圆点）。 */
@Composable
internal fun SignalCell(signal: EyeSignal?, modifier: Modifier = Modifier, label: String = signal?.label.orEmpty()) {
    val colors = LocalEyeColors.current
    val attention = signal?.status == "ATTENTION"
    EyeMetric(label, signal?.value ?: "—", modifier, attention = attention,
        valueColor = if (signal == null || signal.status == "UNAVAILABLE") colors.textTertiary else colors.textPrimary)
}

@Composable
private fun CardHeader(title: String, glyph: EyeGlyph, tone: EyeTileTone, trailing: @Composable RowScope.() -> Unit = {}) {
    EyeCategoryHeader(title, glyph, tone, trailing = trailing)
}

/** 基础数据：今日用屏、最长连续、休息次数、夜间使用、环境光、屏幕亮度（只显示本机系统提供的项）；未授权用屏统计时可去开启。 */
@Composable
private fun BasicDataCard(state: HomeUiState, actions: HomeActions, modifier: Modifier) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val signals = state.report?.signals.orEmpty().filter { it.group == "BASIC" }
    if (signals.isEmpty()) return
    EyeCard(modifier.animateContentSize()) {
        CardHeader("基础数据", EyeGlyph.HOURGLASS, EyeTileTone.BLUE)
        SignalGrid(signals, columns = 2)  // 读数为 26 号大字，两列才放得下「6小时25分」
        if (state.usageAccess == EyeCareEngine.ACCESS_DENIED) Row(verticalAlignment = Alignment.CenterVertically) {
            EyeText("开启用屏统计，自动记录每天用屏", Modifier.weight(1f), style = typography.body, color = colors.textSecondary)
            EyeHelpButton("用屏统计", USAGE_HELP_ANDROID)
            PillButton("去开启", onClick = actions.openUsageAccess)
        }
    }
}

/** 读数网格：每行 [columns] 项，同行竖线分隔、行间横线分隔。 */
@Composable
internal fun SignalGrid(signals: List<EyeSignal>, columns: Int) {
    val colors = LocalEyeColors.current
    Column {
        signals.chunked(columns).forEachIndexed { i, row ->
            if (i > 0) Box(Modifier.fillMaxWidth().padding(vertical = 12.dp).height(1.dp).background(colors.borderSubtle))
            val cells: List<@Composable (Modifier) -> Unit> = row.map { signal -> @Composable { m: Modifier -> SignalCell(signal, m) } } +
                List(columns - row.size) { @Composable { m: Modifier -> Spacer(m) } }
            EyeMetricRow(items = cells)
        }
    }
}

/** 实时检测（始终展开：正方形取景框 + 开始 / 停止按钮，检测中显示画面与眨眼、距离结果）。 */
@Composable
private fun LiveCard(modifier: Modifier, live: @Composable () -> Unit) {
    EyeCard(modifier.animateContentSize(), verticalArrangement = Arrangement.Top) { live() }
}

// endregion

// region 护眼操、提醒与服务入口

/** 休息提醒：开关（下次提醒时间）+ 间隔。不再给出建议间隔。 */
@Composable
private fun ReminderCard(state: HomeUiState, actions: HomeActions) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    EyeCard {
        // 整行可点（48dp 触控区），开关本身只做展示，避免出现两个点击目标
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(state.reminderEnabled, role = Role.Switch) { actions.toggleReminder() },
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CardHeader("休息提醒", EyeGlyph.BELL, EyeTileTone.ACCENT)
                EyeText(if (state.reminderEnabled && state.reminderNextTimeText.isNotEmpty()) "每 ${state.reminderIntervalMinutes} 分钟，下次约 ${state.reminderNextTimeText}"
                    else "按间隔提醒你远眺放松", style = typography.body, color = colors.textPrimary)
            }
            EyeSwitch(checked = state.reminderEnabled, onCheckedChange = null)
        }
        EyeSegmentedRow(listOf(SegmentedOption(20, "20分钟"), SegmentedOption(30, "30分钟"), SegmentedOption(60, "60分钟")),
            state.reminderIntervalMinutes, actions.selectReminderInterval)
        state.notice?.let { EyeText(it, style = typography.bodySecondary, color = colors.warning) }
    }
}

/**
 * 功能卡：一张卡里上排三个彩色方块（护眼操薄荷绿、用眼趋势紫、亮度与蓝光珊瑚红），下面整行蓝色的 AI 主动问诊。
 * 色块上文字与图标一律白色，靠整块颜色做视觉区分。与苹果端 FeatureCard 一致。
 */
@Composable
private fun FeatureCard(navigation: HomeNavigation, onOpenExercise: () -> Unit) {
    val vivid = LocalEyeColors.current.vivid
    EyeCard(contentPadding = 12.dp, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            VividTile("护眼操", EyeGlyph.EYE, vivid.mint, Modifier.weight(1f).fillMaxHeight(), onOpenExercise)
            VividTile("用眼趋势", EyeGlyph.CHART, vivid.violet, Modifier.weight(1f).fillMaxHeight(), navigation.onOpenTrend)
            VividTile("亮度与蓝光", EyeGlyph.SUN, vivid.red, Modifier.weight(1f).fillMaxHeight(), navigation.onOpenBrightness)
        }
        ChatEntryCard(navigation.onOpenChat)
    }
}

/** 功能色块：渐变底 + 左上白色图标块 + 白色名称（不加说明小字），右下角放大的半透明图标做装饰；按下轻微缩小。 */
@Composable
private fun VividTile(title: String, glyph: EyeGlyph, tone: VividTone, modifier: Modifier, onClick: () -> Unit) {
    val typography = LocalEyeTypography.current
    val on = LocalEyeColors.current.vivid.onVivid
    val haptics = rememberEyeHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, EyeMotion.pressSpring(), label = "功能色块按压")
    Box(modifier.heightIn(min = 96.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(EyeShapes.large).background(tone.brush)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button) { haptics.tap(); onClick() }) {
        EyeGlyphIcon(glyph, Modifier.align(Alignment.BottomEnd).offset(x = 14.dp, y = 14.dp).size(64.dp), on.copy(alpha = 0.16f))
        Column(Modifier.padding(12.dp)) {
            Box(Modifier.size(38.dp).clip(EyeShapes.medium).background(on.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                EyeGlyphIcon(glyph, Modifier.size(26.dp), on)
            }
            Spacer(Modifier.height(10.dp))
            EyeText(title, style = typography.body, color = on, fontWeight = FontWeight.Bold)
        }
    }
}

// endregion

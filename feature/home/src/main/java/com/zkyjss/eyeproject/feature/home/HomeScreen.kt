package com.zkyjss.eyeproject.feature.home

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zkyjss.eyeproject.core.designsystem.R
import com.zkyjss.eyeproject.core.designsystem.theme.EyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.EyeMotion
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.EyeSpacing
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.designsystem.util.getTopStatusBarPadding
import com.zkyjss.eyeproject.core.designsystem.util.screenHorizontalPaddingLarge
import com.zkyjss.eyeproject.core.eyecare.EyeCareEngine
import com.zkyjss.eyeproject.core.eyecare.EyeCareReport
import com.zkyjss.eyeproject.core.eyecare.EyeSignal
import com.zkyjss.eyeproject.core.ui.component.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

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
 * 首页（2026-11-08 视觉改版）：日期 +「今日」与右上角账户头像 → 主卡（实心圆环包住的眼健康指数、一句结论、「做 1 分钟护眼操」）→
 * 今日数据（用屏时长整行带近 7 天小柱图，其余读数两列小卡）→ 护眼工具（护眼操、用眼趋势、亮度与蓝光三张白卡）→
 * 爱科卡（半身像 +「今天眼睛感觉怎么样？」，快捷记下今日感受）→ 实时检测（未开始时一行，开始后展开取景框）→ 休息提醒一行。
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
        verticalArrangement = Arrangement.spacedBy(EyeSpacing.cardGap),
    ) {
        EyePageHeader("今日", null, onOpenAccount = navigation.onOpenAccount,
            subtitle = remember { LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)) })
        EyeCareHero(report) { exerciseOpen = true }
        TodayData(state, actions, Modifier.bringIntoViewRequester(basic))
        SectionTitle("护眼工具")
        ToolRow(state, navigation) { exerciseOpen = true }
        EyechoCard(report, navigation.onOpenChat, actions.checkIn)
        LiveCard(Modifier.bringIntoViewRequester(live)) { liveEye(liveRequests) }
        ReminderCard(state, actions)
        navigation.onNavigateToTestModel?.let { openModel ->
            EyeDisclosure("开发者工具") {
                EyeButton(text = "模型测试", role = EyeButtonRole.SECONDARY, onClick = openModel, modifier = Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    if (exerciseOpen) EyeExercisePage({ exerciseOpen = false })
}

/** 分区标题（20 号半粗），与上一块内容拉开 [EyeSpacing.sectionGap]。 */
@Composable
private fun SectionTitle(text: String) {
    EyeText(text, Modifier.padding(top = EyeSpacing.sectionGap - EyeSpacing.cardGap, start = 4.dp),
        style = LocalEyeTypography.current.title, color = LocalEyeColors.current.textPrimary)
}

// region 今日用眼

/** 等级配色（文字用）：轻松绿、留意琥珀、偏累红、待感知灰。 */
internal fun EyeColors.levelTone(level: String?): Pair<Color, Color> = when (level) {
    "LOW" -> success to successSoft
    "MEDIUM" -> warning to warningSoft
    "HIGH" -> danger to dangerSoft
    else -> textSecondary to neutralSoft
}

/** 圆环与状态点用的等级色（琥珀用更亮的 [EyeColors.accent]）。 */
internal fun EyeColors.ringTone(level: String?): Color = when (level) {
    "LOW" -> success
    "MEDIUM" -> accent
    "HIGH" -> danger
    else -> textTertiary
}

/**
 * 主卡：左侧实心圆环（等级色，圈越满越健康）中间一只眼睛，右侧「眼健康指数」、大号分数与等级（小圆点 + 等级色文字）；
 * 下面一句结论（[EyeCareReport.headline]）和主按钮「做 1 分钟护眼操」。眼健康指数 = 100 − 用眼负荷分。与苹果端 HeroCard 一致。
 */
@Composable
private fun EyeCareHero(report: EyeCareReport?, onExercise: () -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val (strong, _) = colors.levelTone(report?.level)
    val ring = colors.ringTone(report?.level)
    val index = report?.score?.let(::healthIndex)
    EyeHeroCard(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EyeProgressRing(index?.let { it / 100f }, Modifier.size(124.dp), strokeWidth = 14.dp, tint = ring,
                description = index?.let { "今日眼健康指数 $it 分，${report.label}" } ?: "今日眼健康指数暂无数据") {
                EyeGlyphIcon(EyeGlyph.EYE, Modifier.size(30.dp), ring)
            }
            Spacer(Modifier.width(22.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                EyeText("眼健康指数", style = typography.chip, color = colors.textSecondary)
                EyeText(buildAnnotatedString {
                    append(index?.toString() ?: "—")
                    withStyle(SpanStyle(fontSize = typography.subtitle.fontSize, fontWeight = FontWeight.Medium, color = colors.textTertiary,
                        letterSpacing = 0.sp)) { append(" /100") }
                }, style = typography.display, color = colors.textPrimary, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(ring))
                    Spacer(Modifier.width(6.dp))
                    EyeText(report?.label ?: "读取中", style = typography.subtitle, color = strong)
                }
            }
        }
        report?.headline?.takeIf { it.isNotBlank() }?.let {
            EyeText(it, style = typography.body, color = colors.textSecondary)
        }
        EyeButton("做 1 分钟护眼操", EyeButtonRole.PRIMARY, onExercise, Modifier.fillMaxWidth())
    }
}

// endregion

// region 今日数据

/** 各读数在首页的类别图标与类别色。 */
@Composable
private fun signalCategory(key: String): Pair<EyeGlyph, Color> {
    val c = LocalEyeColors.current
    return when (key) {
        "screen" -> EyeGlyph.HOURGLASS to c.screen
        "longest" -> EyeGlyph.CLOCK to c.screen
        "night" -> EyeGlyph.MOON to c.screen
        "breaks" -> EyeGlyph.COFFEE to c.success
        "light", "brightness" -> EyeGlyph.SUN to c.sun
        else -> EyeGlyph.INFO to c.textSecondary
    }
}

/**
 * 今日数据：用屏时长整行（右侧近 7 天小柱图，今天实色、其余变淡），其余基础读数两列小卡（屏幕亮度放在「亮度与蓝光」工具上）。
 * 只显示本机系统提供的项；未授权用屏统计时在最上面一行「去开启」。
 */
@Composable
private fun TodayData(state: HomeUiState, actions: HomeActions, modifier: Modifier) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val signals = state.report?.signals.orEmpty().filter { it.group == "BASIC" && it.key != "brightness" }
    val denied = state.usageAccess == EyeCareEngine.ACCESS_DENIED
    if (signals.isEmpty() && !denied) return
    SectionTitle("今日数据")
    Column(modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(EyeSpacing.cardGap)) {
        if (denied) EyeCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EyeText("开启用屏统计，自动记录每天用屏", Modifier.weight(1f), style = typography.body, color = colors.textSecondary)
                EyeHelpButton("用屏统计", USAGE_HELP_ANDROID)
                PillButton("去开启", onClick = actions.openUsageAccess)
            }
        }
        signals.firstOrNull { it.key == "screen" }?.let { ScreenTimeCard(it, state.report?.trend?.days?.takeLast(7)?.map { d -> d.screenMinutes }.orEmpty()) }
        signals.filter { it.key != "screen" }.chunked(2).forEach { row ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(EyeSpacing.cardGap)) {
                row.forEach { SignalCard(it, Modifier.weight(1f).fillMaxHeight()) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** 读数卡顶行：类别标签，需要留意时右侧琥珀「留意」。 */
@Composable
private fun SignalHeader(signal: EyeSignal) {
    val (glyph, tint) = signalCategory(signal.key)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        EyeCategoryLabel(signal.label, glyph, tint, Modifier.weight(1f))
        if (signal.status == "ATTENTION") EyeText("留意", style = LocalEyeTypography.current.chip, color = LocalEyeColors.current.warning)
    }
}

@Composable
private fun SignalValue(signal: EyeSignal, modifier: Modifier = Modifier) {
    val colors = LocalEyeColors.current
    EyeText(eyeReading(signal.value), modifier, style = LocalEyeTypography.current.metricValue,
        color = if (signal.status == "UNAVAILABLE") colors.textTertiary else colors.textPrimary, maxLines = 1)
}

/** 用屏时长：整行卡，左边读数、右边近 7 天小柱图。 */
@Composable
private fun ScreenTimeCard(signal: EyeSignal, week: List<Int?>) {
    val tint = signalCategory(signal.key).second
    EyeCard(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SignalHeader(signal)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            SignalValue(signal, Modifier.weight(1f))
            val max = week.maxOfOrNull { it ?: 0 }?.coerceAtLeast(1) ?: 1
            if (week.isNotEmpty()) Row(Modifier.height(44.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                week.forEachIndexed { i, minutes ->
                    val fraction = ((minutes ?: 0).toFloat() / max).coerceIn(0.06f, 1f)
                    Box(Modifier.width(8.dp).fillMaxHeight(fraction).clip(CircleShape)
                        .background(if (i == week.lastIndex) tint else tint.copy(alpha = 0.28f)))
                }
            }
        }
    }
}

/** 一格读数小卡：类别标签在上、大号读数在下。 */
@Composable
private fun SignalCard(signal: EyeSignal, modifier: Modifier) {
    EyeCard(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SignalHeader(signal)
        SignalValue(signal)
    }
}

/** 一格读数：名称在上、数值在下（需要留意时名称后带琥珀小圆点）。亮度页等处共用。 */
@Composable
internal fun SignalCell(signal: EyeSignal?, modifier: Modifier = Modifier, label: String = signal?.label.orEmpty()) {
    val colors = LocalEyeColors.current
    val attention = signal?.status == "ATTENTION"
    EyeMetric(label, signal?.value ?: "—", modifier, attention = attention,
        valueColor = if (signal == null || signal.status == "UNAVAILABLE") colors.textTertiary else colors.textPrimary)
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

// endregion

// region 护眼工具与爱科

/** 护眼工具：三张等高白卡（浅色块图标 + 名称 + 一项数据）。 */
@Composable
private fun ToolRow(state: HomeUiState, navigation: HomeNavigation, onOpenExercise: () -> Unit) {
    val colors = LocalEyeColors.current
    val brightness = state.report?.signals?.firstOrNull { it.key == "brightness" }?.value?.takeIf { it != "—" }
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(EyeSpacing.cardGap)) {
        ToolTile("护眼操", "约 1 分钟", EyeGlyph.EYE, colors.successSoft, colors.success, Modifier.weight(1f).fillMaxHeight(), onOpenExercise)
        ToolTile("用眼趋势", "近 7 天", EyeGlyph.CHART, colors.screenSoft, colors.screen, Modifier.weight(1f).fillMaxHeight(), navigation.onOpenTrend)
        ToolTile("亮度与蓝光", brightness?.let { "亮度 $it" } ?: "护眼设置", EyeGlyph.SUN, colors.sunSoft, colors.sun,
            Modifier.weight(1f).fillMaxHeight(), navigation.onOpenBrightness)
    }
}

/** 按下轻微缩小的可点卡片面。 */
@Composable
private fun Modifier.pressableCard(shape: androidx.compose.ui.graphics.Shape, onClick: () -> Unit): Modifier {
    val colors = LocalEyeColors.current
    val haptics = rememberEyeHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, EyeMotion.pressSpring(), label = "卡片按压")
    return graphicsLayer { scaleX = scale; scaleY = scale }.clip(shape).background(colors.panelBackground)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button) { haptics.tap(); onClick() }
}

@Composable
private fun ToolTile(title: String, meta: String, glyph: EyeGlyph, soft: Color, ink: Color, modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Column(modifier.pressableCard(EyeShapes.card, onClick).padding(horizontal = 12.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(40.dp).clip(EyeShapes.small).background(soft), contentAlignment = Alignment.Center) {
            EyeGlyphIcon(glyph, Modifier.size(22.dp), ink)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            EyeText(title, style = typography.body, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, maxLines = 1)
            EyeText(meta, style = typography.bodySecondary, color = colors.textSecondary, maxLines = 1)
        }
    }
}

private const val NO_DISCOMFORT = "没有不适"

/** 首页爱科卡的标签。 */
internal const val EYECHO_CARD_LABEL = "爱科 · AI 问诊"

/**
 * 爱科卡：右侧爱科半身像（浅紫圆形衬底），左侧「今天眼睛感觉怎么样？」与快捷感受（点一下就记下今日感受，「更多」弹出完整选择）；
 * 已记下时显示记下的感受与「修改」。点卡片其余位置进入与爱科的对话。与苹果端 EyechoCard 一致。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EyechoCard(report: EyeCareReport?, onOpenChat: () -> Unit, onCheckIn: (List<String>) -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val checked = report?.checkedInToday == true
    val symptoms = report?.todaySymptoms.orEmpty()
    var editing by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().heightIn(min = 180.dp).pressableCard(EyeShapes.hero, onOpenChat)) {
        Box(Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = 18.dp).size(136.dp).clip(CircleShape).background(colors.violetSoft))
        Image(painterResource(R.drawable.eyecho_half), contentDescription = "爱科", contentScale = ContentScale.Fit,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 14.dp, y = 6.dp).size(width = 152.dp, height = 213.dp))
        Column(Modifier.padding(18.dp).fillMaxWidth(0.62f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EyeCategoryLabel(EYECHO_CARD_LABEL, EyeGlyph.CHAT, colors.violet)
            EyeText(if (checked) "今天的感受已记下" else "今天眼睛感觉怎么样？", style = typography.subtitle, color = colors.textPrimary)
            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (checked) {
                    symptoms.ifEmpty { listOf(NO_DISCOMFORT) }.forEach { FeelingTag(it) }
                    QuickChip("修改") { editing = true }
                } else {
                    listOf(EyeCareEngine.SYMPTOMS[0], EyeCareEngine.SYMPTOMS[1], NO_DISCOMFORT).forEach { label ->
                        QuickChip(label) { onCheckIn(listOf(label) - NO_DISCOMFORT) }
                    }
                    QuickChip("更多") { editing = true }
                }
            }
        }
    }
    if (editing) FeelingDialog(
        initial = if (checked) symptoms.ifEmpty { listOf(NO_DISCOMFORT) } else emptyList(),
        onSave = { onCheckIn(it - NO_DISCOMFORT); editing = false },
        onDismiss = { editing = false },
    )
}

/** 爱科卡里的快捷感受：浅填充小块。 */
@Composable
private fun QuickChip(label: String, onClick: () -> Unit) {
    val colors = LocalEyeColors.current
    val haptics = rememberEyeHaptics()
    EyeText(label, Modifier.clip(EyeShapes.chip).background(colors.surfaceVariant)
        .clickable(role = Role.Button) { haptics.tap(); onClick() }.padding(horizontal = 10.dp, vertical = 6.dp),
        style = LocalEyeTypography.current.chip, fontWeight = FontWeight.Medium, color = colors.textPrimary)
}

/** 已记下的一项感受：主色浅底小块。 */
@Composable
private fun FeelingTag(label: String) {
    val colors = LocalEyeColors.current
    EyeText(label, Modifier.clip(EyeShapes.chip).background(colors.primarySoft).padding(horizontal = 10.dp, vertical = 6.dp),
        style = LocalEyeTypography.current.chip, color = colors.primary)
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
    Column(Modifier.fillMaxWidth().clip(EyeShapes.hero).background(colors.panelBackground).padding(24.dp),
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

/** 选择框里的一格：等高圆角块，选中时浅主色底 + 主色描边 + 主色字。 */
@Composable
private fun FeelingOption(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalEyeColors.current
    val haptics = rememberEyeHaptics()
    Box(modifier.heightIn(min = 52.dp).clip(EyeShapes.medium)
        .background(if (selected) colors.primarySoft else colors.surfaceVariant)
        .border(1.5.dp, if (selected) colors.primary else colors.surfaceVariant, EyeShapes.medium)
        .toggleable(selected, role = Role.Checkbox) { haptics.toggle(it); onClick() }
        .padding(horizontal = 6.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        EyeText(label, style = LocalEyeTypography.current.body, color = if (selected) colors.primary else colors.textPrimary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, textAlign = TextAlign.Center)
    }
}

/** 可多选的块：选中时浅主色底 + 主色字。 */
@Composable
internal fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalEyeColors.current
    val haptics = rememberEyeHaptics()
    EyeText(label, Modifier.clip(EyeShapes.chip)
        .background(if (selected) colors.primarySoft else colors.panelBackground)
        .border(1.dp, if (selected) colors.primary else colors.panelBackground, EyeShapes.chip)
        .toggleable(selected, role = Role.Checkbox) { haptics.toggle(it); onClick() }
        .padding(horizontal = 14.dp, vertical = 9.dp),
        style = LocalEyeTypography.current.bodySecondary, color = if (selected) colors.primary else colors.textPrimary)
}

// endregion

// region 干预（图标与小号按钮）

internal fun glyphFor(kind: String): Pair<EyeGlyph, EyeTileTone> = when (kind) {
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
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, EyeMotion.pressSpring(), label = "胶囊按压")
    EyeText(label, Modifier.graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape)
        .background(if (primary) colors.primarySoft else colors.surfaceVariant)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button) { haptics.tap(); onClick() }
        .padding(horizontal = 16.dp, vertical = 7.dp),
        style = LocalEyeTypography.current.bodySecondary, fontWeight = FontWeight.SemiBold,
        color = if (primary) colors.primary else colors.textPrimary)
}

// endregion

// region 实时检测与休息提醒

/** 实时检测：未开始时一行（图标 + 名称 +「开始」），开始后展开取景框与读数。 */
@Composable
private fun LiveCard(modifier: Modifier, live: @Composable () -> Unit) {
    EyeCard(modifier.animateContentSize(), verticalArrangement = Arrangement.Top) { live() }
}

/** 休息提醒：一行（图标 + 名称 + 间隔 + 开关），开启后下方可换间隔。不给出建议间隔。 */
@Composable
private fun ReminderCard(state: HomeUiState, actions: HomeActions) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    EyeCard(Modifier.animateContentSize(), contentPadding = 0.dp, verticalArrangement = Arrangement.Top) {
        // 整行可点（48dp 触控区），开关本身只做展示，避免出现两个点击目标
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(state.reminderEnabled, role = Role.Switch) { actions.toggleReminder() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(EyeShapes.small).background(colors.successSoft), contentAlignment = Alignment.Center) {
                EyeGlyphIcon(EyeGlyph.BELL, Modifier.size(18.dp), colors.success)
            }
            Spacer(Modifier.width(14.dp))
            EyeText("休息提醒", Modifier.weight(1f), style = typography.body, fontWeight = FontWeight.Medium, color = colors.textPrimary)
            if (state.reminderEnabled) EyeText(
                if (state.reminderNextTimeText.isNotEmpty()) "下次 ${state.reminderNextTimeText}" else "每 ${state.reminderIntervalMinutes} 分钟",
                Modifier.padding(end = 10.dp), style = typography.bodySecondary, color = colors.textSecondary)
            EyeSwitch(checked = state.reminderEnabled, onCheckedChange = null)
        }
        if (state.reminderEnabled) Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
            EyeSegmentedRow(listOf(SegmentedOption(20, "每 20 分钟"), SegmentedOption(30, "每 30 分钟"), SegmentedOption(60, "每 60 分钟")),
                state.reminderIntervalMinutes, actions.selectReminderInterval)
        }
        state.notice?.let { EyeText(it, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), style = typography.bodySecondary, color = colors.warning) }
    }
}

// endregion

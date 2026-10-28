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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zkyjss.eyeproject.core.designsystem.theme.EyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.EyeMotion
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.designsystem.util.getTopStatusBarPadding
import com.zkyjss.eyeproject.core.designsystem.util.screenHorizontalPaddingLarge
import com.zkyjss.eyeproject.core.eyecare.EyeTip
import com.zkyjss.eyeproject.core.eyecare.EyeCareEngine
import com.zkyjss.eyeproject.core.eyecare.EyeCareReport
import com.zkyjss.eyeproject.core.eyecare.EyeSignal
import com.zkyjss.eyeproject.core.eyecare.LoopStage
import com.zkyjss.eyeproject.core.ui.component.*
import java.time.LocalDate
import java.time.LocalTime
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

internal const val LOOP_HELP = "小瞳按「感知 → 评估 → 归因 → 干预 → 反馈」帮你管理用眼：汇总用屏、眨眼、距离、环境光和你的档案，" +
    "评估今天的用眼负荷（0–100，越高越累），找出主要原因，给出马上能做的建议，再根据你每天的感受调整。结果仅供参考，不是诊断。"
internal const val PERCEPTION_HELP = "基础数据来自系统用屏统计与光线传感器；视觉行为来自实时检测（画面只在本机处理，不保存）；" +
    "个体档案来自「我的 → 家庭成员」里本人的资料。这些数据都只保存在本机。"
internal const val USAGE_HELP_ANDROID = "需要在系统设置里允许「使用情况访问」，小瞳才能统计每天用屏多久、连续用了多久和夜间使用；统计只在本机进行。"

/**
 * 首页：问候与日期（右上角账户、系统设置）→ 今日用眼（负荷分、小瞳、闭环进度）→ 今日感受 → 为你推荐 →
 * 多源感知（基础数据 / 视觉行为与实时检测 / 个体档案）→ 主动服务（休息提醒、趋势报告、健康建议、亮度与蓝光）。
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
    var breakOpen by rememberSaveable { mutableStateOf(false) }
    var exerciseOpen by rememberSaveable { mutableStateOf(false) }
    var liveRequests by rememberSaveable { mutableIntStateOf(0) }
    val behavior = remember { BringIntoViewRequester() }
    val basic = remember { BringIntoViewRequester() }
    var scrollTarget by remember { mutableStateOf<BringIntoViewRequester?>(null) }
    LaunchedEffect(scrollTarget) { scrollTarget?.bringIntoView(); scrollTarget = null }
    Column(
        modifier.fillMaxSize().background(colors.appBackground).verticalScroll(rememberScrollState())
            .padding(top = getTopStatusBarPadding()).screenHorizontalPaddingLarge(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        EyePageHeader(greeting(), navigation.onOpenSettings, onOpenAccount = navigation.onOpenAccount,
            subtitle = remember { LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)) })
        EyeCareHero(report) { tip ->
            when (tip.kind) {
                "BREAK" -> breakOpen = true
                "EXERCISE" -> exerciseOpen = true
                "REMINDER" -> actions.enableReminder(tip.value ?: 20)
                "BRIGHTNESS", "NIGHT" -> navigation.onOpenBrightness()
                "TEST" -> tip.target?.let(navigation.onOpenTest)
                "PROFILE" -> navigation.onEditProfile(state.selfId)
                "USAGE_ACCESS" -> actions.openUsageAccess()
                "LIVE" -> { liveRequests++; scrollTarget = behavior }
            }
        }
        CheckInCard(report, actions.checkIn)
        SectionTitle("多源感知", PERCEPTION_HELP)
        BasicDataCard(state, actions, Modifier.bringIntoViewRequester(basic))
        BehaviorCard(report, Modifier.bringIntoViewRequester(behavior)) { liveEye(liveRequests) }
        SectionTitle("主动服务", null)
        ExerciseCard { exerciseOpen = true }
        ReminderCard(state, actions)
        ServiceTiles(state, navigation)
        EyeDisclosure("开发者工具") {
            EyeButton(text = "模型测试", role = EyeButtonRole.SECONDARY, onClick = navigation.onNavigateToTestModel, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(24.dp))
    }
    if (breakOpen) RestBreakPage { breakOpen = false }
    if (exerciseOpen) EyeExercisePage({ exerciseOpen = false })
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..10 -> "早上好"
    in 11..12 -> "中午好"
    in 13..17 -> "下午好"
    else -> "晚上好"
}

@Composable
private fun SectionTitle(title: String, help: String?) {
    Row(Modifier.padding(start = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        EyeText(title, style = LocalEyeTypography.current.title, color = LocalEyeColors.current.textPrimary)
        if (help != null) EyeHelpButton(title, help)
    }
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
 * 今日用眼：负荷分圆环 + 会说话的小瞳（随机弹出今天的提醒，可执行的点气泡直接去做；负荷越高嫩芽越蔫）+ 一句话 + 闭环五步进度。
 */
@Composable
private fun EyeCareHero(report: EyeCareReport?, onTip: (EyeTip) -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val (strong, soft) = colors.levelTone(report?.level)
    Column(
        Modifier.fillMaxWidth().clip(EyeShapes.panel)
            .background(Brush.linearGradient(listOf(colors.primarySoft, colors.panelBackground, soft)))
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EyeText("今日用眼", style = typography.subtitle, color = colors.textPrimary)
            EyeHelpButton("主动护眼", LOOP_HELP)
            Spacer(Modifier.weight(1f))
            EyeText(report?.label ?: "读取中", style = typography.chip, color = colors.onStatus, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(EyeShapes.chip).background(strong).padding(horizontal = 12.dp, vertical = 6.dp))
        }
        val tips = remember(report?.tips) { report?.tips.orEmpty().map { MascotTip(it.text, it.action, it) } }
        Box(Modifier.fillMaxWidth().height(220.dp)) {
            XiaoTongTalk(MascotMood.of(report?.mood), tips, Modifier.fillMaxSize(), headScale = 0.8f,
                wilt = report?.score?.let { ((it - 30) / 50f).coerceIn(0f, 1f) }, bubbleHeight = 84.dp, tail = BubbleTail.END,
                mascotFraction = 0.5f) { tip -> (tip.payload as? EyeTip)?.let(onTip) }
            ScoreRing(report?.score, colors.ringTone(report?.level), Modifier.align(Alignment.BottomStart).padding(bottom = 6.dp).size(124.dp))
        }
        EyeText(report?.headline ?: "正在汇总今天的用眼数据…", style = typography.body, color = colors.textPrimary)
        LoopStrip(report?.loop.orEmpty())
    }
}

/** 负荷分圆环：灰色底环 + 等级色进度弧（出现时转动到位），中间大号分数。 */
@Composable
private fun ScoreRing(score: Int?, color: Color, modifier: Modifier) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val fraction by animateFloatAsState((score ?: 0) / 100f, tween(EyeMotion.LONG, easing = EyeMotion.Standard), label = "负荷分")
    Box(modifier.semantics { contentDescription = score?.let { "今日用眼负荷 $it 分" } ?: "今日用眼负荷暂无数据" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(colors.surfaceVariant, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            if (score != null) drawArc(color, -90f, 360f * fraction.coerceIn(0.02f, 1f), false, Offset(inset, inset), arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            EyeText(score?.toString() ?: "—", style = typography.headline, color = colors.textPrimary)
            EyeText("用眼负荷", style = typography.chip, color = colors.textSecondary)
        }
    }
}

/** 闭环五步：完成的一步实心主色带勾，未完成空心；之间连线。 */
@Composable
private fun LoopStrip(stages: List<LoopStage>) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val items = stages.ifEmpty { listOf("感知", "评估", "归因", "干预", "反馈").map { LoopStage(it, it, false, "") } }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.Top) {
        items.forEachIndexed { i, stage ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(44.dp)
                .semantics(mergeDescendants = true) { contentDescription = "${stage.label}：${if (stage.done) "已完成" else "待完成"}，${stage.summary}" }) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(if (stage.done) colors.primary else colors.panelBackground)
                    .border(1.5.dp, if (stage.done) colors.primary else colors.borderSubtle, CircleShape), contentAlignment = Alignment.Center) {
                    if (stage.done) Canvas(Modifier.size(12.dp)) {
                        drawPath(Path().apply {
                            moveTo(size.width * 0.1f, size.height * 0.55f); lineTo(size.width * 0.4f, size.height * 0.82f)
                            lineTo(size.width * 0.92f, size.height * 0.2f)
                        }, colors.onPrimary, style = Stroke(size.width * 0.2f, cap = StrokeCap.Round))
                    } else Box(Modifier.size(6.dp).clip(CircleShape).background(colors.borderSubtle))
                }
                Spacer(Modifier.height(4.dp))
                EyeText(stage.label, style = typography.chip, color = if (stage.done) colors.primary else colors.textSecondary)
            }
            if (i < items.lastIndex) Box(Modifier.weight(1f).padding(top = 12.dp).height(2.dp).clip(CircleShape)
                .background(if (stage.done && items[i + 1].done) colors.primary else colors.borderSubtle))
        }
    }
}

// endregion

// region 今日感受（反馈）

private const val NO_DISCOMFORT = "没有不适"

/** 今日感受打卡：与「视觉行为」同样的卡片标题；没打卡时展开选项，打卡后收成一行，可修改。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CheckInCard(report: EyeCareReport?, onCheckIn: (List<String>) -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val haptics = rememberEyeHaptics()
    val checked = report?.checkedInToday == true
    var editing by rememberSaveable { mutableStateOf(false) }
    var selected by remember(report?.todaySymptoms, checked) {
        mutableStateOf(if (checked) report?.todaySymptoms.orEmpty().ifEmpty { listOf(NO_DISCOMFORT) } else emptyList())
    }
    Column(Modifier.fillMaxWidth().clip(EyeShapes.panel).background(colors.panelBackground).animateContentSize().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val fine = report?.todaySymptoms.isNullOrEmpty()
        CardHeader("今日感受", EyeGlyph.HEART, if (checked && !fine) EyeTileTone.ACCENT else EyeTileTone.SUCCESS) {
            if (checked && !editing) PillButton("修改", primary = false) { editing = true }
        }
        if (checked && !editing) {
            EyeText(report?.todaySymptoms?.joinToString("、")?.ifEmpty { NO_DISCOMFORT } ?: NO_DISCOMFORT,
                style = typography.subtitle, color = if (fine) colors.textPrimary else colors.warning)
        } else {
            EyeText("今天眼睛感觉怎么样？", style = typography.body, color = colors.textSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf(NO_DISCOMFORT) + EyeCareEngine.SYMPTOMS).forEach { label ->
                    val on = label in selected
                    ChoiceChip(label, on) {
                        selected = when {
                            label == NO_DISCOMFORT -> if (on) emptyList() else listOf(NO_DISCOMFORT)
                            on -> selected - label
                            else -> selected - NO_DISCOMFORT + label
                        }
                    }
                }
            }
            EyeButton("记录今天的感受", EyeButtonRole.PRIMARY, {
                haptics.success(); onCheckIn(selected - NO_DISCOMFORT); editing = false
            }, Modifier.fillMaxWidth(), enabled = selected.isNotEmpty())
        }
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
        .background(if (primary) colors.primary else colors.surfaceVariant)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button) { haptics.tap(); onClick() }
        .padding(horizontal = 14.dp, vertical = 8.dp),
        style = LocalEyeTypography.current.chip, color = if (primary) colors.onPrimary else colors.textPrimary, fontWeight = FontWeight.Bold)
}

// endregion

// region 多源感知

/** 一格读数：名称、数值（需要留意时琥珀色并带小圆点）、可选的来源说明。 */
@Composable
internal fun SignalCell(signal: EyeSignal?, modifier: Modifier = Modifier, label: String = signal?.label.orEmpty()) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val attention = signal?.status == "ATTENTION"
    Column(modifier.clip(EyeShapes.medium).background(colors.appBackground).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EyeText(label, style = typography.bodySecondary, color = colors.textSecondary, maxLines = 1)
            if (attention) { Spacer(Modifier.width(4.dp)); Box(Modifier.size(6.dp).clip(CircleShape).background(colors.accent)) }
        }
        EyeText(signal?.value ?: "—", style = typography.subtitle, maxLines = 1,
            color = when { signal == null || signal.status == "UNAVAILABLE" -> colors.textSecondary; attention -> colors.warning; else -> colors.textPrimary })
    }
}

@Composable
private fun CardHeader(title: String, glyph: EyeGlyph, tone: EyeTileTone, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        EyeIconTile(glyph, tone, 32.dp)
        Spacer(Modifier.width(10.dp))
        EyeText(title, Modifier.weight(1f), style = LocalEyeTypography.current.subtitle, color = LocalEyeColors.current.textPrimary)
        trailing()
    }
}

/** 基础数据：今日用屏、最长连续、休息次数、夜间使用、环境光、屏幕亮度（只显示本机系统提供的项）；未授权用屏统计时可去开启。 */
@Composable
private fun BasicDataCard(state: HomeUiState, actions: HomeActions, modifier: Modifier) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val signals = state.report?.signals.orEmpty().filter { it.group == "BASIC" }
    if (signals.isEmpty()) return
    Column(modifier.fillMaxWidth().clip(EyeShapes.panel).background(colors.panelBackground).animateContentSize().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CardHeader("基础数据", EyeGlyph.HOURGLASS, EyeTileTone.BLUE)
        SignalGrid(signals, columns = 3)
        if (state.usageAccess == EyeCareEngine.ACCESS_DENIED) Row(verticalAlignment = Alignment.CenterVertically) {
            EyeText("开启用屏统计，自动记录每天用屏", Modifier.weight(1f), style = typography.body, color = colors.textSecondary)
            EyeHelpButton("用屏统计", USAGE_HELP_ANDROID)
            PillButton("去开启", onClick = actions.openUsageAccess)
        }
    }
}

@Composable
internal fun SignalGrid(signals: List<EyeSignal>, columns: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        signals.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { SignalCell(it, Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 视觉行为：眨眼频率、观看距离、头部姿态 + 实时检测（默认关闭，打开后看到画面与检测结果）。 */
@Composable
private fun BehaviorCard(report: EyeCareReport?, modifier: Modifier, live: @Composable () -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val signals = report?.signals.orEmpty().filter { it.group == "BEHAVIOR" }
    Column(modifier.fillMaxWidth().clip(EyeShapes.panel).background(colors.panelBackground).animateContentSize().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CardHeader("视觉行为", EyeGlyph.EYE, EyeTileTone.PRIMARY) {
            signals.firstOrNull()?.note?.let { EyeText(it, style = typography.chip, color = colors.textSecondary) }
        }
        SignalGrid(signals, columns = 3)
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.borderSubtle))
        live()
    }
}

// endregion

// region 主动服务

/** 护眼操入口：跟小瞳转转眼球（向左、右、上、下看），约 1 分钟。 */
@Composable
private fun ExerciseCard(onStart: () -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Row(Modifier.fillMaxWidth().clip(EyeShapes.panel).background(colors.panelBackground)
        .clickable(role = Role.Button, onClick = onStart).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        XiaoTong(MascotMood.HAPPY, Modifier.size(56.dp), interactive = false, headScale = 0.78f)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            EyeText("护眼操", style = typography.subtitle, color = colors.textPrimary)
            EyeText("跟小瞳转转眼球 · 约 1 分钟", style = typography.bodySecondary, color = colors.textSecondary)
        }
        PillButton("开始", onClick = onStart)
    }
}

/** 休息提醒：开关（下次提醒时间）+ 间隔；建议间隔与当前不同时给出一键采用。 */
@Composable
private fun ReminderCard(state: HomeUiState, actions: HomeActions) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val suggested = state.report?.suggestedReminderMinutes
    Column(Modifier.fillMaxWidth().clip(EyeShapes.panel).background(colors.panelBackground).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 整行可点（48dp 触控区），开关本身只做展示，避免出现两个点击目标
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(state.reminderEnabled, role = Role.Switch) { actions.toggleReminder() },
            verticalAlignment = Alignment.CenterVertically) {
            EyeIconTile(EyeGlyph.BELL, EyeTileTone.ACCENT, 32.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                EyeText("休息提醒", style = typography.subtitle, color = colors.textPrimary)
                EyeText(if (state.reminderEnabled && state.reminderNextTimeText.isNotEmpty()) "每 ${state.reminderIntervalMinutes} 分钟，下次约 ${state.reminderNextTimeText}"
                    else "按间隔提醒你远眺放松", style = typography.bodySecondary, color = colors.textSecondary)
            }
            EyeSwitch(checked = state.reminderEnabled, onCheckedChange = null)
        }
        EyeSegmentedRow(listOf(SegmentedOption(20, "20分钟"), SegmentedOption(30, "30分钟"), SegmentedOption(60, "60分钟")),
            state.reminderIntervalMinutes, actions.selectReminderInterval)
        if (suggested != null && suggested != state.reminderIntervalMinutes) Row(verticalAlignment = Alignment.CenterVertically) {
            EyeText("小瞳建议每 $suggested 分钟休息一次", Modifier.weight(1f), style = typography.body, color = colors.textSecondary)
            PillButton("采用") { actions.enableReminder(suggested) }
        }
        state.notice?.let { EyeText(it, style = typography.bodySecondary, color = colors.warning) }
    }
}

/** 服务入口 2×2：用眼趋势报告、专业咨询建议、AI 综合建议、亮度与蓝光。 */
@Composable
private fun ServiceTiles(state: HomeUiState, navigation: HomeNavigation) {
    val report = state.report
    val trend = report?.trend?.averageScreenMinutes?.let { "日均用屏 ${EyeCareEngine.duration(it)}" } ?: "近 7 天变化"
    val urgent = report?.consults?.any { it.urgent } == true
    val consult = report?.consults?.firstOrNull()?.title ?: "结合自测与档案"
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ServiceTile("用眼趋势报告", trend, EyeGlyph.CHART, EyeTileTone.SUCCESS, Modifier.weight(1f), navigation.onOpenTrend)
            ServiceTile("专业咨询建议", consult, EyeGlyph.CHAT, if (urgent) EyeTileTone.DANGER else EyeTileTone.BLUE, Modifier.weight(1f), navigation.onOpenAdvice)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ServiceTile("AI 综合建议", "小瞳结合大模型给建议", EyeGlyph.BULB, EyeTileTone.ACCENT, Modifier.weight(1f), navigation.onOpenAdvice)
            ServiceTile("亮度与蓝光", state.screenBrightness?.let { "屏幕亮度 ${(it * 100).toInt()}%" } ?: "夜间模式与护眼",
                EyeGlyph.SUN, EyeTileTone.ACCENT, Modifier.weight(1f), navigation.onOpenBrightness)
        }
    }
}

@Composable
private fun ServiceTile(title: String, subtitle: String, glyph: EyeGlyph, tone: EyeTileTone, modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val haptics = rememberEyeHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, EyeMotion.pressSpring(), label = "服务入口按压")
    Column(modifier.graphicsLayer { scaleX = scale; scaleY = scale }.clip(EyeShapes.panel).background(colors.panelBackground)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button) { haptics.tap(); onClick() }
        .heightIn(min = 120.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EyeIconTile(glyph, tone, 40.dp)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            EyeText(title, style = typography.body, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
            EyeText(subtitle, style = typography.bodySecondary, color = colors.textSecondary, maxLines = 2)
        }
    }
}

// endregion

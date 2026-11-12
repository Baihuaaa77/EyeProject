package com.zkyjss.eyeproject.feature.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.*
import com.zkyjss.eyeproject.core.designsystem.util.getTopStatusBarPadding
import com.zkyjss.eyeproject.core.ui.component.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/** 棕色首页只改变呈现，等级、统计、检测与打卡仍由原有状态和事件驱动。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BrownHomeOverview(
    state: HomeUiState,
    navigation: HomeNavigation,
    actions: HomeActions,
    modifier: Modifier,
    liveEye: @Composable (Int) -> Unit,
) {
    val c = LocalEyeColors.current
    val t = LocalEyeTypography.current
    val report = state.report
    var exercise by rememberSaveable { mutableStateOf(false) }
    var feeling by rememberSaveable { mutableStateOf(false) }
    var allData by rememberSaveable { mutableStateOf(false) }
    var liveRequests by rememberSaveable { mutableIntStateOf(0) }
    val live = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val startDetection: () -> Unit = {
        liveRequests++
        scope.launch { live.bringIntoView() }
    }
    val grade =
        when (report?.level) {
            "LOW" -> "较轻"
            "MEDIUM" -> "中等"
            "HIGH" -> "较大"
            else -> "暂无读数"
        }
    val signalColor =
        when (report?.level) {
            "LOW" -> c.success
            "HIGH" -> c.danger
            "MEDIUM" -> c.primary
            else -> c.textSecondary
        }
    val date = remember {
        LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA))
    }
    val greeting = remember {
        when (LocalTime.now().hour) {
            in 5..10 -> "早上好"
            in 11..13 -> "中午好"
            in 14..17 -> "下午好"
            else -> "晚上好"
        }
    }
    Column(
        modifier
            .fillMaxSize()
            .background(c.appBackground)
            .verticalScroll(rememberScrollState())
            .padding(top = getTopStatusBarPadding())
            .padding(horizontal = EyeSpacing.gutter),
        verticalArrangement = Arrangement.spacedBy(EyeSpacing.cardGap),
    ) {
        EyePageHeader(
            greeting,
            navigation.onOpenSettings,
            onOpenAccount = navigation.onOpenAccount,
            subtitle = date,
        )
        EyeWarmCard(fill = EyeBrown.hero, padding = 16.dp, spacing = 2.dp) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                EyeText("今日用眼负荷", style = t.subtitle, color = c.textPrimary)
                EyeHelpButton("今日用眼负荷", "根据当前已有的用屏、休息、环境与自测数据进行评估，不替代医学诊断。", Modifier.size(28.dp))
                Spacer(Modifier.weight(1f))
                WarmLink("全部数据") { allData = true }
            }
            BoxWithConstraints {
                val stacked = maxWidth / LocalDensity.current.fontScale < 250.dp
                val reading: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        EyeText(
                            grade,
                            style = if (report?.score == null) t.title else t.display,
                            color = signalColor,
                        )
                        Row(
                            Modifier.clickable(role = Role.Button, onClick = startDetection)
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            EyeGlyphIcon(EyeGlyph.SYNC, Modifier.size(14.dp), c.textSecondary)
                            EyeText(
                                if (report?.score == null) "检测" else "复测",
                                style = t.bodySecondary,
                                color = c.textSecondary,
                            )
                        }
                    }
                }
                if (stacked)
                    Column {
                        reading()
                        EyeLoadArtwork(report?.level, Modifier.fillMaxWidth().height(120.dp))
                    }
                else
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 112.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(.36f)) { reading() }
                        EyeLoadArtwork(report?.level, Modifier.weight(.64f).height(112.dp))
                    }
            }
            EyeLoadRail(report?.score)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                EyeText("较轻", style = t.caption, color = c.textSecondary)
                EyeText("较大", style = t.caption, color = c.textSecondary)
            }
            Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(1.dp).background(c.borderSubtle))
            Row(Modifier.fillMaxWidth().padding(top = 2.dp).height(IntrinsicSize.Min)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    EyeText("环境光", style = t.bodySecondary, color = c.textSecondary)
                    EyeText(
                        eyeReading(state.ambientLux?.let { "${it.toInt()} lx" } ?: "—"),
                        style = t.metricValue,
                        color = c.textPrimary,
                    )
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(c.borderSubtle))
                Column(
                    Modifier.weight(1f).padding(start = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    EyeText("今日用屏", style = t.bodySecondary, color = c.textSecondary)
                    EyeText(
                        eyeReading(
                            report?.signals?.firstOrNull { it.key == "screen" }?.value ?: "—"
                        ),
                        style = t.metricValue,
                        color = c.textPrimary,
                    )
                }
            }
        }
        EyeWarmCard(fill = EyeBrown.trend, spacing = 2.dp) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                EyeText("本周用眼趋势", Modifier.weight(1f), style = t.subtitle, color = c.textPrimary)
                WarmLink("按日查看", navigation.onOpenTrend)
            }
            val monday = LocalDate.now().with(java.time.DayOfWeek.MONDAY)
            val days = report?.trend?.days.orEmpty().associateBy { it.day }
            EyeTrendRibbon((0..6).map { days[monday.plusDays(it.toLong()).toString()]?.score })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                listOf("周初", "周中", "周末").forEach {
                    EyeText(it, style = t.caption, color = c.textSecondary)
                }
            }
        }
        Row(
            Modifier.height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            WarmQuick(
                "AI 主动问诊",
                EyeGlyph.CHAT,
                EyeBrown.consult,
                Modifier.weight(1f).fillMaxHeight(),
                navigation.onOpenChat,
            )
            WarmQuick(
                "今日感受",
                EyeGlyph.HEART,
                EyeBrown.feeling,
                Modifier.weight(1f).fillMaxHeight(),
                { feeling = true },
                if (report?.checkedInToday == true) "已记录" else "记录",
            )
        }
        EyeWarmCard(Modifier.bringIntoViewRequester(live), padding = 14.dp) {
            liveEye(liveRequests)
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle))
            Row(
                Modifier.fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Switch, onClick = actions.toggleReminder),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EyeGlyphIcon(EyeGlyph.BELL, Modifier.size(20.dp), c.ice)
                EyeText(
                    "休息一下",
                    Modifier.weight(1f).padding(start = 8.dp),
                    style = t.navigationTitle,
                    color = c.textPrimary,
                )
                EyeSwitch(state.reminderEnabled, null)
            }
            if (state.reminderEnabled)
                EyeSegmentedRow(
                    listOf(
                        SegmentedOption(20, "20 分钟"),
                        SegmentedOption(30, "30 分钟"),
                        SegmentedOption(60, "60 分钟"),
                    ),
                    state.reminderIntervalMinutes,
                    actions.selectReminderInterval,
                )
            state.notice?.let { EyeText(it, style = t.bodySecondary, color = c.warning) }
        }
        EyeDisclosure("日常护眼") {
            EyeListRow("护眼操", { exercise = true }, glyph = EyeGlyph.EYE)
            EyeListRow("屏幕光", navigation.onOpenBrightness, glyph = EyeGlyph.SUN, divider = false)
        }
        navigation.onNavigateToTestModel?.let { open ->
            EyeDisclosure("开发者工具") { EyeButton("模型测试", EyeButtonRole.SECONDARY, open) }
        }
        Spacer(Modifier.height(24.dp))
    }
    if (exercise) EyeExercisePage({ exercise = false })
    if (feeling)
        FeelingDialog(
            if (report?.checkedInToday == true) report.todaySymptoms.ifEmpty { listOf("没有不适") }
            else emptyList(),
            { picked ->
                feeling = false
                actions.checkIn(picked.filter { it != "没有不适" })
            },
            { feeling = false },
        )
    if (allData)
        EyeFullScreenPage({ allData = false }) {
            EyeSubPage("全部数据", { allData = false }) {
                report?.signals.orEmpty().forEach { signal ->
                    EyeListRow(signal.label, null, value = signal.value)
                }
                if (
                    state.usageAccess ==
                        com.zkyjss.eyeproject.core.eyecare.EyeCareEngine.ACCESS_DENIED
                )
                    EyeButton("开启用屏统计", EyeButtonRole.PRIMARY, actions.openUsageAccess)
            }
        }
}

@Composable
private fun WarmLink(text: String, action: () -> Unit) {
    EyeText(
        "$text  ›",
        Modifier.heightIn(min = 28.dp)
            .wrapContentHeight()
            .clickable(role = Role.Button, onClick = action),
        style = LocalEyeTypography.current.bodySecondary,
        color = LocalEyeColors.current.primary,
    )
}

@Composable
private fun WarmQuick(
    title: String,
    glyph: EyeGlyph,
    fill: androidx.compose.ui.graphics.Brush,
    modifier: Modifier,
    action: () -> Unit,
    badge: String? = null,
) {
    val c = LocalEyeColors.current
    val t = LocalEyeTypography.current
    EyeWarmCard(modifier.clickable(role = Role.Button, onClick = action), fill, padding = 14.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            EyeGlyphIcon(glyph, Modifier.size(24.dp), c.primary)
            Spacer(Modifier.weight(1f))
            EyeText(badge ?: "↗", style = t.caption, color = c.textSecondary)
        }
        EyeText(
            title,
            Modifier.padding(top = 6.dp),
            style = t.navigationTitle,
            color = c.textPrimary,
        )
    }
}

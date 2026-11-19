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
    var careTimer by rememberSaveable { mutableStateOf<String?>(null) }
    var booking by rememberSaveable { mutableStateOf(false) }
    var allData by rememberSaveable { mutableStateOf(false) }
    var recordDetail by rememberSaveable { mutableStateOf<Int?>(null) }
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
    val signalColor = report?.score?.let(EyeBrown::loadColor) ?: c.textSecondary
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
                WarmLink("用眼全记录") { allData = true }
            }
            BoxWithConstraints {
                val stacked = maxWidth / LocalDensity.current.fontScale < 250.dp
                val reading: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        EyeText(
                            grade,
                            style = if (report?.score == null) t.title else t.display,
                            color = report?.score?.let(EyeBrown::loadTextColor) ?: c.textSecondary,
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
            EyeLoadRail(report?.score, tone = signalColor)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                EyeText("较轻", style = t.caption, color = c.textPrimary)
                EyeText("较大", style = t.caption, color = c.textPrimary)
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
        Row(horizontalArrangement = Arrangement.spacedBy(EyeSpacing.md)) {
            EyeButton("AI问诊", EyeButtonRole.PRIMARY, navigation.onOpenChat, Modifier.weight(1f))
            EyeButton("智能挂号", EyeButtonRole.PRIMARY, { booking = true }, Modifier.weight(1f))
        }
        EyeWarmCard {
            val daily = listOf("闭眼休息", "远眺20秒")
            val completed = report?.completedCare.orEmpty()
            Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically) {
                EyeText("加油小贴贴", Modifier.weight(1f), style = t.subtitle, color = c.textPrimary)
                EyeText("今日 ${daily.count { it in completed }} / ${daily.size} 项", style = t.caption, color = c.textSecondary)
            }
            daily.forEach { name ->
                val label = if (name in completed) "$name · 已完成" else name
                EyeButton(label, EyeButtonRole.SECONDARY, { careTimer = name }, Modifier.fillMaxWidth())
            }
            EyeListRow("热敷眼睛", { careTimer = "热敷眼睛" },
                value = if ("热敷眼睛" in completed) "选做 · 已完成" else "选做", divider = false)
        }
        V16HomeWeek(report?.trend?.historyDays.orEmpty(), { feeling = true }, navigation.onOpenTrend)
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
    if (booking) EyeOfflineSessionPage(EyeOfflineSession.BOOKING, { booking = false })
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
            initialNote = report?.todayNote.orEmpty(),
            onSaveNote = { picked,note -> feeling = false; actions.checkInWithNote(picked.filter { it != "没有不适" },note) },
        )
    careTimer?.let { key ->
        RestBreakPage(onClose = { careTimer = null },title = key,seconds = when (key) { "闭眼休息" -> 30; "热敷眼睛" -> 180; else -> 20 },
            hint = when (key) { "闭眼休息" -> "轻轻闭眼。\n让眼睛休息30秒。"; "热敷眼睛" -> "可用干净的温湿毛巾，也可选眼部专用热敷眼罩，按说明加热和清洁。\n先试温，温热不烫再轻放在闭合的眼睑上；感到烫或不舒服就取下。"; else -> "看向6米外的远处。\n放松视线，持续20秒。" },
            onComplete = { actions.careCheckIn(key) })
    }
    if (allData) {
        val back: () -> Unit = { if (recordDetail != null) recordDetail = null else allData = false }
        EyeFullScreenPage(back) {
            V16DailyRecords(report?.dailyRecords.orEmpty(), recordDetail, { recordDetail = it }, back, actions.openUsageAccess.takeIf {
                state.usageAccess == com.zkyjss.eyeproject.core.eyecare.EyeCareEngine.ACCESS_DENIED
            })
        }
    }
}

@Composable
private fun WarmLink(text: String, action: () -> Unit) {
    EyeText(
        "$text  ›",
        Modifier.heightIn(min = 48.dp)
            .wrapContentHeight()
            .clickable(role = Role.Button, onClick = action),
        style = LocalEyeTypography.current.bodySecondary,
        color = LocalEyeColors.current.primary,
    )
}

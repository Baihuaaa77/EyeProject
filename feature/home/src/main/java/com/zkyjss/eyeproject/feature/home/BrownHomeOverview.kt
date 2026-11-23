package com.zkyjss.eyeproject.feature.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
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
    var careDetails by rememberSaveable { mutableStateOf(false) }
    val grade =
        when (report?.level) {
            "LOW" -> "轻度"
            "MEDIUM" -> "中度"
            "HIGH" -> "重度"
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
            .background(EyeBrown.homeBackground)
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
            warm = true,
        )
        EyeWarmCard(fill = EyeBrown.homeSurface, padding = 16.dp, spacing = 4.dp, edge = signalColor) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                EyeText("用眼负荷", Modifier.weight(1f), style = t.title, color = c.textPrimary)
                WarmLink("用眼全记录") { allData = !allData }
            }
            BoxWithConstraints {
                val reading: @Composable () -> Unit = {
                    EyeText(grade, style = if (report?.score == null) t.title else t.display, color = signalColor)
                }
                if (maxWidth / LocalDensity.current.fontScale < 230.dp) {
                    Column {
                        reading()
                        EyeLoadArtwork(report?.level, Modifier.fillMaxWidth().height(94.dp), signalColor)
                    }
                } else {
                    Row(Modifier.fillMaxWidth().heightIn(min = 112.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(.35f)) { reading() }
                        EyeLoadArtwork(report?.level, Modifier.weight(.65f).height(112.dp), signalColor)
                    }
                }
            }
            EyeLoadRail(report?.score, tone = signalColor)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                EyeText("较轻", style = t.caption, color = c.textPrimary)
                EyeText("较重", style = t.caption, color = c.textPrimary)
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HomeCardButton("AI问诊", navigation.onOpenChat, Modifier.weight(1f))
                HomeCardButton("智能挂号", { booking = true }, Modifier.weight(1f))
            }
        }
        if (allData) V16DailyRecords(report?.dailyRecords.orEmpty(), { allData = false }, actions.openUsageAccess.takeIf {
            state.usageAccess == com.zkyjss.eyeproject.core.eyecare.EyeCareEngine.ACCESS_DENIED
        })
        EyeWarmCard(fill = EyeBrown.homeSurface, edge = EyeBrown.homeEdge, padding = 14.dp, spacing = 4.dp) {
            val habits = listOf("闭眼休息", "远眺20秒", "热敷眼睛")
            val completed = report?.completedCare.orEmpty()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                EyeText("加油小贴贴", Modifier.weight(1f), style = t.subtitle, color = c.textPrimary)
                WarmLink("今日 ${habits.count { it in completed }} 次") { careDetails = !careDetails }
            }
            BoxWithConstraints {
                val columns = if (maxWidth / LocalDensity.current.fontScale < 225.dp) 1 else 3
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    habits.chunked(columns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { name ->
                                HomeCardButton(if (name in completed) "✓ $name" else name, { careTimer = name }, Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            if (careDetails) EyeText(completed.takeIf { it.isNotEmpty() }?.joinToString("、") ?: "今天还没打卡",
                style = t.bodySecondary, color = c.textSecondary)
        }
        V16HomeWeek(report?.trend?.historyDays.orEmpty(), { feeling = !feeling }, navigation.onOpenTrend)
        if (feeling) FeelingPicker(
            initial = if (report?.checkedInToday == true) report.todaySymptoms.ifEmpty { listOf("没有不适") } else emptyList(),
            initialNote = report?.todayNote.orEmpty(),
            onSave = { picked -> feeling = false; actions.checkIn(picked.filter { it != "没有不适" }) },
            onSaveNote = { picked, note -> feeling = false; actions.checkInWithNote(picked.filter { it != "没有不适" }, note) },
            onCancel = { feeling = false },
        )
        EyeWarmCard(padding = 14.dp) {
            liveEye(0)
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
    if (booking) EyeOfflineSessionPage(EyeOfflineSession.BOOKING, { booking = false },
        bookingPatientNames = state.bookingPatientNames)
    if (exercise) EyeExercisePage({ exercise = false })
    careTimer?.let { key ->
        RestBreakPage(onClose = { careTimer = null },title = key,seconds = when (key) { "闭眼休息" -> 30; "热敷眼睛" -> 180; else -> 20 },
            hint = when (key) { "闭眼休息" -> "轻轻闭眼。\n让眼睛休息30秒。"; "热敷眼睛" -> "可用干净的温湿毛巾，也可选眼部专用热敷眼罩，按说明加热和清洁。\n先试温，温热不烫再轻放在闭合的眼睑上；感到烫或不舒服就取下。"; else -> "看向6米外的远处。\n放松视线，持续20秒。" },
            onComplete = { actions.careCheckIn(key) })
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
        color = LocalEyeColors.current.textPrimary,
    )
}

@Composable
private fun HomeCardButton(text: String, onClick: () -> Unit, modifier: Modifier) {
    val colors = LocalEyeColors.current
    val shape = EyeShapes.medium
    Box(modifier.heightIn(min = 46.dp).clip(shape).background(EyeBrown.homeAction)
        .border(1.dp, EyeBrown.homeEdge, shape).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 4.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        EyeText(text, style = LocalEyeTypography.current.navigationTitle, color = colors.textPrimary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

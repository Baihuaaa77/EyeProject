package com.zkyjss.eyeproject.feature.services

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.R
import com.zkyjss.eyeproject.core.designsystem.theme.EyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.EyeMotion
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.ui.component.AiCardState
import com.zkyjss.eyeproject.core.ui.component.AiExplanationCard
import com.zkyjss.eyeproject.core.ui.component.EyeButton
import com.zkyjss.eyeproject.core.ui.component.EyeButtonRole
import com.zkyjss.eyeproject.core.ui.component.EyeFullScreenPage
import com.zkyjss.eyeproject.core.ui.component.EyeSubPage
import com.zkyjss.eyeproject.core.ui.component.MascotMood
import com.zkyjss.eyeproject.core.ui.component.XiaoTong
import com.zkyjss.eyeproject.core.ui.component.EyeIcon
import com.zkyjss.eyeproject.core.ui.component.EyeText

/*
 * 三项测试共用的结果页，内容来自共享的 [ResultReport]（与苹果端同一份）：
 * 结论卡（小瞳 + 等级 + 一句结论 + 一句建议）→ 关键指标 2×2 → 判断依据（点开看明细）→ 操作 →
 * 「查看本次数据」「导出数据」→ 一行说明。首屏不放成段小字，明细都在底板里。
 */

/** 结论超过这个字数时改用较小的标题字号（两端一致）。 */
private const val LONG_TITLE = 8

private fun EyeColors.toneFor(level: ScreeningRiskLevel): Pair<Color, Color> = when (level) {
    ScreeningRiskLevel.LOW -> success to successSoft
    ScreeningRiskLevel.MEDIUM -> warning to warningSoft
    ScreeningRiskLevel.HIGH -> danger to dangerSoft
    ScreeningRiskLevel.UNCERTAIN -> primary to primarySoft
    ScreeningRiskLevel.UNKNOWN -> textSecondary to neutralSoft
}

private fun EyeColors.toneFor(status: EvidenceStatus): Pair<Color, Color> = when (status) {
    EvidenceStatus.NORMAL -> success to successSoft
    EvidenceStatus.ABNORMAL -> warning to warningSoft
    EvidenceStatus.UNCERTAIN -> primary to primarySoft
    EvidenceStatus.UNAVAILABLE -> textSecondary to neutralSoft
}

/** 指标卡上的状态字：无法评估的指标多为「仅供参考」的描述量。 */
private val EvidenceStatus.metricLabel get() = if (this == EvidenceStatus.UNAVAILABLE) "参考" else label

/**
 * 完整结果页。[actions] 放病种特有的主操作（延长、复测、重新测试）；[onExport] 为空时不显示导出；
 * [extraData] 追加到数据底板末尾（如干眼的眨眼分布图）；[aiCard] 为空时不显示 AI 解读。
 */
@Composable
internal fun ResultReportView(
    report: ResultReport,
    onExport: (() -> Unit)?,
    actions: @Composable ColumnScope.() -> Unit,
    aiCard: AiCardState? = null,
    onExplain: (agreeFirst: Boolean) -> Unit = {},
    extraData: @Composable ColumnScope.() -> Unit = {},
) {
    var dataOpen by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ResultHero(report)
        // 数据无效时原因已在 ProblemCard 列出，这里只为有效结果说明可靠性，避免同一原因出现两遍
        if (report.valid && report.qualityNotes.isNotEmpty()) {
            val colors = LocalEyeColors.current
            var qualityOpen by rememberSaveable { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().clip(EyeShapes.large).background(colors.panelBackground).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                EyeText("数据可靠性说明", style = LocalEyeTypography.current.subtitle, color = colors.textPrimary)
                EyeText(report.qualityNotes.first(), style = LocalEyeTypography.current.bodySecondary, color = colors.textSecondary)
                if (report.qualityNotes.size > 1) {
                    EyeText(if (qualityOpen) "收起说明 ▴" else "查看说明 ▾",
                        Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button) { qualityOpen = !qualityOpen },
                        style = LocalEyeTypography.current.body, color = colors.primary)
                    if (qualityOpen) report.qualityNotes.drop(1).forEach {
                        EyeText(it, style = LocalEyeTypography.current.bodySecondary, color = colors.textSecondary)
                    }
                }
            }
        }
        if (!report.valid) {
            ProblemCard(report.problems)
        } else {
            MetricGrid(report.metrics)
            EvidenceCard(report.evidence)
        }
        // 解读放在规则结论与依据之后：先看规则判了什么，再看通俗解释
        if (aiCard != null) AiExplanationCard(aiCard, onGenerate = { onExplain(false) }, onAgree = { onExplain(true) })
        actions()
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            EyeButton("查看本次数据", EyeButtonRole.SECONDARY, { dataOpen = true }, Modifier.weight(1f))
            if (onExport != null) EyeButton("导出数据", EyeButtonRole.SECONDARY, onExport, Modifier.weight(1f))
        }
        EyeText(ResultReports.FOOTNOTE, Modifier.fillMaxWidth().padding(vertical = 4.dp),
            style = LocalEyeTypography.current.bodySecondary, color = LocalEyeColors.current.textSecondary)
    }
    // 「查看本次数据」：盖在结果页上的完整页面（不用底部弹窗），与苹果端同一排版
    if (dataOpen) EyeFullScreenPage({ dataOpen = false }) {
        EyeSubPage("本次测试数据", { dataOpen = false }) { DataSheetContent(report, onExport, extraData) }
    }
}

/** 结论卡：等级色的柔和渐变底，左侧徽标 + 大号结论，右侧小瞳（神情随等级变化），下面一句建议。 */
@Composable
private fun ResultHero(report: ResultReport) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val (strong, soft) = colors.toneFor(report.level)
    Column(
        Modifier.fillMaxWidth().clip(EyeShapes.panel)
            .background(Brush.verticalGradient(listOf(soft, colors.panelBackground)))
            .padding(horizontal = 20.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                EyeText(report.badge, style = typography.chip, color = colors.onStatus,
                    modifier = Modifier.clip(EyeShapes.chip).background(strong).padding(horizontal = 12.dp, vertical = 6.dp))
                // 长结论（如「调节能力与实际年龄基本匹配」）用标题字号，避免大字折成三行
                EyeText(report.title, style = if (report.title.length > LONG_TITLE) typography.title else typography.headline,
                    color = colors.textPrimary)
            }
            Mascot(report.mood, Modifier.size(104.dp))
        }
        EyeText(report.advice, style = typography.body, color = colors.textPrimary)
    }
}

/** 结果页小瞳：神情随等级（放心 / 思考 / 担心 / 没测好犯困），带待机动画，点一下有互动。 */
@Composable
private fun Mascot(mood: ResultMood, modifier: Modifier) {
    XiaoTong(MascotMood.of(mood.name), modifier, greet = mood == ResultMood.HAPPY, headScale = 0.76f)
}

/** 关键指标 2×2：名称、大号数值、状态小标签；状态色只用在标签和数值上，卡片本身保持干净。 */
@Composable
private fun MetricGrid(metrics: List<ResultMetric>) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        metrics.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { m ->
                    val (strong, soft) = colors.toneFor(m.status)
                    Column(
                        Modifier.weight(1f).clip(EyeShapes.large).background(colors.panelBackground).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        EyeText(m.label, style = typography.body, color = colors.textSecondary, maxLines = 1)
                        EyeText(m.value, style = typography.title,
                            color = if (m.status == EvidenceStatus.ABNORMAL) colors.warning else colors.textPrimary, maxLines = 1)
                        EyeText(m.status.metricLabel, style = typography.chip, color = strong,
                            modifier = Modifier.clip(EyeShapes.chip).background(soft).padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

/** 判断依据：每个证据族一行（状态圆点 + 名称 + 一句结论），点开显示明细。 */
@Composable
private fun EvidenceCard(evidence: List<Evidence>) {
    if (evidence.isEmpty()) return
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Column(
        Modifier.fillMaxWidth().clip(EyeShapes.panel).background(colors.panelBackground).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        EyeText("判断依据", style = typography.subtitle, color = colors.textPrimary, modifier = Modifier.padding(bottom = 6.dp))
        evidence.forEachIndexed { index, item ->
            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.borderSubtle))
            EvidenceRow(item)
        }
    }
}

@Composable
private fun EvidenceRow(item: Evidence) {
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val (strong, soft) = colors.toneFor(item.status)
    val chevron by animateFloatAsState(if (expanded) 90f else 0f, tween(EyeMotion.MEDIUM, easing = EyeMotion.Standard), label = "依据箭头")
    Column(
        Modifier.fillMaxWidth().semantics { stateDescription = if (expanded) "已展开" else "已收起" }
            .clickable(role = Role.Button, enabled = item.details.isNotEmpty()) { expanded = !expanded }
            .padding(vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(28.dp).clip(EyeShapes.chip).background(soft), contentAlignment = Alignment.Center) {
                Box(Modifier.size(10.dp).clip(EyeShapes.chip).background(strong))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EyeText(item.title, style = typography.button, color = colors.textPrimary)
                    EyeText(item.status.label, style = typography.chip, color = strong)
                }
                EyeText(item.summary, style = typography.body, color = colors.textSecondary)
            }
            if (item.details.isNotEmpty()) EyeIcon(painter = painterResource(R.drawable.ic_arrow_right), contentDescription = null,
                tint = colors.primary, modifier = Modifier.size(18.dp).rotate(chevron))
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(EyeMotion.MEDIUM, easing = EyeMotion.Standard)) + fadeIn(tween(EyeMotion.MEDIUM)),
            exit = shrinkVertically(tween(EyeMotion.MEDIUM, easing = EyeMotion.Standard)) + fadeOut(tween(EyeMotion.SHORT)),
        ) {
            Column(Modifier.padding(start = 40.dp, top = 10.dp).clip(EyeShapes.medium).background(colors.surfaceVariant).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item.details.forEach { EyeText(it, style = typography.bodySecondary, color = colors.textPrimary) }
            }
        }
    }
}

@Composable
private fun ProblemCard(problems: List<String>) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Column(
        Modifier.fillMaxWidth().clip(EyeShapes.panel).background(colors.panelBackground).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        EyeText("没测好的原因", style = typography.subtitle, color = colors.textPrimary)
        problems.forEach { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.padding(top = 8.dp).size(8.dp).clip(EyeShapes.chip).background(colors.textSecondary))
                EyeText(item, Modifier.weight(1f), style = typography.body, color = colors.textPrimary)
            }
        }
    }
}

/** 「本次测试数据」页：判断依据明细 + 各分区原始数值 + 问卷；顶部可直接导出。 */
@Composable
private fun DataSheetContent(report: ResultReport, onExport: (() -> Unit)?, extraData: @Composable ColumnScope.() -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (onExport != null) EyeButton("导出完整数据", EyeButtonRole.PRIMARY, onExport, Modifier.fillMaxWidth())
        if (report.evidence.isNotEmpty()) DataPanel("判断依据") {
            report.evidence.forEach { e ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    EyeText("${e.title} · ${e.status.label}", style = typography.button, color = colors.textPrimary)
                    EyeText(e.summary, style = typography.body, color = colors.textSecondary)
                    e.details.forEach { EyeText("· $it", style = typography.bodySecondary, color = colors.textSecondary) }
                }
            }
        }
        report.sections.forEach { section ->
            DataPanel(section.title) {
                section.rows.forEach { row ->
                    if (row.label.isEmpty()) EyeText(row.value, style = typography.bodySecondary, color = colors.textSecondary)
                    else Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                        EyeText(row.label, Modifier.weight(1f), style = typography.body, color = colors.textSecondary)
                        EyeText(row.value, Modifier.weight(1.2f), style = typography.body, color = colors.textPrimary)
                    }
                }
            }
        }
        extraData()
        EyeText("评估规则：${report.version}", style = typography.bodySecondary, color = colors.textSecondary)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
internal fun DataPanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalEyeColors.current
    Column(
        Modifier.fillMaxWidth().clip(EyeShapes.large).background(colors.panelBackground).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EyeText(title, style = LocalEyeTypography.current.subtitle, color = colors.textPrimary)
        content()
    }
}

package com.zkyjss.eyeproject.core.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.EyeMotion
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import kotlinx.coroutines.delay

/** 参考资料一行：名称 + 发布机构。 */
data class AiSource(val title: String, val publisher: String)

/** AI 解读卡片的展示状态（各页面由数据层状态映射而来）。 */
sealed interface AiCardState {
    /** 需要云端账号：只显示一句如何开启，不放按钮。 */
    data class Locked(val message: String) : AiCardState
    /** 可以生成；[consentText] 不为空时先在卡片内展开同意说明。 */
    data class Idle(val consentText: String?) : AiCardState
    data object Loading : AiCardState
    data class Done(
        val summary: String,
        val meaning: List<String>,
        val suggestions: List<String>,
        val seeDoctor: String,
        val sources: List<AiSource>,
        val footnote: String,
        /** 中 / 高风险时「何时就医」用提醒色。 */
        val urgent: Boolean,
    ) : AiCardState
    data class Failed(val message: String) : AiCardState
}

/**
 * 「AI 解读」卡片：结果页与测试记录共用（与苹果端 AiExplanationView 同一排版与文案）。
 * 排版：标题行（渐变星标 + AI 解读 + 「依据眼科知识库」）→ 按状态显示：
 * 一句说明 + 生成按钮 / 卡内同意说明 / 三行骨架 + 轮换进度语 / 一句话总结 + 「这说明」「可以这样做」+ 就医提示 + 参考资料。
 */
@Composable
fun AiExplanationCard(
    state: AiCardState,
    onGenerate: () -> Unit,
    onAgree: () -> Unit,
    modifier: Modifier = Modifier,
    /** 嵌在测试记录行里：换用页面底色区分层级，并吞掉点按，避免点正文时收起整行。 */
    embedded: Boolean = false,
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Column(
        modifier.fillMaxWidth().clip(if (embedded) EyeShapes.large else EyeShapes.panel)
            .background(if (embedded) colors.appBackground else colors.panelBackground)
            .then(if (embedded) Modifier.pointerInput(Unit) { detectTapGestures { } } else Modifier)
            .padding(horizontal = if (embedded) 14.dp else 20.dp, vertical = if (embedded) 14.dp else 18.dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AiBadge(Modifier.size(30.dp))
            EyeText("AI 解读", Modifier.weight(1f), style = typography.subtitle, color = colors.textPrimary)
            EyeText("依据眼科知识库", style = typography.chip, color = colors.primary,
                modifier = Modifier.clip(EyeShapes.chip).background(colors.primarySoft).padding(horizontal = 10.dp, vertical = 4.dp))
        }
        AnimatedContent(
            targetState = state,
            contentKey = { it::class },
            transitionSpec = { fadeIn(tween(EyeMotion.MEDIUM)) togetherWith fadeOut(tween(EyeMotion.SHORT)) },
            label = "AI 解读状态",
        ) { current ->
            when (current) {
                is AiCardState.Locked -> EyeText(current.message, style = typography.body, color = colors.textSecondary)
                is AiCardState.Idle -> IdleContent(current.consentText, onGenerate, onAgree)
                AiCardState.Loading -> LoadingContent()
                is AiCardState.Done -> DoneContent(current)
                is AiCardState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    EyeText(current.message, style = typography.body, color = colors.textSecondary)
                    EyeButton("重试", EyeButtonRole.SECONDARY, onGenerate, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun IdleContent(consentText: String?, onGenerate: () -> Unit, onAgree: () -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val haptics = rememberEyeHaptics()
    var asking by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        EyeText("用大白话说说这次结果意味着什么，以及接下来可以怎么做。", style = typography.body, color = colors.textSecondary)
        AnimatedVisibility(
            visible = asking && consentText != null,
            enter = expandVertically(tween(EyeMotion.MEDIUM, easing = EyeMotion.Standard)) + fadeIn(tween(EyeMotion.MEDIUM)),
            exit = shrinkVertically(tween(EyeMotion.MEDIUM, easing = EyeMotion.Standard)) + fadeOut(tween(EyeMotion.SHORT)),
        ) {
            Column(Modifier.fillMaxWidth().clip(EyeShapes.medium).background(colors.appBackground).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                EyeText("首次使用需要你的同意", style = typography.button, color = colors.textPrimary)
                EyeText(consentText.orEmpty(), style = typography.bodySecondary, color = colors.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    EyeButton("暂不", EyeButtonRole.SECONDARY, { haptics.tap(); asking = false }, Modifier.weight(1f))
                    EyeButton("同意并生成", EyeButtonRole.PRIMARY, { haptics.tap(); asking = false; onAgree() }, Modifier.weight(1.4f))
                }
            }
        }
        if (!(asking && consentText != null)) EyeButton("生成解读", EyeButtonRole.PRIMARY, {
            haptics.tap()
            if (consentText != null) asking = true else onGenerate()
        }, Modifier.fillMaxWidth())
    }
}

/** 骨架条 + 每 1.6 秒轮换一句进度语，让等待（约 3–8 秒）有反馈。 */
@Composable
private fun LoadingContent() {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1_600); step = (step + 1) % LOADING_STEPS.size } }
    val pulse by rememberInfiniteTransition(label = "骨架呼吸").animateFloat(
        initialValue = 0.35f, targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "骨架透明度",
    )
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite; contentDescription = LOADING_STEPS[step] },
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(1f, 0.86f, 0.62f).forEach { fraction ->
            Box(Modifier.fillMaxWidth(fraction).height(14.dp).alpha(pulse).clip(EyeShapes.chip).background(colors.primarySoft))
        }
        AnimatedContent(LOADING_STEPS[step], transitionSpec = { fadeIn(tween(EyeMotion.MEDIUM)) togetherWith fadeOut(tween(EyeMotion.SHORT)) },
            label = "进度语") { text ->
            EyeText(text, style = typography.bodySecondary, color = colors.textSecondary)
        }
    }
}

@Composable
private fun DoneContent(done: AiCardState.Done) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val haptics = rememberEyeHaptics()
    var sourcesOpen by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        EyeText(done.summary, style = typography.subtitle, color = colors.textPrimary)
        if (done.meaning.isNotEmpty()) BulletGroup("这说明", done.meaning, colors.textSecondary)
        if (done.suggestions.isNotEmpty()) BulletGroup("可以这样做", done.suggestions, colors.primary)
        if (done.seeDoctor.isNotBlank()) {
            val (strong, soft) = if (done.urgent) colors.warning to colors.warningSoft else colors.primary to colors.primarySoft
            Row(Modifier.fillMaxWidth().clip(EyeShapes.medium).background(soft).padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.padding(top = 6.dp).size(8.dp).clip(EyeShapes.chip).background(strong))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    EyeText("什么时候看医生", style = typography.chip, color = strong)
                    EyeText(done.seeDoctor, style = typography.body, color = colors.textPrimary)
                }
            }
        }
        if (done.sources.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            EyeText(if (sourcesOpen) "收起参考资料 ▴" else "参考资料（${done.sources.size}）▾",
                Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable(role = Role.Button) { haptics.tap(); sourcesOpen = !sourcesOpen }
                    .padding(vertical = 8.dp),
                style = typography.body, color = colors.primary)
            if (sourcesOpen) done.sources.forEach { source ->
                EyeText("· ${source.title}（${source.publisher}）", style = typography.bodySecondary, color = colors.textSecondary)
            }
        }
        EyeText(done.footnote, style = typography.bodySecondary, color = colors.textSecondary)
    }
}

@Composable
private fun BulletGroup(title: String, items: List<String>, dot: Color) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        EyeText(title, style = typography.chip, color = colors.textSecondary, fontWeight = FontWeight.SemiBold)
        items.forEach { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.padding(top = 8.dp).size(6.dp).clip(EyeShapes.chip).background(dot))
                EyeText(item, Modifier.weight(1f), style = typography.body, color = colors.textPrimary)
            }
        }
    }
}

/** 品牌渐变圆底上的四角星：一眼看出这是 AI 生成的内容。 */
@Composable
private fun AiBadge(modifier: Modifier) {
    val colors = LocalEyeColors.current
    Canvas(modifier.clip(EyeShapes.chip).background(colors.brandBrush)) {
        fun star(center: Offset, r: Float) = Path().apply {
            val waist = r * 0.28f
            moveTo(center.x, center.y - r)
            quadraticTo(center.x + waist * 0.3f, center.y - waist * 0.3f, center.x + r, center.y)
            quadraticTo(center.x + waist * 0.3f, center.y + waist * 0.3f, center.x, center.y + r)
            quadraticTo(center.x - waist * 0.3f, center.y + waist * 0.3f, center.x - r, center.y)
            quadraticTo(center.x - waist * 0.3f, center.y - waist * 0.3f, center.x, center.y - r)
            close()
        }
        val s = size.minDimension
        drawPath(star(Offset(s * 0.46f, s * 0.54f), s * 0.3f), colors.onPrimary)
        drawPath(star(Offset(s * 0.74f, s * 0.27f), s * 0.12f), colors.onPrimary.copy(alpha = 0.85f))
    }
}

private val LOADING_STEPS = listOf("正在整理本次结果…", "正在查阅眼科资料…", "正在组织通俗的说法…")

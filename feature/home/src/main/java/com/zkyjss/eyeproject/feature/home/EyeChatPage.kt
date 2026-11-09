package com.zkyjss.eyeproject.feature.home

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zkyjss.eyeproject.core.data.chat.ChatAccess
import com.zkyjss.eyeproject.core.data.chat.ChatRepository
import com.zkyjss.eyeproject.core.data.chat.ChatState
import com.zkyjss.eyeproject.core.designsystem.R
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.EyeMotion
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.sync.AdviceRequest
import com.zkyjss.eyeproject.core.sync.ChatEntry
import com.zkyjss.eyeproject.core.sync.Chats
import com.zkyjss.eyeproject.core.ui.component.AiCardText
import com.zkyjss.eyeproject.core.ui.component.AiExplanationCard
import com.zkyjss.eyeproject.core.ui.component.EyeButton
import com.zkyjss.eyeproject.core.ui.component.EyeButtonRole
import com.zkyjss.eyeproject.core.ui.component.EyeGlyph
import com.zkyjss.eyeproject.core.ui.component.EyeGlyphIcon
import com.zkyjss.eyeproject.core.ui.component.EyeOptionRow
import com.zkyjss.eyeproject.core.ui.component.EyeSubPage
import com.zkyjss.eyeproject.core.ui.component.EyeText
import com.zkyjss.eyeproject.core.ui.component.eyeGlass
import com.zkyjss.eyeproject.core.ui.component.rememberEyeHaptics
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ChatViewModel @Inject constructor(private val chat: ChatRepository) : ViewModel() {
    val state: StateFlow<ChatState?> = chat.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun send(text: String, context: AdviceRequest?, agreeFirst: Boolean, dontAskAgain: Boolean) {
        viewModelScope.launch { chat.send(text, context, agreeFirst, dontAskAgain) }
    }
    fun clear() { viewModelScope.launch { chat.clear() } }
}

/** 对话页标题（两端一致）；首页入口是爱科卡（见 HomeScreen 的 EyechoCard）。 */
internal const val CHAT_TITLE = "AI 主动问诊"

/**
 * 「AI 主动问诊」对话页：由爱尔眼科数字人「爱科 Eyecho」主持。顶部是爱科的自我介绍，接着是今天的综合建议卡片，
 * 下面和爱科自由对话；记录只存在本机，可清空。
 * 同意说明平时不展示，发送时按需弹窗（见 [ChatConsentDialog]）。与苹果端 AiChatPage 同一规则与文案。
 */
@Composable
fun EyeChatScreen(
    onBack: () -> Unit,
    onOpenAccount: () -> Unit,
    home: HomeViewModel = hiltViewModel(),
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val homeState by home.uiState.collectAsStateWithLifecycle()
    val card by home.aiAdvice.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    EyeChatContent(
        state = state,
        onBack = onBack,
        onOpenAccount = onOpenAccount,
        onSend = { text, agree, quiet -> viewModel.send(text, homeState.adviceRequest, agree, quiet) },
        onClear = viewModel::clear,
    ) {
        card?.let { AiExplanationCard(it, onGenerate = { home.generateAdvice() }, onAgree = { home.generateAdvice(agreeFirst = true) },
            text = AiCardText.ADVICE) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EyeChatContent(
    state: ChatState?,
    onBack: () -> Unit,
    onOpenAccount: () -> Unit,
    /** 文字、是否先保存同意、是否勾了「以后不再提示」 */
    onSend: (String, Boolean, Boolean) -> Unit,
    onClear: () -> Unit,
    opening: @Composable () -> Unit,
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val scroll = rememberScrollState()
    var input by rememberSaveable { mutableStateOf("") }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    // 本次进入对话页是否已弹过同意说明；还没同意时每次发送都要弹
    var asked by rememberSaveable { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    val needsConsent = state?.access == ChatAccess.NEEDS_CONSENT
    val messages = state?.messages.orEmpty()
    fun send(agree: Boolean, quiet: Boolean) { onSend(input, agree, quiet); input = "" }
    LaunchedEffect(messages.size, state?.sending) { scroll.animateScrollTo(scroll.maxValue) }
    if (confirming) ChatConsentDialog(
        onAgree = { quiet -> confirming = false; asked = true; send(needsConsent, quiet) },
        onDecline = { confirming = false },
    )
    EyeSubPage(CHAT_TITLE, onBack, Modifier.imePadding(), scroll = scroll, bottomBar = {
        ChatInputBar(state, input, { input = it.take(Chats.INPUT_LIMIT) }, onOpenAccount) {
            if (needsConsent || (state?.askConsent == true && !asked)) confirming = true else send(agree = false, quiet = false)
        }
    }) {
        EyechoIntro()
        opening()
        if (messages.isEmpty()) {
            EyeText("可以这样问爱科", style = typography.bodySecondary, color = colors.textSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chats.SUGGESTIONS.forEach { text -> ChoiceChip(text, false) { input = text } }
            }
        }
        messages.forEachIndexed { index, entry ->
            ChatBubble(entry, active = index == messages.lastIndex && state?.sending != true &&
                state?.access != ChatAccess.NEEDS_ACCOUNT, onOption = { input = it })
        }
        if (state?.sending != true && messages.isNotEmpty()) {
            val pending = Chats.pendingQuestion(messages)
            val label = if (pending != null) Chats.STOP_INQUIRY else "开始新的问询"
            ChoiceChip(label, false) { input = if (pending != null) Chats.STOP_INQUIRY else Chats.START_INQUIRY }
        }
        if (state?.sending == true) Row(verticalAlignment = Alignment.CenterVertically) {
            EyechoAvatar(34.dp)
            Spacer(Modifier.width(8.dp))
            EyeText("爱科正在想…", style = typography.body, color = colors.textSecondary)
        }
        state?.error?.let { EyeText(it, style = typography.bodySecondary, color = colors.danger) }
        EyeText(Chats.DISCLAIMER, style = typography.bodySecondary, color = colors.textSecondary)
        if (messages.isNotEmpty()) {
            if (confirmClear) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EyeButton("清空本机记录", EyeButtonRole.DANGER, { onClear(); confirmClear = false }, Modifier.weight(1f))
                EyeButton("取消", EyeButtonRole.SECONDARY, { confirmClear = false }, Modifier.weight(1f))
            } else EyeText("清空对话", Modifier.clip(EyeShapes.chip).clickable(role = Role.Button) { confirmClear = true }
                .padding(vertical = 8.dp), style = typography.body, color = colors.primary)
        }
    }
}

/** 爱科 Eyecho 圆形头像（浅紫底）。与苹果端 EyechoAvatar 一致。 */
@Composable
internal fun EyechoAvatar(size: Dp) {
    Image(painterResource(R.drawable.eyecho_avatar), contentDescription = null,
        Modifier.size(size).clip(CircleShape).background(LocalEyeColors.current.violetSoft))
}

/** 对话页顶部：居中的爱科大头像 + 问候 + 一句介绍，不加卡片。与苹果端 EyechoIntro 一致。 */
@Composable
private fun EyechoIntro() {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Image(painterResource(R.drawable.eyecho_avatar), contentDescription = "爱科 Eyecho",
            Modifier.size(84.dp).clip(CircleShape).background(colors.violetSoft))
        EyeText(EYECHO_HELLO, Modifier.padding(top = 4.dp), style = typography.title, color = colors.textPrimary)
        EyeText(EYECHO_INTRO, style = typography.bodySecondary, color = colors.textSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

internal const val EYECHO_HELLO = "你好，我是爱科"
internal const val EYECHO_INTRO = "爱尔眼科 AI 助手。眼睛不舒服、用眼习惯、测试结果，都可以问我。"

/** 底部：未登录提示去登录；否则输入框 + 发送。 */
@Composable
private fun ChatInputBar(state: ChatState?, input: String, onInput: (String) -> Unit, onOpenAccount: () -> Unit, onSend: () -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val haptics = rememberEyeHaptics()
    when (state?.access) {
        null -> Unit
        ChatAccess.NEEDS_ACCOUNT -> {
            EyeText(state.accountMessage.orEmpty(), style = typography.body, color = colors.textSecondary)
            EyeButton("登录 / 注册", EyeButtonRole.PRIMARY, onOpenAccount, Modifier.fillMaxWidth())
        }
        else -> {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f).heightIn(min = 50.dp).eyeGlass(colors, EyeShapes.hero).padding(horizontal = 16.dp, vertical = 13.dp)) {
                    if (input.isEmpty()) EyeText("说说今天眼睛的感受…", style = typography.body, color = colors.textSecondary)
                    BasicTextField(input, onInput, Modifier.fillMaxWidth(), textStyle = typography.body.copy(color = colors.textPrimary),
                        cursorBrush = SolidColor(colors.primary), maxLines = 4)
                }
                EyeButton("发送", EyeButtonRole.PRIMARY, { haptics.tap(); onSend() },
                    enabled = input.isNotBlank() && !state.sending)
            }
        }
    }
}

/**
 * 发送前的同意说明弹窗：还没同意时每次发送都弹；已同意但没勾「以后不再提示」时，每次进入对话页第一次发送弹一次。
 * 「同意」后发送（[onAgree] 带上是否勾了「以后不再提示」），「不同意」只关闭、输入框里的字保留。与苹果端 ChatConsentView 同一文案。
 */
@Composable
internal fun ChatConsentDialog(onAgree: (Boolean) -> Unit, onDecline: () -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val haptics = rememberEyeHaptics()
    var quiet by rememberSaveable { mutableStateOf(false) }
    Dialog(onDismissRequest = onDecline) {
        Column(Modifier.fillMaxWidth().clip(EyeShapes.panel).background(colors.panelBackground).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EyechoAvatar(40.dp)
                Spacer(Modifier.width(10.dp))
                EyeText(Chats.CONSENT_TITLE, style = typography.title, color = colors.textPrimary)
            }
            EyeText(Chats.CONSENT_TEXT, style = typography.body, color = colors.textSecondary)
            EyeOptionRow(Chats.DONT_ASK_AGAIN, null, quiet, multiple = true) { haptics.tap(); quiet = !quiet }
            EyeButton("同意", EyeButtonRole.PRIMARY, { haptics.tap(); onAgree(quiet) }, Modifier.fillMaxWidth())
            EyeButton("不同意", EyeButtonRole.SECONDARY, onDecline, Modifier.fillMaxWidth())
        }
    }
}

/**
 * 一条对话（信息式气泡）：用户的靠右（品牌蓝底白字、右下角收尖），爱科的靠左（卡片底、左下角收尖，带参考资料）；
 * 提到危险信号时在提问下方给出「建议尽快就医」提示块。
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ChatBubble(entry: ChatEntry, active: Boolean = false, onOption: (String) -> Unit = {}) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val mine = entry.role == Chats.USER
    val shape = if (mine) androidx.compose.foundation.shape.RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp)
        else androidx.compose.foundation.shape.RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)
    Column(Modifier.fillMaxWidth().animateContentSize(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            if (!mine) { EyechoAvatar(28.dp); Spacer(Modifier.width(8.dp)) }
            Column(Modifier.widthIn(max = 290.dp).clip(shape).background(if (mine) colors.chatUserBackground else colors.panelBackground)
                .padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                EyeText(entry.text, style = typography.chatBody, color = if (mine) colors.chatUserText else colors.textPrimary)
                if (entry.inquiryComplete && !entry.urgent) EyeText("本次问询已总结", style = typography.bodySecondary, color = colors.primary)
                entry.question?.let { q ->
                    EyeText(q.prompt, style = typography.subtitle, color = colors.textPrimary)
                    if (active) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            q.options.forEach { option -> ChoiceChip(option, false) { onOption(option) } }
                        }
                        EyeText(if (q.allowFreeText) "选择选项或输入回答，然后点发送；也可以换个话题。" else "选择选项后点发送；也可以换个话题。",
                            style = typography.bodySecondary, color = colors.textSecondary)
                    } else if (q.options.isNotEmpty()) {
                        EyeText(q.options.joinToString(" / "), style = typography.bodySecondary, color = colors.textSecondary)
                    }
                }
                if (entry.sources.isNotEmpty()) EyeText("参考：" + entry.sources.joinToString("；") { "${it.title}（${it.publisher}）" },
                    style = typography.bodySecondary, color = colors.textSecondary)
            }
        }
        if (mine && entry.urgent) Row(Modifier.fillMaxWidth().clip(EyeShapes.card).background(colors.dangerSoft).padding(horizontal = 14.dp, vertical = 12.dp)) {
            EyeGlyphIcon(EyeGlyph.INFO, Modifier.padding(top = 1.dp).size(20.dp), colors.danger)
            Spacer(Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                EyeText("建议尽快就医", style = typography.body, color = colors.danger, fontWeight = FontWeight.SemiBold)
                EyeText(Chats.URGENT_NOTICE, style = typography.bodySecondary, color = colors.textPrimary)
            }
        }
    }
}

package com.zkyjss.eyeproject.feature.home

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
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
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.sync.AdviceRequest
import com.zkyjss.eyeproject.core.sync.ChatEntry
import com.zkyjss.eyeproject.core.sync.Chats
import com.zkyjss.eyeproject.core.ui.component.AiCardText
import com.zkyjss.eyeproject.core.ui.component.AiExplanationCard
import com.zkyjss.eyeproject.core.ui.component.EyeButton
import com.zkyjss.eyeproject.core.ui.component.EyeButtonRole
import com.zkyjss.eyeproject.core.ui.component.EyeOptionRow
import com.zkyjss.eyeproject.core.ui.component.EyeSubPage
import com.zkyjss.eyeproject.core.ui.component.EyeText
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

/** 首页「AI 综合建议」入口（放在「今日感受」上方）：爱科头像 + 一个按钮，点了进入对话页。与苹果端 ChatEntryCard 一致。 */
@Composable
internal fun ChatEntryCard(onOpen: () -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val haptics = rememberEyeHaptics()
    Row(Modifier.fillMaxWidth().clip(EyeShapes.panel).background(colors.panelBackground)
        .clickable(role = Role.Button) { haptics.tap(); onOpen() }.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
        EyechoAvatar(40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            EyeText("AI 综合建议", style = typography.subtitle, color = colors.textPrimary)
            EyeText("和爱科聊聊今天的用眼感受", style = typography.bodySecondary, color = colors.textSecondary)
        }
        EyeText("›", style = typography.title, color = colors.textSecondary)
    }
}

/**
 * 「AI 综合建议」对话页：由爱尔眼科数字人「爱科 Eyecho」主持。顶部是爱科的自我介绍，接着是今天的综合建议卡片，
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
    EyeSubPage("AI 综合建议", onBack, Modifier.imePadding(), scroll = scroll, bottomBar = {
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

/** 爱科 Eyecho 圆形头像（浅主色底）。与苹果端 EyechoAvatar 一致。 */
@Composable
internal fun EyechoAvatar(size: Dp) {
    Image(painterResource(R.drawable.eyecho_avatar), contentDescription = null,
        Modifier.size(size).clip(CircleShape).background(LocalEyeColors.current.primarySoft))
}

/** 对话页顶部：爱科半身像站在卡片底边上，右侧自我介绍。与苹果端 EyechoIntro 一致。 */
@Composable
private fun EyechoIntro() {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Row(Modifier.fillMaxWidth().clip(EyeShapes.panel).background(colors.primarySoft).padding(start = 16.dp, end = 16.dp, top = 12.dp),
        verticalAlignment = Alignment.Bottom) {
        Image(painterResource(R.drawable.eyecho_half), contentDescription = "爱科 Eyecho", Modifier.height(132.dp))
        Column(Modifier.weight(1f).padding(start = 14.dp, bottom = 16.dp).align(Alignment.CenterVertically),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            EyeText(EYECHO_HELLO, style = typography.title, color = colors.primary)
            EyeText(EYECHO_INTRO, style = typography.bodySecondary, color = colors.textSecondary)
        }
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
                Box(Modifier.weight(1f).clip(EyeShapes.panel).background(colors.panelBackground).padding(horizontal = 14.dp, vertical = 12.dp)) {
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

/** 一条对话：用户的靠右（浅主色底），爱科的靠左（白底，带参考资料）；提到危险信号时在提问下方醒目提示尽快就医。 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ChatBubble(entry: ChatEntry, active: Boolean = false, onOption: (String) -> Unit = {}) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val mine = entry.role == Chats.USER
    Column(Modifier.fillMaxWidth().animateContentSize(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            if (!mine) { EyechoAvatar(34.dp); Spacer(Modifier.width(8.dp)) }
            Column(Modifier.widthIn(max = 300.dp).clip(EyeShapes.panel).background(if (mine) colors.primarySoft else colors.panelBackground)
                .padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                EyeText(entry.text, style = typography.body, color = colors.textPrimary)
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
        if (mine && entry.urgent) EyeText(Chats.URGENT_NOTICE, Modifier.fillMaxWidth().clip(EyeShapes.medium).background(colors.dangerSoft).padding(12.dp),
            style = typography.bodySecondary, color = colors.danger, fontWeight = FontWeight.SemiBold)
    }
}

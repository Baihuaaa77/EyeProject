package com.zkyjss.eyeproject.feature.home

import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zkyjss.eyeproject.core.data.chat.ChatAccess
import com.zkyjss.eyeproject.core.data.chat.ChatRepository
import com.zkyjss.eyeproject.core.data.chat.ChatState
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
import com.zkyjss.eyeproject.core.ui.component.EyeGlyph
import com.zkyjss.eyeproject.core.ui.component.EyeGlyphIcon
import com.zkyjss.eyeproject.core.ui.component.EyeIconTile
import com.zkyjss.eyeproject.core.ui.component.EyeSubPage
import com.zkyjss.eyeproject.core.ui.component.EyeText
import com.zkyjss.eyeproject.core.ui.component.EyeTileTone
import com.zkyjss.eyeproject.core.ui.component.MascotMood
import com.zkyjss.eyeproject.core.ui.component.XiaoTong
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

    fun send(text: String, context: AdviceRequest?, agreeFirst: Boolean) { viewModelScope.launch { chat.send(text, context, agreeFirst) } }
    fun clear() { viewModelScope.launch { chat.clear() } }
}

/** 首页「AI 综合建议」入口（放在「今日感受」上方）：像一个输入框，点一下或点快捷提问就进入对话页。与苹果端 ChatEntryCard 一致。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatEntryCard(onOpen: (String?) -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val haptics = rememberEyeHaptics()
    Column(Modifier.fillMaxWidth().clip(EyeShapes.panel).background(colors.panelBackground).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { haptics.tap(); onOpen(null) }, verticalAlignment = Alignment.CenterVertically) {
            EyeIconTile(EyeGlyph.BULB, EyeTileTone.ACCENT, 32.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                EyeText("AI 综合建议", style = typography.subtitle, color = colors.textPrimary)
                EyeText("和小瞳聊聊今天的用眼感受", style = typography.bodySecondary, color = colors.textSecondary)
            }
            EyeText("›", style = typography.title, color = colors.textSecondary)
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(EyeShapes.chip).background(colors.appBackground)
            .clickable(role = Role.Button) { haptics.tap(); onOpen(null) }.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            EyeText("说说今天眼睛的感受…", Modifier.weight(1f), style = typography.body, color = colors.textSecondary)
            EyeGlyphIcon(EyeGlyph.CHAT, Modifier.size(20.dp), colors.primary)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chats.SUGGESTIONS.take(3).forEach { text -> ChoiceChip(text, false) { onOpen(text) } }
        }
    }
}

/**
 * 「AI 综合建议」对话页：开场是今天的综合建议卡片，下面和小瞳自由对话；记录只存在本机，可清空。
 * [draft] 为首页点的快捷提问（放进输入框，不自动发送）。与苹果端 AiChatPage 同一规则与文案。
 */
@Composable
fun EyeChatScreen(
    onBack: () -> Unit,
    onOpenAccount: () -> Unit,
    draft: String? = null,
    home: HomeViewModel = hiltViewModel(),
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val homeState by home.uiState.collectAsStateWithLifecycle()
    val card by home.aiAdvice.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    EyeChatContent(
        state = state,
        draft = draft,
        onBack = onBack,
        onOpenAccount = onOpenAccount,
        onSend = { text, agree -> viewModel.send(text, homeState.adviceRequest, agree) },
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
    draft: String?,
    onBack: () -> Unit,
    onOpenAccount: () -> Unit,
    onSend: (String, Boolean) -> Unit,
    onClear: () -> Unit,
    opening: @Composable () -> Unit,
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val scroll = rememberScrollState()
    var input by rememberSaveable { mutableStateOf(draft.orEmpty()) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val messages = state?.messages.orEmpty()
    LaunchedEffect(messages.size, state?.sending) { scroll.animateScrollTo(scroll.maxValue) }
    EyeSubPage("AI 综合建议", onBack, Modifier.imePadding(), scroll = scroll, bottomBar = {
        ChatInputBar(state, input, { input = it.take(Chats.MAX_INPUT) }, onOpenAccount) { agree ->
            onSend(input, agree); input = ""
        }
    }) {
        opening()
        if (messages.isEmpty()) {
            EyeText("可以这样问小瞳", style = typography.bodySecondary, color = colors.textSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chats.SUGGESTIONS.forEach { text -> ChoiceChip(text, false) { input = text } }
            }
        }
        messages.forEach { ChatBubble(it) }
        if (state?.sending == true) Row(verticalAlignment = Alignment.CenterVertically) {
            XiaoTong(MascotMood.HAPPY, Modifier.size(34.dp), interactive = false)
            Spacer(Modifier.width(8.dp))
            EyeText("小瞳正在想…", style = typography.body, color = colors.textSecondary)
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

/** 底部：未登录提示去登录；未同意时显示说明与「同意并发送」；否则输入框 + 发送。 */
@Composable
private fun ChatInputBar(state: ChatState?, input: String, onInput: (String) -> Unit, onOpenAccount: () -> Unit, onSend: (Boolean) -> Unit) {
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
            val consent = state.access == ChatAccess.NEEDS_CONSENT
            if (consent) EyeText(Chats.CONSENT_TEXT, style = typography.bodySecondary, color = colors.textSecondary)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f).clip(EyeShapes.panel).background(colors.panelBackground).padding(horizontal = 14.dp, vertical = 12.dp)) {
                    if (input.isEmpty()) EyeText("说说今天眼睛的感受…", style = typography.body, color = colors.textSecondary)
                    BasicTextField(input, onInput, Modifier.fillMaxWidth(), textStyle = typography.body.copy(color = colors.textPrimary),
                        cursorBrush = SolidColor(colors.primary), maxLines = 4)
                }
                EyeButton(if (consent) "同意并发送" else "发送", EyeButtonRole.PRIMARY, { haptics.tap(); onSend(consent) },
                    enabled = input.isNotBlank() && !state.sending)
            }
        }
    }
}

/** 一条对话：用户的靠右（浅主色底），小瞳的靠左（白底，带参考资料）；提到危险信号时在提问下方醒目提示尽快就医。 */
@Composable
private fun ChatBubble(entry: ChatEntry) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val mine = entry.role == Chats.USER
    Column(Modifier.fillMaxWidth().animateContentSize(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            if (!mine) { XiaoTong(MascotMood.HAPPY, Modifier.size(34.dp), interactive = false); Spacer(Modifier.width(8.dp)) }
            Column(Modifier.widthIn(max = 300.dp).clip(EyeShapes.panel).background(if (mine) colors.primarySoft else colors.panelBackground)
                .padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                EyeText(entry.text, style = typography.body, color = colors.textPrimary)
                if (entry.sources.isNotEmpty()) EyeText("参考：" + entry.sources.joinToString("；") { "${it.title}（${it.publisher}）" },
                    style = typography.bodySecondary, color = colors.textSecondary)
            }
        }
        if (mine && entry.urgent) EyeText(Chats.URGENT_NOTICE, Modifier.fillMaxWidth().clip(EyeShapes.medium).background(colors.dangerSoft).padding(12.dp),
            style = typography.bodySecondary, color = colors.danger, fontWeight = FontWeight.SemiBold)
    }
}

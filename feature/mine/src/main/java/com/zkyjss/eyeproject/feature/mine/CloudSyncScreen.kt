package com.zkyjss.eyeproject.feature.mine

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zkyjss.eyeproject.core.data.record.RecordSyncStatus
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.sync.ConsentType
import com.zkyjss.eyeproject.core.ui.component.EyeButton
import com.zkyjss.eyeproject.core.ui.component.EyeButtonRole
import com.zkyjss.eyeproject.core.ui.component.EyeGlyph
import com.zkyjss.eyeproject.core.ui.component.EyeIconTile
import com.zkyjss.eyeproject.core.ui.component.EyeListGroup
import com.zkyjss.eyeproject.core.ui.component.EyeListRow
import com.zkyjss.eyeproject.core.ui.component.EyeStatusChip
import com.zkyjss.eyeproject.core.ui.component.EyeSubPage
import com.zkyjss.eyeproject.core.ui.component.EyeSwitchRow
import com.zkyjss.eyeproject.core.ui.component.EyeText
import com.zkyjss.eyeproject.core.ui.component.EyeTileTone
import com.zkyjss.eyeproject.core.ui.component.StatusTone
import com.zkyjss.eyeproject.core.ui.component.rememberEyeHaptics

/** 云端同步页的全部用户操作，由 ViewModel 处理。 */
internal data class CloudSyncActions(
    val setConsent: (ConsentType, Boolean) -> Unit = { _, _ -> },
    val syncNow: () -> Unit = {},
    val export: () -> Unit = {},
)

/** 各项同意的说明（问号小窗；两端同一文案）。 */
internal object ConsentHelp {
    const val SYNC = "把测试记录与家庭成员资料加密保存到云端，换手机或多台设备时可以找回。关闭后不再上传，已上传的数据可在下方导出或注销账号删除。"
    const val GUARDIAN = "未满 14 岁孩子的测试记录，需要监护人同意后才会上传。"
    const val RESEARCH = "可选。同意后，去掉姓名、账号等身份信息的数据可用于改进眼健康研究；不同意不影响任何功能。"
    const val ADVICE = "生成 AI 综合建议时，把年龄段、职业、戴镜方式、既往眼病选项，近 7 天的用屏与休息概况，实时检测的眨眼与观看距离，" +
        "环境光与亮度等级，三项自测最近的等级，今天的用眼负荷与主要原因，以及你记录的感受发给第三方大模型（DeepSeek）；不发送姓名、账号、具体日期、照片或视频。"
    const val CHAT = "和小瞳聊天时，把你输入的文字、最近几轮对话，以及综合建议用到的去标识用眼概况发给第三方大模型（DeepSeek）回答；" +
        "服务器不保存对话内容，记录只在这台手机上；不发送姓名、账号、照片或视频。"
    const val AI = "生成 AI 解读时，把这次结果的等级、指标、判断依据、年龄段和几项相关资料选项发给第三方大模型（DeepSeek），不发送姓名、账号、照片或视频；风险等级仍由测试规则决定。"
}

/**
 * 「我的 → 云端同步」完整页面：同步状态与立即同步、分项同意（每项旁边问号说明）、导出云端数据、数据如何处理。
 * 登录、退出与注销在右上角的账户页。与苹果端 CloudSyncPage 同一排版与文案。
 */
@Composable
fun CloudSyncScreen(onBack: () -> Unit, onOpenAccount: () -> Unit, viewModel: CloudSyncViewModel = hiltViewModel()) {
    val state by viewModel.cloudState.collectAsStateWithLifecycle()
    val outcome by viewModel.outcome.collectAsStateWithLifecycle()
    val haptics = rememberEyeHaptics()
    LaunchedEffect(outcome?.id) { outcome?.let { if (it.success) haptics.success() else haptics.failure() } }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(viewModel::exportTo)
    }
    CloudSyncContent(state, CloudSyncActions(viewModel::setConsent, viewModel::syncNow) { exportLauncher.launch("eyeproject-cloud-export.json") },
        onBack, onOpenAccount)
}

@Composable
internal fun CloudSyncContent(state: CloudSyncUiState, actions: CloudSyncActions, onBack: () -> Unit, onOpenAccount: () -> Unit) {
    EyeSubPage("云端同步", onBack) {
        when (state) {
            CloudSyncUiState.Loading -> Unit
            is CloudSyncUiState.NotConfigured -> SignInPrompt("还没有连接服务器", "数据目前只保存在本机。在账户页填写服务器地址并登录后，即可开启云端同步。", onOpenAccount)
            is CloudSyncUiState.SignedOut -> SignInPrompt("登录后开启云端同步", "测试记录和家人资料可以在多台设备之间同步，本机记录始终保留。", onOpenAccount)
            is CloudSyncUiState.SignedIn -> SignedInPanel(state, actions)
        }
        DataHandlingGroup()
    }
}

@Composable
private fun SignInPrompt(title: String, text: String, onOpenAccount: () -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Column(Modifier.fillMaxWidth().clip(EyeShapes.panel).background(colors.panelBackground).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        EyeIconTile(EyeGlyph.CLOUD, EyeTileTone.BLUE, 48.dp)
        EyeText(title, style = typography.subtitle, color = colors.textPrimary)
        EyeText(text, style = typography.body, color = colors.textSecondary)
        EyeButton("去登录", EyeButtonRole.PRIMARY, onOpenAccount, Modifier.fillMaxWidth())
    }
}

@Composable
private fun SignedInPanel(state: CloudSyncUiState.SignedIn, actions: CloudSyncActions) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val haptics = rememberEyeHaptics()
    Column(Modifier.fillMaxWidth().clip(EyeShapes.panel).background(colors.panelBackground).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                EyeText("已上传", style = typography.body, color = colors.textSecondary)
                Row(verticalAlignment = Alignment.Bottom) {
                    EyeText("${state.status.synced}", style = typography.headline, color = colors.textPrimary)
                    EyeText(" 条记录", Modifier.padding(bottom = 6.dp), style = typography.body, color = colors.textSecondary)
                }
            }
            val (label, tone) = if (state.healthDataSync) "已开启" to StatusTone.SUCCESS else "未开启" to StatusTone.NEUTRAL
            EyeStatusChip(label, tone, live = state.busy)
        }
        statusLine(state.status, state.busy)?.let { EyeText(it, style = typography.body, color = colors.textSecondary) }
        state.message?.let { EyeText(it, style = typography.body, color = colors.textSecondary) }
        EyeButton(if (state.busy) "正在同步…" else "立即同步", EyeButtonRole.PRIMARY, { haptics.tap(); actions.syncNow() }, Modifier.fillMaxWidth(),
            enabled = !state.busy && state.healthDataSync)
    }
    EyeListGroup(title = "授权") {
        EyeSwitchRow("云端保存记录与资料", state.healthDataSync, { actions.setConsent(ConsentType.HEALTH_DATA_SYNC, it) },
            glyph = EyeGlyph.CLOUD, tone = EyeTileTone.BLUE, enabled = !state.busy, help = "云端保存" to ConsentHelp.SYNC)
        EyeSwitchRow("监护人同意", state.guardian, { actions.setConsent(ConsentType.GUARDIAN, it) },
            glyph = EyeGlyph.FAMILY, enabled = !state.busy, help = "监护人同意" to ConsentHelp.GUARDIAN)
        EyeSwitchRow("用于研究", state.researchUse, { actions.setConsent(ConsentType.RESEARCH_USE, it) },
            glyph = EyeGlyph.CHART, tone = EyeTileTone.SUCCESS, enabled = !state.busy, help = "用于研究" to ConsentHelp.RESEARCH)
        EyeSwitchRow("AI 解读", state.aiExplanation, { actions.setConsent(ConsentType.AI_EXPLANATION, it) },
            glyph = EyeGlyph.BULB, tone = EyeTileTone.ACCENT, enabled = !state.busy, help = "AI 解读" to ConsentHelp.AI)
        EyeSwitchRow("AI 综合建议", state.aiAdvice, { actions.setConsent(ConsentType.AI_ADVICE, it) },
            glyph = EyeGlyph.CHAT, tone = EyeTileTone.BLUE, enabled = !state.busy, help = "AI 综合建议" to ConsentHelp.ADVICE)
        EyeSwitchRow("AI 对话", state.aiChat, { actions.setConsent(ConsentType.AI_CHAT, it) },
            glyph = EyeGlyph.CHAT, tone = EyeTileTone.PRIMARY, enabled = !state.busy, help = "AI 对话" to ConsentHelp.CHAT, divider = false)
    }
    EyeListGroup(title = "数据") {
        EyeListRow("导出云端数据", onClick = actions.export, glyph = EyeGlyph.EXPORT, tone = EyeTileTone.NEUTRAL, enabled = !state.busy, divider = false)
    }
}

/** 「数据如何处理」：点开展开五条要点（两端同一文案）。 */
@Composable
private fun DataHandlingGroup() {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    var open by rememberSaveable { mutableStateOf(false) }
    EyeListGroup {
        EyeListRow("数据如何处理", onClick = { open = !open }, glyph = EyeGlyph.SHIELD, tone = EyeTileTone.SUCCESS,
            value = if (open) "收起" else null, divider = open)
        if (open) Column(Modifier.padding(start = 66.dp, end = 16.dp, top = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DATA_HANDLING.forEach { EyeText("· $it", style = typography.body, color = colors.textSecondary) }
        }
    }
    Spacer(Modifier.height(0.dp))
}

private fun statusLine(status: RecordSyncStatus, busy: Boolean): String? = buildList {
    if (status.pending > 0) add("待上传 ${status.pending} 条")
    if (status.awaitingGuardian > 0) add("${status.awaitingGuardian} 条待监护人同意")
    if (status.failed > 0) add("${status.failed} 条未能上传")
    if (busy) add("正在同步…")
}.takeIf { it.isNotEmpty() }?.joinToString(" · ")

/** 「我的」入口行显示的云端状态。 */
internal fun cloudSummary(configured: Boolean, email: String?, healthDataSync: Boolean): String = when {
    !configured -> "仅本机"
    email == null -> "未登录"
    healthDataSync -> "已开启"
    else -> "未开启"
}

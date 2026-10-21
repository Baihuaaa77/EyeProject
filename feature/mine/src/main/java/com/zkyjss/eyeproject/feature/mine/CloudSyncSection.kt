package com.zkyjss.eyeproject.feature.mine

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.data.record.RecordSyncStatus
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.sync.ConsentType
import com.zkyjss.eyeproject.core.ui.component.EyeButton
import com.zkyjss.eyeproject.core.ui.component.EyeButtonRole
import com.zkyjss.eyeproject.core.ui.component.EyeSectionCard
import com.zkyjss.eyeproject.core.ui.component.EyeStatusChip
import com.zkyjss.eyeproject.core.ui.component.EyeSwitch
import com.zkyjss.eyeproject.core.ui.component.EyeText
import com.zkyjss.eyeproject.core.ui.component.EyeTextField
import com.zkyjss.eyeproject.core.ui.component.StatusTone
import com.zkyjss.eyeproject.core.ui.component.rememberEyeHaptics

/** 云端同步区的全部用户操作，由 ViewModel 处理。 */
internal data class CloudSyncActions(
    val signIn: (String, String) -> Unit = { _, _ -> },
    val register: (String, String) -> Unit = { _, _ -> },
    val signOut: () -> Unit = {},
    val setConsent: (ConsentType, Boolean) -> Unit = { _, _ -> },
    val syncNow: () -> Unit = {},
    val export: () -> Unit = {},
    val deleteCloudAccount: () -> Unit = {},
    val setServer: (String) -> Unit = {},
)

/**
 * 「云端同步」：标题右侧一个状态徽标（未配置 / 未登录 / 已开启 / 仅本机），正文只放必要的输入与开关；
 * 数据处理说明收在「数据如何处理」里。与苹果端 CloudSection 同一排版与文案。
 */
@Composable
internal fun CloudSyncSection(state: CloudSyncUiState, actions: CloudSyncActions, serverUrl: String = "") {
    EyeSectionCard {
        Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val (label, tone) = when (state) {
                is CloudSyncUiState.SignedIn -> if (state.healthDataSync) "已开启" to StatusTone.SUCCESS else "仅本机" to StatusTone.NEUTRAL
                is CloudSyncUiState.SignedOut -> "未登录" to StatusTone.NEUTRAL
                else -> "未配置" to StatusTone.NEUTRAL
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                EyeText("云端同步", Modifier.weight(1f), style = LocalEyeTypography.current.subtitle, color = LocalEyeColors.current.textPrimary)
                EyeStatusChip(label, tone, live = (state as? CloudSyncUiState.SignedIn)?.busy == true)
            }
            when (state) {
                CloudSyncUiState.Loading -> Unit
                is CloudSyncUiState.NotConfigured -> {
                    Note("未连接服务器，数据只保存在本机。")
                    ServerRow(serverUrl, busy = false, actions.setServer)
                    state.message?.let { Note(it) }
                }
                is CloudSyncUiState.SignedOut -> SignedOutForm(state, actions, serverUrl)
                is CloudSyncUiState.SignedIn -> SignedInPanel(state, actions)
            }
        }
    }
}

/** 服务器地址：局域网试用填电脑地址（如 http://192.168.1.5:8080），正式部署填 https 域名。 */
@Composable
private fun ServerRow(serverUrl: String, busy: Boolean, onSave: (String) -> Unit) {
    val haptics = rememberEyeHaptics()
    var text by rememberSaveable(serverUrl) { mutableStateOf(serverUrl) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EyeTextField(text, { text = it }, label = "服务器地址", Modifier.weight(1f), placeholder = "http://192.168.1.5:8080",
            keyboardType = KeyboardType.Uri, enabled = !busy)
        EyeButton("保存", EyeButtonRole.SECONDARY, { haptics.tap(); onSave(text) }, enabled = !busy && text.trim() != serverUrl)
    }
}

@Composable
private fun SignedOutForm(state: CloudSyncUiState.SignedOut, actions: CloudSyncActions, serverUrl: String) {
    val haptics = rememberEyeHaptics()
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var agreed by rememberSaveable { mutableStateOf(false) }
    EyeTextField(email, { email = it }, label = "邮箱", keyboardType = KeyboardType.Email, enabled = !state.busy)
    EyeTextField(password, { password = it }, label = "密码（至少 8 位，含字母和数字）", password = true, enabled = !state.busy)
    ToggleRow("同意隐私政策", agreed, !state.busy) { agreed = it }
    state.message?.let { Note(it) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        EyeButton("登录", EyeButtonRole.PRIMARY, { haptics.tap(); actions.signIn(email, password) }, Modifier.weight(1f),
            enabled = !state.busy && email.isNotBlank() && password.isNotEmpty())
        EyeButton("注册", EyeButtonRole.SECONDARY, { haptics.tap(); actions.register(email, password) }, Modifier.weight(1f),
            enabled = !state.busy && agreed && email.isNotBlank() && password.isNotEmpty())
    }
    MoreSettings(serverUrl, state.busy, actions)
}

@Composable
private fun SignedInPanel(state: CloudSyncUiState.SignedIn, actions: CloudSyncActions) {
    val haptics = rememberEyeHaptics()
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(confirmDelete) { if (confirmDelete) haptics.failure() }
    EyeText(state.email, style = LocalEyeTypography.current.body, color = LocalEyeColors.current.textPrimary)
    ToggleRow("云端保存测试记录与资料", state.healthDataSync, !state.busy) { actions.setConsent(ConsentType.HEALTH_DATA_SYNC, it) }
    ToggleRow("监护人同意（未满 14 岁）", state.guardian, !state.busy) { actions.setConsent(ConsentType.GUARDIAN, it) }
    ToggleRow("用于研究（可选，去标识）", state.researchUse, !state.busy) { actions.setConsent(ConsentType.RESEARCH_USE, it) }
    Note(statusLine(state.status, state.busy))
    state.message?.let { Note(it) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        EyeButton("立即同步", EyeButtonRole.PRIMARY, { haptics.tap(); actions.syncNow() }, Modifier.weight(1f),
            enabled = !state.busy && state.healthDataSync)
        EyeButton("导出数据", EyeButtonRole.SECONDARY, { haptics.tap(); actions.export() }, Modifier.weight(1f), enabled = !state.busy)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        EyeButton("退出登录", EyeButtonRole.SECONDARY, { haptics.tap(); actions.signOut() }, Modifier.weight(1f), enabled = !state.busy)
        EyeButton(if (confirmDelete) "确认注销" else "注销账号", EyeButtonRole.DANGER,
            { if (confirmDelete) { confirmDelete = false; actions.deleteCloudAccount() } else confirmDelete = true },
            Modifier.weight(1f), enabled = !state.busy)
    }
    if (confirmDelete) Note("将永久删除云端账号与全部已上传数据，本机记录保留。")
    MoreSettings(null, state.busy, actions)
}

/** 收起的次要内容：数据如何处理、服务器地址（未登录时可改）。 */
@Composable
private fun MoreSettings(serverUrl: String?, busy: Boolean, actions: CloudSyncActions) {
    val haptics = rememberEyeHaptics()
    var open by rememberSaveable { mutableStateOf(false) }
    EyeText(if (open) "收起 ▴" else if (serverUrl != null) "数据如何处理 · 服务器设置 ▾" else "数据如何处理 ▾",
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button) { haptics.tap(); open = !open }.padding(vertical = 10.dp),
        style = LocalEyeTypography.current.body, color = LocalEyeColors.current.primary)
    if (open) {
        DATA_HANDLING.forEach { Note("· $it") }
        if (serverUrl != null) ServerRow(serverUrl, busy, actions.setServer)
    }
}

/** 数据处理要点（两端同一文案）；正式隐私政策文本待法务提供后替换。 */
internal val DATA_HANDLING = listOf(
    "上传测试记录与成员资料，不收集身份证号、照片或视频。",
    "传输加密，云端以独立密钥加密保存，查看、导出、删除都有审计记录。",
    "可随时关闭上传、导出全部云端数据，或注销账号删除云端数据；本机记录始终保留。",
    "未满 14 岁孩子的数据需监护人同意才上传；研究用途需单独同意。",
)

private fun statusLine(status: RecordSyncStatus, busy: Boolean): String = buildList {
    add("已上传 ${status.synced} 条")
    if (status.pending > 0) add("待上传 ${status.pending} 条")
    if (status.awaitingGuardian > 0) add("${status.awaitingGuardian} 条待监护人同意")
    if (status.failed > 0) add("${status.failed} 条未能上传")
    if (busy) add("正在同步…")
}.joinToString(" · ")

@Composable
private fun ToggleRow(text: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val haptics = rememberEyeHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Switch) { haptics.toggle(!checked); onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EyeText(text, style = LocalEyeTypography.current.body, color = LocalEyeColors.current.textPrimary, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        EyeSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun Note(text: String) {
    EyeText(text, style = LocalEyeTypography.current.bodySecondary, color = LocalEyeColors.current.textSecondary)
}

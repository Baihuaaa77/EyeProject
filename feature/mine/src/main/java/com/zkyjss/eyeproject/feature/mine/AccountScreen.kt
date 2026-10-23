package com.zkyjss.eyeproject.feature.mine

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zkyjss.eyeproject.core.data.account.AccountState
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.ui.component.EyeButton
import com.zkyjss.eyeproject.core.ui.component.EyeButtonRole
import com.zkyjss.eyeproject.core.ui.component.EyeGlyph
import com.zkyjss.eyeproject.core.ui.component.EyeHelpButton
import com.zkyjss.eyeproject.core.ui.component.EyeListGroup
import com.zkyjss.eyeproject.core.ui.component.EyeListRow
import com.zkyjss.eyeproject.core.ui.component.EyeSegmentedRow
import com.zkyjss.eyeproject.core.ui.component.EyeStatusChip
import com.zkyjss.eyeproject.core.ui.component.EyeSubPage
import com.zkyjss.eyeproject.core.ui.component.EyeSwitch
import com.zkyjss.eyeproject.core.ui.component.EyeText
import com.zkyjss.eyeproject.core.ui.component.EyeTextField
import com.zkyjss.eyeproject.core.ui.component.EyeTileTone
import com.zkyjss.eyeproject.core.ui.component.PersonGlyph
import com.zkyjss.eyeproject.core.ui.component.SegmentedOption
import com.zkyjss.eyeproject.core.ui.component.StatusTone
import com.zkyjss.eyeproject.core.ui.component.rememberEyeHaptics

/** 账户页的全部操作，由 ViewModel 处理。 */
internal data class AccountActions(
    val signIn: (String, String) -> Unit = { _, _ -> },
    val register: (String, String) -> Unit = { _, _ -> },
    val signOut: () -> Unit = {},
    val deleteCloudAccount: () -> Unit = {},
    val setServer: (String) -> Unit = {},
)

/** 隐私要点（注册前的「?」说明与云端同步页共用，两端同一文案）；正式隐私政策文本待法务提供后替换。 */
internal val DATA_HANDLING = listOf(
    "上传测试记录与成员资料，不收集身份证号、照片或视频。",
    "传输加密，云端以独立密钥加密保存，查看、导出、删除都有审计记录。",
    "可随时关闭上传、导出全部云端数据，或注销账号删除云端数据；本机记录始终保留。",
    "未满 14 岁孩子的数据需监护人同意才上传；研究用途需单独同意。",
    "AI 功能需单独同意：只发送去标识的结果与概况，由第三方大模型生成文字，风险等级仍由测试规则决定。",
)

internal const val SERVER_HELP = "局域网试用时填电脑上运行的服务地址，如 http://192.168.1.5:8080；正式上线后填 https 域名。更换地址会退出登录。"

/**
 * 账户（右上角人像按钮进入的完整页面）：账号概况、登录 / 注册、退出登录、注销账号与服务器地址。
 * 分项同意、立即同步与导出在「我的 → 云端同步」。与苹果端 AccountPage 同一排版与文案。
 */
@Composable
fun AccountScreen(onBack: () -> Unit, onOpenCloudSync: () -> Unit, viewModel: AccountViewModel = hiltViewModel()) {
    val account by viewModel.account.collectAsStateWithLifecycle()
    val busy by viewModel.busyState.collectAsStateWithLifecycle()
    val message by viewModel.messageState.collectAsStateWithLifecycle()
    val outcome by viewModel.outcome.collectAsStateWithLifecycle()
    val haptics = rememberEyeHaptics()
    LaunchedEffect(outcome?.id) { outcome?.let { if (it.success) haptics.success() else haptics.failure() } }
    AccountContent(account, busy, message, AccountActions(viewModel::signIn, viewModel::register, viewModel::signOut,
        viewModel::deleteCloudAccount, viewModel::setServer), onBack, onOpenCloudSync)
}

@Composable
internal fun AccountContent(
    account: AccountState?,
    busy: Boolean,
    message: String?,
    actions: AccountActions,
    onBack: () -> Unit,
    onOpenCloudSync: () -> Unit = {},
) {
    EyeSubPage("账户", onBack) {
        AccountHeader(account)
        when {
            account == null -> Unit
            !account.cloudConfigured -> ServerGroup(account.serverUrl, busy, actions.setServer, emphasize = true)
            account.email == null -> {
                SignInForm(busy, message, actions)
                ServerGroup(account.serverUrl, busy, actions.setServer)
            }
            else -> SignedInGroups(account, busy, message, actions, onOpenCloudSync)
        }
        if (account != null && !account.cloudConfigured && message != null) Note(message)
    }
}

/** 顶部：头像（首字母或人像）+ 邮箱 / 未登录 + 状态徽标。 */
@Composable
private fun AccountHeader(account: AccountState?) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val email = account?.email
    Row(
        Modifier.fillMaxWidth().clip(EyeShapes.panel)
            .background(Brush.linearGradient(listOf(colors.primarySoft, colors.panelBackground)))
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(60.dp).clip(CircleShape).background(colors.panelBackground), contentAlignment = Alignment.Center) {
            if (email != null) EyeText(email.take(1).uppercase(), style = typography.title, color = colors.primary)
            else PersonGlyph(Modifier.size(30.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            EyeText(email ?: "未登录", style = typography.subtitle, color = colors.textPrimary, maxLines = 1)
            val (label, tone) = when {
                account == null -> "读取中" to StatusTone.NEUTRAL
                !account.cloudConfigured -> "未连接服务器" to StatusTone.NEUTRAL
                email == null -> "数据只在本机" to StatusTone.NEUTRAL
                account.healthDataSync -> "云端同步已开启" to StatusTone.SUCCESS
                else -> "已登录 · 仅本机" to StatusTone.NEUTRAL
            }
            EyeStatusChip(label, tone)
        }
    }
}

@Composable
private fun SignInForm(busy: Boolean, message: String?, actions: AccountActions) {
    val colors = LocalEyeColors.current
    val haptics = rememberEyeHaptics()
    var mode by rememberSaveable { mutableStateOf(0) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var agreed by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(EyeShapes.panel).background(colors.panelBackground).padding(20.dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        EyeSegmentedRow(listOf(SegmentedOption(0, "登录"), SegmentedOption(1, "注册")), mode, { mode = it })
        EyeTextField(email, { email = it }, label = "邮箱", placeholder = "name@example.com", keyboardType = KeyboardType.Email, enabled = !busy)
        EyeTextField(password, { password = it }, label = "密码", placeholder = if (mode == 1) "至少 8 位，含字母和数字" else "", password = true, enabled = !busy)
        if (mode == 1) Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(agreed, enabled = !busy, role = Role.Switch) { haptics.toggle(it); agreed = it },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EyeText("同意隐私政策", style = LocalEyeTypography.current.body, color = colors.textPrimary)
            EyeHelpButton("隐私政策要点", DATA_HANDLING.joinToString("\n"))
            Spacer(Modifier.weight(1f))
            EyeSwitch(agreed, null, enabled = !busy)
        }
        message?.let { Note(it) }
        if (mode == 0) EyeButton("登录", EyeButtonRole.PRIMARY, { haptics.tap(); actions.signIn(email.trim(), password) }, Modifier.fillMaxWidth(),
            enabled = !busy && email.isNotBlank() && password.isNotEmpty())
        else EyeButton("注册", EyeButtonRole.PRIMARY, { haptics.tap(); actions.register(email.trim(), password) }, Modifier.fillMaxWidth(),
            enabled = !busy && agreed && email.isNotBlank() && password.isNotEmpty())
    }
}

@Composable
private fun SignedInGroups(account: AccountState, busy: Boolean, message: String?, actions: AccountActions, onOpenCloudSync: () -> Unit) {
    val colors = LocalEyeColors.current
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val haptics = rememberEyeHaptics()
    LaunchedEffect(confirmDelete) { if (confirmDelete) haptics.failure() }
    EyeListGroup(title = "账号") {
        EyeListRow("邮箱", onClick = null, glyph = EyeGlyph.PERSON, value = account.email)
        EyeListRow("云端同步", onClick = onOpenCloudSync, glyph = EyeGlyph.CLOUD, tone = EyeTileTone.BLUE,
            value = if (account.healthDataSync) "已开启" else "未开启", divider = false)
    }
    message?.let { Note(it) }
    EyeListGroup {
        EyeListRow("退出登录", onClick = actions.signOut, glyph = EyeGlyph.LOGOUT, tone = EyeTileTone.NEUTRAL, enabled = !busy)
        EyeListRow(if (confirmDelete) "确认注销账号" else "注销账号", onClick = {
            if (confirmDelete) { confirmDelete = false; actions.deleteCloudAccount() } else confirmDelete = true
        }, glyph = EyeGlyph.TRASH, tone = EyeTileTone.DANGER, titleColor = colors.danger, enabled = !busy, divider = false,
            subtitle = if (confirmDelete) "将永久删除云端账号与全部已上传数据，本机记录保留" else null)
    }
    EyeListGroup(title = "服务器") {
        EyeListRow("服务器地址", onClick = null, glyph = EyeGlyph.SERVER, tone = EyeTileTone.NEUTRAL, value = account.serverUrl, divider = false)
    }
}

/** 服务器地址：未登录时可修改（更换会退出登录）。 */
@Composable
private fun ServerGroup(serverUrl: String, busy: Boolean, onSave: (String) -> Unit, emphasize: Boolean = false) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val haptics = rememberEyeHaptics()
    var text by rememberSaveable(serverUrl) { mutableStateOf(serverUrl) }
    Column(Modifier.fillMaxWidth().clip(EyeShapes.panel).background(colors.panelBackground).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EyeText("服务器", style = typography.subtitle, color = colors.textPrimary)
            EyeHelpButton("服务器地址", SERVER_HELP)
        }
        if (emphasize) EyeText("连接服务器后才能登录；不连接时数据只保存在本机。", style = typography.body, color = colors.textSecondary)
        EyeTextField(text, { text = it }, label = "服务器地址", placeholder = "http://192.168.1.5:8080", keyboardType = KeyboardType.Uri, enabled = !busy)
        // 与当前生效地址相同时显示「已保存」，改动后才可点「保存」（与苹果端一致）
        val saved = text.trim() == serverUrl
        EyeButton(if (saved && text.isNotEmpty()) "已保存" else "保存", if (emphasize) EyeButtonRole.PRIMARY else EyeButtonRole.SECONDARY,
            { haptics.tap(); onSave(text) }, Modifier.fillMaxWidth(), enabled = !busy && !saved)
    }
}

@Composable
internal fun Note(text: String) {
    EyeText(text, Modifier.padding(horizontal = 4.dp), style = LocalEyeTypography.current.body, color = LocalEyeColors.current.textSecondary)
}

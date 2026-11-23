package com.zkyjss.eyeproject.feature.mine

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.padding
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zkyjss.eyeproject.core.data.account.AccountState
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.ui.component.EyeButton
import com.zkyjss.eyeproject.core.ui.component.eyeGlass
import com.zkyjss.eyeproject.core.ui.component.EyeButtonRole
import com.zkyjss.eyeproject.core.ui.component.EyeGlyph
import com.zkyjss.eyeproject.core.ui.component.EyeHelpButton
import com.zkyjss.eyeproject.core.ui.component.EyeListGroup
import com.zkyjss.eyeproject.core.ui.component.EyeListRow
import com.zkyjss.eyeproject.core.ui.component.EyeSegmentedRow
import com.zkyjss.eyeproject.core.ui.component.EyeSubPage
import com.zkyjss.eyeproject.core.ui.component.EyeSwitch
import com.zkyjss.eyeproject.core.ui.component.EyeText
import com.zkyjss.eyeproject.core.ui.component.EyeTextField
import com.zkyjss.eyeproject.core.ui.component.EyeTileTone
import com.zkyjss.eyeproject.core.ui.component.PersonGlyph
import com.zkyjss.eyeproject.core.ui.component.SegmentedOption
import com.zkyjss.eyeproject.core.ui.component.rememberEyeHaptics

/** 账户页的全部操作，由 ViewModel 处理。 */
internal data class AccountActions(
    val signIn: (String, String) -> Unit = { _, _ -> },
    val register: (String, String) -> Unit = { _, _ -> },
    val signOut: () -> Unit = {},
    val deleteCloudAccount: () -> Unit = {},
)

/** 隐私要点（注册前的「?」说明与云端同步页共用，两端同一文案）；正式隐私政策文本待法务提供后替换。 */
internal val DATA_HANDLING = listOf(
    "上传测试记录与成员资料，不收集身份证号、照片或视频。",
    "传输加密，云端以独立密钥加密保存，查看、导出、删除都有审计记录。",
    "可随时关闭上传、导出全部云端数据，或注销账号删除云端数据；本机记录始终保留。",
    "未满 14 岁孩子的数据需监护人同意才上传；研究用途需单独同意。",
    "AI 功能需单独同意：只发送去标识的结果与概况，由第三方大模型生成文字，风险等级仍由测试规则决定。",
)

/**
 * 账户（右上角人像按钮进入的完整页面）：账号概况、登录 / 注册、退出登录与注销账号。
 * 分项同意、立即同步与导出在「我的 → 云端同步」。与苹果端 AccountPage 同一排版与文案。
 */
@Composable
fun AccountScreen(onBack: () -> Unit, onOpenCloudSync: () -> Unit, onEditSubject: (String?) -> Unit = {}, viewModel: AccountViewModel = hiltViewModel()) {
    val people by viewModel.people.collectAsStateWithLifecycle()
    val self = people.firstOrNull { it.relation == "SELF" }
    val account by viewModel.account.collectAsStateWithLifecycle()
    val busy by viewModel.busyState.collectAsStateWithLifecycle()
    val message by viewModel.messageState.collectAsStateWithLifecycle()
    val outcome by viewModel.outcome.collectAsStateWithLifecycle()
    val haptics = rememberEyeHaptics()
    LaunchedEffect(outcome?.id) { outcome?.let { if (it.success) haptics.success() else haptics.failure() } }
    AccountContent(account, busy, message, AccountActions(viewModel::signIn, viewModel::register, viewModel::signOut,
        viewModel::deleteCloudAccount), onBack, onOpenCloudSync, self?.nickname ?: "小林", { onEditSubject(self?.id) })
}

@Composable
internal fun AccountContent(
    account: AccountState?,
    busy: Boolean,
    message: String?,
    actions: AccountActions,
    onBack: () -> Unit,
    onOpenCloudSync: () -> Unit = {},
    nickname: String = "小林",
    onEditProfile: () -> Unit = {},
) {
    EyeSubPage("我的账户", onBack) {
        EyeListRow(nickname, onEditProfile, subtitle = "我的资料", divider = false)
        when {
            account == null -> Note("正在读取账户")
            account.email == null -> SignInForm(busy, message, actions, account.cloudConfigured)
            else -> SignedInGroups(account, busy, message, actions, onOpenCloudSync)
        }
    }
}

/** 登录 / 注册表单：账户页与启动时的登录引导（WelcomeScreen）共用。 */
@Composable
internal fun SignInForm(busy: Boolean, message: String?, actions: AccountActions, cloudConfigured: Boolean = true) {
    val colors = LocalEyeColors.current
    val haptics = rememberEyeHaptics()
    var method by rememberSaveable { mutableStateOf(0) }
    var mode by rememberSaveable { mutableStateOf(0) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var agreed by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().eyeGlass(colors, EyeShapes.panel).padding(20.dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        EyeText("登录账户", style = LocalEyeTypography.current.title, color = colors.textPrimary)
        EyeSegmentedRow(
            listOf(SegmentedOption(0, "邮箱"), SegmentedOption(1, "手机号"), SegmentedOption(2, "微信")),
            method,
            { method = it },
        )
        when (method) {
            1 -> Note("手机号一键登录待接入运营商认证。")
            2 -> Note("微信一键登录待接入微信开放平台。")
            else -> if (cloudConfigured) {
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
            } else {
                Note("登录服务暂不可用，记录保存在本机。")
            }
        }
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
}

@Composable
internal fun Note(text: String) {
    EyeText(text, Modifier.padding(horizontal = 4.dp), style = LocalEyeTypography.current.body, color = LocalEyeColors.current.textSecondary)
}

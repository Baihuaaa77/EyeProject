package com.zkyjss.eyeproject.feature.mine

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
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
import com.zkyjss.eyeproject.core.sync.SyncContract
import com.zkyjss.eyeproject.core.ui.component.EyeButton
import com.zkyjss.eyeproject.core.ui.component.EyeDisclosure
import com.zkyjss.eyeproject.core.ui.component.EyeButtonRole
import com.zkyjss.eyeproject.core.ui.component.EyeSectionCard
import com.zkyjss.eyeproject.core.ui.component.EyeStatusChip
import com.zkyjss.eyeproject.core.ui.component.EyeSwitch
import com.zkyjss.eyeproject.core.ui.component.EyeText
import com.zkyjss.eyeproject.core.ui.component.EyeTextField
import com.zkyjss.eyeproject.core.ui.component.StatusTone

/** 云端同步区的全部用户操作，由 ViewModel 处理。 */
internal data class CloudSyncActions(
    val signIn: (String, String) -> Unit = { _, _ -> },
    val register: (String, String) -> Unit = { _, _ -> },
    val signOut: () -> Unit = {},
    val setConsent: (ConsentType, Boolean) -> Unit = { _, _ -> },
    val syncNow: () -> Unit = {},
    val export: () -> Unit = {},
    val deleteCloudAccount: () -> Unit = {},
)

/** 「云端同步与数据」：未配置 / 未登录 / 已登录三态；所有上传都以用户分项同意为前提。 */
@Composable
internal fun CloudSyncSection(state: CloudSyncUiState, actions: CloudSyncActions) {
    EyeSectionCard(title = "云端同步与数据") {
        when (state) {
            CloudSyncUiState.Loading -> Unit
            CloudSyncUiState.NotConfigured -> Note("本版本未配置云端服务。测试记录只保存在这台手机上，不会上传。")
            is CloudSyncUiState.SignedOut -> SignedOutForm(state, actions)
            is CloudSyncUiState.SignedIn -> SignedInPanel(state, actions)
        }
    }
}

@Composable
private fun SignedOutForm(state: CloudSyncUiState.SignedOut, actions: CloudSyncActions) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var agreed by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Note("登录后可在多台设备间保存和对比测试记录。不登录也能正常使用全部测试。")
        EyeTextField(email, { email = it }, label = "邮箱", keyboardType = KeyboardType.Email, enabled = !state.busy)
        EyeTextField(password, { password = it }, label = "密码（至少 ${SyncContract.MIN_PASSWORD_LENGTH} 位，含字母和数字）",
            password = true, enabled = !state.busy)
        DataHandlingNotice()
        ToggleRow("我已阅读上述说明并同意隐私政策（注册时需要）", agreed, !state.busy) { agreed = it }
        state.message?.let { Note(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EyeButton("登录", EyeButtonRole.PRIMARY, { actions.signIn(email, password) }, Modifier.weight(1f),
                enabled = !state.busy && email.isNotBlank() && password.isNotEmpty())
            EyeButton("注册", EyeButtonRole.SECONDARY, { actions.register(email, password) }, Modifier.weight(1f),
                enabled = !state.busy && agreed && email.isNotBlank() && password.isNotEmpty())
        }
    }
}

@Composable
private fun SignedInPanel(state: CloudSyncUiState.SignedIn, actions: CloudSyncActions) {
    var confirmDelete by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EyeText(state.email, style = LocalEyeTypography.current.body, color = LocalEyeColors.current.textPrimary,
                modifier = Modifier.weight(1f))
            EyeStatusChip(if (state.healthDataSync) "同步已开启" else "仅本机", if (state.healthDataSync) StatusTone.SUCCESS else StatusTone.NEUTRAL,
                live = state.busy)
        }
        ToggleRow("同意将测试记录加密存储到云端", state.healthDataSync, !state.busy) {
            actions.setConsent(ConsentType.HEALTH_DATA_SYNC, it)
        }
        if (!state.healthDataSync && state.status.synced > 0)
            Note("已停止上传。之前上传的 ${state.status.synced} 条记录仍在云端，可导出查看；如需删除请注销云端账号。")
        ToggleRow("我是监护人，同意上传为未满 14 岁孩子所做的测试", state.guardian, !state.busy) {
            actions.setConsent(ConsentType.GUARDIAN, it)
        }
        ToggleRow("（可选）同意去除身份信息后用于眼健康研究", state.researchUse, !state.busy) {
            actions.setConsent(ConsentType.RESEARCH_USE, it)
        }
        StatusLine(state.status)
        DataHandlingNotice()
        state.message?.let { Note(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EyeButton("立即同步", EyeButtonRole.PRIMARY, actions.syncNow, Modifier.weight(1f),
                enabled = !state.busy && state.healthDataSync)
            EyeButton("导出云端数据", EyeButtonRole.SECONDARY, actions.export, Modifier.weight(1f), enabled = !state.busy)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EyeButton("退出登录", EyeButtonRole.SECONDARY, actions.signOut, Modifier.weight(1f), enabled = !state.busy)
            EyeButton(if (confirmDelete) "确认删除" else "注销云端账号", EyeButtonRole.DANGER,
                { if (confirmDelete) { confirmDelete = false; actions.deleteCloudAccount() } else confirmDelete = true },
                Modifier.weight(1f), enabled = !state.busy)
        }
        if (confirmDelete) Note("将永久删除云端账号、家庭成员和全部已上传记录，无法恢复。本机记录不受影响。再点一次「确认删除」继续。")
    }
}

/** 注册前与登录后都可展开查看的数据处理要点；正式隐私政策文本待法务提供后替换。 */
@Composable
private fun DataHandlingNotice() {
    EyeDisclosure("数据如何处理") {
        listOf(
            "上传内容：测试记录（含问卷回答、逐题作答和眼动数据）。不收集姓名、身份证号、照片或视频。",
            "保护方式：传输全程加密，云端以独立密钥加密保存；所有查看、导出、删除操作留有审计记录。",
            "你的权利：可随时关闭上传、导出全部云端数据，或注销账号立即删除全部云端数据。本机记录始终保留。",
            "儿童：为未满 14 岁孩子所做的测试，需监护人单独同意后才会上传。",
            "研究用途：只有单独同意后，才会在去除身份信息后用于眼健康研究。",
            "结果性质：测试与评估仅为初筛参考，不能替代眼科诊断。",
        ).forEach { Note(it) }
    }
}

@Composable
private fun StatusLine(status: RecordSyncStatus) {
    val parts = buildList {
        add("已上传 ${status.synced} 条")
        add("待上传 ${status.pending} 条")
        if (status.awaitingGuardian > 0) add("其中 ${status.awaitingGuardian} 条需监护人同意")
        if (status.failed > 0) add("${status.failed} 条无法上传")
    }
    Note(parts.joinToString(" · "))
}

@Composable
private fun ToggleRow(text: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .padding(vertical = 4.dp),
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

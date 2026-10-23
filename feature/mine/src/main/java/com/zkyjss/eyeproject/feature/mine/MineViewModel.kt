package com.zkyjss.eyeproject.feature.mine

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zkyjss.eyeproject.core.data.account.AccountRepository
import com.zkyjss.eyeproject.core.data.account.AccountResult
import com.zkyjss.eyeproject.core.data.account.AccountState
import com.zkyjss.eyeproject.core.data.explanation.ExplanationRepository
import com.zkyjss.eyeproject.core.data.record.RecordExportFormat
import com.zkyjss.eyeproject.core.data.record.RecordSyncStatus
import com.zkyjss.eyeproject.core.data.record.ScreeningRecordRepository
import com.zkyjss.eyeproject.core.data.subject.SubjectRepository
import com.zkyjss.eyeproject.core.sync.ConsentType
import com.zkyjss.eyeproject.core.ui.component.AiCardState
import com.zkyjss.eyeproject.feature.services.LocalRecordEntry
import com.zkyjss.eyeproject.feature.services.SubjectProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 「我的」入口页的界面状态：本人资料、记录与成员数量、账号与云端概况。 */
sealed interface MineUiState {
    data object Loading : MineUiState
    data class Ready(
        val self: SubjectProfile?,
        val memberCount: Int,
        val recordCount: Int,
        val email: String?,
        val cloudConfigured: Boolean,
        val healthDataSync: Boolean,
        val synced: Int,
    ) : MineUiState
}

/** 云端同步页的界面状态。 */
sealed interface CloudSyncUiState {
    data object Loading : CloudSyncUiState
    /** 未设置服务器地址：数据只在本机。 */
    data class NotConfigured(val message: String? = null) : CloudSyncUiState
    data class SignedOut(val busy: Boolean, val message: String?) : CloudSyncUiState
    data class SignedIn(
        val email: String,
        val healthDataSync: Boolean,
        val guardian: Boolean,
        val researchUse: Boolean,
        val status: RecordSyncStatus,
        val busy: Boolean,
        val message: String?,
        /** 同意把去标识结果发给大模型生成解读。 */
        val aiExplanation: Boolean = false,
        /** 同意把去标识的用眼概况发给大模型生成综合建议。 */
        val aiAdvice: Boolean = false,
    ) : CloudSyncUiState
}

/** 一次操作的结果：[id] 区分先后两次相同的结果。 */
data class ActionOutcome(val id: Int, val success: Boolean)

/** 账号与云端操作共用：同一时刻只做一件事，结果给一句中文提示与成功 / 失败振动。 */
abstract class AccountActionViewModel : ViewModel() {
    protected val busy = MutableStateFlow(false)
    protected val message = MutableStateFlow<String?>(null)
    private val _outcome = MutableStateFlow<ActionOutcome?>(null)
    /** 最近一次操作的结果（界面据此给成功 / 失败振动）；id 递增，同一结果不重复触发。 */
    val outcome: StateFlow<ActionOutcome?> = _outcome.asStateFlow()

    protected fun run(success: String?, action: suspend () -> AccountResult) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            message.value = null
            val result = action()
            message.value = when (result) {
                AccountResult.Success -> success
                is AccountResult.Failure -> result.message
            }
            _outcome.value = ActionOutcome((_outcome.value?.id ?: 0) + 1, result is AccountResult.Success)
            busy.value = false
        }
    }
}

@HiltViewModel
class MineViewModel @Inject constructor(
    accounts: AccountRepository,
    records: ScreeningRecordRepository,
    subjectRepository: SubjectRepository,
) : ViewModel() {
    /** 本人与家庭成员（本人在前）。 */
    val subjects: StateFlow<List<SubjectProfile>> =
        subjectRepository.subjects.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val uiState: StateFlow<MineUiState> = combine(subjectRepository.subjects, records.localRecords, accounts.accountState, records.syncStatus) {
        subjects, list, account, status ->
        MineUiState.Ready(subjects.firstOrNull { it.relation == "SELF" }, subjects.size, list.size, account.email,
            account.cloudConfigured, account.healthDataSync, status.synced)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MineUiState.Loading)
}

/** 账户页：服务器地址、登录 / 注册、退出与注销。 */
@HiltViewModel
class AccountViewModel @Inject constructor(private val accounts: AccountRepository) : AccountActionViewModel() {
    val account: StateFlow<AccountState?> = accounts.accountState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val busyState: StateFlow<Boolean> = busy.asStateFlow()
    val messageState: StateFlow<String?> = message.asStateFlow()

    fun setServer(url: String) = run("已保存服务器地址") { accounts.setServerAddress(url) }
    fun signIn(email: String, password: String) = run("已登录") { accounts.signIn(email, password) }
    fun register(email: String, password: String) = run("注册成功") { accounts.register(email, password) }
    fun signOut() = run("已退出登录，本机记录仍保留") { accounts.signOut(); AccountResult.Success }
    fun deleteCloudAccount() = run("云端账号及数据已删除，本机记录仍保留") { accounts.deleteCloudAccount() }
}

/** 云端同步页：分项同意、立即同步、导出云端数据。 */
@HiltViewModel
class CloudSyncViewModel @Inject constructor(
    private val accounts: AccountRepository,
    private val records: ScreeningRecordRepository,
    private val subjectRepository: SubjectRepository,
) : AccountActionViewModel() {
    val cloudState: StateFlow<CloudSyncUiState> =
        combine(accounts.accountState, records.syncStatus, busy, message) { account, status, working, note ->
            when {
                !account.cloudConfigured -> CloudSyncUiState.NotConfigured(note)
                account.email == null -> CloudSyncUiState.SignedOut(working, note)
                else -> CloudSyncUiState.SignedIn(account.email!!, account.healthDataSync, account.guardian,
                    account.researchUse, status, working || status.syncing, note ?: status.lastMessage, account.aiExplanation, account.aiAdvice)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CloudSyncUiState.Loading)

    fun setConsent(type: ConsentType, granted: Boolean) = run(null) { accounts.setConsent(type, granted) }

    fun syncNow() = run(null) {
        // 先同步成员与资料：记录要归属到成员
        subjectRepository.sync()
        if (records.syncNow() == null) AccountResult.Failure("请先同意云端存储") else AccountResult.Success
    }

    fun exportTo(target: Uri) = run("已导出云端数据") { accounts.exportCloudData(target) }
}

/** 测试记录页：本机记录、导出与 AI 解读。 */
@HiltViewModel
class RecordsViewModel @Inject constructor(
    private val records: ScreeningRecordRepository,
    private val explanations: ExplanationRepository,
) : ViewModel() {
    /** 本机测试记录（最新在前）。 */
    val localRecords: StateFlow<List<LocalRecordEntry>> =
        records.localRecords.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val explanationCards = mutableMapOf<String, StateFlow<AiCardState?>>()

    /** 一条记录的 AI 解读卡片状态（记录无法解读时为 null，不显示卡片）。 */
    fun explanation(fileName: String): StateFlow<AiCardState?> = explanationCards.getOrPut(fileName) {
        explanations.explanation(fileName).map { it.toCard() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    }

    /** 生成解读；[agreeFirst] 为卡片内点了「同意并生成」。 */
    fun explain(fileName: String, agreeFirst: Boolean = false) {
        viewModelScope.launch { explanations.generate(fileName, agreeFirst) }
    }

    /** 导出一条本机记录到用户选择的位置；[done] 在主线程回报是否成功。 */
    fun exportRecord(fileName: String, target: Uri, format: RecordExportFormat = RecordExportFormat.JSON, done: (Boolean) -> Unit) {
        viewModelScope.launch { done(records.exportRecord(fileName, target, format)) }
    }
}

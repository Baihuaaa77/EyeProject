package com.zkyjss.eyeproject.feature.mine

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zkyjss.eyeproject.core.data.account.AccountRepository
import com.zkyjss.eyeproject.core.data.account.AccountResult
import com.zkyjss.eyeproject.core.data.record.RecordSyncStatus
import com.zkyjss.eyeproject.core.data.record.ScreeningRecordRepository
import com.zkyjss.eyeproject.core.sync.ConsentType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 「云端同步与数据」区的界面状态。 */
sealed interface CloudSyncUiState {
    data object Loading : CloudSyncUiState
    /** 本构建未配置服务器：数据只在本机。 */
    data object NotConfigured : CloudSyncUiState
    data class SignedOut(val busy: Boolean, val message: String?) : CloudSyncUiState
    data class SignedIn(
        val email: String,
        val healthDataSync: Boolean,
        val guardian: Boolean,
        val researchUse: Boolean,
        val status: RecordSyncStatus,
        val busy: Boolean,
        val message: String?,
    ) : CloudSyncUiState
}

@HiltViewModel
class MineViewModel @Inject constructor(
    private val accounts: AccountRepository,
    private val records: ScreeningRecordRepository,
) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    val cloudState: StateFlow<CloudSyncUiState> =
        combine(accounts.accountState, records.syncStatus, busy, message) { account, status, working, note ->
            when {
                !account.cloudConfigured -> CloudSyncUiState.NotConfigured
                account.email == null -> CloudSyncUiState.SignedOut(working, note)
                else -> CloudSyncUiState.SignedIn(account.email!!, account.healthDataSync, account.guardian,
                    account.researchUse, status, working || status.syncing, note ?: status.lastMessage)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CloudSyncUiState.Loading)

    fun signIn(email: String, password: String) = run("已登录") { accounts.signIn(email, password) }

    fun register(email: String, password: String) = run("注册成功") { accounts.register(email, password) }

    fun signOut() = run("已退出登录，本机记录仍保留") { accounts.signOut(); AccountResult.Success }

    fun setConsent(type: ConsentType, granted: Boolean) = run(null) { accounts.setConsent(type, granted) }

    fun syncNow() = run(null) {
        if (records.syncNow() == null) AccountResult.Failure("请先同意云端存储") else AccountResult.Success
    }

    fun exportTo(target: Uri) = run("已导出云端数据") { accounts.exportCloudData(target) }

    fun deleteCloudAccount() = run("云端账号及数据已删除，本机记录仍保留") { accounts.deleteCloudAccount() }

    private fun run(success: String?, action: suspend () -> AccountResult) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            message.value = null
            message.value = when (val result = action()) {
                AccountResult.Success -> success
                is AccountResult.Failure -> result.message
            }
            busy.value = false
        }
    }
}

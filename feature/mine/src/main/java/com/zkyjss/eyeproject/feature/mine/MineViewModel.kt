package com.zkyjss.eyeproject.feature.mine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zkyjss.eyeproject.core.data.MonitoringRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 「我的」页 UI 状态。 */
data class MineUiState(
    /** 本机保存的用眼采样条数（滚动保留最近 2 天）。 */
    val sampleCount: Int = 0,
)

@HiltViewModel
class MineViewModel @Inject constructor(
    private val monitoringRepository: MonitoringRepository,
) : ViewModel() {

    val uiState: StateFlow<MineUiState> = monitoringRepository.samples
        .map { MineUiState(sampleCount = it.size) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MineUiState())

    fun clearMonitoringData() {
        // 删除文件属于 IO，不占主线程
        viewModelScope.launch(Dispatchers.IO) { monitoringRepository.clear() }
    }
}

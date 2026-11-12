package com.zkyjss.eyeproject.feature.services

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zkyjss.eyeproject.core.data.eyecare.EyeCareRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DayOfWeek
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** 服务首页用屏卡状态，缺测项保持空值。 */
data class ServicesUiState(
    val week: List<Int?> = List(7) { null },
    val todayIndex: Int = LocalDate.now().dayOfWeek.value - 1,
)

@HiltViewModel
class ServicesViewModel @Inject constructor(private val eyeCare: EyeCareRepository) : ViewModel() {
    val uiState: StateFlow<ServicesUiState> =
        eyeCare.systemUsage
            .map { usage ->
                val today = LocalDate.now()
                val monday = today.with(DayOfWeek.MONDAY)
                val days = usage.days.associateBy { it.day }
                ServicesUiState(
                    (0..6).map { days[monday.plusDays(it.toLong()).toString()]?.screenMinutes },
                    today.dayOfWeek.value - 1,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ServicesUiState())

    fun refresh() {
        viewModelScope.launch { eyeCare.refreshUsage() }
    }
}

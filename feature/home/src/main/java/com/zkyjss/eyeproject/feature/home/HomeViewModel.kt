package com.zkyjss.eyeproject.feature.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zkyjss.eyeproject.core.common.formatTime
import com.zkyjss.eyeproject.core.data.advice.AdviceRepository
import com.zkyjss.eyeproject.core.data.advice.AiAdviceState
import com.zkyjss.eyeproject.core.data.eyecare.EnvironmentReading
import com.zkyjss.eyeproject.core.data.eyecare.EyeCareRepository
import com.zkyjss.eyeproject.core.data.eyecare.SystemUsage
import com.zkyjss.eyeproject.core.data.record.ScreeningRecordRepository
import com.zkyjss.eyeproject.core.data.subject.SubjectRepository
import com.zkyjss.eyeproject.core.domain.ReminderController
import com.zkyjss.eyeproject.core.eyecare.EyeCareAdvice
import com.zkyjss.eyeproject.core.eyecare.EyeCareContext
import com.zkyjss.eyeproject.core.eyecare.EyeCareDays
import com.zkyjss.eyeproject.core.eyecare.EyeCareEngine
import com.zkyjss.eyeproject.core.eyecare.EyeCareTests
import com.zkyjss.eyeproject.core.eyecare.LedgerData
import com.zkyjss.eyeproject.feature.services.LocalRecordEntry
import com.zkyjss.eyeproject.feature.services.SubjectProfile
import com.zkyjss.eyeproject.feature.services.SubjectProfiles
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.TimeZone
import javax.inject.Inject
import com.zkyjss.eyeproject.core.ui.component.AiCardState
import com.zkyjss.eyeproject.core.ui.component.AiSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** 休息提醒的部分状态（提醒端口只有快照读法，操作后手动刷新）。 */
private data class ReminderPart(val enabled: Boolean = false, val interval: Int = 20, val next: String = "", val notice: String? = null)

private data class EyeInputs(
    val ledger: LedgerData,
    val usage: SystemUsage,
    val env: EnvironmentReading?,
    val self: SubjectProfile?,
    val records: List<LocalRecordEntry>,
)

/**
 * 首页、用眼趋势报告、健康建议、亮度与蓝光共用：把休息提醒、本机账本、系统用屏统计、环境光与亮度、本人资料、
 * 本人近期自测合成一份 EyeCareReport（规则在共用 EyeCareEngine，苹果端同一份）。
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val reminderController: ReminderController,
    private val eyeCare: EyeCareRepository,
    private val advice: AdviceRepository,
    subjects: SubjectRepository,
    records: ScreeningRecordRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val reminder = MutableStateFlow(ReminderPart())
    private val _permissionEvents = MutableSharedFlow<HomePermissionEvent>(extraBufferCapacity = 1)
    val permissionEvents: SharedFlow<HomePermissionEvent> = _permissionEvents.asSharedFlow()
    private var pendingExactAlarm = false
    private var pendingMinutes: Int? = null

    /** 环境光按档次去抖：传感器每秒多次读数，档次与亮度不变就不重算报告。 */
    private val environment: Flow<EnvironmentReading?> = eyeCare.environment().map<EnvironmentReading, EnvironmentReading?> { it }
        .onStart { emit(null) }
        .distinctUntilChanged { a, b -> a?.brightness == b?.brightness && luxBand(a?.lux) == luxBand(b?.lux) }

    val uiState: StateFlow<HomeUiState> = combine(
        combine(eyeCare.ledger, eyeCare.systemUsage, environment, subjects.subjects, records.localRecords) { ledger, usage, env, people, list ->
            EyeInputs(ledger, usage, env, people.firstOrNull { it.relation == "SELF" }, list)
        },
        reminder,
    ) { inputs, part ->
        val now = System.currentTimeMillis()
        val offset = TimeZone.getDefault().getOffset(now) / 60_000
        val today = EyeCareDays.dayOf(now, offset)
        val self = inputs.self
        val context = EyeCareContext(
            today = today, hour = EyeCareDays.hourOf(now, offset),
            systemUsage = inputs.usage.days, usageAccess = inputs.usage.access,
            ambientLux = inputs.env?.lux, ambientSupported = inputs.env?.luxSupported ?: false, screenBrightness = inputs.env?.brightness,
            profile = self?.fields.orEmpty(), age = self?.let { SubjectProfiles.age(it.fields, now) },
            tests = EyeCareTests.from(inputs.records, offset), reminderEnabled = part.enabled, reminderMinutes = part.interval,
        )
        val report = EyeCareEngine.report(inputs.ledger, context)
        HomeUiState(part.notice, part.enabled, part.interval, part.next, report, self?.id, inputs.usage.access,
            EyeCareAdvice.request(inputs.ledger, context, report), inputs.env?.brightness, inputs.env?.lux)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init { refreshReminderState() }

    /** 回到前台：刷新提醒状态与系统用屏统计（可能刚从系统设置授权回来）。 */
    fun refresh() {
        if (pendingExactAlarm) {
            pendingExactAlarm = false
            if (reminderController.canScheduleExactAlarms()) startReminder(pendingMinutes ?: reminder.value.interval)
        }
        refreshReminderState()
        viewModelScope.launch { eyeCare.refreshUsage() }
    }

    fun toggleReminder() {
        if (reminderController.isEnabled()) {
            reminderController.cancel()
            reminder.update { it.copy(notice = null) }
            refreshReminderState()
            return
        }
        requestReminder(reminderController.intervalMinutes())
    }

    /** 干预建议「开启休息提醒 / 缩短间隔」：按建议的间隔开启。 */
    fun enableReminder(minutes: Int) {
        reminderController.setIntervalMinutes(minutes)
        if (reminderController.isEnabled()) { reminderController.schedule(minutes); refreshReminderState() } else requestReminder(minutes)
    }

    private fun requestReminder(minutes: Int) {
        pendingMinutes = minutes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            _permissionEvents.tryEmit(HomePermissionEvent.Notification)
            return
        }
        startReminder(minutes)
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        if (granted) startReminder(pendingMinutes ?: reminder.value.interval)
        else reminder.update { it.copy(notice = "未授予通知权限，无法发送休息提醒") }
    }

    private fun startReminder(minutes: Int) {
        if (!reminderController.canScheduleExactAlarms() || !reminderController.schedule(minutes)) {
            pendingExactAlarm = true
            reminder.update { it.copy(notice = "请允许「闹钟和提醒」权限，以便准时提醒休息") }
            _permissionEvents.tryEmit(HomePermissionEvent.ExactAlarm)
            return
        }
        reminder.update { it.copy(notice = null) }
        refreshReminderState()
    }

    fun selectReminderInterval(minutes: Int) {
        reminderController.setIntervalMinutes(minutes)
        refreshReminderState()
    }

    /** 今日感受打卡（闭环「反馈」）。 */
    fun checkIn(symptoms: List<String>) { viewModelScope.launch { eyeCare.checkIn(symptoms) } }

    /** AI 综合建议卡片（概况变化时跟着换；同样的概况命中本机缓存）。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val aiAdvice: StateFlow<AiCardState?> = uiState.map { it.adviceRequest }.distinctUntilChanged()
        .flatMapLatest { request -> request?.let { advice.advice(it).map { state -> state.toCard() } } ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 生成综合建议；[agreeFirst] 为卡片内点了「同意并生成」。 */
    fun generateAdvice(agreeFirst: Boolean = false) {
        val request = uiState.value.adviceRequest ?: return
        viewModelScope.launch { advice.generate(request, agreeFirst) }
    }

    private fun refreshReminderState() {
        val enabled = reminderController.isEnabled()
        val next = if (enabled) {
            val remaining = (reminderController.nextTriggerElapsed() - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            formatTime(System.currentTimeMillis() + remaining)
        } else ""
        reminder.update { it.copy(enabled = enabled, interval = reminderController.intervalMinutes(), next = next) }
    }

    private companion object {
        fun AiAdviceState.toCard(): AiCardState = when (this) {
            is AiAdviceState.NeedsAccount -> AiCardState.Locked(message)
            is AiAdviceState.NeedsConsent -> AiCardState.Idle(consentText)
            AiAdviceState.Ready -> AiCardState.Idle(null)
            AiAdviceState.Loading -> AiCardState.Loading
            is AiAdviceState.Failed -> AiCardState.Failed(message)
            is AiAdviceState.Done -> with(advice) {
                AiCardState.Done(summary, priorities, habits, seeDoctor, sources.map { AiSource(it.title, it.publisher) }, disclaimer,
                    urgent = fatigueLevel == "HIGH")
            }
        }

        fun luxBand(lux: Float?): Int = when {
            lux == null -> -1
            lux < 10f -> 0
            lux < 50f -> 1
            lux < 200f -> 2
            lux < 1000f -> 3
            else -> 4
        }
    }
}

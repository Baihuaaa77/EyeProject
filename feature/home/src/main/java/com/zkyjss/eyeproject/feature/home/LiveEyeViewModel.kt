package com.zkyjss.eyeproject.feature.home

import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import com.zkyjss.eyeproject.core.domain.LiveEyeMonitor
import com.zkyjss.eyeproject.feature.home.live.LiveEyeTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** 首页实时眼部检测视窗的界面状态。 */
data class LiveEyeUiState(
    val running: Boolean = false,
    val snapshot: LiveEyeMonitor.Snapshot? = null,
    val error: String? = null,
)

/**
 * 首页「实时眼部检测」：检测本身由进程级的 [LiveEyeTracker] 持有（打开后离开首页、退到后台也继续，
 * 直到关掉开关、点通知里的「停止」或从最近任务划掉应用）；这里只把首页的开关、预览接上与摘下转给它。
 * 逐帧逻辑在共用 LiveEyeMonitor（眨眼判据与干眼测试同一份），与苹果端 EyeLiveModel 一致（苹果端退到后台即停止）。
 */
@HiltViewModel
class LiveEyeViewModel @Inject constructor(private val tracker: LiveEyeTracker) : ViewModel() {
    val uiState: StateFlow<LiveEyeUiState> = tracker.state

    /** 打开检测（已在检测时只是接上预览）。 */
    fun start(owner: LifecycleOwner, preview: PreviewView) = tracker.start(owner, preview)

    /** 首页回到前台：接上预览。 */
    fun attach(owner: LifecycleOwner, preview: PreviewView) = tracker.attach(owner, preview)

    /** 离开首页或退到后台：摘下预览，后台继续检测。 */
    fun detach() = tracker.detach()

    /** 关掉开关：停止检测，本次统计记入账本。 */
    fun stop() = tracker.stop()
}

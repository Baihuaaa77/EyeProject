package com.zkyjss.eyeproject.core.domain.gaze

import com.zkyjss.eyeproject.core.model.FaceMetrics
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt

data class ScreenGazeTarget(
    val session: Long,
    val index: Int,
    val point: ScreenGazePoint,
    val validation: Boolean,
    val total: Int,
)

data class ScreenGazeState(
    val target: ScreenGazeTarget? = null,
    val status: String = "等待相机",
    val point: ScreenGazePoint? = null,
    val canCalibrate: Boolean = false,
    val canValidate: Boolean = false,
    val profileCount: Int = 0,
    val report: String? = null,
    val cameraError: Boolean = false,
)

private val calibrationTargets = listOf(
    ScreenGazePoint(0.50f, 0.52f),
    ScreenGazePoint(0.09f, 0.19f), ScreenGazePoint(0.50f, 0.19f), ScreenGazePoint(0.91f, 0.19f),
    ScreenGazePoint(0.91f, 0.52f), ScreenGazePoint(0.91f, 0.89f), ScreenGazePoint(0.50f, 0.89f),
    ScreenGazePoint(0.09f, 0.89f), ScreenGazePoint(0.09f, 0.52f),
)
private val validationTargets = listOf(
    ScreenGazePoint(0.28f, 0.34f), ScreenGazePoint(0.73f, 0.76f),
    ScreenGazePoint(0.74f, 0.33f), ScreenGazePoint(0.29f, 0.75f), ScreenGazePoint(0.53f, 0.65f),
)

/** 在界面线程逐帧调用。时间使用各平台的单调毫秒，画布尺寸使用实际像素。 */
class ScreenGazeSession {
    private data class Sample(val time: Long, val observation: GazeObservation, val errorPx: Float?)
    private class Run(val session: Long, val pose: RelativePose, val profile: GazeProfile?) {
        val targets = if (profile == null) calibrationTargets else validationTargets
        var index = 0
        var shownAt: Long? = null
        val samples = mutableListOf<Sample>()
        val anchors = mutableListOf<GazeAnchor>()
        val errors = mutableListOf<Float>()
        val target get() = targets[index]
    }

    var state = ScreenGazeState()
        private set
    private var targetSession = 0L
    private val profiles = mutableListOf<GazeProfile>()
    private var width = 0
    private var height = 0
    private var run: Run? = null
    private var observation: GazeObservation? = null
    private var lastFrame: Long? = null
    private var lastPointAt: Long? = null
    private var lastProfile: GazeProfile? = null
    private var filtered: ScreenGazePoint? = null
    private var report: String? = null

    fun tick(now: Long) {
        val shownAt = run?.shownAt
        if (shownAt != null && now - shownAt > 8_000) {
            cancelRun("采样超时，请在光线充足且眼睛清晰时重试")
        }
        if (lastFrame?.let { now - it > 380 } == true) {
            lastFrame = null
            observation = null
            clearFilter()
            publish("等待相机画面")
        }
    }

    fun beginCamera() {
        state = state.copy(cameraError = false)
        publish("正在打开前置相机")
    }

    fun stopCamera() {
        run = null
        lastFrame = null
        observation = null
        clearFilter()
        publish("相机已暂停")
    }

    fun cameraError(message: String) {
        stopCamera()
        state = state.copy(cameraError = true)
        publish(message)
    }

    fun setCanvasSize(newWidth: Int, newHeight: Int) {
        if (newWidth <= 0 || newHeight <= 0 || (newWidth == width && newHeight == height)) return
        val changed = width > 0
        width = newWidth
        height = newHeight
        if (changed && (profiles.isNotEmpty() || run != null)) {
            profiles.clear()
            cancelRun("画布尺寸已改变，请重新标定")
        } else if (observation != null) {
            publish(liveStatus())
        }
    }

    fun startTargets(validation: Boolean) {
        val current = observation ?: return
        if (run != null || width <= 0 || height <= 0) return
        val profile = if (validation) nearestProfile(current.pose) ?: return else null
        report = null
        clearFilter()
        run = Run(++targetSession, current.pose, profile)
        publish("保持当前姿势，只转动眼睛看圆点")
    }

    fun markTargetShown(session: Long, index: Int, now: Long) {
        val active = run ?: return
        if (active.session == session && active.index == index && active.shownAt == null) {
            active.shownAt = now
        }
    }

    fun cancelRun(message: String? = null) {
        run = null
        report = message
        clearFilter()
        publish(liveStatus())
    }

    fun consume(face: FaceMetrics?, sourceWidth: Int, sourceHeight: Int, time: Long, now: Long) {
        if (now - time !in 0..350L || time <= (lastFrame ?: 0L)) return
        lastFrame = time
        observation = GazeObservation.from(face, sourceWidth, sourceHeight)
        val active = run
        if (active != null) {
            collectTarget(active, observation, time)
        } else {
            track(observation, time)
        }
    }

    private fun collectTarget(active: Run, sample: GazeObservation?, time: Long) {
        val shownAt = active.shownAt ?: return
        if (time - shownAt < 650L) return
        if (sample == null || active.pose.distance(sample.pose) > 1f ||
            (active.profile?.pose?.distance(sample.pose) ?: 0f) > 1f) {
            active.samples.clear()
            publish(if (sample == null) "请让相机拍清睁开的眼睛" else "请恢复本轮开始时的相对姿势")
            return
        }
        val estimate = active.profile?.project(sample)
        val error = estimate?.let { hypot((it.x - active.target.x) * width, (it.y - active.target.y) * height) }
        if (active.profile != null && error == null) {
            active.samples.clear()
            publish("眼部跟踪不稳定，请继续看圆点")
            return
        }
        if (active.samples.lastOrNull()?.let { time - it.time > 280L } == true) active.samples.clear()
        active.samples += Sample(time, sample, error)
        if (active.samples.size < 7 || time - active.samples.first().time < 620L) {
            publish("继续注视圆点")
            return
        }

        if (active.profile == null) {
            active.anchors += GazeAnchor(active.target, active.samples.map(Sample::observation))
        } else {
            active.errors += median(active.samples.mapNotNull(Sample::errorPx))
        }
        if (active.index == active.targets.lastIndex) {
            finishRun(active)
        } else {
            active.index++
            active.shownAt = null
            active.samples.clear()
            publish("看向新圆点")
        }
    }

    private fun finishRun(active: Run) {
        if (active.profile != null) {
            val middle = median(active.errors)
            val tenthPercent = (1000f * middle / hypot(width.toFloat(), height.toFloat())).roundToInt()
            val percent = "${tenthPercent / 10}.${tenthPercent % 10}"
            val maxError = active.errors.max().roundToInt()
            cancelRun("上次验证误差中位数 ${middle.roundToInt()} px（画布对角线 $percent%），五点中最大 $maxError px")
            return
        }
        val profile = GazeProfile.fit(active.pose, active.anchors)
        if (profile == null) {
            cancelRun("标定没有建立稳定映射，请逐个看清圆点后重试")
            return
        }
        profiles.removeAll { it.pose.distance(profile.pose) < 0.45f }
        profiles += profile
        if (profiles.size > 8) profiles.removeAt(0)
        cancelRun("标定完成，在当前姿势下可以验证误差")
    }

    private fun nearestProfile(pose: RelativePose): GazeProfile? =
        profiles.minByOrNull { it.pose.distance(pose) }?.takeIf { it.pose.distance(pose) <= 1f }

    private fun track(sample: GazeObservation?, time: Long) {
        val profile = sample?.let { nearestProfile(it.pose) }
        val raw = sample?.let { profile?.project(it) }
        if (raw == null || !raw.isOnScreen()) {
            clearFilter()
            publish(when {
                raw != null -> "估计落点超出画布"
                sample != null && profile != null -> "眼部跟踪暂不稳定"
                else -> liveStatus()
            })
            return
        }
        val gap = lastPointAt?.let { time - it }
        filtered = if (gap != null && gap in 1L..250L && lastProfile === profile) {
            filtered?.mix(raw, (1.0 - exp(-gap / 60.0)).toFloat()) ?: raw
        } else raw
        lastPointAt = time
        lastProfile = profile
        publish("正在追踪", filtered)
    }

    private fun liveStatus(): String {
        val pose = observation?.pose ?: return "请让相机拍清睁开的眼睛"
        return when {
            profiles.isEmpty() -> "相机已就绪，请标定当前姿势"
            nearestProfile(pose) == null -> "姿势超出已标定范围，可添加当前姿势"
            else -> "等待有效注视点"
        }
    }

    private fun clearFilter() {
        filtered = null
        lastPointAt = null
        lastProfile = null
    }

    private fun publish(status: String, point: ScreenGazePoint? = null) {
        val active = run
        val pose = observation?.pose
        state = ScreenGazeState(
            target = active?.let { ScreenGazeTarget(it.session, it.index, it.target, it.profile != null, it.targets.size) },
            status = status,
            point = point,
            canCalibrate = active == null && pose != null && width > 0 && height > 0,
            canValidate = active == null && pose != null && nearestProfile(pose) != null,
            profileCount = profiles.size,
            report = report,
            cameraError = state.cameraError,
        )
    }
}

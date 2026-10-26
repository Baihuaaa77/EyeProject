package com.zkyjss.eyeproject.core.domain

import com.zkyjss.eyeproject.core.model.EyeLandmarks
import com.zkyjss.eyeproject.core.model.FaceMetrics
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 首页「实时眼部检测」视窗的状态（安卓与苹果共用）：逐帧喂入关键点几何，给出最近 [windowMs] 的 EAR 曲线、
 * 眨眼事件、帧率与当前双眼关键点，让人一眼看出相机、关键点和眨眼检测都在正常工作。
 * 眨眼判据与干眼测试同一份 [BlinkDetector.dryEyeEar]（按时间判定 + 个人自适应阈值）。
 *
 * 同时累计本次开启的「视觉行为」统计（[session]）：看到双眼的时长、眨眼频率、虹膜测距的观看距离与头部偏转，
 * 供首页多源感知与用眼趋势使用。
 *
 * [update] 在相机分析线程调用，[snapshot] 在界面线程读取，内部加锁。
 */
class LiveEyeMonitor(private val windowMs: Long = DEFAULT_WINDOW_MS) {
    /** 一帧读数：[ear] 为双眼平均（看不到双眼时为 null）；[closed] 为基线建立后低于当帧阈值。 */
    data class Sample(val timeMs: Long, val ear: Float?, val threshold: Float, val closed: Boolean)

    data class Snapshot(
        /** 最近 [windowMs] 的逐帧读数（时间升序） */
        val samples: List<Sample>,
        /** 窗口内每次眨眼完成（睁开那一帧）的时间 */
        val blinkTimesMs: List<Long>,
        /** 本次开启以来累计眨眼次数 */
        val blinkCount: Int,
        /** 最近 60 秒内的眨眼次数 */
        val blinksLastMinute: Int,
        /** 最近一次眨眼的闭眼时长（毫秒） */
        val lastClosedMs: Long?,
        val faceFound: Boolean,
        val eyesFound: Boolean,
        val ear: Float?,
        val threshold: Float,
        /** 睁眼基线已建立（前约 15 帧只采集、不判定） */
        val ready: Boolean,
        val closed: Boolean,
        /** 最近一秒实际分析的帧数 */
        val fps: Float,
        val leftEye: EyeLandmarks?,
        val rightEye: EyeLandmarks?,
        /** 关键点所在图像（已转正、未镜像）的宽高 */
        val imageWidth: Int,
        val imageHeight: Int,
        /** 最新一帧距 [snapshot] 传入的时刻已超过 [STALE_MS]：相机没有送来新画面 */
        val stale: Boolean,
        /** 还没收到任何一帧 */
        val waiting: Boolean,
        /** 虹膜测距的眼屏距离（平滑后，厘米）；没有焦距或看不到虹膜时为 null */
        val distanceCm: Float? = null,
        /** 头部偏离正对屏幕的角度（俯仰与左右合成，度）；没有头姿时为 null */
        val headOffsetDeg: Float? = null,
    )

    /**
     * 本次开启的视觉行为统计。[trackedMs] 为看到双眼的累计时长；眨眼频率按它折算，
     * 距离与头部偏转为有读数帧的平均，[closeFraction] 为距离近于 [CLOSE_CM] 的帧占比。
     */
    data class Session(
        val trackedMs: Long,
        val blinks: Int,
        val blinksPerMinute: Float,
        val meanDistanceCm: Float?,
        val closeFraction: Float?,
        val meanHeadOffsetDeg: Float?,
    )

    private val lock = DomainLock()
    private var detector = BlinkDetector.dryEyeEar()
    private val samples = ArrayDeque<Sample>()
    private val blinks = ArrayDeque<Long>()
    private var blinkCount = 0
    private var earSamples = 0
    private var belowSince = -1L
    private var lastClosedMs: Long? = null
    private var latestMs = -1L
    private var faceFound = false
    private var leftEye: EyeLandmarks? = null
    private var rightEye: EyeLandmarks? = null
    private var imageWidth = 0
    private var imageHeight = 0
    private var distance: Float? = null
    private var headOffset: Float? = null
    private var trackedMs = 0L
    /** 本段统计内的眨眼次数（[takeSession] 分段时清零；界面上的累计次数 [blinkCount] 不清） */
    private var sessionBlinks = 0
    private var distanceSum = 0.0
    private var distanceFrames = 0
    private var closeFrames = 0
    private var headSum = 0.0
    private var headFrames = 0

    /**
     * 喂入一帧（[timeMs] 单调递增的毫秒时间，与 [snapshot] 的 nowMs 同一时钟）。
     * [focalLengthPx] 为相机焦距（像素），大于 0 时用虹膜直径估算观看距离。
     */
    fun update(face: FaceMetrics?, imageWidth: Int, imageHeight: Int, timeMs: Long, focalLengthPx: Float = 0f) = lock.withLock {
        val left = face?.leftEye
        val right = face?.rightEye
        val ear = if (left != null && right != null) BlinkDetector.averageEar(left, right) else null
        val threshold = detector.threshold
        if (ear != null) {
            val before = detector.blinkCount
            detector.update(ear, timeMs)
            earSamples++
            val below = ear < threshold
            if (below && belowSince < 0) belowSince = timeMs
            if (detector.blinkCount != before) {
                blinkCount++
                sessionBlinks++
                blinks.addLast(timeMs)
                lastClosedMs = if (belowSince >= 0) timeMs - belowSince else null
            }
            if (!below) belowSince = -1L
            // 相邻两帧都看到双眼才累计时长；单帧间隔封顶，避免卡顿或断流把一段空白算进去
            if (latestMs >= 0 && samples.lastOrNull()?.ear != null) trackedMs += (timeMs - latestMs).coerceIn(0L, MAX_FRAME_GAP_MS)
        } else {
            // 与干眼测试同一喂法：看不到双眼时中断本次闭眼计时，不累计
            detector.interrupt()
            belowSince = -1L
        }
        measureDistance(left, right, focalLengthPx)
        measureHead(face)
        samples.addLast(Sample(timeMs, ear, threshold, ear != null && earSamples >= READY_SAMPLES && ear < threshold))
        while (samples.isNotEmpty() && samples.first().timeMs < timeMs - windowMs) samples.removeFirst()
        while (blinks.isNotEmpty() && blinks.first() < timeMs - MINUTE_MS) blinks.removeFirst()
        latestMs = timeMs
        faceFound = face != null
        leftEye = left
        rightEye = right
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
    }

    private fun measureDistance(left: EyeLandmarks?, right: EyeLandmarks?, focalLengthPx: Float) {
        val iris = listOfNotNull(left, right).map { it.irisDiameterPx }.filter { it > 0f }
        if (focalLengthPx <= 0f || iris.isEmpty()) return
        val cm = DepthFromIris.distanceCm(iris.average().toFloat(), focalLengthPx)
        if (cm !in MIN_DISTANCE_CM..MAX_DISTANCE_CM) return
        distance = distance?.let { it + (cm - it) * SMOOTHING } ?: cm
        distanceSum += cm; distanceFrames++
        if (cm < CLOSE_CM) closeFrames++
    }

    private fun measureHead(face: FaceMetrics?) {
        val pose = face?.headPose ?: run { headOffset = null; return }
        val offset = sqrt(pose.pitchDeg * pose.pitchDeg + pose.yawDeg * pose.yawDeg)
        if (offset.isNaN() || abs(offset) > 90f) return
        headOffset = headOffset?.let { it + (offset - it) * SMOOTHING } ?: offset
        headSum += offset; headFrames++
    }

    fun snapshot(nowMs: Long): Snapshot = lock.withLock {
        val last = samples.lastOrNull()
        Snapshot(
            samples = samples.toList(),
            blinkTimesMs = blinks.filter { latestMs - it <= windowMs },
            blinkCount = blinkCount,
            blinksLastMinute = blinks.size,
            lastClosedMs = lastClosedMs,
            faceFound = faceFound,
            eyesFound = leftEye != null && rightEye != null,
            ear = last?.ear,
            threshold = last?.threshold ?: detector.threshold,
            ready = earSamples >= READY_SAMPLES,
            closed = last?.closed == true,
            fps = samples.count { it.timeMs > latestMs - 1_000 }.toFloat(),
            leftEye = leftEye,
            rightEye = rightEye,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            stale = latestMs >= 0 && nowMs - latestMs > STALE_MS,
            waiting = latestMs < 0,
            distanceCm = distance.takeIf { leftEye != null || rightEye != null },
            headOffsetDeg = headOffset,
        )
    }

    /** 本次开启的统计；看到双眼不足 [MIN_SESSION_MS] 时数据太少，返回 null。 */
    fun session(): Session? = lock.withLock {
        if (trackedMs < MIN_SESSION_MS) return@withLock null
        Session(
            trackedMs = trackedMs,
            blinks = sessionBlinks,
            blinksPerMinute = sessionBlinks * MINUTE_MS.toFloat() / trackedMs,
            meanDistanceCm = if (distanceFrames > 0) (distanceSum / distanceFrames).toFloat() else null,
            closeFraction = if (distanceFrames > 0) closeFrames.toFloat() / distanceFrames else null,
            meanHeadOffsetDeg = if (headFrames > 0) (headSum / headFrames).toFloat() else null,
        )
    }

    /**
     * 取出当前这段统计并从零开始下一段（长时间后台检测时定期分段记账，跨天也能记到对应的日子）；
     * 眨眼判据、曲线与界面上的累计次数不受影响。数据不足 [MIN_SESSION_MS] 时返回 null 且不清零。
     */
    fun takeSession(): Session? = lock.withLock {
        val current = session() ?: return@withLock null
        trackedMs = 0L; sessionBlinks = 0; distanceSum = 0.0; distanceFrames = 0; closeFrames = 0; headSum = 0.0; headFrames = 0
        current
    }

    /** 清零重来（重新开启视窗时）。 */
    fun reset() = lock.withLock {
        detector = BlinkDetector.dryEyeEar()
        samples.clear(); blinks.clear()
        blinkCount = 0; earSamples = 0; belowSince = -1L; lastClosedMs = null; latestMs = -1L
        faceFound = false; leftEye = null; rightEye = null; imageWidth = 0; imageHeight = 0
        distance = null; headOffset = null
        trackedMs = 0L; sessionBlinks = 0; distanceSum = 0.0; distanceFrames = 0; closeFrames = 0; headSum = 0.0; headFrames = 0
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 8_000L
        /** 超过这么久没有新帧即视为画面中断 */
        const val STALE_MS = 1_500L
        /** 少于这么久的有效画面不计入用眼统计 */
        const val MIN_SESSION_MS = 20_000L
        /** 观看距离近于此值记为「偏近」（厘米） */
        const val CLOSE_CM = 30f
        /** 与 BlinkDetector 自适应阈值的最少样本数一致 */
        private const val READY_SAMPLES = 15
        private const val MINUTE_MS = 60_000L
        private const val MAX_FRAME_GAP_MS = 200L
        private const val SMOOTHING = 0.2f
        private const val MIN_DISTANCE_CM = 8f
        private const val MAX_DISTANCE_CM = 150f
    }
}

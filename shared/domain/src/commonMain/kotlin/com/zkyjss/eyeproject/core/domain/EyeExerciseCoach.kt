package com.zkyjss.eyeproject.core.domain

import com.zkyjss.eyeproject.core.model.FaceMetrics
import kotlin.math.hypot

/**
 * 首页「护眼操」（活动眼球）的教练：小瞳提示「向左看 / 向右看 / 向上看 / 向下看」，前置相机看虹膜在眼裂里的位置，
 * 看到了就算这一步完成（界面随之翻页、振动、小瞳飞出去打个滚），最后闭眼休息几秒。两端共用。
 *
 * 判断方法：虹膜中心相对两眼角连线中点的偏移（以眼裂宽度归一），减去开头「看着小瞳」时的基线；
 * 沿提示方向超过阈值并保持 [HOLD_MS] 即算看到。图像为已转正、不镜像的画面，受测者的左边在图像右侧（+x）。
 * 没有相机或一直看不到脸时按时间带着做（每步 [GUIDED_MS]），不算失败。
 * 线程：[onFace] 可在相机线程调用，[tick] / [state] 在界面线程调用，内部加锁。
 */
class EyeExerciseCoach(private val moves: List<Move>) {
    /** 默认一套：左、右、上、下各两遍（苹果端无默认参数，用这个构造）。 */
    constructor() : this(DEFAULT_MOVES)

    /** [dx] / [dy] 为屏幕方向：左 -1、右 +1；上 -1、下 +1（受测者面对屏幕时与自己的左右一致）。 */
    enum class Move(val label: String, val dx: Int, val dy: Int) {
        LEFT("向左看", -1, 0), RIGHT("向右看", 1, 0), UP("向上看", 0, -1), DOWN("向下看", 0, 1),
    }

    enum class Stage { CALIBRATE, MOVE, BACK, REST, DONE }

    /**
     * 阶段变化事件：界面据此翻页、振动、播放小瞳动作。
     * [COUNTDOWN] 为闭眼休息时倒计时又少了一秒（剩 4、3、2、1 秒各一次），界面播放倒计时音——闭着眼也知道快结束了。
     */
    enum class Event { STARTED, DETECTED, MISSED, NEXT, REST, COUNTDOWN, DONE }

    data class State(
        val stage: Stage,
        /** 当前是第几步（0 起），共 [stepCount] 步 */
        val stepIndex: Int,
        val stepCount: Int,
        val move: Move?,
        val secondsLeft: Int,
        /** 最近 1 秒看得到双眼（看不到时按时间带着做） */
        val faceVisible: Boolean,
        /** 到目前为止真正看到眼球转过去的步数 */
        val detectedCount: Int,
        val progress: Float,
    )

    private val lock = DomainLock()
    private var stage = Stage.CALIBRATE
    private var stepIndex = 0
    private var stageStartMs = -1L
    private var baselineX = 0f
    private var baselineY = 0f
    private val calibration = mutableListOf<Pair<Float, Float>>()
    private var latest: Pair<Float, Float>? = null
    private var latestMs = -1L
    private var holdSinceMs = -1L
    private var everSawFace = false
    private var detectedCount = 0
    /** 闭眼休息时最近一次报过的剩余秒数 */
    private var restSecond = 0

    /** 一帧的人脸（可为空：这帧没看到脸）。 */
    fun onFace(face: FaceMetrics?, timeMs: Long) = lock.withLock {
        val g = face?.let(::gaze)
        if (g == null) return@withLock
        everSawFace = true
        latest = g; latestMs = timeMs
        if (stage == Stage.CALIBRATE && stageStartMs >= 0) calibration += g
    }

    /** 推进计时与判断；阶段变化时返回事件（同一时刻最多一个）。 */
    fun tick(timeMs: Long): Event? = lock.withLock {
        if (stageStartMs < 0) { stageStartMs = timeMs; return@withLock Event.STARTED }
        val elapsed = timeMs - stageStartMs
        when (stage) {
            Stage.CALIBRATE -> if (elapsed >= CALIBRATE_MS) {
                if (calibration.isNotEmpty()) {
                    baselineX = median(calibration.map { it.first }); baselineY = median(calibration.map { it.second })
                }
                enter(Stage.MOVE, timeMs); Event.NEXT
            } else null
            Stage.MOVE -> {
                val move = moves[stepIndex]
                val fresh = latest?.takeIf { timeMs - latestMs <= FRESH_MS }
                val along = fresh?.let { (x, y) -> -move.dx * (x - baselineX) + move.dy * (y - baselineY) }
                val threshold = if (move.dx != 0) HORIZONTAL_THRESHOLD else VERTICAL_THRESHOLD
                if (along != null && along >= threshold) {
                    if (holdSinceMs < 0) holdSinceMs = timeMs
                } else holdSinceMs = -1L
                val faceRecently = latest != null && timeMs - latestMs <= FRESH_MS
                when {
                    holdSinceMs >= 0 && timeMs - holdSinceMs >= HOLD_MS -> { detectedCount++; enter(Stage.BACK, timeMs); Event.DETECTED }
                    // 看得到脸却一直没转过去：到点也往下走，不卡住
                    faceRecently && elapsed >= MOVE_TIMEOUT_MS -> { enter(Stage.BACK, timeMs); Event.MISSED }
                    // 没有相机或看不到脸：按时间带着做
                    !faceRecently && elapsed >= GUIDED_MS -> { enter(Stage.BACK, timeMs); Event.NEXT }
                    else -> null
                }
            }
            Stage.BACK -> if (elapsed >= RETURN_MS) {
                if (stepIndex + 1 < moves.size) { stepIndex++; enter(Stage.MOVE, timeMs); Event.NEXT }
                else { enter(Stage.REST, timeMs); Event.REST }
            } else null
            Stage.REST -> {
                val left = secondsLeft(REST_MS, elapsed)
                when {
                    elapsed >= REST_MS -> { enter(Stage.DONE, timeMs); Event.DONE }
                    left < restSecond -> { restSecond = left; Event.COUNTDOWN }
                    else -> null
                }
            }
            Stage.DONE -> null
        }
    }

    fun state(timeMs: Long): State = lock.withLock {
        val elapsed = if (stageStartMs < 0) 0L else timeMs - stageStartMs
        val limit = when (stage) {
            Stage.CALIBRATE -> CALIBRATE_MS
            Stage.MOVE -> if (latest != null && timeMs - latestMs <= FRESH_MS) MOVE_TIMEOUT_MS else GUIDED_MS
            Stage.BACK -> RETURN_MS
            Stage.REST -> REST_MS
            Stage.DONE -> 0L
        }
        val steps = moves.size + 1f // 每个动作一步，最后闭眼休息一步
        val progress = when (stage) {
            Stage.CALIBRATE -> 0f
            Stage.MOVE -> stepIndex / steps
            Stage.BACK -> (stepIndex + 1) / steps
            Stage.REST -> (moves.size + (elapsed.toFloat() / REST_MS).coerceIn(0f, 1f)) / steps
            Stage.DONE -> 1f
        }
        State(stage, stepIndex, moves.size, moves.getOrNull(stepIndex).takeIf { stage == Stage.MOVE || stage == Stage.BACK },
            secondsLeft(limit, elapsed),
            latest != null && timeMs - latestMs <= 1_000L, detectedCount, progress)
    }

    /** 这次一共做了几步、真正看到了几步（结束页用）。 */
    val summary: Pair<Int, Int> get() = lock.withLock { moves.size to detectedCount }

    /** 是否用上了相机（结束页说明用）。 */
    val usedCamera: Boolean get() = lock.withLock { everSawFace }

    private fun enter(next: Stage, timeMs: Long) {
        stage = next; stageStartMs = timeMs; holdSinceMs = -1L
        if (next == Stage.REST) restSecond = secondsLeft(REST_MS, 0L)
    }

    /** 界面上显示的剩余整秒（向上取整：还剩 4.2 秒显示 5）。 */
    private fun secondsLeft(limit: Long, elapsed: Long): Int = (((limit - elapsed).coerceAtLeast(0L) + 999) / 1000).toInt()

    companion object {
        val DEFAULT_MOVES = listOf(Move.LEFT, Move.RIGHT, Move.UP, Move.DOWN, Move.LEFT, Move.RIGHT, Move.UP, Move.DOWN)
        const val CALIBRATE_MS = 1_500L
        const val MOVE_TIMEOUT_MS = 6_000L
        const val GUIDED_MS = 3_000L
        const val RETURN_MS = 1_200L
        const val REST_MS = 5_000L
        const val HOLD_MS = 250L
        const val FRESH_MS = 400L
        /** 虹膜沿提示方向的偏移（眼裂宽度的比例）超过基线这么多算看到 */
        const val HORIZONTAL_THRESHOLD = 0.07f
        const val VERTICAL_THRESHOLD = 0.04f

        /** 双眼平均的注视偏移 (x, y)：虹膜中心相对眼角连线中点，按眼裂宽度归一。看不到双眼时为 null。 */
        fun gaze(face: FaceMetrics): Pair<Float, Float>? {
            val eyes = listOfNotNull(face.leftEye, face.rightEye).mapNotNull { eye ->
                val p = eye.earPoints
                val iris = eye.irisPoints.firstOrNull() ?: return@mapNotNull null
                if (p.size < 6) return@mapNotNull null
                val a = p[0]
                val b = p[3]
                val width = hypot(b.x - a.x, b.y - a.y)
                if (width < 1f) return@mapNotNull null
                ((iris.x - (a.x + b.x) / 2) / width) to ((iris.y - (a.y + b.y) / 2) / width)
            }
            if (eyes.size < 2) return null
            return eyes.map { it.first }.average().toFloat() to eyes.map { it.second }.average().toFloat()
        }

        private fun median(values: List<Float>): Float = values.sorted().let { it[it.size / 2] }
    }
}

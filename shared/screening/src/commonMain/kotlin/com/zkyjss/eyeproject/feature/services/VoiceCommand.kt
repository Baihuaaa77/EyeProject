package com.zkyjss.eyeproject.feature.services

/** 识别出的语音指令。 */
sealed interface VoiceCommand {
    /** 方向：0 右 1 下 2 左 3 上（与 E 开口方向编码一致）。 */
    data class Direction(val direction: Int) : VoiceCommand
    /** 「看不清 / 跳过 / 不知道」：与「跳过」键相同，记为无法辨认。 */
    data object Unsure : VoiceCommand
    /** 「模糊」：移近法中表示开始持续模糊。 */
    data object Blur : VoiceCommand
    /** 「好了 / 开始」：确认准备就绪。 */
    data object Ready : VoiceCommand
}

/** 中文口语指令解析：包含常见同音误识别字；同一句里取最后提到的方向（允许「上…不对，左」这类改口）。 */
/**
 * 同一句话只作答一次：识别器常对同一段语音先给部分结果、再给最终结果，
 * 或下一题出现时仍带着上一题的尾音，导致一个字连跳两题。语音作答之间至少间隔 [windowMs]。
 */
class VoiceRepeatGuard(private val windowMs: Long = 900L) {
    private var lastAcceptedMs = Long.MIN_VALUE / 2
    fun accept(nowMs: Long): Boolean = (nowMs - lastAcceptedMs >= windowMs).also { if (it) lastAcceptedMs = nowMs }
}

object VoiceCommandParser {
    private val directionChars = mapOf(
        0 to "右又幼佑有由油游优悠柚诱",
        1 to "下夏吓虾厦霞",
        2 to "左做作坐座佐昨",
        3 to "上尚晌赏商伤",
    )
    private val englishDirections = mapOf("right" to 0, "down" to 1, "left" to 2, "up" to 3, "you" to 0, "yo" to 0)
    private val unsureWords = listOf("看不清", "看不见", "看不到", "不知道", "不清楚", "不确定", "没看清",
        "跳过", "跳锅", "条过", "挑过", "调过", "跳")
    private val blurWords = listOf("模糊", "糊了", "花了", "虚了")
    private val readyWords = listOf("好了", "准备好", "开始", "可以了", "继续")

    fun parse(raw: String): VoiceCommand? {
        val text = raw.lowercase().filterNot { it.isWhitespace() || it in "，。,.!！？?、；;：:" }
        if (text.isEmpty()) return null
        if (blurWords.any { it in text }) return VoiceCommand.Blur
        if (unsureWords.any { it in text }) return VoiceCommand.Unsure
        var best: Pair<Int, Int>? = null // (位置, 方向)
        for ((dir, chars) in directionChars) {
            val index = text.indexOfLast { it in chars }
            if (index >= 0 && (best == null || index > best.first)) best = index to dir
        }
        for ((word, dir) in englishDirections) {
            val index = text.lastIndexOf(word)
            if (index >= 0 && (best == null || index > best.first)) best = index to dir
        }
        best?.let { return VoiceCommand.Direction(it.second) }
        if (readyWords.any { it in text }) return VoiceCommand.Ready
        return null
    }

    /**
     * 播报回声过滤：边播报边收听时，麦克风会收到提示语本身。只丢弃提示语里也出现的那个指令
     * （如播报「右眼测试」时听到「右」），其余指令照常作答，用户不必等播报说完。
     */
    fun mentions(spoken: String, command: VoiceCommand): Boolean {
        val text = spoken.lowercase()
        return when (command) {
            is VoiceCommand.Direction -> directionChars[command.direction].orEmpty().any { it in text } ||
                englishDirections.any { (word, dir) -> dir == command.direction && word in text }
            VoiceCommand.Unsure -> unsureWords.any { it in text }
            VoiceCommand.Blur -> blurWords.any { it in text }
            VoiceCommand.Ready -> readyWords.any { it in text }
        }
    }
}

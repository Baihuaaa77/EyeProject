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
object VoiceCommandParser {
    private val directionChars = mapOf(
        0 to "右又幼佑",
        1 to "下夏吓虾厦",
        2 to "左做作坐座佐",
        3 to "上尚晌",
    )
    private val englishDirections = mapOf("right" to 0, "down" to 1, "left" to 2, "up" to 3)
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
}

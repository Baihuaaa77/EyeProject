package com.zkyjss.eyeproject.feature.services

/**
 * 仅改变送给 TTS 的文本，屏幕上的医学与测量数据保持原样；两端共用，读法一致。
 * 数字读成中文、量词前的 2 读「两」、符号换成停顿，句末补句号让语调自然落下。
 */
fun screeningSpeechText(text: String): String {
    val tolerance = Regex("""(\d+(?:\.\d+)?)\s*±\s*(\d+(?:\.\d+)?)\s*(?:cm|厘米)""")
    val range = Regex("""(\d+(?:\.\d+)?)\s*[-–—~～]\s*(\d+(?:\.\d+)?)""")
    val number = Regex("""\d+(?:\.\d+)?""")
    val spaced = text
        .replace(tolerance) { "${it.groupValues[1]}厘米，上下浮动${it.groupValues[2]}厘米" }
        .replace(range) { "${countWord(it.groupValues[1])} 到 ${countWord(it.groupValues[2])}" }
        .replace("cm", "厘米", ignoreCase = true)
        .replace("lux", "勒克斯", ignoreCase = true)
    val spoken = number.replace(spaced) { match ->
        // 量词前的 2 读「两」（两秒、两次），序数「第 2」仍读「二」。
        val before = spaced.substring(0, match.range.first).trimEnd()
        val after = spaced.substring(match.range.last + 1).trimStart()
        if (match.value == "2" && !before.endsWith("第") && MEASURE_WORDS.any { after.startsWith(it) }) "两"
        else spokenNumber(match.value)
    }
    return spoken
        .replace('：', '，').replace(':', '，').replace('；', '，').replace(';', '，')
        .replace('·', '，').replace('（', '，').replace('）', '，').replace('(', '，').replace(')', '，')
        .replace("→", "，然后").replace('／', '或').replace('/', '或')
        .replace(Regex("""\s+"""), "")
        .replace("厘米约", "厘米，约")
        .replace(Regex("""，+"""), "，")
        .replace(Regex("""，([。！？])"""), "$1")
        .trim('，')
        .let { if (it.isEmpty() || it.last() in "。！？…") it else "$it。" }
}

private val MEASURE_WORDS = listOf("秒", "次", "分钟", "个", "只", "下", "遍", "级", "题", "步", "厘米", "米")

private fun countWord(value: String) = if (value == "2") "两" else spokenNumber(value)

private fun spokenNumber(value: String): String {
    val parts = value.split('.')
    val integer = parts[0].toIntOrNull() ?: return value
    if (integer > 999) return value
    val digits = "零一二三四五六七八九"
    fun underHundred(number: Int): String = when {
        number < 10 -> digits[number].toString()
        number < 20 -> "十" + if (number % 10 == 0) "" else digits[number % 10]
        else -> digits[number / 10].toString() + "十" + if (number % 10 == 0) "" else digits[number % 10]
    }
    val whole = if (integer < 100) underHundred(integer) else {
        digits[integer / 100].toString() + "百" +
            if (integer % 100 == 0) "" else if (integer % 100 < 10) "零${digits[integer % 10]}" else
                if (integer % 100 < 20) "一${underHundred(integer % 100)}" else underHundred(integer % 100)
    }
    return if (parts.size == 1) whole else whole + "点" + parts[1].map { digits[it - '0'] }.joinToString("")
}

/**
 * 需要语音提醒的距离区间：与安卓横幅一致，偏离容差 1 cm 以上才提醒，避免在边缘来回播报。
 * 返回 -1 太近、1 太远、2 看不到双眼、0 合适；当前任务不要求固定距离时返回 null。
 */
fun spokenDistanceZone(distanceCm: Float, faceVisible: Boolean, target: Pair<Float, Float>?): Int? = when {
    target == null -> null
    !faceVisible || !distanceCm.isFinite() || distanceCm <= 0f -> 2
    kotlin.math.abs(distanceCm - target.first) <= target.second + 1f -> 0
    distanceCm < target.first -> -1
    else -> 1
}

/** 口语化的播报短句：重复提醒时换一种说法，回到合适距离时给一句肯定，听起来更像真人引导。 */
object SpokenPrompts {
    /** 回到合适距离后的肯定。 */
    const val DISTANCE_OK = "好，就保持这个距离。"

    /** [zone]：-1 太近、1 太远、2 看不到双眼；[repeat] 为同一情况已提醒的次数。 */
    fun distance(zone: Int, repeat: Int): String? {
        val variants = when (zone) {
            -1 -> listOf("太近了，请把手机往远处移一点。", "还是有点近，再往远一点。", "再远一些，马上就好。")
            1 -> listOf("太远了，请把手机靠近一点。", "还是有点远，再近一点。", "再近一些，马上就好。")
            2 -> listOf("请正对屏幕。", "请把脸对准屏幕，让摄像头看到双眼。", "请正对屏幕，露出双眼。")
            else -> return null
        }
        return variants[repeat.coerceAtLeast(0) % variants.size]
    }
}

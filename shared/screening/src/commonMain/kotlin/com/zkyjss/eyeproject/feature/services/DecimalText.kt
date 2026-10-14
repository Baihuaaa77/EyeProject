package com.zkyjss.eyeproject.feature.services

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round

/** 报告文本固定使用小数点；判定仍使用未舍入的原始数值。 */
fun decimalString(value: Double, digits: Int): String {
    if (!value.isFinite()) return "—"
    val scale = 10.0.pow(digits).toLong()
    val rounded = round(abs(value) * scale).toLong()
    val sign = if (value < 0) "-" else ""
    return sign + (rounded / scale).toString() + if (digits == 0) "" else "." + (rounded % scale).toString().padStart(digits, '0')
}

internal fun String.reportFormat(vararg values: Any?): String {
    var index = 0
    return Regex("%\\.(\\d)f").replace(this) { match ->
        decimalString((values[index++] as Number).toDouble(), match.groupValues[1].toInt())
    }
}

package com.zkyjss.eyeproject.feature.services

/**
 * 问卷题目。
 * - [key]：存储字段名（评估代码按它读取，保持稳定）；[title]：给患者看的大白话问题
 * - [options] 为 null 时是输入题（[numeric] 决定键盘）；多选题用「、」拼接存储
 * - [hints]：个别选项下方的一行短提示（只在术语确实需要解释时使用）
 * - [exclusive]：与其他选项互斥的「没有 / 不清楚」类选项
 */
data class Question(
    val key: String,
    val title: String = key,
    val options: List<String>? = null,
    val multiple: Boolean = false,
    val numeric: Boolean = false,
    val optional: Boolean = false,
    val hints: Map<String, String> = emptyMap(),
    val exclusive: Set<String> = NONE_OPTIONS,
    /** 仅当返回 true 时出现（如手术细节只在选择了手术后出现）。 */
    val visibleWhen: (Map<String, String>) -> Boolean = { true },
    val validate: (String) -> Boolean = { true },
) {
    companion object {
        val NONE_OPTIONS = setOf("无", "没有", "都没有", "不清楚", "都没有或不清楚", "不戴眼镜")
    }
}

fun ageQuestion(range: IntRange, title: String = "你的年龄") = Question(
    key = "年龄", title = title, numeric = true,
    validate = { it.toIntOrNull()?.let { age -> age in range } == true },
)

/** 多选字段里是否包含任一指定选项。 */
fun Map<String, String>.hasAny(key: String, vararg values: String): Boolean =
    get(key).orEmpty().split("、").any { it in values }

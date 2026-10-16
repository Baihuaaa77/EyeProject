package com.zkyjss.eyeproject.feature.services

import kotlinx.serialization.json.*

/** 两端共用的记录构建器，非有限数值写 null；不依赖 Android 的 org.json。 */
internal class RecordObject(values: Map<String, *> = emptyMap<String, Any?>()) {
    private val items = values.mapValues { element(it.value) }.toMutableMap()
    fun put(key: String, value: Any?) { items[key] = element(value) }
    fun json(): JsonObject = JsonObject(items)
    override fun toString(): String = json().toString()
    companion object { val NULL: Nothing? = null }
}

internal class RecordArray(values: List<Any?> = emptyList()) {
    private val items = values.map(::element).toMutableList()
    fun put(value: Any?) { items += element(value) }
    fun json(): JsonArray = JsonArray(items)
    /** 必须输出 JSON：苹果端按 JSON 解析反馈事件，缺少此覆盖时得到对象地址，测试中的震动与音效全部丢失。 */
    override fun toString(): String = json().toString()
}

private fun element(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is RecordObject -> value.json()
    is RecordArray -> value.json()
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Double -> if (value.isFinite()) JsonPrimitive(value) else JsonNull
    is Float -> if (value.isFinite()) JsonPrimitive(value) else JsonNull
    is Number -> JsonPrimitive(value)
    else -> error("不支持的测试记录类型：${value::class}")
}

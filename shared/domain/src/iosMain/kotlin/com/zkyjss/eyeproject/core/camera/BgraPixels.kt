@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.zkyjss.eyeproject.core.camera

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.get
import kotlin.math.roundToInt

/**
 * 直接读取已锁定的 BGRA 像素内存，按与安卓相同的 0.2126R + 0.7152G + 0.0722B 取整为灰度。
 * 苹果端逐像素跨 Swift→Kotlin 桥写数组每帧要几千次调用，会把相机分析拖到每秒几帧；这里一次读完。
 * 调用方须在返回前保持像素缓冲锁定。
 */
object BgraPixels {
    /** 把 Swift 连续内存里的 [count] 个浮点数一次拷进 Kotlin 数组（478 个关键点 × 3，逐个跨桥写要上千次调用）。 */
    fun floats(base: CPointer<FloatVar>, count: Int): FloatArray = FloatArray(count) { base[it] }

    fun grayRegion(base: CPointer<UByteVar>, bytesPerRow: Int, left: Int, top: Int, width: Int, height: Int): IntArray =
        IntArray(width * height) { i ->
            val offset = (top + i / width) * bytesPerRow + (left + i % width) * 4
            gray(base[offset + 2].toInt(), base[offset + 1].toInt(), base[offset].toInt())
        }

    /** 与安卓相同的 12×12 网格平均灰度。 */
    fun brightness(base: CPointer<UByteVar>, bytesPerRow: Int, width: Int, height: Int): Float {
        var total = 0f
        for (row in 0 until 12) for (column in 0 until 12) {
            val y = minOf(height - 1, ((row + 0.5f) * height / 12).toInt())
            val x = minOf(width - 1, ((column + 0.5f) * width / 12).toInt())
            val offset = y * bytesPerRow + x * 4
            total += 0.2126f * base[offset + 2].toInt() + 0.7152f * base[offset + 1].toInt() + 0.0722f * base[offset].toInt()
        }
        return total / 144f
    }

    private fun gray(r: Int, g: Int, b: Int): Int = (0.2126f * r + 0.7152f * g + 0.0722f * b).roundToInt()
}

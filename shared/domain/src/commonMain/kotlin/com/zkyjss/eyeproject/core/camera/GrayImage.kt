package com.zkyjss.eyeproject.core.camera

/** 0–255 灰度 ROI。两端都按 0.2126R + 0.7152G + 0.0722B 取整，禁止直接混用 YUV 的 Y。 */
interface GrayImage {
    val width: Int
    val height: Int
    fun grayRegion(left: Int, top: Int, width: Int, height: Int): IntArray
}

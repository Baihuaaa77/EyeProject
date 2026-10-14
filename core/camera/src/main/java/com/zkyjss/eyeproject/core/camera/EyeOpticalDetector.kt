package com.zkyjss.eyeproject.core.camera

import android.graphics.Bitmap
import android.graphics.Color
import com.zkyjss.eyeproject.core.model.EyeLandmarks
import kotlin.math.roundToInt

/** 安卓只提供图像像素；瞳孔和映光计算由双端共用算法完成。 */
internal object EyeOpticalDetector {
    fun measure(bitmap: Bitmap, eye: EyeLandmarks?) = EyeOpticalAlgorithm.measure(BitmapGrayImage(bitmap), eye)
}

internal class BitmapGrayImage(private val bitmap: Bitmap) : GrayImage {
    override val width = bitmap.width
    override val height = bitmap.height
    override fun grayRegion(left: Int, top: Int, width: Int, height: Int): IntArray {
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, left, top, width, height)
        return IntArray(pixels.size) { i ->
            val c = pixels[i]
            (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)).roundToInt()
        }
    }
}

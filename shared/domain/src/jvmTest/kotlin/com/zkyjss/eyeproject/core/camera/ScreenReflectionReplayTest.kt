package com.zkyjss.eyeproject.core.camera

import com.zkyjss.eyeproject.core.model.*
import java.io.DataInputStream
import java.io.File
import kotlin.test.Test

/** 私有真机片段的离线复核入口。素材由环境变量指定，不随仓库和 CI 上传。 */
class ScreenReflectionReplayTest {
    @Test fun replayPrivateFramesWhenProvided() {
        val path = System.getenv("EYE_REFLECTION_REPLAY") ?: return
        val rows = mutableListOf("trial,eye,index,phase,referenceX,referenceY,oldX,oldY,newX,newY,reason")
        DataInputStream(File(path).inputStream().buffered()).use { input ->
            fun string(): String { val bytes = ByteArray(input.readInt()); input.readFully(bytes); return bytes.toString(Charsets.UTF_8) }
            repeat(input.readInt()) {
                val trial = string(); val which = string(); val index = input.readInt(); val phase = input.readInt()
                val w = input.readInt(); val h = input.readInt()
                val center = Point2f(input.readFloat(),input.readFloat()); val diameter = input.readFloat()
                val lid = List(6) { Point2f(input.readFloat(),input.readFloat()) }
                val referenceX = input.readFloat(); val referenceY = input.readFloat()
                val bytes = ByteArray(w*h); input.readFully(bytes)
                val image = object : GrayImage {
                    override val width = w; override val height = h
                    override fun grayRegion(left: Int,top: Int,width: Int,height: Int) =
                        IntArray(width*height) { bytes[(top+it/width)*w+left+it%width].toInt() and 255 }
                }
                val eye = EyeLandmarks(which,lid,listOf(center),diameter)
                val old = EyeOpticalAlgorithm.measure(image,eye)
                val measured = EyeOpticalAlgorithm.measureScreenPoint(image,eye)
                rows += listOf(trial,which,index,phase,referenceX,referenceY,old?.cornealReflex?.x,old?.cornealReflex?.y,
                    measured?.cornealReflex?.x,measured?.cornealReflex?.y,measured?.rejectionReason).joinToString(",") { it?.toString().orEmpty() }
            }
        }
        File(path+".csv").writeText(rows.joinToString("\n"))
    }
}

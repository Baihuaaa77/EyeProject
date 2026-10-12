package com.zkyjss.eyeproject.core.domain

/**
 * Depth-from-Iris 单目测距。实际精度取决于相机有效焦距、裁切和虹膜关键点，需按设备验证。
 *
 * 原理：人眼虹膜水平直径约 11.7±0.5 mm，配合相机焦距与虹膜像素直径，
 * 由相似三角形估算镜头到人眼的距离；研究报告的误差不能直接套用到本项目或模拟器。
 *
 * 距离(mm) = 虹膜物理直径(mm) × 焦距(px) / 虹膜像素直径(px)
 */
object DepthFromIris {
    const val IRIS_DIAMETER_MM = 11.7f

    /** @param irisDiameterPx 虹膜水平直径（像素） @param focalLengthPx 相机焦距（像素） */
    fun distanceCm(irisDiameterPx: Float, focalLengthPx: Float): Float {
        if (focalLengthPx <= 0f || irisDiameterPx <= 0f) return 0f
        return IRIS_DIAMETER_MM * (focalLengthPx / irisDiameterPx) / 10f
    }
}

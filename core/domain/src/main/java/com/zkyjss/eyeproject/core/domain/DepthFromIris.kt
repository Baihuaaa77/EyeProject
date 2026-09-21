package com.zkyjss.eyeproject.core.domain

/**
 * Depth-from-Iris 测距（MediaPipe Iris 方法，无需标定）。
 *
 * 原理：人眼虹膜水平直径近似恒定 11.7±0.5mm，配合相机焦距与虹膜像素直径，
 * 由相似三角形推得相机到人眼的度量距离，误差 < 10%。
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

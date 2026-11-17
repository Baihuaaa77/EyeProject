package com.zkyjss.eyeproject.core.camera

import com.zkyjss.eyeproject.core.model.FaceMetrics

/** 黑屏白点的双端光学入口。保持可见光基线的拒判标准，不把预测位置作为实测映光。 */
object ScreenPointOptics {
    fun face(points: FloatArray, matrix: FloatArray, image: GrayImage): FaceMetrics? {
        val face = LandmarkGeometry.face(points,matrix,image,optics = false) ?: return null
        return face.copy(leftOptical = EyeOpticalAlgorithm.measureScreenPoint(image,face.leftEye),
            rightOptical = EyeOpticalAlgorithm.measureScreenPoint(image,face.rightEye))
    }
}

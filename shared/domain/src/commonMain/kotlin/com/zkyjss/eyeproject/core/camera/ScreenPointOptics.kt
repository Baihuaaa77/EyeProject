package com.zkyjss.eyeproject.core.camera

import com.zkyjss.eyeproject.core.model.FaceMetrics

/** 黑屏白点的双端光学入口，提供原像素中的局部亮点供斜视流程跨帧匹配。 */
object ScreenPointOptics {
    fun face(points: FloatArray, matrix: FloatArray, image: GrayImage): FaceMetrics? {
        val face = LandmarkGeometry.face(points,matrix,image,optics = false) ?: return null
        return face.copy(leftOptical = EyeOpticalAlgorithm.measureScreenPoint(image,face.leftEye),
            rightOptical = EyeOpticalAlgorithm.measureScreenPoint(image,face.rightEye))
    }
}

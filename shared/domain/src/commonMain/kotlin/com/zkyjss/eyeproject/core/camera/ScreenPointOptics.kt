package com.zkyjss.eyeproject.core.camera

import com.zkyjss.eyeproject.core.model.FaceMetrics

/** 黑屏白点的双端光学入口，提供原像素中的局部亮点供斜视流程跨帧匹配。 */
object ScreenPointOptics {
    const val RESEARCH_SEARCH_RADIUS = ScreenGlintDetection.RESEARCH_SEARCH_RADIUS

    /** 保持 3 参数签名向后兼容主 App（Kotlin/Native iOS 调用不支持默认参数）。 */
    fun face(
        points: FloatArray,
        matrix: FloatArray,
        image: GrayImage,
    ): FaceMetrics? = face(points, matrix, image, ScreenGlintDetection.DEFAULT_SEARCH_RADIUS)

    fun face(
        points: FloatArray,
        matrix: FloatArray,
        image: GrayImage,
        searchRadiusFraction: Float,
    ): FaceMetrics? {
        val face = LandmarkGeometry.face(points, matrix, image, optics = false) ?: return null
        return face.copy(
            leftOptical = EyeOpticalAlgorithm.measureScreenPoint(image, face.leftEye, searchRadiusFraction),
            rightOptical = EyeOpticalAlgorithm.measureScreenPoint(image, face.rightEye, searchRadiusFraction),
        )
    }

    /** StrabismusLab 科研入口：默认放宽至 0.80f 虹膜半径，避免大角度斜视映光被裁剪。 */
    fun faceResearch(
        points: FloatArray,
        matrix: FloatArray,
        image: GrayImage,
        searchRadiusFraction: Float = RESEARCH_SEARCH_RADIUS,
    ): FaceMetrics? = face(points, matrix, image, searchRadiusFraction)
}

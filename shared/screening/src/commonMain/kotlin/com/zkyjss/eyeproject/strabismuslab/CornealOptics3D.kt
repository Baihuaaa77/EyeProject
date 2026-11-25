package com.zkyjss.eyeproject.strabismuslab

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 球面角膜镜 3D 光学反演模型（Guestrin & Eizenman / Shih & Liu 单相机-单光源几何 + 虹膜连线 Roll 旋正）。
 *
 * 相比旧版线性公式 `HDE = (leftNasal - rightNasal) * 175.5` 的三大物理改进：
 * 1. **光源-相机离轴偏置显式建模**：传入屏幕编码白点中心相对前置相机光心的 3D 物理坐标 L = (dx, dy, 0) mm；
 * 2. **大角度球面镜非线性精确反演**：沿相机映光射线与角膜球面 (Rc = 7.8 mm) 求反射点，利用入射/反射角平分法向迭代
 *    解析角膜曲率中心 C 与眼球光轴方向 g，再通过 100·tan(Δθ) 换算棱镜度，消除 30–60Δ 大斜视角下的非线性低估；
 * 3. **零头姿噪声的 2D 虹膜连线 Roll 旋正**：用双眼角膜缘中心连线构造头部平面坐标基底 (e_h, e_v)，避免歪头引起的
 *    水平/垂直偏斜串扰，同时避免直接代入含 2–5° 噪声的 MediaPipe 3D Yaw/Pitch 矩阵放大抖动。
 */
object CornealOptics3D {
    /** Gullstrand 标准角膜前表面曲率半径（mm）。 */
    const val CORNEA_RADIUS_MM = 7.8

    /** 平均角膜横径（mm）。 */
    const val CORNEA_DIAMETER_MM = 11.7

    /** 角膜缘平面距角膜曲率中心的轴向距离：sqrt(Rc^2 - (D/2)^2) ≈ 5.159 mm。 */
    val LIMBUS_TO_CORNEA_CENTER_MM: Double = sqrt(
        CORNEA_RADIUS_MM * CORNEA_RADIUS_MM - (CORNEA_DIAMETER_MM * 0.5) * (CORNEA_DIAMETER_MM * 0.5),
    )

    private data class Vec3(val x: Double, val y: Double, val z: Double) {
        operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
        operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
        operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
        infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
        infix fun cross(o: Vec3) = Vec3(
            y * o.z - z * o.y,
            z * o.x - x * o.z,
            x * o.y - y * o.x,
        )
        fun norm() = sqrt(x * x + y * y + z * z)
        fun normalized(): Vec3 {
            val n = norm()
            return if (n > 1e-12) Vec3(x / n, y / n, z / n) else Vec3(0.0, 0.0, 1.0)
        }
    }

    data class EyeOptical3D(
        /** 眼球光轴在 Roll 旋正头部坐标系下相对预期视线的水平角偏差（rad，鼻侧为正：外斜映光偏鼻侧对应光轴偏颞侧）。 */
        val nasalDeviationRad: Double,
        /** 眼球光轴相对预期视线的垂直角偏差（rad，向上为正）。 */
        val verticalDeviationRad: Double,
        /** 角膜曲率中心 3D 坐标（mm）。 */
        val corneaCenterXMm: Double,
        val corneaCenterYMm: Double,
        val corneaCenterZMm: Double,
    )

    data class Binocular3DResult(
        /** 3D 不对称水平偏斜（Δ，抵消双眼对称 κ 角，保留外斜/内斜方向）。 */
        val hdeAsymmetryPrism: Double,
        /** 3D 聚散水平偏斜（Δ，对双眼共轭偏视与横向离轴免疫；外斜为正、内斜为负，含双眼对称 κ 角之和）。 */
        val hdeVergencePrism: Double,
        /** 3D 垂直偏斜（Δ，Roll 旋正后，自动抵消垂直离轴与共轭俯仰）。 */
        val vdePrism: Double,
        /** Roll 旋正角（度，由双眼虹膜连线计算）。 */
        val rollCorrectionDeg: Double,
        /** 左眼（图像左/模型 leftEye）相对预期视线水平偏斜（Δ）。 */
        val leftNasalPrism: Double,
        /** 右眼（图像右/模型 rightEye）相对预期视线水平偏斜（Δ）。 */
        val rightNasalPrism: Double,
    )

    /**
     * 对单帧双眼观测执行 3D 球面角膜镜光学反演。
     *
     * @param leftIrisX / leftIrisY   MediaPipe 左眼虹膜中心（像素）
     * @param leftGlintX / leftGlintY 左眼最终锁定角膜映光（像素）
     * @param leftIrisDiameterPx      左眼虹膜像素直径
     * @param rightIrisX / rightIrisY MediaPipe 右眼虹膜中心（像素）
     * @param rightGlintX / rightGlintY 右眼最终锁定角膜映光（像素）
     * @param rightIrisDiameterPx     右眼虹膜像素直径
     * @param intrinsics              相机内参 (fx, fy, cx, cy) 与屏幕白点相对前摄物理偏移 (lightOffsetXMm, lightOffsetYMm)
     * @param nasalSign               图像坐标系下左眼鼻侧符号：若 rightIrisX > leftIrisX 为 +1，否则 -1
     * @param farFixation             true 表示越屏看 6m 远（预期双视轴平行于 -z），false 表示看近注视屏幕白点 L
     */
    fun solveBinocular(
        leftIrisX: Double,
        leftIrisY: Double,
        leftGlintX: Double,
        leftGlintY: Double,
        leftIrisDiameterPx: Double,
        rightIrisX: Double,
        rightIrisY: Double,
        rightGlintX: Double,
        rightGlintY: Double,
        rightIrisDiameterPx: Double,
        intrinsics: LabCameraIntrinsics,
        nasalSign: Double,
        farFixation: Boolean,
    ): Binocular3DResult? {
        if (intrinsics.fx <= 10.0 || intrinsics.fy <= 10.0) return null
        if (leftIrisDiameterPx < 10.0 || rightIrisDiameterPx < 10.0) return null
        val fMean = 0.5 * (intrinsics.fx + intrinsics.fy)

        // 双眼平均虹膜直径给出稳健基础深度，保留轻度单眼深度差权重
        val zLeftRaw = CORNEA_DIAMETER_MM * fMean / leftIrisDiameterPx
        val zRightRaw = CORNEA_DIAMETER_MM * fMean / rightIrisDiameterPx
        val zMean = 0.5 * (zLeftRaw + zRightRaw)
        if (zMean !in 120.0..1200.0) return null
        val zLeft = 0.7 * zMean + 0.3 * zLeftRaw
        val zRight = 0.7 * zMean + 0.3 * zRightRaw

        // 由双眼虹膜连线构造 Roll 旋正后的头部 3D 坐标基底（图像向右为 +eH，向下为 +eV）
        val ipdDx = if (rightIrisX >= leftIrisX) rightIrisX - leftIrisX else leftIrisX - rightIrisX
        val ipdDy = if (rightIrisX >= leftIrisX) rightIrisY - leftIrisY else leftIrisY - rightIrisY
        val ipdLen = hypot(ipdDx, ipdDy)
        if (ipdLen < 20.0) return null
        val rollRad = atan2(ipdDy, ipdDx)
        val eH = Vec3(ipdDx / ipdLen, ipdDy / ipdLen, 0.0).normalized()
        val eZ = Vec3(0.0, 0.0, 1.0)
        val eV = (eZ cross eH).normalized() // 指向图像下方

        val light = Vec3(intrinsics.lightOffsetXMm, intrinsics.lightOffsetYMm, 0.0)

        val leftEye3d = solveSingleEye(
            irisX = leftIrisX,
            irisY = leftIrisY,
            glintX = leftGlintX,
            glintY = leftGlintY,
            zLimbusMm = zLeft,
            intrinsics = intrinsics,
            light = light,
            eH = eH,
            eV = eV,
            nasalAxisSign = nasalSign,
            farFixation = farFixation,
        ) ?: return null

        val rightEye3d = solveSingleEye(
            irisX = rightIrisX,
            irisY = rightIrisY,
            glintX = rightGlintX,
            glintY = rightGlintY,
            zLimbusMm = zRight,
            intrinsics = intrinsics,
            light = light,
            eH = eH,
            eV = eV,
            nasalAxisSign = -nasalSign,
            farFixation = farFixation,
        ) ?: return null

        val leftNasalPrism = radToPrism(leftEye3d.nasalDeviationRad)
        val rightNasalPrism = radToPrism(rightEye3d.nasalDeviationRad)
        val hdeAsym = radToPrism(leftEye3d.nasalDeviationRad - rightEye3d.nasalDeviationRad)
        val hdeVerg = radToPrism(leftEye3d.nasalDeviationRad + rightEye3d.nasalDeviationRad)
        val vde = radToPrism(leftEye3d.verticalDeviationRad - rightEye3d.verticalDeviationRad)

        return Binocular3DResult(
            hdeAsymmetryPrism = hdeAsym,
            hdeVergencePrism = hdeVerg,
            vdePrism = vde,
            rollCorrectionDeg = rollRad * 180.0 / kotlin.math.PI,
            leftNasalPrism = leftNasalPrism,
            rightNasalPrism = rightNasalPrism,
        )
    }

    /**
     * 单眼 3D 球面镜迭代反演：
     * 1. 在当前角膜曲率中心 C 下，求相机映光射线 r_g 与球面 |P - C| = R_c 的近侧交点 G；
     * 2. 根据反射定律求实际角膜反射点法向 m = norm(norm(L - G) + norm(-G))，反推出精确曲率中心 C_next = G - R_c * m；
     * 3. 用角膜缘中心射线 r_I 与以 C 为心、半径 d_c 的球面求近侧交点 P_limbus，进而得到光轴方向 g = norm(P_limbus - C)；
     * 4. 与预期视线（看近：norm(L - C)；看远：(0, 0, -1)）作角差，直接给出消除离轴偏置与大角度非线性的精确偏斜角。
     */
    private fun solveSingleEye(
        irisX: Double,
        irisY: Double,
        glintX: Double,
        glintY: Double,
        zLimbusMm: Double,
        intrinsics: LabCameraIntrinsics,
        light: Vec3,
        eH: Vec3,
        eV: Vec3,
        nasalAxisSign: Double,
        farFixation: Boolean,
    ): EyeOptical3D? {
        val rIris = Vec3((irisX - intrinsics.cx) / intrinsics.fx, (irisY - intrinsics.cy) / intrinsics.fy, 1.0).normalized()
        val rGlint = Vec3((glintX - intrinsics.cx) / intrinsics.fx, (glintY - intrinsics.cy) / intrinsics.fy, 1.0).normalized()

        // 初始角膜缘中心与曲率中心估计（曲率中心在角膜缘平面后方 LIMBUS_TO_CORNEA_CENTER_MM）
        val pLimbusInit = rIris * (zLimbusMm / max(0.2, rIris.z))
        var center = Vec3(pLimbusInit.x, pLimbusInit.y, pLimbusInit.z + LIMBUS_TO_CORNEA_CENTER_MM)
        val zCenterAnchor = center.z

        // 4 步反射约束迭代：求满足球面镜反射定律的真实曲率中心 C
        for (iter in 0 until 4) {
            val gPoint = intersectRaySphereNear(rGlint, center, CORNEA_RADIUS_MM) ?: run {
                // 若射线略超出球面切线（极端偏心），投影到球面最近切点避免数值发散
                val tClosest = rGlint dot center
                val closest = rGlint * tClosest
                center + (closest - center).normalized() * CORNEA_RADIUS_MM
            }
            val toLight = (light - gPoint).normalized()
            val toCam = (gPoint * -1.0).normalized()
            val bisector = (toLight + toCam).normalized()
            val refined = gPoint - bisector * CORNEA_RADIUS_MM
            // 保持曲率中心深度锚定在测距约束附近，避免单相机单光源沿射线方向的深度漂移
            val scaleZ = zCenterAnchor / max(50.0, refined.z)
            center = Vec3(refined.x * scaleZ, refined.y * scaleZ, zCenterAnchor)
        }

        // 角膜缘中心射线与球面 |P - C| = LIMBUS_TO_CORNEA_CENTER_MM 求近侧交点，解析球面大角度非线性
        val pLimbus = intersectRaySphereNear(rIris, center, LIMBUS_TO_CORNEA_CENTER_MM) ?: run {
            val tClosest = rIris dot center
            val closest = rIris * tClosest
            val dir = (closest - center).normalized()
            center + dir * LIMBUS_TO_CORNEA_CENTER_MM
        }
        val opticalAxis = (pLimbus - center).normalized() // 指向相机前方（z < 0）

        // 预期视线方向（从角膜曲率中心指向目标）：看近指向屏幕白点 L，看远平行指向 -z
        val expectedAxis = if (farFixation) {
            Vec3(0.0, 0.0, -1.0)
        } else {
            (light - center).normalized()
        }

        // 投影到 Roll 旋正后的头部坐标系 (eH, eV, -eZ)
        val optH = atan2(opticalAxis dot eH, max(1e-4, -(opticalAxis.z)))
        val expH = atan2(expectedAxis dot eH, max(1e-4, -(expectedAxis.z)))
        val optV = atan2(opticalAxis dot eV, max(1e-4, -(opticalAxis.z)))
        val expV = atan2(expectedAxis dot eV, max(1e-4, -(expectedAxis.z)))

        // 当外斜视发生时，眼球光轴偏颞侧（-nasalAxisSign 方向），映光偏鼻侧（+nasalAxisSign 方向）；
        // 统一约定：映光偏鼻侧 / 外斜为正，与旧版 leftNasal / rightNasal 同符号，便于直接对照消融。
        val nasalDev = -(optH - expH) * nasalAxisSign
        val vertDev = -(optV - expV)

        return EyeOptical3D(
            nasalDeviationRad = nasalDev,
            verticalDeviationRad = vertDev,
            corneaCenterXMm = center.x,
            corneaCenterYMm = center.y,
            corneaCenterZMm = center.z,
        )
    }

    /** 射线 P(t) = t * dir（t > 0）与球面 |P - center| = radius 的近侧交点。 */
    private fun intersectRaySphereNear(dir: Vec3, center: Vec3, radius: Double): Vec3? {
        val b = dir dot center
        val c = (center dot center) - radius * radius
        val disc = b * b - c
        if (disc < 0.0) return null
        val t = b - sqrt(disc)
        return if (t > 10.0) dir * t else null
    }

    /** 角度（rad）转棱镜度（1Δ = 100·tan θ），限制在物理合理范围内。 */
    fun radToPrism(rad: Double): Double {
        val clamped = rad.coerceIn(-1.15, 1.15) // ±66°
        return 100.0 * tan(clamped)
    }

    fun isFinite(v: Double): Boolean = v.isFinite() && abs(v) < 250.0
}

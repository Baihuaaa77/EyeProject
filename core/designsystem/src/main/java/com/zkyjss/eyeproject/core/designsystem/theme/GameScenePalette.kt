package com.zkyjss.eyeproject.core.designsystem.theme

import androidx.compose.ui.graphics.Color

/**
 * 三项自测互动小游戏（干眼小剧场、老视矢车菊邮局、斜视月夜萤火虫）的专用场景语义色板。
 * 统一由 designsystem 托管，避免业务页面写死色值。
 */
object EyeGamePalette {
    object Theater {
        val stageBg = Color(0xFF221A14)
        val stageGlow = Color(0xFF3E2D20)
        val tearCyan = Color(0xFF7ED6DF)
        val tearHighlight = Color(0xFFDFF8FB)
        val tearDeep = Color(0xFF3A8FA0)
        val leafSage = Color(0xFF8DB596)
        val warmLamp = Color(0xFFFFD166)
        val steam = Color(0xFFF4EAE0)
    }

    object PostOffice {
        val skyTop = Color(0xFF478598)
        val skyMid = Color(0xFF7CB2BC)
        val skyBottom = Color(0xFFC2E1DF)
        val cloud = Color(0xFFEAF5F3)
        val townFar = Color(0xFF69A2AA)
        val townNear = Color(0xFF437680)
        val spireGreen = Color(0xFF4B8E78)
        val lampGold = Color(0xFFF3BF68)
        val lampBrass = Color(0xFFD08334)
        val lampBeam = Color(0xFFFFF3C4)
        val envelopeBase = Color(0xFF1E388E)
        val envelopeDeep = Color(0xFF14276B)
        val envelopeShadow = Color(0xFF0D1B4D)
        val envelopeFiligree = Color(0xFFC4D3F2)
        val flowerOuter = Color(0xFF4A74F0)
        val flowerInner = Color(0xFF80A2FF)
        val flowerCenter = Color(0xFF192A6E)
        val stemGreen = Color(0xFF4D8A67)
        val waxSeal = Color(0xFF9EA8BA)
        val optotypePinkWhite = Color(0xFFFFE4EE)
        val boxBody = Color(0xFF3A5A4E)
        val boxTop = Color(0xFF567A6C)
        val boxSlot = Color(0xFF1E332B)
        val boxRivet = Color(0xFFC2D4CC)
    }

    object Firefly {
        val skyTop = Color(0xFF071326)
        val skyMid = Color(0xFF0E2549)
        val skyBottom = Color(0xFF183D68)
        val moon = Color(0xFFE3F5FF)
        val moonHalo = Color(0xFF7AB8E0)
        val treeFar = Color(0xFF0D2442)
        val treeMid = Color(0xFF0A1B33)
        val treeNear = Color(0xFF061020)
        val creek = Color(0xFF1D4E7A)
        val creekHighlight = Color(0xFF7EC0E6)
        val bodyDark = Color(0xFF2C2017)
        val bodyWarm = Color(0xFF6B4226)
        val wing = Color(0xFFE4F2E8)
        val glowCore = Color(0xFFFFFDE3)
        val glowMid = Color(0xFFFFDF6B)
        val glowOuter = Color(0xFFF5B335)
    }
}

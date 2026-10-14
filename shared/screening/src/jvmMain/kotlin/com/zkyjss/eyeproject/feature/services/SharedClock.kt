package com.zkyjss.eyeproject.feature.services

actual object SharedClock {
    actual fun nowMs() = System.nanoTime() / 1_000_000L
    actual fun epochMs() = System.currentTimeMillis()
}

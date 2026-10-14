package com.zkyjss.eyeproject.feature.services

expect object SharedClock {
    fun nowMs(): Long
    fun epochMs(): Long
}

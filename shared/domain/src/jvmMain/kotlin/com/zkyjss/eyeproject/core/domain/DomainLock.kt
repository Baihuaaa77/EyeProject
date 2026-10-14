package com.zkyjss.eyeproject.core.domain

internal actual class DomainLock actual constructor() {
    private val lock = Any()
    actual fun <T> withLock(block: () -> T): T = synchronized(lock, block)
}

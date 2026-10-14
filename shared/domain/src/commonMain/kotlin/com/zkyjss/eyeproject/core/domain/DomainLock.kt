package com.zkyjss.eyeproject.core.domain

/** 相机回调与界面协程之间的短临界区，两端分别使用系统锁。 */
internal expect class DomainLock() {
    fun <T> withLock(block: () -> T): T
}

class TimeEventQueue {
    private val lock = DomainLock()
    private val events = ArrayDeque<Long>()
    fun add(value: Long) = lock.withLock { events.addLast(value) }
    fun poll(): Long? = lock.withLock { if (events.isEmpty()) null else events.removeFirst() }
    fun clear() = lock.withLock { events.clear() }
}

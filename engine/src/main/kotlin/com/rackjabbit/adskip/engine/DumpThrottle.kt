package com.rackjabbit.adskip.engine

class DumpThrottle(
    private val minIntervalMs: Long = 2500,
) {
    private var lastAtMs: Long = Long.MIN_VALUE / 4

    fun shouldDump(nowMs: Long, force: Boolean): Boolean {
        if (!force && nowMs - lastAtMs < minIntervalMs) return false
        lastAtMs = nowMs
        return true
    }
}

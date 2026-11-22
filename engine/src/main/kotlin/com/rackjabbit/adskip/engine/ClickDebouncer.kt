package com.rackjabbit.adskip.engine

class ClickDebouncer {
    private var lastAtMs: Long = Long.MIN_VALUE / 4
    var lastSignature: String? = null
        private set

    fun allow(nowMs: Long, signature: String, windowMs: Long): Boolean {
        if (nowMs - lastAtMs < windowMs) return false
        lastAtMs = nowMs
        lastSignature = signature
        return true
    }
}

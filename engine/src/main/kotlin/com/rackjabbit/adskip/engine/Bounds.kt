package com.rackjabbit.adskip.engine

import kotlinx.serialization.Serializable

@Serializable
data class Bounds(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
) {
    val isEmpty: Boolean get() = right <= left || bottom <= top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    fun isTappable(screenWidth: Int, screenHeight: Int): Boolean {
        if (isEmpty || screenWidth <= 0 || screenHeight <= 0) return false
        return centerX in 0 until screenWidth && centerY in 0 until screenHeight
    }

    companion object {
        val EMPTY = Bounds()
    }
}

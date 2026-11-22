package com.rackjabbit.adskip.engine

data class OverlayCopy(
    val title: String,
    val detail: String?,
) {
    companion object {
        const val TITLE = "Ad playing"

        fun idle(): OverlayCopy = OverlayCopy(TITLE, null)

        fun build(countdownSeconds: Int?, label: String?): OverlayCopy {
            val clock = countdownSeconds?.let(::formatClock)
            val detail = when {
                label.isNullOrBlank() -> clock
                clock == null -> label
                label.contains(clock) -> label
                else -> "$label · $clock"
            }
            return OverlayCopy(TITLE, detail)
        }

        fun formatClock(totalSeconds: Int): String {
            val clamped = totalSeconds.coerceAtLeast(0)
            val minutes = clamped / 60
            val seconds = clamped % 60
            return "%d:%02d".format(minutes, seconds)
        }
    }
}

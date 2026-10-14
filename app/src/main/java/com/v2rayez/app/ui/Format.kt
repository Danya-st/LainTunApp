package com.v2rayez.app.ui

private const val HOUR_MS = 60L * 60 * 1000
private const val DAY_MS = 24 * HOUR_MS

/** Сколько осталось до [expiryMs]: «12 дн.», «5 ч.», «< 1 ч.». */
internal fun formatRemaining(expiryMs: Long, nowMs: Long): String {
    val left = (expiryMs - nowMs).coerceAtLeast(0)
    val days = left / DAY_MS
    val hours = left / HOUR_MS
    return when {
        days >= 1 -> "$days дн."
        hours >= 1 -> "$hours ч."
        else -> "< 1 ч."
    }
}

package com.v2rayez.app

import com.v2rayez.app.ui.formatRemaining
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    private val now = 1_000_000_000_000L
    private val hour = 3_600_000L

    @Test fun days() = assertEquals("12 дн.", formatRemaining(now + 12 * 24 * hour + 3 * hour, now))
    @Test fun hours() = assertEquals("5 ч.", formatRemaining(now + 5 * hour + 10_000, now))
    @Test fun lessThanHour() = assertEquals("< 1 ч.", formatRemaining(now + 20 * 60_000, now))
    @Test fun pastIsClamped() = assertEquals("< 1 ч.", formatRemaining(now - hour, now))
}

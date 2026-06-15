package com.clauditor.toolwindow

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimitGateTest {

    @Test
    fun `pro and max always show the bars`() {
        assertTrue(rateLimitsVisibleFor("pro", anyRateLimitsSeen = false))
        assertTrue(rateLimitsVisibleFor("max", anyRateLimitsSeen = false))
    }

    @Test
    fun `subscription matching is case-insensitive`() {
        assertTrue(rateLimitsVisibleFor("MAX", anyRateLimitsSeen = false))
        assertFalse(rateLimitsVisibleFor("Enterprise", anyRateLimitsSeen = true))
    }

    @Test
    fun `enterprise never shows the bars`() {
        assertFalse(rateLimitsVisibleFor("enterprise", anyRateLimitsSeen = true))
        assertFalse(rateLimitsVisibleFor("enterprise", anyRateLimitsSeen = false))
    }

    @Test
    fun `team and free defer to live rate-limit data`() {
        assertTrue(rateLimitsVisibleFor("team", anyRateLimitsSeen = true))
        assertFalse(rateLimitsVisibleFor("team", anyRateLimitsSeen = false))
        assertTrue(rateLimitsVisibleFor("free", anyRateLimitsSeen = true))
        assertFalse(rateLimitsVisibleFor("free", anyRateLimitsSeen = false))
    }

    @Test
    fun `null empty or unrecognized tiers keep the bars so a subscriber is never wrongly hidden`() {
        assertTrue(rateLimitsVisibleFor(null, anyRateLimitsSeen = false))
        assertTrue(rateLimitsVisibleFor("", anyRateLimitsSeen = false))
        assertTrue(rateLimitsVisibleFor("some_future_tier", anyRateLimitsSeen = false))
    }
}

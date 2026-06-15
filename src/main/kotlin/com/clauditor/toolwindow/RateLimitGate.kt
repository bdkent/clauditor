package com.clauditor.toolwindow

/**
 * Decides whether the 5h/7d rate-limit bars are meaningful for a given plan.
 *
 * Keyed on the coarse `subscriptionType` from `claude auth status`
 * (free | pro | max | team | enterprise | null):
 *  - **pro / max** → always show (real subscription windows).
 *  - **enterprise** → never show (no subscription usage; the statusline omits `rate_limits`).
 *  - **team / free / unknown** → defer to [anyRateLimitsSeen], i.e. whether the live statusline has
 *    actually reported rate-limit data. Those tiers are ambiguous, so the data is the ground truth.
 *  - **null / unrecognized** → show, so a not-yet-resolved auth check or a future tier rename never
 *    hides the bars from a paying subscriber (they harmlessly render "—" when there's no data).
 *
 * Pure and IDE-free so it can be unit-tested without a Project fixture — see RateLimitGateTest.
 */
fun rateLimitsVisibleFor(subscriptionType: String?, anyRateLimitsSeen: Boolean): Boolean =
    when (subscriptionType?.lowercase()) {
        "enterprise" -> false
        "pro", "max" -> true
        "team", "free" -> anyRateLimitsSeen
        else -> true
    }

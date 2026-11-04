package com.xw.vvtts.utils

import kotlin.math.roundToInt

/**
 * Maps Android's percentage rate to OpenEVV's 0-250 speed parameter.
 * Android's percentage scales words per minute, so use OpenEVV's own
 * eci2RealWorld/realWorld2eci speed curve rather than scaling its raw control.
 */
object SpeechRate {
    private const val MIN_SPEED = 0
    private const val MAX_SPEED = 250
    private const val SPEED_SQUARE = 14
    private const val SPEED_LINEAR = 1406
    private const val SPEED_BASE = 70250
    private const val SPEED_SCALE = 1000

    /** OpenEVV's rounded raw-speed-to-WPM conversion from eci_convert.c. */
    internal fun wordsPerMinuteForSpeed(speed: Int): Int {
        val bounded = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        val thousandths =
            SPEED_SQUARE * bounded * bounded + SPEED_LINEAR * bounded + SPEED_BASE
        return (thousandths + SPEED_SCALE / 2) / SPEED_SCALE
    }

    /**
     * The native speed that produces [percent] of [base]'s WPM. A rate of 100
     * preserves the voice's configured speed; values outside the native range
     * resolve to the nearest supported speed.
     */
    fun speedForPercent(base: Int, percent: Int): Int {
        val boundedBase = base.coerceIn(MIN_SPEED, MAX_SPEED)
        if (percent == 100) return boundedBase

        val maxWpm = wordsPerMinuteForSpeed(MAX_SPEED)
        val targetWpm = (
            wordsPerMinuteForSpeed(boundedBase).toDouble() *
                percent.coerceAtLeast(0).toDouble() / 100.0
            ).coerceAtMost(maxWpm.toDouble()).roundToInt()
        return speedForWordsPerMinute(targetWpm)
    }

    private fun speedForWordsPerMinute(targetWpm: Int): Int {
        val minWpm = wordsPerMinuteForSpeed(MIN_SPEED)
        val maxWpm = wordsPerMinuteForSpeed(MAX_SPEED)
        if (targetWpm <= minWpm) return MIN_SPEED
        if (targetWpm >= maxWpm) return MAX_SPEED

        var low = MIN_SPEED
        var high = MAX_SPEED
        while (high - low > 1) {
            val middle = (low + high) / 2
            if (wordsPerMinuteForSpeed(middle) < targetWpm) {
                low = middle
            } else {
                high = middle
            }
        }

        val below = targetWpm - wordsPerMinuteForSpeed(low)
        val above = wordsPerMinuteForSpeed(high) - targetWpm
        return if (below <= above) low else high
    }
}

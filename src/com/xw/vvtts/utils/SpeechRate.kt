package com.xw.vvtts.utils

/**
 * Android's speech rate (100 = normal, 200 = twice as fast) turned into the
 * engine's speed number (0-250).
 *
 * The engine's speed is not linear in how fast it speaks. Measured on one
 * sentence with everything else held still (ported from evvdroid's SpeechRate):
 *
 *     speed   50   100   150    200    250
 *     times  1.0   2.7   7.0   16.6   21.2
 *
 * A linear `speed = 50 * rate / 100` makes a rate of 400% speed 200, about
 * sixteen times normal, far too fast to follow. Interpolating through the
 * measured table makes it about four times normal.
 */
object SpeechRate {

    /** Speed numbers, and how many times the speed-50 rate each one speaks at. */
    private val SPEED = intArrayOf(0, 5, 10, 20, 30, 40, 50, 60, 70, 85, 100, 125, 150, 175, 200, 225, 250)

    private val TIMES = doubleArrayOf(
        0.400, 0.416, 0.450, 0.552, 0.675, 0.823, 1.000, 1.226, 1.504,
        1.976, 2.740, 4.383, 6.986, 11.801, 16.633, 20.427, 21.170
    )

    /** How many times the speed-50 rate [speed] speaks at. */
    fun timesFor(speed: Int): Double {
        val want = speed.coerceIn(SPEED.first(), SPEED.last())
        for (i in 1 until SPEED.size) {
            if (want > SPEED[i]) continue
            val span = (SPEED[i] - SPEED[i - 1]).toDouble()
            val along = (want - SPEED[i - 1]) / span
            return TIMES[i - 1] + along * (TIMES[i] - TIMES[i - 1])
        }
        return TIMES.last()
    }

    /** The speed number that speaks [times] times the speed-50 rate. */
    fun speedFor(times: Double): Int {
        if (times <= TIMES.first()) return SPEED.first()
        for (i in 1 until TIMES.size) {
            if (times > TIMES[i]) continue
            val span = TIMES[i] - TIMES[i - 1]
            val along = if (span <= 0.0) 0.0 else (times - TIMES[i - 1]) / span
            return Math.round(SPEED[i - 1] + along * (SPEED[i] - SPEED[i - 1])).toInt()
        }
        return SPEED.last()
    }

    /**
     * The speed number for [percent] of the rate [base] speaks at, where 100 is
     * the voice left alone.
     */
    fun speedForPercent(base: Int, percent: Int): Int {
        if (percent == 100) return base
        val want = timesFor(base) * percent.coerceAtLeast(0) / 100.0
        return speedFor(want).coerceIn(0, 250)
    }
}

package com.xw.vvtts.services

import android.os.SystemClock

/** Charges time spent waiting for synthesis, excluding paced playback/detection.
 * The engine's per-call watchdog still bounds one blocked native call. */
internal class SynthesisBudget(private val limitMs: Long, private val clock: () -> Long = { SystemClock.elapsedRealtime() }) {
    private var spentMs = 0L
    val exhausted: Boolean get() = spentMs >= limitMs
    /** Run a block and charge its elapsed time to the budget, including when it throws. */
    fun <T> measure(block: () -> T): T {
        val started = clock()
        try { return block() } finally {
            spentMs += (clock() - started).coerceAtLeast(0L)
        }
    }
}

#!/usr/bin/env python3
"""Resolve 9 conflict blocks in /tmp/wt232/src/com/xw/vvtts/services/VvTtsService.kt."""
import re

p = 'src/com/xw/vvtts/services/VvTtsService.kt'
src = open(p, 'r', encoding='utf-8').read()

MARK = re.compile(r'^<<<<<<< HEAD\n(.*?)^=======\n(.*?)^>>>>>>> origin/main\n', re.M | re.S)

repls = [
    # A: onCreate engine (HEAD null-safe: eng is nullable)
    "        engine = eng\n        eng?.setVoiceProfile(voiceProfile)\n        val ok = eng != null && eng.isInitialized()\n",
    # B: onDestroy comment (main; bumpGeneration sets the stop flag)
    "        // No aggressive shutdown: TextToSpeechService gets created/destroyed,\n        // aggressive shutdown would force the native engine to reload repeatedly (process restarts are expensive).)\n        // Let GC reclaim; a leaked engine handle is acceptable (the handle lives as long as the service process etc.).\n        // No executor anymore: synthesis is synchronous within onSynthesizeText(),\n        // so teardown just drops queued work via the generation bump above.\n",
    # C: onSynthesizeText (main: per-utterance bump + REE catch)
    "    /** Synthesizes synchronously:every callback call (start/audio/done/error) finishes\n     *  within this method's lifetime, per the framework contract. */\n    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {\n        val schedAt = SystemClock.elapsedRealtime()\n        try {\n            // Per-utterance bump: the newest request supersedes still-QUEUED\n            // older ones \u2014 never a plain FIFO for a screen reader (a FIFO that\n            // outpaces the paced drain trails focus for seconds, then \"dies\").\n            // In-flight is unaffected: the chunk loops bail only on stopping(),\n            // and this snapshot post-dates the bump, so THIS task survives the\n            // dequeue gates. The engine cannot abandon an active synthesis.\n            val gen = generation.incrementAndGet()\n            runSynthesis(request, callback, gen, schedAt)\n        } catch (e: RejectedExecutionException) {\n            Log.w(TAG, \"service shutting down;dropping utterance\", e)\n            // Framework contract: every onSynthesizeText must terminate the\n            // callback. runSynthesis never runs on this path, so no start()/done()\n            // pair can fire \u2014 error() is the designated failure termination.\n            callback.error(TextToSpeech.ERROR_SYNTHESIS)\n        }\n",
    # D: stop-flag clear (main: stopPending snapshot)
    "        // The stop flag is cleared only here, once the utterance actually\n        // dequeues:clearing it in onSynthesizeText() would let previous\n        // utterance (still draining on the single-thread executor( resume\n        // after a TalkBack re-swipes, causing overlapping speech.\n        // Snapshot the flag BEFORE the gate check: a stop() racing between the\n        // snapshot and the re-check keeps its flag ( the reset runs only after\n        // the check(, so a genuine cancellation is never misrouted to silentComplete()\n        // instead of error().)\n        val stopPending = stopping\n",
    # E: stop-race gate (main: stopPending restore / superseded silent)
    "            if (stopPending || stopping) {\n                // Genuine stop race: restore the flag and drop via error()..\n                // (Re-set only whenthe snapshot saw it; a stop past the checkpoint\n                // already left the flag true \u2014 never clear it.)\n                if (stopPending) stopping = true\n                Log.w(TAG, \"stop raced the stop flag reset; gen=\" + gen + \" generation=\" + generation.get())\n                try {\n                    callback.error(TextToSpeech.ERROR_SYNTHESIS)\n                } catch (ignore: Throwable) {}\n            } else {\n                // A newer utterance won the queue in this window: superseded,\n                // complete it silently without touching the stop flag.\n                Log.d(TAG, \"utterance superseded mid-queue; gen=\" + gen + \" generation=\" + generation.get())\n                silentComplete(callback)\n            }\n",
    # F: segment-loop bail (main: stop-only; per-utterance bump covers queued)
    "                            // Bail on stop() only. A per-utterance generation bump\n                            // supersedes QUEUED work at the dequeue gates; the engine\n                            // cannot abandon an in-flight synthesis, so a new utterance\n                            // must never cut this one mid-stream.\n                            if (stopping) break\n",
    # G: chunk-loop bail (main stop-only + HEAD truncated-marking restored)
    "                    // stop() only, same rule as the segment loop above.\n                    if (stopping) break\n                    if (SystemClock.elapsedRealtime() > uttDeadline) {\n                        synthFailedOrTruncated = true   // truncated: error(), not done()\n                        break\n                    }\n",
    # H: field declaration (main: volatile var; common code uses bare access)
    "\n    @Volatile private var stopping = false\n    /** Serializes stop-gate checks/flag-reset in runSynthesis against onStop()/bumpGeneration() generation bumps. */\n    private val cancellationLock = Any()\n",
    # I: bumpGeneration + onStop (main: synchronized(cancellationLock))
    "        synchronized(cancellationLock) {\n            stopping = true\n            generation.incrementAndGet()\n            synchronized(pacingMonitor) { (pacingMonitor as Object).notifyAll() }  // wake the pacing wait now\n            Log.i(\"VvTtsX\", \"generation bump (stop/unbind(: generation=\" + generation)\n        }\n    }\n        /** Invalidates queued requests, stops audio delivery and asks the current engine to stop. */\n        override fun onStop() {\n            synchronized(cancellationLock) {\n                stopping = true\n                generation.incrementAndGet()  // invalidate utterances already queued pre-stop\n                synchronized(pacingMonitor) { (pacingMonitor as Object).notifyAll() }  // wake the pacing wait so cancellation is immediate\n            }\n",
]

blocks = MARK.findall(src)
assert len(blocks) == 9, 'expected 9 blocks, got %d' % len(blocks)

state = {'i': 0}

def sub_fn(m):
    i = state['i']
    state['i'] += 1
    return repls[i]

out = MARK.sub(sub_fn, src)
assert MARK.search(out) is None, 'markers remain'
open(p, 'w', encoding='utf-8').write(out)
print('resolved 9 blocks in VvTtsService.kt')
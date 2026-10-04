#!/usr/bin/env python3
import re, sys

def load(p):
    with open(p, 'r', encoding='utf-8') as f:
        return f.read()

def save(p, s):
    with open(p, 'w', encoding='utf-8') as f:
        f.write(s)

def resolve_hunks(text, replacements):
    """replacements: list of (resolved_text, …) applied in order of their <<<<<<< occurrence."""
    out = []
    pos = 0
    for resolved in replacements:
        m = re.search(r'(?ms)^<<<<<<< HEAD\n.*?\n>>>>>>> (?:HEAD|origin/main)\n?', text[pos:])
        if not m:
            raise SystemExit('marker block not found')
        out.append(text[pos:pos + m.start()])
        out.append(resolved)
        out.append('\n' if not resolved.endswith('\n') else '')
        pos += m.end()
    out.append(text[pos:])
    return ''.join(out)

# ---------- VvTtsService.kt ----------
p_v = 'src/com/xw/vvtts/services/VvTtsService.kt'
t_v = load(p_v)

v_res = [

# 1) onCreate warmup: main's background-thread version with null-guard
'''        // Background thread: binder queries (getLanguage/getVoices/onInit reply)
        // can arrive while onCreate is still running; a long synchronous warmup
        // would delay the init state the framework builds from. Synthesis locks
        // engineCallLock, so a concurrent warmup is safe.


        if (eng != null && eng.isInitialized()) {

            val warmDialect = try {
                LanguageDetector.getFixedDialect()
            } catch (t: Throwable) {
                Log.w(TAG, "fixed dialect query failed; warming default", t)
                LanguageDetector.DIALECT_EN_US
            }
            val warmEngine = eng
            Thread {
                try {
                    synchronized(engineCallLock) { warmEngine.warmupDialect(warmDialect)
                } catch (t: Throwable) {
                    Log.w(TAG, "background warmup failed", t)
                }
            }.apply { isDaemon = true }.start()
        }
''',

# 2) start() guard: HEAD pipeEnded + main's condition (.get()-adapted） + main's finally close
'''                    // Once the framework itself killed the pipe (a start()/audioAvailable()
                    // verdict of STOPPED/ERROR), no further calls on the callback are<
                    // legal: it already knows the pipe died,and a done()/error() would
                    // be invalid speech on a dead pipe.


                    if (pipeEnded) return


        }
                    if (!started && !stopping.get() && gen == generation.get()) {
''',

# 3) termination tail: main's stopping/gen error+return FIRST, then HEAD's synthFailedOrTruncated if (common completes it)
'''                    if (stopping.get() || gen != generation.get()) {
                        try {
                            callback.error(TextToSpeech.ERROR_SYNTHESIS)
                        } catch (ignore: Throwable) {
                        }
                        return
                    }
                    if (synthFailedOrTruncated) {



                        // A truncated/failed synthesis must not be reported as a success:the
                        // framework listener needs error() so it can retry/announce failure.


                        Log.w(TAG, "synthesis failed or truncated; reporting error()")
''',

# 4) declarations: HEAD AtomicBoolean stopping + main's pacingMonitor (deliveryExecutor dropped, unreferenced)
'''    private val stopping = AtomicBoolean(false)


    /** Signals the pacing wait in hold(): onStop()/bumpGeneration() notifyAll()
     *  so a cancellation interrupts the artificial audio-duration sleep at once. */
    private val pacingMonitor = Any()
''',

# 5) hold(): main's monitor-wait (.get()-adapted), common tail closes synchronized+while
'''            while (over > 0L && !stopping.get()) {
                // Monitor-wait instead of a raw sleep: onStop()/bumpGeneration()
                // notifyAll() so a stop interrupts the artificial pacing period
                // immediately instead of only after the sleep interval ends.


                synchronized(pacingMonitor) {
                    if (!stopping.get()) {
                        var waiterHit = false
                        try {
                            (pacingMonitor as Object).wait(minOf(over, 20L))
                        } catch (ie: InterruptedException) {
                            waiterHit = true
                        }
                        // break/continue inside synchronized() (an inline lambda) is
                        // experimental in Kotlin 1.9and errors out; re-check in plain scope.


                        if (waiterHit) { break }
                    }
                }
''',
]

t_v = resolve_hunks(t_v, v_res)
save(p_v, t_v)
print('VvTtsService resolved, hunks:', len(v_res))

# ---------- EloquenceEngine.kt ----------
p_e = 'src/com/xw/vvtts/engine/EloquenceEngine.kt'
t_e = load(p_e)

e_res = [

# 1) shutdown(): main's synthWorker body + HEAD drain guard after worker.executor.shutdown()
'''    fun shutdown() {
        engineEpoch++
        val worker = synthWorker
        try {
            worker.executor.execute {
                // Cleanup queues behind any in-flight synthesis so it frees the
                // handles on their owning thread only after the native call returns.


                for (h in worker.handles.values) VvtttsCore.shutdown(h)
                worker.handles.clear()
            }
        } catch (ignore: RejectedExecutionException) {
            // A retired worker may still be using its handles; its in-flight task


            // frees them on the owning thread once it returns (see synthWithTimeout.

        }
        // Retire this worker so no fresh work lands on a closing executor;the
        // replacement starts with clean per-worker caches (fresh handles begin at the
        // engine-default voice, so no stale voice/param state can leak across an open).

        worker.executor.shutdown()


        // Drain/cancel in-flight work BEFORE closing the native handles: a worker
        // still inside synthesizeCore would otherwise hit a native session that
        // shutdown() just freed (use-after-free racing the closed EP pipe),and
        // submit() during teardown would throw. A hung worker gets shutdownNow()
        // after a short grant.



        try {
            if (!worker.executor.awaitTermination(150, TimeUnit.MILLISECONDS))) {

                worker.executor.shutdownNow()
                worker.executor.awaitTermination(100, TimeUnit.MILLISECONDS)
            }
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            worker.executor.shutdownNow()
        }
        synthWorker = SynthWorker()
        initialized = false
        core = null
''',

# 2) warmupDialect(): HEAD's guards, adapted to worker executor
'''        if (dialect < 0) return
        val worker = synthWorker
        // After shutdown() the executor is dead and the natives are closed: a
        // warmup submitted then would throw RejectedExecutionException into
        // onLoadLanguage/onCreate. No-op instead (initialized is the gate).


        if (!initialized) {
            Log.i(TAG, "warmupDialect skipped: engine not initialized")
            return
        }
        try {
            worker.executor.execute { ensureHandle(worker, dialect) }
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "warmupDialect skipped: executor shutting down")
        }
''',
]

t_e = resolve_hunks(t_e, e_res)
save(p_e, t_e)
print('EloquenceEngine resolved, hunks:', len(e_res))

# ---------- LanguageDetector.kt ----------
p_l = 'src/com/xw/vvtts/utils/LanguageDetector.kt'
t_l = load(p_l)
t_l = resolve_hunks(t_l, ['            transientEnabled.set(langs)\n'])
save(p_l, t_l)
print('LanguageDetector resolved')

# ---------- verify ----------
import glob
bad = []
for f in glob.glob('src/com/xw/*/*.kt') + glob.glob('src/com/xw/*/*/*.kt'):
    s = load(f)
    if re.search(r'(?m)^<<<<<<<|^>>>>>>>|^=======$', s):
        bad.append(f)
print('files with remaining markers:', bad)
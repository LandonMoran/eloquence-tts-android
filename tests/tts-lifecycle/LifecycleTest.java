package com.xw.vvtts.services;

import android.content.Context;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import com.xw.vvtts.engine.EloquenceEngine;
import com.xw.vvtts.services.VvTtsService;
import java.lang.reflect.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import kotlin.jvm.functions.Function0;

/** Exercises production lifecycle code; latches stand in for native and framework calls. */
public class LifecycleTest {
    static Object get(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
    static void set(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
    static void owner(VvTtsService service) throws Exception {
        Field f = VvTtsService.class.getDeclaredField("processEngineOwner");
        f.setAccessible(true);
        f.set(null, service);
    }
    static void await(CountDownLatch latch) throws Exception {
        if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("latch timed out");
    }
    static void nativeWait(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try { latch.await(); break; }
            catch (InterruptedException e) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static ExecutorService executor(Object target, String field) throws Exception {
        return (ExecutorService) get(target, field);
    }
    static SynthesisCallback callback(Runnable error) {
        return (SynthesisCallback) Proxy.newProxyInstance(LifecycleTest.class.getClassLoader(),
            new Class<?>[]{SynthesisCallback.class}, (proxy, method, args) -> {
                if (method.getName().equals("error")) error.run();
                if (method.getReturnType() == int.class) return 0;
                if (method.getReturnType() == boolean.class) return false;
                return null;
            });
    }
    static VvTtsService service(EloquenceEngine engine) throws Exception {
        VvTtsService service = new VvTtsService();
        set(service, "engine", engine);
        owner(service);
        return service;
    }
    static void stopWithoutLock(boolean unbind) throws Exception {
        EloquenceEngine engine = new EloquenceEngine(new Context());
        VvTtsService service = service(engine);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            synchronized (get(service, "engineCallLock")) {
                caller.submit(() -> {
                    if (unbind) service.onUnbind(null); else service.onStop();
                }).get(1, TimeUnit.SECONDS);
                check((boolean) get(engine, "stopped"), "stop flag was not set");
            }
        } finally {
            caller.shutdown();
            service.onDestroy();
            check(executor(service, "cleanupExecutor").awaitTermination(3, TimeUnit.SECONDS), "cleanup did not finish");
            executor(engine, "synthExecutor").shutdown();
        }
    }
    static void drain() throws Exception {
        EloquenceEngine engine = new EloquenceEngine(new Context());
        // Force the real watchdog path quickly. The fake native call ignores interruption.
        set(engine, "HANG_TIMEOUT_S", 1L);
        VvTtsService service = service(engine);
        CountDownLatch nativeEntered = new CountDownLatch(1), nativeExit = new CountDownLatch(1);
        CountDownLatch callbackEntered = new CountDownLatch(1), callbackExit = new CountDownLatch(1);
        CountDownLatch cleanupEntered = new CountDownLatch(1), cleanupStart = new CountDownLatch(1);
        AtomicInteger terminals = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Thread> cleanupThread = new AtomicReference<>();
        ExecutorService delivery = executor(service, "deliveryExecutor");
        ExecutorService cleanup = executor(service, "cleanupExecutor");
        cleanup.execute(() -> {
            cleanupThread.set(Thread.currentThread());
            cleanupEntered.countDown();
            nativeWait(cleanupStart);
        });
        await(cleanupEntered);
        Method synth = EloquenceEngine.class.getDeclaredMethod("synthWithTimeout", Function0.class);
        synth.setAccessible(true);
        delivery.execute(() -> {
            try {
                synchronized (get(service, "engineCallLock")) {
                    synth.invoke(engine, (Function0<short[]>) () -> {
                        nativeEntered.countDown();
                        nativeWait(nativeExit);
                        return new short[0];
                    });
                }
            } catch (Throwable e) { failure.set(e); }
        });
        await(nativeEntered);
        for (int i = 0; i < 3; i++) {
            service.onSynthesizeText(new SynthesisRequest(), callback(() -> {
                terminals.incrementAndGet();
                callbackEntered.countDown();
                nativeWait(callbackExit);
            }));
        }
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            // Native synthesis still holds engineCallLock. onDestroy must return anyway.
            caller.submit(service::onDestroy).get(1, TimeUnit.SECONDS);
            cleanupStart.countDown();
            await(callbackEntered); // Delivery passed the watchdog, but native is still running.
            check(failure.get() == null, "synthesis failed: " + failure.get());
            check((boolean) get(engine, "stopped"), "cleanup did not stop engine");
            cleanupThread.get().interrupt();
            check(!cleanup.awaitTermination(100, TimeUnit.MILLISECONDS), "cleanup abandoned callback");
            callbackExit.countDown();
            check(delivery.awaitTermination(3, TimeUnit.SECONDS), "delivery did not drain");
            check(terminals.get() == 3, "accepted callbacks were discarded");
            cleanupThread.get().interrupt();
            check(!cleanup.awaitTermination(100, TimeUnit.MILLISECONDS), "cleanup abandoned native synthesis");
            service.onSynthesizeText(new SynthesisRequest(), callback(terminals::incrementAndGet));
            check(terminals.get() == 4, "post-destroy request was not terminated");
            nativeExit.countDown();
            check(cleanup.awaitTermination(3, TimeUnit.SECONDS), "cleanup failed to finish");
            check(get(service, "engine") == null, "service retained engine after drain");
        } finally {
            cleanupStart.countDown(); callbackExit.countDown(); nativeExit.countDown();
            caller.shutdown();
            executor(engine, "synthExecutor").shutdown();
        }
    }
    static void rebind() throws Exception {
        EloquenceEngine engine = new EloquenceEngine(new Context());
        VvTtsService old = service(engine);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService cleanup = executor(old, "cleanupExecutor");
        cleanup.execute(() -> { entered.countDown(); nativeWait(release); });
        await(entered);
        old.onDestroy();
        VvTtsService next = service(engine);
        release.countDown();
        check(cleanup.awaitTermination(3, TimeUnit.SECONDS), "old cleanup did not finish");
        check(!(boolean) get(engine, "stopped"), "old cleanup cancelled new owner");
        next.onDestroy();
        check(executor(next, "cleanupExecutor").awaitTermination(3, TimeUnit.SECONDS), "new cleanup did not finish");
        executor(engine, "synthExecutor").shutdown();
    }
    static void pendingAndRejectedSynthesis() throws Exception {
        for (boolean reject : new boolean[]{false, true}) {
            EloquenceEngine engine = new EloquenceEngine(new Context());
            set(engine, "HANG_TIMEOUT_S", 1L);
            ExecutorService worker = executor(engine, "synthExecutor");
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            if (reject) {
                worker.shutdown();
            } else {
                worker.execute(() -> { entered.countDown(); nativeWait(release); });
                await(entered);
            }
            try {
                Method synth = EloquenceEngine.class.getDeclaredMethod("synthWithTimeout", Function0.class);
                synth.setAccessible(true);
                AtomicBoolean ran = new AtomicBoolean();
                synth.invoke(engine, (Function0<short[]>) () -> { ran.set(true); return null; });
                check(!ran.get(), "cancelled queued synthesis ran");
                check(((java.util.Set<?>) get(engine, "activeSynthesis")).isEmpty(),
                    "queued/rejected synthesis leaked completion marker");
            } finally {
                release.countDown();
                check(worker.awaitTermination(3, TimeUnit.SECONDS), "retired worker did not finish");
                executor(engine, "synthExecutor").shutdown();
            }
        }
    }
    public static void main(String[] args) throws Exception {
        stopWithoutLock(false);
        stopWithoutLock(true);
        drain();
        rebind();
        pendingAndRejectedSynthesis();
        System.out.println("PASS: stop, unbind, asynchronous destroy, callback/native drain, interruption, rejection, rebind, queued/rejected native work");
    }
}

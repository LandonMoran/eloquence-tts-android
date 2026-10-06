package com.xw.vvtts.core;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
/** Native boundary double; production JNI is tested separately with sanitizers. */
public class VvttsCore {
    public static final Companion Companion=new Companion();
    public static class Companion {
        /** Forward Kotlin companion-style engine creation to the instrumented static fixture. */
        public long openEngine(String config,String libs,int dialect) { return VvttsCore.openEngine(config,libs,dialect); }
        /** Forward Kotlin companion-style shutdown to the close counter. */
        public void shutdown(long handle) { VvttsCore.shutdown(handle); }
        /** Forward Kotlin companion-style stop to the fixture's no-op implementation. */
        public void stop(long handle) { VvttsCore.stop(handle); }
    }
    public static final AtomicInteger opens=new AtomicInteger(), closes=new AtomicInteger();
    public static volatile CountDownLatch entered, release;
    /** Count an open and optionally block it on a latch, preserving interruption for retirement tests. */
    public static long openEngine(String config,String libs,int dialect) {
        int id=opens.incrementAndGet();
        CountDownLatch signal=entered, wait=release;
        if(signal!=null)signal.countDown();
        if(wait!=null) {
            boolean interrupted=false;
            while(true) { try { wait.await(); break; } catch(InterruptedException e){interrupted=true;} }
            if(interrupted)Thread.currentThread().interrupt();
        }
        return id;
    }
    /** Count handle closures without invoking JNI. */
    public static void shutdown(long handle) { closes.incrementAndGet(); }
    /** Leave fixture handles untouched when stop is requested. */
    public static void stop(long handle) {}
}

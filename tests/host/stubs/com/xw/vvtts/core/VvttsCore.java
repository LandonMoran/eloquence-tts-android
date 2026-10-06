package com.xw.vvtts.core;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
/** Native boundary double; production JNI is tested separately with sanitizers. */
public class VvttsCore {
    public static final Companion Companion=new Companion();
    public static class Companion {
        public long openEngine(String config,String libs,int dialect) { return VvttsCore.openEngine(config,libs,dialect); }
        public void shutdown(long handle) { VvttsCore.shutdown(handle); }
        public void stop(long handle) { VvttsCore.stop(handle); }
    }
    public static final AtomicInteger opens=new AtomicInteger(), closes=new AtomicInteger();
    public static volatile CountDownLatch entered, release;
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
    public static void shutdown(long handle) { closes.incrementAndGet(); }
    public static void stop(long handle) {}
}

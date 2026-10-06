package com.xw.vvtts.update;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
public class ElqUpdateChecker {
    public static final ElqUpdateChecker INSTANCE=new ElqUpdateChecker();
    public static final AtomicInteger calls=new AtomicInteger();
    public static final CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1), returned=new CountDownLatch(1);
    public UpdateResult check(android.content.Context app) {
        calls.incrementAndGet(); entered.countDown();
        while(true) { try { release.await(); break; } catch(InterruptedException ignored) {} }
        returned.countDown(); return new UpdateResult();
    }
    public static class UpdateResult {
        public String getError() { return null; }
        public boolean getHasUpdate() { return false; }
        public String getLatestTag() { return "1.0"; }
        public String getReleaseNotes() { return ""; }
        public String getDownloadUrl() { return null; }
        public String getHtmlUrl() { return null; }
    }
}

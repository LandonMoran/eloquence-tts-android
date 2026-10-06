package android.os;
import java.util.concurrent.ConcurrentLinkedQueue;
public class Handler {
    private static final ConcurrentLinkedQueue<Runnable> queue=new ConcurrentLinkedQueue<>();
    /** Accept a looper token; queued callbacks run only when the host test drains them. */
    public Handler(Looper looper) {}
    /** Queue a callback for deterministic host execution and report acceptance. */
    public boolean post(Runnable action) { queue.add(action); return true; }
    /** Execute queued callbacks on the calling test thread until the queue is empty. */
    public static void drain() { Runnable action; while((action=queue.poll())!=null) action.run(); }
}

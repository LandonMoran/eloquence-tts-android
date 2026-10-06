package android.os;
import java.util.concurrent.ConcurrentLinkedQueue;
public class Handler {
    private static final ConcurrentLinkedQueue<Runnable> queue=new ConcurrentLinkedQueue<>();
    public Handler(Looper looper) {}
    public boolean post(Runnable action) { queue.add(action); return true; }
    public static void drain() { Runnable action; while((action=queue.poll())!=null) action.run(); }
}

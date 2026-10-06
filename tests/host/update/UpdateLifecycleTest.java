package com.xw.vvtts.tests;
import android.app.*;
import android.os.Handler;
import android.widget.Toast;
import com.xw.vvtts.update.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

public class UpdateLifecycleTest {
    /** Throw an assertion error with context when a lifecycle invariant fails. */
    static void check(boolean ok,String message) { if(!ok)throw new AssertionError(message); }
    /** Start a blocked update, reject a duplicate check, then dismiss its owner and return a weak reference. */
    static WeakReference<Activity> beginAndDestroy() throws Exception {
        Activity owner=new Activity();
        UpdateActions.INSTANCE.showCheckDialog(owner);
        check(ElqUpdateChecker.entered.await(2,TimeUnit.SECONDS),"worker not started");
        UpdateActions.INSTANCE.showCheckDialog(owner);
        check(ElqUpdateChecker.calls.get()==1 && AlertDialog.active==1,"two checks/dialogs admitted");
        WeakReference<Activity> weak=new WeakReference<>(owner);
        UpdateActions.INSTANCE.dismissFor(owner);
        check(AlertDialog.active==0,"dialog retained");
        return weak;
    }
    /** Verify owner collection, stale-result rejection, and successful checks after activity recreation. */
    public static void main(String[] args) throws Exception {
        WeakReference<Activity> old=beginAndDestroy();
        for(int i=0;i<40 && old.get()!=null;i++) { System.gc(); Thread.sleep(10); }
        check(old.get()==null,"destroyed Activity retained by blocked worker");
        Field current=UpdateActions.class.getDeclaredField("current"); current.setAccessible(true);
        check(current.get(UpdateActions.INSTANCE)==null,"old flow retained");
        ElqUpdateChecker.release.countDown();
        check(ElqUpdateChecker.returned.await(2,TimeUnit.SECONDS),"check did not finish");
        // Join the named daemon to ensure its completion callback is queued.
        for(Thread t:Thread.getAllStackTraces().keySet())if(t.getName().equals("update-check"))t.join(2000);
        Handler.drain();
        check(AlertDialog.active==0 && Toast.shown==0,"stale completion touched destroyed UI");
        Activity replacement=new Activity();
        UpdateActions.INSTANCE.showCheckDialog(replacement);
        for(Thread t:Thread.getAllStackTraces().keySet())if(t.getName().equals("update-check"))t.join(2000);
        Handler.drain();
        check(ElqUpdateChecker.calls.get()==2 && Toast.shown==1,"replacement could not check updates");
        System.out.println("PASS updater: single flight, destroyed Activity collectible during blocked network, stale callback rejection, recreation");
    }
}

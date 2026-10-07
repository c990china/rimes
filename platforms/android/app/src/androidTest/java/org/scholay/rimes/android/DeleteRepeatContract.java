package org.scholay.rimes.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Looper;
import android.os.Handler;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.scholay.rimes.core.KeyboardLayout;
import org.scholay.rimes.core.ChordLayout;
import org.scholay.rimes.core.ChordGesture;

/** Real-window native long-press, Handler scheduling and target-retirement regression contract. */
final class DeleteRepeatContract {
    private final Instrumentation instrumentation;
    private final Activity activity;
    private KeyboardRoot root;
    private KeyboardSurface surface;
    private ChordSurface chordSurface;
    private boolean split,resolves=true;
    private KeyboardLayout.Mode mode=KeyboardLayout.Mode.QWERTY;
    private KeyboardTheme theme=KeyboardTheme.ALL[0];
    private KeyButton delete;
    private final List<Long> deleteTimes=new ArrayList<>();
    private final List<Integer> deleteTargets=new ArrayList<>();
    private int checks,deletes,target;
    private boolean enabled=true;
    private long down;
    private final Handler main=new Handler(Looper.getMainLooper());
    private CountDownLatch workerGate,workerStarted;
    private int pendingWork,peakPendingWork;
    private boolean targetAvailable=true;
    private final AtomicReference<Throwable> workerFailure=new AtomicReference<>();

    private DeleteRepeatContract(Instrumentation instrumentation,Activity activity) {
        this.instrumentation=instrumentation; this.activity=activity;
    }
    static int run(Instrumentation instrumentation) {
        if(Looper.myLooper()==Looper.getMainLooper()) throw new IllegalStateException("DeleteRepeatContract must run off main");
        Instrumentation.ActivityMonitor monitor=instrumentation.addMonitor(SetupActivity.class.getName(),null,false);
        Activity activity=null;
        String component=instrumentation.getTargetContext().getPackageName()+"/"+SetupActivity.class.getName();
        try(android.os.ParcelFileDescriptor launch=instrumentation.getUiAutomation().executeShellCommand(
                "am start -W -f 0x10008000 -n "+component)) {
            activity=instrumentation.waitForMonitorWithTimeout(monitor,15000);
            if(activity==null) throw new AssertionError("Delete repeat fixture did not start within 15 seconds");
            DeleteRepeatContract test=new DeleteRepeatContract(instrumentation,activity);
            test.run(); return test.checks;
        } catch(java.io.IOException error) { throw new AssertionError("Delete repeat fixture shell launch failed",error); }
        finally {
            instrumentation.removeMonitor(monitor);
            if(activity!=null) {
                Activity fixture=activity;
                instrumentation.runOnMainSync(fixture::finish);
                instrumentation.waitForIdleSync();
            }
        }
    }
    private void onMain(Runnable action) {
        java.util.concurrent.atomic.AtomicReference<Throwable> error=new java.util.concurrent.atomic.AtomicReference<>();
        instrumentation.runOnMainSync(() -> { try { action.run(); } catch(Throwable failure) { error.set(failure); } });
        if(error.get()!=null) throw new AssertionError("Delete repeat main-thread phase failed",error.get());
    }
    private void idle() { instrumentation.waitForIdleSync(); }
    private void check(boolean condition,String label) {
        checks++; if(!condition) throw new AssertionError("Delete repeat: "+label);
    }
    private void render() {
        if(chordSurface!=null) { chordSurface.render(split,resolves,false,theme); delete=chordSurface.utilityButton(ChordLayout.Action.DELETE); return; }
        surface.render(mode,theme);
        for(int i=0;i<surface.getChildCount();i++) {
            KeyButton button=(KeyButton)surface.getChildAt(i);
            if("Delete".contentEquals(button.getContentDescription())) delete=button;
        }
    }
    private void layoutReady() {
        boolean[] ready={false};
        for(int attempt=0;attempt<80 && !ready[0];attempt++) {
            onMain(() -> ready[0]=delete.isAttachedToWindow() && delete.getWidth()>0 && delete.getHeight()>0);
            if(!ready[0]) SystemClock.sleep(25);
        }
        onMain(() -> check(ready[0],"delete key attached and laid out"));
    }
    private int count() { int[] result={0}; onMain(() -> result[0]=deletes); return result[0]; }
    private void press() {
        SystemClock.sleep(2);
        onMain(() -> { down=SystemClock.uptimeMillis(); emit(MotionEvent.ACTION_DOWN,false); });
    }
    private void emit(int action,boolean outside) {
        View parent=chordSurface==null?surface:chordSurface;
        float x=parent.getLeft()+delete.getLeft()+(outside?delete.getWidth()+1:delete.getWidth()/2f);
        float y=parent.getTop()+delete.getTop()+delete.getHeight()/2f;
        MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);
        try { root.dispatchTouchEvent(event); } finally { event.recycle(); }
    }
    private void release() { onMain(() -> emit(MotionEvent.ACTION_UP,false)); idle(); }
    private void awaitDeletes(int expected) {
        long deadline=SystemClock.uptimeMillis()+ViewConfiguration.getLongPressTimeout()+1500;
        while(count()<expected && SystemClock.uptimeMillis()<deadline) SystemClock.sleep(10);
        onMain(() -> check(deletes>=expected,"held native delete repeats before deadline"));
    }
    private void assertStopped(int expected,String label) {
        SystemClock.sleep(DeleteRepeatTouch.REPEAT_MILLIS*3); idle();
        onMain(() -> check(deletes==expected,label));
    }
    private boolean canRepeatDelete() { return targetAvailable && pendingWork==0; }
    /** One actual serial-worker operation per generated delete; completions return on main. */
    private void queueWork() {
        CountDownLatch release=workerGate,started=workerStarted;
        pendingWork++; peakPendingWork=Math.max(peakPendingWork,pendingWork);
        EngineWorker.QUEUE.execute(() -> {
            started.countDown();
            try {
                if(!release.await(10,TimeUnit.SECONDS)) throw new AssertionError("repeat worker release timed out");
            } catch(Throwable error) {
                if(error instanceof InterruptedException) Thread.currentThread().interrupt();
                workerFailure.compareAndSet(null,error);
            } finally { main.post(() -> pendingWork--); }
        });
    }
    private void workerIdle() {
        try { EngineWorker.QUEUE.submit(() -> {}).get(10,TimeUnit.SECONDS); }
        catch(Exception error) { throw new AssertionError("repeat worker did not become idle",error); }
        idle();
        if(workerFailure.get()!=null) throw new AssertionError("controlled repeat worker failed",workerFailure.get());
    }
    private void awaitWorker() {
        try { check(workerStarted.await(10,TimeUnit.SECONDS),"controlled repeat operation really blocks the serial worker"); }
        catch(InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
    private void runBackpressure(String layout) {
        CountDownLatch[] release={null};
        try {
            onMain(() -> {
                workerGate=new CountDownLatch(1); workerStarted=new CountDownLatch(1); release[0]=workerGate;
                queueWork(); // A preceding composing key is blocked before this held delete.
            }); awaitWorker();
            int before=count(); int ordinaryTapStart=before; press(); release();
            onMain(() -> check(deletes==ordinaryTapStart+1 && pendingWork==2,
                    layout+" short tap stays ordered behind busy work instead of using the repeat gate"));
            int blocked=count(); press();
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout()+DeleteRepeatTouch.REPEAT_MILLIS*4); idle();
            onMain(() -> check(deletes==blocked && pendingWork==2,
                    layout+" a busy first long-click and timer ticks add no queued deletes"));
            release(); release[0].countDown(); workerIdle();
            assertStopped(blocked,layout+" release while busy leaves no delete debt after the worker resumes");
            onMain(() -> check(pendingWork==0,layout+" queued ordinary work completes exactly once"));

            onMain(() -> {
                workerGate=new CountDownLatch(1); workerStarted=new CountDownLatch(1); release[0]=workerGate; peakPendingWork=0;
            });
            before=count(); int heldStart=before; press(); awaitDeletes(before+1); awaitWorker();
            SystemClock.sleep(DeleteRepeatTouch.REPEAT_MILLIS*4); idle();
            onMain(() -> check(deletes==heldStart+1 && pendingWork==1 && peakPendingWork==1,
                    layout+" the first held delete blocks and subsequent timer ticks cannot grow the queue"));
            release[0].countDown(); workerIdle(); awaitDeletes(heldStart+3);
            int[] stopped={0}; onMain(() -> { stopped[0]=deletes; emit(MotionEvent.ACTION_UP,false); }); workerIdle();
            assertStopped(stopped[0],layout+" held delete resumes after completion but stops immediately on UP");
            onMain(() -> check(peakPendingWork==1,layout+" resumed timer permits at most one operation in flight"));

            onMain(() -> {
                workerGate=new CountDownLatch(1); workerStarted=new CountDownLatch(1); release[0]=workerGate;
            });
            before=count(); press(); awaitDeletes(before+1); awaitWorker();
            onMain(() -> { root.cancelPendingInputEvents(); target++; targetAvailable=false; });
            int retired=count(); release[0].countDown(); workerIdle(); release();
            assertStopped(retired,layout+" retired busy press cannot resume on worker completion or late UP");
            onMain(() -> targetAvailable=true);
        } finally {
            if(release[0]!=null) release[0].countDown();
            onMain(() -> root.cancelPendingInputEvents()); workerIdle();
            onMain(() -> {workerGate=null;workerStarted=null;targetAvailable=true;});
        }
    }
    private void run() {
        onMain(() -> {
            activity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            root=new KeyboardRoot(activity); root.setOrientation(LinearLayout.VERTICAL);
            surface=new KeyboardSurface(activity,new KeyboardSurface.Handler() {
                @Override public String label(KeyboardLayout.Key key) { return key.action==KeyboardLayout.Action.DELETE?"Delete":key.text; }
                @Override public String description(KeyboardLayout.Key key) { return label(key); }
                @Override public boolean enabled(KeyboardLayout.Key key) { return key.action!=KeyboardLayout.Action.DELETE || enabled; }
                @Override public boolean selected(KeyboardLayout.Key key) { return false; }
                @Override public boolean canRepeatDelete() { return DeleteRepeatContract.this.canRepeatDelete(); }
                @Override public void press(KeyboardLayout.Key key) {
                    if(key.action==KeyboardLayout.Action.DELETE) {
                        deletes++; deleteTimes.add(SystemClock.uptimeMillis()); deleteTargets.add(target);
                        if(workerGate!=null) queueWork();
                        // Real service deletion refreshes candidate/Buffer labels on this same surface.
                        render();
                    }
                }
            });
            root.addView(surface,new LinearLayout.LayoutParams(Math.round(320*activity.getResources().getDisplayMetrics().density),
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            render(); activity.setContentView(root);
        }); idle(); layoutReady();

        int before=count(); int tapStart=before; press();
        SystemClock.sleep(Math.min(50,ViewConfiguration.getLongPressTimeout()/4));
        onMain(() -> check(deletes==tapStart,"short press waits for the native tap click"));
        release(); onMain(() -> check(deletes==tapStart+1,"short tap deletes exactly once"));

        int rapidStart=count(); press();
        onMain(() -> {
            emit(MotionEvent.ACTION_UP,false);
            // Two complete tap streams before the main queue drains must retain both clicks.
            SystemClock.sleep(2); down=SystemClock.uptimeMillis();
            emit(MotionEvent.ACTION_DOWN,false); emit(MotionEvent.ACTION_UP,false);
            check(deletes==rapidStart,"rapid native tap clicks both wait in the main queue");
        }); idle(); onMain(() -> check(deletes==rapidStart+2,"a fresh DOWN preserves the previous completed tap click"));

        before=count(); int heldStart=before; press(); awaitDeletes(before+3);
        int[] beforeHeldUp={0};
        onMain(() -> { beforeHeldUp[0]=deletes; emit(MotionEvent.ACTION_UP,false); }); idle(); int heldEnd=count();
        onMain(() -> {
            check(heldEnd==beforeHeldUp[0],"held UP never queues an extra native click");
            check(deleteTimes.get(heldStart)-down>=ViewConfiguration.getLongPressTimeout()-30,"held delete honors native long-press delay");
            for(int i=heldStart+1;i<heldEnd;i++) check(deleteTimes.get(i)-deleteTimes.get(i-1)>=DeleteRepeatTouch.REPEAT_MILLIS-15,
                    "repeats are paced instead of flooding the main queue");
        });
        assertStopped(heldEnd,"held release stops timers without an extra deletion");
        runBackpressure("ordinary");

        before=count(); press();
        onMain(() -> { emit(MotionEvent.ACTION_MOVE,true); emit(MotionEvent.ACTION_MOVE,false); });
        release(); assertStopped(before,"moving off and back cannot revive a short press");
        before=count(); press(); awaitDeletes(before+2);
        onMain(() -> emit(MotionEvent.ACTION_MOVE,true)); int afterMove=count();
        onMain(() -> emit(MotionEvent.ACTION_MOVE,false)); release();
        assertStopped(afterMove,"moving off cancels held repeat and release");

        before=count(); press(); awaitDeletes(before+2);
        onMain(() -> emit(MotionEvent.ACTION_CANCEL,false)); int canceled=count(); release();
        assertStopped(canceled,"native ACTION_CANCEL stops repeats and suppresses a late UP");

        before=count(); press(); awaitDeletes(before+2);
        onMain(() -> { root.cancelPendingInputEvents(); target++; }); int retired=count();
        onMain(() -> { emit(MotionEvent.ACTION_MOVE,false); emit(MotionEvent.ACTION_UP,false); });
        assertStopped(retired,"target retirement blocks repeats and same-stream release");
        onMain(() -> check(!deleteTargets.contains(target),"old repeat does not acquire a new target"));
        press(); release(); onMain(() -> check(deletes==retired+1 && deleteTargets.get(deletes-1)==target,"fresh target tap is accepted once"));

        before=count(); press(); awaitDeletes(before+2);
        onMain(() -> { theme=KeyboardTheme.ALL[1]; render(); }); int themed=count(); release();
        assertStopped(themed,"theme replacement retires the held native press");
        before=count(); press(); awaitDeletes(before+2);
        onMain(() -> { mode=KeyboardLayout.Mode.NUMERIC; render(); }); int switched=count(); release();
        assertStopped(switched,"layout replacement cannot redirect held release to new delete key"); layoutReady();

        before=count(); press(); awaitDeletes(before+2);
        onMain(() -> surface.setVisibility(View.GONE)); int hidden=count(); release();
        assertStopped(hidden,"hidden keyboard stops held repeats");
        onMain(() -> surface.setVisibility(View.VISIBLE)); idle(); layoutReady();

        before=count(); press(); awaitDeletes(before+2);
        onMain(() -> { enabled=false; render(); }); int disabled=count();
        onMain(() -> { enabled=true; render(); }); release();
        assertStopped(disabled,"disable then enable cannot revive a retired held press");

        before=count(); press(); awaitDeletes(before+2);
        onMain(() -> { surface.getLayoutParams().width-=Math.round(20*activity.getResources().getDisplayMetrics().density); surface.requestLayout(); });
        idle(); layoutReady(); int resized=count(); release();
        assertStopped(resized,"keyboard geometry change retires the held press");

        before=count(); int pendingStart=before; press();
        onMain(() -> {
            emit(MotionEvent.ACTION_UP,false);
            check(deletes==pendingStart,"tap click is genuinely pending in the main queue");
            root.cancelPendingInputEvents();
        }); idle(); onMain(() -> check(deletes==pendingStart,"retirement removes a pending native tap click"));
        onMain(() -> {
            check(delete.performClick(),"direct accessibility-style click remains supported");
            check(delete.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,null),"native accessibility click is accepted");
            delete.performAccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK,null);
        }); idle();
        assertStopped(pendingStart+2,"accessibility actions do not start an unowned repeat timer");

        for(KeyboardLayout.Mode next:KeyboardLayout.Mode.values()) {
            onMain(() -> { mode=next; render(); }); idle(); layoutReady();
            before=count(); press(); release(); int expected=before+1;
            onMain(() -> check(deletes==expected,"single delete tap works in "+next));
        }
        before=count(); press(); awaitDeletes(before+2);
        onMain(() -> activity.setContentView(new LinearLayout(activity))); int detached=count();
        assertStopped(detached,"detaching the keyboard removes the last repeat timer");
        runChord();
    }
    private void runChord() {
        onMain(() -> {
            root=new KeyboardRoot(activity); root.setOrientation(LinearLayout.VERTICAL);
            chordSurface=new ChordSurface(activity,new ChordSurface.Handler() {
                @Override public void onChord(String code) { throw new AssertionError("Utility press unexpectedly produced a chord"); }
                @Override public void onKey(String text) { throw new AssertionError("Utility press unexpectedly produced a letter"); }
                @Override public void onPreview(ChordGesture.Preview preview) {}
                @Override public void onControl(ChordLayout.Action action) {
                    if(action!=ChordLayout.Action.DELETE) throw new AssertionError("Unexpected utility action");
                    deletes++; deleteTimes.add(SystemClock.uptimeMillis()); deleteTargets.add(target);
                    if(workerGate!=null) queueWork();
                    render();
                }
                @Override public String label(ChordLayout.Action action) { return action==ChordLayout.Action.DELETE?"Delete":"Emoji"; }
                @Override public String description(ChordLayout.Action action) { return label(action); }
                @Override public boolean canRepeatDelete() { return DeleteRepeatContract.this.canRepeatDelete(); }
            });
            DeleteRepeatTouch repeat=new DeleteRepeatTouch(chordSurface.utilityButton(ChordLayout.Action.DELETE),chordSurface::canRepeatDelete);
            chordSurface.setUtilityRetirementListener(repeat::cancel);
            float density=activity.getResources().getDisplayMetrics().density;
            root.addView(chordSurface,new LinearLayout.LayoutParams(Math.round(320*density),Math.round(70*density)));
            render(); activity.setContentView(root);
        }); idle(); layoutReady();
        onMain(() -> {
            check(delete.getBottom()<=chordSurface.getHeight(),"constrained chord delete cap remains inside the input window");
            try { chordSurface.utilityButton(ChordLayout.Action.TEXT); throw new AssertionError("Letter exposed as utility"); }
            catch(IllegalArgumentException expected) { checks++; }
        });
        int before=count(); press(); release(); int tap=before+1;
        onMain(() -> check(deletes==tap,"constrained chord utility native tap deletes once"));
        before=count(); press(); awaitDeletes(before+3);
        int[] beforeUp={0}; onMain(() -> {beforeUp[0]=deletes;emit(MotionEvent.ACTION_UP,false);}); idle();
        assertStopped(beforeUp[0],"chord held utility UP never adds a click");
        runBackpressure("chord");

        before=count(); press(); awaitDeletes(before+2);
        onMain(() -> {root.cancelPendingInputEvents();target++;}); int retired=count(); release();
        assertStopped(retired,"whole-stream target retirement cancels chord utility repeats");
        before=count(); press(); awaitDeletes(before+2);
        onMain(() -> {theme=KeyboardTheme.ALL[0];render();}); int themed=count(); release();
        assertStopped(themed,"chord theme change cancels host-installed utility timer");
        before=count(); press(); awaitDeletes(before+2);
        onMain(() -> {split=true;render();}); int changed=count(); release();
        assertStopped(changed,"split-layout change cancels chord utility timer"); idle(); layoutReady();
        before=count(); press(); awaitDeletes(before+2);
        onMain(() -> {resolves=false;render();}); int english=count(); release();
        assertStopped(english,"chord language-mode change cancels utility timer");
        before=count(); press(); awaitDeletes(before+2);
        onMain(() -> chordSurface.setVisibility(View.GONE)); int hidden=count(); release();
        assertStopped(hidden,"hidden chord surface cancels its utility timer");
        onMain(() -> chordSurface.setVisibility(View.VISIBLE)); idle(); layoutReady();
        before=count(); press(); awaitDeletes(before+2);
        onMain(() -> chordSurface.setUtilityRetirementListener(null)); int replaced=count(); release();
        assertStopped(replaced,"removing a host utility attachment retires its timer");
        onMain(() -> {
            DeleteRepeatTouch repeat=new DeleteRepeatTouch(chordSurface.utilityButton(ChordLayout.Action.DELETE),chordSurface::canRepeatDelete);
            chordSurface.setUtilityRetirementListener(repeat::cancel);
        });
        before=count(); press(); awaitDeletes(before+2);
        onMain(() -> activity.setContentView(new LinearLayout(activity))); int detached=count();
        assertStopped(detached,"detached chord surface cancels host utility repeats");
    }
}

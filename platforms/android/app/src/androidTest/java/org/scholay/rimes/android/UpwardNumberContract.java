package org.scholay.rimes.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.List;
import org.scholay.rimes.core.KeyboardLayout;

/** Attached native Button taps, hold/up alternates and whole-stream cancellation. */
final class UpwardNumberContract {
    private final Instrumentation instrumentation;
    private final Activity activity;
    private KeyboardRoot root;
    private KeyboardSurface surface;
    private KeyButton key;
    private KeyboardLayout.Mode mode=KeyboardLayout.Mode.QWERTY;
    private KeyboardTheme theme=KeyboardTheme.ALL[0];
    private final List<String> letters=new ArrayList<>(),numbers=new ArrayList<>();
    private int checks,target;
    private final List<Integer> numberTargets=new ArrayList<>();
    private float density,startX,startY;
    private long down;
    private UpwardNumberContract(Instrumentation instrumentation,Activity activity) { this.instrumentation=instrumentation; this.activity=activity; }
    static int run(Instrumentation instrumentation) {
        if(Looper.myLooper()==Looper.getMainLooper()) throw new IllegalStateException("UpwardNumberContract must run off main");
        Instrumentation.ActivityMonitor monitor=instrumentation.addMonitor(SetupActivity.class.getName(),null,false);
        Activity activity=null;
        String component=instrumentation.getTargetContext().getPackageName()+"/"+SetupActivity.class.getName();
        try(android.os.ParcelFileDescriptor launch=instrumentation.getUiAutomation().executeShellCommand("am start -W -f 0x10008000 -n "+component)) {
            activity=instrumentation.waitForMonitorWithTimeout(monitor,15000);
            if(activity==null) throw new AssertionError("Upward number fixture did not start within 15 seconds");
            UpwardNumberContract test=new UpwardNumberContract(instrumentation,activity); test.run(); return test.checks;
        } catch(java.io.IOException error) { throw new AssertionError("Upward number fixture shell launch failed",error); }
        finally {
            instrumentation.removeMonitor(monitor);
            if(activity!=null) { Activity fixture=activity; instrumentation.runOnMainSync(fixture::finish); instrumentation.waitForIdleSync(); }
        }
    }
    private void onMain(Runnable action) {
        java.util.concurrent.atomic.AtomicReference<Throwable> error=new java.util.concurrent.atomic.AtomicReference<>();
        instrumentation.runOnMainSync(() -> { try { action.run(); } catch(Throwable failure) { error.set(failure); } });
        if(error.get()!=null) throw new AssertionError("Upward number main-thread phase failed",error.get());
    }
    private void idle() { instrumentation.waitForIdleSync(); }
    private void check(boolean condition,String label) { checks++; if(!condition) throw new AssertionError("Upward number: "+label); }
    private void render() { surface.render(mode,theme); }
    private void findKey(String text) {
        onMain(() -> {
            key=null;
            for(int i=0;i<surface.getChildCount();i++) {
                KeyButton button=(KeyButton)surface.getChildAt(i);
                if(text.contentEquals(button.getContentDescription())) { key=button; break; }
            }
            check(key!=null,"native key exists: "+text);
        });
        boolean[] ready={false};
        for(int i=0;i<80 && !ready[0];i++) {
            onMain(() -> ready[0]=key.isAttachedToWindow() && key.getWidth()>0 && key.getHeight()>0);
            if(!ready[0]) SystemClock.sleep(25);
        }
        onMain(() -> check(ready[0],"native key has real window geometry"));
    }
    private void press() {
        SystemClock.sleep(2);
        onMain(() -> {
            down=SystemClock.uptimeMillis(); startX=surface.getLeft()+key.getLeft()+key.getWidth()/2f;
            startY=surface.getTop()+key.getTop()+key.getHeight()/2f;
            emit(MotionEvent.ACTION_DOWN,0,0);
        });
    }
    private void emit(int action,float dx,float dy) {
        MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,startX+dx*density,startY+dy*density,0);
        try { root.dispatchTouchEvent(event); } finally { event.recycle(); }
    }
    private void up(float dx,float dy) { onMain(() -> emit(MotionEvent.ACTION_UP,dx,dy)); idle(); }
    private void hold() { SystemClock.sleep(UpwardNumberTouch.HOLD_MILLIS+70); idle(); }
    private int[] counts() { int[] result={0,0}; onMain(() -> {result[0]=letters.size();result[1]=numbers.size();}); return result; }
    private void unchanged(int[] before,String label) {
        SystemClock.sleep(UpwardNumberTouch.HOLD_MILLIS+30); idle();
        onMain(() -> check(letters.size()==before[0] && numbers.size()==before[1],label));
    }
    private int numberAccessibilityAction() {
        AccessibilityNodeInfo info=key.createAccessibilityNodeInfo();
        try {
            for(AccessibilityNodeInfo.AccessibilityAction item:info.getActionList())
                if(item.getLabel()!=null && "输入数字 1".contentEquals(item.getLabel())) return item.getId();
            return 0;
        } finally { info.recycle(); }
    }
    private void run() {
        onMain(() -> {
            activity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            density=activity.getResources().getDisplayMetrics().density;
            root=new KeyboardRoot(activity); root.setOrientation(LinearLayout.VERTICAL);
            View margin=new View(activity); root.addView(margin,new LinearLayout.LayoutParams(1,Math.round(60*density)));
            surface=new KeyboardSurface(activity,new KeyboardSurface.Handler() {
                @Override public String label(KeyboardLayout.Key key) { return key.text; }
                @Override public String description(KeyboardLayout.Key key) { return key.text; }
                @Override public boolean enabled(KeyboardLayout.Key key) { return true; }
                @Override public boolean selected(KeyboardLayout.Key key) { return false; }
                @Override public void press(KeyboardLayout.Key key) { if(key.action==KeyboardLayout.Action.TEXT) letters.add(key.text); }
                @Override public void onAlternate(String text) { numbers.add(text); numberTargets.add(target); render(); }
            });
            root.addView(surface,new LinearLayout.LayoutParams(Math.round(320*density),LinearLayout.LayoutParams.WRAP_CONTENT));
            render(); activity.setContentView(root);
        }); idle(); findKey("q");
        press(); up(0,0); onMain(() -> check(letters.equals(List.of("q")) && numbers.isEmpty(),"native tap remains one letter"));
        press(); hold(); up(0,0); onMain(() -> check(letters.size()==2 && numbers.isEmpty(),"holding without sliding still types one letter"));
        int[] rapid=counts(); press();
        onMain(() -> {
            emit(MotionEvent.ACTION_UP,0,0);
            check(key.performAccessibilityAction(numberAccessibilityAction(),null),"AX alternate accepted after a completed pending tap");
        }); idle();
        onMain(() -> check(letters.size()==rapid[0]+1 && numbers.size()==rapid[1]+1,"independent AX alternate preserves the prior native tap click"));
        int[] held=counts();
        press(); hold();
        onMain(() -> { emit(MotionEvent.ACTION_MOVE,0,-36); check(key.isPressed(),"armed upward slide preserves cap feedback outside the native cell"); });
        up(0,-36); onMain(() -> check(numbers.size()==held[1]+1 && numbers.get(numbers.size()-1).equals("1") && letters.size()==held[0],"hold/up inserts one literal number instead of its letter"));
        int[] once=counts(); onMain(() -> emit(MotionEvent.ACTION_UP,0,-36)); unchanged(once,"duplicate release cannot repeat an alternate");

        int[] quick=counts(); press();
        onMain(() -> emit(MotionEvent.ACTION_MOVE,0,-36)); hold(); up(0,-36);
        unchanged(quick,"a quick slide cannot become an alternate after waiting outside the key");
        press(); hold(); onMain(() -> {emit(MotionEvent.ACTION_MOVE,0,-36);emit(MotionEvent.ACTION_MOVE,0,0);}); up(0,0);
        onMain(() -> check(numbers.size()==quick[1] && letters.size()==quick[0]+1,"returning to the cap cancels alternate selection"));
        int[] sideways=counts(); press(); hold(); onMain(() -> emit(MotionEvent.ACTION_MOVE,50,-36)); up(50,-36);
        unchanged(sideways,"sideways release outside the cell cannot type either a number or a letter");

        int[] retired=counts(); press(); hold(); onMain(() -> {emit(MotionEvent.ACTION_MOVE,0,-36);root.cancelPendingInputEvents();target++;});
        onMain(() -> emit(MotionEvent.ACTION_MOVE,0,-36)); up(0,-36);
        unchanged(retired,"target retirement removes an armed alternate and its old release");
        onMain(() -> check(!numberTargets.contains(target),"old alternate does not adopt the new target"));
        int[] pendingHold=counts(); press(); onMain(() -> root.cancelPendingInputEvents()); hold(); up(0,-36);
        unchanged(pendingHold,"retirement before the arm deadline removes the pending hold callback");
        press(); hold(); onMain(() -> emit(MotionEvent.ACTION_MOVE,0,-24)); up(0,-24);
        onMain(() -> check(numbers.size()==retired[1]+1 && numberTargets.get(numbers.size()-1)==target,"a fresh target gesture works once"));

        int[] themed=counts(); press(); hold(); onMain(() -> {emit(MotionEvent.ACTION_MOVE,0,-36);theme=KeyboardTheme.ALL[1];render();}); up(0,-36);
        unchanged(themed,"theme replacement retires armed selection");
        int[] canceled=counts(); press(); hold(); onMain(() -> {emit(MotionEvent.ACTION_MOVE,0,-36);emit(MotionEvent.ACTION_CANCEL,0,-36);}); up(0,-36);
        unchanged(canceled,"ACTION_CANCEL makes an old alternate inert");
        int[] hidden=counts(); press(); hold(); onMain(() -> {emit(MotionEvent.ACTION_MOVE,0,-36);surface.setVisibility(View.GONE);}); up(0,-36);
        unchanged(hidden,"hidden keyboard cannot commit its old alternate");
        onMain(() -> surface.setVisibility(View.VISIBLE)); idle(); findKey("q");

        int[] switched=counts(); press(); hold(); onMain(() -> {emit(MotionEvent.ACTION_MOVE,0,-36);mode=KeyboardLayout.Mode.NUMERIC;render();}); up(0,-36);
        unchanged(switched,"new layout cannot receive the old alternate release");
        onMain(() -> {mode=KeyboardLayout.Mode.QWERTY;render();}); idle(); findKey("q");
        int[] accessibility=counts();
        onMain(() -> {
            check(key.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,null),"native accessibility click stays supported");
            int action=numberAccessibilityAction();
            check(action!=0 && key.performAccessibilityAction(action,null),"number has a named accessibility action");
        }); idle(); onMain(() -> check(letters.size()==accessibility[0]+1 && numbers.size()==accessibility[1]+1,"accessibility inserts exactly one selected action"));
        for(int i=0;i<10;i++) {
            String letter="qwertyuiop".substring(i,i+1),number="1234567890".substring(i,i+1); findKey(letter);
            int[] before=counts(); press(); hold(); onMain(() -> emit(MotionEvent.ACTION_MOVE,0,-24)); up(0,-24);
            onMain(() -> check(letters.size()==before[0] && numbers.size()==before[1]+1 && numbers.get(numbers.size()-1).equals(number),"top-row mapping "+letter+" → "+number));
        }
        int[] detached=counts(); press(); hold(); onMain(() -> {emit(MotionEvent.ACTION_MOVE,0,-24);activity.setContentView(new LinearLayout(activity));});
        unchanged(detached,"detaching an armed keyboard cannot leave an insertion callback");
    }
}

package org.scholay.rimes.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.InputBinding;
import android.inputmethodservice.InputMethodService;
import android.widget.FrameLayout;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32;
import org.json.JSONArray;
import org.json.JSONObject;
import org.scholay.rimes.core.BufferSession;
import org.scholay.rimes.core.PluginSession;
import org.scholay.rimes.core.RimeEngine;

/**
 * Attached production view, real main-Looper touch/repeat and serial service callbacks.
 * Engine and InputConnection are explicitly synthetic; no JNI, real editor IPC or network.
 * OnPreDraw samples correspond to requested redraws. ROI hashes are software Canvas
 * renders of the attached measured tree, not screenshots of the display compositor.
 */
final class PluginFrameContract {
    private static final String[] IDS={"translate","ask","polish","poem","art"};
    private final Instrumentation instrumentation;
    private final Activity activity;
    private final AtomicReference<Throwable> frameFailure=new AtomicReference<>();
    private final List<Phase> phases=new ArrayList<>();
    private final Choreographer.FrameCallback ticker=this::tick;
    private final ViewTreeObserver.OnPreDrawListener observer=this::observeFrame;
    private Fixture fixture;
    private Phase phase;
    private boolean sampling;
    private long frameTime,down;
    private int checks;

    private PluginFrameContract(Instrumentation instrumentation,Activity activity) {
        this.instrumentation=instrumentation; this.activity=activity;
    }
    static String run(Instrumentation instrumentation) throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper()) throw new IllegalStateException("Plugin frames must run off main");
        Instrumentation.ActivityMonitor monitor=instrumentation.addMonitor(SetupActivity.class.getName(),null,false);
        Activity activity=null;
        try {
            String component=instrumentation.getTargetContext().getPackageName()+"/"+SetupActivity.class.getName();
            // Consume shell completion; closing its descriptor early can cancel am start on some devices.
            try(android.os.ParcelFileDescriptor fd=instrumentation.getUiAutomation().executeShellCommand(
                    "am start -W -f 0x10008000 -n "+component);
                    android.os.ParcelFileDescriptor.AutoCloseInputStream input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)) {
                byte[] bytes=new byte[1024]; while(input.read(bytes)!=-1) { /* No private shell output is logged. */ }
            }
            activity=instrumentation.waitForMonitorWithTimeout(monitor,15000);
            if(activity==null || activity.isFinishing()) throw new AssertionError("Plugin frame activity did not launch within 15 seconds");
            PluginFrameContract test=new PluginFrameContract(instrumentation,activity);
            try { test.run(); return test.report(); }
            finally { test.close(); }
        } finally {
            instrumentation.removeMonitor(monitor);
            if(activity!=null) { Activity current=activity; instrumentation.runOnMainSync(current::finish); }
        }
    }
    private interface MainAction { void run() throws Exception; }
    private void onMain(MainAction action) throws Exception {
        AtomicReference<Throwable> error=new AtomicReference<>();
        instrumentation.runOnMainSync(() -> { try { action.run(); } catch(Throwable failure) { error.set(failure); } });
        if(error.get()!=null) throw new AssertionError("Plugin frame main phase",error.get());
        if(frameFailure.get()!=null) throw new AssertionError("Plugin frame observation",frameFailure.get());
    }
    private void check(boolean condition,String label) {
        checks++; if(!condition) throw new AssertionError("Plugin frames: "+label);
    }
    private void await(BooleanSupplier condition,String label) throws Exception {
        long until=SystemClock.uptimeMillis()+4000; boolean[] done={false};
        do { onMain(() -> done[0]=condition.getAsBoolean()); if(done[0]) return; SystemClock.sleep(20); }
        while(SystemClock.uptimeMillis()<until);
        throw new AssertionError("Plugin frames timed out: "+label);
    }
    private void settleLayout() throws Exception {
        await(() -> fixture.root.isAttachedToWindow() && fixture.root.getWidth()>0 && !fixture.root.isLayoutRequested(),"attached window layout");
        SystemClock.sleep(80);
    }
    private void begin(String name,boolean visible,boolean enabled,String selected) throws Exception {
        settleLayout();
        onMain(() -> { phase=new Phase(name,visible,enabled,selected); phases.add(phase); });
    }
    private void finishPhase() throws Exception {
        onMain(() -> {
            check(phase!=null && phase.frames.length()>=4,"at least four distinct drawn animation frames: "+(phase==null?"none":phase.name));
            phase=null;
        });
    }
    private void tick(long time) {
        if(!sampling) return;
        frameTime=time;
        fixture.root.invalidate();
        Choreographer.getInstance().postFrameCallback(ticker);
    }
    private boolean observeFrame() {
        if(!sampling || phase==null || frameTime==0 || phase.lastTime==frameTime || frameFailure.get()!=null) return true;
        try { phase.observe(frameTime); }
        catch(Throwable error) { frameFailure.compareAndSet(null,error); }
        return true;
    }
    private void press() throws Exception {
        SystemClock.sleep(2);
        onMain(() -> { down=SystemClock.uptimeMillis(); emit(MotionEvent.ACTION_DOWN); });
    }
    private void release() throws Exception { onMain(() -> emit(MotionEvent.ACTION_UP)); }
    private void emit(int action) {
        View key=find(fixture.root,"Delete"); check(key!=null && key.isShown(),"real production Delete is visible");
        int[] keyPoint=new int[2],rootPoint=new int[2]; key.getLocationOnScreen(keyPoint); fixture.root.getLocationOnScreen(rootPoint);
        MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,
                keyPoint[0]-rootPoint[0]+key.getWidth()/2f,keyPoint[1]-rootPoint[1]+key.getHeight()/2f,0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { check(fixture.root.dispatchTouchEvent(event),"attached production touch "+action); }
        finally { event.recycle(); }
    }
    private static View find(View view,String description) {
        if(description.contentEquals(view.getContentDescription()==null?"":view.getContentDescription())) return view;
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            View found=find(((ViewGroup)view).getChildAt(i),description); if(found!=null) return found;
        }
        return null;
    }
    private void deletePhases(String prefix,boolean buffer) throws Exception {
        int[] before={0};
        onMain(() -> before[0]=buffer?fixture.buffer.blockCount():fixture.connection.deletes);
        begin(prefix+"-single",true,true,null); SystemClock.sleep(80); press(); SystemClock.sleep(50); release(); SystemClock.sleep(150); finishPhase();
        onMain(() -> check(buffer?fixture.buffer.blockCount()==before[0]-1:fixture.connection.deletes==before[0]+1,"single Delete once: "+prefix));
        onMain(() -> before[0]=buffer?fixture.buffer.blockCount():fixture.connection.deletes);
        begin(prefix+"-continuous",true,true,null);
        for(int i=0;i<6;i++) { press(); SystemClock.sleep(30); release(); SystemClock.sleep(45); }
        SystemClock.sleep(100); finishPhase();
        onMain(() -> check(buffer?fixture.buffer.blockCount()==before[0]-6:fixture.connection.deletes==before[0]+6,"six distinct native Delete clicks: "+prefix));
        onMain(() -> before[0]=buffer?fixture.buffer.blockCount():fixture.connection.deletes);
        begin(prefix+"-held",true,true,null); press();
        SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout()+DeleteRepeatTouch.REPEAT_MILLIS*5+100);
        release(); int[] stopped={0}; onMain(() -> stopped[0]=buffer?fixture.buffer.blockCount():fixture.connection.deletes);
        SystemClock.sleep(DeleteRepeatTouch.REPEAT_MILLIS*3); finishPhase();
        onMain(() -> {
            check(buffer?stopped[0]<=before[0]-3:stopped[0]>=before[0]+3,"held native Delete repeats: "+prefix);
            check((buffer?fixture.buffer.blockCount():fixture.connection.deletes)==stopped[0],"repeat stopped after UP: "+prefix);
        });
    }
    private void run() throws Exception {
        onMain(() -> {
            activity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            fixture=new Fixture(activity);
            FrameLayout host=new FrameLayout(activity);
            host.addView(fixture.root,new FrameLayout.LayoutParams(-1,-2)); activity.setContentView(host);
            fixture.root.getViewTreeObserver().addOnPreDrawListener(observer);
            sampling=true; Choreographer.getInstance().postFrameCallback(ticker);
        });
        settleLayout(); deletePhases("ordinary",false);
        onMain(() -> {
            check(fixture.engine.keys==0 && fixture.engine.snapshots==0,"idle host deletion never queues engine work");
            fixture.buffer.setEnabled(true);
            for(int i=0;i<24;i++) fixture.buffer.appendCommittedBlock("合成块"+i);
            fixture.render();
        });
        int[] hostDeletes={0}; onMain(() -> hostDeletes[0]=fixture.connection.deletes);
        deletePhases("buffer",true);
        onMain(() -> check(fixture.connection.deletes==hostDeletes[0] && fixture.connection.attempts==0,"Buffer deletion never falls through to host"));

        onMain(() -> { fixture.buffer.clear(); fixture.buffer.setEnabled(false); fixture.render(); });
        Block pending=new Block(false);
        onMain(() -> { fixture.engine.next=pending; invoke(fixture.service,"type",new Class<?>[]{String.class},"n"); });
        check(pending.entered.await(3,TimeUnit.SECONDS),"real worker reaches controlled key callback");
        begin("pending-idle-disabled",true,false,null); SystemClock.sleep(200); finishPhase();
        onMain(() -> {
            check((Integer)get(fixture.service,"pending")==1,"pending operation still holds service gate");
            View button=((ViewGroup)fixture.bar.getChildAt(0)).getChildAt(0); button.performClick();
            check(get(fixture.service,"activePlugin")==null && !fixture.buffer.isEnabled(),"disabled shortcut cannot activate during pending");
        });
        pending.release.countDown();
        await(() -> fixture.composing(),"posted composing callback");
        begin("legitimate-composition-hidden",false,false,null); SystemClock.sleep(180); finishPhase();
        onMain(() -> invoke(fixture.service,"delete",new Class<?>[0]));
        await(() -> fixture.pending()==0 && !fixture.composing(),"composition Delete returns to idle");
        begin("composition-cleared-restored",true,true,null); SystemClock.sleep(180); finishPhase();
        check(phases.get(0).baseline.getLong("crc32")==phases.get(phases.size()-1).baseline.getLong("crc32"),
                "cleared composition restores the original idle shortcut raster");

        Block switching=new Block(true);
        onMain(() -> { fixture.engine.next=switching; invoke(fixture.service,"toggleLanguage",new Class<?>[0]); });
        check(switching.entered.await(3,TimeUnit.SECONDS),"real settlement worker blocks during language change");
        begin("legal-language-switch-disabled",true,false,null); SystemClock.sleep(200); finishPhase(); switching.release.countDown();
        await(() -> fixture.pending()==0,"language settlement callback");
        begin("legal-language-switch-restored",true,true,null); SystemClock.sleep(180); finishPhase();

        PluginSession.Request[] request={null};
        onMain(() -> {
            invoke(fixture.service,"selectPlugin",new Class<?>[]{String.class},"translate"); fixture.buffer.appendCommittedBlock("合成原文");
            set(fixture.service,"pluginResultAuthorization",fixture.official.grant("translate"));
            request[0]=fixture.plugins.start(fixture.buffer); check(request[0]!=null,"synthetic captured-source request starts"); fixture.render();
        });
        begin("authorized-running",true,true,"translate"); SystemClock.sleep(150);
        onMain(() -> {
            check(!fixture.send().isEnabled() && "取消执行".contentEquals(fixture.run().getContentDescription()),"RUNNING disables Send and exposes Cancel");
            check(fixture.plugins.update(request[0],fixture.buffer,"合成部分",false),"partial request accepted"); fixture.render();
        });
        SystemClock.sleep(150); finishPhase();
        onMain(() -> { check(fixture.plugins.update(request[0],fixture.buffer,"合成结果",true),"complete request accepted"); fixture.render(); });
        begin("authorized-ready-rejected-send",true,true,"translate"); SystemClock.sleep(120);
        onMain(() -> {
            check(fixture.send().isEnabled(),"READY enables Send"); fixture.connection.accept=false;
            invoke(fixture.service,"insertPluginResult",new Class<?>[0]);
            check(fixture.connection.attempts==1 && "合成原文".equals(fixture.buffer.text())
                    && fixture.plugins.snapshot(fixture.buffer).status==PluginSession.Status.READY,"rejected Send preserves source and ready output");
        });
        SystemClock.sleep(150); finishPhase();
        onMain(() -> { fixture.connection.accept=true; invoke(fixture.service,"insertPluginResult",new Class<?>[0]); });
        begin("accepted-send-keeps-shortcuts",true,true,"translate"); SystemClock.sleep(160); finishPhase();
        onMain(() -> check(fixture.buffer.text().isEmpty() && fixture.connection.accepted==1,"accepted output consumes source once"));

        onMain(() -> {
            fixture.buffer.appendCommittedBlock("保留原文"); request[0]=fixture.plugins.start(fixture.buffer);
            check(request[0]!=null && fixture.plugins.update(request[0],fixture.buffer,"不应发送",true),"prepare ready authorization fixture"); fixture.render();
            fixture.official.setEnabled(fixture.official.entry("translate"),false);
            invoke(fixture.service,"insertPluginResult",new Class<?>[0]);
            check(fixture.connection.accepted==1,"revoked grant denies Send immediately before queued listener");
        });
        await(() -> fixture.plugins.snapshot(fixture.buffer).status==PluginSession.Status.IDLE,"official authorization listener clears output");
        // Disabled translation is legitimate; other installed plugin entries stay enabled.
        begin("revoked-ready-grant",true,true,null); SystemClock.sleep(180); finishPhase();
        onMain(() -> {
            check(!fixture.plugins.update(request[0],fixture.buffer,"迟到结果",true),"revoked request cannot restore output");
            check("保留原文".equals(fixture.buffer.text()),"grant revocation preserves source");
            fixture.official.setEnabled(fixture.official.entry("translate"),true);
        });
        await(() -> fixture.official.enabled("translate") && fixture.pending()==0,"reauthorized plugin gate");
        begin("reauthorized-shortcuts",true,true,null); SystemClock.sleep(180); finishPhase();
        onMain(() -> {
            invoke(fixture.service,"selectPlugin",new Class<?>[]{String.class},"translate");
            set(fixture.service,"pluginResultAuthorization",fixture.official.grant("translate"));
            request[0]=fixture.plugins.start(fixture.buffer);
            check(request[0]!=null && fixture.plugins.update(request[0],fixture.buffer,"运行中部分",false),"prepare running authorization fixture"); fixture.render();
        });
        begin("running-before-revocation",true,true,"translate"); SystemClock.sleep(150); finishPhase();
        onMain(() -> { fixture.official.setEnabled(fixture.official.entry("translate"),false); invoke(fixture.service,"insertPluginResult",new Class<?>[0]); });
        await(() -> fixture.plugins.snapshot(fixture.buffer).status==PluginSession.Status.IDLE,"running grant listener retires partial output");
        begin("revoked-running-grant",true,true,null); SystemClock.sleep(180); finishPhase();
        onMain(() -> {
            check(!fixture.plugins.update(request[0],fixture.buffer,"迟到完成",true),"revoked running request rejects its final callback");
            check("保留原文".equals(fixture.buffer.text()) && fixture.connection.accepted==1,"running revocation preserves source and never sends partial output");
        });
    }
    private final class Phase {
        final String name,selected;
        final boolean visible,gate;
        final boolean[] expectedEnabled=new boolean[IDS.length];
        final JSONArray frames=new JSONArray();
        JSONObject baseline;
        long lastTime;
        Bitmap bitmap;
        int[] pixels;
        Phase(String name,boolean visible,boolean gate,String selected) {
            this.name=name; this.visible=visible; this.gate=gate; this.selected=selected;
            for(int i=0;i<IDS.length;i++) expectedEnabled[i]=gate && fixture.official.enabled(IDS[i]);
        }
        void observe(long time) throws Exception {
            lastTime=time; check(frames.length()<360,"bounded frame collection");
            check(fixture.root.isAttachedToWindow(),"production tree stays attached: "+name);
            check(fixture.bar.isShown()==visible && fixture.bar.getVisibility()==(visible?View.VISIBLE:View.GONE),"shortcut effective visibility: "+name);
            check(fixture.bar.getAlpha()==1f,"shortcut viewport alpha stays opaque: "+name);
            ViewGroup row=(ViewGroup)fixture.bar.getChildAt(0); check(row.getChildCount()==IDS.length,"all five native shortcut nodes retained");
            JSONArray cells=new JSONArray();
            for(int i=0;i<row.getChildCount();i++) {
                View button=row.getChildAt(i); Rect full=screenRect(button),clip=new Rect(); boolean intersects=button.getGlobalVisibleRect(clip);
                check(button.getAlpha()==1f && button.getVisibility()==View.VISIBLE,"native shortcut alpha/local visibility: "+i+" "+name);
                if(visible) {
                    check(button.isEnabled()==expectedEnabled[i],"native shortcut authorized enabled state: "+i+" "+name);
                    check(button.isSelected()==IDS[i].equals(selected),"native shortcut selection: "+i+" "+name);
                }
                cells.put(new JSONObject().put("id",IDS[i]).put("enabled",button.isEnabled()).put("selected",button.isSelected())
                        .put("shown",button.isShown()).put("alpha",button.getAlpha()).put("bounds",rect(full)).put("clip",intersects?rect(clip):JSONObject.NULL));
            }
            JSONObject sample=new JSONObject().put("frame_time_nanos",time).put("native_cells",cells);
            if(visible) {
                Rect roi=new Rect(); check(fixture.bar.getGlobalVisibleRect(roi) && roi.width()>0 && roi.height()>0,"visible shortcut viewport has actual bounds");
                sample.put("viewport",rect(roi)).put("software_roi_crc32",raster(roi));
                JSONObject comparable=new JSONObject().put("cells",cells).put("viewport",rect(roi)).put("crc32",sample.getLong("software_roi_crc32"));
                if(baseline==null) baseline=comparable;
                else check(baseline.toString().equals(comparable.toString()),"per-frame geometry, clipping, properties and software pixels remain stable: "+name);
            }
            frames.put(sample);
        }
        long raster(Rect roi) {
            if(bitmap==null) { bitmap=Bitmap.createBitmap(roi.width(),roi.height(),Bitmap.Config.ARGB_8888); pixels=new int[roi.width()*roi.height()]; }
            check(bitmap.getWidth()==roi.width() && bitmap.getHeight()==roi.height(),"ROI size stable: "+name);
            bitmap.eraseColor(0); Canvas canvas=new Canvas(bitmap); int[] origin=new int[2]; fixture.root.getLocationOnScreen(origin);
            canvas.translate(origin[0]-roi.left,origin[1]-roi.top); fixture.root.draw(canvas);
            bitmap.getPixels(pixels,0,roi.width(),0,0,roi.width(),roi.height()); CRC32 crc=new CRC32();
            for(int pixel:pixels) { crc.update(pixel>>>24); crc.update(pixel>>>16); crc.update(pixel>>>8); crc.update(pixel); }
            return crc.getValue();
        }
        JSONObject json() throws Exception { return new JSONObject().put("phase",name).put("expected_visible",visible).put("policy_gate",gate).put("frame_count",frames.length()).put("frames",frames); }
        void close() { if(bitmap!=null) { bitmap.recycle(); bitmap=null; } }
    }
    private String report() throws Exception {
        JSONArray results=new JSONArray(); int frames=0;
        for(Phase item:phases) { results.put(item.json()); frames+=item.frames.length(); }
        JSONObject report=new JSONObject().put("format_version",1).put("checks",checks).put("observed_drawn_frames",frames)
                .put("scope","Attached production service view; synthetic engine/InputConnection and isolated official-plugin state. Choreographer/OnPreDraw requested redraws; local software Canvas ROI hashes, not display compositor or real host IPC.")
                .put("learning_or_network_used",false).put("phases",results);
        try(FileOutputStream output=instrumentation.getTargetContext().openFileOutput("plugin-frames.json",Context.MODE_PRIVATE)) {
            output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        return "PASS attached plugin frame contract checks="+checks+" frames="+frames+" phases="+phases.size()
                +"\nREPORT files/plugin-frames.json; scope=attached-synthetic/local-software-raster, not real IME IPC/compositor\n";
    }
    private void close() throws Exception {
        onMain(() -> {
            sampling=false; phase=null; Choreographer.getInstance().removeFrameCallback(ticker);
            if(fixture!=null) {
                if(fixture.root.getViewTreeObserver().isAlive()) fixture.root.getViewTreeObserver().removeOnPreDrawListener(observer);
                for(Phase item:phases) item.close(); fixture.close();
            }
        });
    }
    private static Rect screenRect(View view) { int[] point=new int[2]; view.getLocationOnScreen(point); return new Rect(point[0],point[1],point[0]+view.getWidth(),point[1]+view.getHeight()); }
    private static JSONArray rect(Rect rect) { return new JSONArray().put(rect.left).put(rect.top).put(rect.right).put(rect.bottom); }

    private final class Fixture implements AutoCloseable {
        final RimesInputMethodService service=new RimesInputMethodService();
        final PluginTestContext context;
        final SharedPreferences preferences,officialPreferences;
        final KeyboardSettings settings;
        final OfficialPluginStore official;
        final BufferPluginExecutor executor;
        final Connection connection;
        final Engine engine=new Engine();
        final InputMethodService.InputMethodImpl input;
        final BufferSession buffer;
        final PluginSession plugins;
        final KeyboardRoot root;
        final PluginShortcutBar bar;
        Fixture(Context base) throws Exception {
            context=new PluginTestContext(base,true); preferences=context.getSharedPreferences(KeyboardSettings.PREFERENCES_NAME,Context.MODE_PRIVATE);
            settings=new KeyboardSettings(preferences); settings.setLearningEnabled(false); settings.setLayout("qwerty");
            official=new OfficialPluginStore(context); officialPreferences=context.getSharedPreferences(OfficialPluginStore.PREFERENCES,Context.MODE_PRIVATE);
            executor=new BufferPluginExecutor(context); connection=new Connection(context);
            Method attach=ContextWrapper.class.getDeclaredMethod("attachBaseContext",Context.class); attach.setAccessible(true); attach.invoke(service,context);
            set(service,"officialPlugins",official); set(service,"officialPluginPreferences",officialPreferences);
            set(service,"preferences",preferences); set(service,"settings",settings); set(service,"cometSettings",new OpenAiSettings(preferences)); set(service,"pluginExecutor",executor);
            invoke(service,"restoreSettings",new Class<?>[0]);
            input=(InputMethodService.InputMethodImpl)service.onCreateInputMethodInterface(); input.bindInput(new InputBinding(connection,new Binder(),Process.myUid(),Process.myPid()));
            check(service.getCurrentInputConnection()==connection,"public bind installs synthetic target");
            set(service,"target",connection); set(service,"selection",connection.text.length()); set(service,"selectionStart",connection.text.length());
            set(service,"engine",engine); set(service,"session",1L); set(service,"ready",true);
            buffer=(BufferSession)get(service,"buffer"); plugins=(PluginSession)get(service,"pluginSession"); buffer.beginTarget(true);
            preferences.registerOnSharedPreferenceChangeListener((SharedPreferences.OnSharedPreferenceChangeListener)get(service,"preferenceListener"));
            officialPreferences.registerOnSharedPreferenceChangeListener((SharedPreferences.OnSharedPreferenceChangeListener)get(service,"officialPluginListener"));
            root=(KeyboardRoot)service.onCreateInputView(); bar=(PluginShortcutBar)get(service,"pluginShortcuts");
            for(String id:IDS) check(official.enabled(id),"isolated legacy fixture verifies packaged plugin: "+id);
        }
        void render() throws Exception { invoke(service,"render",new Class<?>[0]); }
        boolean composing() { try { return ((RimeEngine.Snapshot)get(service,"snapshot")).composing(); } catch(Exception error) { throw new AssertionError(error); } }
        int pending() { try { return (Integer)get(service,"pending"); } catch(Exception error) { throw new AssertionError(error); } }
        View send() throws Exception { return (View)get(service,"insertNext"); }
        View run() throws Exception { return (View)get(service,"pluginRunButton"); }
        public void close() throws Exception {
            engine.unblock(); set(service,"ready",false); executor.close();
            preferences.unregisterOnSharedPreferenceChangeListener((SharedPreferences.OnSharedPreferenceChangeListener)get(service,"preferenceListener"));
            officialPreferences.unregisterOnSharedPreferenceChangeListener((SharedPreferences.OnSharedPreferenceChangeListener)get(service,"officialPluginListener"));
            ((Handler)get(service,"main")).removeCallbacksAndMessages(null); input.unbindInput(); context.close();
        }
    }
    private static final class Connection extends BaseInputConnection {
        String text="abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz";
        boolean accept;
        int deletes,attempts,accepted;
        Connection(Context context) { super(new View(context),true); }
        public CharSequence getSelectedText(int flags) { return null; }
        public CharSequence getTextBeforeCursor(int length,int flags) { return text.substring(Math.max(0,text.length()-length)); }
        public boolean deleteSurroundingTextInCodePoints(int before,int after) {
            if(Looper.myLooper()!=Looper.getMainLooper()) throw new AssertionError("host deletion left main");
            deletes++; if(!text.isEmpty()) text=text.substring(0,text.offsetByCodePoints(text.length(),-Math.min(before,text.codePointCount(0,text.length())))); return true;
        }
        public boolean setComposingText(CharSequence text,int cursor) { return true; }
        public boolean finishComposingText() { return true; }
        public boolean commitText(CharSequence value,int cursor) { attempts++; if(accept) { accepted++; text+=value; } return accept; }
    }
    private static final class Block {
        final boolean snapshot;
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        Block(boolean snapshot) { this.snapshot=snapshot; }
        void waitForRelease() { entered.countDown(); try { if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("Controlled callback timed out"); } catch(InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); } }
    }
    private static final class Engine implements RimeEngine {
        volatile Block next,active;
        volatile Snapshot current=Snapshot.EMPTY;
        volatile int keys,snapshots;
        private void worker() { if(Looper.myLooper()==Looper.getMainLooper()) throw new AssertionError("fake engine ran on main"); }
        void block(boolean snapshot) { Block pending=next; if(pending!=null && pending.snapshot==snapshot) { next=null; active=pending; pending.waitForRelease(); active=null; } }
        void unblock() { if(next!=null) next.release.countDown(); if(active!=null) active.release.countDown(); }
        public void initialize(String system,String user) { throw new AssertionError("Frame fixture must not initialize JNI"); }
        public long createSession() { worker(); current=Snapshot.EMPTY; return 2; }
        public void destroySession(long session) { worker(); }
        public boolean selectSchema(long session,String schema) { worker(); return true; }
        public Snapshot snapshot(long session) { worker(); snapshots++; block(true); return current; }
        public Snapshot processKey(long session,int key) {
            worker(); keys++; block(false);
            if(key=='n') current=new Snapshot(true,"n","n",1,"",new String[]{"你"},new String[]{"ni"},0,0,true);
            else if(key==0xff08) current=Snapshot.EMPTY;
            else throw new AssertionError("Unexpected synthetic engine key");
            return current;
        }
        public Snapshot selectCandidate(long session,int index) { throw new AssertionError("No candidate selection invented by frame fixture"); }
        public void clearComposition(long session) { worker(); current=Snapshot.EMPTY; }
    }
    private static Object get(Object instance,String name) throws Exception { Field field=RimesInputMethodService.class.getDeclaredField(name); field.setAccessible(true); return field.get(instance); }
    private static void set(Object instance,String name,Object value) throws Exception { Field field=RimesInputMethodService.class.getDeclaredField(name); field.setAccessible(true); field.set(instance,value); }
    private static Object invoke(Object instance,String name,Class<?>[] types,Object... values) throws Exception {
        Method method=RimesInputMethodService.class.getDeclaredMethod(name,types); method.setAccessible(true);
        try { return method.invoke(instance,values); }
        catch(InvocationTargetException error) { if(error.getCause() instanceof Error) throw (Error)error.getCause(); if(error.getCause() instanceof Exception) throw (Exception)error.getCause(); throw error; }
    }
}

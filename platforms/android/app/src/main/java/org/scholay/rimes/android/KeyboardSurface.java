package org.scholay.rimes.android;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.ViewGroup;
import java.util.ArrayList;
import java.util.List;
import org.scholay.rimes.core.KeyboardLayout;
import org.scholay.rimes.core.KeyboardGeometry;

/** Measured geometry owns every key width. Candidate refreshes never rebuild the touch surface. */
final class KeyboardSurface extends ViewGroup {
    private static final String[] NUMBER_HINTS={"1","2","3","4","5","6","7","8","9","0"};
    interface Handler {
        String label(KeyboardLayout.Key key);
        String description(KeyboardLayout.Key key);
        boolean enabled(KeyboardLayout.Key key);
        boolean selected(KeyboardLayout.Key key);
        void press(KeyboardLayout.Key key);
        /** Literal alternate text must bypass Rime's candidate-number shortcuts. */
        default void onAlternate(String text) {}
    }
    private KeyboardLayout.Mode mode;
    private boolean chinese;
    private List<KeyboardLayout.Key> frames;
    private final Handler handler;
    private KeyboardTheme theme=KeyboardTheme.ALL[0];
    private int themeUiMode=-1;
    private final List<DeleteRepeatTouch> deleteRepeats=new ArrayList<>();
    private final List<UpwardNumberTouch> numberSwipes=new ArrayList<>();
    private final Paint hintPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    KeyboardSurface(Context context,Handler handler) { super(context); this.handler=handler; setLayoutDirection(LAYOUT_DIRECTION_LTR); }
    void render(KeyboardLayout.Mode mode,KeyboardTheme theme) { render(mode,false,theme); }
    void render(KeyboardLayout.Mode mode,boolean chinese,KeyboardTheme theme) {
        int uiMode=getResources().getConfiguration().uiMode;
        boolean rebuild=this.mode!=mode || this.chinese!=chinese;
        if(rebuild || this.theme!=theme || themeUiMode!=uiMode) cancelTouches();
        this.theme=theme;
        themeUiMode=uiMode;
        if(rebuild) {
            this.mode=mode; this.chinese=chinese; removeAllViews(); deleteRepeats.clear(); numberSwipes.clear();
            frames=KeyboardLayout.keys(400,landscape(),mode,chinese);
            for(KeyboardLayout.Key key:frames) {
                KeyButton button=new KeyButton(getContext());
                button.setOnClickListener(v -> handler.press(key));
                if(key.action==KeyboardLayout.Action.DELETE) deleteRepeats.add(new DeleteRepeatTouch(button));
                String number=numberFor(key);
                if(number!=null) numberSwipes.add(new UpwardNumberTouch(button,number,() -> handler.onAlternate(number),this::invalidate));
                addView(button);
            }
            requestLayout();
        }
        for(int i=0;i<getChildCount();i++) {
            KeyboardLayout.Key key=frames.get(i); KeyButton button=(KeyButton)getChildAt(i);
            boolean system=theme.id.equals("apple"),letter=key.action==KeyboardLayout.Action.TEXT;
            boolean glyph=letter || key.action==KeyboardLayout.Action.PUNCTUATION && !key.text.isEmpty();
            boolean functional=mode!=KeyboardLayout.Mode.NINE_KEY && !letter && key.action!=KeyboardLayout.Action.SPACE;
            button.appearance(functional,false,key.action==KeyboardLayout.Action.RETURN);
            int font=glyph?(mode==KeyboardLayout.Mode.NINE_KEY?20:system?24:21)
                    :key.action==KeyboardLayout.Action.LANGUAGE?18:system?18:14;
            button.fontStyle(glyph && !system && mode!=KeyboardLayout.Mode.EMOJI,font,!system || key.action==KeyboardLayout.Action.LANGUAGE);
            button.icon(key.action==KeyboardLayout.Action.SHIFT?(handler.selected(key)?KeyboardIcon.SHIFT_FILL:KeyboardIcon.SHIFT)
                    :key.action==KeyboardLayout.Action.DELETE?KeyboardIcon.DELETE:key.action==KeyboardLayout.Action.EMOJI && mode!=KeyboardLayout.Mode.EMOJI?KeyboardIcon.SMILE:null);
            String label=handler.label(key); if(!android.text.TextUtils.equals(button.getText(),label)) button.setText(label);
            String description=handler.description(key);
            if(!android.text.TextUtils.equals(button.getContentDescription(),description)) button.setContentDescription(description);
            boolean enabled=handler.enabled(key);
            if(button.isEnabled() && !enabled && key.action==KeyboardLayout.Action.DELETE) cancelDeleteRepeats();
            if(button.isEnabled() && !enabled && numberFor(key)!=null) cancelNumberSwipes();
            button.setEnabled(enabled);
            button.setSelected(handler.selected(key)); button.theme(theme);
        }
    }
    private void cancelDeleteRepeats() {
        // Visibility callbacks can occur inside View's constructor, before field initialization.
        if(deleteRepeats!=null) for(DeleteRepeatTouch repeat:deleteRepeats) repeat.cancel();
    }
    private void cancelNumberSwipes() {
        if(numberSwipes!=null) for(UpwardNumberTouch swipe:numberSwipes) swipe.cancel();
    }
    private void cancelTouches() { cancelDeleteRepeats(); cancelNumberSwipes(); }
    @Override public void onCancelPendingInputEvents() {
        super.onCancelPendingInputEvents(); cancelTouches();
    }
    @Override protected void onVisibilityChanged(View changedView,int visibility) {
        super.onVisibilityChanged(changedView,visibility);
        if(visibility!=VISIBLE) cancelTouches();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if(visibility!=VISIBLE) cancelTouches();
    }
    @Override protected void onDetachedFromWindow() {
        cancelTouches(); super.onDetachedFromWindow();
    }
    private String numberFor(KeyboardLayout.Key key) {
        if(mode!=KeyboardLayout.Mode.QWERTY || key.action!=KeyboardLayout.Action.TEXT || key.text.length()!=1) return null;
        int index="qwertyuiop".indexOf(key.text);
        return index<0?null:NUMBER_HINTS[index];
    }
    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if(mode!=KeyboardLayout.Mode.QWERTY || frames==null) return;
        float density=getResources().getDisplayMetrics().density;
        KeyboardTheme.Palette palette=theme.palette(getContext()); int swipeIndex=0;
        for(int i=0;i<frames.size();i++) {
            KeyboardLayout.Key key=frames.get(i); String number=numberFor(key);
            if(number==null) continue;
            View button=getChildAt(i); UpwardNumberTouch swipe=numberSwipes.get(swipeIndex++);
            hintPaint.setTextSize(Math.min(11,key.visualHeight*0.24f)*density);
            hintPaint.setColor(button.isPressed()?palette.accentInk:palette.ink);
            hintPaint.setAlpha(swipe.selected()?255:button.isEnabled()?140:56);
            float x=(key.visualX+key.visualWidth-4)*density-hintPaint.measureText(number);
            float baseline=(key.visualY+2)*density-hintPaint.ascent();
            canvas.drawText(number,x,baseline,hintPaint);
        }
    }
    private boolean landscape() { return getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE; }
    @Override protected void onMeasure(int widthSpec,int heightSpec) {
        int width=MeasureSpec.getSize(widthSpec); float density=getResources().getDisplayMetrics().density;
        int height=Math.round(KeyboardLayout.height(landscape())*density);
        setMeasuredDimension(width,resolveSize(height,heightSpec));
        if(mode==null) return;
        frames=KeyboardGeometry.fitHeight(KeyboardLayout.keys(Math.max(1,width/density),landscape(),mode,chinese),
                KeyboardLayout.height(landscape()),getMeasuredHeight()/density);
        for(int i=0;i<frames.size();i++) {
            KeyboardLayout.Key key=frames.get(i);
            int w=Math.round((key.x+key.width)*density)-Math.round(key.x*density);
            int h=Math.round((key.y+key.height)*density)-Math.round(key.y*density);
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(h,MeasureSpec.EXACTLY));
            int x=Math.round(key.x*density),y=Math.round(key.y*density);
            int capX=Math.round(key.visualX*density),capY=Math.round(key.visualY*density);
            int capW=Math.round((key.visualX+key.visualWidth)*density)-capX;
            int capH=Math.round((key.visualY+key.visualHeight)*density)-capY;
            ((KeyButton)getChildAt(i)).capFrame((capX-x)/density,(capY-y)/density,capW/density,capH/density);
        }
    }
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b) {
        if(changed) cancelTouches();
        float density=getResources().getDisplayMetrics().density;
        if(frames==null) return;
        for(int i=0;i<frames.size();i++) {
            KeyboardLayout.Key key=frames.get(i); View child=getChildAt(i);
            int x=Math.round(key.x*density),y=Math.round(key.y*density);
            child.layout(x,y,x+child.getMeasuredWidth(),y+child.getMeasuredHeight());
        }
    }
}

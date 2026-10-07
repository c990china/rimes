package org.scholay.rimes.android;

import android.content.Context;
import android.content.res.Configuration;
import android.view.View;
import android.view.ViewGroup;
import java.util.List;
import org.scholay.rimes.core.KeyboardLayout;

/** Measured geometry owns every key width. Candidate refreshes never rebuild the touch surface. */
final class KeyboardSurface extends ViewGroup {
    interface Handler {
        String label(KeyboardLayout.Key key);
        String description(KeyboardLayout.Key key);
        boolean enabled(KeyboardLayout.Key key);
        boolean selected(KeyboardLayout.Key key);
        void press(KeyboardLayout.Key key);
    }
    private KeyboardLayout.Mode mode;
    private boolean chinese;
    private List<KeyboardLayout.Key> frames;
    private final Handler handler;
    private KeyboardTheme theme=KeyboardTheme.ALL[0];
    KeyboardSurface(Context context,Handler handler) { super(context); this.handler=handler; setLayoutDirection(LAYOUT_DIRECTION_LTR); }
    void render(KeyboardLayout.Mode mode,boolean chinese,KeyboardTheme theme) {
        this.theme=theme;
        // Only the number and symbol pages differ by language; other pages keep their views.
        chinese&=mode==KeyboardLayout.Mode.NUMERIC || mode==KeyboardLayout.Mode.SYMBOLS;
        if(this.mode!=mode || this.chinese!=chinese) {
            this.mode=mode; this.chinese=chinese; removeAllViews();
            frames=KeyboardLayout.keys(400,landscape(),mode,chinese);
            for(KeyboardLayout.Key key:frames) {
                KeyButton button=new KeyButton(getContext());
                button.setOnClickListener(v -> handler.press(key)); addView(button);
            }
            requestLayout();
        }
        for(int i=0;i<getChildCount();i++) {
            KeyboardLayout.Key key=frames.get(i); KeyButton button=(KeyButton)getChildAt(i);
            boolean system=theme.id.equals("apple"),letter=key.action==KeyboardLayout.Action.TEXT;
            boolean functional=mode!=KeyboardLayout.Mode.NINE_KEY && !letter && key.action!=KeyboardLayout.Action.SPACE;
            button.appearance(functional,false,key.action==KeyboardLayout.Action.RETURN);
            int font=letter?(mode==KeyboardLayout.Mode.NINE_KEY?20:system?24:21)
                    :key.action==KeyboardLayout.Action.LANGUAGE?18:system?18:14;
            button.fontStyle(letter && !system && mode!=KeyboardLayout.Mode.EMOJI,font,!system || key.action==KeyboardLayout.Action.LANGUAGE);
            button.icon(key.action==KeyboardLayout.Action.SHIFT?(handler.selected(key)?KeyboardIcon.SHIFT_FILL:KeyboardIcon.SHIFT)
                    :key.action==KeyboardLayout.Action.DELETE?KeyboardIcon.DELETE:key.action==KeyboardLayout.Action.EMOJI && mode!=KeyboardLayout.Mode.EMOJI?KeyboardIcon.SMILE:null);
            String label=handler.label(key); if(!android.text.TextUtils.equals(button.getText(),label)) button.setText(label);
            String description=handler.description(key);
            if(!android.text.TextUtils.equals(button.getContentDescription(),description)) button.setContentDescription(description);
            button.setEnabled(handler.enabled(key));
            button.setSelected(handler.selected(key)); button.theme(theme);
        }
    }
    private boolean landscape() { return getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE; }
    @Override protected void onMeasure(int widthSpec,int heightSpec) {
        int width=MeasureSpec.getSize(widthSpec); float density=getResources().getDisplayMetrics().density;
        int height=Math.round(KeyboardLayout.height(landscape())*density);
        setMeasuredDimension(width,resolveSize(height,heightSpec));
        if(mode==null) return;
        frames=KeyboardLayout.keys(Math.max(1,width/density),landscape(),mode,chinese);
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
        float density=getResources().getDisplayMetrics().density;
        if(frames==null) return;
        for(int i=0;i<frames.size();i++) {
            KeyboardLayout.Key key=frames.get(i); View child=getChildAt(i);
            int x=Math.round(key.x*density),y=Math.round(key.y*density);
            child.layout(x,y,x+child.getMeasuredWidth(),y+child.getMeasuredHeight());
        }
    }
}

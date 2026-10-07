package org.scholay.rimes.android;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.scholay.rimes.core.KeyboardLayout;

/** Actual native measure/layout/draw contract. Call on main; it does not operate a device window. */
final class KeyboardHeightContract {
    private int checks;
    private static final int BACKGROUND=Color.rgb(7,11,13);
    static int run(Context context) {
        KeyboardHeightContract test=new KeyboardHeightContract();
        for(boolean landscape:new boolean[]{false,true}) for(boolean buffer:new boolean[]{false,true})
            for(KeyboardLayout.Mode mode:new KeyboardLayout.Mode[]{KeyboardLayout.Mode.QWERTY,KeyboardLayout.Mode.NINE_KEY})
                test.fit(context,landscape,buffer,mode);
        test.exhausted(context);
        return test.checks;
    }
    private void check(boolean condition,String label) {
        checks++; if(!condition) throw new AssertionError("Keyboard height: "+label);
    }
    private static final class Fixture {
        final KeyboardRoot root;
        final FrameLayout region;
        final KeyboardSurface keys;
        final TextView candidate,buffer;
        final int width,preferred,paddingBottom;
        Fixture(Context base,boolean landscape,boolean showBuffer,KeyboardLayout.Mode mode) {
            Configuration config=new Configuration(base.getResources().getConfiguration());
            config.orientation=landscape?Configuration.ORIENTATION_LANDSCAPE:Configuration.ORIENTATION_PORTRAIT;
            Context context=base.createConfigurationContext(config);
            float density=context.getResources().getDisplayMetrics().density;
            width=Math.round((landscape?640:400)*density);
            preferred=Math.round(KeyboardLayout.height(landscape)*density);
            root=new KeyboardRoot(context); root.setOrientation(LinearLayout.VERTICAL);
            paddingBottom=Math.round(29*density); // Existing outer padding plus a synthetic system-safe region.
            root.setPadding(0,Math.round(5*density),0,paddingBottom); root.setBackgroundColor(BACKGROUND);
            buffer=new TextView(context); buffer.setText("Buffer");
            LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,Math.round((landscape?60:76)*density));
            bp.bottomMargin=Math.round(4*density); root.addView(buffer,bp); buffer.setVisibility(showBuffer?View.VISIBLE:View.GONE);
            candidate=new TextView(context); candidate.setText("候选");
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,Math.round(32*density));
            cp.bottomMargin=Math.round(4*density); root.addView(candidate,cp);
            region=new FrameLayout(context);
            keys=new KeyboardSurface(context,new KeyboardSurface.Handler() {
                public String label(KeyboardLayout.Key key) { return key.text.isEmpty()?key.action.name():key.text; }
                public String description(KeyboardLayout.Key key) { return label(key); }
                public boolean enabled(KeyboardLayout.Key key) { return true; }
                public boolean selected(KeyboardLayout.Key key) { return false; }
                public void press(KeyboardLayout.Key key) { }
            });
            keys.render(mode,KeyboardTheme.ALL[0]);
            region.addView(keys,new FrameLayout.LayoutParams(-1,-1));
            root.addView(region,new LinearLayout.LayoutParams(-1,preferred)); root.setInputSurface(region);
        }
        void measure(int mode,int maximum) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(maximum,mode));
            root.layout(0,0,width,root.getMeasuredHeight());
        }
    }
    private void fit(Context context,boolean landscape,boolean buffer,KeyboardLayout.Mode mode) {
        Fixture f=new Fixture(context,landscape,buffer,mode);
        f.measure(View.MeasureSpec.UNSPECIFIED,0); int natural=f.root.getMeasuredHeight();
        check(f.region.getHeight()==f.preferred,"preferred surface unchanged without a constraint");
        int candidateHeight=f.candidate.getHeight(),bufferHeight=f.buffer.getHeight();
        int maximum=natural-Math.round(f.preferred*0.25f);
        f.measure(View.MeasureSpec.AT_MOST,maximum);
        check(f.root.getMeasuredHeight()==maximum,"root honors reduced available height");
        check(f.region.getMeasuredHeight()==f.preferred-(natural-maximum),"only surface absorbs the deficit");
        check(f.region.getLayoutParams().height==f.preferred,"preferred layout parameter restored");
        check(f.keys.getHeight()==f.region.getHeight(),"actual key surface uses fitted region");
        check(f.candidate.getHeight()==candidateHeight && (!buffer || f.buffer.getHeight()==bufferHeight),"visible chrome keeps its dimensions");
        check(f.root.getPaddingBottom()==f.paddingBottom,"system-safe padding preserved");
        check(f.region.getBottom()+f.paddingBottom<=f.root.getHeight(),"surface and safe padding fit inside root");
        check(!f.root.chromeTooTall(),"normal constrained chrome fits");
        f.measure(View.MeasureSpec.EXACTLY,maximum);
        check(f.root.getHeight()==maximum && f.region.getHeight()==f.preferred-(natural-maximum),"exact parent height also preserves the complete fitted stack");
        for(int i=0;i<f.keys.getChildCount();i++) {
            View key=f.keys.getChildAt(i);
            check(key.getTop()>=0 && key.getBottom()<=f.keys.getHeight(),"every native touch cell, including footer, fits");
        }
        Bitmap bitmap=Bitmap.createBitmap(f.width,f.root.getHeight(),Bitmap.Config.ARGB_8888);
        try {
            f.root.draw(new Canvas(bitmap));
            for(int i=0;i<f.keys.getChildCount();i++) {
                KeyButton key=(KeyButton)f.keys.getChildAt(i);
                if(!key.getText().toString().equals("SPACE") && !key.getText().toString().equals("RETURN")) continue;
                int x=f.region.getLeft()+f.keys.getLeft()+key.getLeft(),y=f.region.getTop()+f.keys.getTop()+key.getTop();
                int nonBackground=0;
                for(int py=y;py<y+key.getHeight();py++) for(int px=x;px<x+key.getWidth();px++)
                    if(bitmap.getPixel(px,py)!=BACKGROUND) nonBackground++;
                check(nonBackground>0,"fitted footer actually draws within the root bitmap");
            }
        } finally { bitmap.recycle(); }
        // The same root must grow back after hiding Buffer/reopening in an unrestricted window.
        f.measure(View.MeasureSpec.AT_MOST,natural+f.preferred);
        check(f.region.getHeight()==f.preferred && f.root.getMeasuredHeight()==natural,"constraint removal restores original full geometry");
        f.buffer.setVisibility(View.GONE);
        f.measure(View.MeasureSpec.UNSPECIFIED,0);
        check(f.region.getHeight()==f.preferred,"Buffer visibility changes do not leave a compressed surface");
    }
    private void exhausted(Context context) {
        Fixture f=new Fixture(context,false,true,KeyboardLayout.Mode.QWERTY);
        f.measure(View.MeasureSpec.UNSPECIFIED,0);
        int chrome=f.root.getMeasuredHeight()-f.preferred;
        f.measure(View.MeasureSpec.AT_MOST,Math.max(0,chrome-1));
        check(f.region.getMeasuredHeight()==0 && f.root.chromeTooTall(),"impossible chrome budget is explicit and key height is nonnegative");
        check(f.root.getPaddingBottom()==f.paddingBottom,"exhausted budget still preserves safe padding");
        check(f.region.getLayoutParams().height==f.preferred,"exhausted measurement also restores preferred height");
    }
}

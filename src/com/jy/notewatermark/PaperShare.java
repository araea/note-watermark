package com.jy.notewatermark;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.text.TextUtils;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;

/**
 * The share-as-picture surfaces of the ColorOS AI notes app (「笔记」) that
 * {@link ShareAdapt} does not reach.
 *
 * The app has two ways to turn a note into a picture. The screen that shows a whole
 * note is a web page (ShareWebPreviewActivity) whose footer is a DOM element, so it is
 * handled by a small script fed to the page's web view. The native share screen keeps
 * the old footer views, which {@link ShareAdapt} already covers, except for its default
 * "simple" style: that style paints each page into a bitmap, footer included, inside
 * SimpleStyleRenderer, so the paint calls are what get intercepted there.
 */
final class PaperShare {
    private static final String RENDERER = "com.nearme.note.activity.edit.SimpleStyleRenderer";
    private static final String WEB_SHARE = "com.nearme.note.activity.edit.ShareWebPreviewActivity";

    /** Height the renderer reserves for its footer on the last page, in dp. */
    private static final float FOOTER_DP = 106f;
    /** What stays under the last line when the footer goes: the page border sits 16dp in, and the top margin is 32dp. */
    private static final float TAIL_PAD_DP = 32f;
    private static final float MARGIN_DP = 24f;
    private static final float MIN_PAGE_DP = 216f;

    /** Re-feeding the page script this often keeps a reloaded page covered at a negligible cost. */
    private static final long REFRESH_MS = 1000L;

    private static final WeakHashMap<Activity, Boolean> WATCHED = new WeakHashMap<Activity, Boolean>();
    private static final WeakHashMap<Activity, String> LAST = new WeakHashMap<Activity, String>();

    private PaperShare() {
    }

    static void install(XposedInterface xposed, ClassLoader loader) {
        hookSimpleStyle(xposed, loader);
        hookWebShare(xposed);
    }

    // ------------------------------------------------------- simple style (native)

    private static void hookSimpleStyle(XposedInterface xposed, ClassLoader loader) {
        final Class<?> renderer;
        try {
            renderer = loader.loadClass(RENDERER);
        } catch (Throwable t) {
            Main.log("simple share style not found: " + t);
            return;
        }
        Method draw = null, height = null;
        for (Method method : renderer.getDeclaredMethods()) {
            Class<?>[] args = method.getParameterTypes();
            if (method.getName().equals("drawWatermark") && args.length == 4
                    && args[0] == Canvas.class && args[1] == float.class
                    && args[2] == float.class && args[3] == String.class) {
                draw = method;
            } else if (method.getName().equals("calculatePageHeightPx") && args.length == 4
                    && method.getReturnType() == int.class) {
                height = method;
            }
        }
        if (draw != null) {
            try {
                xposed.hook(draw).setId("simpleFooter").intercept(new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        FooterConfig config = Main.readConfig(contextOf(chain.getThisObject()));
                        if (config.custom()) {
                            drawCustom(chain, config.watermarkText);
                            return null;
                        }
                        // Both "hidden" and "blank" leave the footer unpainted; they differ in
                        // whether the page keeps the room, which the height hook decides.
                        return null;
                    }
                });
                Main.log("hooked " + draw);
            } catch (Throwable t) {
                Main.log("could not hook the simple footer: " + t);
            }
        }
        if (height != null) {
            try {
                xposed.hook(height).setId("simpleHeight").intercept(new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Object result = chain.proceed();
                        try {
                            FooterConfig config = Main.readConfig(contextOf(chain.getThisObject()));
                            if (config.hidden() && carriesFooter(chain.getArg(0)) && isFlowing(chain.getArg(1))) {
                                float density = density(chain.getThisObject());
                                int shorter = (Integer) result - Math.round((FOOTER_DP - TAIL_PAD_DP) * density);
                                return Math.max(shorter, Math.round(MIN_PAGE_DP * density));
                            }
                        } catch (Throwable t) {
                            Main.log("could not trim the simple page: " + t);
                        }
                        return result;
                    }
                });
                Main.log("hooked " + height);
            } catch (Throwable t) {
                Main.log("could not hook the simple page height: " + t);
            }
        }
    }

    /** Whether the page object says it paints the footer; its toString names the flag, whatever R8 called the getter. */
    private static boolean carriesFooter(Object page) {
        return page != null && String.valueOf(page).endsWith("showWatermark=true)");
    }

    /** Fixed ratios keep their page size; only the flowing "long image" page grows to fit its footer. */
    private static boolean isFlowing(Object ratio) {
        if (!(ratio instanceof Enum)) return false;
        String name = ((Enum<?>) ratio).name();
        return !name.equals("RATIO_3_4") && !name.equals("RATIO_1_1");
    }

    /** The footer as the app paints it, with the brand word swapped for the module's line. */
    private static void drawCustom(XposedInterface.Chain chain, String custom) {
        Canvas canvas = (Canvas) chain.getArg(0);
        float top = (Float) chain.getArg(1);
        float width = (Float) chain.getArg(2);
        String owner = (String) chain.getArg(3);
        float density = density(chain.getThisObject());
        float margin = MARGIN_DP * density;

        Paint line = new Paint();
        line.setColor(0x1A000000);
        line.setStrokeWidth(0.6f * density);
        canvas.drawLine(margin, top + 24f * density, width - margin, top + 24f * density, line);

        TextPaint text = new TextPaint();
        text.setColor(0x8A000000);
        text.setTextSize(12f * density);
        text.setAntiAlias(true);
        float room = width - 2 * margin;
        CharSequence fitted = TextUtils.ellipsize(custom, text, room, TextUtils.TruncateAt.END);
        canvas.drawText(fitted, 0, fitted.length(),
                (width - text.measureText(fitted, 0, fitted.length())) / 2f, top + 44f * density, text);
        if (owner != null && !owner.trim().isEmpty()) {
            CharSequence by = TextUtils.ellipsize("记录人：" + owner, text, room, TextUtils.TruncateAt.END);
            canvas.drawText(by, 0, by.length(),
                    (width - text.measureText(by, 0, by.length())) / 2f, top + 60f * density, text);
        }
    }

    private static float density(Object renderer) {
        Context context = contextOf(renderer);
        return (context != null ? context.getResources() : android.content.res.Resources.getSystem())
                .getDisplayMetrics().density;
    }

    /** The renderer's own context; null makes {@link Main#readConfig} fall back to the app. */
    private static Context contextOf(Object renderer) {
        try {
            Field field = renderer.getClass().getDeclaredField("context");
            field.setAccessible(true);
            return (Context) field.get(renderer);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------ web page share

    /**
     * The page is fed from outside rather than from a hook on the web view: the app's own
     * calls into it never reach a hook on {@code WebView.evaluateJavascript}, but the
     * activity that owns the view is easy to see. While the share screen is on display, a
     * timer finds its web view and (re)applies the footer style, which also covers a page
     * that reloads itself.
     */
    private static void hookWebShare(XposedInterface xposed) {
        try {
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            xposed.hook(onResume).setId("shareWeb").intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    Object host = chain.getThisObject();
                    if (host.getClass().getName().equals(WEB_SHARE)) {
                        watch((Activity) host);
                    }
                    return result;
                }
            });
            Main.log("hooked " + onResume);
        } catch (Throwable t) {
            Main.log("could not hook the web share: " + t);
        }
    }

    private static void watch(final Activity host) {
        synchronized (WATCHED) {
            if (WATCHED.put(host, Boolean.TRUE) != null) return;
        }
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (host.isFinishing() || host.isDestroyed()) {
                    synchronized (WATCHED) {
                        WATCHED.remove(host);
                    }
                    return;
                }
                feed(host);
                handler.postDelayed(this, REFRESH_MS);
            }
        });
        Main.log("watching the share page");
    }

    private static void feed(Activity host) {
        try {
            WebView view = findWebView(host.getWindow().getDecorView(), 0);
            if (view == null) return;
            FooterConfig config = Main.readConfig(host);
            String mode = config.custom() ? "custom" : config.hidden() ? "hidden" : "blank";
            view.evaluateJavascript(script(config), null);
            String previous;
            synchronized (WATCHED) {
                previous = LAST.put(host, mode);
            }
            if (!mode.equals(previous)) Main.log("share page styled: " + mode);
        } catch (Throwable t) {
            Main.log("could not feed the share page: " + t);
        }
    }

    private static WebView findWebView(View view, int depth) {
        if (view instanceof WebView) return (WebView) view;
        if (!(view instanceof ViewGroup) || depth > 24) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            WebView found = findWebView(group.getChildAt(i), depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * Styles the page's footer for the chosen mode and keeps it that way as the page
     * re-renders. The hiding is CSS, so it holds before the picture is taken; the custom
     * line needs the observer because the page rewrites its own text.
     */
    static String script(FooterConfig config) {
        String mode = config.custom() ? "custom" : config.hidden() ? "hidden" : "blank";
        String css = config.custom() ? ".share-watermark__brand-mark{display:none!important}"
                : config.hidden() ? ".share-watermark{display:none!important}"
                        + ".share-page{--share-watermark-height:0px!important}"
                        + ".share-page__tail-reserve{height:0!important}"
                : ".share-watermark{visibility:hidden!important}";
        return "(function(){try{"
                + "window.__nwCfg={mode:" + JSONObject.quote(mode) + ",text:"
                + JSONObject.quote(config.watermarkText) + "};"
                + "var st=document.getElementById('nw-style');"
                + "if(!st){st=document.createElement('style');st.id='nw-style';"
                + "(document.head||document.documentElement).appendChild(st);}"
                + "st.textContent=" + JSONObject.quote(css) + ";"
                + "if(window.__nwApply){window.__nwApply();return;}"
                + "window.__nwApply=function(){var c=window.__nwCfg;if(c.mode!=='custom')return;"
                + "var n=document.querySelectorAll('.share-watermark__brand-text');"
                + "for(var i=0;i<n.length;i++){if(n[i].textContent!==c.text)n[i].textContent=c.text;}};"
                + "new MutationObserver(function(){window.__nwApply();})"
                + ".observe(document.documentElement,{childList:true,subtree:true,characterData:true});"
                + "window.__nwApply();"
                + "}catch(e){}})();";
    }
}

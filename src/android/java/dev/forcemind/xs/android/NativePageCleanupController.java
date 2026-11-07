package dev.forcemind.xs.android;

import android.os.Looper;
import android.webkit.WebView;
import dev.forcemind.xs.core.ObservationRoutes;
import dev.forcemind.xs.core.StrictJson;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Fixture-only native owner of packaged reversible cleanup. BrowserActivity does not attach it.
 * Settings are not live-site validation. No JavaScript interface, network or storage operations.
 * All lifecycle methods must be called on the main thread, before destroying the WebView.
 */
public final class NativePageCleanupController {
    public static final boolean LIVE_SITE_ENABLED = false;
    public static final class Status {
        public final int hidden;
        public final boolean active, capped;
        private Status(int hidden, boolean active, boolean capped) {
            this.hidden=hidden; this.active=active; this.capped=capped;
        }
    }
    private final WebView web;
    private final String script;
    private boolean ads, subscription, recommendations, foreground, covered, destroyed;
    private long generation;
    private String currentUrl;
    private Status status=new Status(0,false,false);

    public NativePageCleanupController(WebView web) {
        mainThread();
        if(web==null)throw new IllegalArgumentException("WebView required");
        this.web=web;
        try(InputStream input=web.getContext().getAssets().open("xs-cleanup.js")) {
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
            while((n=input.read(buffer))!=-1){out.write(buffer,0,n);if(out.size()>65536)throw new IllegalStateException();}
            script=new String(out.toByteArray(),StandardCharsets.UTF_8);
        } catch(Exception unavailable){throw new IllegalStateException("Packaged cleanup unavailable");}
    }
    public void setOptions(boolean ads,boolean subscription,boolean recommendations) {
        mainThread();if(destroyed)return;
        this.ads=ads;this.subscription=subscription;this.recommendations=recommendations;
        generation++;apply();
    }
    public void resume(){mainThread();if(destroyed)return;foreground=true;generation++;apply();}
    public void pause(){mainThread();if(destroyed)return;foreground=false;generation++;restore();}
    /** Native navigation invalidates the previous document before any new page becomes eligible. */
    public void navigation(String url){mainThread();if(destroyed)return;generation++;restore();currentUrl=url;}
    public void pageReady(){mainThread();if(destroyed)return;generation++;apply();}
    public void setOverlay(boolean covered){mainThread();if(destroyed)return;this.covered=covered;generation++;apply();}
    /** Last explicit native apply response, not a continuously polled webpage counter. */
    public Status getStatus(){mainThread();return status;}
    public void destroy(){mainThread();if(destroyed)return;generation++;foreground=false;restore();destroyed=true;}
    private void apply() {
        if(destroyed||!foreground||covered||!web.isShown()||!allowed(currentUrl)||!same(currentUrl,web.getUrl())){restore();return;}
        final long epoch=generation;final String expected=currentUrl;
        String options="{ads:"+ads+",subscription:"+subscription+",recommendations:"+recommendations+"}";
        String body="(function(){if(location.href!=="+JSONObject.quote(expected)+")return null;"+
            script+"\nreturn JSON.stringify(window.__xsCleanup.apply("+options+"));})()";
        try {
            web.evaluateJavascript(body,raw->{
                if(destroyed||!foreground||covered||epoch!=generation||!same(expected,currentUrl)||!same(expected,web.getUrl()))return;
                try {
                    Object wrapped=StrictJson.parse(raw);
                    if(!(wrapped instanceof String))throw new IllegalArgumentException();
                    Object decoded=StrictJson.parse((String)wrapped);
                    if(!(decoded instanceof Map))throw new IllegalArgumentException();
                    Map<?,?> m=(Map<?,?>)decoded;
                    if(m.size()!=5||!Long.valueOf(1).equals(m.get("v"))||!expected.equals(m.get("url"))||
                        !(m.get("hidden") instanceof Long)||!(m.get("active") instanceof Boolean)||!(m.get("capped") instanceof Boolean))throw new IllegalArgumentException();
                    long count=(Long)m.get("hidden");if(count<0||count>128)throw new IllegalArgumentException();
                    status=new Status((int)count,(Boolean)m.get("active"),(Boolean)m.get("capped"));
                }catch(RuntimeException invalid){status=new Status(0,false,false);restore();}
            });
        }catch(RuntimeException stopped){status=new Status(0,false,false);}
    }
    private void restore() {
        status=new Status(0,false,false);
        try{web.evaluateJavascript("(function(){try{if(window.__xsCleanup)window.__xsCleanup.stop();}catch(e){}})()",null);}
        catch(RuntimeException stopped){/* A detached renderer does not grant cleanup permission. */}
    }
    private static boolean allowed(String url){return ObservationRoutes.classify(url)!=ObservationRoutes.Kind.DENIED;}
    private static boolean same(String a,String b){return a!=null&&a.equals(b);}
    private static void mainThread(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("Main thread required");}
}

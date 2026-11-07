package dev.forcemind.xs.android;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.WebView;
import dev.forcemind.xs.core.CaptureSession;
import dev.forcemind.xs.core.ObservationRoutes;
import dev.forcemind.xs.core.StrictJson;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Native-pull adapter for offline fixtures. Browser production builds do not attach it yet. */
public final class NativePageCaptureController {
    private final WebView web;
    private final CaptureSession session;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final String script;
    private boolean running, destroyed, installed, inFlight;
    private long requestSerial;
    private Runnable timeout;
    private String currentUrl;
    private final Runnable poll=() -> readNow();
    public NativePageCaptureController(WebView web,String library,CaptureSession.Sink sink) {
        this.web=web; session=new CaptureSession(library,sink);
        try(InputStream in=web.getContext().getAssets().open("xs-observer.js")) {
            ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] bytes=new byte[4096];int n;
            while((n=in.read(bytes))!=-1) { out.write(bytes,0,n);if(out.size()>65536)throw new IllegalStateException(); }
            script=new String(out.toByteArray(),StandardCharsets.UTF_8);
        } catch(Exception unavailable) { throw new IllegalStateException("Packaged observer unavailable"); }
    }
    public void setConsent(boolean opens,boolean feed,long threshold) { session.setConsent(opens,feed,threshold); }
    public void resume() { if(destroyed)return;running=true;session.setForeground(true);schedule(); }
    public void pause() { running=false;handler.removeCallbacks(poll);cancelRead();session.setForeground(false); }
    public void setOverlay(boolean covered) { session.setOverlay(covered); }
    public void navigation(String url,String nativeNavigationId,boolean deliberate) {
        currentUrl=url; installed=false;cancelRead();
        session.navigation(url,nativeNavigationId,deliberate,System.currentTimeMillis());
    }
    /** Called after the native page-ready callback; navigation identity must be supplied separately. */
    public void pageReady() {
        if(destroyed || ObservationRoutes.classify(currentUrl)==ObservationRoutes.Kind.DENIED)return;
        final long epoch=session.generation();final String url=currentUrl;
        web.evaluateJavascript(script,ignored -> {
            if(!destroyed && epoch==session.generation() && same(url,web.getUrl())) {
                installed=true;readNow();
            }
        });
    }
    public boolean isCurrent(CaptureSession.Event event) { return !destroyed && session.isCurrent(event); }
    public void invalidate() { session.invalidate(); }
    public void destroy() { pause();destroyed=true;session.destroy();web.evaluateJavascript("window.__xsObserver&&window.__xsObserver.stop()",null); }
    public void readNow() {
        handler.removeCallbacks(poll);
        if(!running || destroyed || inFlight)return;
        if(!installed || !same(currentUrl,web.getUrl())) { schedule();return; }
        final long epoch=session.generation();final String url=currentUrl;
        inFlight=true;final long request=++requestSerial;
        timeout=() -> {if(request==requestSerial){inFlight=false;requestSerial++;
            session.sample(new ArrayList<>(),"home_for_you",SystemClock.elapsedRealtime(),System.currentTimeMillis());schedule();}};
        handler.postDelayed(timeout,1500);
        web.evaluateJavascript("window.__xsObserver?JSON.stringify(window.__xsObserver.read()):null",raw -> {
            if(request!=requestSerial)return;
            inFlight=false;if(timeout!=null)handler.removeCallbacks(timeout);
            if(!running || destroyed || epoch!=session.generation() || !same(url,currentUrl) || !same(url,web.getUrl())){schedule();return;}
            try {
                Object outer=StrictJson.parse(raw);
                if(!(outer instanceof String))throw new IllegalArgumentException();
                Map<String,Object> envelope=object(StrictJson.parse((String)outer));
                keys(envelope,"v","url","cards");
                if(!Long.valueOf(1).equals(envelope.get("v")) || !url.equals(envelope.get("url")))throw new IllegalArgumentException();
                Object cardsValue=envelope.get("cards");
                if(!(cardsValue instanceof List))throw new IllegalArgumentException();
                List<?> values=(List<?>)cardsValue;if(values.size()>CaptureSession.MAX_CARDS)throw new IllegalArgumentException();
                List<CaptureSession.Card> cards=new ArrayList<>();
                for(Object value:values) {
                    Map<String,Object> m=object(value); keys(m,"url","author","body","publishedAtMs","readableFraction","run");
                    Object published=m.get("publishedAtMs"),fraction=m.get("readableFraction");
                    if(published!=null && !(published instanceof Long) || !(fraction instanceof Number))throw new IllegalArgumentException();
                    CaptureSession.Card card=new CaptureSession.Card(string(m,"url",8192),string(m,"author",100),
                        string(m,"body",16000),(Long)published,((Number)fraction).doubleValue(),string(m,"run",80));
                    cards.add(card);
                }
                long wall=System.currentTimeMillis();
                if(ObservationRoutes.classify(url)==ObservationRoutes.Kind.POST_DETAIL) {
                    for(CaptureSession.Card card:cards)session.detail(card,wall);
                } else {
                    String source=url.contains("/search")?"search_results":"home_for_you";
                    session.sample(cards,source,SystemClock.elapsedRealtime(),wall);
                }
            } catch(RuntimeException invalid) {
                session.sample(new ArrayList<>(),"home_for_you",SystemClock.elapsedRealtime(),System.currentTimeMillis());
            }
            schedule();
        });
    }
    private void cancelRead(){requestSerial++;inFlight=false;if(timeout!=null)handler.removeCallbacks(timeout);}
    private void schedule(){if(running&&!destroyed){handler.removeCallbacks(poll);handler.postDelayed(poll,200);}}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object o) {
        if(!(o instanceof Map))throw new IllegalArgumentException();return(Map<String,Object>)o;
    }
    private static void keys(Map<String,Object> m,String...allowed){
        if(m.size()!=allowed.length)throw new IllegalArgumentException();
        for(String key:allowed)if(!m.containsKey(key))throw new IllegalArgumentException();
    }
    private static String string(Map<String,Object> m,String key,int limit){
        Object o=m.get(key);if(!(o instanceof String)||((String)o).length()>limit)throw new IllegalArgumentException();return(String)o;
    }
    private static boolean same(String a,String b){return a!=null&&a.equals(b);}
}

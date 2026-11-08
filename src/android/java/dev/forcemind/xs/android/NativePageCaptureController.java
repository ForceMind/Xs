package dev.forcemind.xs.android;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.PowerManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.WebView;
import dev.forcemind.xs.core.CaptureSession;
import dev.forcemind.xs.core.ObservationRoutes;
import dev.forcemind.xs.core.PostUrls;
import dev.forcemind.xs.core.StrictJson;
import dev.forcemind.xs.core.TimelineCaptureSession;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Native-pull adapter for offline fixtures. Browser production builds do not attach it yet. */
public final class NativePageCaptureController {
    // Bound the JS->WebView result before IPC as well as validating raw native input again.
    private static final String READ="(function(){try{if(!window.__xsObserver)return null;"+
        "var payload=JSON.stringify(window.__xsObserver.read());"+
        "return typeof payload==='string'&&payload.length<=32768&&JSON.stringify(payload).length<=65536?payload:null;"+
        "}catch(_){return null}})()";
    private final WebView web;
    private final CaptureSession session;
    private final TimelineCaptureSession buffer;
    private boolean bufferConsent;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final String script;
    private boolean running, installed, inFlight, covered, consent, ready;
    private volatile boolean destroyed;
    private long requestSerial;
    private long rootRevision=-1;
    private Runnable timeout;
    private String currentUrl, verifiedFeedContext;
    private final Runnable poll=() -> readNow();
    public NativePageCaptureController(WebView web,String library,CaptureSession.Sink sink) {
        mainThread();this.web=web; session=new CaptureSession(library,sink);buffer=new TimelineCaptureSession(library);
        try(InputStream in=web.getContext().getAssets().open("xs-observer.js")) {
            ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] bytes=new byte[4096];int n;
            while((n=in.read(bytes))!=-1) { out.write(bytes,0,n);if(out.size()>65536)throw new IllegalStateException(); }
            script=new String(out.toByteArray(),StandardCharsets.UTF_8);
        } catch(Exception unavailable) { throw new IllegalStateException("Packaged observer unavailable"); }
    }
    public void setConsent(boolean opens,boolean feed,long threshold) {
        mainThread();if(destroyed)return;
        session.setConsent(opens,feed,threshold);consent=opens||feed;
        if(!observing())stopObserver();else {ensureObserver();schedule();}
    }
    public void setBufferSink(TimelineCaptureSession.Sink sink){mainThread();if(!destroyed)buffer.setSink(sink);}
    /** Concrete fixture wiring: library tickets are captured now and native admission is rechecked at write time. */
    public void setBufferStore(XsTimelineBuffer store){
        mainThread();if(store==null)throw new IllegalArgumentException();
        setBufferSink(new TimelineCaptureSession.Sink(){
            public void observed(TimelineCaptureSession.Event event){
                final XsTimelineBuffer.Ticket ticket;
                try{ticket=store.ticket(event.library);}catch(RuntimeException unavailable){return;}
                store.queue(ticket,new XsTimelineBuffer.Batch(event.batchId,event.batchReason,event.source),
                    new XsTimelineBuffer.Candidate(event.url,event.author,event.body,event.publishedAtMs,event.quality,event.observedAtMs,event.sequence),
                    ()->isCurrent(event),null);
            }
            public void dropped(long count,String reason){store.recordAdmissionDrops(count,reason);}
        });
    }
    public void setBufferConsent(boolean enabled){
        mainThread();if(destroyed)return;if(bufferConsent!=enabled)stopObserver();bufferConsent=enabled;buffer.setConsent(enabled);
        if(!observing())stopObserver();else {ensureObserver();schedule();}
    }
    public boolean isCurrent(TimelineCaptureSession.Event event){return !destroyed&&buffer.isCurrent(event);}
    /** Native reload starts a local refresh batch; it does not claim an API response batch. */
    public void refreshed(){mainThread();if(!destroyed)buffer.refreshed();}
    public long bufferDropped(){return buffer.dropped();}
    private boolean observing(){return consent||bufferConsent;}
    /** Called only by a native-verified tab adapter (offline fixtures for now), never DOM labels. */
    public void setFeedContext(String verifiedNativeContext) {
        mainThread();if(destroyed)return;
        if(verifiedNativeContext!=null && !"home_for_you".equals(verifiedNativeContext) &&
                !"home_following".equals(verifiedNativeContext))throw new IllegalArgumentException("Unsupported home context");
        if(!same(verifiedFeedContext,verifiedNativeContext))session.invalidate();
        verifiedFeedContext=verifiedNativeContext;buffer.setFeedContext(verifiedNativeContext);
    }
    public void resume() {
        mainThread();if(destroyed)return;running=true;
        session.setForeground(available());buffer.setForeground(available());ensureObserver();schedule();
    }
    public void pause() {
        mainThread();if(destroyed)return;running=false;handler.removeCallbacks(poll);
        stopObserver();session.setForeground(false);buffer.setForeground(false);
    }
    public void setOverlay(boolean value) {
        mainThread();if(destroyed)return;covered=value;session.setOverlay(value);buffer.setOverlay(value);
        if(value){handler.removeCallbacks(poll);stopObserver();}else {ensureObserver();schedule();}
    }
    public void navigation(String url,String nativeNavigationId,boolean deliberate) {
        mainThread();if(destroyed)return;stopObserver();ready=false;currentUrl=url;rootRevision=-1;
        session.setForeground(available());buffer.setForeground(available());buffer.navigation(url,nativeNavigationId);
        session.navigation(url,nativeNavigationId,deliberate,System.currentTimeMillis());
    }
    /** Called after the native page-ready callback; navigation identity must be supplied separately. */
    public void pageReady() {mainThread();if(destroyed)return;ready=true;ensureObserver();schedule();}
    private void ensureObserver() {
        if(destroyed || installed || inFlight || !ready || !observing() || covered || !available() ||
                ObservationRoutes.classify(currentUrl)==ObservationRoutes.Kind.DENIED || !same(currentUrl,web.getUrl()))return;
        final long epoch=session.generation();final String url=currentUrl;
        final long request=++requestSerial;inFlight=true;
        timeout=() -> {if(request==requestSerial){cancelRead();schedule();}};
        handler.postDelayed(timeout,1500);
        web.evaluateJavascript(script,ignored -> {
            if(request!=requestSerial)return;
            inFlight=false;if(timeout!=null)handler.removeCallbacks(timeout);
            if(!destroyed && !covered && observing() && available() && epoch==session.generation() &&
                    same(url,currentUrl) && same(url,web.getUrl())) {installed=true;readNow();}
            else schedule();
        });
    }
    public boolean isCurrent(CaptureSession.Event event) { return !destroyed && session.isCurrent(event); }
    public void invalidate() {mainThread();if(destroyed)return;session.invalidate();buffer.invalidate();stopObserver();ensureObserver();schedule();}
    public void destroy() {
        mainThread();if(destroyed)return;pause();destroyed=true;session.destroy();buffer.destroy();
    }
    private boolean available() {
        if(!running || !web.isShown() || !web.hasWindowFocus())return false;
        PowerManager power=(PowerManager)web.getContext().getSystemService(Context.POWER_SERVICE);
        KeyguardManager keyguard=(KeyguardManager)web.getContext().getSystemService(Context.KEYGUARD_SERVICE);
        return (power==null || power.isInteractive()) && (keyguard==null || !keyguard.isKeyguardLocked());
    }
    private void stopObserver() {
        installed=false;rootRevision=-1;cancelRead();
        web.evaluateJavascript("window.__xsObserver&&window.__xsObserver.stop()",null);
    }
    public void readNow() {
        mainThread();handler.removeCallbacks(poll);
        if(!running || destroyed || covered || !observing() || inFlight)return;
        boolean visible=available();session.setForeground(visible);buffer.setForeground(visible);
        if(!visible){stopObserver();schedule();return;}
        if(!installed || !same(currentUrl,web.getUrl())) {ensureObserver();schedule();return;}
        final long epoch=session.generation();final String url=currentUrl;
        inFlight=true;final long request=++requestSerial;
        timeout=() -> {if(request==requestSerial){inFlight=false;requestSerial++;
            session.sample(new ArrayList<>(),null,SystemClock.elapsedRealtime(),System.currentTimeMillis());schedule();}};
        handler.postDelayed(timeout,1500);
        web.evaluateJavascript(bufferConsent?READ.replace(".read()", ".read(true)"):READ,raw -> {
            if(request!=requestSerial)return;
            inFlight=false;if(timeout!=null)handler.removeCallbacks(timeout);
            if(!available() || covered || !observing() || destroyed || epoch!=session.generation() || !same(url,currentUrl) || !same(url,web.getUrl())){schedule();return;}
            try {
                if(raw==null || raw.length()>65536)throw new IllegalArgumentException();
                Object outer=StrictJson.parse(raw);
                if(!(outer instanceof String))throw new IllegalArgumentException();
                Map<String,Object> envelope=object(StrictJson.parse((String)outer));
                if(bufferConsent)keys(envelope,"v","url","cards","rootRevision");
                else keys(envelope,"v","url","cards");
                if(!Long.valueOf(1).equals(envelope.get("v")) || !url.equals(envelope.get("url")))throw new IllegalArgumentException();
                Object cardsValue=envelope.get("cards");
                if(!(cardsValue instanceof List))throw new IllegalArgumentException();
                List<?> values=(List<?>)cardsValue;if(values.size()>CaptureSession.MAX_CARDS)throw new IllegalArgumentException();
                List<CaptureSession.Card> cards=new ArrayList<>();
                for(Object value:values) {
                    Map<String,Object> m=object(value);
                    if(m.containsKey("textEvidence"))keys(m,"url","author","body","publishedAtMs","readableFraction","run","textEvidence");
                    else keys(m,"url","author","body","publishedAtMs","readableFraction","run");
                    Object published=m.get("publishedAtMs"),fraction=m.get("readableFraction");
                    if(published!=null && !(published instanceof Long) || !(fraction instanceof Number))throw new IllegalArgumentException();
                    String cardUrl=string(m,"url",8192),body=string(m,"body",16000);
                    boolean complete=ObservationRoutes.classify(url)==ObservationRoutes.Kind.POST_DETAIL &&
                        same(PostUrls.extractPostId(url),PostUrls.extractPostId(cardUrl)) &&
                        ((Number)fraction).doubleValue()>=.6 && completeText(m.get("textEvidence"),body);
                    CaptureSession.Card card=new CaptureSession.Card(cardUrl,string(m,"author",100),
                        body,(Long)published,((Number)fraction).doubleValue(),string(m,"run",80),complete);
                    cards.add(card);
                }
                long wall=System.currentTimeMillis();
                if(bufferConsent){
                    Object revision=envelope.get("rootRevision");
                    if(!(revision instanceof Long)||(Long)revision<0||(Long)revision>1_000_000)throw new IllegalArgumentException();
                    long current=(Long)revision;
                    if(rootRevision>=0 && current!=rootRevision)buffer.suspectedRefresh();
                    rootRevision=current;
                }
                if(ObservationRoutes.classify(url)==ObservationRoutes.Kind.POST_DETAIL) {
                    for(CaptureSession.Card card:cards)session.detail(card,wall);
                } else {
                    String source=ObservationRoutes.feedSource(url,verifiedFeedContext);
                    session.sample(cards,source,SystemClock.elapsedRealtime(),wall);
                    buffer.sample(cards,wall,SystemClock.elapsedRealtime());
                }
            } catch(RuntimeException invalid) {
                session.sample(new ArrayList<>(),null,SystemClock.elapsedRealtime(),System.currentTimeMillis());
            }
            schedule();
        });
    }
    private void cancelRead(){requestSerial++;inFlight=false;if(timeout!=null)handler.removeCallbacks(timeout);}
    private void schedule(){if(running&&!destroyed&&!covered&&observing()){handler.removeCallbacks(poll);handler.postDelayed(poll,200);}}
    private static void mainThread(){
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("Main thread required");
    }
    private static boolean completeText(Object evidence,String body) {
        // Missing/uncertain evidence fails to PARTIAL; no webpage quality enum is accepted.
        if(!(evidence instanceof Map) || body.isEmpty() || body.length()>2048)return false;
        Map<String,Object> m=object(evidence);
        if(m.size()!=8)return false;
        for(String key:new String[]{"rawLength","clientHeight","scrollHeight","lineClamp","overflow","clipped","hasMore","textVisible"})
            if(!m.containsKey(key))return false;
        if(!(m.get("rawLength") instanceof Long) || !(m.get("clientHeight") instanceof Long) ||
                !(m.get("scrollHeight") instanceof Long) || !(m.get("lineClamp") instanceof Long) ||
                !(m.get("overflow") instanceof String) || !(m.get("clipped") instanceof Boolean) || !(m.get("hasMore") instanceof Boolean) ||
                !(m.get("textVisible") instanceof Boolean))return false;
        long client=(Long)m.get("clientHeight"),scroll=(Long)m.get("scrollHeight");
        return Long.valueOf(body.length()).equals(m.get("rawLength")) && client>0 && client<=100000 &&
            scroll>0 && scroll<=client+1 && Long.valueOf(0).equals(m.get("lineClamp")) &&
            "visible".equals(m.get("overflow")) && Boolean.FALSE.equals(m.get("clipped")) && Boolean.FALSE.equals(m.get("hasMore")) &&
            Boolean.TRUE.equals(m.get("textVisible"));
    }
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

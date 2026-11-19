package dev.forcemind.xs.browser;

import android.content.Context;
import android.database.Cursor;
import android.os.SystemClock;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.forcemind.xs.android.NativePageCaptureController;
import dev.forcemind.xs.android.XsArchiveDatabase;
import dev.forcemind.xs.android.XsLibraryWrites;
import dev.forcemind.xs.android.XsTimelineBuffer;
import dev.forcemind.xs.core.ArchiveSearch;
import dev.forcemind.xs.core.CaptureSession;
import dev.forcemind.xs.core.SnapshotPolicy;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Actual WebView + SQLite fixture loops. All requests are intercepted locally; no live X claim. */
@RunWith(AndroidJUnit4.class)
public class NativeCaptureIntegrationTest {
    private static final String LIBRARY="library:observer-fixture";
    private Context app;
    private XsArchiveDatabase db;
    private ActivityScenario<BrowserActivity> scenario;
    private final AtomicReference<NativePageCaptureController> controller=new AtomicReference<>();
    private final AtomicReference<CountDownLatch> loaded=new AtomicReference<>();
    private final AtomicReference<CountDownLatch> openSaved=new AtomicReference<>();
    private final AtomicReference<CountDownLatch> bodySaved=new AtomicReference<>();
    private final AtomicReference<CountDownLatch> feedSaved=new AtomicReference<>();
    private final AtomicReference<String> html=new AtomicReference<>("");
    private final AtomicReference<String> navId=new AtomicReference<>("");
    private volatile boolean deliberate;
    private XsTimelineBuffer timeline;
    private final AtomicReference<CaptureSession.Event> lastOpen=new AtomicReference<>(),lastFeed=new AtomicReference<>();

    @Before public void setup() {
        app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        app.getSharedPreferences("browser.BrowserActivity",Context.MODE_PRIVATE).edit().clear().commit();
        app.deleteDatabase("xs-local-history.db");db=new XsArchiveDatabase(app);
        scenario=ActivityScenario.launch(BrowserActivity.class);
        scenario.onActivity(activity->{
            WebView web=activity.findViewById(R.id.browser_view);
            NativePageCaptureController capture=new NativePageCaptureController(web,LIBRARY,new CaptureSession.Sink(){
                private void write(CaptureSession.Event e,Runnable operation,AtomicReference<CountDownLatch> done){
                    XsLibraryWrites.Ticket ticket=XsLibraryWrites.ticket(LIBRARY);final CountDownLatch completion=done.get();
                    XsLibraryWrites.submit(ticket,()->{
                        if(controller.get().isCurrent(e)) {
                            operation.run();if(completion!=null)completion.countDown();
                        }
                    });
                }
                public void opened(CaptureSession.Event e){lastOpen.set(e);write(e,()->db.recordObservedOpen(LIBRARY,e.url,e.author,null,e.publishedAtMs,e.wallTimeMs,e.navigationId),openSaved);}
                public void enriched(CaptureSession.Event e){write(e,()->db.recordSnapshot(LIBRARY,e.url,e.author,e.body,e.publishedAtMs,e.capturedAtMs,SnapshotPolicy.Source.POST_DETAIL,e.snapshotQuality),bodySaved);}
                public void feed(CaptureSession.Event e){lastFeed.set(e);write(e,()->{db.recordMeasuredFeed(LIBRARY,e.url,e.source,e.author,null,e.publishedAtMs,e.wallTimeMs,e.exposureId,e.durationMs,e.thresholdMs);db.recordSnapshot(LIBRARY,e.url,e.author,e.body,e.publishedAtMs,e.capturedAtMs,SnapshotPolicy.Source.FEED_VISIBLE,SnapshotPolicy.Quality.PARTIAL);},feedSaved);}
            });
            controller.set(capture);capture.setFeedContext("home_for_you");capture.setConsent(true,true,3000);capture.resume();
            web.setWebViewClient(activity.new Client(){
                @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest req){
                    return new WebResourceResponse("text/html","UTF-8",new ByteArrayInputStream(html.get().getBytes(StandardCharsets.UTF_8)));
                }
                @Override public void onPageFinished(WebView view,String url){
                    super.onPageFinished(view,url);capture.navigation(url,navId.get(),deliberate);capture.pageReady();
                    CountDownLatch latch=loaded.get();if(latch!=null)latch.countDown();
                }
            });
        });
    }
    @After public void teardown()throws Exception {
        if(scenario!=null){scenario.onActivity(activity->controller.get().destroy());scenario.close();}
        CountDownLatch drained=new CountDownLatch(1);XsLibraryWrites.invalidateAndSubmit(LIBRARY,drained::countDown);
        assertTrue(drained.await(10,TimeUnit.SECONDS));
        if(timeline!=null){CountDownLatch clear=new CountDownLatch(1);timeline.clear(LIBRARY,ok->clear.countDown());assertTrue(clear.await(10,TimeUnit.SECONDS));timeline.configure(false,48,100);}
        db.close();app.deleteDatabase("xs-local-history.db");
    }
    private void load(String url,String nativeId,boolean userNavigation,String content)throws Exception {
        html.set(content);navId.set(nativeId);deliberate=userNavigation;
        CountDownLatch latch=new CountDownLatch(1);loaded.set(latch);
        scenario.onActivity(activity->((WebView)activity.findViewById(R.id.browser_view)).loadUrl(url));
        assertTrue("Fixture page load",latch.await(20,TimeUnit.SECONDS));
    }
    private void evaluate(String js)throws Exception { evaluateValue(js); }
    private String evaluateValue(String js)throws Exception {
        CountDownLatch latch=new CountDownLatch(1);AtomicReference<String> value=new AtomicReference<>();
        scenario.onActivity(activity->((WebView)activity.findViewById(R.id.browser_view)).evaluateJavascript(js,result->{value.set(result);latch.countDown();}));
        assertTrue(latch.await(10,TimeUnit.SECONDS));return value.get();
    }
    private void awaitJs(String predicate)throws Exception {
        long until=SystemClock.elapsedRealtime()+10000;
        do {if("true".equals(evaluateValue(predicate)))return;SystemClock.sleep(30);}while(SystemClock.elapsedRealtime()<until);
        fail("Fixture condition: "+predicate);
    }
    private void shell(String command)throws Exception {
        try(ParcelFileDescriptor descriptor=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
            java.io.InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            byte[] bytes=new byte[256];while(input.read(bytes)!=-1) { /* drain only fixture-shell completion */ }
        }
    }
    private static String page(String body){return "<!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'>"+
        "<style>body{margin:0}article{box-sizing:border-box;width:100vw;height:65vh;padding:20px;background:#fff}a{display:block}</style>"+body;}
    private static String card(String id,String text){return "<article data-testid='tweet' id='p"+id+"'><a href='https://x.com/alice/status/"+id+"'><time datetime='2026-11-01T00:00:00Z'>date</time></a><div data-testid='tweetText'>"+text+"</div></article>";}
    private XsTimelineBuffer.Entry awaitBuffer(String query)throws Exception {
        long until=SystemClock.elapsedRealtime()+10000;
        do{java.util.List<XsTimelineBuffer.Entry> entries=timeline.list(LIBRARY,query,null,10,0);if(!entries.isEmpty())return entries.get(0);SystemClock.sleep(30);}while(SystemClock.elapsedRealtime()<until);
        throw new AssertionError("Temporary fixture not captured: "+query);
    }
    @Test public void independentBufferFindsQuickPartialCardsAndPriorRefreshBatchesWithoutHistory()throws Exception {
        timeline=XsTimelineBuffer.get(app);timeline.configure(true,48,100);
        scenario.onActivity(activity->{NativePageCaptureController capture=controller.get();capture.setConsent(false,false,3000);capture.setBufferStore(timeline);capture.setBufferConsent(true);});
        String partial=card("140","quick partial offline body").replace("<article ","<article style='height:100vh;transform:translateY(50vh)' ");
        load("https://x.com/home","buffer-first-document",false,page("<main>"+partial+"</main>"));
        XsTimelineBuffer.Entry first=awaitBuffer("quick partial");assertEquals(SnapshotPolicy.Quality.PARTIAL,first.quality);
        assertEquals("quick partial offline body",first.body);
        evaluate("document.querySelector('article').style.display='none'");
        assertEquals(1,timeline.list(LIBRARY,"offline",null,20,0).size());assertEquals(1,timeline.batches(LIBRARY).size());
        scenario.onActivity(activity->controller.get().refreshed());
        evaluate("document.querySelector('main').innerHTML="+org.json.JSONObject.quote(card("141","after refresh offline body")));
        assertEquals("native_refresh",awaitBuffer("after refresh").batchReason);assertEquals(2,timeline.batches(LIBRARY).size());
        assertEquals("140",timeline.list(LIBRARY,"quick partial",first.batchId,20,0).get(0).postId);
        evaluate("document.querySelector('main').outerHTML="+org.json.JSONObject.quote("<main>"+card("142","suspected structural refresh")+"</main>"));
        assertEquals("suspected_refresh",awaitBuffer("suspected structural").batchReason);
        long dropped=timeline.stats(LIBRARY).dropped;
        scenario.onActivity(activity->{for(int i=0;i<512;i++)controller.get().refreshed();});
        long until=SystemClock.elapsedRealtime()+5000;
        while(timeline.stats(LIBRARY).dropped==dropped&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(30);
        assertTrue("Native admission limits reach the coverage UI's persistent counter",timeline.stats(LIBRARY).dropped>dropped);
        scenario.onActivity(activity->controller.get().pause());
        CountDownLatch drained=new CountDownLatch(1);XsLibraryWrites.submit(LIBRARY,drained::countDown);assertTrue(drained.await(5,TimeUnit.SECONDS));
        try(Cursor c=db.timeline(LIBRARY,null,10,"OPEN_POST")){assertEquals(0,c.getCount());}
        try(Cursor c=db.timeline(LIBRARY,null,10,"FEED_VISIBLE")){assertEquals(0,c.getCount());}
    }
    @Test public void preClearNativeReadCannotAcquireFreshBufferAuthorityAndFeedTicketsSurvive()throws Exception {
        timeline=XsTimelineBuffer.get(app);timeline.configure(true,48,100);
        feedSaved.set(new CountDownLatch(1));
        scenario.onActivity(activity->{controller.get().setBufferStore(timeline);controller.get().setBufferConsent(true);controller.get().setConsent(false,true,1200);});
        load("https://x.com/home","buffer-clear-read-race",false,page(card("143","old callback canary")));
        assertTrue(feedSaved.get().await(8,TimeUnit.SECONDS));awaitBuffer("old callback");
        CaptureSession.Event feed=lastFeed.get();assertNotNull(feed);
        XsLibraryWrites.Ticket mediaTicket=XsLibraryWrites.ticket(LIBRARY);
        java.lang.reflect.Field inFlight=NativePageCaptureController.class.getDeclaredField("inFlight"),installed=NativePageCaptureController.class.getDeclaredField("installed");
        inFlight.setAccessible(true);installed.setAccessible(true);
        CountDownLatch cleared=new CountDownLatch(1),hidden=new CountDownLatch(1);AtomicReference<Boolean> started=new AtomicReference<>(false);
        long until=SystemClock.elapsedRealtime()+5000;
        do{
            scenario.onActivity(activity->{
                try{
                    NativePageCaptureController capture=controller.get();if(inFlight.getBoolean(capture)||!installed.getBoolean(capture))return;
                    // Dispatch a real evaluateJavascript read; its callback cannot run until
                    // this same UI turn has advanced the buffer-only clear generation.
                    capture.readNow();assertTrue(inFlight.getBoolean(capture));started.set(true);
                    timeline.clear(LIBRARY,ok->{assertTrue(ok);cleared.countDown();});
                    ((WebView)activity.findViewById(R.id.browser_view)).evaluateJavascript("document.getElementById('p143').style.display='none'",result->hidden.countDown());
                }catch(IllegalAccessException failure){throw new AssertionError(failure);}
            });
            if(!started.get())SystemClock.sleep(20);
        }while(!started.get()&&SystemClock.elapsedRealtime()<until);
        assertTrue("A real native read was in flight across clear",started.get());
        assertTrue(hidden.await(5,TimeUnit.SECONDS));assertTrue(cleared.await(5,TimeUnit.SECONDS));SystemClock.sleep(500);
        assertEquals("The old result must not revive the cleared card",0,timeline.list(LIBRARY,"old callback",null,10,0).size());
        assertTrue("Buffer clear preserves the shared media writer ticket",XsLibraryWrites.isCurrent(mediaTicket));
        assertTrue("Buffer clear does not revoke an accepted qualified feed event",controller.get().isCurrent(feed));
        evaluate("document.getElementById('p143').innerHTML="+org.json.JSONObject.quote("<a href='https://x.com/alice/status/144'><time datetime='2026-11-01T00:00:00Z'>date</time></a><div data-testid='tweetText'>fresh callback canary</div>")+";document.getElementById('p143').style.display='block'");
        assertEquals("144",awaitBuffer("fresh callback").postId);
    }

    @Test public void immediateOpenLateBodyReloadAndQuickReturn()throws Exception {
        openSaved.set(new CountDownLatch(1));
        load("https://x.com/alice/status/123","native-open-1",true,page("<div id='pending'></div>"));
        assertTrue("Open is saved before any body/dwell",openSaved.get().await(5,TimeUnit.SECONDS));
        try(Cursor c=db.timeline(LIBRARY,null,10,"OPEN_POST")){assertEquals(1,c.getCount());assertTrue(c.moveToFirst());assertTrue(c.isNull(5));}
        bodySaved.set(new CountDownLatch(1));
        evaluate("document.getElementById('pending').innerHTML="+org.json.JSONObject.quote(card("123","延迟正文 offline")));
        assertTrue(bodySaved.get().await(10,TimeUnit.SECONDS));
        ArchiveSearch.Filter q=new ArchiveSearch.Filter();q.keyword="延迟正文";
        try(Cursor c=db.search(LIBRARY,q)){assertEquals(1,c.getCount());}
        load("https://x.com/alice/status/123","native-open-1",false,page(card("123","延迟正文 offline")));
        try(Cursor c=db.timeline(LIBRARY,null,10,"OPEN_POST")){assertEquals(1,c.getCount());}
        openSaved.set(new CountDownLatch(1));
        // Both successful navigations use actual WebView callbacks, with no dwell/body wait.
        // The same-UI-turn queued-write race is covered separately in CaptureSessionTest.
        load("https://x.com/alice/status/124","native-open-2",true,page("<div>quick</div>"));
        load("https://x.com/home","native-return",false,page("<div>timeline</div>"));
        assertTrue(openSaved.get().await(5,TimeUnit.SECONDS));
        try(Cursor c=db.timeline(LIBRARY,null,10,"OPEN_POST")){assertEquals(2,c.getCount());}
    }
    @Test public void canonicalDetailsEnrichDelayedBodiesWithoutAnotherOpen()throws Exception {
        openSaved.set(new CountDownLatch(1));
        load("https://x.com/i/web/status/125","native-canonical",true,page("<div id='pending'></div>"));
        assertTrue(openSaved.get().await(5,TimeUnit.SECONDS));
        bodySaved.set(new CountDownLatch(1));
        evaluate("document.getElementById('pending').innerHTML="+org.json.JSONObject.quote(card("125","规范链接正文").replace("/alice/status/","/i/web/status/")));
        assertTrue("Canonical detail enriches its original open",bodySaved.get().await(10,TimeUnit.SECONDS));
        ArchiveSearch.Filter q=new ArchiveSearch.Filter();q.keyword="规范链接正文";
        try(Cursor c=db.search(LIBRARY,q)){assertEquals(1,c.getCount());}
        try(Cursor c=db.timeline(LIBRARY,null,10,"OPEN_POST")){assertEquals(1,c.getCount());}
    }
    /** Fence by the new post's persisted native exposure, not a shared latch that departure can release. */
    private String[] awaitPersistedFeed(String postId)throws Exception {
        long until=SystemClock.elapsedRealtime()+8000;
        do{
            try(Cursor c=db.getReadableDatabase().rawQuery("SELECT source,event_key,duration_ms,threshold_ms FROM history_events WHERE account_id=? AND post_id=? AND event_type='FEED_VISIBLE'",new String[]{LIBRARY,postId})){
                if(c.moveToFirst()){
                    assertEquals("One native exposure for fixture post "+postId,1,c.getCount());
                    assertNotNull(c.getString(1));assertTrue(c.getLong(2)>=1200);assertEquals(1200,c.getLong(3));
                    return new String[]{c.getString(0),c.getString(1)};
                }
            }
            SystemClock.sleep(30);
        }while(SystemClock.elapsedRealtime()<until);
        CaptureSession.Event observed=lastFeed.get();
        throw new AssertionError("No qualified persisted exposure for post "+postId+"; last native post="+(observed==null?"none":dev.forcemind.xs.core.PostUrls.extractPostId(observed.url))+" source="+(observed==null?"none":observed.source));
    }
    private void assertNoPersistedFeedDuring(String postId,long durationMs)throws Exception {
        long until=SystemClock.elapsedRealtime()+durationMs;
        do{
            try(Cursor c=db.getReadableDatabase().rawQuery("SELECT source FROM history_events WHERE account_id=? AND post_id=? AND event_type='FEED_VISIBLE'",new String[]{LIBRARY,postId})){
                if(c.moveToFirst())fail("Unsupported fixture post "+postId+" acquired source "+c.getString(0));
            }
            SystemClock.sleep(30);
        }while(SystemClock.elapsedRealtime()<until);
    }
    @Test public void actualFeedRoutesAndNativeContextsDetermineExposureSource()throws Exception {
        scenario.onActivity(activity->{controller.get().setConsent(false,true,1200);controller.get().setFeedContext("home_following");});
        load("https://x.com/home?next=/search","native-source-home",false,page(card("126","home query")));
        String[] home=awaitPersistedFeed("126");assertEquals("home_following",home[0]);
        load("https://x.com/search?q=/home","native-source-search",false,page(card("127","search route")));
        String[] search=awaitPersistedFeed("127");assertEquals("search_results",search[0]);
        assertNotEquals("Search must qualify a different native exposure",home[1],search[1]);
        load("https://x.com/explore","native-source-explore",false,page(card("128","unsupported explore source")));
        assertNoPersistedFeedDuring("128",1800);
        scenario.onActivity(activity->controller.get().setFeedContext(null));
        load("https://x.com/home","native-source-unknown",false,page(card("129","unknown home source")));
        assertNoPersistedFeedDuring("129",1800);
        CountDownLatch drained=new CountDownLatch(1);XsLibraryWrites.submit(LIBRARY,drained::countDown);assertTrue(drained.await(5,TimeUnit.SECONDS));
        try(Cursor c=db.timeline(LIBRARY,null,10,"FEED_VISIBLE")){assertEquals(2,c.getCount());}
    }

    @Test public void noClickDwellIsMeasuredAndOfflineNeverOpenedSearchWorks()throws Exception {
        timeline=XsTimelineBuffer.get(app);timeline.configure(true,48,100);
        scenario.onActivity(activity->{controller.get().setBufferStore(timeline);controller.get().setBufferConsent(true);});
        feedSaved.set(new CountDownLatch(1));
        load("https://x.com/home","native-feed",false,page(card("456","停留正文 target")+"<div id='short' style='display:none'>"+card("789","一秒正文 skipped")+"</div>"));
        assertTrue("Default three-second continuous dwell",feedSaved.get().await(12,TimeUnit.SECONDS));
        SystemClock.sleep(500); // Allow the same event to update beyond qualification.
        evaluate("document.getElementById('p456').style.display='none';document.getElementById('short').style.display='block'");
        SystemClock.sleep(1000);
        evaluate("document.getElementById('short').style.display='none'");
        scenario.onActivity(activity->controller.get().pause());
        CountDownLatch drained=new CountDownLatch(1);XsLibraryWrites.submit(LIBRARY,drained::countDown);assertTrue(drained.await(5,TimeUnit.SECONDS));
        ArchiveSearch.Filter q=new ArchiveSearch.Filter();q.keyword="停留正文";q.minDwellMs=3000L;q.neverOpened=true;
        try(Cursor c=db.search(LIBRARY,q)){assertEquals(1,c.getCount());assertTrue(c.moveToFirst());assertEquals(0,c.getInt(7));}
        try(Cursor c=db.timeline(LIBRARY,null,10,"FEED_VISIBLE")){assertEquals(1,c.getCount());assertTrue(c.moveToFirst());assertTrue(c.getLong(c.getColumnIndexOrThrow("duration_ms"))>=3000);}
        q.keyword="一秒正文";q.minDwellMs=null;
        try(Cursor c=db.search(LIBRARY,q)){assertEquals(0,c.getCount());}
        assertEquals("Both consents enabled: the sustained card enters temporary buffer too",1,timeline.list(LIBRARY,"停留正文",null,10,0).size());
        assertEquals("Both consents enabled: the one-second card enters only temporary buffer",1,timeline.list(LIBRARY,"一秒正文",null,10,0).size());
        try(Cursor c=db.timeline(LIBRARY,null,10,"OPEN_POST")){assertEquals(0,c.getCount());}
    }
    @Test public void overlayAndBackgroundBreakContinuousIntervals()throws Exception {
        load("https://x.com/home","native-boundary",false,page(card("900","边界正文")));
        SystemClock.sleep(1800);
        scenario.onActivity(activity->controller.get().setOverlay(true));
        SystemClock.sleep(1700);
        scenario.onActivity(activity->controller.get().setOverlay(false));
        SystemClock.sleep(1800);
        scenario.onActivity(activity->controller.get().pause());
        SystemClock.sleep(1700);
        scenario.onActivity(activity->controller.get().resume());
        SystemClock.sleep(1800);
        scenario.onActivity(activity->controller.get().pause());
        try(Cursor c=db.timeline(LIBRARY,null,10,"FEED_VISIBLE")){assertEquals(0,c.getCount());}
    }
    @Test public void pauseOverlayAndDestroyStopPageWorkAndOldConsentCannotRevive()throws Exception {
        openSaved.set(new CountDownLatch(1));
        load("https://x.com/alice/status/950","native-admission",true,page(card("950","admission body")));
        assertTrue(openSaved.get().await(5,TimeUnit.SECONDS));
        CaptureSession.Event oldOpen=lastOpen.get();assertNotNull(oldOpen);
        scenario.onActivity(activity->{controller.get().setConsent(false,false,1200);controller.get().setConsent(true,true,1200);});
        assertFalse("An old admission stays revoked after re-enabling",controller.get().isCurrent(oldOpen));
        feedSaved.set(new CountDownLatch(1));
        String ticker="<script>window.__fixtureTicks=0;var originalInterval=window.setInterval;"+
            "window.setInterval=function(fn,ms){return originalInterval(function(){window.__fixtureTicks++;fn();},ms)};</script>";
        load("https://x.com/home","native-work-budget",false,page(ticker+card("951","budget body")));
        awaitJs("window.__xsObserver&&window.__xsObserver.read().cards.length===1");
        assertTrue(feedSaved.get().await(8,TimeUnit.SECONDS));CaptureSession.Event oldFeed=lastFeed.get();assertNotNull(oldFeed);
        scenario.onActivity(activity->controller.get().setOverlay(true));
        String stopped=evaluateValue("window.__fixtureTicks");SystemClock.sleep(600);
        assertEquals(stopped,evaluateValue("window.__fixtureTicks"));
        assertEquals("0",evaluateValue("window.__xsObserver.read().cards.length"));
        scenario.onActivity(activity->controller.get().setOverlay(false));
        awaitJs("window.__xsObserver.read().cards.length===1");
        scenario.onActivity(activity->controller.get().pause());
        stopped=evaluateValue("window.__fixtureTicks");SystemClock.sleep(600);
        assertEquals(stopped,evaluateValue("window.__fixtureTicks"));
        evaluate("document.getElementById('p951').style.opacity='0';window.dispatchEvent(new Event('scroll'))");
        assertEquals("0",evaluateValue("window.__xsObserver.read().cards.length"));
        // Exercise the exact native-owned transport expression, before WebView marshals IPC.
        java.lang.reflect.Field readField=NativePageCaptureController.class.getDeclaredField("READ");readField.setAccessible(true);
        String boundedRead=(String)readField.get(null);
        evaluate("window.__originalRead=window.__xsObserver.read;window.__xsObserver.read=function(){return {v:1,url:location.href,cards:[],extra:'x'.repeat(70000)}}");
        assertEquals("null",evaluateValue(boundedRead));
        evaluate("window.__xsObserver.read=window.__originalRead;delete window.__originalRead");
        scenario.onActivity(activity->{controller.get().setConsent(false,false,1200);controller.get().setConsent(true,true,1200);controller.get().resume();});
        assertFalse(controller.get().isCurrent(oldFeed));
        evaluate("document.getElementById('p951').style.opacity='1'");
        awaitJs("window.__xsObserver.read().cards.length===1");
        scenario.onActivity(activity->controller.get().destroy());
        stopped=evaluateValue("window.__fixtureTicks");
        scenario.onActivity(activity->{controller.get().resume();controller.get().setConsent(true,true,1200);controller.get().pageReady();});
        SystemClock.sleep(600);assertEquals(stopped,evaluateValue("window.__fixtureTicks"));
        assertFalse(controller.get().isCurrent(oldFeed));assertFalse(controller.get().isCurrent(oldOpen));
    }
    @Test public void transparentParentsAndVirtualRebindsNeverCreateNativeOpens()throws Exception {
        load("https://x.com/home","native-virtual",false,page("<div id='parent' style='opacity:0'>"+card("960","virtual body")+"</div>"));
        awaitJs("!!window.__xsObserver");assertEquals("0",evaluateValue("window.__xsObserver.read().cards.length"));
        evaluate("document.getElementById('parent').style.opacity='1'");
        awaitJs("window.__xsObserver.read().cards.length===1");
        assertEquals("true",evaluateValue("(function(){var p=document.getElementById('p960'),a=p.querySelector('a'),prev=null;"+
            "for(var i=0;i<256;i++){a.href='https://x.com/alice/status/'+(10000+i);var first=window.__xsObserver.read().cards;"+
            "if(first.length!==1||first[0].run===prev)return false;prev=first[0].run;"+
            "p.outerHTML=p.outerHTML;p=document.querySelector('article');a=p.querySelector('a');"+
            "if(window.__xsObserver.read().cards[0].run!==prev)return false;}return true})()"));
        scenario.onActivity(activity->controller.get().pause());
        try(Cursor c=db.timeline(LIBRARY,null,10,"OPEN_POST")){assertEquals(0,c.getCount());}
    }
    @Test public void screenOffBreaksDwellAndStopsTheFixtureObserver()throws Exception {
        scenario.onActivity(activity->controller.get().setConsent(false,true,3000));
        load("https://x.com/home","native-screen",false,page(card("970","screen boundary")));
        awaitJs("window.__xsObserver&&window.__xsObserver.read().cards.length===1");
        SystemClock.sleep(1300);
        PowerManager power=(PowerManager)app.getSystemService(Context.POWER_SERVICE);
        try {
            shell("input keyevent 223");
            assertFalse("The emulator screen actually turns off",power.isInteractive());
            SystemClock.sleep(1800);
        } finally {shell("input keyevent 224");}
        // This synthetic emulator has no user lock credential; no unlock/authentication flow is automated.
        assertTrue(power.isInteractive());
        SystemClock.sleep(1300);scenario.onActivity(activity->controller.get().pause());
        CountDownLatch drained=new CountDownLatch(1);XsLibraryWrites.submit(LIBRARY,drained::countDown);assertTrue(drained.await(5,TimeUnit.SECONDS));
        try(Cursor c=db.timeline(LIBRARY,null,10,"FEED_VISIBLE")){assertEquals(0,c.getCount());}
    }

    @Test public void nativeTextEvidenceKeepsPartialAndAcceptsNewerShortCompleteBody()throws Exception {
        bodySaved.set(new CountDownLatch(1));
        load("https://x.com/alice/status/980","native-completeness",true,page(card("980","original complete body with additional words")));
        assertTrue(bodySaved.get().await(10,TimeUnit.SECONDS));
        try(Cursor c=db.getReadableDatabase().rawQuery("SELECT v.quality FROM posts p JOIN snapshot_versions v ON v.account_id=p.account_id AND v.post_id=p.post_id AND v.snapshot_key=p.current_snapshot_key WHERE p.account_id=? AND p.post_id='980'",new String[]{LIBRARY})) {
            assertTrue(c.moveToFirst());assertEquals("COMPLETE",c.getString(0));
        }
        bodySaved.set(new CountDownLatch(1));
        evaluate("var body=document.querySelector('[data-testid=tweetText]');body.style.display='-webkit-box';body.style.webkitBoxOrient='vertical';body.style.webkitLineClamp='2';body.style.overflow='hidden';body.innerText='short edited body'");
        assertTrue(bodySaved.get().await(10,TimeUnit.SECONDS));
        try(Cursor c=db.getReadableDatabase().rawQuery("SELECT body FROM posts WHERE account_id=? AND post_id='980'",new String[]{LIBRARY})) {
            assertTrue(c.moveToFirst());assertEquals("original complete body with additional words",c.getString(0));
        }
        bodySaved.set(new CountDownLatch(1));
        // Restore the original absent style attribute exactly, including prefixed box/clamp
        // properties, rather than leaving part of the collapsed layout installed.
        evaluate("document.querySelector('[data-testid=tweetText]').removeAttribute('style')");
        if(!bodySaved.get().await(10,TimeUnit.SECONDS))
            fail("Restored visible body evidence: "+evaluateValue("JSON.stringify(window.__xsObserver.read())"));
        try(Cursor c=db.getReadableDatabase().rawQuery("SELECT p.body,v.quality,v.captured_at_ms FROM posts p JOIN snapshot_versions v ON v.account_id=p.account_id AND v.post_id=p.post_id AND v.snapshot_key=p.current_snapshot_key WHERE p.account_id=? AND p.post_id='980'",new String[]{LIBRARY})) {
            assertTrue(c.moveToFirst());assertEquals("short edited body",c.getString(0));assertEquals("COMPLETE",c.getString(1));assertTrue(c.getLong(2)>0);
        }
        try(Cursor c=db.getReadableDatabase().rawQuery("SELECT count(*) FROM snapshot_versions WHERE account_id=? AND post_id='980'",new String[]{LIBRARY})) {
            assertTrue(c.moveToFirst());assertEquals(3,c.getInt(0));
        }
    }

}

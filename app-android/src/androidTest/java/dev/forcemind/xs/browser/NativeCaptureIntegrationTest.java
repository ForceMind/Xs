package dev.forcemind.xs.browser;

import android.content.Context;
import android.database.Cursor;
import android.os.SystemClock;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.forcemind.xs.android.NativePageCaptureController;
import dev.forcemind.xs.android.XsArchiveDatabase;
import dev.forcemind.xs.android.XsLibraryWrites;
import dev.forcemind.xs.core.ArchiveSearch;
import dev.forcemind.xs.core.CaptureSession;
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

    @Before public void setup() {
        app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        app.getSharedPreferences("browser.BrowserActivity",Context.MODE_PRIVATE).edit().clear().commit();
        app.deleteDatabase("xs-local-history.db");db=new XsArchiveDatabase(app);
        scenario=ActivityScenario.launch(BrowserActivity.class);
        scenario.onActivity(activity->{
            WebView web=activity.findViewById(R.id.browser_view);
            NativePageCaptureController capture=new NativePageCaptureController(web,LIBRARY,new CaptureSession.Sink(){
                private void write(CaptureSession.Event e,Runnable operation,AtomicReference<CountDownLatch> done){
                    XsLibraryWrites.Ticket ticket=XsLibraryWrites.ticket(LIBRARY);
                    XsLibraryWrites.submit(ticket,()->{
                        if(controller.get().isCurrent(e)) {
                            operation.run();CountDownLatch latch=done.get();if(latch!=null)latch.countDown();
                        }
                    });
                }
                public void opened(CaptureSession.Event e){write(e,()->db.recordObservedOpen(LIBRARY,e.url,e.author,e.body,e.publishedAtMs,e.wallTimeMs,e.navigationId),openSaved);}
                public void enriched(CaptureSession.Event e){write(e,()->db.enrichSnapshot(LIBRARY,e.url,e.author,e.body,e.publishedAtMs),bodySaved);}
                public void feed(CaptureSession.Event e){write(e,()->db.recordMeasuredFeed(LIBRARY,e.url,e.source,e.author,e.body,e.publishedAtMs,e.wallTimeMs,e.exposureId,e.durationMs,e.thresholdMs),feedSaved);}
            });
            controller.set(capture);capture.setConsent(true,true,3000);capture.resume();
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
        assertTrue(drained.await(10,TimeUnit.SECONDS));db.close();app.deleteDatabase("xs-local-history.db");
    }
    private void load(String url,String nativeId,boolean userNavigation,String content)throws Exception {
        html.set(content);navId.set(nativeId);deliberate=userNavigation;
        CountDownLatch latch=new CountDownLatch(1);loaded.set(latch);
        scenario.onActivity(activity->((WebView)activity.findViewById(R.id.browser_view)).loadUrl(url));
        assertTrue("Fixture page load",latch.await(20,TimeUnit.SECONDS));
    }
    private void evaluate(String js)throws Exception {
        CountDownLatch latch=new CountDownLatch(1);
        scenario.onActivity(activity->((WebView)activity.findViewById(R.id.browser_view)).evaluateJavascript(js,result->latch.countDown()));
        assertTrue(latch.await(10,TimeUnit.SECONDS));
    }
    private static String page(String body){return "<!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'>"+
        "<style>body{margin:0}article{box-sizing:border-box;width:100vw;height:65vh;padding:20px;background:#fff}a{display:block}</style>"+body;}
    private static String card(String id,String text){return "<article data-testid='tweet' id='p"+id+"'><a href='https://x.com/alice/status/"+id+"'><time datetime='2026-11-01T00:00:00Z'>date</time></a><div data-testid='tweetText'>"+text+"</div></article>";}
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
    @Test public void noClickDwellIsMeasuredAndOfflineNeverOpenedSearchWorks()throws Exception {
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
}

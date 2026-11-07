package dev.forcemind.xs.browser;

import android.content.Context;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.forcemind.xs.android.NativePageCaptureController;
import dev.forcemind.xs.android.NativePageCleanupController;
import dev.forcemind.xs.android.XsLibrarySettings;
import dev.forcemind.xs.core.CaptureSession;
import dev.forcemind.xs.core.StrictJson;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Actual System WebView. Every HTTPS request is intercepted with local synthetic HTML. */
@RunWith(AndroidJUnit4.class)
public final class PageCleanupIntegrationTest {
    private static final String HOME="https://x.com/home";
    private ActivityScenario<BrowserActivity> scenario;
    private final AtomicReference<NativePageCleanupController> cleanup=new AtomicReference<>();
    private final AtomicReference<NativePageCaptureController> capture=new AtomicReference<>();
    private final AtomicReference<CountDownLatch> loaded=new AtomicReference<>();
    private final AtomicReference<String> html=new AtomicReference<>("");
    private Context app;

    @Before public void setup(){
        app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        app.getSharedPreferences("browser.BrowserActivity",Context.MODE_PRIVATE).edit().clear().commit();
        app.getSharedPreferences("xs_library_settings",Context.MODE_PRIVATE).edit().clear().commit();
        scenario=ActivityScenario.launch(BrowserActivity.class);
        scenario.onActivity(activity->{
            WebView web=activity.findViewById(R.id.browser_view);
            NativePageCleanupController controller=new NativePageCleanupController(web);
            cleanup.set(controller);controller.resume();
            web.setWebViewClient(activity.new Client(){
                @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                    return new WebResourceResponse("text/html","UTF-8",new ByteArrayInputStream(html.get().getBytes(StandardCharsets.UTF_8)));
                }
                @Override public void onPageFinished(WebView view,String url){
                    super.onPageFinished(view,url);controller.navigation(url);controller.pageReady();
                    CountDownLatch done=loaded.get();if(done!=null)done.countDown();
                }
            });
        });
    }
    @After public void teardown(){
        if(scenario!=null){scenario.onActivity(activity->{if(capture.get()!=null)capture.get().destroy();cleanup.get().destroy();});scenario.close();}
        app.getSharedPreferences("xs_library_settings",Context.MODE_PRIVATE).edit().clear().commit();
    }
    private static String page(String body){return "<!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'>"+
        "<style>body{margin:0}article{box-sizing:border-box;width:100vw;height:40vh;padding:8px;background:#fff}a{display:block}</style>"+body;}
    private static String article(String id,String text){return "<article data-testid='tweet' id='p"+id+"'><a href='/alice/status/"+id+"'><time datetime='2026-11-01T00:00:00Z'>now</time></a><div data-testid='tweetText'>"+text+"</div></article>";}
    private static String ad(){return "<div id='ad' data-testid='placementTracking' style='display:block!important;color:red'>"+
        article("701","ad body <span data-testid='promotedIndicator'>Promoted</span>")+"</div>";}
    private static String premium(){return "<aside id='premium' role='complementary' data-testid='premiumPromo' style='display:grid!important;color:green'>"+
        "<h2 data-testid='premiumPromoTitle'>Optional offer</h2><a href='/i/premium_sign_up'>Details</a></aside>";}
    private static String recommendations(){return "<aside id='recommend' role='complementary' data-testid='whoToFollow'>"+
        "<h2 data-testid='whoToFollowHeading'>Suggestions</h2><div data-testid='UserCell'><a href='/bob'>Bob</a></div></aside>";}
    private void load(String url,String content)throws Exception {
        html.set(content);CountDownLatch done=new CountDownLatch(1);loaded.set(done);
        scenario.onActivity(activity->((WebView)activity.findViewById(R.id.browser_view)).loadUrl(url));
        assertTrue("Synthetic page load",done.await(20,TimeUnit.SECONDS));
    }
    private Object evaluate(String js)throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicReference<String> raw=new AtomicReference<>();
        scenario.onActivity(activity->((WebView)activity.findViewById(R.id.browser_view)).evaluateJavascript(js,result->{raw.set(result);done.countDown();}));
        assertTrue("Fixture JavaScript callback",done.await(5,TimeUnit.SECONDS));return StrictJson.parse(raw.get());
    }
    private void awaitJs(String condition)throws Exception {
        long deadline=android.os.SystemClock.elapsedRealtime()+6000;
        do {if(Boolean.TRUE.equals(evaluate("Boolean("+condition+")")))return;Thread.sleep(30);}while(android.os.SystemClock.elapsedRealtime()<deadline);
        fail("Synthetic cleanup condition: "+condition);
    }
    private void options(boolean ads,boolean subscription,boolean recommendations){scenario.onActivity(a->cleanup.get().setOptions(ads,subscription,recommendations));}

    @Test public void independentDefaultsAndExactStyleRestorationKeepOrdinaryContent()throws Exception {
        assertFalse(NativePageCleanupController.LIVE_SITE_ENABLED);
        assertFalse(XsLibrarySettings.isAdCleanupEnabled(app));assertFalse(XsLibrarySettings.isSubscriptionCleanupEnabled(app));assertFalse(XsLibrarySettings.isRecommendationCleanupEnabled(app));
        XsLibrarySettings.setAdCleanupEnabled(app,true);assertTrue(XsLibrarySettings.isAdCleanupEnabled(app));assertFalse(XsLibrarySettings.isSubscriptionCleanupEnabled(app));assertFalse(XsLibrarySettings.isRecommendationCleanupEnabled(app));
        XsLibrarySettings.setSubscriptionCleanupEnabled(app,true);XsLibrarySettings.setAdCleanupEnabled(app,false);assertFalse(XsLibrarySettings.isAdCleanupEnabled(app));assertTrue(XsLibrarySettings.isSubscriptionCleanupEnabled(app));assertFalse(XsLibrarySettings.isRecommendationCleanupEnabled(app));
        load(HOME,page(ad()+article("700","ordinary <button id='button' onclick='window.clicked=(window.clicked||0)+1'>Click</button>")+premium()+recommendations()));
        awaitJs("window.__xsCleanup&&window.__xsCleanup.status().hidden===0");
        assertEquals("block",evaluate("document.getElementById('ad').style.getPropertyValue('display')"));
        options(true,false,false);awaitJs("document.getElementById('ad').style.getPropertyValue('display')==='none'");
        assertEquals("grid",evaluate("document.getElementById('premium').style.getPropertyValue('display')"));
        assertEquals("",evaluate("document.getElementById('p700').style.getPropertyValue('display')"));
        options(false,true,false);awaitJs("document.getElementById('premium').style.getPropertyValue('display')==='none'");
        assertEquals("block",evaluate("document.getElementById('ad').style.getPropertyValue('display')"));
        assertEquals("important",evaluate("document.getElementById('ad').style.getPropertyPriority('display')"));
        evaluate("document.getElementById('premium').style.setProperty('color','blue')");
        options(false,false,true);awaitJs("document.getElementById('recommend').style.getPropertyValue('display')==='none'");
        assertEquals("grid",evaluate("document.getElementById('premium').style.getPropertyValue('display')"));
        assertEquals("important",evaluate("document.getElementById('premium').style.getPropertyPriority('display')"));
        assertEquals("blue",evaluate("document.getElementById('premium').style.getPropertyValue('color')"));
        options(false,false,false);awaitJs("!document.getElementById('recommend').hasAttribute('style')");
        evaluate("document.getElementById('button').click()");assertEquals(Long.valueOf(1),evaluate("window.clicked"));
        assertEquals(Long.valueOf(1),evaluate("document.querySelectorAll('#p700').length"));
    }

    @Test public void unknownStructuresSensitiveSpaAndPaidPagesFailClosed()throws Exception {
        String unknown="<aside id='unknown' data-testid='premiumPromo'><p>Upgrade promotion without structural contract</p></aside>";
        String entitled=premium().replace("</aside>","<span data-testid='premiumEntitlement'>Existing paid feature</span></aside>");
        load(HOME,page(ad()+article("700","normal content")+entitled+recommendations()+unknown));
        options(true,true,true);awaitJs("document.getElementById('ad').style.getPropertyValue('display')==='none'");
        assertEquals("grid",evaluate("document.getElementById('premium').style.getPropertyValue('display')"));
        assertEquals("",evaluate("document.getElementById('unknown').style.getPropertyValue('display')"));
        evaluate("var input=document.createElement('input');input.id='sensitive';input.type='password';document.body.appendChild(input)");
        awaitJs("document.getElementById('ad').style.getPropertyValue('display')==='block'");
        evaluate("document.getElementById('sensitive').remove()");
        awaitJs("document.getElementById('ad').style.getPropertyValue('display')==='none'");
        evaluate("history.pushState({},'', '/settings/security');dispatchEvent(new PopStateEvent('popstate'))");
        awaitJs("document.getElementById('ad').style.getPropertyValue('display')==='block'");
        load("https://x.com/i/premium_sign_up",page(ad()+premium()+recommendations()));
        awaitJs("document.getElementById('ad').style.getPropertyValue('display')==='block'");
        assertEquals("grid",evaluate("document.getElementById('premium').style.getPropertyValue('display')"));
        load("https://x.com/messages",page(ad()+premium()));
        assertEquals("block",evaluate("document.getElementById('ad').style.getPropertyValue('display')"));
    }

    @Test public void nativePauseNavigationAndDestroyRestoreAndPreserveReadingAnchor()throws Exception {
        load(HOME,page("<div style='height:1500px'></div>"+ad()+article("700","reading anchor")+"<div style='height:2000px'></div>"));
        evaluate("window.scrollTo(0,1500+innerHeight*0.20)");
        final double before=((Number)evaluate("document.getElementById('p700').getBoundingClientRect().top")).doubleValue();
        assertTrue(before>0);
        options(true,false,false);awaitJs("document.getElementById('ad').style.getPropertyValue('display')==='none'");
        awaitJs("Math.abs(document.getElementById('p700').getBoundingClientRect().top-("+before+"))<3");
        scenario.onActivity(a->cleanup.get().setOverlay(true));awaitJs("document.getElementById('ad').style.getPropertyValue('display')==='block'");
        scenario.onActivity(a->cleanup.get().setOverlay(false));awaitJs("document.getElementById('ad').style.getPropertyValue('display')==='none'");
        scenario.onActivity(a->cleanup.get().pause());awaitJs("document.getElementById('ad').style.getPropertyValue('display')==='block'");
        awaitJs("Math.abs(document.getElementById('p700').getBoundingClientRect().top-("+before+"))<3");
        scenario.onActivity(a->cleanup.get().resume());awaitJs("document.getElementById('ad').style.getPropertyValue('display')==='none'");
        scenario.onActivity(a->{cleanup.get().navigation("https://x.com/search?q=next");cleanup.get().pageReady();});
        awaitJs("document.getElementById('ad').style.getPropertyValue('display')==='block'");
        scenario.onActivity(a->{cleanup.get().navigation(HOME);cleanup.get().pageReady();});
        awaitJs("document.getElementById('ad').style.getPropertyValue('display')==='none'");
        scenario.onActivity(a->cleanup.get().destroy());awaitJs("document.getElementById('ad').style.getPropertyValue('display')==='block'");
        scenario.onActivity(a->{cleanup.get().resume();cleanup.get().setOptions(true,true,true);});
        assertEquals("block",evaluate("document.getElementById('ad').style.getPropertyValue('display')"));
    }

    @Test public void hiddenPromotedCardCannotProduceFeedExposure()throws Exception {
        load(HOME,page(article("700","ordinary feed")+ad()));
        CountDownLatch ordinary=new CountDownLatch(1);CopyOnWriteArrayList<CaptureSession.Event> feeds=new CopyOnWriteArrayList<>();
        scenario.onActivity(activity->{
            WebView web=activity.findViewById(R.id.browser_view);
            NativePageCaptureController observer=new NativePageCaptureController(web,"cleanup-feed-fixture",new CaptureSession.Sink(){
                public void opened(CaptureSession.Event event){fail("No deliberate post open in feed fixture");}
                public void enriched(CaptureSession.Event event){fail("No detail snapshot in feed fixture");}
                public void feed(CaptureSession.Event event){feeds.add(event);if(event.url.endsWith("/700"))ordinary.countDown();}
            });
            capture.set(observer);observer.setConsent(false,false,1200);observer.resume();observer.navigation(HOME,"fixture-feed",false);observer.pageReady();
        });
        awaitJs("window.__xsObserver&&window.__xsObserver.read().cards.length===2");
        options(true,false,false);awaitJs("document.getElementById('ad').style.getPropertyValue('display')==='none'");
        awaitJs("window.__xsObserver.read().cards.length===1");
        scenario.onActivity(activity->capture.get().setConsent(false,true,1200));
        assertTrue("Ordinary visible post reaches native dwell threshold",ordinary.await(12,TimeUnit.SECONDS));
        scenario.onActivity(activity->capture.get().pause());
        assertFalse(feeds.isEmpty());for(CaptureSession.Event event:feeds){assertTrue(event.url.endsWith("/700"));assertTrue(event.durationMs>=1200);}
    }
}

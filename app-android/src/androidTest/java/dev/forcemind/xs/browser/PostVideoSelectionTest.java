package dev.forcemind.xs.browser;

import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Only intercepted synthetic DOM. This does not validate the current live X layout. */
@RunWith(AndroidJUnit4.class)
public final class PostVideoSelectionTest {
    @Test public void currentPostExcludesQuotedProtectedAndOtherPostVideos()throws Exception {
        try(ActivityScenario<BrowserActivity> screen=ActivityScenario.launch(BrowserActivity.class)) {
            CountDownLatch loaded=new CountDownLatch(1);
            screen.onActivity(activity->{
                WebView web=activity.findViewById(R.id.browser_view);
                web.setWebViewClient(new WebViewClient(){
                    @Override public void onPageFinished(WebView view,String url){loaded.countDown();}
                    @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view,android.webkit.WebResourceRequest request){return new android.webkit.WebResourceResponse("text/plain","UTF-8",new java.io.ByteArrayInputStream(new byte[0]));}
                });
                web.loadDataWithBaseURL("https://x.com/demo/status/1234","<html><body><article data-testid='tweet'><a href='/demo/status/1234'><time>now</time></a>"+
                    video("primary","")+"<div data-testid='quoteTweet'>"+video("quoted","")+"</div>"+
                    video("protected","controlslist='nodownload'")+video("disabled","disablepictureinpicture")+
                    video("hidden","hidden")+
                    "</article><article data-testid='tweet'><a href='/other/status/9999'><time>later</time></a>"+video("other","")+"</article></body></html>","text/html","UTF-8",null);
            });
            assertTrue(loaded.await(20,TimeUnit.SECONDS));
            CountDownLatch read=new CountDownLatch(1);AtomicReference<String> raw=new AtomicReference<>();
            screen.onActivity(activity->((WebView)activity.findViewById(R.id.browser_view)).evaluateJavascript(PostVideoSelection.script("https://x.com/demo/status/1234"),value->{raw.set(value);read.countDown();}));
            assertTrue(read.await(10,TimeUnit.SECONDS));
            java.util.List<String> candidates=PostVideoSelection.parse("https://x.com/demo/status/1234",raw.get());
            assertEquals(1,candidates.size());assertTrue(candidates.get(0).contains("primary"));
        }
    }
    @Test public void rawListCannotTurnBlobManifestOrPrivateAddressesIntoVideo() {
        for(String url:new String[]{"blob:https://x.com/object","https://video.twimg.com/ext_tw_video/123/pu/pl/stream.m3u8","https://127.0.0.1/a.mp4","https://video.twimg.com/ext_tw_video/123/pu/vid/64x64/file.m4s"}) {
            String raw=org.json.JSONObject.quote(new org.json.JSONArray(java.util.Arrays.asList(url)).toString());
            try{PostVideoSelection.parse("https://x.com/demo/status/1234",raw);fail("Unsupported resource accepted");}
            catch(IllegalArgumentException expected){assertFalse(expected.getMessage().contains(url));}
        }
    }
    private static String video(String token,String attributes){return "<div data-testid='videoPlayer'><video preload='none' style='width:64px;height:64px' "+attributes+" src='https://video.twimg.com/ext_tw_video/1234/pu/vid/64x64/"+token+".mp4'></video></div>";}
}

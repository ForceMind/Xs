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

/** Actual WebView, intercepted synthetic post. No real X/CDN access. */
@RunWith(AndroidJUnit4.class)
public class PostImageSelectionTest {
    @Test public void selectedPostExcludesQuotedAndOtherPostImages() throws Exception {
        try (ActivityScenario<BrowserActivity> scenario=ActivityScenario.launch(BrowserActivity.class)) {
            CountDownLatch loaded=new CountDownLatch(1);
            scenario.onActivity(activity-> {
                WebView web=activity.findViewById(R.id.browser_view);
                web.setWebViewClient(new WebViewClient() {
                    @Override public void onPageFinished(WebView view,String url) { loaded.countDown(); }
                    @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view,
                            android.webkit.WebResourceRequest request) {
                        return new android.webkit.WebResourceResponse("text/plain","UTF-8",
                            new java.io.ByteArrayInputStream(new byte[0]));
                    }
                });
                String image="<div data-testid='tweetPhoto'><a href='/demo/status/%s/photo/1'>"+
                    "<img style='width:80px;height:80px' src='https://pbs.twimg.com/media/%s?format=png&amp;name=large'></a></div>";
                web.loadDataWithBaseURL("https://x.com/demo/status/1234",
                    "<html><body><article data-testid='tweet'><a href='/demo/status/1234'><time>now</time></a>"+
                    String.format(image,"1234","primary")+String.format(image,"8888","quoted")+
                    "</article><article data-testid='tweet'><a href='/demo/status/7777'><time>now</time></a>"+
                    String.format(image,"7777","other")+"</article></body></html>","text/html","UTF-8",null);
            });
            assertTrue(loaded.await(20,TimeUnit.SECONDS));
            CountDownLatch done=new CountDownLatch(1);
            AtomicReference<String> result=new AtomicReference<>();
            scenario.onActivity(activity-> ((WebView)activity.findViewById(R.id.browser_view))
                .evaluateJavascript(PostImageSelection.script("https://x.com/demo/status/1234"),raw->{result.set(raw);done.countDown();}));
            assertTrue(done.await(10,TimeUnit.SECONDS));
            java.util.List<String> selected=PostImageSelection.parse("https://x.com/demo/status/1234",result.get());
            assertEquals(1,selected.size());
            assertTrue(selected.get(0).contains("primary"));
        }
    }
    @Test public void untrustedImageListCannotChoosePrivateHostsOrExtraData() {
        String[] invalid={"[1]","\"[123]\"","\"[\\\"http://127.0.0.1/a.png\\\"]\"",
            "\"[\\\"https://pbs.twimg.com/profile_images/avatar.png\\\"]\"",
            "\"[]\" trailing", "\"[] trailing\""};
        for (String raw:invalid) {
            try { PostImageSelection.parse("https://x.com/demo/status/1234",raw);fail("Rejected candidate required"); }
            catch (IllegalArgumentException expected) { assertFalse(expected.getMessage().contains("127.0.0.1")); }
        }
    }
}

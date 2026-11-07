package dev.forcemind.xs.browser;

import android.content.Context;
import android.content.Intent;
import dev.forcemind.xs.android.XsLocalSpaces;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Synthetic only. No real X requests, accounts, content, or login claims. */
@RunWith(AndroidJUnit4.class)
public class BrowserContainerTest {
    @Before public void resetNotice() {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        app.getSharedPreferences("browser.BrowserActivity", Context.MODE_PRIVATE).edit().clear().commit();
    }
    @Test public void initialHostHasNoNavigationAndRestrictedSettings() {
        final boolean[] safeBrowsing={false};
        try (ActivityScenario<BrowserActivity> scenario = ActivityScenario.launch(BrowserActivity.class)) {
            scenario.onActivity(activity -> {
                WebView web = activity.findViewById(R.id.browser_view);
                assertNotNull(web);
                assertNull(web.getUrl());
                assertNotNull(activity.findViewById(R.id.open_x));
                WebSettings s = web.getSettings();
                assertTrue(s.getJavaScriptEnabled());
                assertTrue(s.getDomStorageEnabled());
                assertFalse(s.getAllowFileAccess());
                assertFalse(s.getAllowContentAccess());
                assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, s.getMixedContentMode());
                safeBrowsing[0]=s.getSafeBrowsingEnabled();
                if(safeBrowsing[0])assertTrue(s.getSafeBrowsingEnabled());
                else {
                    assertEquals(activity.getString(R.string.safe_browsing_unavailable),((TextView)activity.findViewById(R.id.browser_status)).getText().toString());
                    activity.findViewById(R.id.open_x).performClick();
                    assertNull("Unsupported provider must not navigate after notice acceptance",web.getUrl());
                    assertFalse(web.canGoBack());
                    assertEquals(activity.getString(R.string.safe_browsing_unavailable),((TextView)activity.findViewById(R.id.browser_status)).getText().toString());
                }
                assertTrue(s.getMediaPlaybackRequiresUserGesture());
                assertFalse(s.getJavaScriptCanOpenWindowsAutomatically());
                assertTrue((activity.getWindow().getAttributes().flags &
                    WindowManager.LayoutParams.FLAG_SECURE) != 0);
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertNotNull(activity.findViewById(R.id.browser_view));
                if(safeBrowsing[0])assertNotNull(activity.findViewById(R.id.open_x));
                else {
                    assertNull(activity.findViewById(R.id.open_x));
                    assertNull(((WebView)activity.findViewById(R.id.browser_view)).getUrl());
                    assertEquals(activity.getString(R.string.safe_browsing_unavailable),((TextView)activity.findViewById(R.id.browser_status)).getText().toString());
                }
            });
        }
    }
    @Test public void actualWebViewExecutesSyntheticPageWithoutNativeArchive() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        app.deleteDatabase("xs-local-history.db");
        try (ActivityScenario<BrowserActivity> scenario = ActivityScenario.launch(BrowserActivity.class)) {
            CountDownLatch loaded = new CountDownLatch(1);
            scenario.onActivity(activity -> {
                WebView web = activity.findViewById(R.id.browser_view);
                // Retain the production client/security configuration. HTML contains no subresources.
                web.setWebViewClient(activity.new Client() {
                    @Override public void onPageFinished(WebView view, String url) {
                        super.onPageFinished(view, url);
                        loaded.countDown();
                    }
                });
                web.loadDataWithBaseURL("https://x.com/", "<html><body><article id='fixture'>" +
                    "Synthetic Xs test · 离线夹具</article></body></html>", "text/html", "UTF-8", null);
            });
            assertTrue("WebView paint timed out", loaded.await(20, TimeUnit.SECONDS));
            CountDownLatch evaluated = new CountDownLatch(1);
            AtomicReference<String> result = new AtomicReference<>();
            scenario.onActivity(activity -> {
                WebView web = activity.findViewById(R.id.browser_view);
                web.evaluateJavascript("JSON.stringify({text:document.getElementById('fixture').textContent," +
                    "bridge:typeof window.XsNative})", value -> { result.set(value); evaluated.countDown(); });
            });
            assertTrue(evaluated.await(10, TimeUnit.SECONDS));
            assertTrue(result.get(), result.get().contains("Synthetic Xs test"));
            assertTrue(result.get(), result.get().contains("undefined"));
            assertFalse(java.util.Arrays.asList(app.databaseList()).contains("xs-local-history.db"));
        }
    }
    @Test public void configurationRecreationRetainsActualPageAndJavascriptState() throws Exception {
        try (ActivityScenario<BrowserActivity> scenario=ActivityScenario.launch(BrowserActivity.class)) {
            CountDownLatch loaded=new CountDownLatch(1);
            AtomicReference<WebView> original=new AtomicReference<>();
            scenario.onActivity(activity-> {
                WebView view=activity.findViewById(R.id.browser_view);
                original.set(view);
                view.setWebViewClient(activity.new Client() {
                    @Override public void onPageFinished(WebView web,String url) {
                        super.onPageFinished(web,url); loaded.countDown();
                    }
                });
                view.loadDataWithBaseURL("https://x.com/fixture/retained",
                    "<html><body><div id='state'>kept</div><script>window.fixtureState=37;</script></body></html>",
                    "text/html","UTF-8",null);
            });
            assertTrue(loaded.await(20,TimeUnit.SECONDS));
            scenario.recreate();
            CountDownLatch evaluated=new CountDownLatch(1);
            AtomicReference<String> value=new AtomicReference<>();
            scenario.onActivity(activity-> {
                WebView view=activity.findViewById(R.id.browser_view);
                assertSame(original.get(),view);
                view.evaluateJavascript("window.fixtureState",result->{value.set(result);evaluated.countDown();});
            });
            assertTrue(evaluated.await(10,TimeUnit.SECONDS));
            assertEquals("37",value.get());
        }
    }
    @Test public void subframeErrorsDoNotReplacePageAndRendererLossIsRecoverable() {
        try (ActivityScenario<BrowserActivity> scenario = ActivityScenario.launch(BrowserActivity.class)) {
            scenario.onActivity(activity -> {
                WebView web = activity.findViewById(R.id.browser_view);
                // Callback-level simulation; Android does not expose a WebResourceError constructor.
                // Production intentionally never dereferences/logs the error payload.
                web.getWebViewClient().onReceivedError(web, request(false), null);
                assertEquals(View.GONE, activity.findViewById(R.id.browser_retry).getVisibility());
                web.getWebViewClient().onReceivedError(web, request(true), null);
                assertEquals(View.VISIBLE, activity.findViewById(R.id.browser_retry).getVisibility());
                assertEquals(activity.getString(R.string.failed),
                    ((TextView) activity.findViewById(R.id.browser_status)).getText().toString());
                assertTrue(web.getWebViewClient().onRenderProcessGone(web, null));
                assertNull(activity.findViewById(R.id.browser_view));
                assertEquals(activity.getString(R.string.renderer_gone),
                    ((TextView) activity.findViewById(R.id.browser_status)).getText().toString());
                activity.findViewById(R.id.browser_retry).performClick();
                WebView recovered = activity.findViewById(R.id.browser_view);
                assertNotNull(recovered);
                assertNull(recovered.getUrl()); // Retry must not bypass the first-use notice.
                assertNotNull(activity.findViewById(R.id.open_x));
            });
        }
    }
    @Test public void nonWebNavigationAndWebPermissionsAreDenied() {
        try (ActivityScenario<BrowserActivity> scenario = ActivityScenario.launch(BrowserActivity.class)) {
            scenario.onActivity(activity -> {
                WebView web = activity.findViewById(R.id.browser_view);
                assertTrue(web.getWebViewClient().shouldOverrideUrlLoading(web,
                    request("intent://launch", true, true)));
                assertTrue(web.getWebViewClient().shouldOverrideUrlLoading(web,
                    request("https://example.org/", false, true)));
                assertTrue(web.getWebViewClient().shouldOverrideUrlLoading(web,
                    request("https://accounts.google.com/", true, false)));
                assertFalse(web.getWebViewClient().shouldOverrideUrlLoading(web,
                    request("https://x.com/i/flow/login", true, true)));
                final boolean[] denied = {false};
                web.getWebChromeClient().onPermissionRequest(new PermissionRequest() {
                    @Override public Uri getOrigin() { return Uri.parse("https://x.com"); }
                    @Override public String[] getResources() { return new String[]{RESOURCE_AUDIO_CAPTURE}; }
                    @Override public void grant(String[] resources) { fail("Must not grant permission"); }
                    @Override public void deny() { denied[0] = true; }
                });
                assertTrue(denied[0]);
                assertFalse(web.getWebChromeClient().onCreateWindow(web, false, true, null));
            });
        }
    }
    @Test public void realWebViewBackAndReloadUseSyntheticHttpsResponses() throws Exception {
        try (ActivityScenario<BrowserActivity> scenario = ActivityScenario.launch(BrowserActivity.class)) {
            AtomicReference<CountDownLatch> pending = new AtomicReference<>(new CountDownLatch(1));
            scenario.onActivity(activity -> {
                WebView web = activity.findViewById(R.id.browser_view);
                web.setWebViewClient(activity.new Client() {
                    @Override public android.webkit.WebResourceResponse shouldInterceptRequest(
                            WebView view, WebResourceRequest request) {
                        return response(200); // Every request is local; never contacts X/CDNs.
                    }
                    @Override public void onPageFinished(WebView view, String url) {
                        super.onPageFinished(view, url);
                        pending.get().countDown();
                    }
                });
                web.loadUrl("https://x.com/fixture/one");
            });
            assertTrue(pending.get().await(20, TimeUnit.SECONDS));
            pending.set(new CountDownLatch(1));
            scenario.onActivity(activity -> ((WebView) activity.findViewById(R.id.browser_view))
                .loadUrl("https://x.com/fixture/two"));
            assertTrue(pending.get().await(20, TimeUnit.SECONDS));
            pending.set(new CountDownLatch(1));
            scenario.onActivity(activity -> {
                WebView web = activity.findViewById(R.id.browser_view);
                assertTrue(web.canGoBack());
                activity.onBackPressed();
            });
            assertTrue(pending.get().await(20, TimeUnit.SECONDS));
            pending.set(new CountDownLatch(1));
            scenario.onActivity(activity -> {
                WebView web = activity.findViewById(R.id.browser_view);
                assertEquals("https://x.com/fixture/one", web.getUrl());
                assertTrue(web.canGoForward());
                web.reload();
            });
            assertTrue(pending.get().await(20, TimeUnit.SECONDS));
            scenario.onActivity(activity -> {
                assertEquals("https://x.com/fixture/one",
                    ((WebView) activity.findViewById(R.id.browser_view)).getUrl());
            });
        }
    }
    @Test public void httpFailureCallbackDisplaysNativeRetry() throws Exception {
        try (ActivityScenario<BrowserActivity> scenario = ActivityScenario.launch(BrowserActivity.class)) {
            CountDownLatch loaded = new CountDownLatch(1);
            scenario.onActivity(activity -> {
                WebView web = activity.findViewById(R.id.browser_view);
                web.setWebViewClient(activity.new Client() {
                    @Override public android.webkit.WebResourceResponse shouldInterceptRequest(
                            WebView view, WebResourceRequest request) { return response(200); }
                    @Override public void onPageFinished(WebView view, String url) {
                        super.onPageFinished(view, url);
                        loaded.countDown();
                    }
                });
                web.loadUrl("https://x.com/fixture/unavailable");
            });
            assertTrue(loaded.await(20, TimeUnit.SECONDS));
            scenario.onActivity(activity -> {
                WebView web = activity.findViewById(R.id.browser_view);
                assertEquals(View.GONE, activity.findViewById(R.id.browser_retry).getVisibility());
                // Intercepted responses do not reliably emit the network HTTP-error callback.
                // Exercise that platform callback explicitly, retaining the production UI assertions.
                web.getWebViewClient().onReceivedHttpError(web, request(false), response(503));
                assertEquals(View.GONE, activity.findViewById(R.id.browser_retry).getVisibility());
                web.getWebViewClient().onReceivedHttpError(web, request(true), response(503));
                assertEquals(View.VISIBLE, activity.findViewById(R.id.browser_retry).getVisibility());
                assertEquals(activity.getString(R.string.failed),
                    ((TextView) activity.findViewById(R.id.browser_status)).getText().toString());
            });
        }
    }
    @Test public void sharedPostLinkRequiresExplicitSaveBeforeAnyLocalWrite() {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        app.getSharedPreferences("xs_manual_archive", Context.MODE_PRIVATE)
            .edit().clear().commit();
        app.deleteDatabase("xs-local-history.db");
        Intent incoming = new Intent(app, ShareToXsActivity.class)
            .setAction(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "https://x.com/demo/status/998877");
        try (ActivityScenario<ShareToXsActivity> scenario = ActivityScenario.launch(incoming)) {
            scenario.onActivity(activity -> {
                assertNull(XsLocalSpaces.current(app)); // no scope without confirmation
                assertFalse(java.util.Arrays.asList(app.databaseList())
                    .contains("xs-local-history.db")); // no silent history capture
            });
        }
    }

    private static android.webkit.WebResourceResponse response(int code) {
        return new android.webkit.WebResourceResponse("text/html", "UTF-8", code,
            code == 200 ? "OK" : "Unavailable", Collections.emptyMap(),
            new java.io.ByteArrayInputStream("<html><body>Synthetic offline fixture</body></html>"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    private static WebResourceRequest request(boolean main) {
        return request("https://x.com/fixture", main, false);
    }
    private static WebResourceRequest request(String url, boolean main, boolean gesture) {
        return new WebResourceRequest() {
            public Uri getUrl() { return Uri.parse(url); }
            public boolean isForMainFrame() { return main; }
            public boolean isRedirect() { return false; }
            public boolean hasGesture() { return gesture; }
            public String getMethod() { return "GET"; }
            public Map<String, String> getRequestHeaders() { return Collections.emptyMap(); }
        };
    }
}

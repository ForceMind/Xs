package dev.forcemind.xs.browser;

import android.content.Context;
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
                assertTrue(s.getSafeBrowsingEnabled());
                assertTrue(s.getMediaPlaybackRequiresUserGesture());
                assertFalse(s.getJavaScriptCanOpenWindowsAutomatically());
                assertTrue((activity.getWindow().getAttributes().flags &
                    WindowManager.LayoutParams.FLAG_SECURE) != 0);
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertNotNull(activity.findViewById(R.id.browser_view));
                assertNotNull(activity.findViewById(R.id.open_x));
            });
        }
    }
    @Test public void actualWebViewExecutesSyntheticPageWithoutNativeArchive() throws Exception {
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
            Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
            assertFalse(java.util.Arrays.asList(app.databaseList()).contains("xs-local-history.db"));
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

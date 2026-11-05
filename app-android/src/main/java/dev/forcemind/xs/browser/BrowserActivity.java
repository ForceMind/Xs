package dev.forcemind.xs.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import dev.forcemind.xs.core.BrowserNavigation;
import dev.forcemind.xs.core.PostUrls;
import dev.forcemind.xs.android.XsArchiveDatabase;
import dev.forcemind.xs.android.XsLocalSpaces;
import dev.forcemind.xs.android.XsHistoryActivity;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** W0 only: no observer, JavascriptInterface, native capture or account binding. */
public final class BrowserActivity extends Activity {
    private static final String HOME = "https://x.com/home";
    private final ExecutorService localIo = Executors.newSingleThreadExecutor();
    private String initialPost;
    private static final int PICK_MEDIA = 4101;
    private FrameLayout root;
    private LinearLayout shell;
    private WebView web;
    private TextView status;
    private ProgressBar progress;
    private Button retry;
    private View fullScreen;
    private WebChromeClient.CustomViewCallback fullScreenCallback;
    private ValueCallback<Uri[]> upload;
    private AlertDialog uploadDialog;
    private String uploadPage;
    private long pageEpoch;
    private long uploadEpoch;
    private boolean failed;
    private boolean started;
    private boolean foreground;
    private String retryUrl = HOME;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String requested = getIntent() == null ? null : getIntent().getDataString();
        // Only explicit public post routes may be opened from Android intents.
        initialPost = requested != null &&
            BrowserNavigation.classify(requested) == BrowserNavigation.Target.INTERNAL &&
            PostUrls.extractPostId(requested) != null ? requested : null;
        // Avoid exposing login/DM contents in screenshots or recent-task snapshots.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        root = new FrameLayout(this);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        root.addView(shell, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        LinearLayout bar = new LinearLayout(this);
        status = new TextView(this);
        status.setId(R.id.browser_status);
        status.setText(R.string.poc);
        status.setPadding(16, 12, 8, 12);
        bar.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        Button menu = new Button(this);
        menu.setText(R.string.menu);
        menu.setOnClickListener(this::showMenu);
        bar.addView(menu);
        shell.addView(bar);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        shell.addView(progress, new LinearLayout.LayoutParams(-1, 4));
        retry = new Button(this);
        retry.setId(R.id.browser_retry);
        retry.setText(R.string.retry);
        retry.setVisibility(View.GONE);
        retry.setOnClickListener(v -> {
            if (web == null && !createWebView()) return;
            if (started) navigate(retryUrl);
            else {
                status.setText(R.string.poc);
                retry.setVisibility(View.GONE);
            }
        });
        shell.addView(retry);
        if (!createWebView()) return;
        started = getPreferences(MODE_PRIVATE).getBoolean("browser_notice_accepted", false);
        if (!started) {
            TextView notice = new TextView(this);
            notice.setText(R.string.intro);
            notice.setPadding(24, 24, 24, 24);
            shell.addView(notice, 2);
            Button open = new Button(this);
            open.setId(R.id.open_x);
            open.setText(R.string.open_x);
            shell.addView(open, 3);
            open.setOnClickListener(v -> {
                getPreferences(MODE_PRIVATE).edit().putBoolean("browser_notice_accepted", true).apply();
                started = true;
                shell.removeView(notice);
                shell.removeView(open);
                navigate(initialPost == null ? HOME : initialPost);
            });
        } else {
            Bundle browsing = state == null ? null : state.getBundle("web_navigation");
            if (browsing == null || web.restoreState(browsing) == null)
                navigate(initialPost == null ? HOME : initialPost);
            else if (BrowserNavigation.classify(web.getUrl()) != BrowserNavigation.Target.INTERNAL) {
                web.clearHistory();
                navigate(HOME);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private boolean createWebView() {
        try {
            web = new WebView(this);
        } catch (RuntimeException unavailable) {
            web = null;
            showFailure(R.string.webview_missing);
            retry.setVisibility(View.GONE); // Recovery requires enabling WebView and restarting the app.
            return false;
        }
        web.setId(R.id.browser_view);
        WebView.setWebContentsDebuggingEnabled(false);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true); // Required by the official website, no injected scripts.
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(true); // Routed to explicit refusal, never silently replace page.
        settings.setGeolocationEnabled(false);
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.setDownloadListener((url, agent, disposition, type, length) -> offerExternal(url));
        shell.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        return true;
    }

    private void navigate(String url) {
        if (!started || BrowserNavigation.classify(url) != BrowserNavigation.Target.INTERNAL) return;
        if (web == null && !createWebView()) return;
        failed = false;
        retryUrl = url;
        web.loadUrl(url);
    }

    class Client extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            BrowserNavigation.Target target = BrowserNavigation.classify(request.getUrl().toString());
            if (target == BrowserNavigation.Target.INTERNAL) return false;
            // An iframe or automatic redirect may never launch an external Activity.
            if (request.isForMainFrame() && request.hasGesture() &&
                    target == BrowserNavigation.Target.EXTERNAL) offerExternal(request.getUrl().toString());
            else if (request.isForMainFrame()) toast(R.string.blocked);
            return true;
        }
        @Override public void onPageStarted(WebView view, String url, Bitmap icon) {
            cancelUpload();
            pageEpoch++;
            if (BrowserNavigation.classify(url) != BrowserNavigation.Target.INTERNAL) {
                view.stopLoading();
                showFailure(R.string.blocked);
                return;
            }
            retryUrl = url;
            failed = false;
            retry.setVisibility(View.GONE);
            status.setText(R.string.loading);
            progress.setVisibility(View.VISIBLE);
        }
        @Override public void onPageFinished(WebView view, String url) {
            progress.setVisibility(View.GONE);
            if (!failed) status.setText(R.string.ready);
        }
        @Override public void onReceivedError(WebView view, WebResourceRequest request,
                WebResourceError error) {
            if (request.isForMainFrame()) showFailure(R.string.failed);
        }
        @Override public void onReceivedHttpError(WebView view, WebResourceRequest request,
                WebResourceResponse response) {
            if (request.isForMainFrame()) showFailure(R.string.failed);
        }
        @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel();
            showFailure(R.string.tls_failed);
        }
        @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            cancelUpload();
            hideFullScreen();
            shell.removeView(view);
            view.destroy();
            if (web == view) web = null;
            pageEpoch++;
            showFailure(R.string.renderer_gone);
            return true;
        }
    }

    private final class Chrome extends WebChromeClient {
        @Override public void onProgressChanged(WebView view, int value) {
            progress.setProgress(value);
            if (value == 100) progress.setVisibility(View.GONE);
        }
        @Override public boolean onCreateWindow(WebView view, boolean dialog, boolean gesture,
                Message result) {
            toast(R.string.popup);
            return false;
        }
        @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
        @Override public void onGeolocationPermissionsShowPrompt(String origin,
                android.webkit.GeolocationPermissions.Callback callback) {
            callback.invoke(origin, false, false);
        }
        @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                FileChooserParams params) {
            cancelUpload();
            if (!foreground || BrowserNavigation.classify(view.getUrl()) != BrowserNavigation.Target.INTERNAL ||
                    params.getMode() == FileChooserParams.MODE_SAVE) {
                callback.onReceiveValue(null);
                return true;
            }
            upload = callback;
            uploadPage = view.getUrl();
            uploadEpoch = pageEpoch;
            boolean multiple = params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE;
            uploadDialog = new AlertDialog.Builder(BrowserActivity.this)
                .setTitle(R.string.upload).setMessage(R.string.upload_body)
                .setNegativeButton(R.string.cancel, (d, w) -> cancelUpload())
                .setOnCancelListener(d -> cancelUpload())
                .setPositiveButton(R.string.open, (d, w) -> {
                    Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                        .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"})
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
                    try { startActivityForResult(pick, PICK_MEDIA); }
                    catch (ActivityNotFoundException missing) { cancelUpload(); toast(R.string.upload_failed); }
                }).show();
            return true;
        }
        @Override public void onShowCustomView(View view, CustomViewCallback callback) {
            if (fullScreen != null || !foreground) { callback.onCustomViewHidden(); return; }
            fullScreen = view;
            fullScreenCallback = callback;
            shell.setVisibility(View.GONE);
            root.addView(view, new FrameLayout.LayoutParams(-1, -1));
        }
        @Override public void onHideCustomView() { hideFullScreen(); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_MEDIA || upload == null) return;
        ValueCallback<Uri[]> callback = upload;
        upload = null;
        List<Uri> media = new ArrayList<>();
        if (result == RESULT_OK && data != null && web != null && pageEpoch == uploadEpoch &&
                uploadPage != null && uploadPage.equals(web.getUrl()) &&
                BrowserNavigation.classify(web.getUrl()) == BrowserNavigation.Target.INTERNAL) {
            if (data.getClipData() != null) {
                if (data.getClipData().getItemCount() <= 8) {
                    for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                        media.add(data.getClipData().getItemAt(i).getUri());
                    }
                }
            } else if (data.getData() != null) media.add(data.getData());
        }
        boolean valid = !media.isEmpty();
        for (Uri uri : media) {
            if (uri == null || !"content".equals(uri.getScheme()) || uri.getAuthority() == null ||
                    uri.getAuthority().startsWith(getPackageName())) { valid = false; break; }
            try {
                String mime = getContentResolver().getType(uri);
                if (mime == null || !(mime.startsWith("image/") || mime.startsWith("video/"))) valid = false;
            } catch (RuntimeException denied) { valid = false; }
        }
        callback.onReceiveValue(valid ? media.toArray(new Uri[0]) : null);
        uploadPage = null;
        if (!valid && result == RESULT_OK) toast(R.string.upload_failed);
    }

    private void cancelUpload() {
        if (uploadDialog != null) { uploadDialog.dismiss(); uploadDialog = null; }
        if (upload != null) { upload.onReceiveValue(null); upload = null; }
        uploadPage = null;
    }
    private void hideFullScreen() {
        if (fullScreen == null) return;
        root.removeView(fullScreen);
        fullScreen = null;
        shell.setVisibility(View.VISIBLE);
        WebChromeClient.CustomViewCallback callback = fullScreenCallback;
        fullScreenCallback = null;
        if (callback != null) callback.onCustomViewHidden();
    }
    private void showFailure(int message) {
        failed = true;
        status.setText(message);
        progress.setVisibility(View.GONE);
        retry.setVisibility(View.VISIBLE);
    }
    private void offerExternal(String value) {
        if (!foreground || BrowserNavigation.classify(value) == BrowserNavigation.Target.BLOCKED) return;
        Uri uri = Uri.parse(value);
        new AlertDialog.Builder(this).setTitle(R.string.external)
            .setMessage(getString(R.string.external_body, uri.getHost()))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.open, (d, w) -> {
                try {
                    // Never forward headers, cookies, page titles or arbitrary intent:// extras.
                    startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE));
                } catch (ActivityNotFoundException missing) { toast(R.string.no_browser); }
            }).show();
    }
    private void withLocalSpace(Runnable action) {
        String existing = XsLocalSpaces.current(this);
        if (existing != null) {
            action.run();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.local_scope_title)
            .setMessage(R.string.local_scope_notice)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.create_local_scope, (d, n) -> {
                try {
                    XsLocalSpaces.createOnExplicitUserAction(this);
                    action.run();
                } catch (RuntimeException error) {
                    toast(R.string.local_scope_error);
                }
            }).show();
    }

    private void showManualArchive() {
        withLocalSpace(() -> {
            Intent intent = new Intent(this, XsHistoryActivity.class);
            intent.putExtra(XsHistoryActivity.EXTRA_LOCAL_SPACE, true);
            startActivity(intent);
        });
    }

    private void saveCurrentPostManually() {
        String current = web == null ? null : web.getUrl();
        if (current == null || !started ||
            BrowserNavigation.classify(current) != BrowserNavigation.Target.INTERNAL ||
            PostUrls.extractPostId(current) == null) {
            toast(R.string.not_post_detail);
            return;
        }
        // Only the public post URL/ID is saved: never read the page DOM or cookies.
        final String url = current;
        withLocalSpace(() -> {
            final String scope = XsLocalSpaces.current(this);
            if (scope == null) { toast(R.string.local_scope_error); return; }
            localIo.execute(() -> {
                boolean fresh=false;
                boolean valid=false;
                try (XsArchiveDatabase db = new XsArchiveDatabase(getApplicationContext())) {
                    fresh=db.saveManualLink(scope,url,System.currentTimeMillis());
                    valid=true;
                } catch (RuntimeException ignored) { }
                final boolean success=valid, newEntry=fresh;
                runOnUiThread(() -> {
                    if (!isFinishing()) toast(!success ? R.string.local_save_failed :
                        newEntry ? R.string.local_save_done : R.string.local_save_exists);
                });
            });
        });
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        if (started) {
            menu.getMenu().add(R.string.home).setOnMenuItemClickListener(item -> { navigate(HOME); return true; });
            menu.getMenu().add(R.string.reload).setOnMenuItemClickListener(item -> {
                if (web != null) web.reload(); return true;
            });
        }
        menu.getMenu().add(R.string.local_archive).setOnMenuItemClickListener(item -> {
            showManualArchive(); return true;
        });
        if (started) menu.getMenu().add(R.string.local_save).setOnMenuItemClickListener(item -> {
            saveCurrentPostManually(); return true;
        });
        menu.getMenu().add(R.string.about).setOnMenuItemClickListener(item -> {
            PackageInfo provider = WebView.getCurrentWebViewPackage();
            String version = provider == null ? "unavailable" : provider.packageName + " " + provider.versionName;
            new AlertDialog.Builder(this).setTitle(R.string.about)
                .setMessage(getString(R.string.about_body, BuildConfig.VERSION_NAME,
                    Build.VERSION.RELEASE, Build.VERSION.SDK_INT, version))
                .setPositiveButton(android.R.string.ok, null).show();
            return true;
        });
        menu.show();
    }
    private void toast(int message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    @Override public void onBackPressed() {
        if (fullScreen != null) hideFullScreen();
        else if (web != null && web.canGoBack()) { cancelUpload(); web.goBack(); }
        else super.onBackPressed();
    }
    @Override protected void onResume() {
        super.onResume(); foreground = true;
        if (web != null) web.onResume();
    }
    @Override protected void onPause() {
        foreground = false;
        hideFullScreen();
        if (web != null) web.onPause();
        super.onPause();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        if (started && web != null) {
            Bundle browsing = new Bundle();
            web.saveState(browsing);
            state.putBundle("web_navigation", browsing);
        }
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        localIo.shutdown();
        cancelUpload(); hideFullScreen();
        if (web != null) {
            shell.removeView(web);
            web.stopLoading();
            web.setWebChromeClient(null);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}

package dev.forcemind.xs.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.forcemind.xs.android.XsArchiveDatabase;
import dev.forcemind.xs.android.XsLibraryWrites;
import dev.forcemind.xs.android.XsLocalSpaces;
import dev.forcemind.xs.android.XsMediaRepository;
import dev.forcemind.xs.android.XsShareCoordinator;
import dev.forcemind.xs.core.ArchiveSearch;
import dev.forcemind.xs.core.PostUrls;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Local synthetic HTTPS, a fake cookie, SQLite and a real saved PNG/lease. No real account. */
@RunWith(AndroidJUnit4.class)
public final class BrowserPrivacyIntegrationTest {
    private Context app;
    private ActivityScenario<BrowserActivity> scenario;
    private Intent scenarioIntent;
    private XsArchiveDatabase archive;
    private XsMediaRepository media;
    private String library,previousDefault,assetId;
    private File saved,leaseFile;
    private Uri lease;
    private byte[] png;
    private Map<String,?> expectedSettings,expectedSpace,previousPrivacy;
    private final AtomicReference<CountDownLatch> loaded=new AtomicReference<>();
    private static final String POST="https://x.com/alice/status/777900";

    @Before public void setup()throws Exception {
        app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        app.getSharedPreferences("browser.BrowserActivity",Context.MODE_PRIVATE).edit().clear().commit();
        previousPrivacy=new HashMap<>(app.getSharedPreferences("xs_browser_privacy",Context.MODE_PRIVATE).getAll());
        assertFalse("No earlier fixture clear is still pending",BrowserPrivacy.pending(app));
        app.getSharedPreferences("xs_browser_privacy",Context.MODE_PRIVATE).edit().putBoolean("needs_explicit_navigation",false).commit();
        previousDefault=XsLocalSpaces.current(app);library="local:privacy-"+UUID.randomUUID();
        app.getSharedPreferences("xs_manual_archive",Context.MODE_PRIVATE).edit().putString("local_space",library).putString("privacy_fixture_marker","keep").commit();
        app.getSharedPreferences("xs_library_settings",Context.MODE_PRIVATE).edit().putString("privacy_fixture_marker","keep").commit();
        expectedSettings=new HashMap<>(app.getSharedPreferences("xs_library_settings",Context.MODE_PRIVATE).getAll());
        expectedSpace=new HashMap<>(app.getSharedPreferences("xs_manual_archive",Context.MODE_PRIVATE).getAll());
        archive=new XsArchiveDatabase(app);archive.ensureLibrary(library,"Privacy fixture");
        assertTrue(archive.saveManualLink(library,POST,System.currentTimeMillis()));
        assertTrue(archive.recordObservedOpen(library,POST,"alice",null,null,System.currentTimeMillis(),"privacy-fixture-open:"+UUID.randomUUID()));
        assertTrue(archive.favorite(library,POST,"alice","native resource fixture",null,System.currentTimeMillis()));
        archive.putFavoriteDetails(library,"777900","retained note","privacy");
        archive.putCategory(library,"keep","Retained category");assertTrue(archive.assignCategory(library,"777900","keep"));
        media=XsMediaRepository.get(app);assetId=UUID.randomUUID().toString();
        File folder=new File(app.getFilesDir(),"xs-saved-media");assertTrue(folder.isDirectory()||folder.mkdirs());saved=new File(folder,assetId+".png");
        Bitmap bitmap=Bitmap.createBitmap(3,2,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff347821);
        try(FileOutputStream out=new FileOutputStream(saved)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}bitmap.recycle();
        png=java.nio.file.Files.readAllBytes(saved.toPath());
        ContentValues values=new ContentValues();values.put("id",assetId);values.put("library_id",library);values.put("post_url",POST);values.put("state","COMPLETED");values.put("mime","image/png");values.put("filename",saved.getName());values.put("bytes",saved.length());values.put("total",saved.length());values.put("created_ms",System.currentTimeMillis());
        media.getWritableDatabase().insertOrThrow("assets",null,values);
        lease=XsShareCoordinator.assetIntent(app,library,Arrays.asList(assetId)).getParcelableExtra(Intent.EXTRA_STREAM);
        XsMediaRepository.Lease grant=media.resolveLease(lease.getPathSegments().get(0),lease.getLastPathSegment());leaseFile=media.leaseFile(grant);
        assertNativeResources(); // Seed validity is proven before any browser privacy operation.
        scenarioIntent=new Intent(app,BrowserActivity.class).setAction(Intent.ACTION_VIEW).setData(Uri.parse(POST));
        scenario=ActivityScenario.launch(scenarioIntent);installFixtureClient();
        load("https://x.com/fixture/privacy-one");load("https://x.com/fixture/privacy-two");
        evaluate("document.cookie='xs_privacy_fixture=1; path=/; SameSite=Lax';localStorage.setItem('xs_privacy_fixture','1');sessionStorage.setItem('xs_privacy_fixture','1');window.fixtureOldDom=37");
        assertEquals("true",evaluate("document.cookie.indexOf('xs_privacy_fixture=')>=0&&localStorage.getItem('xs_privacy_fixture')==='1'&&sessionStorage.getItem('xs_privacy_fixture')==='1'"));
        scenario.onActivity(a->assertTrue(((WebView)a.findViewById(R.id.browser_view)).canGoBack()));
    }
    @After public void cleanup()throws Exception {
        if(scenario!=null){
            // ActivityScenario keys lifecycle events by its launch Intent. The real onNewIntent
            // path updates Activity.getIntent(); restore only this harness bookkeeping identity
            // after the assertions, so a normal finish is observed rather than timing out.
            scenario.onActivity(a->a.setIntent(new Intent(scenarioIntent)));scenario.close();
        }
        CountDownLatch clear=new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{WebStorage.getInstance().deleteAllData();CookieManager.getInstance().removeAllCookies(done->clear.countDown());});
        assertTrue(clear.await(10,TimeUnit.SECONDS));
        CountDownLatch drained=new CountDownLatch(1);assertTrue(XsLibraryWrites.invalidateAndSubmit(library,()->{media.eraseLibrary(library);archive.deleteLibraryScope(library);drained.countDown();}));assertTrue(drained.await(10,TimeUnit.SECONDS));
        if(lease!=null)media.getWritableDatabase().delete("leases","token=?",new String[]{lease.getPathSegments().get(0)});
        if(leaseFile!=null)leaseFile.delete();archive.close();
        android.content.SharedPreferences.Editor space=app.getSharedPreferences("xs_manual_archive",Context.MODE_PRIVATE).edit().remove("privacy_fixture_marker");
        if(previousDefault==null)space.remove("local_space");else space.putString("local_space",previousDefault);space.commit();
        app.getSharedPreferences("xs_library_settings",Context.MODE_PRIVATE).edit().remove("privacy_fixture_marker").commit();
        // Test-app browser state is restored only after every Activity and async clear is gone.
        android.content.SharedPreferences.Editor privacy=app.getSharedPreferences("xs_browser_privacy",Context.MODE_PRIVATE).edit().clear();
        for(Map.Entry<String,?> entry:previousPrivacy.entrySet()) {
            Object value=entry.getValue();
            if(value instanceof Long)privacy.putLong(entry.getKey(),(Long)value);
            else if(value instanceof Integer)privacy.putInt(entry.getKey(),(Integer)value);
            else if(value instanceof Boolean)privacy.putBoolean(entry.getKey(),(Boolean)value);
        }
        assertTrue(privacy.commit());
    }
    private void installFixtureClient(){scenario.onActivity(a->{
        WebView web=a.findViewById(R.id.browser_view);web.setWebViewClient(a.new Client(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){return new WebResourceResponse("text/html","UTF-8",new ByteArrayInputStream("<!doctype html><html><body><p id='fixture'>Synthetic privacy page</p></body></html>".getBytes(StandardCharsets.UTF_8)));}
            @Override public void onPageFinished(WebView view,String url){super.onPageFinished(view,url);CountDownLatch latch=loaded.get();if(latch!=null)latch.countDown();}
        });
    });}
    private void load(String url)throws Exception {CountDownLatch latch=new CountDownLatch(1);loaded.set(latch);scenario.onActivity(a->((WebView)a.findViewById(R.id.browser_view)).loadUrl(url));assertTrue(latch.await(20,TimeUnit.SECONDS));}
    private String evaluate(String script)throws Exception {CountDownLatch latch=new CountDownLatch(1);AtomicReference<String> raw=new AtomicReference<>();scenario.onActivity(a->((WebView)a.findViewById(R.id.browser_view)).evaluateJavascript(script,value->{raw.set(value);latch.countDown();}));assertTrue(latch.await(10,TimeUnit.SECONDS));return raw.get();}
    private static Object field(BrowserActivity activity,String name){try{Field f=BrowserActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}catch(Exception failure){throw new AssertionError(failure);}}
    private void confirm(boolean cache,boolean positive){scenario.onActivity(a->{if(cache)a.confirmClearBrowserCache();else a.confirmClearWebSession();AlertDialog dialog=(AlertDialog)field(a,"privacyDialog");assertTrue(dialog.isShowing());dialog.getButton(positive?AlertDialog.BUTTON_POSITIVE:AlertDialog.BUTTON_NEGATIVE).performClick();});}
    private WebView current(){AtomicReference<WebView> value=new AtomicReference<>();scenario.onActivity(a->value.set(a.findViewById(R.id.browser_view)));return value.get();}
    private void awaitReplacement(WebView old)throws Exception {long until=SystemClock.elapsedRealtime()+20000;do{if(!BrowserPrivacy.pending(app)&&current()!=null&&current()!=old)return;SystemClock.sleep(30);}while(SystemClock.elapsedRealtime()<until);fail("A cleared session must have a new empty browser view");}
    private void assertNativeResources()throws Exception {
        assertEquals(library,XsLocalSpaces.current(app));assertEquals(expectedSpace,app.getSharedPreferences("xs_manual_archive",Context.MODE_PRIVATE).getAll());assertEquals(expectedSettings,app.getSharedPreferences("xs_library_settings",Context.MODE_PRIVATE).getAll());
        ArchiveSearch.Filter filter=new ArchiveSearch.Filter();filter.keyword="native resource";filter.favoriteOnly=true;filter.categoryId="keep";filter.tag="privacy";
        try(Cursor c=archive.search(library,filter)){assertEquals("Native body/favorite/category/tag seed must remain searchable",1,c.getCount());}
        try(Cursor c=archive.timeline(library,null,10,"OPEN_POST")){assertEquals(1,c.getCount());}
        filter.actionType="LOCAL_SAVE";
        try(Cursor c=archive.search(library,filter)){assertEquals("Manual saves remain distinct from opens",1,c.getCount());}
        assertNotNull(media.find(library,assetId));assertArrayEquals(png,java.nio.file.Files.readAllBytes(saved.toPath()));
        try(java.io.InputStream input=app.getContentResolver().openInputStream(lease);ByteArrayOutputStream out=new ByteArrayOutputStream()){assertNotNull(input);byte[] bytes=new byte[256];int n;while((n=input.read(bytes))!=-1)out.write(bytes,0,n);assertArrayEquals(png,out.toByteArray());}
    }
    @Test public void cacheConfirmationKeepsWebsiteStateAndActualNativeFiles()throws Exception {
        WebView original=current();long epoch=BrowserPrivacy.sessionEpoch(app);
        confirm(true,false);assertSame(original,current());assertNativeResources();
        confirm(true,true);assertSame(original,current());assertEquals(epoch,BrowserPrivacy.sessionEpoch(app));
        assertEquals("true",evaluate("window.fixtureOldDom===37&&document.cookie.indexOf('xs_privacy_fixture=')>=0&&localStorage.getItem('xs_privacy_fixture')==='1'&&sessionStorage.getItem('xs_privacy_fixture')==='1'"));
        scenario.onActivity(a->assertTrue(((WebView)a.findViewById(R.id.browser_view)).canGoBack()));assertNativeResources();
    }
    @Test public void confirmedSessionReplacesDOMClearsWebStateAndRejectsOldCallbacks()throws Exception {
        WebView original=current();long epoch=BrowserPrivacy.sessionEpoch(app);confirm(false,false);
        assertSame(original,current());assertEquals(epoch,BrowserPrivacy.sessionEpoch(app));
        assertEquals("true",evaluate("window.fixtureOldDom===37&&document.cookie.indexOf('xs_privacy_fixture=')>=0"));
        AtomicReference<WebChromeClient> oldChrome=new AtomicReference<>();AtomicReference<WebViewClient> childClient=new AtomicReference<>();AtomicReference<WebView> child=new AtomicReference<>();AtomicInteger uploadCancelled=new AtomicInteger();
        scenario.onActivity(a->{
            oldChrome.set(original.getWebChromeClient());Message result=Message.obtain(new Handler(android.os.Looper.getMainLooper()),()->{});WebView.WebViewTransport transport=original.new WebViewTransport();result.obj=transport;
            assertTrue(oldChrome.get().onCreateWindow(original,false,true,result));child.set(transport.getWebView());childClient.set(child.get().getWebViewClient());
            assertTrue(oldChrome.get().onShowFileChooser(original,uris->{assertNull(uris);uploadCancelled.incrementAndGet();},chooser()));
        });
        confirm(false,true);awaitReplacement(original);assertEquals(epoch+1,BrowserPrivacy.sessionEpoch(app));assertEquals(1,uploadCancelled.get());
        scenario.onActivity(a->{WebView fresh=a.findViewById(R.id.browser_view);assertNull(fresh.getUrl());assertEquals(0,fresh.copyBackForwardList().getSize());assertFalse(fresh.canGoBack());assertFalse(fresh.canGoForward());assertNull(field(a,"popupTransport"));assertNull(field(a,"upload"));
            Message currentResult=Message.obtain(new Handler(android.os.Looper.getMainLooper()),()->{});WebView.WebViewTransport currentTransport=fresh.new WebViewTransport();currentResult.obj=currentTransport;
            assertTrue(fresh.getWebChromeClient().onCreateWindow(fresh,false,true,currentResult));
            childClient.get().shouldOverrideUrlLoading(child.get(),request("https://x.com/fixture/old-popup"));assertNull(field(a,"popupDialog"));
            assertSame("A late old popup callback must not close the new transport",currentTransport.getWebView(),field(a,"popupTransport"));
            try{java.lang.reflect.Method close=BrowserActivity.class.getDeclaredMethod("closePopup");close.setAccessible(true);close.invoke(a);}catch(Exception failure){throw new AssertionError(failure);}
            assertTrue(oldChrome.get().onShowFileChooser(original,uris->{assertNull(uris);uploadCancelled.incrementAndGet();},chooser()));
            assertFalse(oldChrome.get().onCreateWindow(original,false,true,null));
            a.onActivityResult(4101,Activity.RESULT_OK,new Intent().setData(Uri.parse("content://fixture-old/selection")));
            assertFalse(fresh.canGoBack());
        });assertEquals(2,uploadCancelled.get());assertNativeResources();
        scenario.recreate();assertNotSame(original,current());scenario.onActivity(a->assertNull(((WebView)a.findViewById(R.id.browser_view)).getUrl()));
        installFixtureClient();load("https://x.com/fixture/privacy-new");
        assertEquals("true",evaluate("document.cookie.indexOf('xs_privacy_fixture=')<0&&localStorage.getItem('xs_privacy_fixture')===null&&sessionStorage.getItem('xs_privacy_fixture')===null&&typeof window.fixtureOldDom==='undefined'"));
        scenario.onActivity(a->assertFalse(((WebView)a.findViewById(R.id.browser_view)).canGoBack()));assertNativeResources();
    }
    @Test public void destroyedActivityWithOldDeepLinkStaysEmptyUntilCurrentNativeConfirmation()throws Exception {
        final Intent oldLaunch=new Intent(scenarioIntent);
        // Simulate an already accepted first-use notice and an existing launch Intent without
        // opening a network page: the currently loaded document is still the local fixture.
        app.getSharedPreferences("browser.BrowserActivity",Context.MODE_PRIVATE).edit().putBoolean("browser_notice_accepted",true).commit();
        WebView old=current();confirm(false,true);awaitReplacement(old);
        assertTrue(BrowserPrivacy.needsExplicitNavigation(app));assertNativeResources();
        scenario.close();scenario=null;
        // This is a real Activity destruction/new launch, not a claim of killing the process.
        scenario=ActivityScenario.launch(oldLaunch);
        scenario.onActivity(a->{
            WebView empty=a.findViewById(R.id.browser_view);assertNull(empty.getUrl());assertEquals(0,empty.copyBackForwardList().getSize());
            AlertDialog incoming=(AlertDialog)field(a,"incomingDialog");assertNotNull(incoming);assertTrue(incoming.isShowing());
            assertTrue(BrowserPrivacy.needsExplicitNavigation(app));incoming.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        });
        assertTrue(BrowserPrivacy.needsExplicitNavigation(app));assertNativeResources();
        assertFalse(CookieManager.getInstance().hasCookies());
        scenario.recreate();scenario.onActivity(a->{assertNull(((WebView)a.findViewById(R.id.browser_view)).getUrl());assertTrue(BrowserPrivacy.needsExplicitNavigation(app));});
        installFixtureClient();
        final String fresh="https://x.com/alice/status/777901";
        final boolean[] supported={false};CountDownLatch selected=new CountDownLatch(1);loaded.set(selected);
        scenario.onActivity(a->{
            a.onNewIntent(new Intent(app,BrowserActivity.class).setAction(Intent.ACTION_VIEW).setData(Uri.parse(fresh)));
            WebView view=a.findViewById(R.id.browser_view);assertNull(view.getUrl());assertTrue(BrowserPrivacy.needsExplicitNavigation(app));
            AlertDialog incoming=(AlertDialog)field(a,"incomingDialog");assertTrue(incoming.isShowing());
            supported[0]=view.getSettings().getSafeBrowsingEnabled();incoming.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        });
        if(supported[0]) {
            assertTrue("Only the just-confirmed public link loads the local fixture",selected.await(20,TimeUnit.SECONDS));
            assertFalse(BrowserPrivacy.needsExplicitNavigation(app));
            scenario.onActivity(a->assertEquals(PostUrls.canonicalLink(fresh),((WebView)a.findViewById(R.id.browser_view)).getUrl()));
            assertEquals("true",evaluate("typeof window.fixtureOldDom==='undefined'&&localStorage.getItem('xs_privacy_fixture')===null&&sessionStorage.getItem('xs_privacy_fixture')===null&&document.cookie.indexOf('xs_privacy_fixture=')<0"));
        } else {
            assertTrue("A failed safety gate cannot consume explicit-navigation consent",BrowserPrivacy.needsExplicitNavigation(app));
            scenario.onActivity(a->assertNull(((WebView)a.findViewById(R.id.browser_view)).getUrl()));
        }
        assertNativeResources();
    }

    private static WebChromeClient.FileChooserParams chooser(){return new WebChromeClient.FileChooserParams(){
        public int getMode(){return MODE_OPEN;}public String[] getAcceptTypes(){return new String[]{"image/png"};}public boolean isCaptureEnabled(){return false;}public CharSequence getTitle(){return "Synthetic upload";}public String getFilenameHint(){return null;}public Intent createIntent(){return new Intent(Intent.ACTION_OPEN_DOCUMENT);}
    };}
    private static WebResourceRequest request(String url){return new WebResourceRequest(){
        public Uri getUrl(){return Uri.parse(url);}public boolean isForMainFrame(){return true;}public boolean isRedirect(){return false;}public boolean hasGesture(){return true;}public String getMethod(){return "GET";}public Map<String,String> getRequestHeaders(){return java.util.Collections.emptyMap();}
    };}
}

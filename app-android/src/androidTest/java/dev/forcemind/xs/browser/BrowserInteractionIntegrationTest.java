package dev.forcemind.xs.browser;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Process;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.forcemind.xs.browser.fixtures.BrowserDocumentsProvider;
import dev.forcemind.xs.core.StrictJson;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real touch gestures, native dialogs and SYSTEM SAF results. All pages/files are synthetic.
 * Never invokes onShowFileChooser/onCreateWindow/onActivityResult or grants itself URI access.
 */
@RunWith(AndroidJUnit4.class)
public final class BrowserInteractionIntegrationTest {
    private static final String HOME="https://x.com/home",SENSITIVE="https://x.com/settings/security";
    private static final String POPUP="https://x.com/i/flow/login?xs_fixture=1";
    private Instrumentation instrumentation;
    private Context app;
    private ActivityScenario<BrowserActivity> scenario;
    private Map<String,?> previousBrowser,previousPrivacy;
    private boolean bodyCompleted;
    private final AtomicReference<CountDownLatch> loaded=new AtomicReference<>();
    private static final String HTML="<!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'>"+
        "<style>body{margin:8px}button,input{display:block;box-sizing:border-box;width:100%;height:44px;margin:8px 0;font-size:16px}</style>"+
        "<input id='file' type='file' accept='image/png' aria-label='Select synthetic PNG'>"+
        "<button id='reset' onclick=\"document.getElementById('file').value='';window.uploadResult=null\">Reset selection</button>"+
        "<button id='arm' onclick='window.rejectOnHide=true'>Arm navigation boundary</button>"+
        "<button id='internal' onclick=\"window.popupReturned=window.open('"+POPUP+"','_blank')!==null\">Open synthetic authentication window</button>"+
        "<button id='external' onclick=\"window.popupReturned=window.open('https://example.invalid/xs-fixture','_blank')!==null\">Open external fixture link</button>"+
        "<button id='blocked' onclick=\"window.popupReturned=window.open('intent://xs-fixture','_blank')!==null\">Open blocked scheme</button>"+
        "<script>window.uploadResult=null;window.rejectOnHide=false;window.lastFixturePointer=null;"+
        "document.addEventListener('pointerdown',function(e){window.lastFixturePointer={id:e.target.id,trusted:e.isTrusted}},true);"+
        "document.getElementById('file').onchange=function(){var f=this.files[0];if(!f)return;var r=new FileReader();"+
        "r.onload=function(){window.uploadResult={name:f.name,type:f.type,size:f.size,data:r.result}};r.readAsDataURL(f)};"+
        "document.addEventListener('visibilitychange',function(){if(window.rejectOnHide&&document.visibilityState==='hidden'){window.rejectOnHide=false;location.href='"+SENSITIVE+"'}});</script>";
    @Before public void setup()throws Exception {
        instrumentation=InstrumentationRegistry.getInstrumentation();app=instrumentation.getTargetContext();
        previousBrowser=new HashMap<>(app.getSharedPreferences("browser.BrowserActivity",Context.MODE_PRIVATE).getAll());
        previousPrivacy=new HashMap<>(app.getSharedPreferences("xs_browser_privacy",Context.MODE_PRIVATE).getAll());
        assertFalse(BrowserPrivacy.pending(app));
        app.getSharedPreferences("browser.BrowserActivity",Context.MODE_PRIVATE).edit().clear().commit();
        app.getSharedPreferences("xs_browser_privacy",Context.MODE_PRIVATE).edit().putBoolean("needs_explicit_navigation",false).commit();
        AccessibilityServiceInfo info=instrumentation.getUiAutomation().getServiceInfo();info.flags|=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;instrumentation.getUiAutomation().setServiceInfo(info);
        scenario=ActivityScenario.launch(BrowserActivity.class);
        scenario.onActivity(a->{WebView web=a.findViewById(R.id.browser_view);web.setWebViewClient(a.new Client(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){return new WebResourceResponse("text/html","UTF-8",new ByteArrayInputStream(HTML.getBytes(StandardCharsets.UTF_8)));}
            @Override public void onPageFinished(WebView view,String url){super.onPageFinished(view,url);CountDownLatch done=loaded.get();if(done!=null)done.countDown();}
        });});load(HOME);
    }
    @After public void cleanup(){
        Throwable cleanupFailure=null;
        try{if(isDocumentsUi())backFromPicker();}catch(Throwable failure){cleanupFailure=failure;}
        try{if(scenario!=null)scenario.close();}catch(Throwable failure){if(cleanupFailure==null)cleanupFailure=failure;else cleanupFailure.addSuppressed(failure);}
        try{if(previousBrowser!=null)restore("browser.BrowserActivity",previousBrowser);if(previousPrivacy!=null)restore("xs_browser_privacy",previousPrivacy);}
        catch(Throwable failure){if(cleanupFailure==null)cleanupFailure=failure;else cleanupFailure.addSuppressed(failure);}
        if(cleanupFailure!=null){
            if(bodyCompleted)throw new AssertionError("Browser fixture cleanup failed",cleanupFailure);
            android.util.Log.w("XsBrowserFixture","Cleanup failure follows the original test failure",cleanupFailure);
        }
    }
    private void restore(String name,Map<String,?> values){android.content.SharedPreferences.Editor edit=app.getSharedPreferences(name,Context.MODE_PRIVATE).edit().clear();for(Map.Entry<String,?> item:values.entrySet()){
        Object value=item.getValue();if(value instanceof Boolean)edit.putBoolean(item.getKey(),(Boolean)value);else if(value instanceof Integer)edit.putInt(item.getKey(),(Integer)value);else if(value instanceof Long)edit.putLong(item.getKey(),(Long)value);else if(value instanceof String)edit.putString(item.getKey(),(String)value);
    }assertTrue(edit.commit());}
    private void load(String url)throws Exception {CountDownLatch done=new CountDownLatch(1);loaded.set(done);scenario.onActivity(a->((WebView)a.findViewById(R.id.browser_view)).loadUrl(url));assertTrue(done.await(20,TimeUnit.SECONDS));}
    private String evaluate(String script)throws Exception {CountDownLatch done=new CountDownLatch(1);AtomicReference<String> result=new AtomicReference<>();scenario.onActivity(a->((WebView)a.findViewById(R.id.browser_view)).evaluateJavascript(script,value->{result.set(value);done.countDown();}));assertTrue(done.await(10,TimeUnit.SECONDS));return result.get();}
    private Object field(String name){AtomicReference<Object> result=new AtomicReference<>();scenario.onActivity(a->{try{Field field=BrowserActivity.class.getDeclaredField(name);field.setAccessible(true);result.set(field.get(a));}catch(Exception failure){throw new AssertionError(failure);}});return result.get();}
    private boolean dialog(String name){Object value=field(name);return value instanceof AlertDialog&&((AlertDialog)value).isShowing();}
    private String currentUrl(){AtomicReference<String> result=new AtomicReference<>();scenario.onActivity(a->result.set(((WebView)a.findViewById(R.id.browser_view)).getUrl()));return result.get();}
    @SuppressWarnings("unchecked") private void tapHtml(String id)throws Exception {
        await(()->{final boolean[] focused={false};scenario.onActivity(a->{WebView web=a.findViewById(R.id.browser_view);focused[0]=web.isShown()&&web.hasWindowFocus();});return focused[0];},"Fixture WebView has actual window focus before a trusted gesture");
        evaluate("document.getElementById('"+id+"').scrollIntoView();window.lastFixturePointer=null");
        CountDownLatch presented=new CountDownLatch(1);
        scenario.onActivity(a->{WebView web=a.findViewById(R.id.browser_view);
            web.postVisualStateCallback(1,new WebView.VisualStateCallback(){
                @Override public void onComplete(long request){web.postOnAnimation(()->web.postOnAnimation(presented::countDown));}
            });
        });
        assertTrue("The scrolled fixture DOM reaches the compositor before its coordinates are used",presented.await(10,TimeUnit.SECONDS));
        String script="(function(){var r=document.getElementById('"+id+"').getBoundingClientRect();return JSON.stringify({x:r.left+r.width/2,y:r.top+r.height/2,width:innerWidth})})()";
        Object raw=StrictJson.parse(evaluate(script));Map<String,Object> point=(Map<String,Object>)StrictJson.parse((String)raw);
        final float[] pixels=new float[2];scenario.onActivity(a->{WebView web=a.findViewById(R.id.browser_view);int[] location=new int[2];web.getLocationOnScreen(location);double ratio=web.getWidth()/((Number)point.get("width")).doubleValue();pixels[0]=(float)(location[0]+((Number)point.get("x")).doubleValue()*ratio);pixels[1]=(float)(location[1]+((Number)point.get("y")).doubleValue()*ratio);
            Rect visible=new Rect();assertTrue(web.getGlobalVisibleRect(visible));assertTrue("The real touch stays inside the visible WebView",visible.contains((int)pixels[0],(int)pixels[1]));
        });
        tap(pixels[0],pixels[1]);
        awaitJs("window.lastFixturePointer&&window.lastFixturePointer.id==='"+id+"'&&window.lastFixturePointer.trusted===true");
    }
    private void tap(float x,float y){
        MotionEvent.PointerProperties finger=new MotionEvent.PointerProperties();finger.id=0;finger.toolType=MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords point=new MotionEvent.PointerCoords();point.x=x;point.y=y;point.pressure=1;point.size=1;
        MotionEvent.PointerProperties[] pointers={finger};MotionEvent.PointerCoords[] coordinates={point};
        long time=SystemClock.uptimeMillis();
        MotionEvent down=MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,1,pointers,coordinates,0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0);
        assertEquals("The gesture is a real finger, not TOOL_TYPE_UNKNOWN",MotionEvent.TOOL_TYPE_FINGER,down.getToolType(0));
        try{assertTrue(instrumentation.getUiAutomation().injectInputEvent(down,true));}finally{down.recycle();}
        SystemClock.sleep(20);point.pressure=0;
        MotionEvent up=MotionEvent.obtain(time,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,1,pointers,coordinates,0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0);
        try{assertTrue(instrumentation.getUiAutomation().injectInputEvent(up,true));}finally{up.recycle();}
    }
    private void clickNativeId(int resource){
        String id=app.getResources().getResourceName(resource);
        AccessibilityNodeInfo node=awaitNode(n->id.equals(n.getViewIdResourceName())&&n.isVisibleToUser()&&n.isEnabled());
        assertTrue("The identified native control is clicked through accessibility",node.performAction(AccessibilityNodeInfo.ACTION_CLICK));
    }
    private void dialogButton(boolean positive){String id="android:id/button"+(positive?"1":"2");AccessibilityNodeInfo node=awaitNode(n->id.equals(n.getViewIdResourceName())&&n.isVisibleToUser()&&n.isEnabled());assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK));}
    private void chooseFixture(String name){
        assertTrue("Only fixed synthetic filenames may be selected","xs-browser-fixture.png".equals(name)||"xs-browser-boundary.png".equals(name));
        await(this::isDocumentsUi,"System DocumentsUI opens");
        // A picker can restore this exact root. Opening its drawer again can otherwise
        // make the same title resolve to the toolbar/Recent section rather than the root.
        if(!(fixtureToolbarVisible()&&find(this::fixtureSidebarRoot)==null)) {
            if(find(this::fixtureSidebarRoot)==null) {
                AccessibilityNodeInfo navigation=awaitNode(n->systemDocumentNode(n)&&n.getClassName()!=null&&
                    n.getClassName().toString().contains("ImageButton")&&n.getContentDescription()!=null&&
                    (n.getContentDescription().toString().toLowerCase(java.util.Locale.ROOT).contains("roots")||
                     n.getContentDescription().toString().toLowerCase(java.util.Locale.ROOT).contains("navigation")||
                     n.getContentDescription().toString().toLowerCase(java.util.Locale.ROOT).contains("drawer")));
                touchSystemDocument(navigation);
            }
            touchSystemDocument(awaitNode(this::fixtureSidebarRoot));
            await(()->fixtureToolbarVisible()&&find(this::fixtureSidebarRoot)==null,
                "The fixed provider directory is active and its sidebar has closed");
        }
        // DocumentsUI's ancestor AX click can return true without activating its child.
        // Always send a finger gesture to this exact visible file title instead.
        touchSystemDocument(awaitNode(n->systemDocumentNode(n)&&name.contentEquals(n.getText()==null?"":n.getText())));
        await(()->!isDocumentsUi(),"Real SAF result returns to Xs");
    }
    private boolean fixtureSidebarRoot(AccessibilityNodeInfo node){
        if(!systemDocumentNode(node)||!BrowserDocumentsProvider.TITLE.contentEquals(node.getText()==null?"":node.getText()))return false;
        for(int depth=0;node!=null&&depth<12;depth++,node=node.getParent()) {
            String id=node.getViewIdResourceName();CharSequence type=node.getClassName();
            if((id!=null&&(id.equals("android:id/list")||id.endsWith(":id/roots_list")))||
                (type!=null&&type.toString().equals("android.widget.ListView")))return true;
        }
        return false;
    }
    private boolean fixtureToolbarVisible(){return find(n->{
        if(!systemDocumentNode(n)||!BrowserDocumentsProvider.TITLE.contentEquals(n.getText()==null?"":n.getText()))return false;
        for(int depth=0;n!=null&&depth<10;depth++,n=n.getParent()) {
            String id=n.getViewIdResourceName();CharSequence type=n.getClassName();
            if((id!=null&&id.endsWith(":id/toolbar"))||(type!=null&&type.toString().endsWith(".Toolbar")))return true;
        }
        return false;
    })!=null;}
    private static boolean systemDocumentNode(AccessibilityNodeInfo node){return node.isVisibleToUser()&&node.isEnabled()&&node.getPackageName()!=null&&node.getPackageName().toString().contains("documentsui");}
    private void touchSystemDocument(AccessibilityNodeInfo node){
        assertTrue("Only visible system document controls are touched",systemDocumentNode(node));
        final String text=String.valueOf(node.getText()),id=node.getViewIdResourceName(),type=String.valueOf(node.getClassName()),description=String.valueOf(node.getContentDescription());
        final boolean sidebar=fixtureSidebarRoot(node);
        try{instrumentation.getUiAutomation().waitForIdle(100,1000);}catch(java.util.concurrent.TimeoutException backgroundEvents){/* Bounds must still stabilize below. */}
        Rect previous=null,bounds=null;long stableSince=0,deadline=SystemClock.elapsedRealtime()+2000;
        while(SystemClock.elapsedRealtime()<deadline) {
            AccessibilityNodeInfo fresh=find(n->systemDocumentNode(n)&&text.equals(String.valueOf(n.getText()))&&
                java.util.Objects.equals(id,n.getViewIdResourceName())&&type.equals(String.valueOf(n.getClassName()))&&
                description.equals(String.valueOf(n.getContentDescription()))&&(!sidebar||fixtureSidebarRoot(n)));
            AccessibilityNodeInfo active=instrumentation.getUiAutomation().getRootInActiveWindow();
            if(fresh!=null&&fresh.refresh()&&active!=null&&active.getPackageName()!=null&&active.getPackageName().toString().contains("documentsui")) {
                Rect candidate=new Rect(),window=new Rect();fresh.getBoundsInScreen(candidate);active.getBoundsInScreen(window);
                if(candidate.intersect(window)&&!candidate.isEmpty()) {
                    if(candidate.equals(previous)){if(SystemClock.elapsedRealtime()-stableSince>=100){bounds=candidate;break;}}
                    else {previous=candidate;stableSince=SystemClock.elapsedRealtime();}
                }
            }
            SystemClock.sleep(16);
        }
        assertNotNull("The exact system control has stable clipped bounds in its active window",bounds);
        tap(bounds.exactCenterX(),bounds.exactCenterY());
    }
    private boolean isDocumentsUi(){return find(n->n.getPackageName()!=null&&n.getPackageName().toString().contains("documentsui"))!=null;}
    private void backFromPicker(){
        for(int i=0;i<4&&isDocumentsUi();i++){
            // The short KeyEvent(action,code) constructor has eventTime=0; modern input
            // dispatchers correctly drop it as stale instead of delivering system Back.
            long now=SystemClock.uptimeMillis();
            KeyEvent down=new KeyEvent(now,now,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK,0,0,android.view.KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,android.view.InputDevice.SOURCE_KEYBOARD);
            assertTrue("The actual current system Back press is injected",instrumentation.getUiAutomation().injectInputEvent(down,true));
            KeyEvent up=new KeyEvent(now,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK,0,0,android.view.KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,android.view.InputDevice.SOURCE_KEYBOARD);
            assertTrue("The matching system Back release is injected",instrumentation.getUiAutomation().injectInputEvent(up,true));SystemClock.sleep(150);
        }
        await(()->!isDocumentsUi(),"System picker cancellation returns");
    }
    private AccessibilityNodeInfo awaitNode(Predicate<AccessibilityNodeInfo> predicate){AtomicReference<AccessibilityNodeInfo> found=new AtomicReference<>();await(()->{AccessibilityNodeInfo node=find(predicate);if(node==null)return false;found.set(node);return true;},"Required native/system UI appears");return found.get();}
    private AccessibilityNodeInfo find(Predicate<AccessibilityNodeInfo> predicate){return visit(instrumentation.getUiAutomation().getRootInActiveWindow(),predicate,new int[]{0});}
    private AccessibilityNodeInfo visit(AccessibilityNodeInfo node,Predicate<AccessibilityNodeInfo> predicate,int[] count){if(node==null||++count[0]>2000)return null;if(predicate.test(node))return node;for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo found=visit(node.getChild(i),predicate,count);if(found!=null)return found;}return null;}
    private void await(BooleanSupplier condition,String explanation){long end=SystemClock.elapsedRealtime()+20000;do{if(condition.getAsBoolean())return;SystemClock.sleep(40);}while(SystemClock.elapsedRealtime()<end);AccessibilityNodeInfo active=instrumentation.getUiAutomation().getRootInActiveWindow();throw new AssertionError(explanation+"; active package="+(active==null?"none":active.getPackageName()));}
    private void awaitJs(String expression){await(()->{try{return "true".equals(evaluate(expression));}catch(Exception failure){throw new AssertionError(failure);}},expression);}
    private void openSystemPicker()throws Exception {tapHtml("file");await(()->dialog("uploadDialog"),"Trusted file-input gesture opens native consent");dialogButton(true);await(this::isDocumentsUi,"Actual ACTION_OPEN_DOCUMENT runs");}

    @Test public void realFileInputSafSelectionReadsExactPngAndCancelPathsDoNotSelect()throws Exception {
        Uri selected=DocumentsContract.buildDocumentUri(BrowserDocumentsProvider.AUTHORITY,BrowserDocumentsProvider.SELECTED);
        // BOUNDARY is intentionally selected by another case, so its existing task-owned
        // grant is not evidence of a widened new grant. This document is never selected.
        Uri neverSelected=DocumentsContract.buildDocumentUri(BrowserDocumentsProvider.AUTHORITY,BrowserDocumentsProvider.NEVER_SELECTED);
        assertEquals(PackageManager.PERMISSION_DENIED,app.checkSelfPermission("android.permission.MANAGE_DOCUMENTS"));
        assertEquals("The selected document starts without an earlier URI grant",PackageManager.PERMISSION_DENIED,
            app.checkUriPermission(selected,Process.myPid(),app.getApplicationInfo().uid,Intent.FLAG_GRANT_READ_URI_PERMISSION));
        assertEquals("No test has selected or granted the negative-control document",PackageManager.PERMISSION_DENIED,
            app.checkUriPermission(neverSelected,Process.myPid(),app.getApplicationInfo().uid,Intent.FLAG_GRANT_READ_URI_PERMISSION));
        openSystemPicker();chooseFixture("xs-browser-fixture.png");
        String data="data:image/png;base64,"+BrowserDocumentsProvider.PNG_BASE64;
        awaitJs("window.uploadResult&&window.uploadResult.name==='xs-browser-fixture.png'&&window.uploadResult.type==='image/png'&&window.uploadResult.size===68&&window.uploadResult.data==='"+data+"'");
        assertEquals(PackageManager.PERMISSION_GRANTED,app.checkUriPermission(selected,Process.myPid(),app.getApplicationInfo().uid,Intent.FLAG_GRANT_READ_URI_PERMISSION));
        assertEquals("Choosing one PNG must not grant a never-selected sibling",PackageManager.PERMISSION_DENIED,
            app.checkUriPermission(neverSelected,Process.myPid(),app.getApplicationInfo().uid,Intent.FLAG_GRANT_READ_URI_PERMISSION));
        try(InputStream input=app.getContentResolver().openInputStream(selected);ByteArrayOutputStream bytes=new ByteArrayOutputStream()){assertNotNull(input);byte[] chunk=new byte[128];int n;while((n=input.read(chunk))!=-1)bytes.write(chunk,0,n);assertArrayEquals(Base64.decode(BrowserDocumentsProvider.PNG_BASE64,Base64.NO_WRAP),bytes.toByteArray());}
        try{android.os.ParcelFileDescriptor write=app.getContentResolver().openFileDescriptor(selected,"w");if(write!=null)write.close();fail("Read-only fixture cannot be modified");}catch(SecurityException|FileNotFoundException expected){}
        tapHtml("reset");tapHtml("file");await(()->dialog("uploadDialog"),"Native upload consent appears");dialogButton(false);awaitJs("document.getElementById('file').files.length===0&&window.uploadResult===null");assertNull(field("upload"));
        openSystemPicker();backFromPicker();awaitJs("document.getElementById('file').files.length===0&&window.uploadResult===null");assertNull(field("upload"));bodyCompleted=true;
    }
    @Test public void realPickerResultAfterPageNavigationIsRejectedAndSensitivePageCannotChoose()throws Exception {
        tapHtml("arm");openSystemPicker();
        await(()->SENSITIVE.equals(currentUrl()),"Visibility transition performs the fixture's real navigation while SAF is open");
        chooseFixture("xs-browser-boundary.png");
        awaitJs("location.href==='"+SENSITIVE+"'&&document.getElementById('file').files.length===0&&window.uploadResult===null");assertNull(field("upload"));
        tapHtml("file");SystemClock.sleep(300);assertFalse("Security route never opens native file consent",dialog("uploadDialog"));assertFalse(isDocumentsUi());awaitJs("document.getElementById('file').files.length===0&&window.uploadResult===null");bodyCompleted=true;
    }
    @Test public void trustedWindowOpenUsesNativeConfirmationAndNeverRunsChildContent()throws Exception {
        CountDownLatch ready=new CountDownLatch(1);loaded.set(ready);clickNativeId(R.id.open_x);
        final boolean[] supported={false};scenario.onActivity(a->supported[0]=((WebView)a.findViewById(R.id.browser_view)).getSettings().getSafeBrowsingEnabled());
        if(!supported[0]) {
            tapHtml("internal");SystemClock.sleep(300);assertNull(field("popupTransport"));assertFalse(dialog("popupDialog"));assertEquals(HOME,currentUrl());bodyCompleted=true;return;
        }
        assertTrue(ready.await(20,TimeUnit.SECONDS));
        evaluate("window.noGesture=window.open('https://x.com/fixture/unsolicited','_blank')===null");SystemClock.sleep(250);assertNull(field("popupTransport"));assertFalse(dialog("popupDialog"));assertEquals(HOME,currentUrl());
        tapHtml("internal");await(()->dialog("popupDialog"),"Trusted window.open reaches controlled native confirmation");assertEquals(HOME,currentUrl());assertNull(field("popupTransport"));
        ready=new CountDownLatch(1);loaded.set(ready);dialogButton(true);assertTrue(ready.await(20,TimeUnit.SECONDS));assertEquals(POPUP,currentUrl());
        tapHtml("external");await(()->dialog("externalDialog"),"External HTTPS requires native confirmation");assertEquals(POPUP,currentUrl());assertNull(field("popupTransport"));dialogButton(false);assertEquals(POPUP,currentUrl());
        tapHtml("blocked");await(()->field("popupTransport")==null,"Blocked scheme transport is destroyed");assertFalse(dialog("externalDialog"));assertFalse(dialog("popupDialog"));assertEquals(POPUP,currentUrl());bodyCompleted=true;
    }
}

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
    private final AtomicReference<CountDownLatch> loaded=new AtomicReference<>();
    private static final String HTML="<!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'>"+
        "<style>body{margin:8px}button,input{display:block;box-sizing:border-box;width:100%;height:44px;margin:8px 0;font-size:16px}</style>"+
        "<input id='file' type='file' accept='image/png' aria-label='Select synthetic PNG'>"+
        "<button id='reset' onclick=\"document.getElementById('file').value='';window.uploadResult=null\">Reset selection</button>"+
        "<button id='arm' onclick='window.rejectOnHide=true'>Arm navigation boundary</button>"+
        "<button id='internal' onclick=\"window.popupReturned=window.open('"+POPUP+"','_blank')!==null\">Open synthetic authentication window</button>"+
        "<button id='external' onclick=\"window.popupReturned=window.open('https://example.invalid/xs-fixture','_blank')!==null\">Open external fixture link</button>"+
        "<button id='blocked' onclick=\"window.popupReturned=window.open('intent://xs-fixture','_blank')!==null\">Open blocked scheme</button>"+
        "<script>window.uploadResult=null;window.rejectOnHide=false;"+
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
        if(isDocumentsUi())backFromPicker();
        if(scenario!=null)scenario.close();restore("browser.BrowserActivity",previousBrowser);restore("xs_browser_privacy",previousPrivacy);
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
        String script="(function(){var e=document.getElementById('"+id+"');e.scrollIntoView();var r=e.getBoundingClientRect();return JSON.stringify({x:r.left+r.width/2,y:r.top+r.height/2,width:innerWidth})})()";
        Object raw=StrictJson.parse(evaluate(script));Map<String,Object> point=(Map<String,Object>)StrictJson.parse((String)raw);
        final float[] pixels=new float[2];scenario.onActivity(a->{WebView web=a.findViewById(R.id.browser_view);int[] location=new int[2];web.getLocationOnScreen(location);double ratio=web.getWidth()/((Number)point.get("width")).doubleValue();pixels[0]=(float)(location[0]+((Number)point.get("x")).doubleValue()*ratio);pixels[1]=(float)(location[1]+((Number)point.get("y")).doubleValue()*ratio);});
        instrumentation.waitForIdleSync();SystemClock.sleep(50);tap(pixels[0],pixels[1]);
    }
    private void tap(float x,float y){long time=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,x,y,0),up=MotionEvent.obtain(time,time+20,MotionEvent.ACTION_UP,x,y,0);down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);try{assertTrue(instrumentation.getUiAutomation().injectInputEvent(down,true));assertTrue(instrumentation.getUiAutomation().injectInputEvent(up,true));}finally{down.recycle();up.recycle();}}
    private void clickText(String text){AccessibilityNodeInfo node=awaitNode(n->text.contentEquals(n.getText()==null?"":n.getText())&&n.isVisibleToUser());for(int i=0;i<6&&node!=null;i++,node=node.getParent())if(node.isClickable()){assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK));return;}throw new AssertionError("Visible UI is not clickable: "+text);}
    private void dialogButton(boolean positive){String id="android:id/button"+(positive?"1":"2");AccessibilityNodeInfo node=awaitNode(n->id.equals(n.getViewIdResourceName())&&n.isVisibleToUser()&&n.isEnabled());assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK));}
    private void chooseFixture(String name){
        await(this::isDocumentsUi,"System DocumentsUI opens");
        AccessibilityNodeInfo drawer=find(n->n.getClassName()!=null&&n.getClassName().toString().contains("ImageButton")&&n.getContentDescription()!=null&&
            (n.getContentDescription().toString().toLowerCase(java.util.Locale.ROOT).contains("roots")||n.getContentDescription().toString().toLowerCase(java.util.Locale.ROOT).contains("navigation")||n.getContentDescription().toString().toLowerCase(java.util.Locale.ROOT).contains("drawer")));
        if(drawer!=null)assertTrue(drawer.performAction(AccessibilityNodeInfo.ACTION_CLICK));
        if(find(n->BrowserDocumentsProvider.TITLE.contentEquals(n.getText()==null?"":n.getText())&&n.isVisibleToUser())==null){AccessibilityNodeInfo fallback=find(n->n.getClassName()!=null&&n.getClassName().toString().contains("ImageButton")&&n.isVisibleToUser());if(fallback!=null)fallback.performAction(AccessibilityNodeInfo.ACTION_CLICK);}
        clickText(BrowserDocumentsProvider.TITLE);clickFixtureFile(name);await(()->!isDocumentsUi(),"Real SAF result returns to Xs");
    }
    private void clickFixtureFile(String name){
        assertTrue("Only the fixed test APK filenames may use the document-row touch fallback",
            "xs-browser-fixture.png".equals(name)||"xs-browser-boundary.png".equals(name));
        AccessibilityNodeInfo file=awaitNode(n->n.getPackageName()!=null&&n.getPackageName().toString().contains("documentsui")&&
            name.contentEquals(n.getText()==null?"":n.getText())&&n.isVisibleToUser());
        AccessibilityNodeInfo candidate=file;
        for(int i=0;i<6&&candidate!=null;i++,candidate=candidate.getParent())
            if(candidate.isClickable()){assertTrue(candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK));return;}
        // Some DocumentsUI versions handle row taps with TouchDelegate while every AX
        // ancestor reports non-clickable. Touch only the visible fixed filename's own bounds.
        AccessibilityNodeInfo active=instrumentation.getUiAutomation().getRootInActiveWindow();
        assertNotNull(active);assertNotNull(active.getPackageName());
        assertTrue(active.getPackageName().toString().contains("documentsui"));
        Rect bounds=new Rect(),window=new Rect();file.getBoundsInScreen(bounds);active.getBoundsInScreen(window);
        assertTrue("The fixed filename is inside the active DocumentsUI window",bounds.intersect(window)&&!bounds.isEmpty());
        tap(bounds.exactCenterX(),bounds.exactCenterY());
    }
    private boolean isDocumentsUi(){return find(n->n.getPackageName()!=null&&n.getPackageName().toString().contains("documentsui"))!=null;}
    private void backFromPicker(){for(int i=0;i<4&&isDocumentsUi();i++){assertTrue(instrumentation.getUiAutomation().injectInputEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK),true));assertTrue(instrumentation.getUiAutomation().injectInputEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK),true));SystemClock.sleep(150);}await(()->!isDocumentsUi(),"System picker cancellation returns");}
    private AccessibilityNodeInfo awaitNode(Predicate<AccessibilityNodeInfo> predicate){AtomicReference<AccessibilityNodeInfo> found=new AtomicReference<>();await(()->{AccessibilityNodeInfo node=find(predicate);if(node==null)return false;found.set(node);return true;},"Required native/system UI appears");return found.get();}
    private AccessibilityNodeInfo find(Predicate<AccessibilityNodeInfo> predicate){return visit(instrumentation.getUiAutomation().getRootInActiveWindow(),predicate,new int[]{0});}
    private AccessibilityNodeInfo visit(AccessibilityNodeInfo node,Predicate<AccessibilityNodeInfo> predicate,int[] count){if(node==null||++count[0]>2000)return null;if(predicate.test(node))return node;for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo found=visit(node.getChild(i),predicate,count);if(found!=null)return found;}return null;}
    private void await(BooleanSupplier condition,String explanation){long end=SystemClock.elapsedRealtime()+20000;do{if(condition.getAsBoolean())return;SystemClock.sleep(40);}while(SystemClock.elapsedRealtime()<end);throw new AssertionError(explanation);}
    private void awaitJs(String expression){await(()->{try{return "true".equals(evaluate(expression));}catch(Exception failure){throw new AssertionError(failure);}},expression);}
    private void openSystemPicker()throws Exception {tapHtml("file");await(()->dialog("uploadDialog"),"Trusted file-input gesture opens native consent");dialogButton(true);await(this::isDocumentsUi,"Actual ACTION_OPEN_DOCUMENT runs");}

    @Test public void realFileInputSafSelectionReadsExactPngAndCancelPathsDoNotSelect()throws Exception {
        Uri selected=DocumentsContract.buildDocumentUri(BrowserDocumentsProvider.AUTHORITY,BrowserDocumentsProvider.SELECTED);
        Uri unselected=DocumentsContract.buildDocumentUri(BrowserDocumentsProvider.AUTHORITY,BrowserDocumentsProvider.BOUNDARY);
        assertEquals(PackageManager.PERMISSION_DENIED,app.checkSelfPermission("android.permission.MANAGE_DOCUMENTS"));
        openSystemPicker();chooseFixture("xs-browser-fixture.png");
        String data="data:image/png;base64,"+BrowserDocumentsProvider.PNG_BASE64;
        awaitJs("window.uploadResult&&window.uploadResult.name==='xs-browser-fixture.png'&&window.uploadResult.type==='image/png'&&window.uploadResult.size===68&&window.uploadResult.data==='"+data+"'");
        assertEquals(PackageManager.PERMISSION_GRANTED,app.checkUriPermission(selected,Process.myPid(),app.getApplicationInfo().uid,Intent.FLAG_GRANT_READ_URI_PERMISSION));
        assertEquals(PackageManager.PERMISSION_DENIED,app.checkUriPermission(unselected,Process.myPid(),app.getApplicationInfo().uid,Intent.FLAG_GRANT_READ_URI_PERMISSION));
        try(InputStream input=app.getContentResolver().openInputStream(selected);ByteArrayOutputStream bytes=new ByteArrayOutputStream()){assertNotNull(input);byte[] chunk=new byte[128];int n;while((n=input.read(chunk))!=-1)bytes.write(chunk,0,n);assertArrayEquals(Base64.decode(BrowserDocumentsProvider.PNG_BASE64,Base64.NO_WRAP),bytes.toByteArray());}
        try{android.os.ParcelFileDescriptor write=app.getContentResolver().openFileDescriptor(selected,"w");if(write!=null)write.close();fail("Read-only fixture cannot be modified");}catch(SecurityException|FileNotFoundException expected){}
        tapHtml("reset");tapHtml("file");await(()->dialog("uploadDialog"),"Native upload consent appears");dialogButton(false);awaitJs("document.getElementById('file').files.length===0&&window.uploadResult===null");assertNull(field("upload"));
        openSystemPicker();backFromPicker();awaitJs("document.getElementById('file').files.length===0&&window.uploadResult===null");assertNull(field("upload"));
    }
    @Test public void realPickerResultAfterPageNavigationIsRejectedAndSensitivePageCannotChoose()throws Exception {
        tapHtml("arm");openSystemPicker();
        await(()->SENSITIVE.equals(currentUrl()),"Visibility transition performs the fixture's real navigation while SAF is open");
        chooseFixture("xs-browser-boundary.png");
        awaitJs("location.href==='"+SENSITIVE+"'&&document.getElementById('file').files.length===0&&window.uploadResult===null");assertNull(field("upload"));
        tapHtml("file");SystemClock.sleep(300);assertFalse("Security route never opens native file consent",dialog("uploadDialog"));assertFalse(isDocumentsUi());awaitJs("document.getElementById('file').files.length===0&&window.uploadResult===null");
    }
    @Test public void trustedWindowOpenUsesNativeConfirmationAndNeverRunsChildContent()throws Exception {
        CountDownLatch ready=new CountDownLatch(1);loaded.set(ready);clickText(app.getString(R.string.open_x));
        final boolean[] supported={false};scenario.onActivity(a->supported[0]=((WebView)a.findViewById(R.id.browser_view)).getSettings().getSafeBrowsingEnabled());
        if(!supported[0]) {
            tapHtml("internal");SystemClock.sleep(300);assertNull(field("popupTransport"));assertFalse(dialog("popupDialog"));assertEquals(HOME,currentUrl());return;
        }
        assertTrue(ready.await(20,TimeUnit.SECONDS));
        evaluate("window.noGesture=window.open('https://x.com/fixture/unsolicited','_blank')===null");SystemClock.sleep(250);assertNull(field("popupTransport"));assertFalse(dialog("popupDialog"));assertEquals(HOME,currentUrl());
        tapHtml("internal");await(()->dialog("popupDialog"),"Trusted window.open reaches controlled native confirmation");assertEquals(HOME,currentUrl());assertNull(field("popupTransport"));
        ready=new CountDownLatch(1);loaded.set(ready);dialogButton(true);assertTrue(ready.await(20,TimeUnit.SECONDS));assertEquals(POPUP,currentUrl());
        tapHtml("external");await(()->dialog("externalDialog"),"External HTTPS requires native confirmation");assertEquals(POPUP,currentUrl());assertNull(field("popupTransport"));dialogButton(false);assertEquals(POPUP,currentUrl());
        tapHtml("blocked");await(()->field("popupTransport")==null,"Blocked scheme transport is destroyed");assertFalse(dialog("externalDialog"));assertFalse(dialog("popupDialog"));assertEquals(POPUP,currentUrl());
    }
}

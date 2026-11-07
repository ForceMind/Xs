package dev.forcemind.xs.browser;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.graphics.Bitmap;
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
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.junit.After;
import org.junit.Rule;
import org.junit.rules.TestName;
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
    private boolean bodyCompleted,fixtureDirectoryConfirmed;
    @Rule public final TestName testName=new TestName();
    private final AtomicReference<CountDownLatch> loaded=new AtomicReference<>();
    private final List<String> nativeHistoryCallbacks=new CopyOnWriteArrayList<>();
    private static final String HTML=(
        "<!doctype html><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n" +
        "<style>body{margin:8px}button,input{display:block;box-sizing:border-box;width:100%;height:44px;margin:8px 0;font-size:16px}video{display:block;width:128px;height:128px}</style>\n" +
        "<input id=\"file\" type=\"file\" accept=\"image/png\" aria-label=\"Select synthetic PNG\">\n" +
        "<input id=\"videoFile\" type=\"file\" accept=\"video/mp4\" aria-label=\"Select synthetic MP4\">\n" +
        "<input id=\"multiFile\" type=\"file\" accept=\"image/png\" multiple aria-label=\"Select two synthetic PNGs\">\n" +
        "<button id=\"reset\" onclick=\"document.getElementById('file').value='';window.uploadResult=null\">Reset selection</button>\n" +
        "<button id=\"arm\" onclick=\"window.rejectOnHide=true\">Arm navigation boundary</button>\n" +
        "<button id=\"armSpa\" onclick=\"window.spaArmTrusted=event.isTrusted;window.spaRouteArmed=window.spaArmTrusted\">Arm same-document navigation boundary</button>\n" +
        "<button id=\"internal\" onclick=\"window.popupReturned=window.open('__POPUP__','_blank')!==null\">Open synthetic authentication window</button>\n" +
        "<button id=\"external\" onclick=\"window.popupReturned=window.open('https://example.invalid/xs-fixture','_blank')!==null\">Open external fixture link</button>\n" +
        "<button id=\"blocked\" onclick=\"window.popupReturned=window.open('intent://xs-fixture','_blank')!==null\">Open blocked scheme</button>\n" +
        "<video id=\"videoPreview\" playsinline controls preload=\"metadata\"></video>\n" +
        "<button id=\"playVideo\" onclick=\"window.videoState.playGestureTrusted=event.isTrusted;var p=document.getElementById('videoPreview').play();if(p&amp;&amp;p.catch)p.catch(function(e){window.videoState.error=e.name})\">Play selected local MP4</button>\n" +
        "<script>\n" +
        "window.uploadResult=null;window.videoResult=null;window.multiResult=null;window.multiError=null;\n" +
        "window.rejectOnHide=false;window.spaRouteArmed=false;window.spaArmTrusted=false;window.spaRouteRuns=0;window.spaVisitLog=[];window.fixtureDocumentMarker={};window.lastFixturePointer=null;\n" +
        "window.videoState={metadata:false,playing:false,ended:false,timeUpdates:0,playGestureTrusted:false,error:null};\n" +
        "document.addEventListener('pointerdown',function(e){window.lastFixturePointer={id:e.target.id,trusted:e.isTrusted}},true);\n" +
        "document.getElementById('file').onchange=function(){var f=this.files[0];if(!f)return;var r=new FileReader();r.onload=function(){window.uploadResult={name:f.name,type:f.type,size:f.size,data:r.result}};r.readAsDataURL(f)};\n" +
        "var video=document.getElementById('videoPreview');\n" +
        "video.onloadedmetadata=function(){window.videoState.metadata=true;window.videoState.duration=video.duration;window.videoState.width=video.videoWidth;window.videoState.height=video.videoHeight};\n" +
        "video.onplaying=function(){window.videoState.playing=true};video.ontimeupdate=function(){window.videoState.timeUpdates++;window.videoState.currentTime=video.currentTime};\n" +
        "video.onended=function(){window.videoState.ended=true;window.videoState.currentTime=video.currentTime};video.onerror=function(){window.videoState.error=video.error?video.error.code:'unknown'};\n" +
        "document.getElementById('videoFile').onchange=function(){var f=this.files[0];if(!f)return;var r=new FileReader();r.onload=function(){window.videoResult={name:f.name,type:f.type,size:f.size,data:r.result}};r.onerror=function(){window.videoState.error='file-read'};r.readAsDataURL(f);video.src=URL.createObjectURL(f);video.load()};\n" +
        "document.getElementById('multiFile').onchange=function(){var files=Array.prototype.slice.call(this.files);Promise.all(files.map(function(f){return new Promise(function(resolve,reject){var r=new FileReader();r.onload=function(){resolve({name:f.name,type:f.type,size:f.size,data:r.result})};r.onerror=reject;r.readAsDataURL(f)})})).then(function(results){window.multiResult=results},function(){window.multiError='file-read'})};\n" +
        "window.runArmedSpaRoute=function(){if(!window.spaRouteArmed||!window.spaArmTrusted||window.spaRouteRuns!==0||window.spaVisitLog.length!==0)return false;window.spaRouteArmed=false;window.spaRouteRuns++;var marker=window.fixtureDocumentMarker;history.pushState({fixture:'sensitive'},'','__SENSITIVE__');window.spaVisitLog.push(location.href);history.pushState({fixture:'home'},'','__HOME__');window.spaVisitLog.push(location.href);window.spaSameDocument=window.fixtureDocumentMarker===marker;return true};\n" +
        "document.addEventListener('visibilitychange',function(){if(document.visibilityState==='hidden'&&window.rejectOnHide){window.rejectOnHide=false;location.href='__SENSITIVE__'}});\n" +
        "</script>"
        ).replace("__POPUP__",POPUP).replace("__SENSITIVE__",SENSITIVE).replace("__HOME__",HOME);
    @Before public void setup()throws Exception {
        instrumentation=InstrumentationRegistry.getInstrumentation();app=instrumentation.getTargetContext();
        previousBrowser=new HashMap<>(app.getSharedPreferences("browser.BrowserActivity",Context.MODE_PRIVATE).getAll());
        previousPrivacy=new HashMap<>(app.getSharedPreferences("xs_browser_privacy",Context.MODE_PRIVATE).getAll());
        assertFalse(BrowserPrivacy.pending(app));
        app.getSharedPreferences("browser.BrowserActivity",Context.MODE_PRIVATE).edit().clear().commit();
        app.getSharedPreferences("xs_browser_privacy",Context.MODE_PRIVATE).edit().putBoolean("needs_explicit_navigation",false).commit();
        AccessibilityServiceInfo info=instrumentation.getUiAutomation().getServiceInfo();info.flags|=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS|AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;instrumentation.getUiAutomation().setServiceInfo(info);
        scenario=ActivityScenario.launch(BrowserActivity.class);
        scenario.onActivity(a->{WebView web=a.findViewById(R.id.browser_view);web.setWebViewClient(a.new Client(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){return new WebResourceResponse("text/html","UTF-8",new ByteArrayInputStream(HTML.getBytes(StandardCharsets.UTF_8)));}
            @Override public void doUpdateVisitedHistory(WebView view,String url,boolean reload){nativeHistoryCallbacks.add(url);super.doUpdateVisitedHistory(view,url,reload);}
            @Override public void onPageFinished(WebView view,String url){super.onPageFinished(view,url);CountDownLatch done=loaded.get();if(done!=null)done.countDown();}
        });});load(HOME);
    }
    @After public void cleanup(){
        if(!bodyCompleted)saveFailureArtifacts();
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
    private void tap(float x,float y){finger(x,y,20);}
    private void longPress(float x,float y){finger(x,y,android.view.ViewConfiguration.getLongPressTimeout()+100L);}
    private void finger(float x,float y,long holdMs){
        MotionEvent.PointerProperties finger=new MotionEvent.PointerProperties();finger.id=0;finger.toolType=MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords point=new MotionEvent.PointerCoords();point.x=x;point.y=y;point.pressure=1;point.size=1;
        MotionEvent.PointerProperties[] pointers={finger};MotionEvent.PointerCoords[] coordinates={point};
        long time=SystemClock.uptimeMillis();
        MotionEvent down=MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,1,pointers,coordinates,0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0);
        assertEquals("The gesture is a real finger, not TOOL_TYPE_UNKNOWN",MotionEvent.TOOL_TYPE_FINGER,down.getToolType(0));
        try{assertTrue(instrumentation.getUiAutomation().injectInputEvent(down,true));}finally{down.recycle();}
        SystemClock.sleep(holdMs);point.pressure=0;
        MotionEvent up=MotionEvent.obtain(time,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,1,pointers,coordinates,0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0);
        try{assertTrue(instrumentation.getUiAutomation().injectInputEvent(up,true));}finally{up.recycle();}
    }
    private void clickNativeId(int resource){
        String id=app.getResources().getResourceName(resource);
        AccessibilityNodeInfo node=awaitNode(n->id.equals(n.getViewIdResourceName())&&n.isVisibleToUser()&&n.isEnabled());
        assertTrue("The identified native control is clicked through accessibility",node.performAction(AccessibilityNodeInfo.ACTION_CLICK));
    }
    private void dialogButton(boolean positive){String id="android:id/button"+(positive?"1":"2");AccessibilityNodeInfo node=awaitNode(n->id.equals(n.getViewIdResourceName())&&n.isVisibleToUser()&&n.isEnabled());assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK));}
    private static boolean selectableFixtureName(String name){return BrowserDocumentsProvider.SELECTED_NAME.equals(name)||BrowserDocumentsProvider.BOUNDARY_NAME.equals(name)||BrowserDocumentsProvider.MULTI_ONE_NAME.equals(name)||BrowserDocumentsProvider.MULTI_TWO_NAME.equals(name)||BrowserDocumentsProvider.VIDEO_NAME.equals(name)||BrowserDocumentsProvider.SPA_RESULT_NAME.equals(name);}
    private void chooseFixture(String name){
        assertTrue("Only explicitly selectable fixed synthetic names are permitted",selectableFixtureName(name));
        openFixtureDirectory();
        touchSystemDocument(awaitFixtureDocument(name));
        await(()->!isDocumentsUi(),"Real SAF result returns to Xs");
    }
    private void openFixtureDirectory(){
        await(this::isDocumentsUi,"System DocumentsUI opens");
        if(!(fixtureToolbarVisible()&&find(this::fixtureSidebarRoot)==null)) {
            if(find(this::fixtureSidebarRoot)==null) {
                AccessibilityNodeInfo navigation=awaitNode(n->systemDocumentNode(n)&&n.getClassName()!=null&&
                    n.getClassName().toString().contains("ImageButton")&&n.getContentDescription()!=null&&
                    (n.getContentDescription().toString().toLowerCase(java.util.Locale.ROOT).contains("roots")||
                     n.getContentDescription().toString().toLowerCase(java.util.Locale.ROOT).contains("navigation")||
                     n.getContentDescription().toString().toLowerCase(java.util.Locale.ROOT).contains("drawer")));
                touchSystemDocument(navigation);
            }
            activateFixtureSidebarRow();
            await(()->fixtureToolbarVisible()&&find(this::fixtureSidebarRoot)==null,
                "The fixed provider directory is active and its sidebar has closed");
        }
        fixtureDirectoryConfirmed=true;
        try{instrumentation.getUiAutomation().waitForIdle(100,1000);}
        catch(java.util.concurrent.TimeoutException backgroundEvents){/* Use fresh actual menu nodes below. */}
        // Image-only picking can use GridPhotoHolder, whose tile intentionally has
        // no filename TextView. Use the actual system List view action rather than
        // treating its composite accessibility description as a filename.
        AccessibilityNodeInfo listView=find(n->pickerViewAction(n,"sub_menu_list","List view"));
        if(listView!=null) {
            assertTrue("Only the confirmed fixture directory changes its presentation",confirmedFixtureDirectoryActive());
            touchSystemDocument(listView);
            await(()->confirmedFixtureDirectoryActive()&&find(n->pickerViewAction(n,"sub_menu_grid","Grid view"))!=null,
                "The real system List view change exposes the opposite Grid view action");
        }
    }
    private boolean pickerViewAction(AccessibilityNodeInfo node,String resource,String label){
        if(!systemDocumentNode(node)||!node.isClickable())return false;
        String id=node.getViewIdResourceName();
        String suffix="sub_menu_list".equals(resource)?"list":"sub_menu_grid".equals(resource)?"grid":null;
        // Official DocumentsUI actions: API26 menu_*, API27 option_menu_*, modern sub_menu_*.
        return id!=null&&suffix!=null&&(id.endsWith(":id/menu_"+suffix)||id.endsWith(":id/option_menu_"+suffix)||id.endsWith(":id/sub_menu_"+suffix))&&
            (label.contentEquals(node.getText()==null?"":node.getText())||label.contentEquals(node.getContentDescription()==null?"":node.getContentDescription()));
    }
    private void activateFixtureSidebarRow(){
        AccessibilityNodeInfo title=awaitNode(this::fixtureSidebarRoot);
        for(int depth=0;title!=null&&depth<12;depth++) {
            AccessibilityNodeInfo parent=title.getParent();if(parent==null)break;
            String id=parent.getViewIdResourceName();CharSequence type=parent.getClassName();
            boolean rootList=(id!=null&&(id.equals("android:id/list")||id.endsWith(":id/roots_list")))||
                (type!=null&&type.toString().equals("android.widget.ListView"));
            if(rootList) {
                assertTrue("Only the actual clickable system sidebar item is activated",title.refresh()&&systemDocumentNode(title)&&title.isClickable());
                assertNotNull("The refreshed direct item still contains this exact fixture title",visit(title,
                    n->systemDocumentNode(n)&&BrowserDocumentsProvider.TITLE.contentEquals(n.getText()==null?"":n.getText()),new int[]{0}));
                AccessibilityNodeInfo active=instrumentation.getUiAutomation().getRootInActiveWindow();
                assertTrue("The system picker remains active",active!=null&&systemDocumentNode(active));
                Rect row=new Rect(),window=new Rect();title.getBoundsInScreen(row);active.getBoundsInScreen(window);
                assertTrue("The direct fixture item is clipped inside the active picker",row.intersect(window)&&!row.isEmpty());
                assertTrue("Activate the exact actual roots-list item",title.performAction(AccessibilityNodeInfo.ACTION_CLICK));return;
            }
            title=parent;
        }
        throw new AssertionError("The exact fixture title has no direct system roots-list item");
    }
    private boolean confirmedFixtureDirectoryActive(){
        return fixtureDirectoryConfirmed&&isDocumentsUi()&&find(this::fixtureSidebarRoot)==null;
    }
    private AccessibilityNodeInfo awaitFixtureDocument(String name){
        assertTrue("Only fixed selectable filenames may be sought",selectableFixtureName(name));
        long deadline=SystemClock.elapsedRealtime()+20000,nextScrollAt=0;int pages=0;
        boolean towardStart=true,cannotAdvance=false;
        while(SystemClock.elapsedRealtime()<deadline) {
            assertTrue("Only the already-confirmed fixture directory may be searched/scrolled",confirmedFixtureDirectoryActive());
            AccessibilityNodeInfo file=find(n->systemDocumentNode(n)&&name.contentEquals(n.getText()==null?"":n.getText())&&enabledDocumentAncestors(n));
            if(file!=null&&file.refresh()&&systemDocumentNode(file)&&enabledDocumentAncestors(file))return file;
            if(!cannotAdvance&&pages<5&&SystemClock.elapsedRealtime()>=nextScrollAt) {
                AccessibilityNodeInfo list=find(this::documentScrollContainer);
                if(list!=null&&list.refresh()&&documentScrollContainer(list)) {
                    AccessibilityNodeInfo active=instrumentation.getUiAutomation().getRootInActiveWindow();
                    Rect item=new Rect(),window=new Rect();list.getBoundsInScreen(item);if(active!=null)active.getBoundsInScreen(window);
                    assertTrue("Only the active picker's actual directory list scrolls",active!=null&&systemDocumentNode(active)&&item.intersect(window)&&!item.isEmpty());
                    // DocumentsUI restores its previous scroll position. Before searching
                    // downward, genuinely rewind this directory; both directions share the
                    // original five-action/20-second bound and preserve selection state.
                    int direction=towardStart?AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD:AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
                    if(towardStart&&(list.getActions()&AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)==0) {
                        towardStart=false;continue;
                    }
                    pages++;boolean advanced=list.performAction(direction);
                    if(!advanced){if(towardStart)towardStart=false;else cannotAdvance=true;}
                    long remaining=deadline-SystemClock.elapsedRealtime();
                    if(advanced&&remaining>100)try{instrumentation.getUiAutomation().waitForIdle(100,Math.min(1000,remaining));}
                    catch(java.util.concurrent.TimeoutException backgroundEvents){/* Keep the original deadline and require fresh visible file bounds below. */}
                    nextScrollAt=SystemClock.elapsedRealtime()+200;
                }
            }
            SystemClock.sleep(40);
        }
        throw new AssertionError("Fixed document not visible within the original 20s / five-page bound: "+name);
    }
    private boolean documentScrollContainer(AccessibilityNodeInfo node){
        if(!systemDocumentNode(node)||!node.isScrollable())return false;
        String id=node.getViewIdResourceName(),type=String.valueOf(node.getClassName());
        return (id!=null&&id.endsWith(":id/dir_list"))||type.endsWith(".RecyclerView")||type.equals("android.widget.GridView");
    }
    private void chooseTwoPngFixtures(){
        openFixtureDirectory();
        touchSystemDocument(awaitFixtureDocument(BrowserDocumentsProvider.MULTI_ONE_NAME),true);
        await(()->find(this::openPickerButton)!=null,"A real long press enters SAF multiple-selection mode");
        touchSystemDocument(awaitFixtureDocument(BrowserDocumentsProvider.MULTI_TWO_NAME));
        touchSystemDocument(awaitNode(this::openPickerButton));
        await(()->!isDocumentsUi(),"The real SAF OPEN action returns both chosen documents");
    }
    private boolean openPickerButton(AccessibilityNodeInfo node){
        if(!systemDocumentNode(node))return false;
        String text=String.valueOf(node.getText()).trim().toUpperCase(java.util.Locale.ROOT);
        String description=String.valueOf(node.getContentDescription()).trim().toUpperCase(java.util.Locale.ROOT);
        String id=node.getViewIdResourceName(),type=String.valueOf(node.getClassName());
        boolean label=text.equals("OPEN")||text.equals("SELECT")||description.equals("OPEN")||description.equals("SELECT");
        // Android 8.1 action_mode_menu names its Open TextView action_menu_open.
        // Its framework AX class is TextView, so the exact resource ID is essential.
        return label&&((id!=null&&(id.endsWith(":id/menu_open")||id.endsWith(":id/action_menu_open")||id.endsWith(":id/action_menu_select")||id.equals("android:id/button1")))||type.contains("Button")||type.contains("MenuItemView"));
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
    private boolean enabledDocumentAncestors(AccessibilityNodeInfo node){
        for(int depth=0;node!=null&&depth<12;depth++,node=node.getParent())if(!node.isEnabled())return false;
        return true;
    }
    private static boolean systemDocumentNode(AccessibilityNodeInfo node){return node.isVisibleToUser()&&node.isEnabled()&&node.getPackageName()!=null&&node.getPackageName().toString().contains("documentsui");}
    private void touchSystemDocument(AccessibilityNodeInfo node){touchSystemDocument(node,false);}
    private void touchSystemDocument(AccessibilityNodeInfo node,boolean hold){
        assertTrue("Only visible system document controls are touched",systemDocumentNode(node));
        final String text=String.valueOf(node.getText()),id=node.getViewIdResourceName(),type=String.valueOf(node.getClassName()),description=String.valueOf(node.getContentDescription());
        final boolean sidebar=fixtureSidebarRoot(node);
        try{instrumentation.getUiAutomation().waitForIdle(100,1000);}catch(java.util.concurrent.TimeoutException backgroundEvents){/* Bounds must still stabilize below. */}
        Rect previous=null,bounds=null;long stableSince=0,deadline=SystemClock.elapsedRealtime()+2000;
        while(SystemClock.elapsedRealtime()<deadline) {
            AccessibilityNodeInfo fresh=find(n->systemDocumentNode(n)&&text.equals(String.valueOf(n.getText()))&&
                java.util.Objects.equals(id,n.getViewIdResourceName())&&type.equals(String.valueOf(n.getClassName()))&&
                description.equals(String.valueOf(n.getContentDescription()))&&(!sidebar||fixtureSidebarRoot(n))&&(!selectableFixtureName(text)||enabledDocumentAncestors(n)));
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
        if(hold)longPress(bounds.exactCenterX(),bounds.exactCenterY());else tap(bounds.exactCenterX(),bounds.exactCenterY());
    }
    private boolean isDocumentsUi(){return find(n->n.getPackageName()!=null&&n.getPackageName().toString().contains("documentsui"))!=null;}
    private void saveFailureArtifacts(){
        try {
            if(instrumentation==null||app==null||!"1".equals(fixtureShell("getprop ro.kernel.qemu").trim()))return;
            String method=testName.getMethodName();
            if(!Arrays.asList("realFileInputSafSelectionReadsExactPngAndCancelPathsDoNotSelect",
                "realPickerResultAfterPageNavigationIsRejectedAndSensitivePageCannotChoose",
                "trustedWindowOpenUsesNativeConfirmationAndNeverRunsChildContent",
                "realMp4SafSelectionReadsCompleteFileAndUserGesturePlaysSelectedLocalVideo",
                "realSafMultipleSelectionReturnsOnlyTwoChosenPngFilesWithoutAssumingOrder",
                "realSafResultAfterSameDocumentSensitiveRouteAndHomeRoundTripIsRejected").contains(method))return;
            AccessibilityNodeInfo active=instrumentation.getUiAutomation().getRootInActiveWindow();
            if(active==null||active.getPackageName()==null)return;
            String owner=active.getPackageName().toString();
            if(!app.getPackageName().equals(owner)&&!(owner.contains("documentsui")&&(fixtureDirectoryConfirmed||fixtureToolbarVisible()||find(this::fixtureSidebarRoot)!=null)))return;
            if("realSafResultAfterSameDocumentSensitiveRouteAndHomeRoundTripIsRejected".equals(method))recordSyntheticSpaState();
            String name="browser-"+method;File directory=app.getExternalFilesDir("xs-governance-ui");
            if(directory==null||(!directory.isDirectory()&&!directory.mkdirs()))return;
            StringBuilder ax=new StringBuilder();
            for(android.view.accessibility.AccessibilityWindowInfo window:instrumentation.getUiAutomation().getWindows()) {
                Rect bounds=new Rect();window.getBoundsInScreen(bounds);ax.append("WINDOW type=").append(window.getType()).append(" active=").append(window.isActive()).append(" focused=").append(window.isFocused()).append(" bounds=").append(bounds).append('\n');
            }
            visit(active,n->{
                if(ax.length()>=65536)return false;
                Rect bounds=new Rect();n.getBoundsInScreen(bounds);
                String text=diagnosticText(n.isPassword()?"[password field]":n.getText());
                String description=diagnosticText(n.isPassword()?"[password field]":n.getContentDescription());
                AccessibilityNodeInfo parent=n.getParent();
                ax.append(n.getClassName()).append('\t').append(n.getViewIdResourceName()).append('\t').append(bounds)
                    .append(" visible=").append(n.isVisibleToUser()).append(" enabled=").append(n.isEnabled()).append(" clickable=").append(n.isClickable())
                    .append(" longClickable=").append(n.isLongClickable()).append(" selected=").append(n.isSelected()).append(" checked=").append(n.isChecked())
                    .append(" scrollable=").append(n.isScrollable()).append(" actions=").append(n.getActions())
                    .append(" parent=").append(parent==null?"none":parent.getViewIdResourceName())
                    .append(" text=").append(text).append(" description=").append(description).append('\n');return false;
            },new int[]{0});
            if(ax.length()>65536)ax.setLength(65536);
            File axFile=new File(directory,name+".ax.txt");Files.write(axFile.toPath(),ax.toString().getBytes(StandardCharsets.UTF_8));
            // Persist AX first. A secure-window screenshot failure must not discard the
            // only native hierarchy, and a logcat fallback survives any later copy issue.
            android.util.Log.i("XsBrowserFixture","SYNTHETIC_AX_BEGIN "+method);
            for(int start=0;start<Math.min(ax.length(),32768);start+=2500)
                android.util.Log.i("XsBrowserFixture",ax.substring(start,Math.min(start+2500,Math.min(ax.length(),32768))));
            android.util.Log.i("XsBrowserFixture","SYNTHETIC_AX_END "+method);
            preserveDiagnostic(axFile,name+".ax.txt");
            File pngFile=new File(directory,name+".png");
            try {
                Bitmap shot=instrumentation.getUiAutomation().takeScreenshot();
                if(shot!=null)try(FileOutputStream out=new FileOutputStream(pngFile)){shot.compress(Bitmap.CompressFormat.PNG,100,out);}finally{shot.recycle();}
            }catch(Throwable screenshotUnavailable){android.util.Log.i("XsBrowserFixture","Synthetic screenshot unavailable; AX already preserved");}
            preserveDiagnostic(pngFile,name+".png");
        }catch(Throwable unavailable){android.util.Log.i("XsBrowserFixture","Synthetic emulator failure capture unavailable");}
    }
    private void recordSyntheticSpaState(){
        // Called only behind the emulator, fixed-method and synthetic-window gates.
        // No body text, arbitrary URLs, selected bytes or browser storage is logged.
        try {
            Object state=jsonFromPage("(function(){function route(value){return value==='"+HOME+"'?'HOME':value==='"+SENSITIVE+"'?'SENSITIVE':'OTHER'}return {visibility:document.visibilityState,armed:window.spaRouteArmed===true,trusted:window.spaArmTrusted===true,runs:Number(window.spaRouteRuns)||0,visitCount:Array.isArray(window.spaVisitLog)?window.spaVisitLog.length:-1,visits:Array.isArray(window.spaVisitLog)?window.spaVisitLog.slice(0,8).map(route):[],sameDocument:window.spaSameDocument===true,location:route(location.href),fileCount:document.getElementById('file').files.length}})()");
            String text=String.valueOf(state);if(text.length()>2048)text=text.substring(0,2048);
            List<String> routes=new ArrayList<>();for(String value:nativeHistoryCallbacks){if(routes.size()>=16)break;routes.add(HOME.equals(value)?"HOME":SENSITIVE.equals(value)?"SENSITIVE":"OTHER");}
            android.util.Log.i("XsBrowserFixture","SYNTHETIC_SPA_STATE "+text+" nativeRouteCount="+nativeHistoryCallbacks.size()+" nativeRoutes="+routes+" uploadPending="+(field("upload")!=null));
        }catch(Throwable unavailable){android.util.Log.i("XsBrowserFixture","Synthetic SPA scalar diagnostic unavailable");}
    }
    private static String diagnosticText(CharSequence value){String text=String.valueOf(value).replace('\n',' ').replace('\t',' ');return text.length()>256?text.substring(0,256):text;}
    private void preserveDiagnostic(File file,String name){
        if(!file.isFile())return;
        try {
            String source=file.getAbsolutePath(),target="/data/local/tmp/xs-governance-ui/"+name;
            // UiAutomation executes argv through Runtime.exec, not a shell. These paths
            // are generated from the fixed whitelist and contain no whitespace/metacharacters.
            if(!source.matches("[/A-Za-z0-9._-]+")||!target.matches("[/A-Za-z0-9._-]+"))return;
            fixtureShell("mkdir -p /data/local/tmp/xs-governance-ui");fixtureShell("chmod 700 /data/local/tmp/xs-governance-ui");
            fixtureShell("cp "+source+" "+target);
            boolean copied=target.equals(fixtureShell("ls "+target).trim());
            android.util.Log.i("XsBrowserFixture","Synthetic diagnostic "+name+" bytes="+file.length()+" preserved="+copied);
        }catch(Throwable unavailable){android.util.Log.i("XsBrowserFixture","Synthetic diagnostic copy unavailable");}
    }
    private String fixtureShell(String command)throws java.io.IOException {
        try(android.os.ParcelFileDescriptor.AutoCloseInputStream input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation().executeShellCommand(command))) {
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] bytes=new byte[1024];int n;while((n=input.read(bytes))!=-1)if(out.size()<8192)out.write(bytes,0,Math.min(n,8192-out.size()));return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
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
    private void openSystemPicker()throws Exception {openSystemPicker("file");}
    private void openSystemPicker(String input)throws Exception {fixtureDirectoryConfirmed=false;tapHtml(input);await(()->dialog("uploadDialog"),"Trusted file-input gesture opens native consent");dialogButton(true);await(this::isDocumentsUi,"Actual ACTION_OPEN_DOCUMENT runs");}

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
        tapHtml("arm");awaitJs("window.rejectOnHide===true");openSystemPicker();
        await(()->SENSITIVE.equals(currentUrl()),"Visibility transition performs the fixture's real navigation while SAF is open");
        chooseFixture("xs-browser-boundary.png");
        awaitJs("location.href==='"+SENSITIVE+"'&&document.getElementById('file').files.length===0&&window.uploadResult===null");assertNull(field("upload"));
        tapHtml("file");SystemClock.sleep(300);assertFalse("Security route never opens native file consent",dialog("uploadDialog"));assertFalse(isDocumentsUi());awaitJs("document.getElementById('file').files.length===0&&window.uploadResult===null");bodyCompleted=true;
    }
    @SuppressWarnings("unchecked") private Object jsonFromPage(String expression)throws Exception {
        Object encoded=StrictJson.parse(evaluate("JSON.stringify("+expression+")"));
        assertTrue("The synthetic page returns JSON text",encoded instanceof String);return StrictJson.parse((String)encoded);
    }
    private byte[] videoAsset()throws Exception {
        try(InputStream input=instrumentation.getContext().getAssets().open("standard-video.mp4.b64");ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))!=-1){assertTrue(bytes.size()+n<=16384);bytes.write(buffer,0,n);}
            return Base64.decode(bytes.toByteArray(),Base64.DEFAULT);
        }
    }
    private static String sha256(byte[] bytes)throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder out=new StringBuilder(64);
        for(byte value:digest){out.append(Character.forDigit((value>>>4)&15,16));out.append(Character.forDigit(value&15,16));}return out.toString();
    }
    private static byte[] fileReaderBytes(Map<String,Object> result,String mime) {
        assertEquals(mime,result.get("type"));assertTrue(result.get("data") instanceof String);
        String data=(String)result.get("data"),prefix="data:"+mime+";base64,";
        assertTrue("FileReader returns the actual selected media type",data.startsWith(prefix));return Base64.decode(data.substring(prefix.length()),Base64.DEFAULT);
    }
    private Uri document(String id){return DocumentsContract.buildDocumentUri(BrowserDocumentsProvider.AUTHORITY,id);}
    private void assertNeverSelectedDenied(){
        Uri sibling=document(BrowserDocumentsProvider.NEVER_SELECTED);
        assertEquals("The never-selected sibling has no inherited document permission",PackageManager.PERMISSION_DENIED,
            app.checkUriPermission(sibling,Process.myPid(),app.getApplicationInfo().uid,Intent.FLAG_GRANT_READ_URI_PERMISSION));
        try(InputStream ignored=app.getContentResolver().openInputStream(sibling)){fail("An unselected sibling must not be opened");}
        catch(SecurityException|FileNotFoundException expected){}catch(java.io.IOException unavailable){throw new AssertionError(unavailable);}
    }
    private void assertSelectedReadOnly(String id)throws Exception {
        Uri selected=document(id);assertEquals(PackageManager.PERMISSION_GRANTED,
            app.checkUriPermission(selected,Process.myPid(),app.getApplicationInfo().uid,Intent.FLAG_GRANT_READ_URI_PERMISSION));
        try{android.os.ParcelFileDescriptor write=app.getContentResolver().openFileDescriptor(selected,"w");if(write!=null)write.close();fail("A real SAF selection grants no writable synthetic document");}
        catch(SecurityException|FileNotFoundException expected){}
    }
    @SuppressWarnings("unchecked") @Test public void realMp4SafSelectionReadsCompleteFileAndUserGesturePlaysSelectedLocalVideo()throws Exception {
        byte[] expected=videoAsset();assertEquals(BrowserDocumentsProvider.VIDEO_BYTES,expected.length);assertEquals(BrowserDocumentsProvider.VIDEO_SHA256,sha256(expected));
        assertNeverSelectedDenied();openSystemPicker("videoFile");chooseFixture(BrowserDocumentsProvider.VIDEO_NAME);
        awaitJs("window.videoResult&&window.videoResult.name==='"+BrowserDocumentsProvider.VIDEO_NAME+"'&&window.videoResult.type==='video/mp4'&&window.videoResult.size==="+BrowserDocumentsProvider.VIDEO_BYTES);
        Map<String,Object> result=(Map<String,Object>)jsonFromPage("window.videoResult");byte[] actual=fileReaderBytes(result,"video/mp4");
        assertEquals(BrowserDocumentsProvider.VIDEO_BYTES,actual.length);assertEquals(BrowserDocumentsProvider.VIDEO_SHA256,sha256(actual));assertArrayEquals(expected,actual);
        assertSelectedReadOnly(BrowserDocumentsProvider.VIDEO);assertNeverSelectedDenied();assertNull(field("upload"));
        awaitJs("window.videoState.metadata&&window.videoState.width===64&&window.videoState.height===64&&window.videoState.duration>0.8&&window.videoState.duration<1.5");
        awaitJs("document.getElementById('videoPreview').paused===true&&document.getElementById('videoPreview').autoplay===false&&!window.videoState.playGestureTrusted&&!window.videoState.playing");
        tapHtml("playVideo");
        awaitJs("window.videoState.playGestureTrusted===true&&window.videoState.playing===true&&window.videoState.ended===true&&window.videoState.timeUpdates>0&&window.videoState.currentTime>0.8&&window.videoState.error===null");
        bodyCompleted=true;
    }
    @SuppressWarnings("unchecked") @Test public void realSafMultipleSelectionReturnsOnlyTwoChosenPngFilesWithoutAssumingOrder()throws Exception {
        assertNeverSelectedDenied();
        for(String id:new String[]{BrowserDocumentsProvider.MULTI_ONE,BrowserDocumentsProvider.MULTI_TWO})assertEquals("This distinct document starts without an earlier legitimate grant",PackageManager.PERMISSION_DENIED,
            app.checkUriPermission(document(id),Process.myPid(),app.getApplicationInfo().uid,Intent.FLAG_GRANT_READ_URI_PERMISSION));
        openSystemPicker("multiFile");chooseTwoPngFixtures();
        awaitJs("document.getElementById('multiFile').files.length===2&&window.multiResult&&window.multiResult.length===2&&window.multiError===null");
        Object result=jsonFromPage("window.multiResult");assertTrue(result instanceof List);assertEquals(2,((List<?>)result).size());
        Map<String,byte[]> expected=new HashMap<>();expected.put(BrowserDocumentsProvider.MULTI_ONE_NAME,Base64.decode(BrowserDocumentsProvider.PNG_BASE64,Base64.NO_WRAP));
        expected.put(BrowserDocumentsProvider.MULTI_TWO_NAME,Base64.decode(BrowserDocumentsProvider.SECOND_PNG_BASE64,Base64.NO_WRAP));
        assertFalse("The two fixtures contain different bytes",Arrays.equals(expected.get(BrowserDocumentsProvider.MULTI_ONE_NAME),expected.get(BrowserDocumentsProvider.MULTI_TWO_NAME)));
        for(Object item:(List<?>)result) {
            assertTrue(item instanceof Map);Map<String,Object> file=(Map<String,Object>)item;
            assertTrue("Each returned filename is chosen exactly once",expected.containsKey(file.get("name")));
            byte[] bytes=expected.remove(file.get("name")),actual=fileReaderBytes(file,"image/png");assertEquals(bytes.length,((Number)file.get("size")).intValue());assertArrayEquals(bytes,actual);assertEquals(sha256(bytes),sha256(actual));
        }
        assertTrue(expected.isEmpty());assertSelectedReadOnly(BrowserDocumentsProvider.MULTI_ONE);assertSelectedReadOnly(BrowserDocumentsProvider.MULTI_TWO);
        assertNeverSelectedDenied();assertNull(field("upload"));bodyCompleted=true;
    }
    @Test public void realSafResultAfterSameDocumentSensitiveRouteAndHomeRoundTripIsRejected()throws Exception {
        nativeHistoryCallbacks.clear();tapHtml("armSpa");
        awaitJs("window.spaArmTrusted===true&&window.spaRouteArmed===true&&window.spaRouteRuns===0&&window.spaVisitLog.length===0");
        openSystemPicker();assertTrue("The actual system picker is foreground before the route stimulus",isDocumentsUi());
        assertNotNull("The real native upload callback is still pending before the route changes",field("upload"));
        // The tested boundary is an actual same-DOM route change while SAF is pending.
        // Invoke the synthetic page's once-armed closure through the standard WebView
        // API, avoiding dependence on delivery timing of background visibility events.
        assertEquals("The armed fixture executes its actual pushState pair once","true",evaluate("window.runArmedSpaRoute()"));
        awaitJs("window.spaRouteRuns===1&&window.spaVisitLog.length===2&&window.spaVisitLog[0]==='"+SENSITIVE+"'&&window.spaVisitLog[1]==='"+HOME+"'&&window.spaSameDocument===true&&location.href==='"+HOME+"'");
        await(()->{
            int sensitive=nativeHistoryCallbacks.indexOf(SENSITIVE);if(sensitive<0)return false;
            for(int i=sensitive+1;i<nativeHistoryCallbacks.size();i++)if(HOME.equals(nativeHistoryCallbacks.get(i)))return true;
            return false;
        },"Both transient route callbacks come from the real WebView in order");
        assertEquals("The final URL deliberately equals the original picker URL",HOME,currentUrl());
        chooseFixture(BrowserDocumentsProvider.SPA_RESULT_NAME);
        awaitJs("location.href==='"+HOME+"'&&window.spaVisitLog.length===2&&window.spaSameDocument===true&&document.getElementById('file').files.length===0&&window.uploadResult===null");
        assertNull("A transient sensitive route invalidates the old callback even after returning HOME",field("upload"));
        assertNeverSelectedDenied();bodyCompleted=true;
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

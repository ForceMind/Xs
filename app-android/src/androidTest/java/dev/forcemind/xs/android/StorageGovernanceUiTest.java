package dev.forcemind.xs.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Rect;
import android.os.LocaleList;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.forcemind.xs.core.ArchiveSearch;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Native controls plus isolated narrow/large-font configurations; no physical-device claim. */
@RunWith(AndroidJUnit4.class)
public final class StorageGovernanceUiTest {
    private Instrumentation instrumentation;private Context app;private XsArchiveDatabase db;private Activity screen;
    private String library,other;private final long now=System.currentTimeMillis();
    @Before public void setup(){instrumentation=InstrumentationRegistry.getInstrumentation();app=instrumentation.getTargetContext();AccessibilityServiceInfo access=instrumentation.getUiAutomation().getServiceInfo();access.flags|=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;instrumentation.getUiAutomation().setServiceInfo(access);db=new XsArchiveDatabase(app);library="storage-ui:"+UUID.randomUUID();other="storage-other:"+UUID.randomUUID();db.ensureLibrary(library,"Storage governance fixture");db.ensureLibrary(other,"Other fixture");}
    @After public void cleanup()throws Exception {
        if(screen!=null){instrumentation.runOnMainSync(screen::finish);instrumentation.waitForIdleSync();}
        for(String owner:new String[]{library,other}){CountDownLatch done=new CountDownLatch(1);assertTrue(XsLibraryWrites.invalidateAndSubmit(owner,()->{try{XsMediaRepository.get(app).eraseLibrary(owner);db.deleteLibraryScope(owner);}finally{done.countDown();}}));assertTrue(done.await(10,TimeUnit.SECONDS));}
        db.close();
    }
    @Test public void retentionRequiresPreviewConfirmationAndPreservesLifetimeFactsAndOtherLibrary()throws Exception {
        long old=now-45L*86400000L;String previous="https://x.com/i/web/status/3101";
        assertTrue(db.recordObservedOpen(library,previous,"author","old favorite",null,old,"storage-old-open"));
        assertTrue(db.recordMeasuredFeed(library,previous,"home_following","author","old favorite",null,old,"storage-old-feed",3500,3000));
        db.favorite(library,previous,"author","old favorite",null,old);
        assertTrue(db.recordObservedOpen(library,"https://x.com/i/web/status/3102","author","recent",null,now,"storage-new-open"));
        assertTrue(db.recordObservedOpen(other,"https://x.com/i/web/status/3103","other","other old",null,old,"storage-other-open"));
        open();assertEquals(0,XsRetentionSettings.getDays(db,library));
        click(XsLibraryManagementActivity.ID_RETENTION);clickText(label("7天","7 days"));awaitText(label("确认历史保留设置","Confirm history retention"));
        clickDialog(android.R.id.button2);awaitReady();assertEquals("Cancel must not save a policy",0,XsRetentionSettings.getDays(db,library));assertEquals(2,db.historyStats(library).openEventCount);
        click(XsLibraryManagementActivity.ID_RETENTION);clickText(label("7天","7 days"));awaitText(label("确认历史保留设置","Confirm history retention"));clickDialog(android.R.id.button1);
        await("retention policy and scoped pruning committed",()->XsRetentionSettings.getDays(db,library)==7&&db.historyStats(library).openEventCount==1&&db.historyStats(library).feedEventCount==0);awaitReady();
        XsArchiveDatabase.HistoryStats stats=db.historyStats(library);assertEquals(2,stats.postCount);assertEquals(2,stats.lifetimeOpenedCount);assertEquals(2,stats.everOpenedPostCount);assertEquals(1,stats.favoriteCount);assertTrue(stats.snapshotCount>=2);assertEquals(1,db.historyStats(other).openEventCount);assertEquals(0,XsRetentionSettings.getDays(db,other));
        ArchiveSearch.Filter never=new ArchiveSearch.Filter();never.neverOpened=true;try(Cursor rows=db.search(library,never)){assertEquals("Expired open details must not fabricate never-opened posts",0,rows.getCount());}
        final String[] displayed={""};instrumentation.runOnMainSync(()->displayed[0]=((TextView)screen.findViewById(XsLibraryManagementActivity.ID_STATS)).getText().toString());assertTrue(displayed[0].contains(label("累计打开次数 2","Lifetime opens 2")));assertTrue(displayed[0].contains(label("保留打开事件 1","Retained open events 1")));
    }
    @Test public void mediaLimitRequiresConfirmationAndAppliesOnlyToChosenLibrary()throws Exception {
        open();XsMediaRepository media=XsMediaRepository.get(app);assertEquals(0,media.usage(library).quotaBytes);
        click(XsLibraryManagementActivity.ID_QUOTA);clickText("100.0 MiB");awaitText(label("确认媒体配额","Confirm media limit"));clickDialog(android.R.id.button2);assertEquals(0,media.usage(library).quotaBytes);
        click(XsLibraryManagementActivity.ID_QUOTA);clickText("100.0 MiB");awaitText(label("确认媒体配额","Confirm media limit"));clickDialog(android.R.id.button1);
        await("chosen library quota persisted",()->media.usage(library).quotaBytes==100L*1024*1024);awaitReady();assertEquals(0,media.usage(other).quotaBytes);
        final String[] text={""};instrumentation.runOnMainSync(()->text[0]=((Button)screen.findViewById(XsLibraryManagementActivity.ID_QUOTA)).getText().toString());assertTrue(text[0].contains("100.0 MiB"));
    }
    @Test public void sameManagementControlsWrapAt320DpWithLargeFontsInBothLanguagesAndThemes(){
        final XsLibraryManagementActivity.Actions noop=new XsLibraryManagementActivity.Actions(){public void backup(){}public void retention(){}public void quota(){}public void clean(){}public void refresh(){}public void erase(){}public void back(){}};
        for(String language:new String[]{"en","zh"})for(boolean night:new boolean[]{false,true}){
            Configuration config=new Configuration(app.getResources().getConfiguration());config.setLocales(new LocaleList(Locale.forLanguageTag(language)));config.fontScale=1.8f;config.screenWidthDp=320;config.screenHeightDp=480;config.smallestScreenWidthDp=320;config.uiMode=(config.uiMode&~Configuration.UI_MODE_NIGHT_MASK)|(night?Configuration.UI_MODE_NIGHT_YES:Configuration.UI_MODE_NIGHT_NO);
            Context display=new ContextThemeWrapper(app.createConfigurationContext(config),night?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);
            instrumentation.runOnMainSync(()->{
                XsLibraryManagementActivity.Panel panel=new XsLibraryManagementActivity.Panel(display,noop);int width=Math.round(320*display.getResources().getDisplayMetrics().density),height=Math.round(480*display.getResources().getDisplayMetrics().density);
                panel.root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));panel.root.layout(0,0,width,height);
                ViewGroup content=(ViewGroup)panel.root.getChildAt(0);assertTrue("Long content must be scrollable at large font",content.getHeight()>height);
                for(int id:new int[]{XsLibraryManagementActivity.ID_RETENTION,XsLibraryManagementActivity.ID_CLEAN,XsLibraryManagementActivity.ID_QUOTA,XsLibraryManagementActivity.ID_REFRESH,XsLibraryManagementActivity.ID_DELETE}){
                    Button button=panel.root.findViewById(id);assertNotNull(button);assertTrue(button.getWidth()>0);assertTrue(button.getLeft()>=0&&button.getRight()<=content.getWidth());assertNotNull(button.getLayout());
                    assertEquals("Labels must not be ellipsized",0,button.getLayout().getEllipsisCount(button.getLayout().getLineCount()-1));
                    assertTrue("Button must contain every large-font line",button.getHeight()>=button.getLayout().getHeight()+button.getCompoundPaddingTop()+button.getCompoundPaddingBottom());
                    panel.root.scrollTo(0,Math.max(0,button.getBottom()-height));Rect visible=new Rect();assertTrue("Every action must be reachable by vertical scroll",button.getGlobalVisibleRect(visible));assertTrue(visible.height()>0);
                }
                assertTrue(panel.retention.getText().toString().contains(language.equals("zh")?"历史保留":"History retention"));assertTrue(panel.quota.getText().toString().contains(language.equals("zh")?"媒体配额":"Media limit"));
            });
        }
    }
    private void open(){screen=instrumentation.startActivitySync(XsLibraryManagementActivity.intent(app,library).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));instrumentation.waitForIdleSync();awaitReady();}
    private void awaitReady(){await("storage controls ready",()->{final boolean[] ready={false};instrumentation.runOnMainSync(()->ready[0]=screen.findViewById(XsLibraryManagementActivity.ID_RETENTION).isEnabled()&&screen.findViewById(XsLibraryManagementActivity.ID_QUOTA).isEnabled());return ready[0];});}
    private void click(int id){instrumentation.runOnMainSync(()->{View view=screen.findViewById(id);view.requestRectangleOnScreen(new Rect(0,0,view.getWidth(),view.getHeight()),true);assertTrue(view.performClick());});instrumentation.waitForIdleSync();}
    private void clickText(String label){AccessibilityNodeInfo node=textNode(label);assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK));instrumentation.waitForIdleSync();}
    private void awaitText(String label){textNode(label);}
    private AccessibilityNodeInfo textNode(String text){final AccessibilityNodeInfo[] result={null};await("visible text "+text,()->{AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow();if(root==null)return false;for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(text))if(text.contentEquals(node.getText()==null?"":node.getText())&&node.isVisibleToUser()){result[0]=node;return true;}return false;});return result[0];}
    private void clickDialog(int id){final AccessibilityNodeInfo[] found={null};String resource=app.getResources().getResourceName(id);await("dialog button "+resource,()->{AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow();if(root==null)return false;for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByViewId(resource))if(node.isVisibleToUser()&&node.isEnabled()){found[0]=node;return true;}return false;});assertTrue(found[0].performAction(AccessibilityNodeInfo.ACTION_CLICK));instrumentation.waitForIdleSync();}
    private interface Check{boolean passed();}
    private void await(String expected,Check check){long end=SystemClock.uptimeMillis()+8000;do{if(check.passed())return;SystemClock.sleep(30);}while(SystemClock.uptimeMillis()<end);fail("Timed out waiting for "+expected);}
    private String label(String zh,String en){return app.getResources().getConfiguration().getLocales().get(0).getLanguage().equals("zh")?zh:en;}
}

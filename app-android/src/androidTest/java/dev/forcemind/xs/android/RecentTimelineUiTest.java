package dev.forcemind.xs.android;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.LocaleList;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ContextThemeWrapper;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.ListView;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.forcemind.xs.core.ArchiveSearch;
import dev.forcemind.xs.core.SnapshotPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
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

/** Native offline recovery controls; generated metadata only, with live capture still gated off. */
@RunWith(AndroidJUnit4.class)
public final class RecentTimelineUiTest {
    private Instrumentation instrumentation;
    private Context app;
    private XsTimelineBuffer buffer;
    private XsTimelineBuffer.Settings previousSettings;
    private XsArchiveDatabase archive;
    private Activity screen;
    private String library,other;
    private boolean previousOpen,previousFeed;
    private String olderBatch,newerBatch;

    @Before public void setUp()throws Exception {
        instrumentation=InstrumentationRegistry.getInstrumentation();app=instrumentation.getTargetContext();
        AccessibilityServiceInfo info=instrumentation.getUiAutomation().getServiceInfo();
        info.flags|=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;instrumentation.getUiAutomation().setServiceInfo(info);
        buffer=XsTimelineBuffer.get(app);previousSettings=buffer.settings();buffer.configure(false,48,100);
        previousOpen=XsLibrarySettings.isOpenEnabled(app);previousFeed=XsLibrarySettings.isFeedEnabled(app);
        XsLibrarySettings.setOpenEnabled(app,true);XsLibrarySettings.setFeedEnabled(app,false);
        archive=new XsArchiveDatabase(app);library="recent-ui:"+UUID.randomUUID();other="recent-ui-other:"+UUID.randomUUID();
        queued(library,()->archive.ensureLibrary(library,"Recent timeline UI fixture"));
        queued(other,()->archive.ensureLibrary(other,"Other recent timeline fixture"));
    }
    @After public void tearDown()throws Exception {
        if(screen!=null){instrumentation.runOnMainSync(screen::finish);instrumentation.waitForIdleSync();}
        for(String owner:new String[]{library,other})if(owner!=null) {
            CountDownLatch done=new CountDownLatch(1);AtomicReference<Boolean> cleared=new AtomicReference<>();
            buffer.clear(owner,ok->{cleared.set(ok);done.countDown();});
            assertTrue("Scoped temporary cleanup finished",done.await(15,TimeUnit.SECONDS));assertEquals(Boolean.TRUE,cleared.get());
            queued(owner,()->archive.deleteLibraryScope(owner));
        }
        XsLibrarySettings.setOpenEnabled(app,previousOpen);XsLibrarySettings.setFeedEnabled(app,previousFeed);
        if(previousSettings!=null)buffer.configure(previousSettings.enabled,previousSettings.hours,previousSettings.budgetMiB);
        if(archive!=null)archive.close();
    }

    @Test public void offlineSearchBatchPagingAndPreviewDoNotCreateOpenOrDwellFacts()throws Exception {
        seedPages();open();awaitCount(50);
        assertTrue(listLabels().get(0).contains("refreshedcard"));assertTrue(enabled(XsRecentTimelineActivity.ID_NEXT));
        assertFalse(enabled(XsRecentTimelineActivity.ID_PREVIOUS));
        click(XsRecentTimelineActivity.ID_NEXT);awaitCount(5);
        assertTrue(listLabels().get(4).contains("pageend53"));assertFalse(enabled(XsRecentTimelineActivity.ID_NEXT));
        assertTrue(enabled(XsRecentTimelineActivity.ID_PREVIOUS));
        click(XsRecentTimelineActivity.ID_PREVIOUS);awaitCount(50);
        search("刷新之前中文");awaitCount(1);assertTrue(listLabels().get(0).contains("cjkfixtureauthor"));
        openRow(0);awaitText("Local temporary preview","本机临时预览");
        awaitText("刷新之前中文","刷新之前中文");awaitText("Partial visible text","可见的部分正文");
        assertEquals(0,archive.historyStats(library).openEventCount);assertEquals(0,archive.historyStats(library).feedEventCount);
        assertEquals(0,archive.historyStats(library).favoriteCount);assertEquals(0,archive.historyStats(library).postCount);
        clickDialog(android.R.id.button2);
        search("freshfixture");awaitCount(2);assertTrue(listLabels().get(0).contains("refreshedcard"));search("");awaitCount(50);
        click(XsRecentTimelineActivity.ID_BATCHES);awaitText("Refresh and browsing batches","刷新与浏览批次");
        clickTextContaining("Native refresh","原生刷新");awaitCount(2);
        assertTrue(listLabels().get(0).contains("refreshedcard"));assertTrue(listLabels().get(1).contains("secondfresh"));
        assertEquals(0,archive.historyStats(library).openEventCount);assertEquals(0,archive.historyStats(library).feedEventCount);
        assertEquals(55,buffer.stats(library).count);assertEquals(1,buffer.stats(other).count);
    }

    @Test public void independentSettingsRejectInvalidBoundsAndPersistBothValidExtremes()throws Exception {
        open();awaitStatus("Off","已关闭");click(XsRecentTimelineActivity.ID_SETTINGS);
        awaitText("Recent timeline settings","最近时间线设置");
        assertEquals("48",editors().get(0).getText().toString());assertEquals("100",editors().get(1).getText().toString());
        clickTextContaining("Enable recent timeline","开启最近时间线");setEditor(0,"0");setEditor(1,"24");
        clickDialog(android.R.id.button1);assertFalse(buffer.settings().enabled);assertEquals(48,buffer.settings().hours);
        assertEquals("Invalid bounds keep the dialog open",2,editors().size());
        setEditor(0,"169");setEditor(1,"501");clickDialog(android.R.id.button1);
        assertFalse(buffer.settings().enabled);assertEquals(100,buffer.settings().budgetMiB);
        assertEquals("Upper overflow keeps the same dialog open",2,editors().size());
        setEditor(0,"1");setEditor(1,"25");clickDialog(android.R.id.button1);
        await(()->buffer.settings().enabled&&buffer.settings().hours==1&&buffer.settings().budgetMiB==25,"The lower bounds were committed");
        awaitStatus("1 hours","1 小时");assertIndependentConsent();
        click(XsRecentTimelineActivity.ID_SETTINGS);awaitText("Recent timeline settings","最近时间线设置");
        clickTextContaining("Enable recent timeline","开启最近时间线");setEditor(0,"168");setEditor(1,"500");clickDialog(android.R.id.button1);
        await(()->!buffer.settings().enabled&&buffer.settings().hours==168&&buffer.settings().budgetMiB==500,"The upper bounds and independent opt-out were committed");
        awaitStatus("500 MiB","500 MiB");assertIndependentConsent();assertEquals(0,archive.historyStats(library).postCount);
    }

    @Test public void explicitNativeFavoriteSurvivesScopedBufferClearWithoutOpeningPost()throws Exception {
        seedPages();open();awaitCount(50);search("刷新之前中文");awaitCount(1);openRow(0);
        awaitText("Local temporary preview","本机临时预览");clickDialog(android.R.id.button1);
        await(()->archive.historyStats(library).favoriteCount==1,"The explicit native favorite committed");
        awaitCount(1);assertEquals(0,archive.historyStats(library).openEventCount);assertEquals(0,archive.historyStats(library).feedEventCount);
        click(XsRecentTimelineActivity.ID_CLEAR);awaitText("Clear this library's temporary timeline?","清空此空间的临时时间线？");
        clickDialog(android.R.id.button1);await(()->buffer.stats(library).count==0,"Only this temporary scope was erased");awaitCount(0);
        assertEquals(1,buffer.stats(other).count);assertEquals(1,archive.historyStats(library).favoriteCount);
        ArchiveSearch.Filter filter=new ArchiveSearch.Filter();filter.keyword="刷新之前中文";filter.favoriteOnly=true;
        try(Cursor rows=archive.search(library,filter)){assertEquals(1,rows.getCount());}
        assertEquals(0,archive.historyStats(library).openEventCount);assertEquals(0,archive.historyStats(library).feedEventCount);
    }

    @Test public void clockResetRequiresExplicitConfirmationClearsAllTemporaryScopesAndLeavesCaptureOff()throws Exception {
        seedPages();buffer.configure(true,72,75);
        queued(library,()->assertTrue(archive.favorite(library,"https://x.com/i/web/status/950010","durablefixture","permanent reset canary",null,System.currentTimeMillis())));
        open();awaitCount(50);click(XsRecentTimelineActivity.ID_SETTINGS);awaitText("Recent timeline settings","最近时间线设置");
        clickTextContaining("Reset all temporary buffers and clock","重置所有临时缓冲与时间");
        awaitText("Reset temporary buffers and clock for every library?","重置所有空间的临时缓冲与时间？");
        awaitText("ALL local libraries","所有本机资料空间");
        assertEquals(55,buffer.stats(library).count);assertEquals(1,buffer.stats(other).count);assertTrue(buffer.settings().enabled);
        clickDialog(android.R.id.button2);awaitText("Recent timeline settings","最近时间线设置");
        assertEquals(55,buffer.stats(library).count);assertEquals(1,buffer.stats(other).count);assertTrue(buffer.settings().enabled);
        clickTextContaining("Reset all temporary buffers and clock","重置所有临时缓冲与时间");
        awaitText("Reset temporary buffers and clock for every library?","重置所有空间的临时缓冲与时间？");clickDialog(android.R.id.button1);
        await(()->!buffer.settings().enabled&&buffer.stats(library).count==0&&buffer.stats(other).count==0,
            "Explicit confirmation reset every temporary scope and left capture off");
        awaitCount(0);awaitStatus("72 hours","72 小时");
        assertFalse(hasText("Recent timeline settings","最近时间线设置"));
        assertEquals(72,buffer.settings().hours);assertEquals(75,buffer.settings().budgetMiB);assertIndependentConsent();
        assertEquals(1,archive.historyStats(library).favoriteCount);assertEquals(0,archive.historyStats(library).openEventCount);
        ArchiveSearch.Filter favorite=new ArchiveSearch.Filter();favorite.favoriteOnly=true;favorite.keyword="permanent reset canary";
        try(Cursor rows=archive.search(library,favorite)){assertEquals(1,rows.getCount());}
    }

    @Test public void alreadyOpenTemporaryPreviewExpiresAndExternalClearDismissesItWithoutTap()throws Exception {
        seedOne("950001","expiringpreviewneedle");open();awaitCount(1);openRow(0);
        awaitText("Local temporary preview","本机临时预览");awaitText("expiringpreviewneedle","expiringpreviewneedle");
        // Shorten only this generated appearance's real SQLite deadline after the native
        // dialog is visible. The app's native wall/elapsed clocks remain unchanged.
        queued(library,()->{synchronized(buffer){buffer.getWritableDatabase().execSQL(
            "UPDATE timeline_appearances SET expires_at_ms=? WHERE library_id=? AND post_id='950001'",
            new Object[]{System.currentTimeMillis()+1800,library});}});
        await(()->!hasText("Local temporary preview","本机临时预览"),"An open expired preview dismissed itself without another gesture");
        awaitCount(0);assertNull(buffer.entry(library,firstAppearanceId("950001")));
        seedOne("950002","clearpreviewneedle");search("clearpreviewneedle");awaitCount(1);openRow(0);
        awaitText("Local temporary preview","本机临时预览");
        CountDownLatch cleared=new CountDownLatch(1);AtomicReference<Boolean> clearedSuccessfully=new AtomicReference<>();
        buffer.clear(library,ok->{clearedSuccessfully.set(ok);cleared.countDown();});
        assertTrue(cleared.await(15,TimeUnit.SECONDS));assertEquals(Boolean.TRUE,clearedSuccessfully.get());
        await(()->!hasText("Local temporary preview","本机临时预览"),"A preview of an externally cleared card was dismissed");awaitCount(0);
        await(()->!hasVisibleBodyText("expiringpreviewneedle","clearpreviewneedle"),"Cleared card bodies disappear from visible noneditable text after the actual render frame");
        String[] preservedQuery={""};instrumentation.runOnMainSync(()->preservedQuery[0]=((EditText)screen.findViewById(XsRecentTimelineActivity.ID_QUERY)).getText().toString());
        assertEquals("Clearing temporary cards does not erase the user's search input","clearpreviewneedle",preservedQuery[0]);
        assertEquals(0,archive.historyStats(library).openEventCount);assertEquals(0,archive.historyStats(library).postCount);
    }

    @Test public void delayedPreviewCannotReappearAfterActualPauseResumeOrAfterClosingNewPreview()throws Exception {
        seedOne("950003","lifecyclepreviewneedle");open();awaitCount(1);
        CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1),paused=new CountDownLatch(1),resumedAgain=new CountDownLatch(1);
        AtomicReference<Throwable> blockingFailure=new AtomicReference<>();
        Thread blocker=new Thread(()->{synchronized(buffer){locked.countDown();try{
            if(!release.await(15,TimeUnit.SECONDS))blockingFailure.set(new AssertionError("Fixture store lock was not released"));
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();blockingFailure.set(interrupted);}}},"xs-ui-fixture-store-lock");
        Application application=(Application)app.getApplicationContext();Activity original=screen;
        Application.ActivityLifecycleCallbacks lifecycle=new Application.ActivityLifecycleCallbacks(){
            public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}
            public void onActivityResumed(Activity a){if(a==original)resumedAgain.countDown();}
            public void onActivityPaused(Activity a){if(a==original)paused.countDown();}
            public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}
        };
        Activity cover=null;application.registerActivityLifecycleCallbacks(lifecycle);
        try {
            blocker.start();assertTrue(locked.await(5,TimeUnit.SECONDS));openRow(0);
            // A real second Activity covers Recent while its requested detail read waits.
            cover=instrumentation.startActivitySync(new Intent(app,XsHistoryActivity.class)
                .putExtra(XsHistoryActivity.EXTRA_LIBRARY,library).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            assertTrue("The original Activity actually paused",paused.await(5,TimeUnit.SECONDS));
            Activity finishing=cover;instrumentation.runOnMainSync(finishing::finish);
            assertTrue("The original Activity actually resumed",resumedAgain.await(5,TimeUnit.SECONDS));
            search("notpresentafterresume");release.countDown();blocker.join(5000);
            assertFalse(blocker.isAlive());assertNull(blockingFailure.get());awaitCount(0);
            assertFalse("An old worker result cannot show a dialog in a new lifecycle epoch",hasText("Local temporary preview","本机临时预览"));
            search("");awaitCount(1);openRow(0);awaitText("Local temporary preview","本机临时预览");clickDialog(android.R.id.button2);
            search("notpresentafterclose");awaitCount(0);
            assertFalse("A closed current preview cannot be revived by an old callback",hasText("Local temporary preview","本机临时预览"));
            assertEquals(0,archive.historyStats(library).openEventCount);
        }finally {
            release.countDown();blocker.join(5000);application.unregisterActivityLifecycleCallbacks(lifecycle);
            if(cover!=null){Activity closing=cover;instrumentation.runOnMainSync(closing::finish);}
        }
    }

    @Test public void productionPanelActionsRemainReachableAt320DpLargeFontInBothLocalesAndThemes() {
        open();awaitStatus("Off","已关闭");
        for(String language:new String[]{"en","zh"})for(boolean night:new boolean[]{false,true}) {
            Configuration configuration=new Configuration(app.getResources().getConfiguration());
            configuration.setLocales(new LocaleList(Locale.forLanguageTag(language)));configuration.fontScale=1.8f;
            configuration.screenWidthDp=320;configuration.screenHeightDp=480;configuration.smallestScreenWidthDp=320;
            configuration.uiMode=(configuration.uiMode&~Configuration.UI_MODE_NIGHT_MASK)|(night?Configuration.UI_MODE_NIGHT_YES:Configuration.UI_MODE_NIGHT_NO);
            Context display=new ContextThemeWrapper(app.createConfigurationContext(configuration),
                night?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);
            instrumentation.runOnMainSync(()->{
                ScrollView panel=((XsRecentTimelineActivity)screen).buildPanel(display);
                int width=Math.round(320*display.getResources().getDisplayMetrics().density),height=Math.round(480*display.getResources().getDisplayMetrics().density);
                panel.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));panel.layout(0,0,width,height);
                ViewGroup content=(ViewGroup)panel.getChildAt(0);assertTrue("Large-font content is vertically scrollable",content.getHeight()>height);
                for(int id:new int[]{XsRecentTimelineActivity.ID_BATCHES,XsRecentTimelineActivity.ID_SETTINGS,
                        XsRecentTimelineActivity.ID_PREVIOUS,XsRecentTimelineActivity.ID_NEXT,XsRecentTimelineActivity.ID_SELECT,
                        XsRecentTimelineActivity.ID_CLEAR,XsRecentTimelineActivity.ID_SAVE,XsRecentTimelineActivity.ID_FAVORITE}) {
                    Button button=panel.findViewById(id);assertNotNull(button);assertTrue(button.getWidth()>0&&button.getHeight()>0);
                    Rect bounds=new Rect(0,0,button.getWidth(),button.getHeight());content.offsetDescendantRectToMyCoords(button,bounds);
                    assertTrue("Actions stay within the narrow content width",bounds.left>=0&&bounds.right<=content.getWidth());
                    assertNotNull(button.getLayout());assertEquals("No action label is ellipsized",0,button.getLayout().getEllipsisCount(button.getLayout().getLineCount()-1));
                    assertTrue("Every large-font label line fits its control",button.getHeight()>=button.getLayout().getHeight()+button.getCompoundPaddingTop()+button.getCompoundPaddingBottom());
                    panel.scrollTo(0,Math.max(0,bounds.bottom-height));Rect visible=new Rect();
                    assertTrue("Every action is reachable by vertical scrolling",button.getGlobalVisibleRect(visible)&&visible.height()>0&&visible.width()>0);
                }
                Button settings=panel.findViewById(XsRecentTimelineActivity.ID_SETTINGS);
                assertEquals(language.equals("zh")?"设置":"Settings",settings.getText().toString());
                EditText query=panel.findViewById(XsRecentTimelineActivity.ID_QUERY);
                assertTrue(query.getHint().toString().contains(language.equals("zh")?"搜索":"Search"));
            });
        }
    }

    private void seedOne(String post,String body)throws Exception {
        buffer.configure(true,48,100);XsTimelineBuffer.Ticket ticket=buffer.ticket(library);long now=System.currentTimeMillis();
        queued(library,()->assertTrue(buffer.append(ticket,new XsTimelineBuffer.Batch("native:one:"+UUID.randomUUID(),"native_navigation","unknown"),
            new XsTimelineBuffer.Candidate("https://x.com/i/web/status/"+post,"onefixture",body,null,SnapshotPolicy.Quality.PARTIAL,now,1))));
    }
    private long firstAppearanceId(String post){
        try(Cursor row=buffer.getReadableDatabase().rawQuery("SELECT id FROM timeline_appearances WHERE library_id=? AND post_id=?",new String[]{library,post})){
            return row.moveToFirst()?row.getLong(0):-1;
        }
    }
    private boolean hasVisibleBodyText(String en,String zh){
        return collect(instrumentation.getUiAutomation().getRootInActiveWindow(),n->n.isVisibleToUser()&&!n.isEditable()&&
            n.getClassName()!=null&&n.getClassName().toString().endsWith("TextView")&&contains(n,en,zh),new int[]{0})!=null;
    }
    private boolean hasText(String en,String zh){return collect(instrumentation.getUiAutomation().getRootInActiveWindow(),n->n.isVisibleToUser()&&contains(n,en,zh),new int[]{0})!=null;}

    private void seedPages()throws Exception {
        buffer.configure(true,48,100);long now=System.currentTimeMillis();
        olderBatch="native:older:"+UUID.randomUUID();newerBatch="native:refresh:"+UUID.randomUUID();
        XsTimelineBuffer.Ticket ticket=buffer.ticket(library);
        queued(library,()->{
            XsTimelineBuffer.Batch old=new XsTimelineBuffer.Batch(olderBatch,"native_navigation","home_following");
            for(int i=1;i<=53;i++)assertTrue(buffer.append(ticket,old,new XsTimelineBuffer.Candidate(
                "https://x.com/i/web/status/"+(920000+i),i==1?"cjkfixtureauthor":"fixtureauthor",
                i==1?"刷新之前中文 可离线找回":i==53?"pageend53":"oldercard "+i,null,SnapshotPolicy.Quality.PARTIAL,now-2000,i)));
            XsTimelineBuffer.Batch fresh=new XsTimelineBuffer.Batch(newerBatch,"native_refresh","home_for_you");
            assertTrue(buffer.append(ticket,fresh,new XsTimelineBuffer.Candidate("https://x.com/i/web/status/930001","freshfixture","refreshedcard",null,SnapshotPolicy.Quality.PARTIAL,now-1000,1)));
            assertTrue(buffer.append(ticket,fresh,new XsTimelineBuffer.Candidate("https://x.com/i/web/status/930002","freshfixture","secondfresh",null,SnapshotPolicy.Quality.PARTIAL,now-1000,2)));
        });
        XsTimelineBuffer.Ticket second=buffer.ticket(other);
        queued(other,()->assertTrue(buffer.append(second,new XsTimelineBuffer.Batch("native:other:"+UUID.randomUUID(),"native_navigation","unknown"),
            new XsTimelineBuffer.Candidate("https://x.com/i/web/status/940001","otherfixture","different library",null,SnapshotPolicy.Quality.PARTIAL,now,1))));
        assertEquals(55,buffer.stats(library).count);
    }
    private void open(){screen=instrumentation.startActivitySync(XsRecentTimelineActivity.intent(app,library).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));instrumentation.waitForIdleSync();}
    private void assertIndependentConsent(){assertTrue(XsLibrarySettings.isOpenEnabled(app));assertFalse(XsLibrarySettings.isFeedEnabled(app));}
    private void queued(String owner,Runnable work)throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();
        assertTrue(XsLibraryWrites.submit(owner,()->{try{work.run();}catch(Throwable problem){failure.set(problem);}finally{done.countDown();}}));
        assertTrue("Shared library writer finished",done.await(20,TimeUnit.SECONDS));if(failure.get()!=null)throw new AssertionError(failure.get());
    }
    private void search(String text){instrumentation.runOnMainSync(()->((EditText)screen.findViewById(XsRecentTimelineActivity.ID_QUERY)).setText(text));}
    private List<String> listLabels(){List<String> labels=new ArrayList<>();instrumentation.runOnMainSync(()->{ListView list=screen.findViewById(XsRecentTimelineActivity.ID_RESULTS);for(int i=0;i<list.getAdapter().getCount();i++)labels.add(String.valueOf(list.getAdapter().getItem(i)));});return labels;}
    private void awaitCount(int count){await(()->listLabels().size()==count,"Native result count "+count);}
    private boolean enabled(int id){boolean[] enabled={false};instrumentation.runOnMainSync(()->enabled[0]=screen.findViewById(id).isEnabled());return enabled[0];}
    private void openRow(int index){instrumentation.runOnMainSync(()->{ListView list=screen.findViewById(XsRecentTimelineActivity.ID_RESULTS);View row=list.getChildAt(index-list.getFirstVisiblePosition());assertNotNull("The selected native row is visible",row);assertTrue(list.performItemClick(row,index,list.getAdapter().getItemId(index)));});}
    private void click(int id){
        instrumentation.runOnMainSync(()->{View view=screen.findViewById(id);assertNotNull(view);view.requestRectangleOnScreen(new Rect(0,0,view.getWidth(),view.getHeight()),true);});instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(()->{View view=screen.findViewById(id);assertTrue(view.isEnabled());Rect visible=new Rect();assertTrue("Native action is reachable",view.getGlobalVisibleRect(visible)&&visible.width()>0&&visible.height()>0);assertTrue(view.performClick());});instrumentation.waitForIdleSync();
    }
    private void awaitStatus(String en,String zh){await(()->{String[] text={""};instrumentation.runOnMainSync(()->text[0]=((TextView)screen.findViewById(XsRecentTimelineActivity.ID_STATUS)).getText().toString());return text[0].contains(en)||text[0].contains(zh);},"Native coverage/status text");}
    private List<AccessibilityNodeInfo> editors(){List<AccessibilityNodeInfo> found=new ArrayList<>();collect(instrumentation.getUiAutomation().getRootInActiveWindow(),n->{if(n.isVisibleToUser()&&n.getClassName()!=null&&n.getClassName().toString().endsWith("EditText"))found.add(n);return false;},new int[]{0});return found;}
    private void setEditor(int index,String value){List<AccessibilityNodeInfo> fields=editors();assertTrue(fields.size()>index);Bundle text=new Bundle();text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);assertTrue(fields.get(index).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,text));}
    private void clickDialog(int id){String resource=app.getResources().getResourceName(id);AccessibilityNodeInfo node=awaitNode(n->n.isVisibleToUser()&&n.isEnabled()&&resource.equals(n.getViewIdResourceName()));assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK));instrumentation.waitForIdleSync();}
    private void clickTextContaining(String en,String zh){
        AccessibilityNodeInfo candidate=awaitNode(n->n.isEnabled()&&contains(n,en,zh));
        candidate.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
        AccessibilityNodeInfo node=awaitNode(n->n.isVisibleToUser()&&n.isEnabled()&&contains(n,en,zh));
        for(int depth=0;node!=null&&depth<8;depth++,node=node.getParent())if(node.isClickable()){
            assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK));instrumentation.waitForIdleSync();return;
        }fail("Native control is not clickable");
    }
    private void awaitText(String en,String zh){awaitNode(n->n.isVisibleToUser()&&contains(n,en,zh));}
    private static boolean contains(AccessibilityNodeInfo node,String en,String zh){String text=node.getText()==null?"":node.getText().toString();return text.contains(en)||text.contains(zh);}
    private AccessibilityNodeInfo awaitNode(Predicate<AccessibilityNodeInfo> match){AtomicReference<AccessibilityNodeInfo> found=new AtomicReference<>();await(()->{AccessibilityNodeInfo node=collect(instrumentation.getUiAutomation().getRootInActiveWindow(),match,new int[]{0});if(node==null)return false;found.set(node);return true;},"Native control appeared");return found.get();}
    private AccessibilityNodeInfo collect(AccessibilityNodeInfo node,Predicate<AccessibilityNodeInfo> match,int[] count){if(node==null||++count[0]>1200)return null;if(match.test(node))return node;for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo result=collect(node.getChild(i),match,count);if(result!=null)return result;}return null;}
    private void await(BooleanSupplier condition,String expected){long deadline=SystemClock.elapsedRealtime()+15000;do{if(condition.getAsBoolean())return;SystemClock.sleep(40);}while(SystemClock.elapsedRealtime()<deadline);throw new AssertionError(expected);}
}

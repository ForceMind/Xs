package dev.forcemind.xs.harness;

import android.app.Activity;
import android.app.Instrumentation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.ListView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import dev.forcemind.xs.android.XsArchiveDatabase;
import dev.forcemind.xs.android.XsCaptureBridge;
import dev.forcemind.xs.android.XsHistoryActivity;
import dev.forcemind.xs.android.XsLibrarySettings;
import dev.forcemind.xs.android.XsLibraryWrites;
import dev.forcemind.xs.android.XsLocalSpaces;
import dev.forcemind.xs.core.ArchiveSearch;
import dev.forcemind.xs.core.OwnActionSession;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Synthetic app UI only; no X login, network, or recipient-app claims. */
@RunWith(AndroidJUnit4.class)
public final class LibraryUiTest {
    private Instrumentation instrumentation;
    private Context app;
    private XsArchiveDatabase db;
    private Activity screen;
    private String library;

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        app = instrumentation.getTargetContext();
        AccessibilityServiceInfo accessibility = instrumentation.getUiAutomation().getServiceInfo();
        accessibility.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        instrumentation.getUiAutomation().setServiceInfo(accessibility);
        library = XsLocalSpaces.defaultLibrary(app);
        CountDownLatch cleared = new CountDownLatch(1);
        assertTrue(XsLibraryWrites.invalidateAndSubmit(library, cleared::countDown));
        assertTrue(cleared.await(5, TimeUnit.SECONDS));
        app.deleteDatabase("xs-local-history.db");
        app.getSharedPreferences("xs_library_settings", Context.MODE_PRIVATE).edit().clear().commit();
        db = new XsArchiveDatabase(app);
        db.ensureLibrary(library, "Xs");
    }

    @After public void tearDown() throws Exception {
        if (screen != null) {
            instrumentation.runOnMainSync(screen::finish);
            instrumentation.waitForIdleSync();
        }
        CountDownLatch drained = new CountDownLatch(1);
        assertTrue(XsLibraryWrites.invalidateAndSubmit(library, drained::countDown));
        assertTrue(drained.await(5, TimeUnit.SECONDS));
        XsCaptureBridge.unbindAccount();
        db.close();
        app.deleteDatabase("xs-local-history.db");
        app.getSharedPreferences("xs_library_settings", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void recordingPreferencesAreIndependentAndPersisted() {
        assertFalse(XsLibrarySettings.isOpenEnabled(app));
        assertFalse(XsLibrarySettings.isFeedEnabled(app));
        assertEquals(3000L, XsLibrarySettings.getDwellThresholdMs(app));
        XsLibrarySettings.setOpenEnabled(app, true);
        assertTrue(XsLibrarySettings.isOpenEnabled(app));
        assertFalse(XsLibrarySettings.isFeedEnabled(app));
        XsLibrarySettings.setFeedEnabled(app, true);
        XsLibrarySettings.setOpenEnabled(app, false);
        assertTrue(XsLibrarySettings.isFeedEnabled(app));
        assertFalse(XsLibrarySettings.isOpenEnabled(app));
        for (long threshold : new long[]{1200,3000,5000}) {
            XsLibrarySettings.setDwellThresholdMs(app, threshold);
            assertEquals(threshold, XsLibrarySettings.getDwellThresholdMs(app));
        }
        try { XsLibrarySettings.setDwellThresholdMs(app, 1000); fail("unsupported threshold"); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void nativeSearchFiltersMeasuredDwellAndNeverOpenedOffline() {
        seed();
        openScreen();
        click(XsHistoryActivity.ID_SEARCH);
        instrumentation.runOnMainSync(() -> ((EditText) screen.findViewById(XsHistoryActivity.ID_QUERY)).setText("dwellneedle"));
        awaitCount(3);
        click(XsHistoryActivity.ID_FILTERS);
        setAccessibleText(label("最低停留秒数", "Minimum dwell seconds"), "3");
        accessible(label("从未主动打开", "Never deliberately opened")).performAction(AccessibilityNodeInfo.ACTION_CLICK);
        clickDialogButton(android.R.id.button1, label("应用", "Apply"));
        awaitCount(2);
        assertTrue(rowsText().contains("3.5 s"));
        assertTrue(rowsText().contains("6.0 s"));
        assertTrue(rowsText().contains("feedonlymarker"));
        assertTrue(rowsText().contains("savedneedle"));
        assertFalse(rowsText().contains("openedonlymarker"));
        // Native X login identity changes cannot switch the app-owned library.
        XsCaptureBridge.bindAuthenticatedAccount(app, "different-x-account");
        assertEquals(library, XsLocalSpaces.defaultLibrary(app));
        click(XsHistoryActivity.ID_FAVORITES);
        awaitCount(1);
        assertTrue(rowsText().contains("savedneedle"));
    }

    @Test public void editExistingFavoriteTagsAndClearHistoryKeepLocalFavorite() {
        seed();
        openScreen();
        click(XsHistoryActivity.ID_FAVORITES);
        awaitCount(1);
        instrumentation.runOnMainSync(() -> {
            ListView list = screen.findViewById(XsHistoryActivity.ID_RESULTS);
            View row = list.getChildAt(0);
            assertNotNull(row);
            list.performItemClick(row, 0, list.getAdapter().getItemId(0));
        });
        clickDialogButton(android.R.id.button3, label("整理", "Organize"));
        clickAccessible(label("编辑收藏备注与标签", "Edit favorite note & tags"));
        assertEquals("existing private note", accessible(label("收藏备注", "Favorite note")).getText().toString());
        assertTrue(accessible(label("收藏标签", "Favorite tags")).getText().toString().contains("work"));
        setAccessibleText(label("收藏备注", "Favorite note"), "edited private note");
        setAccessibleText(label("收藏标签", "Favorite tags"), "work, reviewed");
        clickDialogButton(android.R.id.button1, label("保存", "Save"));
        awaitCondition(() -> {
            ArchiveSearch.Filter filter = new ArchiveSearch.Filter(); filter.tag = "reviewed";
            try (Cursor c = db.search(library, filter)) {
                return c.moveToFirst() && "edited private note".equals(c.getString(11));
            }
        });
        click(XsHistoryActivity.ID_CLEAR_HISTORY);
        clickDialogButton(android.R.id.button1, label("清历史", "Clear history"));
        awaitCondition(() -> {
            try (Cursor c = db.timeline(library, null, 20, "FEED_VISIBLE")) { return c.getCount() == 0; }
        });
        ArchiveSearch.Filter favorites = new ArchiveSearch.Filter(); favorites.favoriteOnly = true;
        try (Cursor c = db.search(library, favorites)) {
            assertEquals(1, c.getCount()); assertTrue(c.moveToFirst());
            assertEquals("edited private note", c.getString(11));
            assertTrue(c.getString(12).contains("reviewed"));
            assertEquals(0, c.getInt(7));
        }
    }

    @Test public void nativeConfirmedActivityAndReversalAreSearchableInActualIndexUi() throws Exception {
        OwnActionSession nativeOwner=OwnActionSession.forOfflineFixture(library,"ui-operation");
        OwnActionSession.Admission confirmed=nativeOwner.confirm(nativeOwner.begin(OwnActionSession.Type.REPOST,url("920"),"fixture:me"),
            "fixture:me",url("920"),OwnActionSession.Type.REPOST,true,null,System.currentTimeMillis());
        OwnActionSession.Admission reversed=nativeOwner.confirm(nativeOwner.beginReversal(confirmed),"fixture:me",url("920"),
            OwnActionSession.Type.REPOST,true,null,System.currentTimeMillis());
        CountDownLatch written=new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        assertTrue(XsLibraryWrites.submit(XsLibraryWrites.ticket(library), () -> {
            try {
                assertTrue(db.recordConfirmedOwnAction(confirmed,"other","ownneedle confirmed repost",null));
                assertFalse(db.recordConfirmedOwnAction(confirmed,"other","duplicate",null));
                assertTrue(db.recordConfirmedOwnAction(reversed,null,null,null));
            } catch(Throwable problem) { failure.set(problem); }
            finally { written.countDown(); }
        }));
        assertTrue(written.await(5,TimeUnit.SECONDS));
        if(failure.get() != null) throw new AssertionError(failure.get());
        openScreen(); click(XsHistoryActivity.ID_OWN_ACTIONS); awaitCount(1);
        assertTrue(rowsText().contains("ownneedle")); assertTrue(rowsText().contains("REVERSED"));
        assertTrue(rowsText().contains("NATIVE_FIXTURE"));
        instrumentation.runOnMainSync(() -> ((EditText)screen.findViewById(XsHistoryActivity.ID_QUERY)).setText("ownneedle"));
        awaitCount(1);
        instrumentation.runOnMainSync(() -> {
            ListView list=screen.findViewById(XsHistoryActivity.ID_RESULTS);
            list.performItemClick(list.getChildAt(0),0,list.getAdapter().getItemId(0));
        });
        clickDialogButton(android.R.id.button3,label("整理","Organize"));
        clickAccessible(label("本人行为轨迹","Own activity trail"));
        accessible(label("本人行为轨迹","Own activity trail"));
        assertTrue(instrumentation.getUiAutomation().getRootInActiveWindow().findAccessibilityNodeInfosByText("fixture:me").size() > 0);
        clickDialogButton(android.R.id.button1,label("关闭","Close"));
        try(Cursor views=db.timeline(library,null,20)) { assertEquals(0,views.getCount()); }
    }

    @Test public void existingLibraryIntentSelectsSeparateScopeWithoutChangingDefault() {
        String separate="local:restored-fixture";
        db.ensureLibrary(separate,"Restored fixture");
        assertTrue(db.favorite(separate,url("940"),"author","restored-scope-marker",null,System.currentTimeMillis()));
        screen=instrumentation.startActivitySync(new Intent(app,XsHistoryActivity.class)
            .putExtra(XsHistoryActivity.EXTRA_LIBRARY,separate).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync(); click(XsHistoryActivity.ID_FAVORITES);
        awaitCount(1); assertTrue(rowsText().contains("restored-scope-marker"));
        assertEquals(library,XsLocalSpaces.defaultLibrary(app));
        instrumentation.runOnMainSync(screen::finish); instrumentation.waitForIdleSync();
        screen=instrumentation.startActivitySync(new Intent(app,XsHistoryActivity.class)
            .putExtra(XsHistoryActivity.EXTRA_LIBRARY,"local:does-not-exist").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync(); click(XsHistoryActivity.ID_FAVORITES); awaitCount(0);
        try(Cursor scopes=db.libraries()) {
            while(scopes.moveToNext()) assertNotEquals("local:does-not-exist",scopes.getString(3));
        }
        assertEquals(library,XsLocalSpaces.defaultLibrary(app));
    }

    private void seed() {
        long now = System.currentTimeMillis();
        assertTrue(db.recordMeasuredFeed(library, url("901"), "home_following", "alice",
            "dwellneedle feedonlymarker", null, now, "exposure-901", 3500, 3000));
        assertTrue(db.recordMeasuredFeed(library, url("902"), "home_following", "bob",
            "dwellneedle openedonlymarker", null, now + 1, "exposure-902", 4000, 3000));
        assertTrue(db.recordObservedOpen(library, url("902"), "bob", "dwellneedle openedonlymarker",
            null, now + 2, "nav-902"));
        assertTrue(db.recordMeasuredFeed(library, url("903"), "home_following", "carol",
            "dwellneedle savedneedle", null, now + 3, "exposure-903", 6000, 3000));
        assertTrue(db.favorite(library, url("903"), "carol", "dwellneedle savedneedle", null, now + 4));
        db.putFavoriteDetails(library, "903", "existing private note", "work, reference");
        assertFalse(db.recordMeasuredFeed(library, url("904"), "home_following", "dave",
            "dwellneedle brief", null, now + 5, "exposure-904", 1000, 3000));
    }

    private void openScreen() {
        screen = instrumentation.startActivitySync(new Intent(app, XsHistoryActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync();
    }
    private void click(int id) {
        instrumentation.runOnMainSync(() -> assertTrue(screen.findViewById(id).performClick()));
        instrumentation.waitForIdleSync();
    }
    private void awaitCount(int count) {
        awaitCondition("native list count " + count, () -> {
            final int[] actual = {-1};
            instrumentation.runOnMainSync(() -> actual[0] = ((ListView) screen.findViewById(XsHistoryActivity.ID_RESULTS)).getAdapter().getCount());
            return actual[0] == count;
        });
    }
    private String rowsText() {
        StringBuilder result = new StringBuilder();
        instrumentation.runOnMainSync(() -> {
            ListView list = screen.findViewById(XsHistoryActivity.ID_RESULTS);
            for (int i = 0; i < list.getAdapter().getCount(); i++) result.append(list.getAdapter().getItem(i)).append('\n');
        });
        return result.toString();
    }
    private AccessibilityNodeInfo accessible(String text) {
        final AccessibilityNodeInfo[] found = {null};
        awaitCondition("accessible label " + text, () -> {
            AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            if (root == null) return false;
            List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByText(text);
            for (AccessibilityNodeInfo node : matches) {
                if (text.equals(node.getContentDescription() == null ? null : node.getContentDescription().toString()) ||
                    text.equals(node.getText() == null ? null : node.getText().toString())) {
                    found[0] = node; return true;
                }
            }
            return false;
        });
        return found[0];
    }
    private void clickAccessible(String text) {
        assertTrue(accessible(text).performAction(AccessibilityNodeInfo.ACTION_CLICK));
        instrumentation.waitForIdleSync();
    }
    /** Framework AlertDialog buttons apply theme-specific all-caps transformations. */
    private void clickDialogButton(int viewId, String expectedLabel) {
        String resourceName = app.getResources().getResourceName(viewId);
        final AccessibilityNodeInfo[] found = {null};
        awaitCondition("dialog button " + resourceName + " (" + expectedLabel + ")", () -> {
            AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            if (root == null) return false;
            for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByViewId(resourceName)) {
                if (node.isVisibleToUser() && node.isEnabled()) { found[0] = node; return true; }
            }
            return false;
        });
        assertNotNull("Expected dialog button label", found[0].getText());
        assertTrue("Expected " + expectedLabel + ", got " + found[0].getText(),
            expectedLabel.equalsIgnoreCase(found[0].getText().toString()));
        assertTrue(found[0].performAction(AccessibilityNodeInfo.ACTION_CLICK));
        instrumentation.waitForIdleSync();
    }
    private void setAccessibleText(String description, String value) {
        Bundle args = new Bundle(); args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        assertTrue(accessible(description).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args));
        instrumentation.waitForIdleSync();
    }
    private interface Check { boolean passed(); }
    private void awaitCondition(Check check) { awaitCondition("native library condition", check); }
    private void awaitCondition(String expected, Check check) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        do {
            if (check.passed()) return;
            SystemClock.sleep(30);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("Timed out waiting for " + expected);
    }
    private String label(String chinese, String english) {
        return app.getResources().getConfiguration().getLocales().get(0).getLanguage().equals("zh") ? chinese : english;
    }
    private static String url(String id) { return "https://x.com/i/web/status/" + id; }
}

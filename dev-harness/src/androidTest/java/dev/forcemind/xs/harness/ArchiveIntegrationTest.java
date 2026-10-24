package dev.forcemind.xs.harness;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import dev.forcemind.xs.android.XsArchiveDatabase;
import dev.forcemind.xs.android.XsCaptureBridge;
import dev.forcemind.xs.android.XsHistoryActivity;
import dev.forcemind.xs.core.ArchiveSchema;
import dev.forcemind.xs.core.ArchiveSearch;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import dev.forcemind.xs.android.XsArchiveImporter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ArchiveIntegrationTest {
    private Context app;
    private XsArchiveDatabase store;
    private static final String NAME = "xs-local-history.db";

    @Before public void open() {
        app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        XsCaptureBridge.unbindAccount();
        app.deleteDatabase(NAME);
        store = new XsArchiveDatabase(app);
    }

    @After public void close() {
        if (store != null) store.close();
        XsCaptureBridge.unbindAccount();
        app.deleteDatabase(NAME);
    }

    @Test public void historyFeedAndSearchAreAccountScoped() {
        String url = "https://x.com/example/status/2112345678901234567";
        assertTrue(store.recordFeedVisible("A", url, "home_following", "author",
            "人工智能 agent notes", 1000L, 2000L));
        assertTrue(store.recordOpened("A", url, "author", "人工智能 agent notes", 1000L, 2100L));
        assertTrue(store.recordOpened("A", url, "author", "人工智能 agent notes", 1000L, 2200L));
        assertTrue(store.recordOpened("B", url, "author", "unrelated", 1000L, 2300L));
        try (Cursor c = store.timeline("A", null, 20, "OPEN_POST")) {
            assertEquals(2, c.getCount());
        }
        try (Cursor c = store.timeline("A", null, 20, "FEED_VISIBLE")) {
            assertEquals(1, c.getCount());
            assertTrue(c.moveToFirst());
            assertEquals("home_following", c.getString(7));
        }
        try (Cursor c = store.timeline("B", null, 20, "FEED_VISIBLE")) {
            assertEquals(0, c.getCount());
        }
        ArchiveSearch.Filter filter = new ArchiveSearch.Filter();
        filter.keyword = "人工智能";
        try (Cursor c = store.search("A", filter)) {
            assertEquals(1, c.getCount());
            assertTrue(c.moveToFirst());
            assertEquals(2, c.getInt(7));
        }
        try (Cursor c = store.search("B", filter)) {
            assertEquals(0, c.getCount());
        }
        filter.keyword = "";
        filter.dateField = ArchiveSearch.DateField.LAST_OPENED;
        try (Cursor c = store.search("A", filter)) {
            assertTrue(c.moveToFirst());
            assertEquals(2200L, c.getLong(8));
        }
        filter.dateField = ArchiveSearch.DateField.FEED_SEEN;
        try (Cursor c = store.search("A", filter)) {
            assertTrue(c.moveToFirst());
            assertEquals(2000L, c.getLong(8));
        }
    }

    @Test public void foldersAndActionsDoNotLeakBetweenAccounts() {
        String url = "https://x.com/demo/status/75";
        assertTrue(store.recordOpened("A", url, "demo", "Agent notes", null, 111L));
        assertTrue(store.recordOpened("B", url, "demo", "other", null, 112L));
        store.putCategory("A", "work", "Old");
        assertTrue(store.assignCategory("A", "75", "work"));
        store.putCategory("A", "work", "Renamed");
        ArchiveSearch.Filter q = new ArchiveSearch.Filter();
        q.categoryId = "work";
        try (Cursor c = store.search("A", q)) { assertEquals(1, c.getCount()); }
        try (Cursor c = store.search("B", q)) { assertEquals(0, c.getCount()); }

        assertTrue(store.recordPersonalAction("A", url, "REPOST", "demo", "Agent notes",
            null, null, 200L));
        assertTrue(store.recordPersonalAction("A", url, "REPOST", "demo", "Agent notes",
            null, null, 250L));
        try (Cursor c = store.getReadableDatabase().rawQuery(
            "SELECT count(*), MAX(action_at_ms) FROM user_actions WHERE account_id='A'", null)) {
            assertTrue(c.moveToFirst());
            assertEquals(1, c.getInt(0));
            assertTrue(c.isNull(1));
        }
        store.clearAccount("A");
        try (Cursor c = store.timeline("A", null, 20)) { assertEquals(0, c.getCount()); }
        try (Cursor c = store.timeline("B", null, 20)) { assertEquals(1, c.getCount()); }
    }

    @Test public void databaseV1MigrationPreservesHistory() {
        store.close();
        store = null;
        app.deleteDatabase(NAME);
        SQLiteDatabase old = app.openOrCreateDatabase(NAME, Context.MODE_PRIVATE, null);
        for (String ddl : ArchiveSchema.CREATE) {
            if (ddl.contains("idx_posts_open_time") || ddl.contains("idx_posts_feed_time") ||
                ddl.contains("idx_actions_by_post") || ddl.contains("uniq_actions_")) continue;
            old.execSQL(ddl.replace("last_opened_at_ms INTEGER, ", "")
                           .replace("last_feed_seen_at_ms INTEGER, ", ""));
        }
        old.execSQL("INSERT INTO posts(account_id,post_id,body,first_seen_at_ms,last_seen_at_ms) " +
                    "VALUES ('A','75','old post',1,2)");
        old.execSQL("INSERT INTO history_events(account_id,post_id,event_type,event_at_ms,source) " +
                    "VALUES ('A','75','OPEN_POST',2,'post_detail')");
        old.setVersion(1);
        old.close();
        store = new XsArchiveDatabase(app);
        try (Cursor c = store.getReadableDatabase().rawQuery(
            "SELECT last_opened_at_ms, body FROM posts WHERE account_id='A'", null)) {
            assertTrue(c.moveToFirst());
            assertEquals(2, c.getLong(0));
            assertEquals("old post", c.getString(1));
        }
    }

    @Test public void largeHistoryCanPageWithoutLosingEarlierVisits() {
        for (int i = 0; i < 165; i++) {
            assertTrue(store.recordOpened("A", "https://x.com/demo/status/" + (10000 + i),
                "demo", "same content " + i, null, 100000L + i));
        }
        try (Cursor first = store.timeline("A", null, 80, "OPEN_POST", 0);
             Cursor middle = store.timeline("A", null, 80, "OPEN_POST", 80);
             Cursor last = store.timeline("A", null, 80, "OPEN_POST", 160)) {
            assertEquals(80, first.getCount());
            assertEquals(80, middle.getCount());
            assertEquals(5, last.getCount());
            assertTrue(first.moveToFirst());
            assertEquals("10164", first.getString(1));
            assertTrue(last.moveToFirst());
            assertEquals("10004", last.getString(1));
        }
        ArchiveSearch.Filter query = new ArchiveSearch.Filter();
        query.limit = 80;
        query.offset = 160;
        try (Cursor end = store.search("A", query)) {
            assertEquals(5, end.getCount());
        }
    }

    @Test public void officialPostsJsImportsOfflineWithoutFakeVisits() throws Exception {
        String json = "window.YTD.tweets.part0 = [" +
            "{\"tweet\":{\"id_str\":\"401\",\"full_text\":\"Standalone test original\"," +
            "\"created_at\":\"Sat Jan 02 03:04:05 +0000 2021\"}}," +
            "{\"tweet\":{\"id_str\":\"402\",\"full_text\":\"reply body\"," +
            "\"in_reply_to_status_id_str\":\"100\"," +
            "\"created_at\":\"Sat Jan 02 03:04:06 +0000 2021\"}}," +
            "{\"tweet\":{\"id_str\":\"403\",\"full_text\":\"RT @someone: repost\"," +
            "\"created_at\":\"Sat Jan 02 03:04:07 +0000 2021\"}}]";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        XsArchiveImporter.Result first = XsArchiveImporter.importJs(
            new ByteArrayInputStream(bytes), "A", store, 2000000000000L);
        assertEquals(3, first.parsed);
        assertEquals(3, first.inserted);
        XsArchiveImporter.Result again = XsArchiveImporter.importJs(
            new ByteArrayInputStream(bytes), "A", store, 2000000000001L);
        assertEquals(0, again.inserted);

        try (Cursor views = store.timeline("A", null, 50, "OPEN_POST")) {
            assertEquals(0, views.getCount());
        }
        ArchiveSearch.Filter q = new ArchiveSearch.Filter();
        q.actionType = "REPOST";
        q.dateField = ArchiveSearch.DateField.ACTION;
        try (Cursor posts = store.search("A", q)) {
            assertTrue(posts.moveToFirst());
            assertEquals("403", posts.getString(1));
            assertTrue(posts.isNull(8)); // unknown repost action time stays unknown
            assertEquals(0, posts.getInt(7));
        }
        q.actionType = "REPLY";
        try (Cursor posts = store.search("A", q)) {
            assertTrue(posts.moveToFirst());
            assertEquals("402", posts.getString(1));
            assertFalse(posts.isNull(8));
        }
    }

    @Test public void nativeArchiveActivityLoads() {
        XsCaptureBridge.bindAuthenticatedAccount(app, "dev-only");
        Intent intent = new Intent(app, XsHistoryActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity opened = InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
        assertNotNull(opened);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(opened::finish);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
}

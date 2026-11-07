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
import dev.forcemind.xs.android.XsLocalSpaces;
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
        for (String ddl : ArchiveSchema.CREATE_V2) {
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

    @Test public void partialMetadataDoesNotTruncateStoredTextOrSearchIndex() {
        String url = "https://x.com/demo/status/650";
        assertTrue(store.recordOpened("A", url, "demo", "Long retained searchable snapshot", null, 100L));
        assertTrue(store.recordFeedVisible("A", url, "home_following", "demo", "Long", null, 200L));
        assertTrue(store.recordOpened("B", url, "other", "Other scope", null, 300L));
        ArchiveSearch.Filter q = new ArchiveSearch.Filter();
        q.keyword = "searchable";
        try (Cursor c = store.search("A", q)) {
            assertEquals(1, c.getCount());
        }
        try (Cursor c = store.search("B", q)) { assertEquals(0, c.getCount()); }
        assertTrue(store.recordOpened("A", url, "demo", "Long retained searchable snapshot plus expansion", null, 400L));
        q.keyword = "expansion";
        try (Cursor c = store.search("A", q)) { assertEquals(1, c.getCount()); }
        try (Cursor c = store.timeline("A", null, 20)) { assertEquals(2, c.getCount()); }
    }

    @Test public void manualLocalSpaceSavesLinkWithoutFakeReadingHistory() {
        String scope = XsLocalSpaces.createOnExplicitUserAction(app);
        assertTrue(scope.startsWith("local:"));
        assertEquals(scope, XsLocalSpaces.current(app));
        assertEquals(scope, XsLocalSpaces.createOnExplicitUserAction(app));
        String link = "https://x.com/alice/status/99123";
        assertTrue(store.saveManualLink(scope, link, 5000L));
        assertFalse(store.saveManualLink(scope, link, 7000L));
        assertFalse(store.saveManualLink(scope, "https://evil.test/user/status/99123", 8000L));
        try (Cursor visits = store.timeline(scope, null, 20, "OPEN_POST")) {
            assertEquals(0, visits.getCount());
        }
        ArchiveSearch.Filter filter = new ArchiveSearch.Filter();
        filter.actionType = "LOCAL_SAVE";
        filter.dateField = ArchiveSearch.DateField.ACTION;
        try (Cursor records = store.search(scope, filter)) {
            assertEquals(1, records.getCount());
            assertTrue(records.moveToFirst());
            assertEquals("99123", records.getString(1));
            assertEquals(0, records.getInt(7));
            assertEquals(5000L, records.getLong(8));
        }
        try (Cursor other = store.search("different-scope", filter)) {
            assertEquals(0, other.getCount());
        }
        Intent nativeArchive = new Intent(app, XsHistoryActivity.class)
            .putExtra(XsHistoryActivity.EXTRA_LOCAL_SPACE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity screen = InstrumentationRegistry.getInstrumentation().startActivitySync(nativeArchive);
        assertNotNull(screen);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(screen::finish);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test public void nativeNavigationAndExposureIdentitiesArePersistentAndIndependent() {
        String library = XsLocalSpaces.defaultLibrary(app);
        String url = "https://x.com/alice/status/7001";
        assertTrue(store.recordObservedOpen(library,url,null,null,null,100,"epoch1:nav1"));
        for (int i=0; i<5; i++) {
            assertFalse(store.recordObservedOpen(library,url,"alice","Late searchable body",
                50L,100+i,"epoch1:nav1"));
        }
        assertFalse(store.recordObservedOpen(library,"https://x.com/a/status/9009",null,
            "collision",null,100,"epoch1:nav1"));
        try (Cursor visits = store.timeline(library,null,20)) { assertEquals(1,visits.getCount()); }
        assertTrue(store.enrichSnapshot(library,url,"alice","Late searchable body expanded",50L));
        try (Cursor rows = store.search(library,new ArchiveSearch.Filter())) {
            assertEquals(1,rows.getCount()); assertTrue(rows.moveToFirst());
            assertEquals(1,rows.getInt(7));
        }
        String feed = "https://x.com/bob/status/7002";
        assertFalse(store.recordMeasuredFeed(library,feed,"home_following","bob","Robot notes",null,
            1000,"epoch1:exposure1",2999,3000));
        assertTrue(store.recordMeasuredFeed(library,feed,"home_following","bob","Robot notes",null,
            1000,"epoch1:exposure1",3500,3000));
        assertFalse(store.recordMeasuredFeed(library,feed,"home_following","bob","Robot notes",null,
            1000,"epoch1:exposure1",4500,3000));
        assertFalse(store.recordMeasuredFeed(library,url,"home_following","alice","collision",null,
            1000,"epoch1:exposure1",5000,3000));
        assertTrue(store.recordMeasuredFeed(library,feed,"home_following","bob","Robot notes",null,
            10000,"epoch1:exposure2",3500,3000));
        ArchiveSearch.Filter filter = new ArchiveSearch.Filter();
        filter.keyword="robot"; filter.minDwellMs=3000L; filter.neverOpened=true;
        filter.sortByDwell=true; filter.author="@bob";
        try (Cursor rows = store.search(library,filter)) {
            assertEquals(1,rows.getCount()); assertTrue(rows.moveToFirst());
            assertEquals("7002",rows.getString(1)); assertEquals(0,rows.getInt(7));
            assertEquals(4500L,rows.getLong(9)); assertTrue(rows.isNull(10));
        }
        try (Cursor events = store.timeline(library,null,20,"FEED_VISIBLE")) {
            assertEquals(2,events.getCount());
        }
        assertFalse(store.recordMeasuredFeed(library,feed,"home_following","bob",null,null,
            1000,"epoch1:exposure1",600000,3000));
        try (Cursor duration = store.getReadableDatabase().rawQuery(
                "SELECT duration_ms,duration_quality FROM history_events WHERE event_key='epoch1:exposure1'",null)) {
            assertTrue(duration.moveToFirst()); assertEquals(600000L,duration.getLong(0));
            assertEquals("CAPPED_LOWER_BOUND",duration.getString(1));
        }
        store.close(); store=new XsArchiveDatabase(app);
        assertFalse(store.recordObservedOpen(library,url,null,null,null,200,"epoch1:nav1"));
        assertTrue(store.recordObservedOpen(library,feed,"bob",null,null,20000,"epoch1:nav2"));
        try (Cursor rows = store.search(library,filter)) { assertEquals(0,rows.getCount()); }
    }

    @Test public void favoritesNotesTagsAndHistoryErasureHaveSeparateScopes() {
        String library=XsLocalSpaces.defaultLibrary(app);
        String url="https://x.com/alice/status/8001";
        assertTrue(store.favorite(library,url,"alice","Local favorite snapshot",null,1000));
        assertFalse(store.favorite(library,url,"alice",null,null,9000));
        store.putFavoriteDetails(library,"8001","Personal annotation","AI, research, AI");
        store.putCategory(library,"work","Work");
        store.assignCategory(library,"8001","work");
        assertTrue(store.recordObservedOpen(library,url,null,null,null,2000,"fav:nav1"));
        store.clearHistory(library);
        ArchiveSearch.Filter q=new ArchiveSearch.Filter();
        q.favoriteOnly=true; q.tag="research"; q.categoryId="work";
        try (Cursor rows=store.search(library,q)) {
            assertTrue(rows.moveToFirst()); assertEquals(0,rows.getInt(7));
            assertEquals(1000,rows.getLong(10));
            assertEquals("Personal annotation",rows.getString(11));
        }
        assertEquals(library,XsLocalSpaces.defaultLibrary(app));
        assertTrue(store.removeFavorite(library,"8001"));
        try (Cursor rows=store.search(library,q)) { assertEquals(0,rows.getCount()); }
        q.favoriteOnly=false; q.tag=null;
        try (Cursor rows=store.search(library,q)) { assertEquals(1,rows.getCount()); }
        assertFalse(store.enrichSnapshot(library,"https://x.com/a/status/999",null,"stale",null));
    }

    @Test public void databaseV2UpgradeKeepsScopesMappingsAndUnknownLegacyDurations() {
        store.close(); store=null; app.deleteDatabase(NAME);
        SQLiteDatabase old=app.openOrCreateDatabase(NAME,Context.MODE_PRIVATE,null);
        for (String ddl:ArchiveSchema.CREATE_V2) old.execSQL(ddl);
        for (String scope:new String[]{"legacy-A","legacy-B"}) {
            old.execSQL("INSERT INTO posts(account_id,post_id,body,first_seen_at_ms,last_seen_at_ms) " +
                "VALUES (?,'88','preserved snapshot',1,2)",new Object[]{scope});
            old.execSQL("INSERT INTO categories VALUES (?,'work','Research')",new Object[]{scope});
            old.execSQL("INSERT INTO post_categories VALUES (?,'88','work')",new Object[]{scope});
            old.execSQL("INSERT INTO history_events(account_id,post_id,event_type,event_at_ms,source) " +
                "VALUES (?,'88','FEED_VISIBLE',2,'home_following')",new Object[]{scope});
        }
        old.setVersion(2); old.close(); store=new XsArchiveDatabase(app);
        try (Cursor mappings=store.getReadableDatabase().rawQuery("SELECT legacy_scope,library_id " +
                "FROM legacy_scope_mapping ORDER BY legacy_scope",null)) {
            assertEquals(2,mappings.getCount()); assertTrue(mappings.moveToFirst());
            assertEquals("legacy-A",mappings.getString(0)); assertEquals("legacy-A",mappings.getString(1));
        }
        ArchiveSearch.Filter q=new ArchiveSearch.Filter(); q.keyword="preserved"; q.categoryId="work";
        for (String scope:new String[]{"legacy-A","legacy-B"}) {
            try (Cursor rows=store.search(scope,q)) { assertEquals(1,rows.getCount()); }
            try (Cursor events=store.timeline(scope,null,20,"FEED_VISIBLE")) {
                assertTrue(events.moveToFirst()); assertTrue(events.isNull(8)); assertTrue(events.isNull(11));
            }
        }
        try (Cursor check=store.getReadableDatabase().rawQuery("PRAGMA foreign_key_check",null)) {
            assertEquals(0,check.getCount());
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

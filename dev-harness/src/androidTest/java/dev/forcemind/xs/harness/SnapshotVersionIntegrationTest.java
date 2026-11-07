package dev.forcemind.xs.harness;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.forcemind.xs.android.XsArchiveDatabase;
import dev.forcemind.xs.android.XsArchiveImporter;
import dev.forcemind.xs.android.XsCaptureBridge;
import dev.forcemind.xs.android.XsLibraryWrites;
import dev.forcemind.xs.core.OwnActionSession;
import dev.forcemind.xs.core.ArchiveSchema;
import dev.forcemind.xs.core.ArchiveSearch;
import dev.forcemind.xs.core.SnapshotPolicy;
import dev.forcemind.xs.core.SnapshotPolicy.Source;
import dev.forcemind.xs.core.SnapshotPolicy.Quality;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** Accepted native fixture snapshots, not live X edit detection. */
@RunWith(AndroidJUnit4.class)
public final class SnapshotVersionIntegrationTest {
    private Context app;private XsArchiveDatabase db;
    private static final String LIB="local:snapshots",URL="https://x.com/author/status/9901",NAME="xs-local-history.db";
    @Before public void open(){app=InstrumentationRegistry.getInstrumentation().getTargetContext();XsCaptureBridge.unbindAccount();app.deleteDatabase(NAME);db=new XsArchiveDatabase(app);}
    @After public void close(){if(db!=null)db.close();XsCaptureBridge.unbindAccount();app.deleteDatabase(NAME);}
    private void seed(){assertTrue(db.recordObservedOpen(LIB,URL,"author",null,50L,100,"navigation:snapshot"));}
    private boolean snapshot(String body,long time,Quality quality){return db.recordSnapshot(LIB,URL,"author",body,50L,time,Source.POST_DETAIL,quality);}
    private int versions(){try(Cursor c=db.listSnapshots(LIB,"9901")){return c.getCount();}}
    private String current(){ArchiveSearch.Filter f=new ArchiveSearch.Filter();try(Cursor c=db.search(LIB,f)){assertTrue(c.moveToFirst());return c.getString(3);}}

    @Test public void newerCompleteShortEditRetainsOldVersionAndBothSearchIndexes() {
        seed();assertTrue(snapshot("superseded uniquely searchable long complete body",110,Quality.COMPLETE));
        assertFalse(snapshot("superseded uniquely searchable long complete body",120,Quality.COMPLETE));assertEquals(1,versions());
        assertTrue(snapshot("edited",200,Quality.COMPLETE));assertEquals("edited",current());assertEquals(2,versions());
        ArchiveSearch.Filter q=new ArchiveSearch.Filter();q.keyword="superseded";
        try(Cursor c=db.search(LIB,q)){assertEquals(0,c.getCount());}
        q.includeSnapshotVersions=true;
        try(Cursor c=db.search(LIB,q)){assertTrue(c.moveToFirst());assertEquals("edited",c.getString(3));assertEquals(2,c.getInt(18));assertEquals(1,c.getInt(7));assertEquals("COMPLETE",c.getString(16));assertEquals(200,c.getLong(17));assertEquals(50,c.getLong(4));}
        try(Cursor old=db.searchSnapshots(LIB,"9901","superseded",10,0)) {
            assertTrue(old.moveToFirst());assertEquals(0,old.getInt(8));assertEquals(110,old.getLong(4));
            assertEquals(SnapshotPolicy.hash(old.getString(2)),old.getString(7));assertEquals("native_post_detail",old.getString(5));
            try(Cursor exact=db.getSnapshot(LIB,"9901",old.getString(0))){assertTrue(exact.moveToFirst());assertEquals(old.getString(2),exact.getString(2));}
        }
    }
    @Test public void partialLinkOnlyAndOutOfOrderBodiesDoNotReplaceCompleteCurrent() {
        seed();assertTrue(snapshot("complete body",200,Quality.COMPLETE));
        assertTrue(snapshot("much longer but truncated or partial visible body",300,Quality.PARTIAL));
        assertFalse(snapshot("much longer but truncated or partial visible body",400,Quality.PARTIAL));
        assertFalse(snapshot("",500,Quality.LINK_ONLY));assertEquals("complete body",current());assertEquals(2,versions());
        assertTrue(snapshot("stale full body",100,Quality.COMPLETE));assertEquals("complete body",current());
        assertFalse(snapshot("stale full body",150,Quality.COMPLETE));assertEquals(3,versions());
        assertTrue(snapshot("short edit",600,Quality.COMPLETE));assertEquals("short edit",current());assertEquals(4,versions());
    }
    @Test public void clearingVisitsKeepsVersionsButDeletingScopeCascadesOnlyThatScope() {
        seed();assertTrue(snapshot("saved history version",110,Quality.COMPLETE));
        assertTrue(db.favorite(LIB,URL,"author",null,50L,120));
        assertTrue(db.recordOpened("local:other",URL,"other","other scope body",50L,130));
        db.clearHistory(LIB);assertEquals(1,versions());assertEquals("saved history version",current());
        ArchiveSearch.Filter saved=new ArchiveSearch.Filter();saved.favoriteOnly=true;
        try(Cursor c=db.search(LIB,saved)){assertTrue(c.moveToFirst());assertEquals(0,c.getInt(7));}
        db.deleteLibraryScope(LIB);
        for(String table:new String[]{"posts","snapshot_versions","snapshot_versions_fts"})try(Cursor c=db.getReadableDatabase().rawQuery("SELECT count(*) FROM "+table+" WHERE account_id=?",new String[]{LIB})){assertTrue(c.moveToFirst());assertEquals(0,c.getInt(0));}
        try(Cursor c=db.listSnapshots("local:other","9901")){assertEquals(1,c.getCount());}
        assertFalse(snapshot("late callback must not resurrect",500,Quality.COMPLETE));
        try(Cursor c=db.libraries()){while(c.moveToNext())assertNotEquals(LIB,c.getString(3));}
    }
    @Test public void v4MigrationPreservesBodyFtsRelationsAndUnknownCaptureTime() {
        db.close();db=null;app.deleteDatabase(NAME);SQLiteDatabase old=app.openOrCreateDatabase(NAME,Context.MODE_PRIVATE,null);
        for(String sql:ArchiveSchema.CREATE_V4)old.execSQL(sql);
        old.execSQL("INSERT INTO libraries(library_id,storage_scope,label,origin) VALUES ('legacy','legacy','Legacy','LEGACY')");
        old.execSQL("INSERT INTO posts(account_id,post_id,author_handle,body,published_at_ms,first_seen_at_ms,last_seen_at_ms,snapshot_state) VALUES ('legacy','42','author','retained migration body',50,100,900,'VISIBLE_TEXT')");
        old.execSQL("INSERT INTO favorites(account_id,post_id,favorited_at_ms,note) VALUES ('legacy','42',200,'retained note')");
        old.execSQL("INSERT INTO categories VALUES ('legacy','folder','Retained folder')");old.execSQL("INSERT INTO post_categories VALUES ('legacy','42','folder')");
        old.setVersion(4);old.close();db=new XsArchiveDatabase(app);
        try(Cursor c=db.listSnapshots("legacy","42")){assertTrue(c.moveToFirst());assertEquals("migration:v4",c.getString(0));assertEquals("retained migration body",c.getString(2));assertTrue(c.isNull(4));assertEquals("legacy_migration",c.getString(5));assertEquals("LEGACY_UNKNOWN",c.getString(6));assertTrue(c.isNull(7));assertEquals(1,c.getInt(8));}
        ArchiveSearch.Filter q=new ArchiveSearch.Filter();q.keyword="migration";q.favoriteOnly=true;q.categoryId="folder";
        try(Cursor c=db.search("legacy",q)){assertTrue(c.moveToFirst());assertEquals("retained note",c.getString(11));assertEquals(100,c.getLong(5));assertEquals(900,c.getLong(6));}
        try(Cursor c=db.searchSnapshots("legacy","42","migration",10,0)){assertEquals(1,c.getCount());}
        try(Cursor c=db.getReadableDatabase().rawQuery("PRAGMA foreign_key_check",null)){assertEquals(0,c.getCount());}
    }
    @Test public void archiveSnapshotHasImportProvenanceWithoutInventingWebsiteEditOrVisit() throws Exception {
        seed();assertTrue(snapshot("newer native complete body to retain",110,Quality.COMPLETE));
        String json="[{\"tweet\":{\"id_str\":\"9901\",\"full_text\":\"older exported text\",\"created_at\":\"Sat Jan 02 03:04:05 +0000 2021\"}}]";
        XsArchiveImporter.importJs(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),LIB,db,2000000000000L);
        assertEquals("newer native complete body to retain",current());assertEquals(2,versions());
        try(Cursor c=db.searchSnapshots(LIB,"9901","exported",10,0)){assertTrue(c.moveToFirst());assertEquals("local_x_archive",c.getString(5));assertEquals("COMPLETE",c.getString(6));assertEquals(2000000000000L,c.getLong(4));assertEquals(0,c.getInt(8));}
        try(Cursor c=db.timeline(LIB,null,20,"OPEN_POST")){assertEquals(1,c.getCount());}
        try(Cursor c=db.timeline(LIB,null,20,"FEED_VISIBLE")){assertEquals(0,c.getCount());}
    }
    @Test public void deletedScopeRejectsDirectNativeWritesEvenAfterWaitingForDatabaseLock() throws Exception {
        String scope="local:deleted-snapshot-"+java.util.UUID.randomUUID();
        assertTrue(db.recordOpened(scope,URL,"author","before deletion",50L,100));
        OwnActionSession nativeOwner=OwnActionSession.forOfflineFixture(scope,"native-before-delete");
        OwnActionSession.Admission admitted=nativeOwner.confirm(nativeOwner.begin(OwnActionSession.Type.REPOST,URL,"fixture:me"),"fixture:me",URL,OwnActionSession.Type.REPOST,true,null,150);
        java.util.concurrent.CountDownLatch started=new java.util.concurrent.CountDownLatch(1),done=new java.util.concurrent.CountDownLatch(1),purged=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean wrote=new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<Throwable> failed=new java.util.concurrent.atomic.AtomicReference<>();
        SQLiteDatabase lock=db.getWritableDatabase();lock.beginTransaction();
        Thread waiting=new Thread(()->{started.countDown();try{wrote.set(db.recordOpened(scope,URL,"author","must not return",50L,200));}catch(Throwable failure){failed.set(failure);}finally{done.countDown();}},"xs-test-waiting-db-writer");
        try {
            waiting.start();assertTrue(started.await(3,java.util.concurrent.TimeUnit.SECONDS));
            long deadline=android.os.SystemClock.elapsedRealtime()+3000;
            while(waiting.getState()!=Thread.State.WAITING&&waiting.getState()!=Thread.State.TIMED_WAITING&&android.os.SystemClock.elapsedRealtime()<deadline)Thread.yield();
            assertTrue("writer must wait for the actual SQLite transaction lock",done.getCount()==1&&(waiting.getState()==Thread.State.WAITING||waiting.getState()==Thread.State.TIMED_WAITING));
            assertTrue(XsLibraryWrites.deleteAndSubmit(scope,()->{try{db.deleteLibraryScope(scope);}finally{purged.countDown();}}));
        } finally {lock.endTransaction();}
        assertTrue(done.await(5,java.util.concurrent.TimeUnit.SECONDS));assertTrue(purged.await(5,java.util.concurrent.TimeUnit.SECONDS));
        if(failed.get()!=null)throw new AssertionError(failed.get());assertFalse(wrote.get());
        assertFalse(db.favorite(scope,URL,"author","late favorite",50L,300));
        assertFalse(db.recordObservedOpen(scope,URL,"author","late open",50L,300,"late-nav"));
        assertFalse(db.recordMeasuredFeed(scope,URL,"home_following","author","late feed",50L,300,"late-feed",3500,3000));
        assertFalse(db.recordSnapshot(scope,URL,"author","late complete",50L,300,Source.POST_DETAIL,Quality.COMPLETE));
        assertFalse(db.recordConfirmedOwnAction(admitted,"author","late own action",50L));
        assertFalse(db.saveManualLink(scope,URL,300));
        String json="[{\"tweet\":{\"id_str\":\"9901\",\"full_text\":\"late archive\"}}]";
        assertEquals(0,XsArchiveImporter.importJs(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),scope,db,300).inserted);
        try{db.ensureLibrary(scope,"must not return");fail("deleted library must remain unavailable");}catch(IllegalStateException expected){}
        try(Cursor c=db.getReadableDatabase().rawQuery("SELECT count(*) FROM posts WHERE account_id=?",new String[]{scope})){assertTrue(c.moveToFirst());assertEquals(0,c.getInt(0));}
    }

    @Test public void lateAuthorAndPublicationEnrichSameTextWithoutInventingNewCaptureOrVersion() {
        assertTrue(db.recordObservedOpen(LIB,URL,null,null,null,100,"metadata-nav"));
        assertTrue(db.recordSnapshot(LIB,URL,null,"stable text",null,110,Source.POST_DETAIL,Quality.COMPLETE));
        assertTrue(db.recordSnapshot(LIB,URL,"known_author","stable text",50L,120,Source.POST_DETAIL,Quality.COMPLETE));
        assertEquals(1,versions());
        try(Cursor current=db.listSnapshots(LIB,"9901")){assertTrue(current.moveToFirst());assertEquals("known_author",current.getString(1));assertEquals(50,current.getLong(3));assertEquals(110,current.getLong(4));}
        ArchiveSearch.Filter filter=new ArchiveSearch.Filter();filter.author="known_author";
        try(Cursor current=db.search(LIB,filter)){assertTrue(current.moveToFirst());assertEquals(50,current.getLong(4));}
        assertFalse(db.recordSnapshot(LIB,URL,"known_author","stable text",50L,130,Source.POST_DETAIL,Quality.COMPLETE));
    }

}

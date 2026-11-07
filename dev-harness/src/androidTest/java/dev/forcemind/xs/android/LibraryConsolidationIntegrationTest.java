package dev.forcemind.xs.android;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.forcemind.xs.core.ArchiveSearch;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Native multi-source preview/commit over actual SQLite, not a silent default-library migration. */
@RunWith(AndroidJUnit4.class)
public final class LibraryConsolidationIntegrationTest {
    private Context app;private XsArchiveDatabase db;private String first,second,defaultLibrary;
    @Before public void setUp()throws Exception {
        app=InstrumentationRegistry.getInstrumentation().getTargetContext();XsCaptureBridge.unbindAccount();
        app.deleteDatabase("xs-local-history.db");db=new XsArchiveDatabase(app);
        first="legacy-first:"+UUID.randomUUID();second="legacy-second:"+UUID.randomUUID();defaultLibrary=XsLocalSpaces.defaultLibrary(app);
        seed(first,"第一来源",2000L,"short","来源一备注 🚀","共同,研发");
        seed(second,"第二来源",3000L,"longer Chinese 中文 body","来源二备注 🧪","共同,收藏");
    }
    @After public void tearDown()throws Exception {
        drain(first);drain(second);db.close();app.deleteDatabase("xs-local-history.db");
    }
    @Test public void explicitMergeKeepsEverySourceNoteFolderAndVisitInAnotherLibrary()throws Exception {
        int libraries=libraryCount();XsLibraryConsolidation.Prepared preview=XsLibraryConsolidation.prepare(app,Arrays.asList(first,second),"合并资料 📚");
        assertEquals(Integer.valueOf(1),preview.counts().get("posts"));assertEquals(Integer.valueOf(2),preview.counts().get("history_events"));
        assertEquals(Integer.valueOf(2),preview.counts().get("categories"));assertEquals(Integer.valueOf(2),preview.counts().get("post_categories"));
        try{preview.counts().put("posts",42);fail("mutable preview");}catch(UnsupportedOperationException expected){}
        String merged=XsLibraryConsolidation.commit(app,preview);
        assertTrue(merged.startsWith("local:"));assertNotEquals(first,merged);assertNotEquals(second,merged);
        assertEquals(defaultLibrary,XsLocalSpaces.defaultLibrary(app));assertEquals(libraries+1,libraryCount());
        assertEquals(1,count("posts",first));assertEquals(1,count("posts",second));assertEquals(1,count("posts",merged));
        assertEquals(1,count("history_events",first));assertEquals(1,count("history_events",second));assertEquals(2,count("history_events",merged));
        assertEquals(2,count("post_categories",merged));assertEquals(3,count("post_tags",merged));
        ArchiveSearch.Filter q=new ArchiveSearch.Filter();q.keyword="中文";q.favoriteOnly=true;
        try(Cursor c=db.search(merged,q)) {
            assertTrue(c.moveToFirst());assertEquals(1,c.getCount());assertEquals("longer Chinese 中文 body",c.getString(3));assertEquals(2,c.getInt(7));
            String note=c.getString(11);assertTrue(note.contains("第一来源"));assertTrue(note.contains("第二来源"));assertTrue(note.contains("来源一备注 🚀"));assertTrue(note.contains("来源二备注 🧪"));
        }
        try(Cursor c=db.getReadableDatabase().rawQuery("SELECT COUNT(DISTINCT category_id) FROM post_categories WHERE account_id=?",new String[]{merged})){assertTrue(c.moveToFirst());assertEquals(2,c.getInt(0));}
        try(Cursor c=db.getReadableDatabase().rawQuery("SELECT COUNT(DISTINCT event_key),MIN(event_at_ms),MAX(event_at_ms) FROM history_events WHERE account_id=?",new String[]{merged})){assertTrue(c.moveToFirst());assertEquals(2,c.getInt(0));assertEquals(2000L,c.getLong(1));assertEquals(3000L,c.getLong(2));}
        try(Cursor c=db.getReadableDatabase().rawQuery("SELECT note FROM favorites WHERE account_id=?",new String[]{first})){assertTrue(c.moveToFirst());assertEquals("来源一备注 🚀",c.getString(0));}
        try(Cursor c=db.getReadableDatabase().rawQuery("SELECT label,origin FROM libraries WHERE storage_scope=?",new String[]{merged})){assertTrue(c.moveToFirst());assertEquals("合并资料 📚",c.getString(0));assertEquals("RESTORED",c.getString(1));}
        try{XsLibraryConsolidation.commit(app,preview);fail("preview reused");}catch(IllegalStateException expected){}
        assertEquals(libraries+1,libraryCount());
    }
    @Test public void erasingEitherPreparedSourceExpiresPreviewWithoutCloningDeletedData()throws Exception {
        XsLibraryConsolidation.Prepared preview=XsLibraryConsolidation.prepare(app,Arrays.asList(first,second),"should not appear");int libraries=libraryCount();
        CountDownLatch erased=new CountDownLatch(1);assertTrue(XsLibraryWrites.invalidateAndSubmit(second,()->{try{db.clearAccount(second);}finally{erased.countDown();}}));
        assertTrue(erased.await(10,TimeUnit.SECONDS));
        try{XsLibraryConsolidation.commit(app,preview);fail("erased snapshot committed");}catch(IllegalStateException expected){}
        assertEquals(libraries,libraryCount());assertEquals(1,count("posts",first));assertEquals(0,count("posts",second));assertEquals(defaultLibrary,XsLocalSpaces.defaultLibrary(app));
    }
    @Test public void conflictingLegacyActionSourcesFailBeforePreviewAndKeepOriginals()throws Exception {
        legacyAction(first,"local_x_archive");legacyAction(second,"unverified_legacy");int libraries=libraryCount();
        try{XsLibraryConsolidation.prepare(app,Arrays.asList(first,second),"conflict");fail("conflicting legacy sources collapsed");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("sources conflict"));}
        assertEquals(libraries,libraryCount());assertEquals(1,count("user_actions",first));assertEquals(1,count("user_actions",second));
        assertEquals(1,count("posts",first));assertEquals(1,count("posts",second));assertEquals(defaultLibrary,XsLocalSpaces.defaultLibrary(app));
    }
    @Test public void sourceMustBeExplicitDistinctAndRegistered()throws Exception {
        int libraries=libraryCount();
        try{XsLibraryConsolidation.prepare(app,Arrays.asList(first,first),"duplicate");fail();}catch(IllegalArgumentException expected){}
        try{XsLibraryConsolidation.prepare(app,Arrays.asList(first,"missing:"+UUID.randomUUID()),"missing");fail();}catch(IllegalArgumentException expected){}
        assertEquals(libraries,libraryCount());assertEquals(1,count("posts",first));assertEquals(1,count("posts",second));
    }
    @Test public void undoRemovesOnlyItsUnchangedNewResultAndCannotBeReused()throws Exception {
        int libraries=libraryCount();XsLibraryConsolidation.Prepared preview=XsLibraryConsolidation.prepare(app,Arrays.asList(first,second),"可撤销结果");
        try{XsLibraryConsolidation.rollback(app,preview);fail("uncommitted preview had a result");}catch(IllegalStateException expected){}
        String merged=XsLibraryConsolidation.commit(app,preview);assertEquals(libraries+1,libraryCount());
        XsLibraryConsolidation.rollback(app,preview);
        assertEquals(libraries,libraryCount());assertEquals(1,count("posts",first));assertEquals(1,count("posts",second));assertEquals(defaultLibrary,XsLocalSpaces.defaultLibrary(app));
        for(String table:XsLibraryBackup.allowedColumns().keySet())assertEquals(table,0,count(table,merged));
        try(Cursor c=db.search(merged,new ArchiveSearch.Filter())){assertEquals(0,c.getCount());}
        try{XsLibraryConsolidation.rollback(app,preview);fail("result undone twice");}catch(IllegalStateException expected){}
        assertEquals(libraries,libraryCount());
    }
    @Test public void undoRefusesResultWithLaterMetadataEditsAndKeepsSources()throws Exception {
        XsLibraryConsolidation.Prepared preview=XsLibraryConsolidation.prepare(app,Arrays.asList(first,second),"edited result");
        String merged=XsLibraryConsolidation.commit(app,preview);int libraries=libraryCount();
        queued(merged,()->db.putFavoriteDetails(merged,"8801","后续新增备注，不可删除 🧪","new-tag"));
        XsLibraryWrites.Ticket current=XsLibraryWrites.ticket(merged);
        try{XsLibraryConsolidation.rollback(app,preview);fail("edited result was deleted");}catch(IllegalStateException expected){}
        assertTrue(XsLibraryWrites.isCurrent(current));
        assertEquals(libraries,libraryCount());assertEquals(1,count("posts",merged));assertEquals(2,count("history_events",merged));
        try(Cursor c=db.getReadableDatabase().rawQuery("SELECT note FROM favorites WHERE account_id=?",new String[]{merged})){assertTrue(c.moveToFirst());assertEquals("后续新增备注，不可删除 🧪",c.getString(0));}
        assertEquals(1,count("posts",first));assertEquals(1,count("posts",second));assertEquals(defaultLibrary,XsLocalSpaces.defaultLibrary(app));
    }
    @Test public void undoRefusesAnyMediaTaskWithoutDeletingItsMetadataOrSources()throws Exception {
        XsLibraryConsolidation.Prepared preview=XsLibraryConsolidation.prepare(app,Arrays.asList(first,second),"media result");
        String merged=XsLibraryConsolidation.commit(app,preview);int libraries=libraryCount();
        // Synthetic in-flight task metadata. This does not claim that a real transfer ran.
        XsMediaRepository media=XsMediaRepository.get(app);String asset="undo-fixture:"+UUID.randomUUID();
        try {
            queued(merged,()->{ContentValues row=new ContentValues();row.put("id",asset);row.put("library_id",merged);row.put("post_url","https://x.com/demo/status/8801");row.put("state","DOWNLOADING");row.put("created_ms",100L);media.getWritableDatabase().insertOrThrow("assets",null,row);});
            XsLibraryWrites.Ticket transfer=XsLibraryWrites.ticket(merged);
            try{XsLibraryConsolidation.rollback(app,preview);fail("result containing media metadata was deleted");}catch(IllegalStateException expected){}
            assertTrue(XsLibraryWrites.isCurrent(transfer));assertEquals("DOWNLOADING",media.find(merged,asset).state);
            assertEquals(libraries,libraryCount());assertEquals(1,count("posts",merged));
            assertEquals(1,count("posts",first));assertEquals(1,count("posts",second));assertEquals(defaultLibrary,XsLocalSpaces.defaultLibrary(app));
        }finally{media.getWritableDatabase().delete("assets","id=? AND library_id=?",new String[]{asset,merged});}
    }
    @Test public void undoProtectsDefaultEvenIfPreferenceWasChangedAfterCreation()throws Exception {
        XsLibraryConsolidation.Prepared preview=XsLibraryConsolidation.prepare(app,Arrays.asList(first,second),"default guard");
        String merged=XsLibraryConsolidation.commit(app,preview);int libraries=libraryCount();
        // Only the fixture changes this preference; consolidation itself never selects a default.
        assertTrue(app.getSharedPreferences("xs_manual_archive",Context.MODE_PRIVATE).edit().putString("local_space",merged).commit());
        try {
            try{XsLibraryConsolidation.rollback(app,preview);fail("default was deleted");}catch(IllegalStateException expected){}
            assertEquals(merged,XsLocalSpaces.defaultLibrary(app));assertEquals(libraries,libraryCount());assertEquals(1,count("posts",merged));
            assertEquals(1,count("posts",first));assertEquals(1,count("posts",second));
        }finally{assertTrue(app.getSharedPreferences("xs_manual_archive",Context.MODE_PRIVATE).edit().putString("local_space",defaultLibrary).commit());}
    }
    private void seed(String scope,String label,long at,String body,String note,String tags)throws Exception {
        queued(scope,()->{db.ensureLibrary(scope,label);assertTrue(db.recordObservedOpen(scope,"https://x.com/demo/status/8801","demo",body,null,at,"same-navigation"));
            assertTrue(db.favorite(scope,"https://x.com/demo/status/8801","demo",body,null,at+100));db.putFavoriteDetails(scope,"8801",note,tags);
            db.putCategory(scope,"same-folder",label+"分类");assertTrue(db.assignCategory(scope,"8801","same-folder"));});
    }
    private void legacyAction(String scope,String source)throws Exception {
        queued(scope,()->{ContentValues row=new ContentValues();row.put("account_id",scope);row.put("post_id","8801");row.put("action_type","REPOST");row.put("action_at_ms",1000L);row.put("source",source);db.getWritableDatabase().insertOrThrow("user_actions",null,row);});
    }
    private void queued(String scope,Runnable work)throws Exception {
        CountDownLatch complete=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();
        assertTrue(XsLibraryWrites.submit(scope,()->{try{work.run();}catch(Throwable e){failure.set(e);}finally{complete.countDown();}}));
        assertTrue(complete.await(10,TimeUnit.SECONDS));if(failure.get()!=null)throw new AssertionError(failure.get());
    }
    private void drain(String scope)throws Exception{CountDownLatch drained=new CountDownLatch(1);assertTrue(XsLibraryWrites.invalidateAndSubmit(scope,drained::countDown));assertTrue(drained.await(10,TimeUnit.SECONDS));}
    private int count(String table,String scope){try(Cursor c=db.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM "+table+" WHERE account_id=?",new String[]{scope})){assertTrue(c.moveToFirst());return c.getInt(0);}}
    private int libraryCount(){try(Cursor c=db.libraries()){return c.getCount();}}
}

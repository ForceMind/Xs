package dev.forcemind.xs.android;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.forcemind.xs.core.ArchiveSearch;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Synthetic local evidence: real SQLite pruning, durable policy, live write tickets and saved bytes. */
@RunWith(AndroidJUnit4.class)
public final class RetentionIntegrationTest {
    private static final long DAY=86_400_000L;
    private Context app;private XsArchiveDatabase db;private XsMediaRepository media;
    private String library,other;private long now;private File staged;
    @Before public void setUp()throws Exception {
        app=InstrumentationRegistry.getInstrumentation().getTargetContext();db=new XsArchiveDatabase(app);media=XsMediaRepository.get(app);
        library="local:"+UUID.randomUUID();other="local:"+UUID.randomUUID();now=System.currentTimeMillis();
        write(library,()->db.ensureLibrary(library,"retention fixture"));write(other,()->db.ensureLibrary(other,"outside retention"));
    }
    @After public void tearDown()throws Exception {
        for(String scope:new String[]{library,other})write(scope,()->{media.eraseLibrary(scope);db.clearAccount(scope);db.getWritableDatabase().delete("libraries","storage_scope=?",new String[]{scope});XsRetentionSettings.commit(db,scope,0);});
        db.close();if(staged!=null)staged.delete();
    }
    @Test public void foreverIsDefaultAndOnlyExplicitSupportedDurationsAreAccepted()throws Exception {
        assertEquals(0,XsRetentionSettings.getDays(db,library));assertEquals(0,db.historyStats(library).postCount);
        write(library,()->assertTrue(db.recordObservedOpen(library,url("7101"),null,null,null,now-800*DAY,"forever:old")));
        assertEquals(0,db.retentionPreview(library,0,now));assertEquals(0,db.pruneHistory(library,0,now));assertEquals(1,db.historyStats(library).openEventCount);
        for(int days:new int[]{0,7,30,90,365})assertTrue(XsRetentionSettings.valid(days));
        for(int days:new int[]{-1,1,8,366,Integer.MAX_VALUE}){
            try{db.retentionPreview(library,days,now);fail("Unsupported policy accepted");}catch(IllegalArgumentException expected){}
        }
        XsLibraryWrites.Ticket before=XsLibraryWrites.ticket(library);assertEquals(0,clean());assertTrue("Empty forever maintenance must not invalidate unrelated work",XsLibraryWrites.isCurrent(before));
    }
    @Test public void strictCutoffPreservesOtherLibrariesAndLifetimeNeverOpenedSemantics()throws Exception {
        final long cutoff=now-7*DAY;
        write(library,()->{
            assertTrue(db.recordObservedOpen(library,url("7201"),"alice","retained searchable snapshot",1L,cutoff-1,"old:open"));
            assertTrue(db.recordObservedOpen(library,url("7202"),null,null,null,cutoff,"at:cutoff"));
            assertTrue(db.recordMeasuredFeed(library,url("7201"),"home_for_you","alice",null,null,cutoff-1,"old:feed",5000,3000));
            assertTrue(db.recordMeasuredFeed(library,url("7203"),"home_following","bob","never opened candidate",null,now,"new:feed",3500,3000));
        });
        write(other,()->assertTrue(db.recordObservedOpen(other,url("7201"),null,null,null,cutoff-1,"outside:old")));
        assertEquals(2,db.retentionPreview(library,7,now));
        write(library,()->assertEquals(2,db.pruneHistory(library,7,now)));
        XsArchiveDatabase.HistoryStats stats=db.historyStats(library);
        assertEquals(1,stats.openEventCount);assertEquals(1,stats.feedEventCount);assertEquals(2,stats.lifetimeOpenedCount);assertEquals(2,stats.everOpenedPostCount);
        assertEquals(1,db.historyStats(other).openEventCount);
        ArchiveSearch.Filter f=new ArchiveSearch.Filter();f.neverOpened=true;
        try(Cursor rows=db.search(library,f)){assertEquals(1,rows.getCount());assertTrue(rows.moveToFirst());assertEquals("7203",rows.getString(1));}
        try(Cursor row=db.getReadableDatabase().rawQuery("SELECT opened_count,last_opened_at_ms,last_feed_seen_at_ms FROM posts WHERE account_id=? AND post_id='7201'",new String[]{library})){
            assertTrue(row.moveToFirst());assertEquals(1,row.getLong(0));assertEquals(cutoff-1,row.getLong(1));assertEquals(cutoff-1,row.getLong(2));
        }
    }
    @Test public void legacyEventsWithoutCountersKeepTheirOpenedFactAfterPruning()throws Exception {
        write(library,()->{
            assertTrue(db.recordObservedOpen(library,url("7301"),null,null,null,now-9*DAY,"legacy:open"));
            db.getWritableDatabase().execSQL("UPDATE posts SET opened_count=0,last_opened_at_ms=NULL WHERE account_id=?",new Object[]{library});
            assertEquals(1,db.pruneHistory(library,7,now));
        });
        XsArchiveDatabase.HistoryStats stats=db.historyStats(library);assertEquals(0,stats.openEventCount);assertEquals(1,stats.lifetimeOpenedCount);assertEquals(1,stats.everOpenedPostCount);assertEquals(0,stats.neverOpenedPostCount);
        ArchiveSearch.Filter f=new ArchiveSearch.Filter();f.neverOpened=true;try(Cursor c=db.search(library,f)){assertEquals(0,c.getCount());}
        write(library,()->db.clearHistory(library));assertEquals(1,db.historyStats(library).neverOpenedPostCount);
    }
    @Test public void metadataVersionsFavoritesOwnActionsAndActualSavedFileSurvive()throws Exception {
        write(library,()->{
            assertTrue(db.recordObservedOpen(library,url("7401"),"alice","archived body",null,now-9*DAY,"protected:open"));
            assertTrue(db.favorite(library,url("7401"),null,null,null,now));db.putFavoriteDetails(library,"7401","keep note","keep-tag");
            db.putCategory(library,"keep-folder","keep folder");assertTrue(db.assignCategory(library,"7401","keep-folder"));
            db.getWritableDatabase().execSQL("INSERT INTO own_actions(account_id,action_key,post_id,action_type,actor_provenance,source,state) VALUES (?,'fixture:own','7401','ORIGINAL','NATIVE_FIXTURE','native_fixture','ACTIVE')",new Object[]{library});
        });
        staged=File.createTempFile("retention-saved-",".png",app.getCacheDir());Bitmap image=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888);
        try(FileOutputStream out=new FileOutputStream(staged)){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}finally{image.recycle();}
        byte[] bytes=Files.readAllBytes(staged.toPath());StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        AtomicReference<String> assetId=new AtomicReference<>();write(library,()->assetId.set(media.restoreCompleted(library,Collections.singletonList(new XsMediaRepository.RestoredAsset(url("7401"),"IMAGE","image/png",staged.length(),hash.toString(),"keep",now,0,2,2,0,0,0,staged))).get(0)));
        XsArchiveDatabase.HistoryStats before=db.historyStats(library);write(library,()->assertEquals(1,db.pruneHistory(library,7,now)));
        assertEquals(before.postCount,db.historyStats(library).postCount);assertEquals(before.snapshotCount,db.historyStats(library).snapshotCount);assertEquals(1,db.historyStats(library).favoriteCount);
        for(String table:new String[]{"own_actions","categories","post_categories","post_tags"})try(Cursor c=db.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM "+table+" WHERE account_id=?",new String[]{library})){assertTrue(c.moveToFirst());assertEquals(table,1,c.getLong(0));}
        assertArrayEquals(bytes,Files.readAllBytes(media.completedFile(library,assetId.get()).toPath()));
        try(Cursor c=db.getReadableDatabase().rawQuery("SELECT note FROM favorites WHERE account_id=?",new String[]{library})){assertTrue(c.moveToFirst());assertEquals("keep note",c.getString(0));}
    }
    @Test public void retainedCountsAndLowerBoundAggregatesDoNotMixLifetimeSummaries()throws Exception {
        write(library,()->{
            assertTrue(db.recordObservedOpen(library,url("7501"),null,null,null,now-9*DAY,"stats:old"));
            assertTrue(db.recordMeasuredFeed(library,url("7501"),"home_for_you",null,null,null,now,"stats:one",3500,3000));
            assertFalse(db.recordMeasuredFeed(library,url("7501"),"home_for_you",null,null,null,now,"stats:one",4500,3000));
            assertTrue(db.recordMeasuredFeed(library,url("7502"),"home_following",null,null,null,now,"stats:cap",600000,3000));
            assertTrue(db.recordFeedVisible(library,url("7503"),"home_for_you",null,null,null,now));
            db.pruneHistory(library,7,now);
        });
        XsArchiveDatabase.HistoryStats s=db.historyStats(library);
        assertEquals(3,s.postCount);assertEquals(0,s.openEventCount);assertEquals(1,s.lifetimeOpenedCount);assertEquals(1,s.everOpenedPostCount);
        assertEquals(3,s.feedEventCount);assertEquals(3,s.distinctFeedPosts);assertEquals(0,s.distinctOpenPosts);assertEquals(2,s.neverOpenedPostCount);
        assertEquals(604500,s.totalDwellLowerBoundMs);assertEquals(600000,s.maxDwellLowerBoundMs);assertEquals(1,s.cappedDwellCount);assertEquals(1,s.unknownDwellCount);
    }
    @Test public void policyKeepsTicketsAndQueuedFreshWritesButRejectsExpiredCallbacks()throws Exception {
        write(library,()->assertTrue(db.recordObservedOpen(library,url("7601"),null,null,null,now-9*DAY,"epoch:old")));
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1),done=new CountDownLatch(1);AtomicBoolean success=new AtomicBoolean();AtomicLong removed=new AtomicLong();
        XsLibraryWrites.Ticket original=XsLibraryWrites.ticket(library);assertTrue(XsLibraryWrites.submit(original,()->{started.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException error){throw new AssertionError(error);}}));assertTrue(started.await(5,TimeUnit.SECONDS));
        XsRetentionController.apply(app,library,7,(ok,count)->{success.set(ok);removed.set(count);done.countDown();});
        assertTrue("History policy must not invalidate a media operation's ticket",XsLibraryWrites.isCurrent(original));
        CountDownLatch queuedDone=new CountDownLatch(1);AtomicReference<Throwable> queuedFailure=new AtomicReference<>();
        assertTrue(XsLibraryWrites.submit(original,()->{
            try {
                assertFalse(db.recordObservedOpen(library,url("7601"),null,null,null,now-9*DAY,"epoch:old"));
                assertFalse(db.recordOpened(library,url("7603"),null,null,null,now-9*DAY));
                assertFalse(db.recordMeasuredFeed(library,url("7602"),"home_following",null,null,null,now-9*DAY,"epoch:feed",3500,3000));
                assertTrue(db.recordObservedOpen(library,url("7601"),null,null,null,System.currentTimeMillis(),"epoch:queued-new"));
            } catch(Throwable error){queuedFailure.set(error);}finally{queuedDone.countDown();}
        }));
        release.countDown();assertTrue(done.await(10,TimeUnit.SECONDS));assertTrue(queuedDone.await(10,TimeUnit.SECONDS));
        if(queuedFailure.get()!=null)throw new AssertionError(queuedFailure.get());
        assertTrue(success.get());assertEquals(1,removed.get());assertTrue(XsLibraryWrites.isCurrent(original));assertEquals(7,XsRetentionSettings.getDays(db,library));assertEquals(0,XsRetentionSettings.getDays(db,other));
        write(library,()->{
            assertFalse(db.recordObservedOpen(library,url("7601"),null,null,null,now-9*DAY,"epoch:old"));
            assertFalse(db.recordMeasuredFeed(library,url("7602"),"home_following",null,null,null,now-9*DAY,"epoch:feed",3500,3000));
            assertTrue(db.recordObservedOpen(library,url("7601"),null,null,null,System.currentTimeMillis(),"epoch:new"));
        });
        try(XsArchiveDatabase reopened=new XsArchiveDatabase(app)){assertEquals(7,XsRetentionSettings.getDays(reopened,library));assertEquals(2,reopened.historyStats(library).openEventCount);}
    }
    @Test public void sqliteFailureRollsBackDeletionSummaryAndPolicy()throws Exception {
        write(library,()->assertTrue(db.recordObservedOpen(library,url("7701"),null,null,null,now-9*DAY,"failure:open")));
        db.getWritableDatabase().execSQL("CREATE TRIGGER retention_fixture_failure BEFORE DELETE ON history_events WHEN old.account_id='"+library+"' BEGIN SELECT RAISE(ABORT,'retention fixture'); END");
        try {
            CountDownLatch done=new CountDownLatch(1);AtomicBoolean success=new AtomicBoolean(true);AtomicLong removed=new AtomicLong(-1);
            XsRetentionController.apply(app,library,7,(ok,count)->{success.set(ok);removed.set(count);done.countDown();});
            assertTrue(done.await(10,TimeUnit.SECONDS));assertFalse(success.get());assertEquals(0,removed.get());assertEquals(0,XsRetentionSettings.getDays(db,library));assertEquals(1,db.historyStats(library).openEventCount);
        } finally {db.getWritableDatabase().execSQL("DROP TRIGGER retention_fixture_failure");}
    }
    @Test public void failedDurablePreferenceCommitRestoresCacheAndLeavesEvents()throws Exception {
        write(library,()->assertTrue(db.recordObservedOpen(library,url("7751"),null,null,null,now-9*DAY,"preference:old")));
        java.util.Map<String,Object> cached=new java.util.HashMap<>();
        android.content.SharedPreferences failed=(android.content.SharedPreferences)java.lang.reflect.Proxy.newProxyInstance(
            android.content.SharedPreferences.class.getClassLoader(),new Class<?>[]{android.content.SharedPreferences.class},(proxy,method,args)->{
                if(method.getName().equals("getInt")){Object value=cached.get((String)args[0]);return value==null?args[1]:value;}
                if(method.getName().equals("edit")){
                    java.util.Map<String,Object> pending=new java.util.HashMap<>();
                    return java.lang.reflect.Proxy.newProxyInstance(android.content.SharedPreferences.Editor.class.getClassLoader(),new Class<?>[]{android.content.SharedPreferences.Editor.class},(editor,action,parameters)->{
                        if(action.getName().equals("putInt")){pending.put((String)parameters[0],parameters[1]);return editor;}
                        if(action.getName().equals("commit")){cached.putAll(pending);return false;}
                        throw new UnsupportedOperationException(action.getName());
                    });
                }
                throw new UnsupportedOperationException(method.getName());
            });
        Context failing=new android.content.ContextWrapper(app){
            @Override public Context getApplicationContext(){return this;}
            @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){return name.equals("xs_history_retention")?failed:super.getSharedPreferences(name,mode);}
        };
        CountDownLatch done=new CountDownLatch(1);AtomicBoolean success=new AtomicBoolean(true);
        XsRetentionController.apply(failing,library,7,(ok,count)->{success.set(ok);done.countDown();});assertTrue(done.await(10,TimeUnit.SECONDS));assertFalse(success.get());
        try(XsArchiveDatabase underFailure=new XsArchiveDatabase(failing)){assertEquals("Failed commit must not leave an unpersisted policy in cached reads",0,XsRetentionSettings.getDays(underFailure,library));}
        assertEquals(1,db.historyStats(library).openEventCount);
    }
    @Test public void lazyMaintenancePrunesExpiredDetailsWithoutRequiringBackgroundScheduling()throws Exception {
        apply(7);write(library,()->{
            assertTrue(db.recordObservedOpen(library,url("7801"),null,null,null,System.currentTimeMillis(),"lazy:open"));
            db.getWritableDatabase().execSQL("UPDATE history_events SET event_at_ms=? WHERE account_id=?",new Object[]{now-9*DAY,library});
        });
        XsLibraryWrites.Ticket original=XsLibraryWrites.ticket(library);
        assertEquals(1,clean());assertTrue("Lazy maintenance must not cancel an in-progress media ticket",XsLibraryWrites.isCurrent(original));
        assertEquals(0,db.historyStats(library).openEventCount);assertEquals(1,db.historyStats(library).lifetimeOpenedCount);
    }
    private long clean()throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicBoolean success=new AtomicBoolean();AtomicLong removed=new AtomicLong();
        XsRetentionController.cleanNow(app,library,(ok,count)->{success.set(ok);removed.set(count);done.countDown();});assertTrue(done.await(10,TimeUnit.SECONDS));assertTrue(success.get());return removed.get();
    }
    private void apply(int days)throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicBoolean success=new AtomicBoolean();XsRetentionController.apply(app,library,days,(ok,count)->{success.set(ok);done.countDown();});assertTrue(done.await(10,TimeUnit.SECONDS));assertTrue(success.get());
    }
    private static String url(String id){return "https://x.com/i/web/status/"+id;}
    private interface Mutation {void run()throws Exception;}
    private void write(String scope,Mutation mutation)throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();assertTrue(XsLibraryWrites.submit(scope,()->{try{mutation.run();}catch(Throwable error){failure.set(error);}finally{done.countDown();}}));assertTrue(done.await(10,TimeUnit.SECONDS));if(failure.get()!=null)throw new AssertionError(failure.get());
    }
}

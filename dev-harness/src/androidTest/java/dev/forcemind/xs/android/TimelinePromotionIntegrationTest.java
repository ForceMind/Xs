package dev.forcemind.xs.android;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.forcemind.xs.core.ArchiveSchema;
import dev.forcemind.xs.core.ArchiveSearch;
import dev.forcemind.xs.core.LibraryMergePlan;
import dev.forcemind.xs.core.SnapshotPolicy;
import dev.forcemind.xs.core.TimelinePromotionData;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real separate SQLite stores and interruption boundaries, never real-X acceptance. */
@RunWith(AndroidJUnit4.class)
public final class TimelinePromotionIntegrationTest {
    private Context app;private XsArchiveDatabase archive;private XsTimelineBuffer buffer;
    private XsTimelineBuffer.Ticket ticket;private String library,tempName;private Clock clock;
    private static final class Clock implements XsTimelineBuffer.Clock {
        long wall=System.currentTimeMillis(),elapsed=1000;
        public long wall(){return wall;}public long elapsed(){return elapsed;}public String boot(){return "promotion-fixture-boot";}
    }
    @Before public void open()throws Exception {
        app=InstrumentationRegistry.getInstrumentation().getTargetContext();XsCaptureBridge.unbindAccount();
        app.deleteDatabase("xs-local-history.db");archive=new XsArchiveDatabase(app);
        library="local:promotion:"+UUID.randomUUID();archive.ensureLibrary(library,"Promotion fixture");
        tempName="xs-timeline-promotion-"+UUID.randomUUID()+".db";clock=new Clock();buffer=new XsTimelineBuffer(app,tempName,clock);
        buffer.configure(true,48,100);ticket=buffer.ticket(library);
    }
    @After public void close()throws Exception {
        CountDownLatch done=new CountDownLatch(1);assertTrue(XsLibraryWrites.invalidateAndSubmit(library,done::countDown));assertTrue(done.await(10,TimeUnit.SECONDS));
        archive.close();buffer.close();app.deleteDatabase(tempName);app.deleteDatabase("xs-local-history.db");
    }
    @Test public void archiveCommitThenLostAckRetryRetainsOneSnapshotAndNoViewingFacts()throws Exception {
        long id=append("321","visible 临时正文",0);XsTimelineBuffer.Entry e=buffer.entry(ticket,id);
        assertNotNull(e);assertNotNull(e.url);
        TimelinePromotionData data=data(e);long saved=clock.wall+50;
        queued(()->assertTrue(archive.promoteTimeline(library,data,false,saved,()->buffer.entry(ticket,id)!=null)));
        assertEquals(0,buffer.entry(ticket,id).savedAt); // process can die at this exact boundary
        archive.close();archive=new XsArchiveDatabase(app);
        XsTimelinePromotion.Result result=queuedResult(()->XsTimelinePromotion.promote(app,buffer,ticket,id,false,saved+100));
        assertTrue(result.committed);assertTrue(result.acknowledged);assertFalse(result.favorite);
        assertEquals(1,count("posts"));assertEquals(1,count("timeline_promotions"));assertEquals(1,count("snapshot_versions"));
        assertEquals(1,count("user_actions"));assertEquals(0,count("history_events"));assertEquals(0,count("favorites"));assertEquals(0,count("own_actions"));
        try(Cursor c=archive.getReadableDatabase().rawQuery("SELECT p.opened_count,p.last_opened_at_ms,p.last_feed_seen_at_ms,v.source,v.quality,v.captured_at_ms,t.saved_at_ms,t.timeline_source,t.provenance FROM posts p JOIN snapshot_versions v ON v.account_id=p.account_id AND v.post_id=p.post_id JOIN timeline_promotions t ON t.account_id=p.account_id AND t.post_id=p.post_id WHERE p.account_id=?",new String[]{library})) {
            assertTrue(c.moveToFirst());assertEquals(0,c.getInt(0));assertTrue(c.isNull(1));assertTrue(c.isNull(2));
            assertEquals("native_timeline_buffer",c.getString(3));assertEquals("PARTIAL",c.getString(4));assertEquals(e.capturedAt,c.getLong(5));
            assertEquals(saved,c.getLong(6));assertEquals("unknown",c.getString(7));assertEquals(e.provenance,c.getString(8));
        }
        ArchiveSearch.Filter f=new ArchiveSearch.Filter();f.keyword="临时正文";try(Cursor c=archive.search(library,f)){assertEquals(1,c.getCount());}
    }
    @Test public void favoriteRetriesAreIdempotentAndBufferClearRetainsPermanentRows()throws Exception {
        long id=append("322","favorite body",0);long saved=clock.wall+20;
        for(int i=0;i<3;i++) {
            final long at=saved+i;XsTimelinePromotion.Result result=queuedResult(()->XsTimelinePromotion.promote(app,buffer,ticket,id,true,at));
            assertTrue(result.committed);assertTrue(result.favorite);
        }
        assertEquals(1,count("favorites"));assertEquals(1,count("timeline_promotions"));assertEquals(1,count("snapshot_versions"));assertEquals(1,count("user_actions"));assertEquals(0,count("history_events"));
        assertEquals(saved,scalar("SELECT favorited_at_ms FROM favorites WHERE account_id=?"));
        assertEquals(saved,scalar("SELECT favorited_at_ms FROM timeline_promotions WHERE account_id=?"));
        clock.wall+=49*3600000L;clock.elapsed+=49*3600000L;assertNull(buffer.entry(ticket,id));buffer.maintain();
        assertEquals(1,count("favorites"));assertEquals(1,count("posts"));assertEquals(1,count("snapshot_versions"));
    }
    @Test public void expiredOrRevokedBeforeWriteNeverPromotesAndLateGuardRollsBack()throws Exception {
        long id=append("323","must expire",0);XsTimelineBuffer.Entry e=buffer.entry(ticket,id);AtomicInteger guard=new AtomicInteger();
        queued(()->assertFalse(archive.promoteTimeline(library,data(e),true,clock.wall+1,()->guard.incrementAndGet()==1)));
        assertEquals(2,guard.get());assertEquals(0,count("posts"));assertEquals(0,count("timeline_promotions"));assertEquals(0,count("favorites"));
        clock.wall+=48*3600000L;clock.elapsed+=48*3600000L;
        assertFalse(queuedResult(()->XsTimelinePromotion.promote(app,buffer,ticket,id,true,clock.wall)).committed);
        assertEquals(0,count("posts"));assertEquals(0,count("history_events"));
    }
    @Test public void promotionPreservesCompleteSnapshotAndLinkOnlyQualityWithoutInventingText()throws Exception {
        long id=append("324","short partial",0);
        queued(()->{assertTrue(archive.favorite(library,"https://x.com/demo/status/324","known","complete accepted visible body",null,clock.wall-20));assertTrue(archive.recordSnapshot(library,"https://x.com/demo/status/324","known","complete accepted visible body",null,clock.wall-10,SnapshotPolicy.Source.POST_DETAIL,SnapshotPolicy.Quality.COMPLETE));});
        assertTrue(queuedResult(()->XsTimelinePromotion.promote(app,buffer,ticket,id,false,clock.wall+5)).committed);
        try(Cursor c=archive.getReadableDatabase().rawQuery("SELECT body FROM posts WHERE account_id=? AND post_id='324'",new String[]{library})){assertTrue(c.moveToFirst());assertEquals("complete accepted visible body",c.getString(0));}
        long link=append("325","",1);assertTrue(queuedResult(()->XsTimelinePromotion.promote(app,buffer,ticket,link,false,clock.wall+6)).committed);
        try(Cursor c=archive.getReadableDatabase().rawQuery("SELECT body,snapshot_state,opened_count FROM posts WHERE account_id=? AND post_id='325'",new String[]{library})){assertTrue(c.moveToFirst());assertTrue(c.isNull(0));assertEquals("LINK_ONLY",c.getString(1));assertEquals(0,c.getInt(2));}
        assertEquals(0,scalar("SELECT count(*) FROM snapshot_versions WHERE account_id=? AND post_id='325'"));assertEquals(0,count("history_events"));
    }
    @Test public void v6MetadataRoundTripAndConsolidationPreservePromotionAndExcludeTemporaryRows()throws Exception {
        long saved=append("326","saved metadata",0),temporary=append("327","temporary must not enter backup",1);
        assertTrue(queuedResult(()->XsTimelinePromotion.promote(app,buffer,ticket,saved,true,clock.wall+1)).committed);
        XsLibraryBackup.ValidatedBackup backup=backup();assertEquals(6,backup.schemaVersion());assertEquals(1,backup.rows("timeline_promotions").size());assertEquals(1,backup.rows("posts").size());
        String restored=XsLibraryBackup.restoreNewLibrary(app,backup,"Restored promotion");
        try(Cursor c=archive.getReadableDatabase().rawQuery("SELECT count(*) FROM timeline_promotions WHERE account_id=? AND provenance IN ('native_dom_fixture','offline_dom_fixture')",new String[]{restored})){assertTrue(c.moveToFirst());assertEquals(1,c.getInt(0));}
        Map<String,List<Map<String,Object>>> tables=new LinkedHashMap<>();for(String name:LibraryMergePlan.TABLES)tables.put(name,backup.rows(name));
        LibraryMergePlan.Result merge=LibraryMergePlan.merge(Arrays.asList(new LibraryMergePlan.Input(library,"first",tables),new LibraryMergePlan.Input(restored,"second",tables)));
        ByteArrayOutputStream out=new ByteArrayOutputStream();XsLibraryBackup.writeCandidate(merge.tables,6,"Merged promotion",out);
        XsLibraryBackup.ValidatedBackup merged=XsLibraryBackup.inspect(new ByteArrayInputStream(out.toByteArray()));assertEquals(2,merged.rows("timeline_promotions").size());
        assertNotEquals(merged.rows("timeline_promotions").get(0).get("promotion_id"),merged.rows("timeline_promotions").get(1).get("promotion_id"));
        assertNotNull(buffer.entry(ticket,temporary));assertEquals(0,count("history_events"));
    }
    @Test public void actualV5FileUpgradesAdditivelyAndOldBackupNormalizesWithoutInventedPromotion()throws Exception {
        archive.close();app.deleteDatabase("xs-local-history.db");SQLiteDatabase old=app.openOrCreateDatabase("xs-local-history.db",Context.MODE_PRIVATE,null);
        for(String sql:ArchiveSchema.CREATE_V5)old.execSQL(sql);
        old.execSQL("INSERT INTO posts(account_id,post_id,first_seen_at_ms,last_seen_at_ms) VALUES (?,?,10,20)",new Object[]{library,"328"});
        old.setVersion(5);old.close();archive=new XsArchiveDatabase(app);assertEquals(6,archive.getWritableDatabase().getVersion());assertEquals(1,count("posts"));assertEquals(0,count("timeline_promotions"));
        archive.ensureLibrary(library,"Old v5");Map<String,List<Map<String,Object>>> rows=new LinkedHashMap<>();XsLibraryBackup.ValidatedBackup current=backup();
        for(String name:LibraryMergePlan.TABLES)if(!name.equals("timeline_promotions"))rows.put(name,current.rows(name));
        ByteArrayOutputStream out=new ByteArrayOutputStream();XsLibraryBackup.writeCandidate(rows,5,"Old v5",out);
        XsLibraryBackup.ValidatedBackup inspected=XsLibraryBackup.inspect(new ByteArrayInputStream(out.toByteArray()));assertEquals(5,inspected.sourceSchemaVersion());assertEquals(6,inspected.schemaVersion());assertEquals(0,inspected.rows("timeline_promotions").size());
        assertEquals(1,inspected.rows("posts").size());
    }
    @Test public void enrichedBodyKeepsItsOwnCaptureClockAndOriginalAppearanceTime()throws Exception {
        long first=clock.wall,id=append("330","initial",0);
        clock.wall+=1000;clock.elapsed+=1000;assertEquals(id,append("330","enriched visible body",9));
        XsTimelineBuffer.Entry entry=buffer.entry(ticket,id);assertEquals(first,entry.capturedAt);assertEquals(clock.wall,entry.snapshotCapturedAt);
        assertTrue(queuedResult(()->XsTimelinePromotion.promote(app,buffer,ticket,id,false,clock.wall+1)).committed);
        assertEquals(first,scalar("SELECT first_seen_at_ms FROM posts WHERE account_id=?"));
        assertEquals(clock.wall,scalar("SELECT captured_at_ms FROM snapshot_versions WHERE account_id=?"));
        assertEquals(first,scalar("SELECT captured_at_ms FROM timeline_promotions WHERE account_id=?"));
        assertEquals(clock.wall,scalar("SELECT snapshot_captured_at_ms FROM timeline_promotions WHERE account_id=?"));
    }
    @Test public void savingLinkThenEnrichedBodyKeepsOnePromotionAndOriginalSaveTime()throws Exception {
        long first=clock.wall,id=append("331","",0),saved=clock.wall+1;
        assertTrue(queuedResult(()->XsTimelinePromotion.promote(app,buffer,ticket,id,false,saved)).committed);
        assertEquals(0,count("snapshot_versions"));
        clock.wall+=1000;clock.elapsed+=1000;assertEquals(id,append("331","late visible 正文",9));
        for(int retry=0;retry<2;retry++)assertTrue(queuedResult(()->XsTimelinePromotion.promote(app,buffer,ticket,id,false,clock.wall+1)).committed);
        assertEquals(1,count("timeline_promotions"));assertEquals(1,count("user_actions"));assertEquals(1,count("snapshot_versions"));
        assertEquals(0,count("history_events"));assertEquals(0,count("favorites"));assertEquals(0,count("own_actions"));
        assertEquals(saved,scalar("SELECT saved_at_ms FROM timeline_promotions WHERE account_id=?"));
        assertEquals(saved,scalar("SELECT action_at_ms FROM user_actions WHERE account_id=? AND action_type='LOCAL_SAVE'"));
        assertEquals(first,scalar("SELECT captured_at_ms FROM timeline_promotions WHERE account_id=?"));
        assertEquals(clock.wall,scalar("SELECT snapshot_captured_at_ms FROM timeline_promotions WHERE account_id=?"));
        try(Cursor c=archive.getReadableDatabase().rawQuery("SELECT p.body,p.snapshot_state,p.opened_count,t.quality,t.content_hash FROM posts p JOIN timeline_promotions t ON t.account_id=p.account_id AND t.post_id=p.post_id WHERE p.account_id=?",new String[]{library})) {
            assertTrue(c.moveToFirst());assertEquals("late visible 正文",c.getString(0));assertEquals("VISIBLE_TEXT",c.getString(1));
            assertEquals(0,c.getInt(2));assertEquals("PARTIAL",c.getString(3));assertEquals(SnapshotPolicy.hash("late visible 正文"),c.getString(4));
        }
        ArchiveSearch.Filter filter=new ArchiveSearch.Filter();filter.keyword="正文";try(Cursor c=archive.search(library,filter)){assertEquals(1,c.getCount());}
    }
    @Test public void promotionBackupRejectsInventedQualitySourcesAndOldSchemaSmuggling()throws Exception {
        long id=append("329","validated snapshot",0);
        assertTrue(queuedResult(()->XsTimelinePromotion.promote(app,buffer,ticket,id,false,clock.wall+1)).committed);
        XsLibraryBackup.ValidatedBackup valid=backup();
        for(String badField:new String[]{"quality","timeline_source","provenance","post_id"}) {
            Map<String,List<Map<String,Object>>> tables=new LinkedHashMap<>();
            for(String name:LibraryMergePlan.TABLES) {
                java.util.ArrayList<Map<String,Object>> rows=new java.util.ArrayList<>();
                for(Map<String,Object> row:valid.rows(name))rows.add(new LinkedHashMap<>(row));
                tables.put(name,rows);
            }
            tables.get("timeline_promotions").get(0).put(badField,badField.equals("quality")?"COMPLETE":badField.equals("post_id")?"999":"invented");
            try{XsLibraryBackup.writeCandidate(tables,6,"invalid",new ByteArrayOutputStream());fail("Unvalidated promotion metadata");}catch(IllegalArgumentException expected){}
        }
        Map<String,List<Map<String,Object>>> old=new LinkedHashMap<>();
        for(String name:LibraryMergePlan.TABLES)if(!name.equals("timeline_promotions"))old.put(name,valid.rows(name));
        try{XsLibraryBackup.writeCandidate(old,5,"invalid old source",new ByteArrayOutputStream());fail("New timeline source smuggled into schema5");}catch(IllegalArgumentException expected){}
        assertEquals(1,count("posts"));assertEquals(0,count("history_events"));
    }
    private TimelinePromotionData data(XsTimelineBuffer.Entry e){return new TimelinePromotionData(e.promotionId,e.url,e.body,e.author,e.publishedAt,e.capturedAt,e.snapshotCapturedAt,e.source,e.batchId,e.batchReason,e.provenance,e.quality);}
    private long append(String post,String body,long seq)throws Exception {
        queued(()->assertTrue(buffer.append(ticket,new XsTimelineBuffer.Batch("promotion-batch","native_navigation","unknown"),new XsTimelineBuffer.Candidate("https://x.com/demo/status/"+post,"author",body,null,body.isEmpty()?SnapshotPolicy.Quality.LINK_ONLY:SnapshotPolicy.Quality.PARTIAL,clock.wall,seq))));
        for(XsTimelineBuffer.Entry e:buffer.list(library,"","",50,0))if(e.postId.equals(post))return e.appearanceId;throw new AssertionError("Missing appearance");
    }
    private XsLibraryBackup.ValidatedBackup backup()throws Exception {ByteArrayOutputStream out=new ByteArrayOutputStream();XsLibraryBackup.exportLibrary(app,library,out);return XsLibraryBackup.inspect(new ByteArrayInputStream(out.toByteArray()));}
    private long count(String table){return scalar("SELECT count(*) FROM "+table+" WHERE account_id=?");}
    private long scalar(String sql){try(Cursor c=archive.getReadableDatabase().rawQuery(sql,new String[]{library})){assertTrue(c.moveToFirst());return c.getLong(0);}}
    private interface Op<T>{T run()throws Exception;}
    private <T>T queuedResult(Op<T> op)throws Exception {AtomicReference<T> result=new AtomicReference<>();queued(()->result.set(op.run()));return result.get();}
    private interface Work {void run()throws Exception;}
    private void queued(Work work)throws Exception {CountDownLatch done=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();assertTrue(XsLibraryWrites.submit(library,()->{try{work.run();}catch(Throwable ex){failure.set(ex);}finally{done.countDown();}}));assertTrue(done.await(15,TimeUnit.SECONDS));if(failure.get()!=null)throw new AssertionError(failure.get());}
}

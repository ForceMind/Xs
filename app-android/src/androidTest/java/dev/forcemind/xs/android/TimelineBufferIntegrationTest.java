package dev.forcemind.xs.android;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.forcemind.xs.core.SnapshotPolicy;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Actual Android SQLite/FTS/sidecar evidence. Clock control does not claim physical-device timing. */
@RunWith(AndroidJUnit4.class)
public final class TimelineBufferIntegrationTest {
    private static final long HOUR=3600000L;
    private Context app;private XsTimelineBuffer store;private MutableClock clock;
    private String name,library,other;
    private static final class MutableClock implements XsTimelineBuffer.Clock {
        long wall=1700000000000L,elapsed=1000;String boot="timeline-test-boot-a";
        public long wall(){return wall;}public long elapsed(){return elapsed;}public String boot(){return boot;}
        void advance(long duration){wall+=duration;elapsed+=duration;}
    }
    @Before public void setUp(){
        app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        name="xs-timeline-test-"+UUID.randomUUID()+".db";clock=new MutableClock();
        library="local:"+UUID.randomUUID();other="local:"+UUID.randomUUID();
        store=new XsTimelineBuffer(app,name,clock);
    }
    @After public void tearDown(){if(store!=null)store.close();if(name!=null)app.deleteDatabase(name);}
    @Test public void freshIndependentPolicyDefaultsAndBoundsPersistWithoutOptingIntoCapture() throws Exception {
        XsTimelineBuffer.Settings defaults=store.settings();assertFalse(defaults.enabled);assertEquals(48,defaults.hours);assertEquals(100,defaults.budgetMiB);
        assertFalse(append(store.ticket(library),batch("default"),candidate("9101","not opted in",0)));
        assertEquals(0,store.stats(library).count);
        for(int[] bounds:new int[][]{{1,25},{168,500}}){store.configure(false,bounds[0],bounds[1]);assertEquals(bounds[0],store.settings().hours);assertEquals(bounds[1],store.settings().budgetMiB);}
        for(int[] invalid:new int[][]{{0,100},{169,100},{48,24},{48,501}}){try{store.configure(true,invalid[0],invalid[1]);fail("Invalid policy accepted");}catch(IllegalArgumentException expected){}}
        store.close();store=new XsTimelineBuffer(app,name,clock);assertFalse(store.settings().enabled);assertEquals(168,store.settings().hours);assertEquals(500,store.settings().budgetMiB);
    }
    @Test public void exactExpiryAppliesToListPreviewSearchCountsAndPolicyExtensionCannotRevive() throws Exception {
        store.configure(true,1,100);XsTimelineBuffer.Ticket ticket=store.ticket(library);
        assertTrue(append(ticket,batch("expiry"),candidate("9102","offline robot 机器人",0)));
        long id=store.list(library,null,null,100,0).get(0).appearanceId;
        clock.advance(HOUR-1);assertEquals(1,store.list(library,"robot",null,100,0).size());assertNotNull(store.entry(library,id));
        clock.advance(1);assertTrue(store.list(library,null,null,100,0).isEmpty());assertTrue(store.list(library,"机器人",null,100,0).isEmpty());
        assertNull(store.entry(library,id));assertEquals(0,store.stats(library).count);assertTrue(store.batches(library).isEmpty());
        store.configure(true,168,100);assertTrue(store.list(library,"robot",null,100,0).isEmpty());
        store.close();clock.wall-=HOUR;clock.boot="timeline-test-boot-b";clock.elapsed=100;store=new XsTimelineBuffer(app,name,clock);
        assertTrue(store.list(library,null,null,100,0).isEmpty());
    }
    @Test public void rebindEnrichmentPreservesFirstOrderAndExpiryWhileNewBatchHasIndependentLifetime() throws Exception {
        store.configure(true,1,100);XsTimelineBuffer.Ticket ticket=store.ticket(library);long first=clock.wall;
        assertTrue(append(ticket,batch("before-refresh"),candidate("9103","body",5)));
        XsTimelineBuffer.Entry original=store.list(library,null,null,100,0).get(0);
        clock.advance(1000);assertTrue(append(ticket,batch("before-refresh"),candidate("9103","body with later visible text",99)));
        XsTimelineBuffer.Entry enriched=store.entry(library,original.appearanceId);assertEquals(first,enriched.capturedAt);assertEquals(5,enriched.sequence);assertEquals(original.promotionId,enriched.promotionId);
        assertEquals("body with later visible text",enriched.body);assertEquals(1,store.stats(library).count);
        assertTrue(append(ticket,new XsTimelineBuffer.Batch("after-refresh","native_refresh","unknown"),candidate("9103","body with later visible text",0)));
        assertEquals(2,store.stats(library).count);assertEquals(1,count("timeline_snapshots"));
        clock.advance(HOUR-1000);assertNull(store.entry(library,original.appearanceId));
        List<XsTimelineBuffer.Entry> remaining=store.list(library,null,null,100,0);assertEquals(1,remaining.size());assertEquals("after-refresh",remaining.get(0).batchId);
        clock.advance(1000);assertTrue(store.list(library,null,null,100,0).isEmpty());
    }
    @Test public void offlineFtsCjkAuthorBatchAndLiteralFiltersRemainScopeIsolated() throws Exception {
        store.configure(true,48,100);XsTimelineBuffer.Ticket a=store.ticket(library),b=store.ticket(other);
        assertTrue(append(a,batch("native-batch-42"),new XsTimelineBuffer.Candidate(url("9104"),"alice","offline robot 机器人 %_!",null,SnapshotPolicy.Quality.PARTIAL,clock.wall,0)));
        assertTrue(append(b,batch("native-batch-42"),candidate("9104","different scope contents",0)));
        assertEquals(1,store.list(library,"offline robot",null,100,0).size());assertEquals(1,store.list(library,"alice",null,100,0).size());
        assertEquals(1,store.list(library,"机器人",null,100,0).size());assertEquals(1,store.list(library,"%_!",null,100,0).size());
        assertTrue(store.list(library,"' OR 1=1 --",null,100,0).isEmpty());assertTrue(store.list(other,"robot",null,100,0).isEmpty());
        assertEquals(1,store.list(library,null,"native-batch-42",1,0).size());assertTrue(store.list(library,null,"native-batch-42",1,1).isEmpty());
        assertEquals(SnapshotPolicy.Quality.PARTIAL,store.list(library,null,null,100,0).get(0).quality);
    }
    @Test public void consentRevocationIsPermanentForOldTicketsButExistingEntriesRetainTheirTtl() throws Exception {
        store.configure(true,48,100);XsTimelineBuffer.Ticket old=store.ticket(library);
        assertTrue(append(old,batch("accepted"),candidate("9105","kept until expiry",0)));
        store.configure(false,48,100);assertFalse(store.isCurrent(old));store.configure(true,48,100);assertFalse(store.isCurrent(old));
        assertFalse(append(old,batch("late"),candidate("9106","stale callback",0)));
        assertEquals(1,store.stats(library).count);assertTrue(append(store.ticket(library),batch("fresh"),candidate("9106","new consent",0)));
    }
    @Test public void clearingBufferRevokesOnlyBufferGenerationAndPreservesOtherScopeAndDurableFile() throws Exception {
        store.configure(true,48,100);XsTimelineBuffer.Ticket old=store.ticket(library),outside=store.ticket(other);
        assertTrue(append(old,batch("a"),candidate("9107","remove this buffer",0)));assertTrue(append(outside,batch("b"),candidate("9108","outside scope",0)));
        XsLibraryWrites.Ticket mediaTicket=XsLibraryWrites.ticket(library);
        File canary=new File(app.getFilesDir(),"timeline-preserved-"+UUID.randomUUID()+".bin");
        try(FileOutputStream output=new FileOutputStream(canary)){output.write(new byte[]{12,34,56});}
        try{
            CountDownLatch done=new CountDownLatch(1);AtomicBoolean success=new AtomicBoolean();store.clear(library,ok->{success.set(ok);done.countDown();});
            assertTrue(done.await(20,TimeUnit.SECONDS));assertTrue(success.get());assertFalse(store.isCurrent(old));assertTrue(XsLibraryWrites.isCurrent(mediaTicket));
            assertFalse(append(old,batch("old-callback"),candidate("9107","must not revive",0)));
            assertEquals(0,store.stats(library).count);assertEquals(1,store.stats(other).count);assertTrue(canary.isFile());assertEquals(3,canary.length());
            assertTrue(append(store.ticket(library),batch("fresh"),candidate("9107","explicitly observed again",0)));
        }finally{assertTrue(canary.delete());}
    }
    @Test public void forwardClockJumpExpiresImmediatelyAndRollbackRebootNeverRevivesIt() throws Exception {
        store.configure(true,1,100);XsTimelineBuffer.Ticket ticket=store.ticket(library);
        assertTrue(append(ticket,batch("clock"),candidate("9109","clock controlled snapshot",0)));
        long wall=clock.wall;clock.wall+=2*HOUR;clock.elapsed+=1000;
        assertTrue(store.list(library,null,null,100,0).isEmpty());assertEquals("CLOCK_CHANGED",store.stats(library).pauseReason);
        assertFalse(append(ticket,batch("jump"),candidate("9110","clock capture paused",0)));
        clock.wall=wall+2000;clock.elapsed+=1000;assertTrue(store.list(library,null,null,100,0).isEmpty());
        store.close();clock.boot="timeline-test-new-boot";clock.elapsed=50;store=new XsTimelineBuffer(app,name,clock);
        assertTrue(store.list(library,null,null,100,0).isEmpty());assertEquals("CLOCK_CHANGED",store.stats(library).pauseReason);
        store.maintain();assertEquals(0,count("timeline_snapshots"));
    }
    @Test public void explicitAllTemporaryClockResetPreservesDurableStateAndAcceptsFreshCaptureAfterReenable() throws Exception {
        store.configure(true,48,25);XsTimelineBuffer.Ticket old=store.ticket(library),outside=store.ticket(other);
        assertTrue(append(old,batch("old-clock"),candidate("9181","old clock body never revived",0)));
        assertTrue(append(outside,batch("other-clock"),candidate("9182","other scope temporary body",0)));
        long oldId=store.list(library,null,null,100,0).get(0).appearanceId;
        XsLibraryWrites.Ticket mediaTicket=XsLibraryWrites.ticket(library);
        File durable=new File(app.getFilesDir(),"timeline-clock-survivor-"+UUID.randomUUID());
        try(FileOutputStream out=new FileOutputStream(durable)){out.write(new byte[]{3,1,4,1,5});}
        try(XsArchiveDatabase archive=new XsArchiveDatabase(app)){
            write(library,()->{archive.ensureLibrary(library,"clock reset archive survivor");assertTrue(archive.recordOpened(library,url("9180"),"alice","permanent clock survivor",null,System.currentTimeMillis()));});
            long original=clock.wall;clock.wall+=4L*365*24*HOUR;clock.elapsed+=1000;
            assertTrue(store.list(library,null,null,100,0).isEmpty());
            clock.wall=original+2000;clock.elapsed+=1000;
            assertEquals("CLOCK_CHANGED",store.stats(library).pauseReason);
            CountDownLatch reset=new CountDownLatch(1);AtomicBoolean success=new AtomicBoolean();
            store.resetAfterClockChange(library,ok->{success.set(ok);reset.countDown();});assertTrue(reset.await(20,TimeUnit.SECONDS));assertTrue(success.get());
            assertFalse(store.settings().enabled);assertEquals(48,store.settings().hours);assertEquals(25,store.settings().budgetMiB);
            assertFalse(store.isCurrent(old));assertFalse(store.isCurrent(outside));assertTrue(XsLibraryWrites.isCurrent(mediaTicket));
            for(String table:new String[]{"timeline_appearances","timeline_snapshots","timeline_snapshots_fts","timeline_seen","timeline_batches"})assertEquals(table,0,count(table));
            assertEquals(1,archive.historyStats(library).openEventCount);assertTrue(durable.isFile());assertEquals(5,durable.length());
            assertFalse(append(old,batch("old-callback"),candidate("9181","must remain revoked",0)));
            XsTimelineBuffer.Ticket whileOff=store.ticket(library);store.configure(true,48,25);assertFalse(store.isCurrent(whileOff));
            XsTimelineBuffer.Ticket fresh=store.ticket(library);assertTrue(append(fresh,batch("new-clock"),candidate("9183","fresh corrected clock body",0)));
            assertEquals(1,store.stats(library).count);assertEquals(0,store.stats(other).count);assertTrue(store.list(library,"never revived",null,100,0).isEmpty());
            assertTrue(store.list(library,null,null,100,0).get(0).appearanceId>oldId);assertNull(store.entry(library,oldId));
            store.close();clock.boot="timeline-reset-next-boot";clock.elapsed=50;store=new XsTimelineBuffer(app,name,clock);
            assertTrue("Corrected clock works after an actual reopen/new boot identity",store.stats(library).pauseReason.isEmpty());
            assertTrue(append(store.ticket(library),batch("new-boot"),candidate("9184","fresh after reset boot",0)));
            write(library,()->{archive.clearAccount(library);archive.getWritableDatabase().delete("libraries","storage_scope=?",new String[]{library});});
        }finally{assertTrue(durable.delete());}
    }
    @Test public void clockResetRejectsAnOldQueuedCaptureWithoutRevokingItsSharedWriter() throws Exception {
        store.configure(true,48,100);XsTimelineBuffer.Ticket old=store.ticket(library);
        assertTrue(append(old,batch("before-reset"),candidate("9185","before explicit reset",0)));
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),resetDone=new CountDownLatch(1),oldDone=new CountDownLatch(1);
        AtomicBoolean resetOk=new AtomicBoolean(),oldAccepted=new AtomicBoolean(true);
        assertTrue(XsLibraryWrites.submit(store.writerTicket(old),()->{entered.countDown();try{release.await(20,TimeUnit.SECONDS);}catch(InterruptedException stop){Thread.currentThread().interrupt();}}));assertTrue(entered.await(20,TimeUnit.SECONDS));
        try{
            store.resetAfterClockChange(library,ok->{resetOk.set(ok);resetDone.countDown();});
            assertTrue(store.queue(old,batch("queued-before-reset"),candidate("9186","queued old native result",0),ok->{oldAccepted.set(ok);oldDone.countDown();}));
        }finally{release.countDown();}
        assertTrue(resetDone.await(20,TimeUnit.SECONDS));assertTrue(oldDone.await(20,TimeUnit.SECONDS));assertTrue(resetOk.get());assertFalse(oldAccepted.get());
        assertTrue(XsLibraryWrites.isCurrent(store.writerTicket(old)));assertFalse(store.isCurrent(old));assertEquals(0,store.stats(library).count);assertEquals(0,count("timeline_snapshots_fts"));
    }
    @Test public void maintenanceActuallyReclaimsSnapshotFtsAndWalBytes() throws Exception {
        store.configure(true,1,25);XsTimelineBuffer.Ticket ticket=store.ticket(library);
        StringBuilder body=new StringBuilder("temporary reclamation ");while(body.length()<7000)body.append("visible temporary metadata ");
        for(int i=0;i<120;i++)assertTrue("insert "+i,append(ticket,batch("bulk"),candidate(Integer.toString(9200+i),body.toString()+i,i)));
        long before=store.actualBytes();assertTrue(before>512*1024);assertEquals(120,store.stats(library).count);
        clock.advance(HOUR);assertEquals(0,store.stats(library).count);store.maintain();
        assertEquals(0,count("timeline_appearances"));assertEquals(0,count("timeline_snapshots"));assertEquals(0,count("timeline_snapshots_fts"));
        long after=store.actualBytes();assertTrue("bytes before="+before+" after="+after,after<before/2);
        assertTrue("reclaim reason="+store.stats(library).reclaimReason,store.stats(library).reclaimReason.isEmpty());
    }
    @Test public void expiredAppearanceIdIsNeverReusedForALaterUiAction() throws Exception {
        store.configure(true,1,100);XsTimelineBuffer.Ticket ticket=store.ticket(library);
        assertTrue(append(ticket,batch("old-id"),candidate("9398","old selection",0)));
        long old=store.list(library,null,null,100,0).get(0).appearanceId;
        clock.advance(HOUR);store.maintain();assertNull(store.entry(library,old));
        assertTrue(append(ticket,batch("new-id"),candidate("9397","new selection",0)));
        long fresh=store.list(library,null,null,100,0).get(0).appearanceId;assertTrue(fresh>old);assertNull(store.entry(library,old));
    }
    @Test public void expiredSameBatchReceiptCannotBeReinsertedAfterGcOrTtlExtension() throws Exception {
        store.configure(true,1,100);XsTimelineBuffer.Ticket ticket=store.ticket(library);
        assertTrue(append(ticket,batch("same-batch"),candidate("9399","old temporary text",0)));
        clock.advance(HOUR);store.maintain();assertEquals(0,store.stats(library).count);
        store.configure(true,168,100);assertFalse(append(ticket,batch("same-batch"),candidate("9399","late rebind",1)));
        assertEquals(0,store.stats(library).count);
        assertTrue(append(ticket,batch("genuine-new-batch"),candidate("9399","observed again",0)));assertEquals(1,store.stats(library).count);
    }
    @Test public void concurrentScopesShareOneMeasuredBudgetAndLoweringBudgetEvictsOrPausesSafely() throws Exception {
        store.configure(true,48,50);XsTimelineBuffer.Ticket a=store.ticket(library),b=store.ticket(other);
        StringBuilder text=new StringBuilder();while(text.length()<15800)text.append("bounded metadata payload ");final String body=text.toString();
        ExecutorService producers=Executors.newFixedThreadPool(2);
        try{
            Future<?> first=producers.submit(()->{try{for(int i=0;i<500;i++)append(a,batch("quota-a"),candidate(Integer.toString(960000+i),body+i,i));}catch(Exception failure){throw new AssertionError(failure);}});
            Future<?> second=producers.submit(()->{try{for(int i=0;i<500;i++)append(b,batch("quota-b"),candidate(Integer.toString(970000+i),body+i,i));}catch(Exception failure){throw new AssertionError(failure);}});
            first.get(180,TimeUnit.SECONDS);second.get(180,TimeUnit.SECONDS);
        }finally{producers.shutdownNow();}
        assertEquals("One physical budget is reported for both scopes",store.stats(library).actualBytes,store.stats(other).actualBytes);
        assertTrue(store.actualBytes()<=50*1024*1024L);
        store.configure(true,48,25);store.maintain();
        XsTimelineBuffer.Stats stats=store.stats(library);
        assertTrue("Actual temporary bytes="+store.actualBytes(),store.actualBytes()<=25*1024*1024L);
        assertTrue("Pressure must be explained",stats.dropped>0||!stats.pauseReason.isEmpty());
        assertTrue(store.stats(library).count+store.stats(other).count<1000);
    }
    @Test public void pendingClearSurvivesReopenAndIsHiddenBeforeMaintenanceRepairsIt() throws Exception {
        store.configure(true,48,100);XsTimelineBuffer.Ticket old=store.ticket(library),outside=store.ticket(other);
        assertTrue(append(old,batch("clear-crash"),candidate("9501","pending clear text",0)));assertTrue(append(outside,batch("outside"),candidate("9502","outside remains",0)));
        store.getWritableDatabase().execSQL("UPDATE timeline_scopes SET generation=generation+1,pending_clear=1 WHERE library_id=?",new Object[]{library});
        store.close();store=new XsTimelineBuffer(app,name,clock);
        assertTrue(store.list(library,null,null,100,0).isEmpty());assertEquals(0,store.stats(library).count);assertTrue(store.batches(library).isEmpty());
        store.maintain();assertEquals(1,count("timeline_appearances"));assertEquals(1,store.stats(other).count);
        assertTrue(append(store.ticket(library),batch("after-clear-recovery"),candidate("9501","fresh observation",0)));
    }
    @Test public void readerLockReportsReclamationPendingThenReleasesItWithoutFalseFreeClaim() throws Exception {
        store.configure(true,1,100);XsTimelineBuffer.Ticket ticket=store.ticket(library);
        assertTrue(append(ticket,batch("reader"),candidate("9503","reader held snapshot",0)));
        SQLiteDatabase reader=SQLiteDatabase.openDatabase(app.getDatabasePath(name).getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY);
        try{
            // SAVEPOINT starts an actual deferred read transaction on the separate read-only connection.
            reader.execSQL("SAVEPOINT xs_timeline_reader");
            try(Cursor rows=reader.rawQuery("SELECT body FROM timeline_snapshots",null)){assertTrue(rows.moveToFirst());assertEquals("reader held snapshot",rows.getString(0));}
            clock.advance(HOUR);store.maintain();
            assertEquals(0,store.stats(library).count);
            assertFalse("Checkpoint must report the held reader",store.stats(library).reclaimReason.isEmpty());
            reader.execSQL("RELEASE xs_timeline_reader");
        }finally{reader.close();}
        store.maintain();assertEquals(0,count("timeline_snapshots"));assertEquals(0,count("timeline_snapshots_fts"));
        assertTrue("Reader released",store.stats(library).reclaimReason.isEmpty());
    }
    @Test public void corruptTemporaryDatabaseCannotEraseArchiveOrExistingFiles() throws Exception {
        String post="9504";File canary=new File(app.getFilesDir(),"timeline-corruption-canary-"+UUID.randomUUID());
        try(XsArchiveDatabase archive=new XsArchiveDatabase(app)){
            write(library,()->{archive.ensureLibrary(library,"corruption survivor");assertTrue(archive.recordOpened(library,url(post),"alice","durable archive corruption survivor",null,System.currentTimeMillis()));});
            try(FileOutputStream out=new FileOutputStream(canary)){out.write(new byte[]{1,4,9,16});}
            File temporary=app.getDatabasePath(name);assertTrue(temporary.getParentFile().isDirectory()||temporary.getParentFile().mkdirs());
            try(FileOutputStream out=new FileOutputStream(temporary)){out.write("corrupt disposable timeline bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            try{store.settings();}catch(RuntimeException expected){/* Failure stays scoped to the disposable DB. */}
            assertEquals(1,archive.historyStats(library).openEventCount);assertTrue(canary.isFile());assertEquals(4,canary.length());
            try(Cursor row=archive.getReadableDatabase().rawQuery("SELECT body FROM posts WHERE account_id=? AND post_id=?",new String[]{library,post})){assertTrue(row.moveToFirst());assertEquals("durable archive corruption survivor",row.getString(0));}
            write(library,()->{archive.clearAccount(library);archive.getWritableDatabase().delete("libraries","storage_scope=?",new String[]{library});});
        }finally{assertTrue(canary.delete());}
    }
    @Test public void scheduledMaintenanceUsesTheExistingBoundedScopeWriterAndCanBeCanceled() throws Exception {
        store.configure(true,1,100);XsTimelineBuffer.Ticket ticket=store.ticket(library);
        assertTrue(append(ticket,batch("maintenance-writer"),candidate("9599","expires behind shared writer",0)));clock.advance(HOUR);
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1),finished=new CountDownLatch(1);AtomicBoolean success=new AtomicBoolean();
        assertTrue(XsLibraryWrites.submit(store.writerTicket(ticket),()->{started.countDown();try{release.await(20,TimeUnit.SECONDS);}catch(InterruptedException stop){Thread.currentThread().interrupt();}}));
        assertTrue(started.await(20,TimeUnit.SECONDS));
        try{
            store.maintainAsync(()->true,ok->{success.set(ok);finished.countDown();});
            assertEquals("Physical GC waits behind the existing writer",1,count("timeline_appearances"));
            assertEquals(1,finished.getCount());
        }finally{release.countDown();}
        assertTrue(finished.await(20,TimeUnit.SECONDS));assertTrue(success.get());assertEquals(0,count("timeline_snapshots"));
        CountDownLatch stopped=new CountDownLatch(1);AtomicBoolean canceled=new AtomicBoolean(true);
        store.maintainAsync(()->false,ok->{canceled.set(ok);stopped.countDown();});assertTrue(stopped.await(20,TimeUnit.SECONDS));assertFalse(canceled.get());
    }
    @Test public void durableLibraryTombstoneRevokesPendingWorkAndMaintenanceErasesOnlyDeletedScope() throws Exception {
        store.configure(true,48,100);XsTimelineBuffer.Ticket ticket=store.ticket(library),outside=store.ticket(other);
        assertTrue(append(ticket,batch("delete"),candidate("9401","deleted library buffer",0)));assertTrue(append(outside,batch("other"),candidate("9402","other remains",0)));
        XsDeletedLibraries.markDeleted(app,library);XsDeletedLibraries.initialize(app);assertFalse(store.isCurrent(ticket));
        assertFalse(append(ticket,batch("late"),candidate("9401","no resurrection",0)));store.maintain();
        assertEquals(1,count("timeline_appearances"));assertEquals(1,store.stats(other).count);
        store.close();store=new XsTimelineBuffer(app,name,clock);try{store.ticket(library);fail("Deleted library reopened");}catch(IllegalStateException expected){}
        assertEquals(1,store.stats(other).count);XsDeletedLibraries.completeDeletion(app,library);
    }
    private XsTimelineBuffer.Batch batch(String id){return new XsTimelineBuffer.Batch(id,"native_navigation","unknown");}
    private XsTimelineBuffer.Candidate candidate(String post,String body,long sequence){return new XsTimelineBuffer.Candidate(url(post),"alice",body,null,SnapshotPolicy.Quality.PARTIAL,clock.wall,sequence);}
    private static String url(String post){return "https://x.com/i/web/status/"+post;}
    private long count(String table){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT count(*) FROM "+table,null)){assertTrue(c.moveToFirst());return c.getLong(0);}}
    private static void write(String library,Runnable operation)throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();
        assertTrue(XsLibraryWrites.submit(library,()->{try{operation.run();}catch(Throwable problem){failure.set(problem);}finally{done.countDown();}}));
        assertTrue(done.await(20,TimeUnit.SECONDS));if(failure.get()!=null)throw new AssertionError(failure.get());
    }
    private boolean append(XsTimelineBuffer.Ticket ticket,XsTimelineBuffer.Batch batch,XsTimelineBuffer.Candidate candidate)throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicBoolean accepted=new AtomicBoolean();AtomicReference<Throwable> failure=new AtomicReference<>();
        boolean queued=XsLibraryWrites.submit(store.writerTicket(ticket),()->{try{accepted.set(store.append(ticket,batch,candidate));}catch(Throwable problem){failure.set(problem);}finally{done.countDown();}},done::countDown);
        if(!queued)return false;assertTrue("shared write timed out",done.await(20,TimeUnit.SECONDS));if(failure.get()!=null)throw new AssertionError(failure.get());return accepted.get();
    }
}

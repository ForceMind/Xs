package dev.forcemind.xs.android;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.forcemind.xs.core.ArchiveSearch;
import dev.forcemind.xs.core.SnapshotPolicy;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Per-database SQLite FULL, not a claim of exhausting the device's filesystem. */
@RunWith(AndroidJUnit4.class)
public final class TimelineStoragePressureTest {
    private Context app;
    private XsTimelineBuffer store;
    private XsArchiveDatabase archive;
    private String name,library;
    private File canary;
    private static final byte[] BYTES={4,8,15,16,23,42};
    private final XsTimelineBuffer.Clock clock=new XsTimelineBuffer.Clock(){
        public long wall(){return System.currentTimeMillis();}
        public long elapsed(){return android.os.SystemClock.elapsedRealtime();}
        public String boot(){return "storage-pressure-fixture";}
    };
    @Before public void setUp()throws Exception {
        app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        name="xs-timeline-pressure-"+UUID.randomUUID()+".db";library="local:"+UUID.randomUUID();
        store=new XsTimelineBuffer(app,name,clock);store.configure(true,48,100);
        archive=new XsArchiveDatabase(app);
        write(()->{archive.ensureLibrary(library,"Storage pressure survivor");
            archive.favorite(library,url("98001"),"fixture","durable pressure survivor",null,System.currentTimeMillis());});
        canary=new File(app.getFilesDir(),"pressure-survivor-"+UUID.randomUUID());
        try(FileOutputStream out=new FileOutputStream(canary)){out.write(BYTES);}
        assertTrue(capture("98002","small temporary survivor"));store.maintain();
    }
    @After public void tearDown()throws Exception {
        if(store!=null){
            try{store.getWritableDatabase().setMaximumSize(100L*1024*1024);}finally{store.close();}
        }
        if(archive!=null){try{write(()->archive.deleteLibraryScope(library));}finally{archive.close();}}
        if(canary!=null)assertTrue(canary.delete());
        if(name!=null)app.deleteDatabase(name);
    }
    @Test public void sqliteFullRollsBackCaptureReportsReasonAndAllowsExplicitRetry()throws Exception {
        XsLibraryWrites.Ticket unrelated=XsLibraryWrites.ticket(library);
        SQLiteDatabase db=store.getWritableDatabase();long pages=scalar("PRAGMA page_count"),size=scalar("PRAGMA page_size");
        assertEquals("FULL fixture starts with no reusable free pages",0,scalar("PRAGMA freelist_count"));
        assertEquals(pages*size,db.setMaximumSize(pages*size));
        assertFalse("A real SQLite page cap must reject this new large snapshot",capture("98003",largeBody()));
        assertEquals("SQLITE_FULL",store.stats(library).pauseReason);assertEquals(1,store.stats(library).dropped);
        assertEquals(1,store.stats(library).count);assertTrue(store.list(library,"allocationneedle",null,10,0).isEmpty());
        assertEquals(0,scalar("SELECT COUNT(*) FROM timeline_seen WHERE post_id='98003'"));
        assertTrue(XsLibraryWrites.isCurrent(unrelated));assertDurable();
        db.setMaximumSize(100L*1024*1024);
        assertTrue(capture("98003",largeBody()));assertEquals(2,store.stats(library).count);
        assertTrue(store.stats(library).pauseReason.isEmpty());assertEquals(1,store.stats(library).dropped);
        assertDurable();
        store.close();store=new XsTimelineBuffer(app,name,clock);
        assertEquals(2,store.stats(library).count);assertEquals(1,store.stats(library).dropped);
    }
    @Test public void rejectedCaptureStillHasBoundedDiagnosticsWhenCounterWriteFails()throws Exception {
        SQLiteDatabase db=store.getWritableDatabase();
        db.execSQL("CREATE TRIGGER fixture_counter_failure BEFORE UPDATE OF dropped_count ON timeline_state BEGIN SELECT RAISE(ABORT,'fixture counter unavailable'); END");
        store.maintain();long pages=scalar("PRAGMA page_count"),size=scalar("PRAGMA page_size");db.setMaximumSize(pages*size);
        assertFalse(capture("98004",largeBody()));
        assertEquals("SQLITE_FULL",store.stats(library).pauseReason);assertEquals(1,store.stats(library).dropped);
        assertEquals(1,store.stats(library).count);assertDurable();
        db.setMaximumSize(100L*1024*1024);db.execSQL("DROP TRIGGER fixture_counter_failure");
        assertTrue(capture("98004",largeBody()));assertTrue(store.stats(library).pauseReason.isEmpty());
        assertEquals("A failed telemetry write must not double count the same rejection",1,store.stats(library).dropped);
        CountDownLatch reset=new CountDownLatch(1);AtomicBoolean resetOk=new AtomicBoolean();
        store.resetAfterClockChange(library,ok->{resetOk.set(ok);reset.countDown();});
        assertTrue(reset.await(20,TimeUnit.SECONDS));assertTrue(resetOk.get());
        assertEquals(0,store.stats(library).dropped);assertTrue(store.stats(library).pauseReason.isEmpty());
        assertFalse(store.settings().enabled);assertDurable();
    }
    private boolean capture(String id,String body)throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicBoolean result=new AtomicBoolean();AtomicInteger callbacks=new AtomicInteger();
        assertTrue(store.queue(store.ticket(library),new XsTimelineBuffer.Batch("pressure:"+id,"native_navigation","unknown"),
            new XsTimelineBuffer.Candidate(url(id),"fixture",body,null,SnapshotPolicy.Quality.PARTIAL,clock.wall(),1),ok->{
                result.set(ok);callbacks.incrementAndGet();done.countDown();}));
        assertTrue("Pressure callback completed",done.await(20,TimeUnit.SECONDS));
        CountDownLatch drained=new CountDownLatch(1);assertTrue(XsLibraryWrites.submit(library,drained::countDown));assertTrue(drained.await(20,TimeUnit.SECONDS));
        assertEquals(1,callbacks.get());return result.get();
    }
    private void assertDurable()throws Exception {
        assertArrayEquals(BYTES,Files.readAllBytes(canary.toPath()));
        ArchiveSearch.Filter filter=new ArchiveSearch.Filter();filter.keyword="durable pressure survivor";filter.favoriteOnly=true;
        try(Cursor rows=archive.search(library,filter)){assertEquals(1,rows.getCount());}
        assertEquals(0,archive.historyStats(library).openEventCount);assertEquals(0,archive.historyStats(library).feedEventCount);
    }
    private void write(Runnable operation)throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicReference<Throwable> problem=new AtomicReference<>();
        assertTrue(XsLibraryWrites.submit(library,()->{try{operation.run();}catch(Throwable failure){problem.set(failure);}finally{done.countDown();}}));
        assertTrue(done.await(20,TimeUnit.SECONDS));if(problem.get()!=null)throw new AssertionError(problem.get());
    }
    private long scalar(String sql){try(Cursor row=store.getReadableDatabase().rawQuery(sql,null)){assertTrue(row.moveToFirst());return row.getLong(0);}}
    private static String largeBody(){StringBuilder body=new StringBuilder("allocationneedle ");while(body.length()<15000)body.append(UUID.randomUUID()).append(' ');return body.toString();}
    private static String url(String id){return "https://x.com/i/web/status/"+id;}
}

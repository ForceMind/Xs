package dev.forcemind.xs.android;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.os.Process;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.forcemind.xs.core.ArchiveSearch;
import dev.forcemind.xs.core.SnapshotPolicy;
import dev.forcemind.xs.core.TimelinePromotionData;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** The CI driver invokes seed/verify in separate OS processes and explicitly force-stops
 * a live foreground target between them. The ordinary suite only exercises recovery in
 * process; it is never counted as evidence of a process kill, reboot or power loss. */
@RunWith(AndroidJUnit4.class)
public final class ProcessRecoveryProbe {
    private static final String PREFS="xs_process_recovery_fixture";
    private static final String POST="https://x.com/i/web/status/991231";
    private static final String TEMP_POST="https://x.com/i/web/status/991232";
    private static final String SAVED_POST="https://x.com/i/web/status/991233";
    private Context app(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}
    @Test public void nativeStateAndRecoveryAcrossProcessBoundary()throws Exception {
        String phase=InstrumentationRegistry.getArguments().getString("xs_phase","");
        if("seed".equals(phase)){
            seed();assertTrue("Fixture measurement must use the ordinary application UID",Process.myUid()>=10000);
            android.os.Bundle status=new android.os.Bundle();status.putString("xs_seed_available_bytes",Long.toString(app().getFilesDir().getUsableSpace()));status.putString("xs_seed_app_uid",Integer.toString(Process.myUid()));
            InstrumentationRegistry.getInstrumentation().sendStatus(0,status);return;
        }
        if("verify".equals(phase)){try{verify(true);}finally{cleanup();}return;}
        if("low-space".equals(phase)){try{lowSpace();}finally{cleanup();}return;}
        assertEquals("Unexpected fixture phase","",phase);
        seed();
        try {
            XsDeletedLibraries.initialize(app());
            XsMediaRepository.recoverScheduled(app());
            XsLibraryDeletion.retryPending(app());
            verify(false);
        } finally {cleanup();}
    }
    private void seed()throws Exception {
        Context context=app();SharedPreferences prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        assertFalse("Previous process fixture must be cleaned",prefs.contains("survivor"));
        String survivor="local:"+UUID.randomUUID(),deleted="local:"+UUID.randomUUID(),id=UUID.randomUUID().toString(),run=UUID.randomUUID().toString();
        XsMediaRepository media=XsMediaRepository.get(context);
        File image=File.createTempFile("xs-process-fixture-",".png",context.getCacheDir());
        Bitmap bitmap=Bitmap.createBitmap(4,3,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff38a7cf);
        try(FileOutputStream out=new FileOutputStream(image)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));out.getFD().sync();}finally{bitmap.recycle();}
        String checksum=hash(image);
        try(XsArchiveDatabase archive=new XsArchiveDatabase(context)) {
            write(survivor,()->{
                archive.ensureLibrary(survivor,"Process fixture survivor");
                assertTrue(archive.recordOpened(survivor,POST,"fixture","Process boundary searchable text",null,System.currentTimeMillis()));
                assertTrue(archive.favorite(survivor,POST,"fixture","Process boundary searchable text",null,System.currentTimeMillis()));
                media.restoreCompleted(survivor,Collections.singletonList(new XsMediaRepository.RestoredAsset(POST,"IMAGE","image/png",image.length(),checksum,"fixture",System.currentTimeMillis(),0,0,0,0,0,0,image)));
            });
            write(deleted,()->{archive.ensureLibrary(deleted,"Process fixture pending deletion");assertTrue(archive.favorite(deleted,POST,"fixture","Delete only this scope",null,System.currentTimeMillis()));});
        } finally {assertTrue(image.delete());}
        XsTimelineBuffer timeline=XsTimelineBuffer.get(context);XsTimelineBuffer.Settings previous=timeline.settings();
        timeline.configure(true,48,100);
        String batch="process-buffer:"+UUID.randomUUID();
        write(survivor,()->{
            long now=System.currentTimeMillis();XsTimelineBuffer.Ticket ticket=timeline.ticket(survivor);
            XsTimelineBuffer.Batch nativeBatch=new XsTimelineBuffer.Batch(batch,"native_navigation","home_following");
            assertTrue(timeline.append(ticket,nativeBatch,new XsTimelineBuffer.Candidate(TEMP_POST,"fixture","Temporary process boundary canary",null,SnapshotPolicy.Quality.PARTIAL,now,1)));
            assertTrue(timeline.append(ticket,nativeBatch,new XsTimelineBuffer.Candidate(SAVED_POST,"fixture","Durably saved process buffer canary",null,SnapshotPolicy.Quality.PARTIAL,now,2)));
            XsTimelineBuffer.Entry saved=timeline.list(survivor,"Durably saved process",batch,10,0).get(0);
            TimelinePromotionData projection=new TimelinePromotionData(saved.promotionId,saved.url,saved.body,saved.author,
                saved.publishedAt,saved.capturedAt,saved.snapshotCapturedAt,saved.source,saved.batchId,saved.batchReason,saved.provenance,saved.quality);
            // Fixture fault seam: commit the actual Archive half, then deliberately omit
            // buffer acknowledgement. External drivers stop this OS process before verify;
            // the ordinary suite exercises only the same-process interruption state.
            try(XsArchiveDatabase archive=new XsArchiveDatabase(context)){
                assertTrue(archive.promoteTimeline(survivor,projection,false,now+1,()->{
                    XsTimelineBuffer.Entry current=timeline.entry(ticket,saved.appearanceId);
                    return current!=null&&saved.promotionId.equals(current.promotionId);
                }));
                try(Cursor c=archive.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM timeline_promotions WHERE account_id=? AND promotion_id=?",new String[]{survivor,saved.promotionId})){assertTrue(c.moveToFirst());assertEquals(1,c.getInt(0));}
            }
            assertEquals("Seed leaves an actual Archive commit pending buffer acknowledgement",0,timeline.entry(ticket,saved.appearanceId).savedAt);
        });
        write(deleted,()->{
            long now=System.currentTimeMillis();assertTrue(timeline.append(timeline.ticket(deleted),new XsTimelineBuffer.Batch("pending-delete:"+UUID.randomUUID(),"native_navigation","unknown"),
                new XsTimelineBuffer.Candidate(TEMP_POST,"fixture","Deleted temporary canary",null,SnapshotPolicy.Quality.PARTIAL,now,1)));
        });
        assertTrue(prefs.edit().putBoolean("timeline_previous_enabled",previous.enabled).putInt("timeline_previous_hours",previous.hours).putInt("timeline_previous_budget",previous.budgetMiB).commit());
        XsMediaRepository.Lease lease=media.lease(survivor,media.list(survivor).get(0).id);
        // These rows model the exact durable state of an interrupted transfer. No real
        // network request is scheduled; recovery must not silently initiate a new one.
        write(survivor,()->{
            ContentValues row=new ContentValues();row.put("id",id);row.put("library_id",survivor);row.put("post_url",POST);
            row.put("state","DOWNLOADING");row.put("created_ms",System.currentTimeMillis());row.put("media_kind","IMAGE");row.put("bytes",8);row.put("run_token",run);row.put("job_id",19798);
            row.put("url_cipher",XsDownloadSecrets.seal("https://pbs.twimg.com/media/ProcessFixture?format=png&name=orig",id+"|"+survivor+"|"+POST+"|IMAGE"));
            media.getWritableDatabase().insertOrThrow("assets",null,row);
        });
        File part=new File(new File(context.getFilesDir(),"xs-saved-media"),id+"-"+run+".part");
        try(FileOutputStream out=new FileOutputStream(part)){out.write(new byte[]{1,2,3,4,5,6,7,8});out.getFD().sync();}
        XsDeletedLibraries.markDeleted(context,deleted);
        assertTrue(context.getSharedPreferences("browser.BrowserActivity",Context.MODE_PRIVATE).edit().putBoolean("browser_notice_accepted",false).commit());
        assertTrue(prefs.edit().putString("survivor",survivor).putString("deleted",deleted).putString("task",id).putString("part",part.getName()).putString("sha256",checksum).putString("lease_token",lease.token).putString("lease_filename",lease.filename).putString("default",XsLocalSpaces.defaultLibrary(context)).putInt("seed_pid",Process.myPid()).commit());
    }
    private void verify(boolean separateProcess)throws Exception {
        Context context=app();SharedPreferences prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        String survivor=prefs.getString("survivor",null),deleted=prefs.getString("deleted",null),id=prefs.getString("task",null);assertNotNull(survivor);assertNotNull(deleted);assertNotNull(id);
        if(separateProcess){
            boolean rebooted="true".equals(InstrumentationRegistry.getArguments().getString("xs_rebooted","false"));
            if(!rebooted){
                assertNotEquals("Verification must execute in a different native process",prefs.getInt("seed_pid",-1),Process.myPid());
                int stopped=Integer.parseInt(InstrumentationRegistry.getArguments().getString("xs_stopped_pid","-1"));
                assertTrue("Host driver must identify the process actually stopped",stopped>0);
                assertNotEquals("Verification cannot reuse the stopped foreground process",stopped,Process.myPid());
            } // Across a reboot, PIDs may legitimately repeat. The host proves different kernel boot IDs.
            android.os.Bundle status=new android.os.Bundle();status.putString("xs_verify_pid",Integer.toString(Process.myPid()));
            InstrumentationRegistry.getInstrumentation().sendStatus(0,status);
        }
        XsMediaRepository media=XsMediaRepository.get(context);
        long deadline=android.os.SystemClock.elapsedRealtime()+15000;
        while(XsDeletedLibraries.pending(context).contains(deleted)&&android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(30);
        assertFalse("Startup retries the persisted deletion",XsDeletedLibraries.pending(context).contains(deleted));
        assertTrue(XsDeletedLibraries.isDeleted(context,deleted));assertTrue(XsLibraryWrites.isDeleted(deleted));
        assertFalse("Revoked scope cannot accept new work",XsLibraryWrites.submit(deleted,()->fail("Deleted callback ran")));
        try(XsArchiveDatabase archive=new XsArchiveDatabase(context)) {
            ArchiveSearch.Filter filter=new ArchiveSearch.Filter();filter.keyword="Process boundary";filter.favoriteOnly=true;
            try(Cursor c=archive.search(survivor,filter)){assertEquals(1,c.getCount());assertTrue(c.moveToFirst());assertEquals(1,c.getInt(c.getColumnIndexOrThrow("opened_count")));}
            try(Cursor c=archive.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM posts WHERE account_id=?",new String[]{deleted})){assertTrue(c.moveToFirst());assertEquals(0,c.getInt(0));}
        }
        XsTimelineBuffer timeline=XsTimelineBuffer.get(context);
        assertEquals(separateProcess?"The temporary DB survives the actual process/boot/upgrade boundary":"The ordinary suite verifies the same-process recovery seam only",2,timeline.list(survivor,"process",null,10,0).size());
        XsTimelineBuffer.Entry temporary=timeline.list(survivor,"Temporary process boundary",null,10,0).get(0);
        assertEquals("991232",temporary.postId);assertEquals("Temporary process boundary canary",temporary.body);
        assertEquals("home_following",temporary.source);assertEquals(SnapshotPolicy.Quality.PARTIAL,temporary.quality);
        XsTimelineBuffer.Entry saved=timeline.list(survivor,"Durably saved process",null,10,0).get(0);
        assertEquals("The committed Archive result has not yet been acknowledged by the temporary DB",0,saved.savedAt);assertEquals(0,saved.favoritedAt);
        try(XsArchiveDatabase archive=new XsArchiveDatabase(context)){
            try(Cursor c=archive.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM timeline_promotions WHERE account_id=? AND promotion_id=?",new String[]{survivor,saved.promotionId})){assertTrue(c.moveToFirst());assertEquals("Archive commit precedes recovery retry",1,c.getInt(0));}
        }
        // External phases retry after the driver proves the OS process/boot boundary.
        // The default suite uses this identical native fault seam without claiming a kill.
        write(survivor,()->{
            XsTimelinePromotion.Result retry=XsTimelinePromotion.promote(context,timeline,timeline.ticket(survivor),saved.appearanceId,false,System.currentTimeMillis());
            assertTrue(retry.committed);assertTrue(retry.acknowledged);
        });
        assertTrue("Recovery acknowledges the pre-existing Archive commit",timeline.entry(survivor,saved.appearanceId).savedAt>0);
        try(XsArchiveDatabase archive=new XsArchiveDatabase(context)){
            try(Cursor c=archive.getReadableDatabase().rawQuery("SELECT post_id,opened_count FROM posts WHERE account_id=? ORDER BY post_id",new String[]{survivor})){
                assertEquals("Buffer-only cards do not enter the durable library",2,c.getCount());assertTrue(c.moveToFirst());
                assertEquals("991231",c.getString(0));assertEquals(1,c.getInt(1));assertTrue(c.moveToNext());assertEquals("991233",c.getString(0));assertEquals(0,c.getInt(1));
            }
            try(Cursor c=archive.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM timeline_promotions WHERE account_id=?",new String[]{survivor})){assertTrue(c.moveToFirst());assertEquals(1,c.getInt(0));}
            try(Cursor c=archive.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM user_actions WHERE account_id=? AND post_id='991233' AND action_type='LOCAL_SAVE'",new String[]{survivor})){assertTrue(c.moveToFirst());assertEquals("Recovery must not duplicate the explicit save fact",1,c.getInt(0));}
            try(Cursor c=archive.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM favorites WHERE account_id=? AND post_id='991233'",new String[]{survivor})){assertTrue(c.moveToFirst());assertEquals("Saving does not invent a favorite",0,c.getInt(0));}
        }
        try(Cursor c=timeline.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM timeline_appearances WHERE library_id=?",new String[]{deleted})){assertTrue(c.moveToFirst());assertEquals(0,c.getInt(0));}
        android.os.Bundle timelineStatus=new android.os.Bundle();timelineStatus.putString("xs_timeline_buffer_entries","2");timelineStatus.putString("xs_timeline_saved_promotions","1");timelineStatus.putString("xs_timeline_commit_ack_gap","verified");InstrumentationRegistry.getInstrumentation().sendStatus(0,timelineStatus);
        assertEquals(prefs.getString("default",null),XsLocalSpaces.defaultLibrary(context));
        XsMediaRepository.Asset task=media.find(survivor,id);assertNotNull(task);assertEquals("PAUSED",task.state);
        if(separateProcess){assertEquals(0,task.bytes);assertFalse(new File(new File(context.getFilesDir(),"xs-saved-media"),prefs.getString("part","")).exists());}
        int completed=0;for(XsMediaRepository.Asset asset:media.list(survivor))if("COMPLETED".equals(asset.state)){completed++;assertEquals(prefs.getString("sha256",null),hash(media.completedFile(survivor,asset.id)));}assertEquals(1,completed);
        try(Cursor c=media.getReadableDatabase().rawQuery("SELECT url_cipher,run_token FROM assets WHERE id=?",new String[]{id})){assertTrue(c.moveToFirst());assertNotNull(c.getString(0));assertEquals("https://pbs.twimg.com/media/ProcessFixture?format=png&name=orig",XsDownloadSecrets.open(c.getString(0),id+"|"+survivor+"|"+POST+"|IMAGE"));if(separateProcess)assertTrue(c.isNull(1));}
        assertEquals(prefs.getString("sha256",null),hash(media.leaseFile(media.resolveLease(prefs.getString("lease_token",null),prefs.getString("lease_filename",null)))));
    }
    private void lowSpace()throws Exception {
        Context context=app();SharedPreferences prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        String survivor=prefs.getString("survivor",null);assertNotNull(survivor);
        long available=context.getFilesDir().getUsableSpace();
        assertTrue("Low-space admission must run with the ordinary application UID",Process.myUid()>=10000);
        assertTrue("Driver must create real low-space conditions",available>0&&available<2*dev.forcemind.xs.core.MediaDownloadPolicy.MAX_BYTES);
        XsMediaRepository media=XsMediaRepository.get(context);int before=media.list(survivor).size();
        try{media.enqueueImage(survivor,POST,"https://pbs.twimg.com/media/LowSpaceFixture?format=png&name=orig");fail("Low-space request should be rejected before a task or network is created");}
        catch(IllegalStateException expected){assertEquals("Insufficient free space",expected.getMessage());}
        assertEquals(before,media.list(survivor).size());int completed=0;
        for(XsMediaRepository.Asset asset:media.list(survivor))if("COMPLETED".equals(asset.state)){completed++;assertEquals(prefs.getString("sha256",null),hash(media.completedFile(survivor,asset.id)));}assertEquals(1,completed);
        long opens,feeds;
        try(XsArchiveDatabase archive=new XsArchiveDatabase(context)){ArchiveSearch.Filter filter=new ArchiveSearch.Filter();filter.keyword="Process boundary";filter.favoriteOnly=true;try(Cursor c=archive.search(survivor,filter)){assertEquals(1,c.getCount());}
            opens=archive.historyStats(survivor).openEventCount;feeds=archive.historyStats(survivor).feedEventCount;}
        bufferUnderFilesystemPressure(context,survivor);
        // Recheck the actual registered download and Archive after native pressure,
        // independently of the temporary DB's permitted oldest-card eviction.
        for(XsMediaRepository.Asset asset:media.list(survivor))if("COMPLETED".equals(asset.state))assertEquals(prefs.getString("sha256",null),hash(media.completedFile(survivor,asset.id)));
        try(XsArchiveDatabase archive=new XsArchiveDatabase(context)){ArchiveSearch.Filter filter=new ArchiveSearch.Filter();filter.keyword="Process boundary";filter.favoriteOnly=true;try(Cursor c=archive.search(survivor,filter)){assertEquals(1,c.getCount());}
            assertEquals(opens,archive.historyStats(survivor).openEventCount);assertEquals(feeds,archive.historyStats(survivor).feedEventCount);
            filter=new ArchiveSearch.Filter();filter.keyword="Durably saved process buffer";try(Cursor c=archive.search(survivor,filter)){assertEquals(1,c.getCount());}
            try(Cursor c=archive.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM timeline_promotions WHERE account_id=?",new String[]{survivor})){assertTrue(c.moveToFirst());assertEquals(1,c.getInt(0));}}
        android.os.Bundle status=new android.os.Bundle();status.putString("xs_available_bytes",Long.toString(available));status.putString("xs_low_space_app_uid",Integer.toString(Process.myUid()));InstrumentationRegistry.getInstrumentation().sendStatus(0,status);
    }
    /** The guarded host leaves ~28MiB. Only this already-running ordinary UID
     * briefly consumes the final bounded tail; no new process starts below1MiB. */
    private void bufferUnderFilesystemPressure(Context context,String library)throws Exception {
        assertEquals("Guarded CI driver capability required","bounded-native-buffer-v1",InstrumentationRegistry.getArguments().getString("xs_pressure_fixture"));
        assertTrue("Never allocate pressure on a phone: "+android.os.Build.HARDWARE,"ranchu".equals(android.os.Build.HARDWARE)||"goldfish".equals(android.os.Build.HARDWARE));
        assertTrue(Process.myUid()>=10000);
        XsTimelineBuffer timeline=XsTimelineBuffer.get(context);XsTimelineBuffer.Ticket ticket=timeline.ticket(library);
        long previousCards=timeline.stats(library).count;
        XsLibraryWrites.Ticket mediaTicket=XsLibraryWrites.ticket(library);
        long baseline=context.getFilesDir().getUsableSpace();assertTrue(baseline>2*1048576L&&baseline<40*1048576L);
        File pressure=File.createTempFile("xs-buffer-pressure-",".fixture",context.getFilesDir());
        long before=0,after=0,written=0;java.util.concurrent.atomic.AtomicBoolean accepted=new java.util.concurrent.atomic.AtomicBoolean(true);
        try{
            byte[] block=new byte[65536];java.util.Random random=new java.util.Random(0x585316L);
            try(FileOutputStream out=new FileOutputStream(pressure)){
                while(context.getFilesDir().getUsableSpace()>768*1024L&&written<40*1048576L){
                    long remaining=context.getFilesDir().getUsableSpace()-768*1024L;
                    if(remaining<=0)break;
                    int bytes=(int)Math.min(block.length,Math.min(remaining,40*1048576L-written));
                    random.nextBytes(block);out.write(block,0,bytes);written+=bytes;
                    // Avoid treating delayed allocation as actual available space.
                    out.getFD().sync();
                }
            }
            assertEquals(written,pressure.length());
            before=context.getFilesDir().getUsableSpace();assertTrue("Actual native filesystem floor required: available="+before+", written="+written,before>=128*1024L&&before<1048576L);
            write(library,()->accepted.set(timeline.append(ticket,new XsTimelineBuffer.Batch("disk-pressure:refused","native_navigation","unknown"),
                new XsTimelineBuffer.Candidate("https://x.com/i/web/status/991234","fixture","Filesystem pressure refused body",null,SnapshotPolicy.Quality.PARTIAL,System.currentTimeMillis(),1))));
            after=context.getFilesDir().getUsableSpace();assertTrue("Capture remained inside the measured low-space window: "+after,after>0&&after<1048576L);
            assertFalse("No temporary admission below the native headroom floor",accepted.get());
            try(Cursor reason=timeline.getReadableDatabase().rawQuery("SELECT pause_reason FROM timeline_state WHERE id=1",null)){assertTrue(reason.moveToFirst());assertEquals("DEVICE_SPACE",reason.getString(0));}
        }finally{
            assertTrue("Remove only this generated native pressure file",pressure.delete());assertFalse(pressure.exists());
        }
        long recovered=context.getFilesDir().getUsableSpace();assertTrue("Native pressure release restored usable headroom",recovered>2*1048576L);
        assertEquals("Low device headroom must not evict existing temporary cards",previousCards,timeline.stats(library).count);
        assertTrue(XsLibraryWrites.isCurrent(mediaTicket));assertTrue(timeline.list(library,"Filesystem pressure refused",null,10,0).isEmpty());
        write(library,()->assertTrue(timeline.append(timeline.ticket(library),new XsTimelineBuffer.Batch("disk-pressure:recovered","native_navigation","unknown"),
            new XsTimelineBuffer.Candidate("https://x.com/i/web/status/991234","fixture","Filesystem pressure recovered body",null,SnapshotPolicy.Quality.PARTIAL,System.currentTimeMillis(),1))));
        assertEquals(1,timeline.list(library,"Filesystem pressure recovered",null,10,0).size());assertTrue(timeline.stats(library).pauseReason.isEmpty());
        android.os.Bundle status=new android.os.Bundle();
        status.putString("xs_buffer_available_before",Long.toString(before));status.putString("xs_buffer_available_after",Long.toString(after));
        status.putString("xs_buffer_pressure_bytes",Long.toString(written));status.putString("xs_buffer_recovered_bytes",Long.toString(recovered));
        status.putString("xs_buffer_rejected","1");status.putString("xs_buffer_retry_accepted","1");status.putString("xs_buffer_file_removed","1");
        InstrumentationRegistry.getInstrumentation().sendStatus(0,status);
    }
    private void cleanup()throws Exception {
        Context context=app();SharedPreferences prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);XsMediaRepository media=XsMediaRepository.get(context);
        for(String key:new String[]{"survivor","deleted"}){String scope=prefs.getString(key,null);if(scope==null)continue;CountDownLatch done=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();
            Runnable erase=()->{try(XsArchiveDatabase archive=new XsArchiveDatabase(context)){media.eraseLibrary(scope);XsTimelineBuffer.get(context).eraseLibrary(scope);archive.deleteLibraryScope(scope);}catch(Throwable problem){failure.set(problem);}finally{done.countDown();}};
            assertTrue(XsLibraryWrites.isDeleted(scope)?XsLibraryWrites.submitDeletion(scope,erase):XsLibraryWrites.invalidateAndSubmit(scope,erase));assertTrue(done.await(15,TimeUnit.SECONDS));if(failure.get()!=null)throw new AssertionError(failure.get());}
        if(prefs.contains("lease_token"))media.discardLease(media.resolveLease(prefs.getString("lease_token",null),prefs.getString("lease_filename",null)));
        if(prefs.contains("timeline_previous_hours"))XsTimelineBuffer.get(context).configure(prefs.getBoolean("timeline_previous_enabled",false),prefs.getInt("timeline_previous_hours",48),prefs.getInt("timeline_previous_budget",100));
        assertTrue(prefs.edit().clear().commit());
    }
    private interface Operation{void run()throws Exception;}
    private static void write(String scope,Operation operation)throws Exception {CountDownLatch done=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();assertTrue(XsLibraryWrites.submit(scope,()->{try{operation.run();}catch(Throwable problem){failure.set(problem);}finally{done.countDown();}}));assertTrue(done.await(15,TimeUnit.SECONDS));if(failure.get()!=null)throw new AssertionError(failure.get());}
    private static String hash(File file)throws Exception {byte[] digest=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()));StringBuilder hex=new StringBuilder();for(byte value:digest)hex.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return hex.toString();}
}

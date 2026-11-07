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
    private Context app(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}
    @Test public void nativeStateAndRecoveryAcrossProcessBoundary()throws Exception {
        String phase=InstrumentationRegistry.getArguments().getString("xs_phase","");
        if("seed".equals(phase)){seed();return;}
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
        assertTrue("Driver must create real low-space conditions",available>0&&available<2*dev.forcemind.xs.core.MediaDownloadPolicy.MAX_BYTES);
        XsMediaRepository media=XsMediaRepository.get(context);int before=media.list(survivor).size();
        try{media.enqueueImage(survivor,POST,"https://pbs.twimg.com/media/LowSpaceFixture?format=png&name=orig");fail("Low-space request should be rejected before a task or network is created");}
        catch(IllegalStateException expected){assertEquals("Insufficient free space",expected.getMessage());}
        assertEquals(before,media.list(survivor).size());int completed=0;
        for(XsMediaRepository.Asset asset:media.list(survivor))if("COMPLETED".equals(asset.state)){completed++;assertEquals(prefs.getString("sha256",null),hash(media.completedFile(survivor,asset.id)));}assertEquals(1,completed);
        try(XsArchiveDatabase archive=new XsArchiveDatabase(context)){ArchiveSearch.Filter filter=new ArchiveSearch.Filter();filter.keyword="Process boundary";filter.favoriteOnly=true;try(Cursor c=archive.search(survivor,filter)){assertEquals(1,c.getCount());}}
        android.os.Bundle status=new android.os.Bundle();status.putString("xs_available_bytes",Long.toString(available));InstrumentationRegistry.getInstrumentation().sendStatus(0,status);
    }
    private void cleanup()throws Exception {
        Context context=app();SharedPreferences prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);XsMediaRepository media=XsMediaRepository.get(context);
        for(String key:new String[]{"survivor","deleted"}){String scope=prefs.getString(key,null);if(scope==null)continue;CountDownLatch done=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();
            Runnable erase=()->{try(XsArchiveDatabase archive=new XsArchiveDatabase(context)){media.eraseLibrary(scope);archive.deleteLibraryScope(scope);}catch(Throwable problem){failure.set(problem);}finally{done.countDown();}};
            assertTrue(XsLibraryWrites.isDeleted(scope)?XsLibraryWrites.submitDeletion(scope,erase):XsLibraryWrites.invalidateAndSubmit(scope,erase));assertTrue(done.await(15,TimeUnit.SECONDS));if(failure.get()!=null)throw new AssertionError(failure.get());}
        if(prefs.contains("lease_token"))media.discardLease(media.resolveLease(prefs.getString("lease_token",null),prefs.getString("lease_filename",null)));
        assertTrue(prefs.edit().clear().commit());
    }
    private interface Operation{void run()throws Exception;}
    private static void write(String scope,Operation operation)throws Exception {CountDownLatch done=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();assertTrue(XsLibraryWrites.submit(scope,()->{try{operation.run();}catch(Throwable problem){failure.set(problem);}finally{done.countDown();}}));assertTrue(done.await(15,TimeUnit.SECONDS));if(failure.get()!=null)throw new AssertionError(failure.get());}
    private static String hash(File file)throws Exception {byte[] digest=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()));StringBuilder hex=new StringBuilder();for(byte value:digest)hex.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return hex.toString();}
}

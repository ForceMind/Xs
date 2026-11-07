package dev.forcemind.xs.android;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.os.Build;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real AES-GCM/Keystore, SQLite migration and engine; a deterministic scheduler avoids network/OS timing claims. */
@RunWith(AndroidJUnit4.class)
public final class MediaDurabilityTest {
    private Context context;
    private String library,dbName,suffix;
    private XsMediaRepository repository;
    private final CapturingScheduler scheduler=new CapturingScheduler();
    private final AtomicInteger requests=new AtomicInteger();
    @Before public void setup(){context=ApplicationProvider.getApplicationContext();library="durable:"+UUID.randomUUID();String unique=UUID.randomUUID().toString();dbName="xs-media-test-"+unique+".db";suffix="-"+unique;}
    private byte[] png()throws Exception{Bitmap image=Bitmap.createBitmap(4,3,Bitmap.Config.ARGB_8888);image.eraseColor(0xff30ab45);ByteArrayOutputStream out=new ByteArrayOutputStream();assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));image.recycle();return out.toByteArray();}
    private XsMediaRepository open(byte[] bytes){return new XsMediaRepository(context,url->{requests.incrementAndGet();return new ImageResponse(url,bytes);},scheduler,dbName,suffix);}
    private void drain()throws Exception{CountDownLatch done=new CountDownLatch(1);assertTrue(XsLibraryWrites.submit(library,done::countDown));assertTrue(done.await(10,TimeUnit.SECONDS));}
    private void idle()throws Exception{CountDownLatch done=new CountDownLatch(1);repository.workerMarkerForTests(done::countDown);assertTrue(done.await(10,TimeUnit.SECONDS));drain();}
    private XsMediaRepository.Asset await(String id,String expected)throws Exception{long deadline=android.os.SystemClock.elapsedRealtime()+10000;do{XsMediaRepository.Asset a=repository.find(library,id);if(a!=null&&a.state.equals(expected)){idle();return a;}Thread.sleep(20);}while(android.os.SystemClock.elapsedRealtime()<deadline);fail("Expected transfer state "+expected);return null;}
    @After public void cleanup()throws Exception{if(repository!=null){repository.stopWorkerForTests();drain();repository.eraseLibrary(library);repository.close();}context.deleteDatabase(dbName);remove(new File(context.getFilesDir(),"xs-saved-media"+suffix));remove(new File(context.getFilesDir(),"xs-share-leases"+suffix));}
    private void remove(File path){File[] children=path.listFiles();if(children!=null)for(File file:children)file.delete();path.delete();}

    @Test public void queuedUrlIsEncryptedBoundToTaskAndResumesAfterRepositoryRestart()throws Exception {
        byte[] bytes=png();repository=open(bytes);
        String url="https://pbs.twimg.com/media/CipherEvidenceUniqueNoPlaintext?format=png&name=orig";
        String id=repository.enqueueImage(library,"https://x.com/u/status/123",url);drain();assertEquals(0,requests.get());assertEquals("QUEUED",repository.find(library,id).state);
        String cipher;
        try(Cursor c=repository.getReadableDatabase().rawQuery("SELECT url_cipher,media_url FROM assets WHERE id=?",new String[]{id})){assertTrue(c.moveToFirst());cipher=c.getString(0);assertNotNull(cipher);assertFalse(cipher.contains("CipherEvidenceUniqueNoPlaintext"));assertTrue(c.isNull(1));}
        assertFalse(new String(java.nio.file.Files.readAllBytes(context.getDatabasePath(dbName).toPath()),java.nio.charset.StandardCharsets.ISO_8859_1).contains("CipherEvidenceUniqueNoPlaintext"));
        assertEquals(url,XsDownloadSecrets.open(cipher,id+"|"+library+"|https://x.com/i/web/status/123|IMAGE"));
        try{XsDownloadSecrets.open(cipher,"other-task");fail("Ciphertext replayed in another scope");}catch(java.security.GeneralSecurityException expected){}
        repository.stopWorkerForTests();repository.close();repository=open(bytes);assertEquals("PAUSED",repository.find(library,id).state);
        CountDownLatch completed=new CountDownLatch(1);assertTrue(repository.runScheduled(library,id,completed::countDown));assertTrue(completed.await(10,TimeUnit.SECONDS));
        assertEquals("COMPLETED",await(id,"COMPLETED").state);assertArrayEquals(bytes,java.nio.file.Files.readAllBytes(repository.completedFile(library,id).toPath()));
        try(Cursor c=repository.getReadableDatabase().rawQuery("SELECT url_cipher,attempt_count FROM assets WHERE id=?",new String[]{id})){c.moveToFirst();assertTrue(c.isNull(0));assertEquals(1,c.getInt(1));}
        assertEquals(1,requests.get());assertNoParts();
    }
    @Test public void cancelClearsSecretAndOrphanCallbackCannotStartNetwork()throws Exception {
        repository=open(png());String id=repository.enqueueImage(library,"https://x.com/u/status/123","https://pbs.twimg.com/media/CancelDurable?format=png&name=orig");drain();
        repository.cancel(library,id);drain();assertEquals("CANCELED",repository.find(library,id).state);assertTrue(scheduler.canceled>0);
        CountDownLatch done=new CountDownLatch(1);assertTrue(repository.runScheduled(library,id,done::countDown));assertTrue(done.await(10,TimeUnit.SECONDS));assertEquals(0,requests.get());
        try(Cursor c=repository.getReadableDatabase().rawQuery("SELECT url_cipher FROM assets WHERE id=?",new String[]{id})){c.moveToFirst();assertTrue(c.isNull(0));}
    }
    @Test public void erasureBarrierCancelsPersistedRequestAndLateJobStaysInert()throws Exception {
        repository=open(png());String id=repository.enqueueImage(library,"https://x.com/u/status/123","https://pbs.twimg.com/media/EraseDurable?format=png&name=orig");drain();
        CountDownLatch erased=new CountDownLatch(1);assertTrue(XsLibraryWrites.invalidateAndSubmit(library,()->{repository.eraseLibrary(library);erased.countDown();}));assertTrue(erased.await(10,TimeUnit.SECONDS));
        assertNull(repository.find(library,id));assertTrue(scheduler.canceled>0);
        CountDownLatch finished=new CountDownLatch(1);assertTrue(repository.runScheduled(library,id,finished::countDown));assertTrue(finished.await(10,TimeUnit.SECONDS));assertEquals(0,requests.get());assertNoParts();
    }
    @Test public void rejectedSchedulerAndTamperedCipherBecomeExplicitFailures()throws Exception {
        scheduler.accept=false;repository=open(png());String first=repository.enqueueImage(library,"https://x.com/u/status/123","https://pbs.twimg.com/media/Rejected?format=png&name=orig");await(first,"FAILED");assertEquals(0,requests.get());
        scheduler.accept=true;String second=repository.enqueueImage(library,"https://x.com/u/status/124","https://pbs.twimg.com/media/Tampered?format=png&name=orig");drain();repository.getWritableDatabase().execSQL("UPDATE assets SET url_cipher='invalid' WHERE id=?",new Object[]{second});
        CountDownLatch done=new CountDownLatch(1);assertTrue(repository.runScheduled(library,second,done::countDown));assertTrue(done.await(10,TimeUnit.SECONDS));await(second,"FAILED");assertEquals(0,requests.get());
    }
    @Test public void networkRetriesAreBoundedAndRestartAtByteZero()throws Exception {
        repository=new XsMediaRepository(context,url->{requests.incrementAndGet();throw new SocketTimeoutException("synthetic network interruption");},scheduler,dbName,suffix);
        String id=repository.enqueueImage(library,"https://x.com/u/status/123","https://pbs.twimg.com/media/RetryDurable?format=png&name=orig");drain();
        for(int n=1;n<=3;n++){CountDownLatch done=new CountDownLatch(1);assertTrue(repository.runScheduled(library,id,done::countDown));assertTrue(done.await(10,TimeUnit.SECONDS));await(id,n<3?"RETRY_WAIT":"FAILED");}
        assertEquals(3,requests.get());assertEquals(0,repository.find(library,id).bytes);assertNoParts();
    }
    @Test public void systemStopPreservesEncryptedReferenceAndOldRunCannotComplete()throws Exception {
        byte[] bytes=png();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        repository=new XsMediaRepository(context,url->new ImageResponse(url,bytes){@Override public InputStream getInputStream(){return new ByteArrayInputStream(bytes){boolean waited;@Override public synchronized int read(byte[] b,int o,int l){if(!waited){waited=true;entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException stopped){Thread.currentThread().interrupt();return -1;}}return super.read(b,o,l);}};}},scheduler,dbName,suffix);
        String id=repository.enqueueImage(library,"https://x.com/u/status/123","https://pbs.twimg.com/media/StopDurable?format=png&name=orig");drain();CountDownLatch finished=new CountDownLatch(1);assertTrue(repository.runScheduled(library,id,finished::countDown));assertTrue(entered.await(10,TimeUnit.SECONDS));repository.pauseScheduled(library,id,3);drain();release.countDown();assertTrue(finished.await(10,TimeUnit.SECONDS));idle();assertEquals("PAUSED",repository.find(library,id).state);assertNoParts();
        try(Cursor c=repository.getReadableDatabase().rawQuery("SELECT url_cipher,last_stop_reason FROM assets WHERE id=?",new String[]{id})){c.moveToFirst();assertNotNull(c.getString(0));assertEquals(3,c.getInt(1));}
    }
    @Test public void staleDeletedJobScopeIsRejectedWithoutStatusReentry()throws Exception {
        String deleted=library+":deleted",task=UUID.randomUUID().toString();CountDownLatch erased=new CountDownLatch(1);
        assertTrue(XsDownloadJobService.accepts(deleted,task));
        assertFalse(XsDownloadJobService.accepts(" ",task));assertFalse(XsDownloadJobService.accepts(deleted,"invalid-task"));
        assertTrue(XsLibraryWrites.deleteAndSubmit(deleted,erased::countDown));assertTrue(erased.await(10,TimeUnit.SECONDS));
        assertFalse(XsDownloadJobService.accepts(deleted,task));
        repository=open(png());repository.schedulingRejected(deleted,task,"Stale callback");assertFalse(repository.shouldRetry(deleted,task));assertEquals(0,requests.get());
    }
    @Test public void stopPolicyRespectsUserIntentAndLegacyLibraryLength()throws Exception {
        assertFalse(XsDownloadJobService.shouldRescheduleAfterStop(31,JobParameters.STOP_REASON_USER));
        assertFalse(XsDownloadJobService.shouldRescheduleAfterStop(36,JobParameters.STOP_REASON_CANCELLED_BY_APP));
        for(int reason:new int[]{JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY,JobParameters.STOP_REASON_QUOTA,JobParameters.STOP_REASON_DEVICE_STATE})assertTrue(XsDownloadJobService.shouldRescheduleAfterStop(31,reason));
        assertTrue(XsDownloadJobService.shouldRescheduleAfterStop(26,0));
        StringBuilder scope=new StringBuilder();for(int i=0;i<256;i++)scope.append('界');String legacy=scope.toString(),task=UUID.randomUUID().toString();
        assertTrue(XsDownloadJobService.accepts(legacy,task));assertFalse(XsDownloadJobService.accepts(legacy+"x",task));
        repository=open(png());assertTrue(repository.list(legacy).isEmpty());
    }
    @Test public void persistedJobContainsOnlyOpaqueNativeIdsAndProtectedService()throws Exception {
        JobInfo regular=XsDownloadScheduler.request(context,"task-id",library,19731,1024,false);assertTrue(regular.isPersisted());assertEquals(JobInfo.NETWORK_TYPE_ANY,regular.getNetworkType());assertEquals(2,regular.getExtras().size());assertEquals("task-id",regular.getExtras().getString("task"));
        if(Build.VERSION.SDK_INT>=34)assertTrue(XsDownloadScheduler.request(context,"task-id",library,19732,1024,true).isUserInitiated());
        ServiceInfo service=context.getPackageManager().getServiceInfo(new ComponentName(context,XsDownloadJobService.class),0);assertFalse(service.exported);assertEquals("android.permission.BIND_JOB_SERVICE",service.permission);
    }
    @Test public void versionOneAndTwoMigrationPreservesAssetsAndLeaseRows()throws Exception {
        for(int version:new int[]{1,2}) {
            SQLiteDatabase legacy=context.openOrCreateDatabase(dbName,0,null);
            legacy.execSQL("CREATE TABLE assets(id TEXT PRIMARY KEY,library_id TEXT NOT NULL,post_url TEXT NOT NULL,media_url TEXT,state TEXT NOT NULL,bytes INTEGER NOT NULL DEFAULT 0,total INTEGER NOT NULL DEFAULT -1,mime TEXT,filename TEXT,sha256 TEXT,created_ms INTEGER NOT NULL,error TEXT,category TEXT NOT NULL DEFAULT '')");
            legacy.execSQL("CREATE TABLE leases(token TEXT PRIMARY KEY,filename TEXT NOT NULL,mime TEXT NOT NULL,bytes INTEGER NOT NULL,expires_ms INTEGER NOT NULL)");
            if(version==2)for(String declaration:new String[]{"media_kind TEXT NOT NULL DEFAULT 'IMAGE'","duration_ms INTEGER","width INTEGER","height INTEGER","video_tracks INTEGER","audio_tracks INTEGER","rotation_degrees INTEGER"})legacy.execSQL("ALTER TABLE assets ADD COLUMN "+declaration);
            legacy.execSQL("INSERT INTO assets(id,library_id,post_url,state,created_ms) VALUES ('legacy',?,'https://x.com/i/web/status/123','COMPLETED',123)",new Object[]{library});
            legacy.execSQL("INSERT INTO leases VALUES ('old-copy','old.png','image/png',4,9999999999999)");legacy.setVersion(version);legacy.close();
            repository=open(png());assertEquals(3,repository.getReadableDatabase().getVersion());assertEquals(123,repository.find(library,"legacy").createdMs);
            try(Cursor c=repository.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM leases",null)){c.moveToFirst();assertEquals(1,c.getInt(0));}
            repository.stopWorkerForTests();repository.close();repository=null;context.deleteDatabase(dbName);
        }
    }
    private void assertNoParts(){File[] files=new File(context.getFilesDir(),"xs-saved-media"+suffix).listFiles();if(files!=null)for(File file:files)assertFalse(file.getName().endsWith(".part"));}
    private static final class CapturingScheduler implements XsMediaRepository.Scheduler {
        boolean accept=true;int canceled;final Set<Integer> pending=new HashSet<>();
        public boolean schedule(String id,String library,int jobId,long estimate,boolean user){pending.add(jobId);return accept;}
        public void cancel(int id){pending.remove(id);canceled++;}
        public boolean isPending(int id){return pending.contains(id);}
    }
    private static class ImageResponse extends HttpURLConnection {
        final byte[] bytes;ImageResponse(URL url,byte[] bytes){super(url);this.bytes=bytes;}
        public int getResponseCode(){return 200;}public String getContentType(){return "image/png";}public long getContentLengthLong(){return bytes.length;}
        public InputStream getInputStream(){return new ByteArrayInputStream(bytes);}public void connect(){}public void disconnect(){}public boolean usingProxy(){return false;}
    }
}

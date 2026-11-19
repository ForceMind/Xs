package dev.forcemind.xs.android;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import dev.forcemind.xs.core.MediaDownloadPolicy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real SQLite/files/Keystore with instance-local HTTP and scheduler seams; no OS transfer claims. */
@RunWith(AndroidJUnit4.class)
public final class MediaQuotaIntegrationTest {
    private static final long IMAGE_CAP=MediaDownloadPolicy.MAX_BYTES;
    private static final String POST="https://x.com/i/web/status/123";
    private Context context;private String library,other,dbName,suffix;private byte[] image;
    private XsMediaRepository repository,second;private final Schedule scheduler=new Schedule();
    private final AtomicInteger requests=new AtomicInteger();
    private final List<String> scopes=new ArrayList<>();private File stage;
    @Before public void setup()throws Exception {
        context=ApplicationProvider.getApplicationContext();String unique=UUID.randomUUID().toString();library="quota:"+unique;other="quota-other:"+unique;scopes.add(library);scopes.add(other);
        dbName="xs-media-test-"+unique+".db";suffix="-"+unique;
        Bitmap bitmap=Bitmap.createBitmap(4,3,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff897430);
        try(ByteArrayOutputStream out=new ByteArrayOutputStream()){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));image=out.toByteArray();}finally{bitmap.recycle();}
        stage=File.createTempFile("quota-stage-",".png",context.getCacheDir());try(FileOutputStream out=new FileOutputStream(stage)){out.write(image);}
        repository=open(url->new Response(url,image));
    }
    private XsMediaRepository open(XsMediaRepository.ConnectionFactory factory){return new XsMediaRepository(context,url->{requests.incrementAndGet();return factory.open(url);},scheduler,dbName,suffix);}
    private interface Mutation {void run()throws Exception;}
    private void write(String scope,Mutation mutation)throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();
        assertTrue(XsLibraryWrites.submit(scope,()->{try{mutation.run();}catch(Throwable problem){failure.set(problem);}finally{done.countDown();}}));assertTrue(done.await(20,TimeUnit.SECONDS));
        if(failure.get() instanceof Exception)throw (Exception)failure.get();if(failure.get()!=null)throw new AssertionError(failure.get());
    }
    private void drain(String scope)throws Exception{write(scope,()->{});}
    private void quota(String scope,long bytes)throws Exception{write(scope,()->repository.setQuotaBytes(scope,bytes));}
    private String enqueue(XsMediaRepository engine,String scope,String name){return engine.enqueueImage(scope,POST,"https://pbs.twimg.com/media/Quota"+name+"?format=png&name=orig");}
    private void run(String id)throws Exception{CountDownLatch done=new CountDownLatch(1);assertTrue(repository.runScheduled(library,id,done::countDown));assertTrue(done.await(15,TimeUnit.SECONDS));drain(library);}
    private File savedDir(){return new File(context.getFilesDir(),"xs-saved-media"+suffix);}
    private void noParts(){File[] files=savedDir().listFiles();if(files!=null)for(File file:files)assertFalse(file.getName().endsWith(".part"));}
    @After public void cleanup()throws Exception {
        if(second!=null){second.stopWorkerForTests();second.close();}
        if(repository!=null){repository.stopWorkerForTests();for(String scope:scopes)write(scope,()->{repository.setQuotaBytes(scope,0);repository.eraseLibrary(scope);});repository.close();}
        context.deleteDatabase(dbName);for(File directory:new File[]{savedDir(),new File(context.getFilesDir(),"xs-share-leases"+suffix)}){File[] files=directory.listFiles();if(files!=null)for(File file:files)file.delete();directory.delete();}if(stage!=null)stage.delete();
    }
    @Test public void concurrentRequestsAcrossRepositoryInstancesCannotOversellReservation()throws Exception {
        second=open(url->new Response(url,image));quota(library,IMAGE_CAP);CountDownLatch start=new CountDownLatch(1);List<String> ids=new CopyOnWriteArrayList<>();
        ExecutorService pool=Executors.newFixedThreadPool(4);List<Future<?>> futures=new ArrayList<>();
        try{for(int i=0;i<8;i++){final int n=i;futures.add(pool.submit(()->{try{start.await();ids.add(enqueue(n%2==0?repository:second,library,"Concurrent"+n));}catch(InterruptedException stopped){Thread.currentThread().interrupt();}}));}start.countDown();for(Future<?> result:futures)result.get(10,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        drain(library);int queued=0,failed=0;for(String id:ids){XsMediaRepository.Asset asset=repository.find(library,id);if("QUEUED".equals(asset.state))queued++;else{assertEquals("FAILED",asset.state);assertTrue(asset.error.contains("quota"));assertFalse(asset.error.contains("https://"));failed++;}}
        assertEquals(1,queued);assertEquals(7,failed);assertEquals(IMAGE_CAP,repository.usage(library).reservedBytes);assertEquals(IMAGE_CAP,second.usage(library).reservedBytes);assertEquals(0,requests.get());assertEquals(3,repository.getReadableDatabase().getVersion());
    }
    @Test public void cancellationDeletionAndScopeErasureReleaseOnlyTheirReservations()throws Exception {
        assertEquals(0,repository.usage(library).quotaBytes);quota(library,IMAGE_CAP);
        String first=enqueue(repository,library,"First"),denied=enqueue(repository,library,"Denied"),unlimited=enqueue(repository,other,"Other");drain(library);drain(other);
        assertEquals("FAILED",repository.find(library,denied).state);assertEquals(0,repository.usage(other).quotaBytes);assertEquals(IMAGE_CAP,repository.usage(other).reservedBytes);
        repository.cancel(library,first);drain(library);assertEquals(0,repository.usage(library).reservedBytes);
        String next=enqueue(repository,library,"AfterCancel");drain(library);assertEquals("QUEUED",repository.find(library,next).state);
        repository.delete(library,next);drain(library);assertNull(repository.find(library,next));assertEquals(0,repository.usage(library).reservedBytes);
        enqueue(repository,library,"BeforeClear");drain(library);write(library,()->repository.eraseLibrary(library));assertEquals(0,repository.usage(library).reservedBytes);assertNotNull(repository.find(other,unlimited));
    }
    @Test public void lowQuotaKeepsCompletedBytesAndExplicitRetryRequiresNewCapacity()throws Exception {
        List<String> saved=new ArrayList<>();write(library,()->saved.addAll(repository.restoreCompleted(library,Collections.singletonList(restored()))));File original=repository.completedFile(library,saved.get(0));
        quota(library,1);assertTrue(original.isFile());assertArrayEquals(image,java.nio.file.Files.readAllBytes(original.toPath()));assertEquals(image.length,repository.usage(library).completedBytes);
        String id=enqueue(repository,library,"LowQuota");drain(library);assertEquals("FAILED",repository.find(library,id).state);repository.retryStored(library,id);drain(library);assertEquals("FAILED",repository.find(library,id).state);assertEquals(0,requests.get());
        quota(library,IMAGE_CAP+image.length);repository.retryStored(library,id);drain(library);assertEquals("QUEUED",repository.find(library,id).state);run(id);
        assertEquals("COMPLETED",repository.find(library,id).state);assertEquals(2L*image.length,repository.usage(library).completedBytes);assertEquals(0,repository.usage(library).reservedBytes);assertTrue(original.isFile());noParts();
    }
    @Test public void loweringQuotaDuringReadStopsIncompleteFileAndPreservesCompletedOriginal()throws Exception {
        List<String> saved=new ArrayList<>();write(library,()->saved.addAll(repository.restoreCompleted(library,Collections.singletonList(restored()))));File original=repository.completedFile(library,saved.get(0));
        repository.stopWorkerForTests();repository.close();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        repository=open(url->new Response(url,image){@Override public InputStream getInputStream(){return new ByteArrayInputStream(image){boolean waited;@Override public synchronized int read(byte[] b,int offset,int length){if(!waited){waited=true;entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();return -1;}}return super.read(b,offset,length);}};}@Override public void disconnect(){release.countDown();}});
        quota(library,IMAGE_CAP+image.length);String id=enqueue(repository,library,"DuringRead");drain(library);CountDownLatch done=new CountDownLatch(1);assertTrue(repository.runScheduled(library,id,done::countDown));assertTrue(entered.await(10,TimeUnit.SECONDS));assertEquals(image.length,repository.usage(library).reservedBytes);
        quota(library,image.length);release.countDown();assertTrue(done.await(10,TimeUnit.SECONDS));drain(library);
        assertEquals("FAILED",repository.find(library,id).state);assertTrue(repository.find(library,id).error.contains("quota"));assertEquals(0,repository.usage(library).reservedBytes);assertEquals(image.length,repository.usage(library).completedBytes);assertArrayEquals(image,java.nio.file.Files.readAllBytes(original.toPath()));noParts();
    }
    @Test public void restartRechecksLegacyPendingBudgetBeforeOpeningTransport()throws Exception {
        String id=enqueue(repository,library,"Restart");drain(library);repository.stopWorkerForTests();repository.close();write(library,()->new XsStorageSettings(context).save(library,1));
        repository=open(url->new Response(url,image));assertEquals("PAUSED",repository.find(library,id).state);run(id);
        assertEquals("FAILED",repository.find(library,id).state);assertTrue(repository.find(library,id).error.contains("quota"));assertEquals(0,requests.get());assertEquals(0,repository.usage(library).reservedBytes);noParts();
    }
    @Test public void aggregateRestoreQuotaRejectsAtomicallyAndPreservesPrivateStage()throws Exception {
        quota(library,2L*image.length-1);
        try{write(library,()->repository.restoreCompleted(library,Arrays.asList(restored(),restored())));fail("Aggregate resources exceeded quota");}catch(IOException expected){assertTrue(expected.getMessage().contains("quota"));}
        assertTrue(repository.list(library).isEmpty());assertEquals(0,repository.usage(library).completedBytes);assertArrayEquals(image,java.nio.file.Files.readAllBytes(stage.toPath()));noParts();
        quota(library,2L*image.length);write(library,()->repository.restoreCompleted(library,Arrays.asList(restored(),restored())));assertEquals(2,repository.list(library).size());assertEquals(2L*image.length,repository.usage(library).completedBytes);
        try{write(library,()->repository.restoreCompleted(library,Collections.singletonList(restored())));fail("Existing completed resources were overwritten");}catch(IOException expected){}
        assertEquals(2,repository.list(library).size());for(XsMediaRepository.Asset asset:repository.list(library))assertTrue(repository.completedFile(library,asset.id).isFile());
    }
    @Test public void oldVersionMigrationUnderLowQuotaRetainsCompletedFileAndSchema()throws Exception {
        repository.stopWorkerForTests();repository.close();repository=null;assertTrue(context.deleteDatabase(dbName));String id=UUID.randomUUID().toString(),filename=id+".png";
        try(FileOutputStream out=new FileOutputStream(new File(savedDir(),filename))){out.write(image);}
        try(SQLiteDatabase old=context.openOrCreateDatabase(dbName,0,null)) {
            old.execSQL("CREATE TABLE assets(id TEXT PRIMARY KEY,library_id TEXT NOT NULL,post_url TEXT NOT NULL,media_url TEXT,state TEXT NOT NULL,bytes INTEGER NOT NULL DEFAULT 0,total INTEGER NOT NULL DEFAULT -1,mime TEXT,filename TEXT,sha256 TEXT,created_ms INTEGER NOT NULL,error TEXT,category TEXT NOT NULL DEFAULT '')");old.execSQL("CREATE TABLE leases(token TEXT PRIMARY KEY,filename TEXT NOT NULL,mime TEXT NOT NULL,bytes INTEGER NOT NULL,expires_ms INTEGER NOT NULL)");
            ContentValues row=new ContentValues();row.put("id",id);row.put("library_id",library);row.put("post_url",POST);row.put("state","COMPLETED");row.put("mime","image/png");row.put("filename",filename);row.put("bytes",image.length);row.put("created_ms",123);old.insertOrThrow("assets",null,row);old.setVersion(1);
        }
        write(library,()->new XsStorageSettings(context).save(library,1));repository=open(url->new Response(url,image));assertEquals(3,repository.getReadableDatabase().getVersion());assertEquals(123,repository.find(library,id).createdMs);assertArrayEquals(image,java.nio.file.Files.readAllBytes(repository.completedFile(library,id).toPath()));
        String denied=enqueue(repository,library,"MigratedQuota");drain(library);assertEquals("FAILED",repository.find(library,denied).state);assertEquals(image.length,repository.usage(library).completedBytes);assertTrue(repository.completedFile(library,id).isFile());
    }
    private XsMediaRepository.RestoredAsset restored()throws Exception {return new XsMediaRepository.RestoredAsset(POST,"IMAGE","image/png",image.length,hex(MessageDigest.getInstance("SHA-256").digest(image)),"",123,0,0,0,0,0,0,stage);}
    private static String hex(byte[] bytes){StringBuilder value=new StringBuilder();for(byte b:bytes)value.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return value.toString();}
    private static final class Schedule implements XsMediaRepository.Scheduler {
        public boolean schedule(String id,String library,int job,long estimate,boolean user){return true;}public void cancel(int job){}
    }
    private static class Response extends HttpURLConnection {
        final byte[] bytes;Response(URL url,byte[] bytes){super(url);this.bytes=bytes;}public int getResponseCode(){return 200;}public long getContentLengthLong(){return bytes.length;}public String getContentType(){return "image/png";}public InputStream getInputStream(){return new ByteArrayInputStream(bytes);}public void connect(){}public void disconnect(){}public boolean usingProxy(){return false;}
    }
}

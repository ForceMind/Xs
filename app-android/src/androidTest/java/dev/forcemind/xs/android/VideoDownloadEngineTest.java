package dev.forcemind.xs.android;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
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

/** Real codec/filesystem/Provider fixture loop. All HTTP bytes are generated locally. */
@RunWith(AndroidJUnit4.class)
public final class VideoDownloadEngineTest {
    private static final String LIBRARY="video-engine-fixture";
    private static final String POST="https://x.com/fixture/status/1234";
    private Context context,legacyContext;private XsMediaRepository engine;
    @Before public void setup()throws Exception {
        context=ApplicationProvider.getApplicationContext();
        XsMediaRepository repository=XsMediaRepository.get(context);
        CountDownLatch cleared=new CountDownLatch(1);
        assertTrue(XsLibraryWrites.invalidateAndSubmit(LIBRARY,()->{repository.eraseLibrary(LIBRARY);cleared.countDown();}));
        assertTrue(cleared.await(10,TimeUnit.SECONDS));
    }
    @After public void cleanup()throws Exception {
        if(engine!=null){engine.stopWorkerForTests();drain();engine.eraseLibrary(LIBRARY);engine.close();}
        if(legacyContext!=null)legacyContext.deleteDatabase("xs-media.db");
    }
    private void drain()throws Exception {CountDownLatch done=new CountDownLatch(1);assertTrue(XsLibraryWrites.submit(LIBRARY,done::countDown));assertTrue(done.await(10,TimeUnit.SECONDS));}
    private byte[] fixture(String name)throws Exception {
        try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name+"-video.mp4.b64");ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] bytes=new byte[4096];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);return Base64.decode(out.toByteArray(),Base64.DEFAULT);
        }
    }
    private static String url(String token){return "https://video.twimg.com/ext_tw_video/1234/pu/vid/64x64/"+token+".mp4?tag=12";}
    private XsMediaRepository.Asset await(String id,String state)throws Exception {
        long end=android.os.SystemClock.elapsedRealtime()+20000;
        do {
            XsMediaRepository.Asset result=engine.find(LIBRARY,id);
            if(result!=null&&state.equals(result.state)) {
                CountDownLatch idle=new CountDownLatch(1);engine.workerMarkerForTests(idle::countDown);assertTrue(idle.await(10,TimeUnit.SECONDS));drain();return result;
            }
            Thread.sleep(20);
        }while(android.os.SystemClock.elapsedRealtime()<end);
        XsMediaRepository.Asset row=engine.find(LIBRARY,id);fail("Expected "+state+", actual "+(row==null?"missing":row.state));return null;
    }
    private File part(String id){File folder=new File(context.getFilesDir(),"xs-saved-media");File[] candidates=folder.listFiles();if(candidates!=null)for(File file:candidates)if(file.getName().startsWith(id+".")&&file.getName().endsWith(".part"))return file;return new File(folder,id+".part");}
    @Test public void fullMp4WithAudioCompletesPlaysAndSharesOriginalFile()throws Exception {
        byte[] movie=fixture("standard");AtomicInteger requests=new AtomicInteger();
        engine=new XsMediaRepository(context,address->{requests.incrementAndGet();return new Response(address,movie,movie.length);});
        String id=engine.enqueueVideo(LIBRARY,POST,url("complete"));XsMediaRepository.Asset video=await(id,"COMPLETED");
        assertEquals(1,requests.get());assertEquals("VIDEO",video.mediaKind);assertEquals("video/mp4",video.mime);
        assertEquals(1,video.videoTracks);assertEquals(1,video.audioTracks);assertEquals(64,video.width);assertEquals(64,video.height);assertEquals(0,video.rotationDegrees);
        assertTrue(video.durationMs>=900&&video.durationMs<=1300);assertEquals(movie.length,video.bytes);assertFalse(part(id).exists());
        File saved=engine.completedFile(LIBRARY,id);assertArrayEquals(movie,java.nio.file.Files.readAllBytes(saved.toPath()));
        try(Cursor c=engine.getReadableDatabase().rawQuery("SELECT sha256,media_url FROM assets WHERE id=?",new String[]{id})){
            assertTrue(c.moveToFirst());assertEquals(hex(MessageDigest.getInstance("SHA-256").digest(movie)),c.getString(0));assertTrue(c.isNull(1));
        }
        playToCompletion(saved);
        Intent send=XsShareCoordinator.assetIntent(context,LIBRARY,Arrays.asList(id));assertEquals(Intent.ACTION_SEND,send.getAction());assertEquals("video/mp4",send.getType());
        assertEquals(0,send.getFlags()&Intent.FLAG_GRANT_WRITE_URI_PERMISSION);assertTrue((send.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0);
        Uri uri=send.getParcelableExtra(Intent.EXTRA_STREAM);assertEquals(uri,send.getClipData().getItemAt(0).getUri());assertEquals("video/mp4",context.getContentResolver().getType(uri));
        try{context.getContentResolver().openFileDescriptor(uri,"w");fail("Video grant allowed writes");}catch(java.io.FileNotFoundException expected){}
        String image=seedImage();
        try{XsShareCoordinator.assetIntent(context,LIBRARY,Arrays.asList(id,image));fail("Mixed media silently shared");}catch(IllegalArgumentException expected){}
        engine.delete(LIBRARY,id);drain();assertFalse(saved.exists());assertNull(engine.find(LIBRARY,id));
        try(ParcelFileDescriptor.AutoCloseInputStream input=new ParcelFileDescriptor.AutoCloseInputStream(context.getContentResolver().openFileDescriptor(uri,"r"));ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[1024];int n;while((n=input.read(buffer))!=-1)out.write(buffer,0,n);assertArrayEquals(movie,out.toByteArray());
        }
    }
    private void playToCompletion(File file)throws Exception {
        CountDownLatch completed=new CountDownLatch(1);AtomicReference<String> error=new AtomicReference<>();AtomicInteger frames=new AtomicInteger();
        ImageReader images=ImageReader.newInstance(64,64,ImageFormat.PRIVATE,3);
        images.setOnImageAvailableListener(reader->{Image image=reader.acquireLatestImage();if(image!=null){frames.incrementAndGet();image.close();}},new Handler(Looper.getMainLooper()));
        MediaPlayer player=new MediaPlayer();
        try {
            player.setDataSource(file.getPath());player.setSurface(images.getSurface());player.setVolume(0,0);
            player.setOnPreparedListener(ready->{try{ready.start();}catch(RuntimeException failure){error.set("Playback start failed");completed.countDown();}});
            player.setOnErrorListener((failed,what,extra)->{error.set("Player error "+what+":"+extra);completed.countDown();return true;});
            player.setOnCompletionListener(done->completed.countDown());player.prepareAsync();
            assertTrue("Complete fixture playback",completed.await(10,TimeUnit.SECONDS));assertNull(error.get());assertTrue("Decoded video frames",frames.get()>0);
        }finally{player.release();images.close();}
    }
    @Test public void truncatedFragmentedHtmlAndPlaylistNeverRegisterVideo()throws Exception {
        byte[] movie=fixture("standard"),fragment=fixture("fragmented");AtomicInteger requests=new AtomicInteger();
        engine=new XsMediaRepository(context,address->{int n=requests.getAndIncrement();byte[] body=n==0?Arrays.copyOf(movie,movie.length-40):n==1?fragment:(n==2?"<html>not a video</html>":"#EXTM3U\n#EXT-X-VERSION:3\nsegment.ts").getBytes(StandardCharsets.UTF_8);return new Response(address,body,body.length);});
        for(String token:new String[]{"truncated","fragmented","html","playlist"}){
            String id=engine.enqueueVideo(LIBRARY,POST,url(token));XsMediaRepository.Asset row=await(id,"FAILED");assertNull(row.filename);assertFalse(part(id).exists());assertEquals(0,row.durationMs);
            try{XsShareCoordinator.assetIntent(context,LIBRARY,Arrays.asList(id));fail("Incomplete video shared");}catch(IllegalArgumentException expected){}
        }
        assertEquals(4,requests.get());
    }
    @Test public void cancelAndEraseDuringVideoReadCannotComplete()throws Exception {
        byte[] movie=fixture("standard");CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),enteredAgain=new CountDownLatch(1),releaseAgain=new CountDownLatch(1);AtomicInteger requests=new AtomicInteger();
        engine=new XsMediaRepository(context,address->{Response response=new Response(address,movie,movie.length);if(requests.getAndIncrement()==0)response.block(entered,release);else response.block(enteredAgain,releaseAgain);return response;});
        String canceled=engine.enqueueVideo(LIBRARY,POST,url("cancel"));assertTrue(entered.await(10,TimeUnit.SECONDS));engine.cancel(LIBRARY,canceled);drain();assertEquals("CANCELED",engine.find(LIBRARY,canceled).state);release.countDown();
        String erased=engine.enqueueVideo(LIBRARY,POST,url("erase"));assertTrue(enteredAgain.await(10,TimeUnit.SECONDS));assertFalse(part(canceled).exists());
        CountDownLatch cleared=new CountDownLatch(1);assertTrue(XsLibraryWrites.invalidateAndSubmit(LIBRARY,()->{engine.eraseLibrary(LIBRARY);cleared.countDown();}));assertTrue(cleared.await(10,TimeUnit.SECONDS));releaseAgain.countDown();
        CountDownLatch exited=new CountDownLatch(1);engine.workerMarkerForTests(exited::countDown);assertTrue(exited.await(10,TimeUnit.SECONDS));drain();assertTrue(engine.list(LIBRARY).isEmpty());assertFalse(part(erased).exists());
    }
    @Test public void redirectPolicyBlocksPrivateDestinationBeforeOpeningIt()throws Exception {
        AtomicInteger requests=new AtomicInteger();engine=new XsMediaRepository(context,address->{requests.incrementAndGet();return new Response(address,new byte[0],0){@Override public int getResponseCode(){return 302;}@Override public String getHeaderField(String name){return "Location".equalsIgnoreCase(name)?"https://127.0.0.1/movie.mp4":null;}};});
        String id=engine.enqueueVideo(LIBRARY,POST,url("redirect"));await(id,"FAILED");assertEquals(1,requests.get());assertFalse(part(id).exists());
    }
    @Test public void mediaV1MigrationPreservesExistingImagesAndShareLeases()throws Exception {
        legacyContext=context.createDeviceProtectedStorageContext();legacyContext.deleteDatabase("xs-media.db");
        File directory=new File(legacyContext.getFilesDir(),"xs-saved-media");assertTrue(directory.isDirectory()||directory.mkdirs());
        File shares=new File(legacyContext.getFilesDir(),"xs-share-leases");assertTrue(shares.isDirectory()||shares.mkdirs());
        String id=UUID.randomUUID().toString(),token=UUID.randomUUID().toString(),filename=id+".png";byte[] image=png();
        try(FileOutputStream out=new FileOutputStream(new File(directory,filename))){out.write(image);}try(FileOutputStream out=new FileOutputStream(new File(shares,token))){out.write(image);}
        File database=legacyContext.getDatabasePath("xs-media.db");assertTrue(database.getParentFile().isDirectory()||database.getParentFile().mkdirs());
        try(SQLiteDatabase old=SQLiteDatabase.openOrCreateDatabase(database,null)){
            old.execSQL("CREATE TABLE assets (id TEXT PRIMARY KEY,library_id TEXT NOT NULL,post_url TEXT NOT NULL,media_url TEXT,state TEXT NOT NULL,bytes INTEGER NOT NULL DEFAULT 0,total INTEGER NOT NULL DEFAULT -1,mime TEXT,filename TEXT,sha256 TEXT,created_ms INTEGER NOT NULL,error TEXT,category TEXT NOT NULL DEFAULT '')");
            old.execSQL("CREATE TABLE leases (token TEXT PRIMARY KEY,filename TEXT NOT NULL,mime TEXT NOT NULL,bytes INTEGER NOT NULL,expires_ms INTEGER NOT NULL)");
            old.execSQL("INSERT INTO assets(id,library_id,post_url,state,bytes,total,mime,filename,created_ms,category) VALUES(?,?,?,'COMPLETED',?,?,'image/png',?,123,'Legacy')",new Object[]{id,LIBRARY,POST,image.length,image.length,filename});
            old.execSQL("INSERT INTO leases VALUES(?,?,'image/png',?,?)",new Object[]{token,filename,image.length,System.currentTimeMillis()+60000});old.setVersion(1);
        }
        engine=new XsMediaRepository(legacyContext,address->{throw new java.io.IOException("No transport in migration");});
        XsMediaRepository.Asset retained=engine.find(LIBRARY,id);assertEquals("IMAGE",retained.mediaKind);assertEquals("Legacy",retained.category);assertEquals(123,retained.createdMs);assertEquals(0,retained.durationMs);
        assertArrayEquals(image,java.nio.file.Files.readAllBytes(engine.completedFile(LIBRARY,id).toPath()));assertEquals(image.length,engine.resolveLease(token,filename).bytes);
        assertEquals(3,engine.getReadableDatabase().getVersion());
    }
    private String seedImage()throws Exception {
        String id=UUID.randomUUID().toString(),filename=id+".png";byte[] bytes=png();try(FileOutputStream out=new FileOutputStream(new File(context.getFilesDir(),"xs-saved-media/"+filename))){out.write(bytes);}
        ContentValues row=new ContentValues();row.put("id",id);row.put("library_id",LIBRARY);row.put("post_url",POST);row.put("state","COMPLETED");row.put("mime","image/png");row.put("filename",filename);row.put("bytes",bytes.length);row.put("created_ms",1);engine.getWritableDatabase().insertOrThrow("assets",null,row);return id;
    }
    private static byte[] png()throws Exception {Bitmap bitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888);try(ByteArrayOutputStream out=new ByteArrayOutputStream()){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));return out.toByteArray();}finally{bitmap.recycle();}}
    private static String hex(byte[] bytes){StringBuilder text=new StringBuilder();for(byte value:bytes)text.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return text.toString();}
    private static class Response extends HttpURLConnection {
        final byte[] bytes;final long declared;CountDownLatch entered,release;
        Response(URL address,byte[] bytes,long declared){super(address);this.bytes=bytes;this.declared=declared;}
        void block(CountDownLatch entered,CountDownLatch release){this.entered=entered;this.release=release;}
        @Override public int getResponseCode(){return 200;}@Override public long getContentLengthLong(){return declared;}@Override public String getContentType(){return "video/mp4";}
        @Override public InputStream getInputStream(){if(entered==null)return new ByteArrayInputStream(bytes);return new ByteArrayInputStream(bytes){boolean waited;@Override public synchronized int read(byte[] output,int offset,int count){if(!waited){waited=true;entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))return -1;}catch(InterruptedException failure){Thread.currentThread().interrupt();return -1;}}return super.read(output,offset,count);}};}
        @Override public void connect(){}@Override public void disconnect(){}@Override public boolean usingProxy(){return false;}
    }
}

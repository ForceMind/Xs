package dev.forcemind.xs.android;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** The real bounded download engine runs against an instance-local fake HTTP response.
 * It still validates every URL/redirect and performs decoding, checksum and atomic registration.
 * No real network, X credentials or receiving-chat-app compatibility is claimed.
 */
@RunWith(AndroidJUnit4.class)
public final class MediaDownloadEngineTest {
    private Context context;
    private String library;
    private XsMediaRepository engine;
    @Before public void setup() {
        context=ApplicationProvider.getApplicationContext();
        // Provider uses the unmodified production singleton; test transport is never installed globally.
        XsMediaRepository.get(context);
        library="media-engine:"+UUID.randomUUID();
    }
    @After public void cleanup()throws Exception {
        if(engine!=null) {
            engine.stopWorkerForTests();
            drain();
            engine.eraseLibrary(library);
            engine.close();
        }
    }
    private void drain()throws Exception {
        CountDownLatch done=new CountDownLatch(1);
        assertTrue(XsLibraryWrites.submit(library,done::countDown));
        assertTrue(done.await(10,TimeUnit.SECONDS));
    }
    private static byte[] png()throws IOException {
        Bitmap bitmap=Bitmap.createBitmap(3,2,Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xff22aa44);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes));
        bitmap.recycle();return bytes.toByteArray();
    }
    private XsMediaRepository.Asset await(String id,String state)throws Exception {
        long deadline=android.os.SystemClock.elapsedRealtime()+10000;
        do {
            XsMediaRepository.Asset result=engine.find(library,id);
            if(result!=null&&state.equals(result.state)) {
                CountDownLatch idle=new CountDownLatch(1);
                engine.workerMarkerForTests(idle::countDown);
                assertTrue(idle.await(10,TimeUnit.SECONDS));
                drain();
                return result;
            }
            Thread.sleep(20);
        }while(android.os.SystemClock.elapsedRealtime()<deadline);
        XsMediaRepository.Asset actual=engine.find(library,id);
        fail("Expected "+state+", actual "+(actual==null?"missing":actual.state));return null;
    }
    private File part(String id){return new File(context.getFilesDir(),"xs-saved-media/"+id+".part");}
    private static String sha256(byte[] bytes)throws Exception {
        StringBuilder hash=new StringBuilder();for(byte value:MessageDigest.getInstance("SHA-256").digest(bytes))hash.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return hash.toString();
    }
    @Test public void realEngineCompletesValidatedImageAndSharesEveryByte()throws Exception {
        byte[] original=png();AtomicInteger requests=new AtomicInteger();
        engine=new XsMediaRepository(context,url->{assertEquals("pbs.twimg.com",url.getHost());requests.incrementAndGet();return new Response(url,original,"image/png",original.length);});
        String id=engine.enqueueImage(library,"https://x.com/user/status/123","https://pbs.twimg.com/media/Fixture?format=png&name=orig");
        XsMediaRepository.Asset completed=await(id,"COMPLETED");
        assertEquals(1,requests.get());assertEquals(original.length,completed.bytes);assertEquals("image/png",completed.mime);
        File saved=engine.completedFile(library,id);assertArrayEquals(original,java.nio.file.Files.readAllBytes(saved.toPath()));assertFalse(part(id).exists());
        try(Cursor c=engine.getReadableDatabase().rawQuery("SELECT sha256,media_url FROM assets WHERE id=?",new String[]{id})) {
            assertTrue(c.moveToFirst());assertEquals(sha256(original),c.getString(0));assertTrue("Media credential URL must not persist",c.isNull(1));
        }
        Intent share=XsShareCoordinator.assetIntent(context,library,Arrays.asList(id));Uri uri=share.getParcelableExtra(Intent.EXTRA_STREAM);
        try(ParcelFileDescriptor.AutoCloseInputStream input=new ParcelFileDescriptor.AutoCloseInputStream(context.getContentResolver().openFileDescriptor(uri,"r"));ByteArrayOutputStream received=new ByteArrayOutputStream()) {
            byte[] b=new byte[128];int n;while((n=input.read(b))!=-1)received.write(b,0,n);assertArrayEquals(original,received.toByteArray());
        }
    }
    @Test public void truncatedAndHtmlResponsesNeverBecomeCompletedAssets()throws Exception {
        byte[] full=png();AtomicInteger requests=new AtomicInteger();
        engine=new XsMediaRepository(context,url->{if(requests.getAndIncrement()==0)return new Response(url,Arrays.copyOf(full,full.length/2),"image/png",full.length);return new Response(url,"<html>login required</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8),"image/png",-1);});
        String first=engine.enqueueImage(library,"https://x.com/u/status/123","https://pbs.twimg.com/media/Truncated?format=png&name=orig");
        await(first,"FAILED");assertFalse(part(first).exists());assertNull(engine.find(library,first).filename);
        String second=engine.enqueueImage(library,"https://x.com/u/status/123","https://pbs.twimg.com/media/Html?format=png&name=orig");
        await(second,"FAILED");assertFalse(part(second).exists());assertNull(engine.find(library,second).filename);
        try{XsShareCoordinator.assetIntent(context,library,Arrays.asList(first));fail("Failed download shared");}catch(IllegalArgumentException expected){}
        assertEquals(2,requests.get());
    }
    @Test public void cancelAndErasureWhileReadingCannotRegisterAnAsset()throws Exception {
        byte[] image=png();CountDownLatch firstRead=new CountDownLatch(1),allowRead=new CountDownLatch(1);
        CountDownLatch secondRead=new CountDownLatch(1),allowSecondRead=new CountDownLatch(1);AtomicInteger requests=new AtomicInteger();
        engine=new XsMediaRepository(context,url->{Response response=new Response(url,image,"image/png",image.length);if(requests.getAndIncrement()==0)response.block(firstRead,allowRead);else response.block(secondRead,allowSecondRead);return response;});
        String canceled=engine.enqueueImage(library,"https://x.com/u/status/123","https://pbs.twimg.com/media/Cancel?format=png&name=orig");
        assertTrue(firstRead.await(10,TimeUnit.SECONDS));engine.cancel(library,canceled);drain();assertEquals("CANCELED",engine.find(library,canceled).state);allowRead.countDown();
        // The single transport worker reaches the second response only after cleaning the first temporary file.
        String erased=engine.enqueueImage(library,"https://x.com/u/status/124","https://pbs.twimg.com/media/Erase?format=png&name=orig");
        assertTrue(secondRead.await(10,TimeUnit.SECONDS));assertFalse(part(canceled).exists());
        CountDownLatch erasedAtBarrier=new CountDownLatch(1);
        assertTrue(XsLibraryWrites.invalidateAndSubmit(library,()->{engine.eraseLibrary(library);erasedAtBarrier.countDown();}));
        assertTrue(erasedAtBarrier.await(10,TimeUnit.SECONDS));allowSecondRead.countDown();
        // A marker task proves the long-running old transfer has exited, then drain its final status-only write.
        CountDownLatch oldExited=new CountDownLatch(1);engine.workerMarkerForTests(oldExited::countDown);
        assertTrue(oldExited.await(10,TimeUnit.SECONDS));drain();
        assertNull(engine.find(library,erased));assertNull(engine.find(library,canceled));assertFalse(part(erased).exists());assertTrue(engine.list(library).isEmpty());
    }
    @Test public void hostileRedirectIsRejectedBeforeOpeningItsDestination()throws Exception {
        AtomicInteger requests=new AtomicInteger();
        engine=new XsMediaRepository(context,url->{requests.incrementAndGet();return new Response(url,new byte[0],"image/png",0){@Override public int getResponseCode(){return 302;}@Override public String getHeaderField(String name){return "Location".equalsIgnoreCase(name)?"https://127.0.0.1/private":null;}};});
        String id=engine.enqueueImage(library,"https://x.com/u/status/123","https://pbs.twimg.com/media/Redirect?format=png&name=orig");
        await(id,"FAILED");assertEquals(1,requests.get());assertFalse(part(id).exists());assertNull(engine.find(library,id).filename);
    }
    private static class Response extends HttpURLConnection {
        final byte[] bytes;final String mime;final long declared;CountDownLatch entered,release;
        Response(URL url,byte[] bytes,String mime,long declared){super(url);this.bytes=bytes;this.mime=mime;this.declared=declared;}
        void block(CountDownLatch entered,CountDownLatch release){this.entered=entered;this.release=release;}
        @Override public int getResponseCode(){return 200;}
        @Override public long getContentLengthLong(){return declared;}
        @Override public String getContentType(){return mime;}
        @Override public InputStream getInputStream(){
            if(entered==null)return new ByteArrayInputStream(bytes);
            return new ByteArrayInputStream(bytes){boolean waited;@Override public synchronized int read(byte[] out,int offset,int count){
                if(!waited){waited=true;entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))return -1;}catch(InterruptedException stop){Thread.currentThread().interrupt();return -1;}}
                return super.read(out,offset,count);
            }};
        }
        @Override public void connect(){}
        @Override public void disconnect(){}
        @Override public boolean usingProxy(){return false;}
    }
}

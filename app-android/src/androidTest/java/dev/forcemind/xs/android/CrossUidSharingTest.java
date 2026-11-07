package dev.forcemind.xs.android;

import dev.forcemind.xs.browser.CrossUidShareHostActivity;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.forcemind.xs.android.XsArchiveDatabase;
import dev.forcemind.xs.android.XsLibraryDeletion;
import dev.forcemind.xs.android.XsLibraryWrites;
import dev.forcemind.xs.android.XsMediaRepository;
import dev.forcemind.xs.android.XsShareCoordinator;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real framework grants to a separately installed UID; only generated PNG/MP4 fixtures. */
@RunWith(AndroidJUnit4.class)
public final class CrossUidSharingTest {
    private Instrumentation instrumentation;
    private Context app;
    private XsMediaRepository media;
    private XsArchiveDatabase archive;
    private CrossUidShareHostActivity host;
    private CrossUidShareHostActivity.Probe active;
    private String library;
    private int receiverUid;
    private final List<Uri> copies=new ArrayList<>();

    @Before public void setup()throws Exception {
        instrumentation=InstrumentationRegistry.getInstrumentation();app=instrumentation.getTargetContext();
        receiverUid=app.getPackageManager().getApplicationInfo(CrossUidShareHostActivity.RECEIVER_PACKAGE,0).uid;
        assertNotEquals("A receiver running under the target UID would not test Android grants",Process.myUid(),receiverUid);
        library="local:"+UUID.randomUUID();archive=new XsArchiveDatabase(app);archive.ensureLibrary(library,"Cross UID synthetic fixture");
        media=XsMediaRepository.get(app);
        host=(CrossUidShareHostActivity)instrumentation.startActivitySync(new Intent(app,CrossUidShareHostActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync();
    }
    @After public void cleanup()throws Exception {
        if(active!=null&&active.done.getCount()!=0){active.cancel();active.done.await(5,TimeUnit.SECONDS);}
        if(host!=null){instrumentation.runOnMainSync(host::finish);instrumentation.waitForIdleSync();}
        if(media!=null) {
            for(Uri uri:copies)try{media.discardLease(media.resolveLease(uri.getPathSegments().get(0),uri.getPathSegments().get(1)));}catch(IllegalArgumentException|IllegalStateException alreadyGone){}
            if(!XsLibraryWrites.isDeleted(library)){drain();media.eraseLibrary(library);archive.deleteLibraryScope(library);}
        }
        if(archive!=null)archive.close();
    }
    private void drain()throws Exception {CountDownLatch done=new CountDownLatch(1);assertTrue(XsLibraryWrites.submit(library,done::countDown));assertTrue(done.await(10,TimeUnit.SECONDS));}
    private String image(int color)throws Exception {
        Bitmap pixels=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888);pixels.eraseColor(color);
        try(ByteArrayOutputStream bytes=new ByteArrayOutputStream()){assertTrue(pixels.compress(Bitmap.CompressFormat.PNG,100,bytes));return saved(bytes.toByteArray(),false);}
        finally{pixels.recycle();}
    }
    private String video()throws Exception {
        try(InputStream input=instrumentation.getContext().getAssets().open("standard-video.mp4.b64");ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1)bytes.write(buffer,0,count);
            return saved(Base64.decode(bytes.toByteArray(),Base64.DEFAULT),true);
        }
    }
    private String saved(byte[] bytes,boolean video)throws Exception {
        String id=UUID.randomUUID().toString(),filename=id+(video?".mp4":".png");
        File directory=new File(app.getFilesDir(),"xs-saved-media");assertTrue(directory.isDirectory()||directory.mkdirs());
        try(FileOutputStream output=new FileOutputStream(new File(directory,filename))){output.write(bytes);output.getFD().sync();}
        ContentValues row=new ContentValues();row.put("id",id);row.put("library_id",library);row.put("post_url","https://x.com/i/web/status/123");
        row.put("state","COMPLETED");row.put("mime",video?"video/mp4":"image/png");row.put("filename",filename);
        row.put("bytes",bytes.length);row.put("total",bytes.length);row.put("created_ms",System.currentTimeMillis());row.put("sha256",hex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        if(video){row.put("media_kind","VIDEO");row.put("duration_ms",1000);row.put("video_tracks",1);row.put("audio_tracks",1);row.put("width",64);row.put("height",64);row.put("rotation_degrees",0);}
        media.getWritableDatabase().insertOrThrow("assets",null,row);return id;
    }
    private Intent share(String id)throws Exception {
        Intent intent=XsShareCoordinator.assetIntent(app,library,Collections.singletonList(id));
        copies.add(intent.getParcelableExtra(Intent.EXTRA_STREAM));return intent;
    }
    private void start(Intent intent)throws Exception {
        active=new CrossUidShareHostActivity.Probe();
        instrumentation.runOnMainSync(()->host.launch(intent,active));
        assertTrue("Separate receiver must reach its pre-read barrier",active.ready.await(10,TimeUnit.SECONDS));
        assertTrue("Fixture launch must not fail",active.done.getCount()!=0);
    }
    private Bundle finishRead()throws Exception {
        active.readNow();assertTrue("Separate receiver must return a bounded result",active.done.await(15,TimeUnit.SECONDS));
        Bundle result=active.summary;assertNotNull(result);assertEquals(Activity.RESULT_OK,result.getInt("activity_result"));
        assertEquals(receiverUid,result.getInt("receiver_uid"));assertNotEquals(Process.myUid(),result.getInt("receiver_uid"));
        return result;
    }
    private void assertSameFile(Bundle result,File expected)throws Exception {
        assertEquals("READABLE",result.getString("status"));assertTrue(result.getBoolean("read_granted"));
        assertEquals(expected.length(),result.getLong("bytes"));assertEquals(hash(expected),result.getString("sha256"));
    }
    private static String hash(File file)throws Exception {
        MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream input=new java.io.FileInputStream(file)){byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1)hash.update(buffer,0,count);}return hex(hash.digest());
    }
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder(bytes.length*2);for(byte value:bytes){out.append(Character.forDigit((value>>>4)&15,16));out.append(Character.forDigit(value&15,16));}return out.toString();}

    @Test public void separateUidCannotReadFreshLeaseWithoutIntentGrant()throws Exception {
        Intent intent=share(image(0xffff0000));intent.setFlags(0); // ClipData remains, but no grant flag is present.
        start(intent);Bundle result=finishRead();assertEquals("DENIED",result.getString("status"));
        assertFalse(result.getBoolean("read_granted"));assertEquals(0,result.getLong("bytes"));assertNull(result.getString("sha256"));
    }
    @Test public void frameworkGrantsOnlyChosenImageReadWithoutWriteOrOtherLeaseAccess()throws Exception {
        String selected=image(0xff00ff00),unselected=image(0xff0000ff);
        Intent selectedIntent=share(selected),otherIntent=share(unselected);
        Uri other=otherIntent.getParcelableExtra(Intent.EXTRA_STREAM);
        selectedIntent.putExtra("xs.fixture.probe_uri",other.toString()); // String extra is deliberately outside ClipData grants.
        assertEquals(0,selectedIntent.getFlags()&Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        start(selectedIntent);Bundle result=finishRead();assertSameFile(result,media.completedFile(library,selected));
        assertTrue("Receiver must not obtain write access",result.getBoolean("write_denied"));
        assertTrue("An unselected real lease must remain unavailable to the other UID",result.getBoolean("probe_denied"));
        assertTrue(media.completedFile(library,unselected).isFile());
    }
    @Test public void grantedVideoIsTheActualCompleteFileAtTheSeparateReceiver()throws Exception {
        String selected=video();Intent intent=share(selected);assertEquals("video/mp4",intent.getType());
        start(intent);Bundle result=finishRead();assertSameFile(result,media.completedFile(library,selected));assertTrue(result.getBoolean("write_denied"));
    }
    @Test public void delayedFirstReadSurvivesActualWholeSourceLibraryErasure()throws Exception {
        String selected=image(0xff226688);File original=media.completedFile(library,selected);
        long expectedBytes=original.length();String expectedHash=hash(original);Intent intent=share(selected);
        start(intent); // Receiver has its system grant but has not opened even one byte yet.
        CountDownLatch deleted=new CountDownLatch(1);AtomicBoolean success=new AtomicBoolean();
        XsLibraryDeletion.delete(app,library,(ok,replacement)->{success.set(ok);deleted.countDown();});
        assertTrue(deleted.await(15,TimeUnit.SECONDS));assertTrue(success.get());
        assertFalse(original.exists());assertNull(media.find(library,selected));
        try(android.database.Cursor rows=archive.libraries()){while(rows.moveToNext())assertNotEquals(library,rows.getString(3));}
        Bundle result=finishRead();assertEquals("READABLE",result.getString("status"));assertTrue(result.getBoolean("read_granted"));
        assertEquals(expectedBytes,result.getLong("bytes"));assertEquals(expectedHash,result.getString("sha256"));assertTrue(result.getBoolean("write_denied"));
    }
    @Test public void evenAnAccidentalReadGrantCannotExposeThePrivateDatabasePath()throws Exception {
        Uri database=new Uri.Builder().scheme("content").authority(app.getPackageName()+".xs.assets").appendPath("databases").appendPath("xs-local-history.db").build();
        Intent intent=new Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM,database)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(new ClipData("Synthetic invalid path",new String[]{"application/octet-stream"},new ClipData.Item(database)));
        start(intent);Bundle result=finishRead();assertTrue("Framework grant is real; rejection must come from the bounded provider",result.getBoolean("read_granted"));
        assertEquals("UNAVAILABLE",result.getString("status"));assertEquals(0,result.getLong("bytes"));assertNull(result.getString("sha256"));
    }
}

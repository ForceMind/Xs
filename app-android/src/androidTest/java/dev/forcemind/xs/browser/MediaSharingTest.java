package dev.forcemind.xs.browser;

import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import dev.forcemind.xs.android.XsMediaRepository;
import dev.forcemind.xs.android.XsLibraryWrites;
import dev.forcemind.xs.android.XsShareCoordinator;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Generated local PNGs only. These tests make no claims about live X or recipient apps. */
@RunWith(AndroidJUnit4.class)
public final class MediaSharingTest {
    private Context context;private XsMediaRepository repo;private String library;
    @Before public void setup(){context=ApplicationProvider.getApplicationContext();repo=XsMediaRepository.get(context);library="media-test:"+UUID.randomUUID();}
    @After public void cleanup()throws Exception {drain();repo.eraseLibrary(library);}
    private void drain()throws Exception {java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);assertTrue(XsLibraryWrites.submit(library,done::countDown));assertTrue(done.await(10,java.util.concurrent.TimeUnit.SECONDS));}
    private String fixture(int color)throws Exception {
        String id=UUID.randomUUID().toString(),filename=id+".png";File f=new File(new File(context.getFilesDir(),"xs-saved-media"),filename);
        Bitmap image=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888);image.eraseColor(color);try(FileOutputStream output=new FileOutputStream(f)){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,output));}image.recycle();
        ContentValues v=new ContentValues();v.put("id",id);v.put("library_id",library);v.put("post_url","https://x.com/i/web/status/123");v.put("state","COMPLETED");v.put("mime","image/png");v.put("filename",filename);v.put("bytes",f.length());v.put("total",f.length());v.put("created_ms",1L);repo.getWritableDatabase().insertOrThrow("assets",null,v);return id;
    }
    @Test public void singleImageSharesActualReadOnlyBytesAfterSourceDeletion()throws Exception {
        String id=fixture(0xffff0000);File original=repo.completedFile(library,id);long size=original.length();
        byte[] expectedBytes=java.nio.file.Files.readAllBytes(original.toPath());
        Intent share=XsShareCoordinator.assetIntent(context,library,Arrays.asList(id));assertEquals(Intent.ACTION_SEND,share.getAction());assertEquals("image/png",share.getType());assertTrue((share.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0);assertEquals(0,share.getFlags()&Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        Uri uri=share.getParcelableExtra(Intent.EXTRA_STREAM);assertNotNull(uri);assertEquals(uri,share.getClipData().getItemAt(0).getUri());assertEquals("content",uri.getScheme());
        repo.delete(library,id);
        drain(); // FIFO barrier: deletion must finish before testing the receiver's delayed read.
        assertFalse("Source file must really be deleted",original.exists());
        assertNull("Source metadata must really be deleted",repo.find(library,id));
        ParcelFileDescriptor descriptor=context.getContentResolver().openFileDescriptor(uri,"r");
        assertNotNull(descriptor);
        try(ParcelFileDescriptor.AutoCloseInputStream input=new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
            java.io.ByteArrayOutputStream received=new java.io.ByteArrayOutputStream()) {
            byte[] buffer=new byte[1024];int count;
            while((count=input.read(buffer))!=-1)received.write(buffer,0,count);
            assertArrayEquals("Receiver must get every original byte after source deletion",expectedBytes,received.toByteArray());
        }
        try(Cursor c=context.getContentResolver().query(uri,null,null,null,null)){assertNotNull(c);assertTrue(c.moveToFirst());assertEquals(size,c.getLong(c.getColumnIndexOrThrow(OpenableColumns.SIZE)));assertTrue(c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)).endsWith(".png"));}
        try{context.getContentResolver().openFileDescriptor(uri,"w");fail("Writable share accepted");}catch(java.io.FileNotFoundException expected){}
    }
    @Test public void multipleImageIntentContainsOnlyChosenCopies()throws Exception {
        String first=fixture(0xff00ff00),second=fixture(0xff0000ff);Intent share=XsShareCoordinator.assetIntent(context,library,Arrays.asList(first,second));assertEquals(Intent.ACTION_SEND_MULTIPLE,share.getAction());assertEquals(2,share.getParcelableArrayListExtra(Intent.EXTRA_STREAM).size());ClipData clip=share.getClipData();assertEquals(2,clip.getItemCount());
        for(int i=0;i<2;i++){assertEquals("image/png",context.getContentResolver().getType(clip.getItemAt(i).getUri()));try(ParcelFileDescriptor fd=context.getContentResolver().openFileDescriptor(clip.getItemAt(i).getUri(),"r")){assertTrue(fd.getStatSize()>0);}}
        assertEquals(2,repo.list(library).size());
    }
    @Test public void providerRejectsRootTraversalExpiredAndUnfinishedFiles()throws Exception {
        String id=fixture(0xffffffff);Intent share=XsShareCoordinator.assetIntent(context,library,Arrays.asList(id));Uri uri=share.getParcelableExtra(Intent.EXTRA_STREAM);
        Uri root=new Uri.Builder().scheme("content").authority(context.getPackageName()+".xs.assets").appendPath("databases").appendPath("xs-local-history.db").build();
        try{context.getContentResolver().openFileDescriptor(root,"r");fail("Database exposed");}catch(java.io.FileNotFoundException expected){}
        try{context.getContentResolver().openFileDescriptor(Uri.parse(uri.toString()+"?path=../databases"),"r");fail("Query accepted");}catch(java.io.FileNotFoundException expected){}
        ContentValues expired=new ContentValues();expired.put("expires_ms",0);repo.getWritableDatabase().update("leases",expired,"token=?",new String[]{uri.getPathSegments().get(0)});repo.cleanupLeases();
        try{context.getContentResolver().openFileDescriptor(uri,"r");fail("Expired copy exposed");}catch(java.io.FileNotFoundException expected){}
        ContentValues unfinished=new ContentValues();unfinished.put("state","FAILED");repo.getWritableDatabase().update("assets",unfinished,"id=?",new String[]{id});
        try{XsShareCoordinator.assetIntent(context,library,Arrays.asList(id));fail("Unfinished asset shared");}catch(IllegalArgumentException expected){}
    }
    @Test public void cacheAndOtherLibraryDeletionPreserveSavedImage()throws Exception {
        String id=fixture(0xff111111);File saved=repo.completedFile(library,id);File cache=new File(context.getCacheDir(),"media-fixture.tmp");try(FileOutputStream out=new FileOutputStream(cache)){out.write(1);}assertTrue(cache.delete());assertTrue(saved.isFile());repo.eraseLibrary("different-library");assertTrue(saved.isFile());
        repo.setCategory(library,id,"Research");drain();assertEquals("Research",repo.find(library,id).category);assertNull(repo.find("different-library",id));
    }
    @Test public void failedBatchShareRollsBackOnlyItsOwnNewCopies()throws Exception {
        String id=fixture(0xff123456);int before;
        try(Cursor c=repo.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM leases",null)){c.moveToFirst();before=c.getInt(0);}
        try{XsShareCoordinator.assetIntent(context,library,Arrays.asList(id,"missing"));fail("Missing asset accepted");}catch(IllegalArgumentException expected){}
        try(Cursor c=repo.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM leases",null)){c.moveToFirst();assertEquals(before,c.getInt(0));}
        assertTrue(repo.completedFile(library,id).isFile());
    }
    @Test public void textSharingCanonicalizesSourceAndNeverAddsPrivateMetadata(){Intent share=XsShareCoordinator.textIntent("https://x.com/user/status/123?s=secret","Visible body");assertEquals("text/plain",share.getType());assertEquals("Visible body\n\nhttps://x.com/i/web/status/123",share.getStringExtra(Intent.EXTRA_TEXT));assertFalse(share.hasExtra(Intent.EXTRA_STREAM));}
}

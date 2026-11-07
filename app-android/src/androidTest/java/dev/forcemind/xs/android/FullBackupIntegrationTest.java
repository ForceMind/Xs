package dev.forcemind.xs.android;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.forcemind.xs.core.ArchiveSearch;
import dev.forcemind.xs.core.EncryptedLibraryBackup;
import dev.forcemind.xs.core.LibraryBackupCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real SQLite, Bitmap/MP4 validation, private files and outbound Provider bytes; local fixtures only. */
@RunWith(AndroidJUnit4.class)
public final class FullBackupIntegrationTest {
    private Context app;private XsArchiveDatabase archive;private XsMediaRepository media;
    private String source,defaultLibrary;private File png,movie;private final List<String> owned=new ArrayList<>();
    private static final char[] PASSWORD="Xs本地恢复-test-2026".toCharArray();
    private static final String POST="https://x.com/i/web/status/912345";
    @Before public void setUp()throws Exception {
        app=InstrumentationRegistry.getInstrumentation().getTargetContext();archive=new XsArchiveDatabase(app);media=XsMediaRepository.get(app);
        source="local:"+UUID.randomUUID();owned.add(source);defaultLibrary=XsLocalSpaces.defaultLibrary(app);
        png=File.createTempFile("full-backup-image-",".png",app.getCacheDir());Bitmap bitmap=Bitmap.createBitmap(3,2,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff4688ff);
        try(FileOutputStream out=new FileOutputStream(png)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}finally{bitmap.recycle();}
        movie=File.createTempFile("full-backup-video-",".mp4",app.getCacheDir());
        try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("standard-video.mp4.b64");ByteArrayOutputStream bytes=new ByteArrayOutputStream();FileOutputStream out=new FileOutputStream(movie)) {
            byte[] b=new byte[8192];int count;while((count=in.read(b))!=-1)bytes.write(b,0,count);out.write(Base64.decode(bytes.toByteArray(),Base64.DEFAULT));
        }
        write(source,()->{archive.ensureLibrary(source,"加密资料 📚");assertTrue(archive.favorite(source,POST,"作者","加密中文正文 🚀",1000L,2000L));archive.putFavoriteDetails(source,"912345","需要保留的备注 🧪","离线,研发");archive.putCategory(source,"folder","研究");assertTrue(archive.assignCategory(source,"912345","folder"));});
    }
    @After public void tearDown()throws Exception {
        for(String scope:owned){CountDownLatch done=new CountDownLatch(1);assertTrue(XsLibraryWrites.invalidateAndSubmit(scope,()->{try{media.eraseLibrary(scope);archive.clearAccount(scope);archive.getWritableDatabase().delete("libraries","storage_scope=?",new String[]{scope});}finally{done.countDown();}}));assertTrue(done.await(20,TimeUnit.SECONDS));}
        archive.close();if(png!=null)png.delete();if(movie!=null)movie.delete();
    }
    @Test public void encryptedRoundTripRestoresActualImageVideoMetadataAndShareableBytes()throws Exception {
        addResources();String secret="PRIVATE_BROWSER_OR_DOWNLOAD_SECRET_"+UUID.randomUUID();
        // Completed images from the old media schema may have no recorded checksum.
        write(source,()->media.getWritableDatabase().execSQL("UPDATE assets SET sha256=NULL WHERE library_id=? AND media_kind='IMAGE'",new Object[]{source}));
        write(source,()->{ContentValues task=new ContentValues();task.put("id",UUID.randomUUID().toString());task.put("library_id",source);task.put("post_url",POST);task.put("state","FAILED");task.put("created_ms",9000L);task.put("url_cipher",secret);media.getWritableDatabase().insertOrThrow("assets",null,task);});
        byte[] encrypted=export();assertFalse(new String(encrypted,StandardCharsets.UTF_8).contains("加密中文正文"));assertFalse(new String(encrypted,StandardCharsets.UTF_8).contains(secret));
        XsFullBackup.Prepared preview=XsFullBackup.inspectEncrypted(app,source,new ByteArrayInputStream(encrypted),PASSWORD);
        assertEquals(2,preview.assetCount());assertEquals(png.length()+movie.length(),preview.assetBytes());assertEquals("加密资料 📚",preview.label());
        String restored=XsFullBackup.restoreNewLibrary(app,preview,null);owned.add(restored);
        assertNotEquals(source,restored);assertEquals(defaultLibrary,XsLocalSpaces.defaultLibrary(app));
        assertEquals(3,media.list(source).size());assertEquals(2,media.list(restored).size());
        ArchiveSearch.Filter query=new ArchiveSearch.Filter();query.keyword="加密中文";query.tag="研发";query.categoryId="folder";query.favoriteOnly=true;
        try(Cursor c=archive.search(restored,query)){assertEquals(1,c.getCount());assertTrue(c.moveToFirst());assertEquals("需要保留的备注 🧪",c.getString(11));}
        try(Cursor c=archive.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM snapshot_versions WHERE account_id=?",new String[]{restored})){assertTrue(c.moveToFirst());assertTrue(c.getInt(0)>0);}
        for(XsMediaRepository.Asset asset:media.list(restored)) {
            assertEquals("COMPLETED",asset.state);assertEquals(POST,asset.postUrl);File file=media.completedFile(restored,asset.id);
            String expected="VIDEO".equals(asset.mediaKind)?hash(movie):hash(png);assertEquals(expected,hash(file));
            Intent send=XsShareCoordinator.assetIntent(app,restored,Collections.singletonList(asset.id));Uri uri=send.getParcelableExtra(Intent.EXTRA_STREAM);
            assertNotNull(uri);assertEquals(asset.mime,app.getContentResolver().getType(uri));try(InputStream in=app.getContentResolver().openInputStream(uri)){assertNotNull(in);assertEquals(expected,hash(in));}
        }
        try(Cursor c=media.getReadableDatabase().rawQuery("SELECT media_url,url_cipher,sha256 FROM assets WHERE library_id=?",new String[]{restored})){while(c.moveToNext()){assertTrue(c.isNull(0));assertTrue(c.isNull(1));assertEquals(64,c.getString(2).length());}}
        assertEquals(0,markerCount());
    }
    @Test public void wrongPasswordTamperingTruncationAndCancellationNeverCreateALibrary()throws Exception {
        addResources();byte[] encrypted=export();int libraries=libraries(),assets=allAssets(),staging=stagingCount();
        reject(encrypted,"wrong-password".toCharArray());byte[] corrupt=encrypted.clone();corrupt[corrupt.length-1]^=1;reject(corrupt,PASSWORD);
        reject(Arrays.copyOf(encrypted,encrypted.length-17),PASSWORD);
        try(XsFullBackup.Prepared prepared=XsFullBackup.inspectEncrypted(app,source,new ByteArrayInputStream(encrypted),PASSWORD)){assertEquals(2,prepared.assetCount());}
        assertEquals(staging,stagingCount());assertEquals(libraries,libraries());assertEquals(assets,allAssets());assertEquals(defaultLibrary,XsLocalSpaces.defaultLibrary(app));
    }
    @Test public void authenticatedMaliciousAssetDescriptorsAndInvalidMediaAreRejected()throws Exception {
        byte[] metadata=metadata();int libraries=libraries(),assets=allAssets(),staging=stagingCount();
        Map<String,Object> descriptor=descriptor(png);descriptor.put("filename","../../outside.db");reject(candidate(metadata,descriptor,png,1),PASSWORD);
        descriptor=descriptor(png);descriptor.put("post_url","file:///data/data/private");reject(candidate(metadata,descriptor,png,1),PASSWORD);
        descriptor=descriptor(png);reject(candidate(metadata,descriptor,png,2),PASSWORD); // Duplicate opaque source identity.
        File invalid=File.createTempFile("full-backup-invalid-",".png",app.getCacheDir());
        try {try(FileOutputStream out=new FileOutputStream(invalid)){out.write("not a real image, even with a valid checksum".getBytes(StandardCharsets.UTF_8));}
            reject(candidate(metadata,descriptor(invalid),invalid,1),PASSWORD);
        }finally{invalid.delete();}
        assertEquals(staging,stagingCount());assertEquals(libraries,libraries());assertEquals(assets,allAssets());assertFalse(new File(app.getDataDir(),"outside.db").exists());
    }
    @Test public void sqliteFailureAfterResourceStagingRollsBackEveryNewResourceAndMarker()throws Exception {
        addResources();byte[] encrypted=export();int libraries=libraries(),assets=allAssets(),staging=stagingCount();
        archive.getWritableDatabase().execSQL("CREATE TRIGGER fixture_full_restore_reject BEFORE INSERT ON libraries WHEN new.label='reject-full-fixture' BEGIN SELECT RAISE(ABORT,'fixture'); END");
        try {
            XsFullBackup.Prepared prepared=XsFullBackup.inspectEncrypted(app,source,new ByteArrayInputStream(encrypted),PASSWORD);
            try{XsFullBackup.restoreNewLibrary(app,prepared,"reject-full-fixture");fail("SQLite fixture must abort after media staging");}catch(IOException expected){}
        }finally{archive.getWritableDatabase().execSQL("DROP TRIGGER fixture_full_restore_reject");}
        assertEquals(libraries,libraries());assertEquals(assets,allAssets());assertEquals(staging,stagingCount());assertEquals(0,markerCount());
        assertEquals(2,media.list(source).size());assertEquals(defaultLibrary,XsLocalSpaces.defaultLibrary(app));
    }
    @Test public void clearingSourceExpiresPreparedFullRestoreWithoutResurrection()throws Exception {
        addResources();XsFullBackup.Prepared prepared=XsFullBackup.inspectEncrypted(app,source,new ByteArrayInputStream(export()),PASSWORD);int libraries=libraries();
        CountDownLatch cleared=new CountDownLatch(1);assertTrue(XsLibraryWrites.invalidateAndSubmit(source,()->{try{media.eraseLibrary(source);archive.clearAccount(source);}finally{cleared.countDown();}}));assertTrue(cleared.await(20,TimeUnit.SECONDS));
        try{XsFullBackup.restoreNewLibrary(app,prepared,null);fail("cleared source admission reused");}catch(IOException expected){}
        assertEquals(libraries,libraries());assertEquals(0,media.list(source).size());assertEquals(0,markerCount());assertEquals(defaultLibrary,XsLocalSpaces.defaultLibrary(app));
    }
    @Test public void sourceEpochChangeStopsExportBeforeAuthenticatedCompletion()throws Exception {
        addResources();int libraries=libraries(),staging=stagingCount();ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        java.io.OutputStream changed=new java.io.OutputStream(){boolean first=true;@Override public void write(int value){bytes.write(value);}@Override public void write(byte[] b,int off,int count){bytes.write(b,off,count);if(first){first=false;XsLibraryWrites.invalidate(source);}}};
        try{XsFullBackup.exportEncrypted(app,source,changed,PASSWORD);fail("expired export completed");}catch(IOException expected){}
        reject(bytes.toByteArray(),PASSWORD);assertEquals(libraries,libraries());assertEquals(2,media.list(source).size());assertEquals(staging,stagingCount());assertEquals(0,markerCount());
    }
    @Test public void startupRecoveryDeletesUnpublishedResourcesButPreservesACommittedResult()throws Exception {
        addResources();String pending="local:"+UUID.randomUUID();owned.add(pending);List<String> ids=new ArrayList<>();
        write(pending,()->ids.addAll(media.restoreCompleted(pending,Collections.singletonList(image(png)))));
        File orphan=media.completedFile(pending,ids.get(0));assertTrue(orphan.exists());File pendingMarker=marker(pending);
        File staleDir=new File(new File(app.getCacheDir(),"xs-full-backup"),UUID.randomUUID().toString());assertTrue(staleDir.mkdirs());File stale=new File(staleDir,"verified.payload");try(FileOutputStream out=new FileOutputStream(stale)){out.write(7);}
        XsFullBackup.recoverInterrupted(app);assertEquals(0,media.list(pending).size());assertFalse(orphan.exists());assertFalse(pendingMarker.exists());assertFalse(staleDir.exists());
        XsFullBackup.Prepared prepared=XsFullBackup.inspectEncrypted(app,source,new ByteArrayInputStream(export()),PASSWORD);String committed=XsFullBackup.restoreNewLibrary(app,prepared,null);owned.add(committed);File committedMarker=marker(committed);
        XsFullBackup.recoverInterrupted(app);assertFalse(committedMarker.exists());assertEquals(2,media.list(committed).size());
        for(XsMediaRepository.Asset asset:media.list(committed))assertTrue(media.completedFile(committed,asset.id).isFile());assertEquals(2,media.list(source).size());assertEquals(defaultLibrary,XsLocalSpaces.defaultLibrary(app));
    }
    private void addResources()throws Exception {
        VideoFileValidator.Info video=VideoFileValidator.validate(movie);
        write(source,()->media.restoreCompleted(source,Arrays.asList(image(png),new XsMediaRepository.RestoredAsset(POST,"VIDEO","video/mp4",movie.length(),hash(movie),"视频",2500L,video.durationMs,video.width,video.height,video.videoTracks,video.audioTracks,video.rotationDegrees,movie))));
    }
    private XsMediaRepository.RestoredAsset image(File file)throws Exception{return new XsMediaRepository.RestoredAsset(POST,"IMAGE","image/png",file.length(),hash(file),"图片 📷",2400L,0,0,0,0,0,0,file);}
    private byte[] export()throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();XsFullBackup.exportEncrypted(app,source,out,PASSWORD);return out.toByteArray();}
    private byte[] metadata()throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();write(source,()->XsLibraryBackup.exportLibrary(app,source,out));return out.toByteArray();}
    private static Map<String,Object> descriptor(File file)throws Exception {
        Map<String,Object> row=new LinkedHashMap<>();row.put("source_id",UUID.randomUUID().toString());row.put("post_url",POST);row.put("media_kind","IMAGE");row.put("mime","image/png");row.put("bytes",file.length());row.put("sha256",hash(file));row.put("category","");row.put("created_ms",1000L);
        for(String key:Arrays.asList("duration_ms","width","height","video_tracks","audio_tracks","rotation_degrees"))row.put(key,null);return row;
    }
    private static byte[] candidate(byte[] metadata,Map<String,Object> descriptor,File bytes,int copies)throws Exception {
        ByteArrayOutputStream json=new ByteArrayOutputStream();LibraryBackupCodec.write(json,new LibraryBackupCodec.Header(3,1,"asset"),Collections.singletonList(new LibraryBackupCodec.Record("assets",descriptor)));
        EncryptedLibraryBackup.AssetSource source=new EncryptedLibraryBackup.AssetSource(new String(json.toByteArray(),StandardCharsets.UTF_8),bytes.length(),hash(bytes),()->new FileInputStream(bytes));
        ByteArrayOutputStream plain=new ByteArrayOutputStream();EncryptedLibraryBackup.writePayload(plain,metadata,Collections.nCopies(copies,source));ByteArrayOutputStream encrypted=new ByteArrayOutputStream();EncryptedLibraryBackup.encrypt(new ByteArrayInputStream(plain.toByteArray()),encrypted,PASSWORD,plain.size());return encrypted.toByteArray();
    }
    private void reject(byte[] bytes,char[] password)throws Exception{try(XsFullBackup.Prepared ignored=XsFullBackup.inspectEncrypted(app,source,new ByteArrayInputStream(bytes),password)){fail("invalid backup accepted");}catch(IOException expected){}}
    private File marker(String target)throws Exception{File dir=new File(app.getFilesDir(),"xs-restore-pending");if(!dir.isDirectory())assertTrue(dir.mkdirs());File marker=new File(dir,target.substring(6)+".pending");assertTrue(marker.createNewFile());try(FileOutputStream out=new FileOutputStream(marker)){out.getFD().sync();}return marker;}
    private int markerCount(){File[] files=new File(app.getFilesDir(),"xs-restore-pending").listFiles();return files==null?0:files.length;}
    private int stagingCount(){File[] files=new File(app.getCacheDir(),"xs-full-backup").listFiles();return files==null?0:files.length;}
    private int libraries(){try(Cursor c=archive.libraries()){return c.getCount();}}
    private int allAssets(){try(Cursor c=media.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM assets",null)){assertTrue(c.moveToFirst());return c.getInt(0);}}
    private interface Mutation{void run()throws Exception;}
    private void write(String scope,Mutation mutation)throws Exception{CountDownLatch done=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();assertTrue(XsLibraryWrites.submit(scope,()->{try{mutation.run();}catch(Throwable problem){failure.set(problem);}finally{done.countDown();}}));assertTrue(done.await(20,TimeUnit.SECONDS));if(failure.get()!=null)throw new AssertionError(failure.get());}
    private static String hash(File file)throws Exception{try(InputStream in=new FileInputStream(file)){return hash(in);}}
    private static String hash(InputStream in)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] bytes=new byte[8192];int count;while((count=in.read(bytes))!=-1)digest.update(bytes,0,count);StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return hex.toString();}
}

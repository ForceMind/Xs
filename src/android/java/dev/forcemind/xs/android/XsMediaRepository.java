package dev.forcemind.xs.android;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import dev.forcemind.xs.core.MediaDownloadPolicy;
import dev.forcemind.xs.core.PostUrls;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** User-requested image downloads. Files live in files/, never WebView cache.
 * Work is foreground/process scoped; interrupted tasks require explicit restart.
 * No cookies, authorization headers, arbitrary URL fetching or video claims.
 */
public final class XsMediaRepository extends SQLiteOpenHelper {
    private static XsMediaRepository instance;
    private final File savedDir;
    private final File shareDir;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0L,
        TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16));

    public static synchronized XsMediaRepository get(Context context) {
        if (instance == null) instance = new XsMediaRepository(context.getApplicationContext());
        return instance;
    }

    private XsMediaRepository(Context context) {
        super(context, "xs-media.db", null, 1);
        savedDir = new File(context.getFilesDir(), "xs-saved-media");
        shareDir = new File(context.getFilesDir(), "xs-share-leases");
        if (!savedDir.isDirectory() && !savedDir.mkdirs()) throw new IllegalStateException("Media storage unavailable");
        if (!shareDir.isDirectory() && !shareDir.mkdirs()) throw new IllegalStateException("Share storage unavailable");
        getWritableDatabase().execSQL("UPDATE assets SET state='FAILED', error='Interrupted; retry restarts download', media_url=NULL WHERE state IN ('QUEUED','DOWNLOADING')");
        for (File file : files(savedDir)) if (file.getName().endsWith(".part")) file.delete();
        // A crash after atomic rename but before commit leaves an unregistered file.
        for (File file : files(savedDir)) {
            try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM assets WHERE filename=? AND state='COMPLETED'", new String[]{file.getName()})) {
                if (!c.moveToFirst()) file.delete();
            }
        }
        cleanupLeases();
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE assets (id TEXT PRIMARY KEY, library_id TEXT NOT NULL, post_url TEXT NOT NULL, media_url TEXT, state TEXT NOT NULL, bytes INTEGER NOT NULL DEFAULT 0, total INTEGER NOT NULL DEFAULT -1, mime TEXT, filename TEXT, sha256 TEXT, created_ms INTEGER NOT NULL, error TEXT, category TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX asset_library_time ON assets(library_id,created_ms DESC)");
        db.execSQL("CREATE TABLE leases (token TEXT PRIMARY KEY, filename TEXT NOT NULL, mime TEXT NOT NULL, bytes INTEGER NOT NULL, expires_ms INTEGER NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Media migration required");
    }

    public static final class Asset {
        public final String id, libraryId, postUrl, state, mime, filename, error, category;
        public final long bytes, total, createdMs;
        Asset(Cursor c) {
            id=c.getString(0); libraryId=c.getString(1); postUrl=c.getString(2); state=c.getString(3);
            mime=c.getString(4); filename=c.getString(5); bytes=c.getLong(6); total=c.getLong(7);
            createdMs=c.getLong(8); error=c.getString(9); category=c.getString(10);
        }
    }

    private static final String COLUMNS = "id,library_id,post_url,state,mime,filename,bytes,total,created_ms,error,category";
    public List<Asset> list(String libraryId) {
        requireLibrary(libraryId);
        List<Asset> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + COLUMNS + " FROM assets WHERE library_id=? ORDER BY created_ms DESC LIMIT 500", new String[]{libraryId})) {
            while(c.moveToNext()) result.add(new Asset(c));
        }
        return result;
    }
    public Asset find(String libraryId, String id) {
        requireLibrary(libraryId);
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + COLUMNS + " FROM assets WHERE library_id=? AND id=?", new String[]{libraryId,id})) {
            return c.moveToFirst() ? new Asset(c) : null;
        }
    }

    /** Only call after an explicit native selection of this post's image candidate. */
    public String enqueueImage(String libraryId, String postUrl, String mediaUrl) {
        requireLibrary(libraryId);
        final String safeUrl = MediaDownloadPolicy.validateImage(postUrl, mediaUrl);
        if(savedDir.getUsableSpace()<MediaDownloadPolicy.MAX_BYTES*2) throw new IllegalStateException("Insufficient free space");
        final String id=UUID.randomUUID().toString();
        XsLibraryWrites.Ticket ticket=XsLibraryWrites.ticket(libraryId);
        boolean queued=XsLibraryWrites.submit(ticket,()->{
            synchronized(XsMediaRepository.this) {
                boolean full;
                try (Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM assets WHERE state IN ('QUEUED','DOWNLOADING')", null)) {
                    c.moveToFirst(); full=c.getInt(0)>=16;
                }
                ContentValues v=new ContentValues();
                v.put("id",id); v.put("library_id",libraryId); v.put("post_url",PostUrls.canonicalLink(postUrl));
                v.put("state",full?"FAILED":"QUEUED"); v.put("created_ms",System.currentTimeMillis());
                if(full)v.put("error","Download queue full; select the image again later");
                getWritableDatabase().insertOrThrow("assets",null,v);
                if(full)return;
                try { worker.execute(() -> download(id, libraryId, postUrl, safeUrl, ticket)); }
                catch (RuntimeException rejected) { fail(libraryId,id,"Download queue full"); }
            }
        });
        if(!queued)throw new IllegalStateException("Download write queue full");
        return id;
    }

    /** Retry is a new request using a newly confirmed candidate; signed URLs are not retained on disk. */
    public void retryImage(String libraryId, String id, String confirmedMediaUrl) {
        Asset asset=find(libraryId,id);
        if(asset==null || !("FAILED".equals(asset.state)||"CANCELED".equals(asset.state))) throw new IllegalArgumentException("Select a failed task");
        enqueueImage(libraryId,asset.postUrl,confirmedMediaUrl);
        delete(libraryId,id);
    }

    public void cancel(String libraryId, String id) {
        mutate(libraryId,()->cancelNow(libraryId,id));
    }
    private synchronized void cancelNow(String libraryId, String id) {
        Asset asset=find(libraryId,id);
        if(asset==null||"COMPLETED".equals(asset.state)) return;
        ContentValues v=new ContentValues(); v.put("state","CANCELED"); v.putNull("media_url"); v.put("error","Canceled");
        getWritableDatabase().update("assets",v,"library_id=? AND id=?",new String[]{libraryId,id});
        new File(savedDir,id+".part").delete();
    }
    public void delete(String libraryId, String id) {
        mutate(libraryId,()->deleteNow(libraryId,id));
    }
    private synchronized void deleteNow(String libraryId, String id) {
        Asset asset=find(libraryId,id); if(asset==null)return;
        getWritableDatabase().delete("assets","library_id=? AND id=?",new String[]{libraryId,id});
        new File(savedDir,id+".part").delete();
        if(asset.filename!=null) new File(savedDir,asset.filename).delete();
        // Existing share copies remain readable until their lease expires.
    }
    /** Call inside XsLibraryWrites.invalidateAndSubmit when explicitly deleting a library. */
    public synchronized void eraseLibrary(String libraryId) {
        requireLibrary(libraryId);
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,filename FROM assets WHERE library_id=?",new String[]{libraryId})) {
            while(c.moveToNext()) {new File(savedDir,c.getString(0)+".part").delete();if(!c.isNull(1))new File(savedDir,c.getString(1)).delete();}
        }
        getWritableDatabase().delete("assets","library_id=?",new String[]{libraryId});
    }
    public void setCategory(String libraryId,String id,String category) {
        if(category==null||category.length()>120)throw new IllegalArgumentException("Category too long");
        mutate(libraryId,()->{
            synchronized(XsMediaRepository.this) {
                ContentValues v=new ContentValues();v.put("category",category.trim());
                getWritableDatabase().update("assets",v,"library_id=? AND id=?",new String[]{libraryId,id});
            }
        });
    }
    private static void mutate(String libraryId,Runnable write) {
        requireLibrary(libraryId);
        if(!XsLibraryWrites.submit(libraryId,write))throw new IllegalStateException("Library write queue full");
    }
    public synchronized File completedFile(String libraryId,String id) {
        Asset a=find(libraryId,id);
        if(a==null||!"COMPLETED".equals(a.state)||a.filename==null)throw new IllegalArgumentException("Completed file required");
        File f=new File(savedDir,a.filename);
        if(!f.isFile()||f.length()!=a.bytes)throw new IllegalStateException("Saved file unavailable");
        return f;
    }
    private synchronized boolean active(String id) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM assets WHERE id=? AND state IN ('QUEUED','DOWNLOADING')",new String[]{id})) {return c.moveToFirst();}
    }
    private void download(String id,String libraryId,String postUrl,String mediaUrl,XsLibraryWrites.Ticket ticket) {
        File part=new File(savedDir,id+".part"); HttpURLConnection connection=null; boolean completionQueued=false;
        final long deadline=android.os.SystemClock.elapsedRealtime()+120000;
        try {
            if(!active(id)||!XsLibraryWrites.isCurrent(ticket)){fail(libraryId,id,"Observation context changed; select image again to restart");return;}
            String current=mediaUrl;
            for(int redirects=0;;redirects++) {
                if(android.os.SystemClock.elapsedRealtime()>deadline||!XsLibraryWrites.isCurrent(ticket))throw new IOException("Download context expired");
                MediaDownloadPolicy.validateImage(postUrl,current);
                connection=(HttpURLConnection)new URL(current).openConnection();
                connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(15000);connection.setReadTimeout(15000);
                connection.setRequestProperty("Accept","image/jpeg,image/png,image/webp,image/gif");
                int status=connection.getResponseCode();
                if(status>=300&&status<400) {
                    if(redirects>=MediaDownloadPolicy.MAX_REDIRECTS)throw new IOException("Too many redirects");
                    String location=connection.getHeaderField("Location");
                    if(location==null)throw new IOException("Invalid redirect");
                    String resolved=new URL(new URL(current),location).toExternalForm();
                    MediaDownloadPolicy.validateImage(postUrl,resolved);
                    connection.disconnect();connection=null;current=resolved;continue;
                }
                if(status!=200)throw new IOException("Server did not provide an image");
                break;
            }
            long declared=connection.getContentLengthLong();
            if(declared==0||declared>MediaDownloadPolicy.MAX_BYTES)throw new IOException("Image size is not allowed");
            String encoding=connection.getContentEncoding();
            if(encoding!=null&&!"identity".equalsIgnoreCase(encoding))throw new IOException("Encoded response is unsupported");
            XsLibraryWrites.submit(ticket,()->{
                synchronized(XsMediaRepository.this) {
                    ContentValues downloading=new ContentValues();downloading.put("state","DOWNLOADING");downloading.put("total",declared);
                    getWritableDatabase().update("assets",downloading,"id=? AND library_id=? AND state='QUEUED'",new String[]{id,libraryId});
                }
            });
            MessageDigest digest=MessageDigest.getInstance("SHA-256"); long bytes=0,lastProgress=0; byte[] prefix=new byte[16];int prefixLength=0;
            try(InputStream input=connection.getInputStream(); FileOutputStream output=new FileOutputStream(part)) {
                byte[] buffer=new byte[16384];int n;
                while((n=input.read(buffer))!=-1) {
                    if(!active(id)||!XsLibraryWrites.isCurrent(ticket)||android.os.SystemClock.elapsedRealtime()>deadline)throw new IOException("Canceled or expired");
                    bytes+=n;if(bytes>MediaDownloadPolicy.MAX_BYTES||savedDir.getUsableSpace()<1048576)throw new IOException("Image exceeds available storage");
                    int copy=Math.min(n,prefix.length-prefixLength);if(copy>0){System.arraycopy(buffer,0,prefix,prefixLength,copy);prefixLength+=copy;}
                    output.write(buffer,0,n);digest.update(buffer,0,n);
                    if(bytes-lastProgress>=262144){progress(ticket,libraryId,id,bytes);lastProgress=bytes;}
                }
                output.getFD().sync();
            }
            if(!MediaDownloadPolicy.completeLength(declared,bytes))throw new IOException("Incomplete image");
            String extension=MediaDownloadPolicy.imageExtension(prefix,connection.getContentType());
            if(!MediaDownloadPolicy.hasCompleteEnvelope(part,extension))throw new IOException("Incomplete image file");
            validateBitmap(part);
            String hash=hex(digest.digest());String filename=id+"."+extension; File completed=new File(savedDir,filename);
            final long completedBytes=bytes;
            completionQueued=XsLibraryWrites.submit(ticket,()->{
                synchronized(XsMediaRepository.this) {
                    try {
                        // Same serial barrier as library erasure, checked at actual write time.
                        if(!active(id))return;
                        if(!part.renameTo(completed))throw new IOException("Could not finalize image");
                        ContentValues v=new ContentValues();v.put("state","COMPLETED");v.put("bytes",completedBytes);v.put("total",completedBytes);
                        v.put("filename",filename);v.put("mime",mime(extension));v.put("sha256",hash);v.putNull("media_url");v.putNull("error");
                        getWritableDatabase().update("assets",v,"id=? AND library_id=?",new String[]{id,libraryId});
                    } catch(Exception failure) {completed.delete();fail(libraryId,id,"Could not finalize image");}
                    finally {part.delete();}
                }
            },()->{part.delete();fail(libraryId,id,"Context changed before completion; select image again to restart");});
            if(!completionQueued)fail(libraryId,id,"Write queue unavailable; restart image download");
        } catch(Exception failure) {fail(libraryId,id,safeFailure(failure));}
        finally {if(connection!=null)connection.disconnect();if(!completionQueued)part.delete();}
    }
    private static void validateBitmap(File file)throws IOException {
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),bounds);
        if(bounds.outWidth<=0||bounds.outHeight<=0||(long)bounds.outWidth*bounds.outHeight>16000000)throw new IOException("Image dimensions are unsupported");
        BitmapFactory.Options decode=new BitmapFactory.Options();decode.inPreferredConfig=Bitmap.Config.RGB_565;
        Bitmap bitmap=BitmapFactory.decodeFile(file.getPath(),decode);if(bitmap==null)throw new IOException("Invalid image");bitmap.recycle();
    }
    private void progress(XsLibraryWrites.Ticket ticket,String libraryId,String id,long bytes) {
        XsLibraryWrites.submit(ticket,()->{
            synchronized(XsMediaRepository.this) {
                ContentValues v=new ContentValues();v.put("bytes",bytes);
                getWritableDatabase().update("assets",v,"id=? AND library_id=? AND state='DOWNLOADING'",new String[]{id,libraryId});
            }
        });
    }
    private void fail(String libraryId,String id,String message) {
        // A status-only update in the current generation cannot recreate erased rows.
        XsLibraryWrites.submit(libraryId,()->{
            synchronized(XsMediaRepository.this) {
                ContentValues v=new ContentValues();v.put("state","FAILED");v.put("error",message);v.putNull("media_url");
                getWritableDatabase().update("assets",v,"id=? AND library_id=? AND state IN ('QUEUED','DOWNLOADING')",new String[]{id,libraryId});
            }
        });
    }
    private static String safeFailure(Exception error){return error instanceof IllegalArgumentException?"Unsupported image response":"Download interrupted or invalid image; select the image to retry from the start";}
    private static String hex(byte[] value){StringBuilder s=new StringBuilder();for(byte b:value)s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();}
    private static String mime(String ext){return "jpg".equals(ext)?"image/jpeg":"image/"+ext;}
    private static File[] files(File dir){File[] files=dir.listFiles();return files==null?new File[0]:files;}
    private static void requireLibrary(String id){if(id==null||id.trim().isEmpty()||id.length()>200)throw new IllegalArgumentException("Library required");}

    public static final class Lease {
        public final String token,filename,mime;public final long bytes;
        Lease(String token,String filename,String mime,long bytes){this.token=token;this.filename=filename;this.mime=mime;this.bytes=bytes;}
    }
    public synchronized Lease lease(String libraryId,String assetId)throws IOException {
        cleanupLeases();Asset a=find(libraryId,assetId);File source=completedFile(libraryId,assetId);
        long used=0;for(File f:files(shareDir))used+=f.length();
        if(used+source.length()>200L*1024*1024||shareDir.getUsableSpace()<source.length()+1048576)throw new IOException("Share storage full");
        String token=UUID.randomUUID().toString();File copy=new File(shareDir,token);
        try(FileInputStream input=new FileInputStream(source);FileOutputStream output=new FileOutputStream(copy)){
            byte[] b=new byte[16384];int n;while((n=input.read(b))!=-1)output.write(b,0,n);output.getFD().sync();
        }catch(IOException failure){copy.delete();throw failure;}
        ContentValues v=new ContentValues();v.put("token",token);v.put("filename",a.filename);v.put("mime",a.mime);v.put("bytes",a.bytes);v.put("expires_ms",System.currentTimeMillis()+24*60*60*1000L);
        try{getWritableDatabase().insertOrThrow("leases",null,v);}catch(RuntimeException failure){copy.delete();throw failure;}
        return new Lease(token,a.filename,a.mime,a.bytes);
    }
    /** Roll back only a copy created by the current, not-yet-launched share attempt. */
    synchronized void discardLease(Lease lease) {
        getWritableDatabase().delete("leases","token=?",new String[]{lease.token});
        new File(shareDir,lease.token).delete();
    }
    public synchronized Lease resolveLease(String token,String filename) {
        if(token==null||!token.matches("[0-9a-f-]{36}")||filename==null||!filename.matches("[0-9a-f-]{36}\\.(jpg|png|gif|webp)"))throw new IllegalArgumentException("Invalid file grant");
        try(Cursor c=getReadableDatabase().rawQuery("SELECT mime,bytes FROM leases WHERE token=? AND filename=? AND expires_ms>?",new String[]{token,filename,Long.toString(System.currentTimeMillis())})){
            if(!c.moveToFirst())throw new IllegalArgumentException("File grant expired");
            File f=new File(shareDir,token);if(!f.isFile()||f.length()!=c.getLong(1))throw new IllegalArgumentException("Share file unavailable");
            return new Lease(token,filename,c.getString(0),c.getLong(1));
        }
    }
    public synchronized File leaseFile(Lease lease){return new File(shareDir,lease.token);}
    public synchronized void cleanupLeases(){
        long now=System.currentTimeMillis();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT token FROM leases WHERE expires_ms<=?",new String[]{Long.toString(now)})){while(c.moveToNext())new File(shareDir,c.getString(0)).delete();}
        getWritableDatabase().delete("leases","expires_ms<=?",new String[]{Long.toString(now)});
        for(File f:files(shareDir))try(Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM leases WHERE token=?",new String[]{f.getName()})){if(!c.moveToFirst())f.delete();}
    }
}

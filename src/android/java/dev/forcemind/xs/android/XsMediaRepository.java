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

/** User-requested image and standard MP4 downloads. Files live in files/, never WebView cache.
 * User-confirmed transfers persist encrypted references and run under Android JobScheduler.
 * Interrupted transfers restart at byte zero; no unverified Range append is attempted.
 * No cookies, authorization headers, arbitrary URL fetching, manifests or DRM access.
 */
public final class XsMediaRepository extends SQLiteOpenHelper {
    private static XsMediaRepository instance;
    private final File savedDir;
    private final File shareDir;
    private final Scheduler scheduler;
    private final File privateRoot;
    private final java.util.Map<String,String> activeRuns=new java.util.HashMap<>();
    private final java.util.Set<String> stoppedRuns=new java.util.HashSet<>();
    private final java.util.Map<String,HttpURLConnection> runningConnections=new java.util.HashMap<>();
    interface Scheduler {
        boolean schedule(String id,String library,int jobId,long estimate,boolean userInitiated);
        void cancel(int jobId);
        default boolean isPending(int jobId){return true;}
    }
    interface ConnectionFactory { HttpURLConnection open(URL url) throws IOException; }
    private final ConnectionFactory connections;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0L,
        TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16));

    public static synchronized XsMediaRepository get(Context context) {
        if (instance == null) instance = new XsMediaRepository(context.getApplicationContext());
        return instance;
    }

    /** Startup reconciles prior explicit requests; missing jobs require a new user retry. */
    public static void recoverScheduled(Context context) {get(context).reconcileScheduled();}
    private synchronized void reconcileScheduled() {
        if(scheduler==null)return;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,library_id,job_id,url_cipher,state FROM assets WHERE job_id IS NOT NULL",null)) {
            while(c.moveToNext()) {
                String id=c.getString(0),library=c.getString(1),state=c.getString(4);int job=c.getInt(2);
                if(activeRuns.containsKey(id))continue;
                if(XsLibraryWrites.isDeleted(library)||c.isNull(3)||state.equals("COMPLETED")||state.equals("CANCELED")||state.equals("FAILED")) {scheduler.cancel(job);continue;}
                if(!scheduler.isPending(job)) {
                    ContentValues v=new ContentValues();v.put("state","PAUSED");v.putNull("run_token");v.put("error","Previous Android job is no longer scheduled; tap retry to request a new transfer");
                    getWritableDatabase().update("assets",v,"id=? AND library_id=?",new String[]{id,library});
                }
            }
        }
    }

    private XsMediaRepository(Context context) {
        this(context, url -> (HttpURLConnection)url.openConnection(),new XsDownloadScheduler(context));
    }

    /** Instance-only transport seam for same-package synthetic tests; URL policy is unchanged. */
    XsMediaRepository(Context context, ConnectionFactory connections) {
        this(context,connections,null);
    }
    XsMediaRepository(Context context, ConnectionFactory connections,Scheduler scheduler) {
        this(context,connections,scheduler,"xs-media.db","");
    }
    XsMediaRepository(Context context,ConnectionFactory connections,Scheduler scheduler,String databaseName,String storageSuffix) {
        super(context, databaseName, null, 3);
        if(databaseName==null||!databaseName.matches("xs-media(?:-test-[a-z0-9-]{1,64})?\\.db")||storageSuffix==null||!storageSuffix.matches("(?:-[a-z0-9-]{1,64})?"))throw new IllegalArgumentException("Invalid private media store");
        if(connections==null)throw new IllegalArgumentException("Connection factory required");
        this.connections=connections;
        this.scheduler=scheduler;
        privateRoot=context.getDataDir();
        savedDir = new File(context.getFilesDir(), "xs-saved-media"+storageSuffix);
        shareDir = new File(context.getFilesDir(), "xs-share-leases"+storageSuffix);
        ensureStorageDirectory(savedDir);
        ensureStorageDirectory(shareDir);
        // A fresh process never calls stale work completed or appends an unverifiable partial file.
        getWritableDatabase().execSQL("UPDATE assets SET state=CASE WHEN url_cipher IS NULL THEN 'FAILED' ELSE 'PAUSED' END, error='Interrupted; next permitted run restarts at byte zero', run_token=NULL, bytes=0, media_url=NULL WHERE state IN ('QUEUED','DOWNLOADING')");
        for (File file : files(savedDir)) if (file.getName().endsWith(".part")) file.delete();
        // A crash after atomic rename but before commit leaves an unregistered file.
        for (File file : files(savedDir)) {
            try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM assets WHERE filename=? AND state='COMPLETED'", new String[]{file.getName()})) {
                if (!c.moveToFirst()) file.delete();
            }
        }
        cleanupLeases();
    }

    void stopWorkerForTests() throws InterruptedException {
        worker.shutdownNow();
        if(!worker.awaitTermination(10,TimeUnit.SECONDS))throw new IllegalStateException("Test transfer worker did not stop");
    }
    void workerMarkerForTests(Runnable marker) { worker.execute(marker); }

    @Override public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.execSQL("PRAGMA synchronous=FULL");
    }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE assets (id TEXT PRIMARY KEY, library_id TEXT NOT NULL, post_url TEXT NOT NULL, media_url TEXT, state TEXT NOT NULL, bytes INTEGER NOT NULL DEFAULT 0, total INTEGER NOT NULL DEFAULT -1, mime TEXT, filename TEXT, sha256 TEXT, created_ms INTEGER NOT NULL, error TEXT, category TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX asset_library_time ON assets(library_id,created_ms DESC)");
        db.execSQL("CREATE TABLE leases (token TEXT PRIMARY KEY, filename TEXT NOT NULL, mime TEXT NOT NULL, bytes INTEGER NOT NULL, expires_ms INTEGER NOT NULL)");
        migrateVideo(db);
        migrateDurable(db);
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if(oldVersion<1||newVersion!=3)throw new IllegalStateException("Media migration required");
        if(oldVersion<2)migrateVideo(db);
        if(oldVersion<3)migrateDurable(db);
    }

    private static void migrateVideo(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE assets ADD COLUMN media_kind TEXT NOT NULL DEFAULT 'IMAGE'");
        db.execSQL("ALTER TABLE assets ADD COLUMN duration_ms INTEGER");
        db.execSQL("ALTER TABLE assets ADD COLUMN width INTEGER");
        db.execSQL("ALTER TABLE assets ADD COLUMN height INTEGER");
        db.execSQL("ALTER TABLE assets ADD COLUMN video_tracks INTEGER");
        db.execSQL("ALTER TABLE assets ADD COLUMN audio_tracks INTEGER");
        db.execSQL("ALTER TABLE assets ADD COLUMN rotation_degrees INTEGER");
    }

    private static void migrateDurable(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE assets ADD COLUMN url_cipher TEXT");
        db.execSQL("ALTER TABLE assets ADD COLUMN job_id INTEGER");
        db.execSQL("ALTER TABLE assets ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE assets ADD COLUMN run_token TEXT");
        db.execSQL("ALTER TABLE assets ADD COLUMN last_stop_reason INTEGER");
        db.execSQL("CREATE UNIQUE INDEX asset_job_id ON assets(job_id) WHERE job_id IS NOT NULL");
        db.execSQL("UPDATE assets SET media_url=NULL");
    }
    private static String secretScope(String id,String library,String postUrl,boolean video) {
        return id+"|"+library+"|"+PostUrls.canonicalLink(postUrl)+"|"+(video?"VIDEO":"IMAGE");
    }
    private int allocateJobId() {
        java.security.SecureRandom random=new java.security.SecureRandom();
        for(int n=0;n<16;n++) {
            int candidate=10000+random.nextInt(Integer.MAX_VALUE-10000);
            try(Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM assets WHERE job_id=?",new String[]{Integer.toString(candidate)})){if(!c.moveToFirst())return candidate;}
        }
        throw new IllegalStateException("Unable to allocate download job");
    }

    public static final class Asset {
        public final String id, libraryId, postUrl, state, mime, filename, error, category,mediaKind;
        public final long bytes, total, createdMs,durationMs;
        public final int width,height,videoTracks,audioTracks,rotationDegrees;
        Asset(Cursor c) {
            id=c.getString(0); libraryId=c.getString(1); postUrl=c.getString(2); state=c.getString(3);
            mime=c.getString(4); filename=c.getString(5); bytes=c.getLong(6); total=c.getLong(7);
            createdMs=c.getLong(8); error=c.getString(9); category=c.getString(10);mediaKind=c.getString(11);
            durationMs=c.isNull(12)?0:c.getLong(12);width=c.isNull(13)?0:c.getInt(13);height=c.isNull(14)?0:c.getInt(14);
            videoTracks=c.isNull(15)?0:c.getInt(15);audioTracks=c.isNull(16)?0:c.getInt(16);rotationDegrees=c.isNull(17)?0:c.getInt(17);
        }
    }

    private static final String COLUMNS = "id,library_id,post_url,state,mime,filename,bytes,total,created_ms,error,category,media_kind,duration_ms,width,height,video_tracks,audio_tracks,rotation_degrees";
    public List<Asset> list(String libraryId) {
        requireLibrary(libraryId);
        List<Asset> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + COLUMNS + " FROM assets WHERE library_id=? ORDER BY created_ms DESC LIMIT 500", new String[]{libraryId})) {
            while(c.moveToNext()) result.add(new Asset(c));
        }
        return result;
    }
    public List<Asset> search(String library,String keyword,String kind,String state,int offset,int limit) {
        requireLibrary(library);if(offset<0||limit<1||limit>100)throw new IllegalArgumentException("Invalid resource page");
        if(kind!=null&&!kind.equals("IMAGE")&&!kind.equals("VIDEO"))throw new IllegalArgumentException("Invalid resource kind");
        if(state!=null&&!java.util.Arrays.asList("COMPLETED","ACTIVE","FAILED","CANCELED").contains(state))throw new IllegalArgumentException("Invalid resource state");
        if(keyword!=null&&keyword.length()>256)throw new IllegalArgumentException("Query too long");
        StringBuilder sql=new StringBuilder("SELECT "+COLUMNS+" FROM assets WHERE library_id=?");List<String> args=new ArrayList<>();args.add(library);
        if(kind!=null){sql.append(" AND media_kind=?");args.add(kind);}
        if("ACTIVE".equals(state))sql.append(" AND state IN ('QUEUED','DOWNLOADING','WAITING_NETWORK','PAUSED','RETRY_WAIT')");
        else if(state!=null){sql.append(" AND state=?");args.add(state);}
        if(keyword!=null&&!keyword.trim().isEmpty()) {
            String pattern="%"+keyword.trim().replace("!","!!").replace("%","!%").replace("_","!_")+"%";
            sql.append(" AND (post_url LIKE ? ESCAPE '!' OR category LIKE ? ESCAPE '!' OR state LIKE ? ESCAPE '!' OR mime LIKE ? ESCAPE '!')");for(int i=0;i<4;i++)args.add(pattern);
        }
        sql.append(" ORDER BY created_ms DESC,id DESC LIMIT ? OFFSET ?");args.add(Integer.toString(limit));args.add(Integer.toString(offset));
        List<Asset> result=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery(sql.toString(),args.toArray(new String[0]))){while(c.moveToNext())result.add(new Asset(c));}return result;
    }
    public Asset find(String libraryId, String id) {
        requireLibrary(libraryId);
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + COLUMNS + " FROM assets WHERE library_id=? AND id=?", new String[]{libraryId,id})) {
            return c.moveToFirst() ? new Asset(c) : null;
        }
    }

    /** Only call after an explicit native selection of this post's image candidate. */
    public String enqueueImage(String libraryId, String postUrl, String mediaUrl) {
        return enqueue(libraryId,postUrl,mediaUrl,false);
    }
    /** Requires current-post selection and explicit confirmation that saving is permitted. */
    public String enqueueVideo(String libraryId,String postUrl,String mediaUrl) {
        return enqueue(libraryId,postUrl,mediaUrl,true);
    }
    private String enqueue(String libraryId,String postUrl,String mediaUrl,boolean video) {
        requireLibrary(libraryId);
        final String safeUrl=validateUrl(postUrl,mediaUrl,video);
        final long maxBytes=video?MediaDownloadPolicy.MAX_VIDEO_BYTES:MediaDownloadPolicy.MAX_BYTES;
        if(savedDir.getUsableSpace()<maxBytes*2) throw new IllegalStateException("Insufficient free space");
        final String id=UUID.randomUUID().toString();
        XsLibraryWrites.Ticket ticket=XsLibraryWrites.ticket(libraryId);
        boolean queued=XsLibraryWrites.submit(ticket,()->{
            synchronized(XsMediaRepository.this) {
                boolean full;
                try (Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM assets WHERE state IN ('QUEUED','DOWNLOADING','PAUSED','RETRY_WAIT')", null)) {
                    c.moveToFirst(); full=c.getInt(0)>=16;
                }
                ContentValues v=new ContentValues();
                v.put("id",id); v.put("library_id",libraryId); v.put("post_url",PostUrls.canonicalLink(postUrl));
                v.put("state",full?"FAILED":"QUEUED"); v.put("created_ms",System.currentTimeMillis());v.put("media_kind",video?"VIDEO":"IMAGE");
                int jobId=allocateJobId();v.put("job_id",jobId);
                if(full)v.put("error","Download queue full; select the media again later");
                else try {v.put("url_cipher",XsDownloadSecrets.seal(safeUrl,secretScope(id,libraryId,postUrl,video)));}
                catch(java.security.GeneralSecurityException unavailable){v.put("state","FAILED");v.put("error","Secure download reference unavailable; reselect media");full=true;}
                getWritableDatabase().insertOrThrow("assets",null,v);
                if(full)return;
                scheduleStored(libraryId,id,jobId,maxBytes,true);

            }
        });
        if(!queued)throw new IllegalStateException("Download write queue full");
        return id;
    }

    private void scheduleStored(String library,String id,int jobId,long estimate,boolean userInitiated) {
        if(scheduler==null) {runScheduled(library,id,()->{});return;}
        if(!scheduler.schedule(id,library,jobId,estimate,userInitiated))schedulingRejected(library,id,"Android rejected background scheduling; retry from the visible app");
    }
    /** Explicit user retry reuses the encrypted candidate and always restarts at byte zero. */
    public synchronized void retryStored(String library,String id) {
        requireLibrary(library);
        if(activeRuns.containsKey(id))throw new IllegalStateException("Wait for the interrupted transfer to stop before retrying");
        try(Cursor candidate=getReadableDatabase().rawQuery("SELECT state,url_cipher FROM assets WHERE library_id=? AND id=?",new String[]{library,id})) {
            if(!candidate.moveToFirst()||candidate.isNull(1)||!java.util.Arrays.asList("FAILED","PAUSED","RETRY_WAIT").contains(candidate.getString(0)))throw new IllegalStateException("Select a stopped task or reselect media at its source");
        }
        mutate(library,()->{
            synchronized(XsMediaRepository.this) {
                Asset asset=find(library,id);
                if(asset==null||activeRuns.containsKey(id)||!java.util.Arrays.asList("FAILED","PAUSED","RETRY_WAIT").contains(asset.state))return;
                try(Cursor c=getReadableDatabase().rawQuery("SELECT url_cipher,job_id FROM assets WHERE library_id=? AND id=?",new String[]{library,id})) {
                    if(!c.moveToFirst()||c.isNull(0))throw new IllegalStateException("Select media again at its source");
                    boolean video="VIDEO".equals(asset.mediaKind);
                    validateUrl(asset.postUrl,XsDownloadSecrets.open(c.getString(0),secretScope(id,library,asset.postUrl,video)),video);
                    ContentValues v=new ContentValues();v.put("state","QUEUED");v.put("bytes",0);v.put("attempt_count",0);v.putNull("run_token");v.putNull("error");
                    getWritableDatabase().update("assets",v,"library_id=? AND id=?",new String[]{library,id});
                    scheduleStored(library,id,c.getInt(1),video?MediaDownloadPolicy.MAX_VIDEO_BYTES:MediaDownloadPolicy.MAX_BYTES,true);
                }catch(java.security.GeneralSecurityException invalid){schedulingRejected(library,id,"Saved media reference unavailable; select the media again");}
            }
        });
    }
    /** Invoked only by the protected system job or the instance-local synthetic scheduler. */
    boolean runScheduled(String library,String id,Runnable finished) {
        if(XsLibraryWrites.isDeleted(library))return false;
        XsLibraryWrites.Ticket ticket;
        try{ticket=XsLibraryWrites.ticket(library);}catch(IllegalStateException deleted){return false;}
        return XsLibraryWrites.submit(ticket,()->{
            synchronized(XsMediaRepository.this) {
                Asset asset=find(library,id);
                if(asset==null||activeRuns.containsKey(id)||!("QUEUED".equals(asset.state)||"PAUSED".equals(asset.state)||"RETRY_WAIT".equals(asset.state))) {finished.run();return;}
                try(Cursor c=getReadableDatabase().rawQuery("SELECT url_cipher,attempt_count FROM assets WHERE library_id=? AND id=?",new String[]{library,id})) {
                    c.moveToFirst();boolean video="VIDEO".equals(asset.mediaKind);
                    if(c.isNull(0)||c.getInt(1)>=3){schedulingRejected(library,id,"Retry limit reached or source unavailable; retry from the visible app");finished.run();return;}
                    String url=validateUrl(asset.postUrl,XsDownloadSecrets.open(c.getString(0),secretScope(id,library,asset.postUrl,video)),video);
                    String run=UUID.randomUUID().toString();
                    ContentValues v=new ContentValues();v.put("state","QUEUED");v.put("bytes",0);v.put("total",-1);v.put("run_token",run);v.put("attempt_count",c.getInt(1)+1);v.putNull("error");
                    getWritableDatabase().update("assets",v,"id=? AND library_id=?",new String[]{id,library});
                    activeRuns.put(id,run);
                    try {worker.execute(()->{
                        download(id,library,asset.postUrl,url,ticket,video,run);
                        Runnable done=()->{synchronized(XsMediaRepository.this){if(run.equals(activeRuns.get(id)))activeRuns.remove(id);stoppedRuns.remove(run);runningConnections.remove(run);}finished.run();};
                        try{if(!XsLibraryWrites.submit(library,done))done.run();}catch(IllegalStateException deleted){done.run();}
                    });}catch(RuntimeException rejected){activeRuns.remove(id);schedulingRejected(library,id,"Download queue unavailable; retry later");finished.run();}
                }catch(Exception invalid){schedulingRejected(library,id,"Saved media reference unavailable; select the media again");finished.run();}
            }
        },finished);
    }
    void schedulingRejected(String library,String id,String message) {
        if(XsLibraryWrites.isDeleted(library))return;
        try{XsLibraryWrites.submit(library,()->{synchronized(XsMediaRepository.this){
            ContentValues v=new ContentValues();v.put("state","FAILED");v.putNull("run_token");v.put("error",message);
            getWritableDatabase().update("assets",v,"library_id=? AND id=? AND state IN ('QUEUED','DOWNLOADING','PAUSED','RETRY_WAIT')",new String[]{library,id});
        }});}catch(IllegalStateException deletedOrUnavailable){/* Tombstone may win between the check and ticket acquisition. */}
    }
    void pauseScheduled(String library,String id,int reason) {
        stopTransfer(id);
        mutate(library,()->{
            synchronized(XsMediaRepository.this) {
                stopTransfer(id);
                ContentValues v=new ContentValues();v.put("state","PAUSED");v.put("last_stop_reason",reason);v.putNull("run_token");v.put("error","Android paused transfer; the next permitted run restarts at byte zero");
                getWritableDatabase().update("assets",v,"library_id=? AND id=? AND state IN ('QUEUED','DOWNLOADING','RETRY_WAIT')",new String[]{library,id});
                deleteParts(id);
            }
        });
    }
    boolean shouldRetry(String library,String id) {if(XsLibraryWrites.isDeleted(library))return false;Asset a=find(library,id);return a!=null&&(a.state.equals("RETRY_WAIT")||a.state.equals("PAUSED"));}
    private void retryNetwork(String library,String id,String run) {
        if(XsLibraryWrites.isDeleted(library))return;
        mutate(library,()->{synchronized(XsMediaRepository.this){
            ContentValues v=new ContentValues();v.put("state","RETRY_WAIT");v.putNull("run_token");v.put("error","Network interrupted; Android will retry from byte zero within its scheduling limits");
            getWritableDatabase().update("assets",v,"library_id=? AND id=? AND run_token=? AND attempt_count<3 AND state IN ('QUEUED','DOWNLOADING')",new String[]{library,id,run});
        }});
    }

    /** Retry replaces the request using a newly confirmed candidate; stored URLs remain encrypted. */
    public void retryImage(String libraryId, String id, String confirmedMediaUrl) {
        Asset asset=find(libraryId,id);
        if(asset==null || !"IMAGE".equals(asset.mediaKind) || !("FAILED".equals(asset.state)||"CANCELED".equals(asset.state))) throw new IllegalArgumentException("Select a failed task");
        enqueueImage(libraryId,asset.postUrl,confirmedMediaUrl);
        delete(libraryId,id);
    }

    public void retryVideo(String libraryId,String id,String confirmedMediaUrl) {
        Asset asset=find(libraryId,id);
        if(asset==null||!"VIDEO".equals(asset.mediaKind)||!("FAILED".equals(asset.state)||"CANCELED".equals(asset.state)))throw new IllegalArgumentException("Select a failed video task");
        enqueueVideo(libraryId,asset.postUrl,confirmedMediaUrl);delete(libraryId,id);
    }

    public void cancel(String libraryId, String id) {
        stopTransfer(id);
        mutate(libraryId,()->cancelNow(libraryId,id));
    }
    private synchronized void cancelNow(String libraryId, String id) {
        stopTransfer(id);
        Asset asset=find(libraryId,id);
        if(asset==null||"COMPLETED".equals(asset.state)) return;
        ContentValues v=new ContentValues(); v.put("state","CANCELED"); v.putNull("media_url");v.putNull("url_cipher");v.putNull("run_token"); v.put("error","Canceled");
        getWritableDatabase().update("assets",v,"library_id=? AND id=?",new String[]{libraryId,id});
        deleteParts(id);cancelJob(libraryId,id);
    }
    private void cancelJob(String library,String id) {
        if(scheduler==null)return;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT job_id FROM assets WHERE library_id=? AND id=?",new String[]{library,id})){if(c.moveToFirst()&&!c.isNull(0))scheduler.cancel(c.getInt(0));}
    }
    public void delete(String libraryId, String id) {
        mutate(libraryId,()->deleteNow(libraryId,id));
    }
    private synchronized void deleteNow(String libraryId, String id) {
        deleteManyNow(libraryId,java.util.Collections.singletonList(id));
    }
    /** Call inside XsLibraryWrites.invalidateAndSubmit when explicitly deleting a library. */
    public synchronized void eraseLibrary(String libraryId) {
        requireLibrary(libraryId);
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,filename,job_id FROM assets WHERE library_id=?",new String[]{libraryId})) {
            while(c.moveToNext()) {stopTransfer(c.getString(0));if(scheduler!=null&&!c.isNull(2))scheduler.cancel(c.getInt(2));deleteParts(c.getString(0));if(!c.isNull(1))deleteFile(new File(savedDir,c.getString(1)));}
        }
        try{syncDirectory(savedDir);}catch(IOException failure){throw new IllegalStateException("Resource deletion not durable; retry");}
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
    private synchronized boolean active(String id,String run) {
        if(stoppedRuns.contains(run))return false;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM assets WHERE id=? AND run_token=? AND state IN ('QUEUED','DOWNLOADING')",new String[]{id,run})) {return c.moveToFirst();}
    }
    private static String validateUrl(String postUrl,String mediaUrl,boolean video) {
        return video?MediaDownloadPolicy.validateVideo(postUrl,mediaUrl):MediaDownloadPolicy.validateImage(postUrl,mediaUrl);
    }
    private void download(String id,String libraryId,String postUrl,String mediaUrl,XsLibraryWrites.Ticket ticket,boolean video,String run) {
        File part=new File(savedDir,id+"."+run+".part"); HttpURLConnection connection=null; boolean completionQueued=false;
        final long maxBytes=video?MediaDownloadPolicy.MAX_VIDEO_BYTES:MediaDownloadPolicy.MAX_BYTES;
        final long deadline=android.os.SystemClock.elapsedRealtime()+(video?300000:120000);
        try {
            if(!active(id,run)||!XsLibraryWrites.isCurrent(ticket)){fail(libraryId,id,"Observation context changed; select media again to restart",run);return;}
            String current=mediaUrl;
            for(int redirects=0;;redirects++) {
                if(android.os.SystemClock.elapsedRealtime()>deadline||!XsLibraryWrites.isCurrent(ticket))throw new IOException("Download context expired");
                validateUrl(postUrl,current,video);
                connection=connections.open(new URL(current));
                synchronized(this){if(stoppedRuns.contains(run))throw new IOException("Transfer stopped");runningConnections.put(run,connection);}
                connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(15000);connection.setReadTimeout(15000);
                connection.setRequestProperty("Accept-Encoding","identity");
                connection.setRequestProperty("Accept",video?"video/mp4":"image/jpeg,image/png,image/webp,image/gif");
                int status=connection.getResponseCode();
                if(status>=300&&status<400) {
                    if(redirects>=MediaDownloadPolicy.MAX_REDIRECTS)throw new IOException("Too many redirects");
                    String location=connection.getHeaderField("Location");
                    if(location==null)throw new IOException("Invalid redirect");
                    String resolved=new URL(new URL(current),location).toExternalForm();
                    validateUrl(postUrl,resolved,video);
                    connection.disconnect();connection=null;current=resolved;continue;
                }
                if(status!=200)throw new IOException("Server did not provide media");
                break;
            }
            long declared=connection.getContentLengthLong();
            if(declared==0||declared>maxBytes)throw new IOException("Media size is not allowed");
            String encoding=connection.getContentEncoding();
            if(encoding!=null&&!"identity".equalsIgnoreCase(encoding))throw new IOException("Encoded response is unsupported");
            XsLibraryWrites.submit(ticket,()->{
                synchronized(XsMediaRepository.this) {
                    ContentValues downloading=new ContentValues();downloading.put("state","DOWNLOADING");downloading.put("total",declared);
                    getWritableDatabase().update("assets",downloading,"id=? AND library_id=? AND run_token=? AND state='QUEUED'",new String[]{id,libraryId,run});
                }
            });
            MessageDigest digest=MessageDigest.getInstance("SHA-256"); long bytes=0,lastProgress=0; byte[] prefix=new byte[16];int prefixLength=0;
            try(InputStream input=connection.getInputStream(); FileOutputStream output=new FileOutputStream(part)) {
                byte[] buffer=new byte[16384];int n;
                while((n=input.read(buffer))!=-1) {
                    if(!active(id,run)||!XsLibraryWrites.isCurrent(ticket)||android.os.SystemClock.elapsedRealtime()>deadline)throw new IOException("Canceled or expired");
                    bytes+=n;if(bytes>maxBytes||savedDir.getUsableSpace()<1048576)throw new IOException("Media exceeds available storage");
                    int copy=Math.min(n,prefix.length-prefixLength);if(copy>0){System.arraycopy(buffer,0,prefix,prefixLength,copy);prefixLength+=copy;}
                    output.write(buffer,0,n);digest.update(buffer,0,n);
                    if(bytes-lastProgress>=262144){progress(ticket,libraryId,id,bytes,run);lastProgress=bytes;}
                }
                output.getFD().sync();
            }
            if(!MediaDownloadPolicy.completeLength(declared,bytes,maxBytes))throw new IOException("Incomplete media");
            final VideoFileValidator.Info videoInfo;
            final String extension;
            if(video) {
                String type=connection.getContentType();
                if(type==null||!type.split(";",2)[0].trim().equalsIgnoreCase("video/mp4"))throw new IOException("Unsupported video response");
                videoInfo=VideoFileValidator.validate(part);extension="mp4";
            } else {
                videoInfo=null;extension=MediaDownloadPolicy.imageExtension(prefix,connection.getContentType());
                if(!MediaDownloadPolicy.hasCompleteEnvelope(part,extension))throw new IOException("Incomplete image file");
                validateBitmap(part);
            }
            String hash=hex(digest.digest());String filename=id+"."+extension; File completed=new File(savedDir,filename);
            final long completedBytes=bytes;
            completionQueued=XsLibraryWrites.submit(ticket,()->{
                synchronized(XsMediaRepository.this) {
                    try {
                        // Same serial barrier as library erasure, checked at actual write time.
                        if(!active(id,run))return;
                        if(!part.renameTo(completed))throw new IOException("Could not finalize media");
                        syncDirectory(savedDir);
                        ContentValues v=new ContentValues();v.put("state","COMPLETED");v.put("bytes",completedBytes);v.put("total",completedBytes);
                        v.put("filename",filename);v.put("mime",video?"video/mp4":mime(extension));v.put("sha256",hash);
                        if(videoInfo!=null) {v.put("duration_ms",videoInfo.durationMs);v.put("width",videoInfo.width);v.put("height",videoInfo.height);v.put("video_tracks",videoInfo.videoTracks);v.put("audio_tracks",videoInfo.audioTracks);v.put("rotation_degrees",videoInfo.rotationDegrees);}v.putNull("media_url");v.putNull("url_cipher");v.putNull("run_token");v.putNull("error");
                        getWritableDatabase().update("assets",v,"id=? AND library_id=?",new String[]{id,libraryId});
                    } catch(Exception failure) {completed.delete();fail(libraryId,id,"Could not finalize media",run);}
                    finally {part.delete();}
                }
            },()->{part.delete();fail(libraryId,id,"Context changed before completion; select media again to restart",run);});
            if(!completionQueued)fail(libraryId,id,"Write queue unavailable; restart media download",run);
        } catch(Exception failure) {
            if(failure instanceof java.net.SocketTimeoutException||failure instanceof java.net.SocketException||failure instanceof java.net.UnknownHostException) {
                retryNetwork(libraryId,id,run);failAtRetryLimit(libraryId,id,run);
            }else fail(libraryId,id,safeFailure(failure),run);
        }
        finally {synchronized(this){runningConnections.remove(run);}if(connection!=null)connection.disconnect();if(!completionQueued)part.delete();}
    }
    private static void validateBitmap(File file)throws IOException {
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),bounds);
        if(bounds.outWidth<=0||bounds.outHeight<=0||(long)bounds.outWidth*bounds.outHeight>16000000)throw new IOException("Image dimensions are unsupported");
        BitmapFactory.Options decode=new BitmapFactory.Options();decode.inPreferredConfig=Bitmap.Config.RGB_565;
        Bitmap bitmap=BitmapFactory.decodeFile(file.getPath(),decode);if(bitmap==null)throw new IOException("Invalid image");bitmap.recycle();
    }
    private void progress(XsLibraryWrites.Ticket ticket,String libraryId,String id,long bytes,String run) {
        XsLibraryWrites.submit(ticket,()->{
            synchronized(XsMediaRepository.this) {
                ContentValues v=new ContentValues();v.put("bytes",bytes);
                getWritableDatabase().update("assets",v,"id=? AND library_id=? AND run_token=? AND state='DOWNLOADING'",new String[]{id,libraryId,run});
            }
        });
    }
    private void fail(String libraryId,String id,String message) {fail(libraryId,id,message,null);}
    private void fail(String libraryId,String id,String message,String run) {
        if(XsLibraryWrites.isDeleted(libraryId))return;
        // A status-only update in the current generation cannot recreate erased rows.
        XsLibraryWrites.submit(libraryId,()->{
            synchronized(XsMediaRepository.this) {
                ContentValues v=new ContentValues();v.put("state","FAILED");v.put("error",message);v.putNull("media_url");v.putNull("run_token");
                getWritableDatabase().update("assets",v,"id=? AND library_id=? AND state IN ('QUEUED','DOWNLOADING')"+(run==null?"":" AND run_token=?"),run==null?new String[]{id,libraryId}:new String[]{id,libraryId,run});
            }
        });
    }
    private void failAtRetryLimit(String library,String id,String run) {
        if(XsLibraryWrites.isDeleted(library))return;
        mutate(library,()->{synchronized(XsMediaRepository.this){
            ContentValues v=new ContentValues();v.put("state","FAILED");v.putNull("run_token");v.put("error","Network retry limit reached; retry from the visible app");
            getWritableDatabase().update("assets",v,"library_id=? AND id=? AND run_token=? AND attempt_count>=3 AND state IN ('QUEUED','DOWNLOADING')",new String[]{library,id,run});
        }});
    }
    /** Signal before queued status mutation so a stopped job releases its active network operation. */
    private void stopTransfer(String id) {
        HttpURLConnection connection;
        synchronized(this){String run=activeRuns.get(id);if(run==null)return;stoppedRuns.add(run);connection=runningConnections.get(run);}
        if(connection!=null)connection.disconnect();
    }
    private void deleteParts(String id) {
        for(File file:files(savedDir))if(file.getName().equals(id+".part")||(file.getName().startsWith(id+".")&&file.getName().endsWith(".part")))deleteFile(file);
    }
    /** Persist the files/ directory entry as well as contents before any metadata can reference it. */
    private static void ensureStorageDirectory(File directory) {
        if(!directory.isDirectory()&&!directory.mkdir()&&!directory.isDirectory())throw new IllegalStateException("Private media storage unavailable");
        try{syncDirectory(directory);syncDirectory(directory.getParentFile());}
        catch(IOException unavailable){throw new IllegalStateException("Private media storage initialization failed");}
    }
    private static void syncDirectory(File directory)throws IOException {
        java.io.FileDescriptor descriptor=null;
        try{descriptor=android.system.Os.open(directory.getCanonicalPath(),android.system.OsConstants.O_RDONLY,0);android.system.Os.fsync(descriptor);}
        catch(android.system.ErrnoException failure){throw new IOException("Resource directory sync failed",failure);}
        finally{if(descriptor!=null)try{android.system.Os.close(descriptor);}catch(android.system.ErrnoException ignored){}}
    }
    private static void deleteFile(File file) {if(file.exists()&&!file.delete())throw new IllegalStateException("Saved file could not be deleted; retry");}
    private static String safeFailure(Exception error){return error instanceof IllegalArgumentException?"Unsupported media response":"Download interrupted or invalid media; select the media to retry from the start";}
    private static String hex(byte[] value){StringBuilder s=new StringBuilder();for(byte b:value)s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();}
    private static String mime(String ext){return "jpg".equals(ext)?"image/jpeg":"image/"+ext;}
    private static File[] files(File dir){File[] files=dir.listFiles();return files==null?new File[0]:files;}
    private static void requireLibrary(String id){if(id==null||id.trim().isEmpty()||id.length()>256)throw new IllegalArgumentException("Library required");}

    public static final class CompletedAsset {
        public final Asset asset;
        public final String sha256;
        public final File file;
        CompletedAsset(Asset asset,String hash,File file){this.asset=asset;this.sha256=hash;this.file=file;}
    }
    /** Call under the shared source-library writer while streaming a consistent backup. */
    public synchronized List<CompletedAsset> snapshotCompleted(String library) {
        requireLibrary(library);List<CompletedAsset> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT "+COLUMNS+",sha256 FROM assets WHERE library_id=? AND state='COMPLETED' ORDER BY created_ms,id LIMIT 501",new String[]{library})) {
            while(c.moveToNext()){if(result.size()>=500)throw new IllegalStateException("Backup supports up to 500 completed resources");Asset asset=new Asset(c);result.add(new CompletedAsset(asset,c.getString(18),completedFile(library,asset.id)));}
        }
        return java.util.Collections.unmodifiableList(result);
    }
    public static final class RestoredAsset {
        public final String postUrl,kind,mime,sha256,category;
        public final long bytes,createdMs,durationMs;
        public final int width,height,videoTracks,audioTracks,rotationDegrees;
        public final File stageFile;
        public RestoredAsset(String postUrl,String kind,String mime,long bytes,String sha256,String category,long createdMs,
                long durationMs,int width,int height,int videoTracks,int audioTracks,int rotationDegrees,File stageFile) {
            this.postUrl=postUrl;this.kind=kind;this.mime=mime;this.bytes=bytes;this.sha256=sha256;this.category=category;this.createdMs=createdMs;
            this.durationMs=durationMs;this.width=width;this.height=height;this.videoTracks=videoTracks;this.audioTracks=audioTracks;this.rotationDegrees=rotationDegrees;this.stageFile=stageFile;
        }
    }
    /** Native validation of authenticated private staging, repeated before any publication. */
    public synchronized void validateRestored(List<RestoredAsset> staged)throws IOException {
        if(staged==null||staged.size()>500)throw new IOException("Too many backup resources");
        long total=0;
        for(RestoredAsset asset:staged) {
            if(asset==null||asset.stageFile==null||!asset.stageFile.isFile()||!asset.stageFile.getCanonicalPath().startsWith(privateRoot.getCanonicalPath()+File.separator))throw new IOException("Private staged resource required");
            boolean video="VIDEO".equals(asset.kind);
            if(!video&&!"IMAGE".equals(asset.kind))throw new IOException("Unsupported resource kind");
            if(PostUrls.canonicalLink(asset.postUrl)==null||asset.category==null||asset.category.length()>120||asset.createdMs<=0||asset.sha256==null||!asset.sha256.matches("[0-9a-f]{64}"))throw new IOException("Invalid resource metadata");
            long maximum=video?MediaDownloadPolicy.MAX_VIDEO_BYTES:MediaDownloadPolicy.MAX_BYTES;
            if(asset.bytes<=0||asset.bytes>maximum||asset.stageFile.length()!=asset.bytes||(total+=asset.bytes)>512L*1024*1024)throw new IOException("Resource size exceeds backup limits");
            if(!asset.sha256.equals(hashFile(asset.stageFile)))throw new IOException("Resource checksum mismatch");
            if(video) {
                if(!"video/mp4".equals(asset.mime))throw new IOException("Unsupported video type");
                VideoFileValidator.Info info=VideoFileValidator.validate(asset.stageFile);
                if(info.durationMs!=asset.durationMs||info.width!=asset.width||info.height!=asset.height||info.videoTracks!=asset.videoTracks||info.audioTracks!=asset.audioTracks||info.rotationDegrees!=asset.rotationDegrees)throw new IOException("Video metadata does not match bytes");
            } else {
                if(asset.durationMs!=0||asset.videoTracks!=0||asset.audioTracks!=0||asset.rotationDegrees!=0)throw new IOException("Unexpected image metadata");
                BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(asset.stageFile.getPath(),bounds);
                if((asset.width!=0||asset.height!=0)&&(asset.width!=bounds.outWidth||asset.height!=bounds.outHeight))throw new IOException("Image dimensions do not match bytes");
                String extension=imageExtension(asset.stageFile,asset.mime);
                if(!MediaDownloadPolicy.hasCompleteEnvelope(asset.stageFile,extension))throw new IOException("Incomplete image");
                validateBitmap(asset.stageFile);
            }
        }
    }
    private static String imageExtension(File file,String mime)throws IOException {
        byte[] prefix=new byte[16];try(FileInputStream input=new FileInputStream(file)){if(input.read(prefix)<12)throw new IOException("Invalid image");}
        try{return MediaDownloadPolicy.imageExtension(prefix,mime);}catch(IllegalArgumentException invalid){throw new IOException("Invalid image type");}
    }
    private static String hashFile(File file)throws IOException {
        try{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(FileInputStream input=new FileInputStream(file)){byte[] b=new byte[16384];int n;while((n=input.read(b))!=-1)digest.update(b,0,n);}return hex(digest.digest());}
        catch(java.security.GeneralSecurityException unavailable){throw new IOException("Checksum unavailable");}
    }
    /** The caller owns new-library staging/recovery markers and must keep it hidden until metadata commits. */
    public synchronized List<String> restoreCompleted(String library,List<RestoredAsset> staged)throws IOException {
        requireLibrary(library);validateRestored(staged);
        try(Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM assets WHERE library_id=? LIMIT 1",new String[]{library})){if(c.moveToFirst())throw new IOException("Restore requires an empty new library");}
        List<String> ids=new ArrayList<>();List<File> published=new ArrayList<>();SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        Exception failed=null;
        try {
            for(RestoredAsset asset:staged) {
                if(Thread.currentThread().isInterrupted()||XsLibraryWrites.isDeleted(library))throw new IOException("Restore canceled");
                String id=UUID.randomUUID().toString();String extension="VIDEO".equals(asset.kind)?"mp4":imageExtension(asset.stageFile,asset.mime);
                File temporary=new File(savedDir,id+".part"),complete=new File(savedDir,id+"."+extension);published.add(temporary);published.add(complete);
                try(FileInputStream input=new FileInputStream(asset.stageFile);FileOutputStream output=new FileOutputStream(temporary)){byte[] b=new byte[16384];int n;while((n=input.read(b))!=-1){if(Thread.currentThread().isInterrupted()||XsLibraryWrites.isDeleted(library))throw new IOException("Restore canceled");output.write(b,0,n);}output.getFD().sync();}
                if(Thread.currentThread().isInterrupted()||XsLibraryWrites.isDeleted(library))throw new IOException("Restore canceled");
                if(temporary.length()!=asset.bytes||!asset.sha256.equals(hashFile(temporary))||!temporary.renameTo(complete))throw new IOException("Resource staging failed");
                ContentValues v=new ContentValues();v.put("id",id);v.put("library_id",library);v.put("post_url",PostUrls.canonicalLink(asset.postUrl));v.put("state","COMPLETED");v.put("bytes",asset.bytes);v.put("total",asset.bytes);v.put("mime",asset.mime);v.put("filename",complete.getName());v.put("sha256",asset.sha256);v.put("created_ms",asset.createdMs);v.put("category",asset.category);v.put("media_kind",asset.kind);
                if("VIDEO".equals(asset.kind)){v.put("duration_ms",asset.durationMs);v.put("width",asset.width);v.put("height",asset.height);v.put("video_tracks",asset.videoTracks);v.put("audio_tracks",asset.audioTracks);v.put("rotation_degrees",asset.rotationDegrees);}
                db.insertOrThrow("assets",null,v);ids.add(id);
            }
            syncDirectory(savedDir);
            db.setTransactionSuccessful();
        }catch(IOException|RuntimeException failure){failed=failure;}
        finally{try{db.endTransaction();}catch(RuntimeException commitFailure){if(failed==null)failed=commitFailure;else failed.addSuppressed(commitFailure);}}
        if(failed!=null) {
            for(File file:published)file.delete();
            try{getWritableDatabase().delete("assets","library_id=?",new String[]{library});syncDirectory(savedDir);}catch(RuntimeException|IOException compensation){failed.addSuppressed(compensation);}
            if(failed instanceof IOException)throw (IOException)failed;throw new IOException("Resource restore failed",failed);
        }
        return java.util.Collections.unmodifiableList(ids);
    }
    /** These synchronous batch operations must run inside XsLibraryWrites for the same library. */
    public synchronized void setCategoriesNow(String library,List<String> ids,String category) {
        validateBatch(library,ids);if(category==null||category.length()>120)throw new IllegalArgumentException("Category too long");
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{for(String id:ids){ContentValues v=new ContentValues();v.put("category",category.trim());db.update("assets",v,"library_id=? AND id=?",new String[]{library,id});}db.setTransactionSuccessful();}finally{db.endTransaction();}
    }
    public synchronized void deleteManyNow(String library,List<String> ids) {
        validateBatch(library,ids);
        // Preflight before mutating metadata. If a filesystem delete fails, rows remain for explicit retry.
        for(String id:ids){Asset a=find(library,id);if(a==null)continue;stopTransfer(id);cancelJob(library,id);deleteParts(id);if(a.filename!=null)deleteFile(new File(savedDir,a.filename));}
        try{syncDirectory(savedDir);}catch(IOException failure){throw new IllegalStateException("Resource deletion not durable; retry");}
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{for(String id:ids)db.delete("assets","library_id=? AND id=?",new String[]{library,id});db.setTransactionSuccessful();}finally{db.endTransaction();}
    }
    private static void validateBatch(String library,List<String> ids) {
        requireLibrary(library);if(ids==null||ids.size()>500)throw new IllegalArgumentException("Select up to 500 assets");
        for(String id:ids)if(id==null||!id.matches("[0-9a-f-]{36}"))throw new IllegalArgumentException("Invalid resource ID");
    }

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
        if(token==null||!token.matches("[0-9a-f-]{36}")||filename==null||!filename.matches("[0-9a-f-]{36}\\.(jpg|png|gif|webp|mp4)"))throw new IllegalArgumentException("Invalid file grant");
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

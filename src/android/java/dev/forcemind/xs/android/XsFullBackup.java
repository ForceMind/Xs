package dev.forcemind.xs.android;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.forcemind.xs.core.EncryptedLibraryBackup;
import dev.forcemind.xs.core.LibraryBackupCodec;
import dev.forcemind.xs.core.MediaDownloadPolicy;
import dev.forcemind.xs.core.PostUrls;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Portable password-protected metadata and completed resource files. No browser or task secrets. */
public final class XsFullBackup {
    private static final Object OPERATIONS=new Object();
    private static final Set<String> ACTIVE=new HashSet<>();
    private static final String UUID_PATTERN="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final List<String> ASSET_COLUMNS=Arrays.asList("source_id","post_url","media_kind","mime","bytes","sha256","category","created_ms","duration_ms","width","height","video_tracks","audio_tracks","rotation_degrees");
    private XsFullBackup(){}

    /** Owns authenticated private staging until restored or canceled; never stores a password. */
    public static final class Prepared implements Closeable {
        private final File directory;
        private final EncryptedLibraryBackup.Payload payload;
        private final XsLibraryBackup.ValidatedBackup metadata;
        private final List<XsMediaRepository.RestoredAsset> assets;
        private final XsLibraryWrites.Ticket admission;
        private final AtomicInteger state=new AtomicInteger(); // open / restoring / closed
        private Prepared(File directory,EncryptedLibraryBackup.Payload payload,XsLibraryBackup.ValidatedBackup metadata,
                         List<XsMediaRepository.RestoredAsset> assets,XsLibraryWrites.Ticket admission) {
            this.directory=directory;this.payload=payload;this.metadata=metadata;this.assets=Collections.unmodifiableList(new ArrayList<>(assets));this.admission=admission;
        }
        public String label(){return metadata.label();}
        public Map<String,Integer> counts(){return metadata.counts();}
        public int assetCount(){return assets.size();}
        public long assetBytes(){long bytes=0;for(XsMediaRepository.RestoredAsset asset:assets)bytes+=asset.bytes;return bytes;}
        @Override public void close()throws IOException {
            int before=state.getAndSet(2);if(before==0)cleanup(); // An active restore cleans up when its result is known.
        }
        private void cleanup()throws IOException {
            synchronized(OPERATIONS) {
                IOException failure=null;try{payload.close();}catch(IOException problem){failure=problem;}
                try{removeTree(directory,directory);}catch(IOException problem){failure=problem;}finally{ACTIVE.remove(directory.getAbsolutePath());}
                if(failure!=null)throw failure;
            }
        }
    }
    public static void exportEncrypted(Context context,String library,OutputStream out,char[] password)throws IOException {
        exportEncrypted(context,library,XsLibraryWrites.ticket(library),out,password);
    }
    static void exportEncrypted(Context context,String library,XsLibraryWrites.Ticket ticket,OutputStream out,char[] password)throws IOException {
        if(context==null||out==null)throw invalid();Context app=context.getApplicationContext();
        onWriter(ticket,canceled->{
            synchronized(OPERATIONS) {
                BooleanSupplier guard=()->!canceled.get()&&XsLibraryWrites.isCurrent(ticket);
                check(guard);File work=newWork(app);try {
                    ByteArrayOutputStream metadataOut=new ByteArrayOutputStream();XsLibraryBackup.exportLibrary(app,library,metadataOut);
                    byte[] metadata=metadataOut.toByteArray();List<EncryptedLibraryBackup.AssetSource> sources=new ArrayList<>();
                    List<XsMediaRepository.RestoredAsset> actual=new ArrayList<>();Set<String> identities=new HashSet<>();
                    XsMediaRepository media=XsMediaRepository.get(app);
                    for(XsMediaRepository.CompletedAsset completed:media.snapshotCompleted(library)) {
                        check(guard);XsMediaRepository.Asset asset=completed.asset;
                        String hash=completed.sha256==null?hash(completed.file,guard):completed.sha256;
                        Map<String,Object> descriptor=describe(asset,hash);validateDescriptor(descriptor,asset.bytes,hash,identities);
                        actual.add(restored(descriptor,completed.file));String json=encodeDescriptor(descriptor);
                        sources.add(new EncryptedLibraryBackup.AssetSource(json,asset.bytes,hash,()->guarded(new FileInputStream(completed.file),guard)));
                    }
                    media.validateRestored(actual);check(guard);
                    long length=EncryptedLibraryBackup.payloadLength(metadata,sources);space(work,length);
                    File plain=new File(work,"export.payload");
                    try(FileOutputStream file=new FileOutputStream(plain)){EncryptedLibraryBackup.writePayload(new GuardOutput(file,guard),metadata,sources);file.getFD().sync();}
                    Arrays.fill(metadata,(byte)0);check(guard);
                    try(FileInputStream file=new FileInputStream(plain)){EncryptedLibraryBackup.encrypt(guarded(file,guard),new GuardOutput(out,guard),password,plain.length());}
                    check(guard);return null;
                }finally{try{removeTree(work,work);}finally{ACTIVE.remove(work.getAbsolutePath());}}
            }
        });
    }
    public static Prepared inspectEncrypted(Context context,String library,InputStream encrypted,char[] password)throws IOException {
        return inspectEncrypted(context,library,XsLibraryWrites.ticket(library),encrypted,password);
    }
    static Prepared inspectEncrypted(Context context,String library,XsLibraryWrites.Ticket ticket,InputStream encrypted,char[] password)throws IOException {
        if(context==null||encrypted==null)throw invalid();Context app=context.getApplicationContext();
        synchronized(OPERATIONS) {
            BooleanSupplier guard=()->XsLibraryWrites.isCurrent(ticket)&&!Thread.currentThread().isInterrupted();check(guard);
            File work=newWork(app);EncryptedLibraryBackup.Payload payload=null;boolean ready=false;
            try {
                File plain=new File(work,"verified.payload");EncryptedLibraryBackup.decryptToVerifiedFile(guarded(encrypted,guard),plain,password);check(guard);space(work,plain.length());
                File assetsDir=new File(work,"assets");if(!assetsDir.mkdir())throw invalid();
                try(FileInputStream in=new FileInputStream(plain)){payload=EncryptedLibraryBackup.readPayload(guarded(in,guard),assetsDir);}
                byte[] metadataBytes=payload.metadata();XsLibraryBackup.ValidatedBackup metadata;
                try{metadata=XsLibraryBackup.inspect(new ByteArrayInputStream(metadataBytes));}finally{Arrays.fill(metadataBytes,(byte)0);}
                List<XsMediaRepository.RestoredAsset> assets=new ArrayList<>();Set<String> ids=new HashSet<>();
                for(EncryptedLibraryBackup.AssetEntry entry:payload.assets()) {
                    Map<String,Object> descriptor=decodeDescriptor(entry.descriptorJson);validateDescriptor(descriptor,entry.length,entry.sha256Hex,ids);
                    assets.add(restored(descriptor,entry.file));
                }
                XsMediaRepository.get(app).validateRestored(assets);check(guard);
                if(!plain.delete())throw new IOException("Could not clear verified backup staging");
                Prepared prepared=new Prepared(work,payload,metadata,assets,ticket);ready=true;return prepared;
            }finally {
                if(!ready){if(payload!=null)try{payload.close();}catch(IOException ignored){}try{removeTree(work,work);}finally{ACTIVE.remove(work.getAbsolutePath());}}
            }
        }
    }
    /** Library registration is the commit point. Media is never exposed as a registered partial library. */
    public static String restoreNewLibrary(Context context,Prepared prepared,String label)throws IOException {
        if(context==null||prepared==null||!prepared.state.compareAndSet(0,1))throw invalid();Context app=context.getApplicationContext();
        String restored=null;
        try {
            restored=onWriter(prepared.admission,canceled->{
                synchronized(OPERATIONS) {
                    BooleanSupplier source=()->!canceled.get()&&XsLibraryWrites.isCurrent(prepared.admission);check(source);
                    String target="local:"+UUID.randomUUID();XsLibraryWrites.Ticket reservation=XsLibraryWrites.ticket(target);
                    BooleanSupplier guard=()->source.getAsBoolean()&&!XsLibraryWrites.isDeleted(target)&&XsLibraryWrites.isCurrent(reservation);
                    File marker=null;boolean published=false;XsMediaRepository media=XsMediaRepository.get(app);
                    try {
                        check(guard);space(prepared.directory,prepared.assetBytes());marker=createMarker(app,target);
                        media.restoreCompleted(target,prepared.assets);check(guard);
                        XsLibraryBackup.restoreIntoNewLibrary(app,prepared.metadata,label,target,guard);
                        published=true;finishPublishedMarker(app,target,marker);return target;
                    }catch(Exception failure) {
                        // A crash or provider-close error after the archive commit must preserve its files.
                        if(registered(app,target)){published=true;finishPublishedMarker(app,target,marker);return target;}
                        try{media.eraseLibrary(target);if(marker!=null&&marker.delete())syncDirectory(marker.getParentFile());}
                        catch(Exception cleanup){failure.addSuppressed(cleanup);}
                        if(failure instanceof IOException)throw (IOException)failure;throw new IOException("Encrypted restore did not complete",failure);
                    }finally{if(!published)XsLibraryWrites.releaseUnused(reservation);}
                }
            });return restored;
        }finally{prepared.state.set(2);try{prepared.cleanup();}catch(IOException cleanup){if(restored==null)throw cleanup;/* Startup retries private cleanup after an already committed result. */}}
    }
    /** Startup recovery of native pending markers; never reads backup-controlled paths or credentials. */
    public static void recoverInterrupted(Context context)throws IOException {
        if(context==null)throw invalid();Context app=context.getApplicationContext();
        synchronized(OPERATIONS) {
            File markers=markerDirectory(app);File[] pending=markers.listFiles();if(pending==null)throw invalid();
            int reviewed=0; // A retained registered marker is retryable housekeeping, not app failure.
            for(File marker:pending) {
                if(!marker.getName().matches(UUID_PATTERN+"\\.pending")||!marker.isFile())continue;
                if(reviewed++>=128)break; // Preserve excess markers for the next recovery pass.
                String target="local:"+marker.getName().substring(0,36);
                if(target.equals(XsLocalSpaces.current(app)))continue;
                if(!registered(app,target)) {
                    // An unpublished scope has no user-facing identity. Do not create a writer for
                    // a permanently deleted scope; the marker owns only its new native UUID.
                    XsMediaRepository.get(app).eraseLibrary(target);
                    try(XsArchiveDatabase archive=new XsArchiveDatabase(app)) {
                        if(!registered(archive.getReadableDatabase(),target))archive.clearAccount(target);
                    }
                    if(!marker.delete())throw new IOException("Interrupted restore cleanup incomplete");syncDirectory(markers);
                }else finishPublishedMarker(app,target,marker); // Busy/uncertain durability is retried on a later start.
            }
            File root=workRoot(app);File[] stale=root.listFiles();if(stale!=null)for(File work:stale)
                if(work.getName().matches(UUID_PATTERN)&&!ACTIVE.contains(work.getAbsolutePath()))removeTree(work,work);
        }
    }
    private static Map<String,Object> describe(XsMediaRepository.Asset asset,String hash) {
        LinkedHashMap<String,Object> row=new LinkedHashMap<>();row.put("source_id",asset.id);row.put("post_url",asset.postUrl);row.put("media_kind",asset.mediaKind);
        row.put("mime",asset.mime);row.put("bytes",asset.bytes);row.put("sha256",hash);row.put("category",asset.category);row.put("created_ms",asset.createdMs);
        boolean video="VIDEO".equals(asset.mediaKind);row.put("duration_ms",video?asset.durationMs:null);row.put("width",video?(long)asset.width:null);row.put("height",video?(long)asset.height:null);
        row.put("video_tracks",video?(long)asset.videoTracks:null);row.put("audio_tracks",video?(long)asset.audioTracks:null);row.put("rotation_degrees",video?(long)asset.rotationDegrees:null);return row;
    }
    private static String encodeDescriptor(Map<String,Object> row)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();LibraryBackupCodec.write(bytes,new LibraryBackupCodec.Header(3,1,"asset"),Collections.singletonList(new LibraryBackupCodec.Record("assets",row)));
        return new String(bytes.toByteArray(),StandardCharsets.UTF_8);
    }
    private static Map<String,Object> decodeDescriptor(String json)throws IOException {
        LibraryBackupCodec.Data data=LibraryBackupCodec.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        if(data.header().schemaVersion!=3||data.header().createdAtMs!=1||!"asset".equals(data.header().label)||data.records().size()!=1||!"assets".equals(data.records().get(0).table))throw invalid();
        return data.records().get(0).values;
    }
    private static void validateDescriptor(Map<String,Object> row,long length,String hash,Set<String> ids)throws IOException {
        if(row.size()!=ASSET_COLUMNS.size()||!row.keySet().containsAll(ASSET_COLUMNS))throw invalid();
        String id=string(row,"source_id",128),url=string(row,"post_url",128),kind=string(row,"media_kind",5),mime=string(row,"mime",32);
        if(!id.matches(UUID_PATTERN)||!ids.add(id)||!url.equals(PostUrls.canonicalLink(url))||!("IMAGE".equals(kind)||"VIDEO".equals(kind)))throw invalid();
        if(!hash.equals(string(row,"sha256",64))||!hash.matches("[0-9a-f]{64}")||number(row,"bytes")!=length||length<=0||length>("VIDEO".equals(kind)?MediaDownloadPolicy.MAX_VIDEO_BYTES:MediaDownloadPolicy.MAX_BYTES))throw invalid();
        if(!(row.get("category") instanceof String)||((String)row.get("category")).length()>120||number(row,"created_ms")<=0)throw invalid();
        if("IMAGE".equals(kind)) {
            if(!Arrays.asList("image/jpeg","image/png","image/webp","image/gif").contains(mime))throw invalid();
            for(String field:Arrays.asList("duration_ms","width","height","video_tracks","audio_tracks","rotation_degrees"))if(row.get(field)!=null)throw invalid();
        } else {
            if(!"video/mp4".equals(mime)||number(row,"duration_ms")<=0||number(row,"duration_ms")>600000||number(row,"width")<=0||number(row,"width")>3840||number(row,"height")<=0||number(row,"height")>2160||number(row,"video_tracks")!=1||number(row,"audio_tracks")<0||number(row,"audio_tracks")>1||!Arrays.asList(0L,90L,180L,270L).contains(row.get("rotation_degrees")))throw invalid();
        }
    }
    private static XsMediaRepository.RestoredAsset restored(Map<String,Object> row,File file)throws IOException {
        boolean video="VIDEO".equals(row.get("media_kind"));return new XsMediaRepository.RestoredAsset((String)row.get("post_url"),(String)row.get("media_kind"),(String)row.get("mime"),number(row,"bytes"),(String)row.get("sha256"),(String)row.get("category"),number(row,"created_ms"),video?number(row,"duration_ms"):0,video?(int)number(row,"width"):0,video?(int)number(row,"height"):0,video?(int)number(row,"video_tracks"):0,video?(int)number(row,"audio_tracks"):0,video?(int)number(row,"rotation_degrees"):0,file);
    }
    private static String string(Map<String,Object> row,String name,int limit)throws IOException {Object value=row.get(name);if(!(value instanceof String)||((String)value).isEmpty()||((String)value).length()>limit)throw invalid();return (String)value;}
    private static long number(Map<String,Object> row,String name)throws IOException {Object value=row.get(name);if(!(value instanceof Long))throw invalid();return (Long)value;}
    private static String hash(File file,BooleanSupplier guard)throws IOException {
        try {MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream in=guarded(new FileInputStream(file),guard)){byte[] bytes=new byte[65536];int count;while((count=in.read(bytes))!=-1)hash.update(bytes,0,count);Arrays.fill(bytes,(byte)0);}StringBuilder hex=new StringBuilder();for(byte b:hash.digest())hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return hex.toString();}
        catch(java.security.GeneralSecurityException unavailable){throw new IOException("Backup checksum unavailable");}
    }
    private interface Work<T>{T run(AtomicBoolean canceled)throws Exception;}
    private static <T>T onWriter(XsLibraryWrites.Ticket ticket,Work<T> work)throws IOException {
        AtomicReference<T> result=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();AtomicBoolean canceled=new AtomicBoolean();CountDownLatch done=new CountDownLatch(1);
        boolean accepted=XsLibraryWrites.submit(ticket,()->{try{result.set(work.run(canceled));}catch(Throwable problem){failure.set(problem);}finally{done.countDown();}},()->{failure.set(invalid());done.countDown();});
        if(!accepted)throw invalid();boolean interrupted=false;for(;;)try{done.await();break;}catch(InterruptedException stop){interrupted=true;canceled.set(true);}if(interrupted)Thread.currentThread().interrupt();
        if(failure.get()!=null){if(failure.get() instanceof IOException)throw (IOException)failure.get();throw new IOException("Backup operation failed",failure.get());}return result.get();
    }
    private static InputStream guarded(InputStream source,BooleanSupplier guard){return new FilterInputStream(source){@Override public int read()throws IOException{check(guard);return super.read();}@Override public int read(byte[] bytes,int offset,int count)throws IOException{check(guard);return in.read(bytes,offset,count);}};}
    private static final class GuardOutput extends FilterOutputStream {
        final BooleanSupplier guard;GuardOutput(OutputStream out,BooleanSupplier guard){super(out);this.guard=guard;}
        @Override public void write(int value)throws IOException{check(guard);out.write(value);}@Override public void write(byte[] bytes,int offset,int count)throws IOException{check(guard);out.write(bytes,offset,count);}
    }
    private static void check(BooleanSupplier guard)throws IOException {if(!guard.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("Library state changed; choose the backup again");}
    private static void space(File directory,long bytes)throws IOException {if(directory.getUsableSpace()<bytes+1048576L)throw new IOException("Insufficient private backup staging space");}
    private static File workRoot(Context app)throws IOException {File root=new File(app.getCacheDir(),"xs-full-backup");if(!root.isDirectory()&&!root.mkdirs())throw invalid();return root;}
    private static File newWork(Context app)throws IOException {File work=new File(workRoot(app),UUID.randomUUID().toString());if(!work.mkdir())throw invalid();ACTIVE.add(work.getAbsolutePath());return work;}
    private static File markerDirectory(Context app)throws IOException {
        File dir=new File(app.getFilesDir(),"xs-restore-pending");
        if(!dir.isDirectory()){if(!dir.mkdirs())throw invalid();syncDirectory(dir.getParentFile());}
        return dir;
    }
    private static File createMarker(Context app,String target)throws IOException {File marker=new File(markerDirectory(app),target.substring(6)+".pending");if(!marker.createNewFile())throw invalid();try(FileOutputStream out=new FileOutputStream(marker)){out.getFD().sync();}syncDirectory(marker.getParentFile());return marker;}
    private static void syncDirectory(File directory)throws IOException {
        java.io.FileDescriptor fd=null;try{if(!directory.isDirectory())throw new IOException("Restore marker directory unavailable");fd=android.system.Os.open(directory.getAbsolutePath(),android.system.OsConstants.O_RDONLY,0);android.system.Os.fsync(fd);}
        catch(android.system.ErrnoException failure){throw new IOException("Restore marker could not be synced");}finally{if(fd!=null)try{android.system.Os.close(fd);}catch(android.system.ErrnoException ignored){}}
    }
    private static boolean registered(Context app,String target) {try(XsArchiveDatabase archive=new XsArchiveDatabase(app)){return registered(archive.getReadableDatabase(),target);}}
    private static boolean registered(SQLiteDatabase db,String target) {try(Cursor rows=db.rawQuery("SELECT 1 FROM libraries WHERE storage_scope=?",new String[]{target})){return rows.moveToFirst();}}
    private static void finishPublishedMarker(Context app,String target,File marker) {
        if(marker==null)return;
        try(XsArchiveDatabase archive=new XsArchiveDatabase(app)) {
            SQLiteDatabase db=archive.getWritableDatabase();if(registered(db,target))checkpointAndClearMarker(db,marker);
        }catch(RuntimeException ignored){/* Registration already committed. Keep its marker for a later retry. */}
    }
    /** Engineering sync barrier, not a physical power-cut test. Never changes synchronous mode.
     * SQLite FULL checkpoint syncs all transferred frames; a busy or incomplete checkpoint does
     * not qualify. See sqlite.org/pragma.html#pragma_wal_checkpoint and #pragma_synchronous.
     * Package visibility supplies real SQLite/marker regression without a fake success switch.
     */
    static boolean checkpointAndClearMarker(SQLiteDatabase db,File marker) {
        if(db==null||marker==null||!marker.isFile()||android.os.Looper.myLooper()==android.os.Looper.getMainLooper())return false;
        Long previousTimeout=null;
        try {
            if(!db.isOpen()||db.inTransaction())return false;
            String mode;try(Cursor rows=db.rawQuery("PRAGMA main.journal_mode",null)){if(!rows.moveToFirst()||rows.isNull(0))return false;mode=rows.getString(0).toLowerCase(java.util.Locale.ROOT);}
            long synchronous=pragmaLong(db,"PRAGMA main.synchronous");
            if("wal".equals(mode)) {
                if(synchronous<1||synchronous>3)return false;
                // Android 30+ can configure every pooled connection uniformly. Older devices
                // retain their platform timeout; this entire operation is background-only.
                if(android.os.Build.VERSION.SDK_INT>=30) {
                    long timeout=pragmaLong(db,"PRAGMA busy_timeout");
                    if(timeout<0||timeout>Integer.MAX_VALUE)return false;previousTimeout=timeout;
                    db.execPerConnectionSQL("PRAGMA busy_timeout=100",null);
                }
                try(Cursor result=db.rawQuery("PRAGMA main.wal_checkpoint(FULL)",null)) {
                    if(result.getColumnCount()!=3||!result.moveToFirst()||result.isNull(0)||result.isNull(1)||result.isNull(2))return false;
                    long busy=result.getLong(0),frames=result.getLong(1),copied=result.getLong(2);
                    if(busy!=0||frames<0||copied!=frames||result.moveToNext())return false;
                }
            }else {
                // FULL rollback mode alone may lose the final journal unlink on power loss.
                // EXTRA includes its directory sync; retain the marker for weaker modes.
                if(!Arrays.asList("delete","truncate","persist").contains(mode)||synchronous!=3)return false;
            }
            File database=new File(db.getPath());if(!database.isFile()||database.getParentFile()==null)return false;
            syncDirectory(database.getParentFile());
            if(!marker.delete())return false;syncDirectory(marker.getParentFile());return true;
        }catch(IOException|RuntimeException uncertain){return false;}
        finally {
            if(previousTimeout!=null&&android.os.Build.VERSION.SDK_INT>=30)try{db.execPerConnectionSQL("PRAGMA busy_timeout="+previousTimeout,null);}catch(RuntimeException ignored){/* Dedicated production helper closes its pool next. */}
        }
    }
    private static long pragmaLong(SQLiteDatabase db,String sql) {
        try(Cursor value=db.rawQuery(sql,null)){if(value.getColumnCount()!=1||!value.moveToFirst()||value.isNull(0))throw new IllegalStateException("Sync configuration unavailable");long result=value.getLong(0);if(value.moveToNext())throw new IllegalStateException("Ambiguous sync configuration");return result;}
    }
    private static void removeTree(File root,File current)throws IOException {
        if(!current.exists())return;String base=root.getCanonicalPath(),path=current.getCanonicalPath();
        boolean link=java.nio.file.Files.isSymbolicLink(current.toPath());
        if(!link&&current.isDirectory()&&(path.equals(base)||path.startsWith(base+File.separator))){File[] children=current.listFiles();if(children==null)throw invalid();for(File child:children)removeTree(root,child);}
        if(!current.delete()){if(!link&&current.isFile())try(FileOutputStream wipe=new FileOutputStream(current)){wipe.getFD().sync();}if(!current.delete())throw new IOException("Private backup staging cleanup incomplete");}
    }
    private static IOException invalid(){return new IOException("Invalid, expired or unsupported encrypted library backup");}
}

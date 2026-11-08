package dev.forcemind.xs.android;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import dev.forcemind.xs.core.PostUrls;
import dev.forcemind.xs.core.SnapshotPolicy;
import dev.forcemind.xs.core.TimelineBufferPolicy;
import dev.forcemind.xs.core.TimelineBufferSchema;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Application-wide temporary metadata database. It never writes the durable Archive/media DBs.
 * One file and one monitor serialize all scopes' byte reservations, eviction and commits.
 */
public final class XsTimelineBuffer extends SQLiteOpenHelper {
    public static final String DATABASE_NAME="xs-timeline-buffer.db";
    private static final Map<String,XsTimelineBuffer> INSTANCES=new HashMap<>();
    private static final long WRITE_RESERVE=262144L;
    private static final int MAINTENANCE_JOB=0x58535442;
    private final Context app;
    private final File file;
    private String reclaimReason="";
    private final Clock clock;

    /** Injectable native clock for deterministic SQLite boundary/fault tests. */
    public interface Clock {long wall();long elapsed();String boot();}
    private static Clock nativeClock(Context context) {
        return new Clock() {
            public long wall(){return System.currentTimeMillis();}
            public long elapsed(){return SystemClock.elapsedRealtime();}
            public String boot(){
                int count=android.provider.Settings.Global.getInt(context.getContentResolver(),android.provider.Settings.Global.BOOT_COUNT,-1);
                return count<0?"unknown-boot":Integer.toString(count);
            }
        };
    }
    public static synchronized XsTimelineBuffer get(Context context) {
        Context application=context.getApplicationContext();String path=application.getDatabasePath(DATABASE_NAME).getAbsolutePath();
        XsTimelineBuffer store=INSTANCES.get(path);
        if(store==null){store=new XsTimelineBuffer(application,DATABASE_NAME,nativeClock(application));INSTANCES.put(path,store);}
        return store;
    }
    /** A separate filename is required; tests cannot replace the application's singleton clock. */
    XsTimelineBuffer(Context context,String name,Clock clock) {
        super(context.getApplicationContext(),name,null,TimelineBufferSchema.VERSION);
        if(clock==null||name==null||!name.startsWith("xs-timeline-"))throw new IllegalArgumentException("Temporary database required");
        app=context.getApplicationContext();file=app.getDatabasePath(name);this.clock=clock;
        setWriteAheadLoggingEnabled(true);
    }
    @Override public void onConfigure(SQLiteDatabase db){
        super.onConfigure(db);db.setForeignKeyConstraintsEnabled(true);
        // These PRAGMA assignments return a row on Android SQLite. Consume them as queries;
        // execSQL rejects them before any temporary schema or policy can be opened.
        for(String pragma:new String[]{"PRAGMA busy_timeout=1000","PRAGMA wal_autocheckpoint=64","PRAGMA journal_size_limit=262144"}){
            try(Cursor result=db.rawQuery(pragma,null)){result.moveToFirst();}
        }
    }
    @Override public void onCreate(SQLiteDatabase db){
        // FULL moves freed pages to the end at each commit; checkpoint releases the WAL separately.
        db.execSQL("PRAGMA auto_vacuum=FULL");
        for(String sql:TimelineBufferSchema.CREATE)db.execSQL(sql);
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){throw new IllegalStateException("Temporary timeline migration required");}

    public static final class Settings {
        public final boolean enabled;public final int hours,budgetMiB;
        Settings(boolean enabled,int hours,int budget){this.enabled=enabled;this.hours=hours;budgetMiB=budget;}
    }
    public static final class Ticket {
        private final String library;private final long generation,consent;
        private final XsLibraryWrites.Ticket writer;
        private final XsTimelineBuffer owner;
        private Ticket(XsTimelineBuffer owner,String library,long generation,long consent,XsLibraryWrites.Ticket writer){this.owner=owner;this.library=library;this.generation=generation;this.consent=consent;this.writer=writer;}
    }
    public static final class Batch {
        public final String id,reason,source;public final long createdAt,count;
        public Batch(String id,String reason,String source){this(id,reason,source,0,0);}
        Batch(String id,String reason,String source,long createdAt,long count){this.id=id;this.reason=reason;this.source=source;this.createdAt=createdAt;this.count=count;}
    }
    public static final class Candidate {
        public final String url,author,body;public final Long publishedAt;public final long observedAt,sequence;
        public final SnapshotPolicy.Quality quality;
        public Candidate(String url,String author,String body,Long publishedAt,SnapshotPolicy.Quality quality,long observedAt,long sequence){this.url=url;this.author=author;this.body=body;this.publishedAt=publishedAt;this.quality=quality;this.observedAt=observedAt;this.sequence=sequence;}
    }
    public static final class Entry {
        public final long appearanceId,capturedAt,snapshotCapturedAt,sequence,savedAt,favoritedAt;
        public final String libraryId,postId,url,body,author,source,batchId,batchReason,provenance,promotionId;
        public final Long publishedAt;public final SnapshotPolicy.Quality quality;
        Entry(Cursor c){
            appearanceId=c.getLong(0);libraryId=c.getString(1);postId=c.getString(2);url="https://x.com/i/web/status/"+postId;
            body=c.getString(3);author=c.getString(4);publishedAt=c.isNull(5)?null:c.getLong(5);capturedAt=c.getLong(6);
            source=c.getString(7);batchId=c.getString(8);batchReason=c.getString(9);sequence=c.getLong(10);quality=SnapshotPolicy.Quality.valueOf(c.getString(11));
            provenance=c.getString(12);promotionId=c.getString(13);savedAt=c.isNull(14)?0:c.getLong(14);favoritedAt=c.isNull(15)?0:c.getLong(15);snapshotCapturedAt=c.getLong(16);
        }
    }
    public static final class Stats {
        public final long count,oldest,latest,actualBytes,dropped;public final String pauseReason,reclaimReason;
        Stats(long count,long oldest,long latest,long bytes,long dropped,String pause,String reclaim){this.count=count;this.oldest=oldest;this.latest=latest;actualBytes=bytes;this.dropped=dropped;pauseReason=pause;reclaimReason=reclaim;}
    }
    public interface Callback {void onComplete(boolean success);}
    public synchronized Settings settings(){return readSettings(getWritableDatabase());}
    private Settings readSettings(SQLiteDatabase db){try(Cursor c=db.rawQuery("SELECT enabled,ttl_hours,budget_mib FROM timeline_state WHERE id=1",null)){if(!c.moveToFirst())throw new IllegalStateException("Missing timeline policy");return new Settings(c.getInt(0)!=0,c.getInt(1),c.getInt(2));}}
    private long scalar(SQLiteDatabase db,String sql,String... args){try(Cursor c=db.rawQuery(sql,args)){return c.moveToFirst()?c.getLong(0):0;}}

    /** Policy changes first fix the old expired set, so later extension cannot revive it. */
    public synchronized void configure(boolean enabled,int hours,int budgetMiB){
        new TimelineBufferPolicy(enabled,hours,budgetMiB);
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            long now=now(db).nowMs;pruneExpired(db,now);
            Settings before=readSettings(db);
            db.execSQL("UPDATE timeline_state SET enabled=?,ttl_hours=?,budget_mib=?,policy_version=policy_version+1,consent_generation=consent_generation+? WHERE id=1",new Object[]{enabled?1:0,hours,budgetMiB,before.enabled!=enabled?1:0});
            // Rows already expired under the previous policy were removed before recomputation.
            db.execSQL("UPDATE timeline_seen SET expires_at_ms=first_at_ms+? WHERE expires_at_ms>?",new Object[]{hours*3600000L,now});
            db.execSQL("UPDATE timeline_appearances SET expires_at_ms=first_at_ms+?",new Object[]{hours*3600000L});pruneExpired(db,now);
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        enforceLimits(WRITE_RESERVE);scheduleMaintenance(app);
    }
    /**
     * Explicit all-temporary reset, never a silent clock retry. An anomalous future high-water
     * can fall only in the same transaction that removes every old body/reference/receipt.
     * Capture stays OFF; a later independent enable issues another consent generation.
     */
    public void resetAfterClockChange(String requestingLibrary,Callback callback){
        final Ticket request;Handler main=new Handler(Looper.getMainLooper());
        Runnable skipped=()->main.post(()->{if(callback!=null)callback.onComplete(false);});
        try{request=ticket(requestingLibrary);}catch(RuntimeException unavailable){skipped.run();return;}
        boolean queued=XsLibraryWrites.submit(request.writer,()->{
            boolean committed=false;
            try{synchronized(this){
                if(isCurrent(request)){
                    SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
                    try{
                        if(!isCurrent(request))throw new IllegalStateException("Reset request invalidated");
                        db.delete("timeline_appearances",null,null);
                        db.delete("timeline_seen",null,null);
                        db.delete("timeline_batches",null,null);
                        db.delete("timeline_snapshots",null,null);
                        db.delete("timeline_snapshots_fts",null,null);
                        db.execSQL("UPDATE timeline_scopes SET generation=generation+1,pending_clear=0");
                        long wall=clock.wall(),elapsed=clock.elapsed();String boot=clock.boot();
                        // Validate the new native clock sample before committing any erasure.
                        TimelineBufferPolicy.advance(null,wall,elapsed,boot);
                        db.execSQL("UPDATE timeline_state SET enabled=0,consent_generation=consent_generation+1,policy_version=policy_version+1,logical_now_ms=?,wall_ms=?,elapsed_ms=?,boot_id=?,clock_paused=0,pause_reason='',dropped_count=0 WHERE id=1",new Object[]{wall,wall,elapsed,boot});
                        db.setTransactionSuccessful();
                    }finally{db.endTransaction();}
                    committed=true;
                    reclaim(db,readSettings(db).budgetMiB*1048576L);
                }
            }}catch(RuntimeException unavailable){/* Failed transactions preserve both the old clock domain and old rows. */}
            final boolean result=committed;main.post(()->{if(callback!=null)callback.onComplete(result);});
        },skipped);
        if(!queued)skipped.run();
    }
    private TimelineBufferPolicy.ClockResult now(SQLiteDatabase db){
        if(!db.inTransaction()){
            long budget=readSettings(db).budgetMiB*1048576L;
            if(actualBytes()+65536L>budget){
                try(Cursor checkpoint=db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)",null)){checkpoint.moveToFirst();}
                if(actualBytes()+65536L>budget){reclaimReason="READERS_BUSY";throw new IllegalStateException("Temporary budget requires reclamation");}
            }
        }
        TimelineBufferPolicy.ClockState previous;
        try(Cursor c=db.rawQuery("SELECT logical_now_ms,wall_ms,elapsed_ms,boot_id,clock_paused FROM timeline_state WHERE id=1",null)){
            if(!c.moveToFirst())throw new IllegalStateException("Missing timeline clock");previous=new TimelineBufferPolicy.ClockState(c.getLong(0),c.getLong(1),c.getLong(2),c.getString(3),c.getInt(4)!=0);
        }
        TimelineBufferPolicy.ClockResult result=TimelineBufferPolicy.advance(previous,clock.wall(),clock.elapsed(),clock.boot());
        TimelineBufferPolicy.ClockState state=result.state;
        db.execSQL("UPDATE timeline_state SET logical_now_ms=?,wall_ms=?,elapsed_ms=?,boot_id=?,clock_paused=?,pause_reason=CASE WHEN ?=1 THEN 'CLOCK_CHANGED' WHEN pause_reason='CLOCK_CHANGED' THEN '' ELSE pause_reason END WHERE id=1",new Object[]{state.logicalNowMs,state.wallMs,state.elapsedMs,state.bootId,state.paused?1:0,state.paused?1:0});
        return result;
    }
    private void requireLibrary(String library){if(!TimelineBufferPolicy.validLibrary(library)||XsLibraryWrites.isDeleted(library)||XsDeletedLibraries.isDeleted(app,library))throw new IllegalStateException("Library unavailable");}
    public synchronized Ticket ticket(String library){
        requireLibrary(library);SQLiteDatabase db=getWritableDatabase();
        if(scalar(db,"SELECT COUNT(*) FROM timeline_scopes WHERE library_id=?",library)==0&&scalar(db,"SELECT COUNT(*) FROM timeline_scopes")>=4096)throw new IllegalStateException("Temporary scope registry full");
        db.execSQL("INSERT OR IGNORE INTO timeline_scopes(library_id,generation) VALUES (?,0)",new Object[]{library});
        return new Ticket(this,library,scalar(db,"SELECT generation FROM timeline_scopes WHERE library_id=?",library),scalar(db,"SELECT consent_generation FROM timeline_state WHERE id=1"),XsLibraryWrites.ticket(library));
    }
    public XsLibraryWrites.Ticket writerTicket(Ticket ticket){return ticket==null?null:ticket.writer;}
    public synchronized boolean isCurrent(Ticket ticket){
        if(ticket==null||ticket.owner!=this||!XsLibraryWrites.isCurrent(ticket.writer)||XsDeletedLibraries.isDeleted(app,ticket.library))return false;
        SQLiteDatabase db=getWritableDatabase();
        return ticket.generation==scalar(db,"SELECT generation FROM timeline_scopes WHERE library_id=?",ticket.library)&&ticket.consent==scalar(db,"SELECT consent_generation FROM timeline_state WHERE id=1")&&scalar(db,"SELECT pending_clear FROM timeline_scopes WHERE library_id=?",ticket.library)==0;
    }
    public boolean queue(Ticket ticket,Batch batch,Candidate candidate,Callback callback){return queue(ticket,batch,candidate,()->true,callback);}
    /** Revalidate controller consent/revocation at actual persistence, after any queue delay. */
    public boolean queue(Ticket ticket,Batch batch,Candidate candidate,java.util.function.BooleanSupplier admitted,Callback callback){
        Handler main=new Handler(Looper.getMainLooper());
        Runnable skipped=()->main.post(()->{if(callback!=null)callback.onComplete(false);});
        if(ticket==null){skipped.run();return false;}
        boolean queued=XsLibraryWrites.submit(ticket.writer,()->{
            boolean accepted=false;try{synchronized(this){accepted=admitted!=null&&admitted.getAsBoolean()&&append(ticket,batch,candidate);}}catch(RuntimeException unavailable){/* Fixed result only. */}
            final boolean result=accepted;main.post(()->{if(callback!=null)callback.onComplete(result);});
        },skipped);
        if(!queued){recordAdmissionDrops(1,"QUEUE_FULL");skipped.run();}return queued;
    }
    private boolean valid(Batch batch,Candidate candidate){
        if(batch==null||candidate==null||!TimelineBufferPolicy.validIdentity(batch.id)||!validReason(batch.reason)||!validSource(batch.source)||candidate.sequence<0||candidate.observedAt<=0)return false;
        if(PostUrls.extractPostId(candidate.url)==null||candidate.url.length()>2048||!TimelineBufferPolicy.validText(candidate.author,100)||!TimelineBufferPolicy.validText(candidate.body,16000))return false;
        if(candidate.publishedAt!=null&&candidate.publishedAt<=0)return false;
        return candidate.quality==SnapshotPolicy.Quality.PARTIAL||candidate.quality==SnapshotPolicy.Quality.LINK_ONLY;
    }
    private static boolean validSource(String source){return "home_for_you".equals(source)||"home_following".equals(source)||"search_results".equals(source)||"unknown".equals(source);}
    private static boolean validReason(String reason){return "native_navigation".equals(reason)||"native_refresh".equals(reason)||"native_stream_change".equals(reason)||"native_stream_switch".equals(reason)||"suspected_refresh".equals(reason)||"native_session".equals(reason);}
    /** Run from the owning shared writer. The queued immutable candidate retains its native capture time. */
    public synchronized boolean append(Ticket ticket,Batch batch,Candidate candidate){
        if(!valid(batch,candidate)||!isCurrent(ticket))return false;
        SQLiteDatabase db=getWritableDatabase();Settings settings=readSettings(db);
        TimelineBufferPolicy.ClockResult time=now(db);
        if(!settings.enabled||!time.allowCapture){drop(db,time.allowCapture?"DISABLED":"CLOCK_CHANGED");return false;}
        // Native observations may wait in the bounded queue; arbitrary future/past payload times fail closed.
        if(candidate.observedAt>clock.wall()+60000L||TimelineBufferPolicy.isExpired(TimelineBufferPolicy.expiresAt(candidate.observedAt,settings.hours),time.nowMs)){drop(db,"EXPIRED_BEFORE_WRITE");return false;}
        long reserve=WRITE_RESERVE+4L*(candidate.body==null?0:candidate.body.length())+4L*(candidate.author==null?0:candidate.author.length());
        if(!enforceLimits(reserve)){drop(db,"SPACE_BUDGET");return false;}
        db.beginTransaction();
        try{
            if(!isCurrent(ticket)||!readSettings(db).enabled||scalar(db,"SELECT pending_clear FROM timeline_scopes WHERE library_id=?",ticket.library)!=0)return false;
            String post=PostUrls.extractPostId(candidate.url);String body=candidate.body==null?"":candidate.body;
            String author=candidate.author==null?"":candidate.author;
            long seenAt=0,seenExpiry=0,seenOrdinal=candidate.sequence;
            try(Cursor seen=db.rawQuery("SELECT first_at_ms,expires_at_ms,ordinal FROM timeline_seen WHERE library_id=? AND batch_id=? AND post_id=?",new String[]{ticket.library,batch.id,post})){if(seen.moveToFirst()){seenAt=seen.getLong(0);seenExpiry=seen.getLong(1);seenOrdinal=seen.getLong(2);}}
            if(seenAt>0&&seenExpiry<=time.nowMs){drop(db,"EXPIRED_BEFORE_WRITE");db.setTransactionSuccessful();return false;}
            if(seenAt==0&&(scalar(db,"SELECT COUNT(*) FROM timeline_seen")>=40000||scalar(db,"SELECT COUNT(*) FROM timeline_batches")>=TimelineBufferPolicy.MAX_BATCHES&&scalar(db,"SELECT COUNT(*) FROM timeline_batches WHERE library_id=? AND batch_id=?",ticket.library,batch.id)==0)){drop(db,"ROW_BUDGET");db.setTransactionSuccessful();return false;}
            SnapshotPolicy.Quality quality=body.isEmpty()?SnapshotPolicy.Quality.LINK_ONLY:SnapshotPolicy.Quality.PARTIAL;
            String hash=SnapshotPolicy.hash(author+"\n"+body+"\n"+(candidate.publishedAt==null?"":candidate.publishedAt)+"\n"+quality.name());
            ContentValues batchValues=new ContentValues();batchValues.put("library_id",ticket.library);batchValues.put("batch_id",batch.id);batchValues.put("created_at_ms",candidate.observedAt);batchValues.put("reason",batch.reason);batchValues.put("source",batch.source);
            db.insertWithOnConflict("timeline_batches",null,batchValues,SQLiteDatabase.CONFLICT_IGNORE);
            try(Cursor existing=db.rawQuery("SELECT reason,source FROM timeline_batches WHERE library_id=? AND batch_id=?",new String[]{ticket.library,batch.id})){
                if(!existing.moveToFirst()||!batch.reason.equals(existing.getString(0))||!batch.source.equals(existing.getString(1)))return false;
            }
            if(seenAt==0){ContentValues seen=new ContentValues();seen.put("library_id",ticket.library);seen.put("batch_id",batch.id);seen.put("post_id",post);seen.put("first_at_ms",candidate.observedAt);seen.put("expires_at_ms",TimelineBufferPolicy.expiresAt(candidate.observedAt,settings.hours));seen.put("ordinal",candidate.sequence);db.insertOrThrow("timeline_seen",null,seen);}
            ContentValues snapshot=new ContentValues();snapshot.put("library_id",ticket.library);snapshot.put("post_id",post);snapshot.put("content_hash",hash);snapshot.put("author_handle",author);snapshot.put("body",body);
            if(candidate.publishedAt!=null)snapshot.put("published_at_ms",candidate.publishedAt);snapshot.put("quality",quality.name());snapshot.put("provenance","offline_dom_fixture");snapshot.put("captured_at_ms",candidate.observedAt);
            db.insertWithOnConflict("timeline_snapshots",null,snapshot,SQLiteDatabase.CONFLICT_IGNORE);
            long snapshotId=scalar(db,"SELECT id FROM timeline_snapshots WHERE library_id=? AND post_id=? AND content_hash=?",ticket.library,post,hash);
            long appearance=scalar(db,"SELECT id FROM timeline_appearances WHERE library_id=? AND batch_id=? AND post_id=?",ticket.library,batch.id,post);
            if(appearance==0&&seenAt>0){pruneOrphans(db);drop(db,"CAPACITY_EVICTION");db.setTransactionSuccessful();return false;}
            if(appearance==0){
                ContentValues row=new ContentValues();row.put("library_id",ticket.library);row.put("batch_id",batch.id);row.put("post_id",post);row.put("snapshot_id",snapshotId);row.put("ordinal",seenOrdinal);row.put("first_at_ms",seenAt==0?candidate.observedAt:seenAt);row.put("last_at_ms",candidate.observedAt);row.put("expires_at_ms",seenAt==0?TimelineBufferPolicy.expiresAt(candidate.observedAt,settings.hours):seenExpiry);row.put("promotion_id","timeline:"+SnapshotPolicy.hash(ticket.library+"\n"+batch.id+"\n"+post));db.insertOrThrow("timeline_appearances",null,row);
            }else{
                // Enrich without moving the first capture/order/TTL. Never replace a longer visible body with an empty rebound card.
                db.execSQL("UPDATE timeline_appearances SET last_at_ms=MAX(last_at_ms,?),snapshot_id=CASE WHEN LENGTH((SELECT body FROM timeline_snapshots WHERE id=?))>=LENGTH((SELECT body FROM timeline_snapshots WHERE id=snapshot_id)) THEN ? ELSE snapshot_id END WHERE id=?",new Object[]{candidate.observedAt,snapshotId,snapshotId,appearance});
            }
            pruneOrphans(db);enforceRowCaps(db);db.execSQL("UPDATE timeline_state SET pause_reason='' WHERE id=1 AND clock_paused=0");
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        // The reservation above is conservative. If SQLite's measured allocation exceeds it, reclaim and stop further writes.
        if(actualBytes()>settings.budgetMiB*1048576L){enforceLimits(WRITE_RESERVE);return false;}
        scheduleMaintenance(app);return true;
    }
    private void drop(SQLiteDatabase db,String reason){db.execSQL("UPDATE timeline_state SET dropped_count=MIN(dropped_count+1,2147483647),pause_reason=? WHERE id=1",new Object[]{reason});}
    private int pruneExpired(SQLiteDatabase db,long now){int deleted=db.delete("timeline_appearances","expires_at_ms<=?",new String[]{Long.toString(now)});pruneOrphans(db);return deleted;}
    private void pruneOrphans(SQLiteDatabase db){
        db.execSQL("DELETE FROM timeline_snapshots WHERE NOT EXISTS(SELECT 1 FROM timeline_appearances a WHERE a.snapshot_id=timeline_snapshots.id)");
        // No-body seen receipts prevent expired/evicted cards from renewing the same native batch TTL.
        long cutoff=scalar(db,"SELECT logical_now_ms FROM timeline_state WHERE id=1")-168L*3600000L;
        db.delete("timeline_seen","first_at_ms<=?",new String[]{Long.toString(cutoff)});
        db.execSQL("DELETE FROM timeline_batches WHERE created_at_ms<=? AND NOT EXISTS(SELECT 1 FROM timeline_appearances a WHERE a.library_id=timeline_batches.library_id AND a.batch_id=timeline_batches.batch_id)",new Object[]{cutoff});
    }
    private void enforceRowCaps(SQLiteDatabase db){
        long count=scalar(db,"SELECT COUNT(*) FROM timeline_appearances");
        if(count>TimelineBufferPolicy.MAX_APPEARANCES)evict(db,(int)(count-TimelineBufferPolicy.MAX_APPEARANCES));

    }
    private int evict(SQLiteDatabase db,int count){
        int removed=db.delete("timeline_appearances","id IN(SELECT id FROM timeline_appearances ORDER BY first_at_ms,id LIMIT ?)",new String[]{Integer.toString(count)});
        if(removed>0)db.execSQL("UPDATE timeline_state SET dropped_count=MIN(dropped_count+?,2147483647),pause_reason='CAPACITY_EVICTION' WHERE id=1",new Object[]{removed});pruneOrphans(db);return removed;
    }
    private boolean enforceLimits(long reserve){
        SQLiteDatabase db=getWritableDatabase();Settings policy=readSettings(db);long limit=policy.budgetMiB*1048576L;
        db.beginTransaction();try{pruneExpired(db,now(db).nowMs);enforceRowCaps(db);db.setTransactionSuccessful();}finally{db.endTransaction();}
        if(actualBytes()+reserve+TimelineBufferPolicy.MIN_HEADROOM_BYTES<=limit&&freeBytes()>reserve+TimelineBufferPolicy.MIN_HEADROOM_BYTES)return true;
        reclaim(db,limit);
        for(int turn=0;turn<160&&(actualBytes()+reserve+TimelineBufferPolicy.MIN_HEADROOM_BYTES>limit||freeBytes()<=reserve+TimelineBufferPolicy.MIN_HEADROOM_BYTES);turn++){
            db.beginTransaction();int removed;try{removed=evict(db,256);db.setTransactionSuccessful();}finally{db.endTransaction();}
            reclaim(db,limit);if(removed==0)break;
        }
        boolean fits=actualBytes()+reserve+TimelineBufferPolicy.MIN_HEADROOM_BYTES<=limit&&freeBytes()>reserve+TimelineBufferPolicy.MIN_HEADROOM_BYTES;
        if(!fits)db.execSQL("UPDATE timeline_state SET pause_reason='SPACE_BUDGET' WHERE id=1");return fits;
    }
    public synchronized long actualBytes(){
        // All scopes share this file. Include sidecars and any SQLite vacuum/temp sidecar, not character estimates.
        long bytes=0;File parent=file.getParentFile();File[] files=parent==null?null:parent.listFiles();
        if(files!=null)for(File candidate:files)if(candidate.isFile()&&(candidate.getName().equals(file.getName())||candidate.getName().startsWith(file.getName()+"-")))bytes+=candidate.length();
        return bytes;
    }
    private long freeBytes(){return file.getParentFile()==null?0:file.getParentFile().getUsableSpace();}
    private void reclaim(SQLiteDatabase db,long budget){
        reclaimReason="";
        try{
            try(Cursor c=db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)",null)){if(c.moveToFirst()&&c.getInt(0)!=0){reclaimReason="READERS_BUSY";return;}}
            long bytes=actualBytes();
            // FTS optimize and VACUUM may each need another database's worth of space. Reserve the peak first.
            if(bytes*2+TimelineBufferPolicy.MIN_HEADROOM_BYTES>budget||freeBytes()<bytes+TimelineBufferPolicy.MIN_HEADROOM_BYTES){reclaimReason="COMPACTION_HEADROOM";return;}
            db.execSQL("INSERT INTO timeline_snapshots_fts(timeline_snapshots_fts) VALUES('optimize')");
            try(Cursor c=db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)",null)){if(c.moveToFirst()&&c.getInt(0)!=0){reclaimReason="READERS_BUSY";return;}}
            bytes=actualBytes();if(bytes*2+TimelineBufferPolicy.MIN_HEADROOM_BYTES>budget||freeBytes()<bytes+TimelineBufferPolicy.MIN_HEADROOM_BYTES){reclaimReason="COMPACTION_HEADROOM";return;}
            db.execSQL("VACUUM");
            try(Cursor c=db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)",null)){if(c.moveToFirst()&&c.getInt(0)!=0)reclaimReason="READERS_BUSY";}
        }catch(RuntimeException busy){reclaimReason="RECLAIM_PENDING";}
    }
    public synchronized void maintain(){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            pruneExpired(db,now(db).nowMs);
            try(Cursor c=db.rawQuery("SELECT library_id,pending_clear FROM timeline_scopes",null)){List<String> deleted=new ArrayList<>();while(c.moveToNext())if(c.getInt(1)!=0||XsLibraryWrites.isDeleted(c.getString(0))||XsDeletedLibraries.isDeleted(app,c.getString(0)))deleted.add(c.getString(0));for(String library:deleted)eraseRows(db,library);}
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        enforceLimits(WRITE_RESERVE);reclaim(db,readSettings(db).budgetMiB*1048576L);
    }
    /** Production maintenance uses the existing bounded scope writers; no IO runs on the caller. */
    public void maintainAsync(java.util.function.BooleanSupplier continueRunning,Callback callback){
        final List<String> scopes=new ArrayList<>();
        try{synchronized(this){try(Cursor c=getWritableDatabase().rawQuery("SELECT library_id FROM timeline_scopes ORDER BY library_id LIMIT 4096",null)){while(c.moveToNext())scopes.add(c.getString(0));}}}
        catch(RuntimeException unavailable){finishMaintenance(callback,false);return;}
        dispatchMaintenance(scopes,0,true,continueRunning,callback);
    }
    private void finishMaintenance(Callback callback,boolean success){if(callback!=null)new Handler(Looper.getMainLooper()).post(()->callback.onComplete(success));}
    private void dispatchMaintenance(List<String> scopes,int index,boolean previous,java.util.function.BooleanSupplier running,Callback callback){
        if(running==null||!running.getAsBoolean()){finishMaintenance(callback,false);return;}
        if(index>=scopes.size()){finishMaintenance(callback,previous);return;}
        String library=scopes.get(index);
        Runnable skipped=()->new Handler(Looper.getMainLooper()).post(()->dispatchMaintenance(scopes,index+1,false,running,callback));
        Runnable work=()->{
            boolean ok=false;
            try{if(running.getAsBoolean()){maintainScope(library);ok=true;}}catch(RuntimeException unavailable){/* Retry in a later deferrable pass. */}
            dispatchMaintenance(scopes,index+1,previous&&ok,running,callback);
        };
        boolean accepted;
        try{
            if(XsLibraryWrites.isDeleted(library)||XsDeletedLibraries.isDeleted(app,library)){
                XsDeletedLibraries.initialize(app);accepted=XsLibraryWrites.submitDeletion(library,work);
            }else accepted=XsLibraryWrites.submit(XsLibraryWrites.ticket(library),work,skipped);
        }catch(RuntimeException unavailable){accepted=false;}
        if(!accepted)skipped.run();
    }
    private synchronized void maintainScope(String library){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            long at=now(db).nowMs;
            if(XsLibraryWrites.isDeleted(library)||XsDeletedLibraries.isDeleted(app,library)||scalar(db,"SELECT pending_clear FROM timeline_scopes WHERE library_id=?",library)!=0)eraseRows(db,library);
            else {db.delete("timeline_appearances","library_id=? AND expires_at_ms<=?",new String[]{library,Long.toString(at)});pruneOrphans(db);}
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        // Global budget/oldest eviction remains protected by the application-wide store lock.
        enforceLimits(WRITE_RESERVE);reclaim(db,readSettings(db).budgetMiB*1048576L);
    }
    private static final String ENTRY_SELECT="SELECT a.id,a.library_id,a.post_id,s.body,s.author_handle,s.published_at_ms,a.first_at_ms,b.source,a.batch_id,b.reason,a.ordinal,s.quality,s.provenance,a.promotion_id,a.saved_at_ms,a.favorited_at_ms,s.captured_at_ms FROM timeline_appearances a JOIN timeline_snapshots s ON s.id=a.snapshot_id JOIN timeline_batches b ON b.library_id=a.library_id AND b.batch_id=a.batch_id ";
    public synchronized List<Entry> list(String library,String query,String batchId,int limit,int offset){
        requireLibrary(library);if(limit<1||limit>TimelineBufferPolicy.MAX_PAGE||offset<0||query!=null&&query.length()>256)throw new IllegalArgumentException("Bounded query required");
        SQLiteDatabase db=getWritableDatabase();long now=now(db).nowMs;if(scalar(db,"SELECT pending_clear FROM timeline_scopes WHERE library_id=?",library)!=0)return new ArrayList<>();
        StringBuilder sql=new StringBuilder(ENTRY_SELECT+"WHERE a.library_id=? AND a.expires_at_ms>?");List<String> args=new ArrayList<>();args.add(library);args.add(Long.toString(now));
        if(batchId!=null&&!batchId.isEmpty()){sql.append(" AND a.batch_id=?");args.add(batchId);}
        String keyword=query==null?"":query.trim();
        if(!keyword.isEmpty()){
            if(keyword.matches("[A-Za-z0-9]+(?:[ \\t]+[A-Za-z0-9]+)*")){
                String[] words=keyword.toLowerCase(Locale.ROOT).split("[ \\t]+");StringBuilder term=new StringBuilder();for(String word:words){if(term.length()>0)term.append(' ');term.append(word).append('*');}
                sql.append(" AND s.id IN(SELECT rowid FROM timeline_snapshots_fts WHERE timeline_snapshots_fts MATCH ?)");args.add(term.toString());
            }else{sql.append(" AND (s.body LIKE ? ESCAPE '!' OR s.author_handle LIKE ? ESCAPE '!' OR a.batch_id LIKE ? ESCAPE '!')");String term="%"+keyword.replace("!","!!").replace("%","!%").replace("_","!_")+"%";args.add(term);args.add(term);args.add(term);}
        }
        sql.append(" ORDER BY b.created_at_ms DESC,b.batch_id,a.ordinal,a.id LIMIT ? OFFSET ?");args.add(Integer.toString(limit));args.add(Integer.toString(offset));
        List<Entry> entries=new ArrayList<>();try(Cursor c=db.rawQuery(sql.toString(),args.toArray(new String[0]))){while(c.moveToNext())entries.add(new Entry(c));}return entries;
    }
    public synchronized Entry entry(String library,long appearanceId){
        requireLibrary(library);SQLiteDatabase db=getWritableDatabase();long now=now(db).nowMs;if(scalar(db,"SELECT pending_clear FROM timeline_scopes WHERE library_id=?",library)!=0)return null;
        try(Cursor c=db.rawQuery(ENTRY_SELECT+"WHERE a.library_id=? AND a.id=? AND a.expires_at_ms>?",new String[]{library,Long.toString(appearanceId),Long.toString(now)})){return c.moveToFirst()?new Entry(c):null;}
    }
    public synchronized Entry entry(Ticket ticket,long appearanceId){return isCurrent(ticket)?entry(ticket.library,appearanceId):null;}
    public synchronized List<Batch> batches(String library){
        requireLibrary(library);SQLiteDatabase db=getWritableDatabase();long now=now(db).nowMs;List<Batch> rows=new ArrayList<>();if(scalar(db,"SELECT pending_clear FROM timeline_scopes WHERE library_id=?",library)!=0)return rows;
        try(Cursor c=db.rawQuery("SELECT b.batch_id,b.reason,b.source,b.created_at_ms,COUNT(a.id) FROM timeline_batches b JOIN timeline_appearances a ON a.library_id=b.library_id AND a.batch_id=b.batch_id WHERE b.library_id=? AND a.expires_at_ms>? GROUP BY b.library_id,b.batch_id ORDER BY b.created_at_ms DESC LIMIT 1024",new String[]{library,Long.toString(now)})){while(c.moveToNext())rows.add(new Batch(c.getString(0),c.getString(1),c.getString(2),c.getLong(3),c.getLong(4)));}return rows;
    }
    /** Native logical clock, for UI expiry while an unchanged list remains open. */
    public synchronized long nextExpiryDelayMillis(String library){
        requireLibrary(library);SQLiteDatabase db=getWritableDatabase();long at=now(db).nowMs;if(scalar(db,"SELECT pending_clear FROM timeline_scopes WHERE library_id=?",library)!=0)return -1;
        try(Cursor c=db.rawQuery("SELECT MIN(expires_at_ms) FROM timeline_appearances WHERE library_id=? AND expires_at_ms>?",new String[]{library,Long.toString(at)})){return c.moveToFirst()&&!c.isNull(0)?Math.max(0,c.getLong(0)-at):-1;}
    }
    /** Bounded fixed-code admission telemetry only; never stores page data or exception details. */
    public synchronized void recordAdmissionDrops(long count,String reason){
        if(count<=0)return;
        String fixed="NATIVE_ADMISSION_LIMIT";
        if("QUEUE_FULL".equals(reason)||"ROW_BUDGET".equals(reason))fixed=reason;
        try{
            SQLiteDatabase db=getWritableDatabase();
            // Telemetry cannot consume the last write reservation or crash the WebView on full disk.
            if(actualBytes()+65536L>readSettings(db).budgetMiB*1048576L)return;
            db.execSQL("UPDATE timeline_state SET dropped_count=MIN(dropped_count+?,2147483647),pause_reason=? WHERE id=1",new Object[]{Math.min(count,2147483647L),fixed});
        }catch(RuntimeException unavailable){/* Best-effort fixed counter; never page text or exception details. */}
    }
    public synchronized Stats stats(String library){
        requireLibrary(library);SQLiteDatabase db=getWritableDatabase();long now=now(db).nowMs;long count=0,oldest=0,latest=0,dropped=0;String pause="";
        try(Cursor c=db.rawQuery("SELECT COUNT(*),MIN(first_at_ms),MAX(first_at_ms) FROM timeline_appearances WHERE library_id=? AND expires_at_ms>?",new String[]{library,Long.toString(now)})){if(c.moveToFirst()&&scalar(db,"SELECT pending_clear FROM timeline_scopes WHERE library_id=?",library)==0){count=c.getLong(0);oldest=c.getLong(1);latest=c.getLong(2);}}
        try(Cursor c=db.rawQuery("SELECT dropped_count,pause_reason FROM timeline_state WHERE id=1",null)){if(c.moveToFirst()){dropped=c.getLong(0);pause=c.getString(1);}}
        return new Stats(count,oldest,latest,actualBytes(),dropped,pause,reclaimReason);
    }
    public synchronized boolean acknowledgePromotion(String library,long appearanceId,String promotionId,boolean favorite,long savedAt){
        requireLibrary(library);if(savedAt<=0||promotionId==null)return false;SQLiteDatabase db=getWritableDatabase();long now=now(db).nowMs;
        ContentValues update=new ContentValues();update.put("saved_at_ms",savedAt);if(favorite)update.put("favorited_at_ms",savedAt);
        return db.update("timeline_appearances",update,"library_id=? AND id=? AND promotion_id=? AND expires_at_ms>?",new String[]{library,Long.toString(appearanceId),promotionId,Long.toString(now)})==1;
    }
    /** Buffer-only generation is advanced before the queued barrier. Existing media tickets survive. */
    public void clear(String library,Callback callback){
        Handler main=new Handler(Looper.getMainLooper());Ticket current;
        try{synchronized(this){requireLibrary(library);current=ticket(library);getWritableDatabase().execSQL("UPDATE timeline_scopes SET generation=generation+1,pending_clear=1 WHERE library_id=?",new Object[]{library});}}
        catch(RuntimeException unavailable){main.post(()->{if(callback!=null)callback.onComplete(false);});return;}
        Runnable skipped=()->main.post(()->{if(callback!=null)callback.onComplete(false);});
        boolean queued=XsLibraryWrites.submit(current.writer,()->{
            boolean success=false;try{synchronized(this){SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{eraseRows(db,library);db.setTransactionSuccessful();}finally{db.endTransaction();}reclaim(db,readSettings(db).budgetMiB*1048576L);success=true;}}catch(RuntimeException unavailable){/* Scope remains invalidated. */}
            final boolean ok=success;main.post(()->{if(callback!=null)callback.onComplete(ok);});
        },skipped);if(!queued)skipped.run();
    }
    private void eraseRows(SQLiteDatabase db,String library){db.delete("timeline_appearances","library_id=?",new String[]{library});db.delete("timeline_seen","library_id=?",new String[]{library});db.delete("timeline_batches","library_id=?",new String[]{library});db.execSQL("UPDATE timeline_scopes SET pending_clear=0 WHERE library_id=?",new Object[]{library});pruneOrphans(db);}
    /** Called inside the existing durable whole-library deletion barrier; revocation remains external/durable. */
    synchronized void eraseLibrary(String library){SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{db.execSQL("UPDATE timeline_scopes SET generation=generation+1 WHERE library_id=?",new Object[]{library});eraseRows(db,library);db.setTransactionSuccessful();}finally{db.endTransaction();}reclaim(db,readSettings(db).budgetMiB*1048576L);}
    public static void scheduleMaintenance(Context context){
        try{
            JobScheduler scheduler=context.getSystemService(JobScheduler.class);if(scheduler==null)return;
            if(scheduler.getPendingJob(MAINTENANCE_JOB)!=null)return;
            scheduler.schedule(new JobInfo.Builder(MAINTENANCE_JOB,new ComponentName(context,XsTimelineMaintenanceJobService.class)).setPersisted(true).setPeriodic(6*3600000L).setRequiresCharging(false).build());
        }catch(RuntimeException unavailable){/* Query-time expiry still applies if Android refuses scheduling. */}
    }
}

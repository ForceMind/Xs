package dev.forcemind.xs.android;

import android.content.Context;
import android.database.Cursor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.forcemind.xs.core.ArchiveSchema;
import dev.forcemind.xs.core.ArchiveSearch;
import dev.forcemind.xs.core.LibraryBackupCodec;
import dev.forcemind.xs.core.OwnActionSession;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

/** Real Android SQLite/FTS transactions; synthetic local metadata, no X or document-provider claims. */
@RunWith(AndroidJUnit4.class)
public final class LibraryBackupIntegrationTest {
    private Context app;
    private XsArchiveDatabase db;
    private String source,defaultLibrary;
    private XsLibraryWrites.Ticket ticket;
    private File sentinel;

    @Before public void open()throws Exception {
        app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        XsCaptureBridge.unbindAccount();
        app.deleteDatabase("xs-local-history.db");
        db=new XsArchiveDatabase(app);source="local:backup-test:"+UUID.randomUUID();
        defaultLibrary=XsLocalSpaces.defaultLibrary(app);ticket=XsLibraryWrites.ticket(source);
        db.ensureLibrary(source,"中文资料 📚");
    }
    @After public void close()throws Exception {
        CountDownLatch idle=new CountDownLatch(1);
        assertTrue(XsLibraryWrites.invalidateAndSubmit(source,idle::countDown));
        assertTrue(idle.await(10,TimeUnit.SECONDS));
        db.close();app.deleteDatabase("xs-local-history.db");
        if(sentinel!=null)assertTrue(sentinel.delete());
        app.getSharedPreferences("backup-private-sentinel",Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void metadataRoundTripPreservesUnicodeTimesDwellAndSearchWithoutPrivateFiles()throws Exception {
        seed();
        String secret="PRIVATE_COOKIE_MEDIA_KEY_SENTINEL_"+UUID.randomUUID();
        sentinel=new File(app.getFilesDir(),"backup-media-sentinel.bin");
        try(FileOutputStream out=new FileOutputStream(sentinel)){out.write(secret.getBytes(StandardCharsets.UTF_8));}
        app.getSharedPreferences("backup-private-sentinel",Context.MODE_PRIVATE).edit().putString("token",secret).commit();
        byte[] bytes=serialized();String text=new String(bytes,StandardCharsets.UTF_8);
        assertFalse(text.contains(secret));assertFalse(text.contains("account_id"));assertFalse(text.contains(sentinel.getAbsolutePath()));
        XsLibraryBackup.ValidatedBackup backup=XsLibraryBackup.inspect(new ByteArrayInputStream(bytes));
        assertEquals(ArchiveSchema.VERSION,backup.schemaVersion());assertEquals(2,backup.rows("posts").size());
        assertEquals(2,backup.rows("history_events").size());assertEquals(1,backup.rows("own_actions").size());
        assertEquals(2,backup.rows("own_action_events").size());
        String restored=queued(()->XsLibraryBackup.restoreNewLibrary(app,backup,null));
        assertNotEquals(source,restored);assertTrue(restored.startsWith("local:"));assertEquals(defaultLibrary,XsLocalSpaces.defaultLibrary(app));
        assertEquals(2,count("posts",source));assertEquals(2,count("posts",restored));assertTrue(sentinel.exists());
        ArchiveSearch.Filter q=new ArchiveSearch.Filter();q.keyword="中文";q.tag="研发";q.categoryId="work";q.favoriteOnly=true;
        try(Cursor found=db.search(restored,q)) {
            assertEquals(1,found.getCount());assertTrue(found.moveToFirst());assertEquals("7101",found.getString(1));
            assertEquals("中文离线正文 🚀\nsecond line",found.getString(3));assertEquals(1,found.getInt(7));
            assertEquals("备注 🧪\nnot a path: ../../outside",found.getString(11));
        }
        q=new ArchiveSearch.Filter();q.keyword="dwellneedle";q.neverOpened=true;q.minDwellMs=3000L;q.sortByDwell=true;
        try(Cursor found=db.search(restored,q)){assertEquals(1,found.getCount());assertTrue(found.moveToFirst());assertEquals(3500L,found.getLong(9));assertEquals("SAMPLED_LOWER_BOUND",found.getString(13));}
        try(Cursor found=db.getReadableDatabase().rawQuery("SELECT event_at_ms,event_key,duration_ms,threshold_ms,duration_quality FROM history_events WHERE account_id=? ORDER BY event_at_ms",new String[]{restored})) {
            assertTrue(found.moveToFirst());assertEquals(2000L,found.getLong(0));assertEquals("open:original",found.getString(1));assertTrue(found.isNull(2));
            assertTrue(found.moveToNext());assertEquals(3000L,found.getLong(0));assertEquals("feed:original",found.getString(1));assertEquals(3500L,found.getLong(2));assertEquals(3000L,found.getLong(3));
        }
        q=new ArchiveSearch.Filter();q.actionType="REPOST";q.actionState="REVERSED";
        try(Cursor found=db.search(restored,q)){assertEquals(1,found.getCount());}
        try(Cursor found=db.getReadableDatabase().rawQuery("SELECT state,action_at_ms,observed_at_ms,actor_provenance,actor_id FROM own_actions WHERE account_id=?",new String[]{restored})) {
            assertTrue(found.moveToFirst());assertEquals("REVERSED",found.getString(0));assertEquals(3100L,found.getLong(1));assertEquals(3200L,found.getLong(2));assertEquals("NATIVE_FIXTURE",found.getString(3));assertEquals("fixture:me",found.getString(4));
        }
        // Imported provenance is historical metadata; it creates no native session admission.
        assertFalse(db.recordPersonalAction(restored,"https://x.com/demo/status/7101","REPOST","demo","fake",null,3300L,3400L));
    }

    @Test public void schemaThreeRestoreProjectsUnknownOwnActionsWithoutInventedTimesOrActor()throws Exception {
        seed();Map<String,List<Map<String,Object>>> rows=mutable(inspect());rows.remove("own_actions");rows.remove("own_action_events");
        rows.get("user_actions").add(row("post_id","7101","action_type","ORIGINAL","action_at_ms",1500L,"source","local_x_archive"));
        rows.get("user_actions").add(row("post_id","7102","action_type","REPOST","action_at_ms",null,"source","legacy_fixture"));
        ByteArrayOutputStream out=new ByteArrayOutputStream();XsLibraryBackup.writeCandidate(rows,3,"v3 中文",out);
        XsLibraryBackup.ValidatedBackup old=XsLibraryBackup.inspect(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(3,old.sourceSchemaVersion());assertEquals(4,old.schemaVersion());assertEquals(2,old.rows("own_actions").size());
        String restored=queued(()->XsLibraryBackup.restoreNewLibrary(app,old,null));
        for(String type:new String[]{"ORIGINAL","REPOST"}){ArchiveSearch.Filter q=new ArchiveSearch.Filter();q.actionType=type;q.actionState="UNKNOWN";try(Cursor c=db.search(restored,q)){assertEquals(1,c.getCount());}}
        try(Cursor c=db.getReadableDatabase().rawQuery("SELECT action_type,actor_id,actor_provenance,source,action_at_ms,observed_at_ms,state,state_at_ms FROM own_actions WHERE account_id=? ORDER BY action_type",new String[]{restored})) {
            assertTrue(c.moveToFirst());assertEquals("ORIGINAL",c.getString(0));assertTrue(c.isNull(1));assertEquals("USER_CONFIRMED_ARCHIVE",c.getString(2));assertEquals("local_x_archive",c.getString(3));assertEquals(1500L,c.getLong(4));assertTrue(c.isNull(5));assertEquals("UNKNOWN",c.getString(6));assertTrue(c.isNull(7));
            assertTrue(c.moveToNext());assertEquals("REPOST",c.getString(0));assertEquals("LEGACY_UNVERIFIED",c.getString(2));assertEquals("legacy_fixture",c.getString(3));assertTrue(c.isNull(4));assertTrue(c.isNull(5));
        }
        assertEquals(2,count("own_action_events",restored));assertEquals(2,count("posts",source));
    }

    @Test public void malformedMetadataFailsBeforeCreatingAnyLibrary()throws Exception {
        seed();int libraries=libraryCount();byte[] bytes=serialized();
        byte[] corrupt=bytes.clone();corrupt[corrupt.length-6]^=1;rejectBytes(corrupt);
        rejectBytes(java.util.Arrays.copyOf(bytes,bytes.length-4));
        Map<String,List<Map<String,Object>>> rows=mutable(inspect());rows.get("posts").get(0).put("local_path","../../outside");rejectRows(rows);
        rows=mutable(inspect());rows.get("history_events").get(0).put("post_id","999999");rejectRows(rows);
        rows=mutable(inspect());rows.get("posts").get(0).put("opened_count",Long.MAX_VALUE);rejectRows(rows);
        rows=mutable(inspect());rows.get("posts").get(0).put("opened_count",-1L);rejectRows(rows);
        rows=mutable(inspect());rows.get("posts").get(0).put("body",17L);rejectRows(rows);
        rows=mutable(inspect());rows.get("posts").add(new LinkedHashMap<>(rows.get("posts").get(0)));rejectRows(rows);
        rows=mutable(inspect());rows.get("own_action_events").get(0).put("action_key","missing");rejectRows(rows);
        rows=mutable(inspect());rows.put("browser_tokens",new ArrayList<>());rows.get("browser_tokens").add(row("token","not-importable"));rejectRows(rows);
        assertEquals(libraries,libraryCount());assertEquals(2,count("posts",source));assertEquals(defaultLibrary,XsLocalSpaces.defaultLibrary(app));
    }

    @Test public void restoreRollbackPreservesOriginalForEarlyAndLateGuardFailure()throws Exception {
        seed();XsLibraryBackup.ValidatedBackup backup=inspect();int libraries=libraryCount();
        try{XsLibraryBackup.restoreNewLibrary(app,backup,null,()->false);fail("guard required");}catch(IllegalArgumentException expected){}
        AtomicInteger calls=new AtomicInteger();
        try{XsLibraryBackup.restoreNewLibrary(app,backup,null,()->calls.incrementAndGet()==1);fail("late invalidation required");}catch(IllegalArgumentException expected){}
        assertEquals(2,calls.get());assertEquals(libraries,libraryCount());assertEquals(2,count("posts",source));
        assertEquals(2,count("history_events",source));assertEquals(1,count("favorites",source));
        // Repeated failures must release unpublished writer reservations.
        for(int i=0;i<40;i++)try{XsLibraryBackup.restoreNewLibrary(app,backup,null,()->false);fail();}catch(IllegalArgumentException expected){}
        assertEquals(libraries,libraryCount());
        String restored=queued(()->XsLibraryBackup.restoreNewLibrary(app,backup,"After rollback"));assertEquals(2,count("posts",restored));
    }

    @Test public void libraryErasureInvalidatesQueuedRestoreAndCannotResurrectMetadata()throws Exception {
        seed();XsLibraryBackup.ValidatedBackup backup=inspect();int libraries=libraryCount();
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1),skipped=new CountDownLatch(1),erased=new CountDownLatch(1);
        assertTrue(XsLibraryWrites.submit(ticket,()->{started.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}));
        assertTrue(started.await(10,TimeUnit.SECONDS));
        assertTrue(XsLibraryWrites.submit(ticket,()->{XsLibraryBackup.restoreNewLibrary(app,backup,null,()->XsLibraryWrites.isCurrent(ticket));fail("stale restore ran");},skipped::countDown));
        assertTrue(XsLibraryWrites.invalidateAndSubmit(source,()->{try{db.clearAccount(source);}finally{erased.countDown();}}));
        release.countDown();assertTrue(skipped.await(10,TimeUnit.SECONDS));assertTrue(erased.await(10,TimeUnit.SECONDS));
        assertEquals(libraries,libraryCount());assertEquals(0,count("posts",source));assertEquals(0,count("history_events",source));
    }

    @Test public void generatedLongLabelIsUnicodeSafeAndRepeatedRestoreIsAlwaysSeparate()throws Exception {
        seed();Map<String,List<Map<String,Object>>> rows=mutable(inspect());
        String label=repeat("字",108)+"🚀"+repeat("字",10);assertEquals(120,label.length());
        ByteArrayOutputStream out=new ByteArrayOutputStream();XsLibraryBackup.writeCandidate(rows,4,label,out);
        XsLibraryBackup.ValidatedBackup backup=XsLibraryBackup.inspect(new ByteArrayInputStream(out.toByteArray()));
        String first=queued(()->XsLibraryBackup.restoreNewLibrary(app,backup,null));String second=queued(()->XsLibraryBackup.restoreNewLibrary(app,backup,null));
        assertNotEquals(first,second);assertNotEquals(source,first);assertEquals(defaultLibrary,XsLocalSpaces.defaultLibrary(app));
        try(Cursor c=db.getReadableDatabase().rawQuery("SELECT label,origin FROM libraries WHERE storage_scope=?",new String[]{first})) {
            assertTrue(c.moveToFirst());assertEquals(119,c.getString(0).length());assertFalse(Character.isHighSurrogate(c.getString(0).charAt(118)));assertEquals("RESTORED",c.getString(1));
        }
        try{XsLibraryBackup.restoreNewLibrary(app,backup,repeat("x",121));fail("explicit long label accepted");}catch(IllegalArgumentException expected){}
    }

    @Test public void restoredMaximumOpenCountSaturatesAcrossBothOpenEntryPoints()throws Exception {
        seed();Map<String,List<Map<String,Object>>> rows=mutable(inspect());
        for(Map<String,Object> post:rows.get("posts"))if("7101".equals(post.get("post_id")))post.put("opened_count",(long)Integer.MAX_VALUE);
        ByteArrayOutputStream out=new ByteArrayOutputStream();XsLibraryBackup.writeCandidate(rows,4,"maximum count",out);
        XsLibraryBackup.ValidatedBackup backup=XsLibraryBackup.inspect(new ByteArrayInputStream(out.toByteArray()));
        String restored=queued(()->XsLibraryBackup.restoreNewLibrary(app,backup,null));
        assertTrue(db.recordOpened(restored,"https://x.com/demo/status/7101",null,null,null,8000L));
        assertTrue(db.recordObservedOpen(restored,"https://x.com/demo/status/7101",null,null,null,9000L,"after-restore"));
        try(Cursor c=db.getReadableDatabase().rawQuery("SELECT opened_count,typeof(opened_count),last_opened_at_ms FROM posts WHERE account_id=? AND post_id='7101'",new String[]{restored})) {
            assertTrue(c.moveToFirst());assertEquals(Integer.MAX_VALUE,c.getInt(0));assertEquals("integer",c.getString(1));assertEquals(9000L,c.getLong(2));
        }
        ByteArrayOutputStream second=new ByteArrayOutputStream();XsLibraryBackup.exportLibrary(app,restored,second);
        assertEquals(2,XsLibraryBackup.inspect(new ByteArrayInputStream(second.toByteArray())).rows("posts").size());
    }

    private void seed()throws Exception {
        queued(()->{
            assertTrue(db.recordObservedOpen(source,"https://x.com/demo/status/7101","作者","中文离线正文 🚀\nsecond line",1000L,2000L,"open:original"));
            assertTrue(db.recordMeasuredFeed(source,"https://x.com/demo/status/7102","home_following","another","dwellneedle 没有打开",null,3000L,"feed:original",3500L,3000L));
            assertTrue(db.favorite(source,"https://x.com/demo/status/7101","作者",null,null,4000L));db.putFavoriteDetails(source,"7101","备注 🧪\nnot a path: ../../outside","研发,emoji🚀");
            db.putCategory(source,"work","工作 📂");assertTrue(db.assignCategory(source,"7101","work"));
            OwnActionSession session=OwnActionSession.forOfflineFixture(source,"backup-native-fixture");
            OwnActionSession.Admission admitted=session.confirm(session.begin(OwnActionSession.Type.REPOST,"https://x.com/demo/status/7101","fixture:me"),"fixture:me","https://x.com/demo/status/7101",OwnActionSession.Type.REPOST,true,3100L,3200L);
            assertNotNull(admitted);assertTrue(db.recordConfirmedOwnAction(admitted,"作者",null,null));
            OwnActionSession.Admission reversed=session.confirm(session.beginReversal(admitted),"fixture:me","https://x.com/demo/status/7101",OwnActionSession.Type.REPOST,true,3300L,3400L);
            assertNotNull(reversed);assertTrue(db.recordConfirmedOwnAction(reversed,null,null,null));return true;
        });
    }
    private byte[] serialized()throws Exception{return queued(()->{ByteArrayOutputStream out=new ByteArrayOutputStream();XsLibraryBackup.exportLibrary(app,source,out);return out.toByteArray();});}
    private XsLibraryBackup.ValidatedBackup inspect()throws Exception{return XsLibraryBackup.inspect(new ByteArrayInputStream(serialized()));}
    private int count(String table,String library){try(Cursor c=db.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM "+table+" WHERE account_id=?",new String[]{library})){assertTrue(c.moveToFirst());return c.getInt(0);}}
    private int libraryCount(){try(Cursor c=db.libraries()){return c.getCount();}}
    private void rejectBytes(byte[] bytes)throws Exception{try{XsLibraryBackup.inspect(new ByteArrayInputStream(bytes));fail("malformed backup accepted");}catch(IllegalArgumentException expected){}}
    private void rejectRows(Map<String,List<Map<String,Object>>> rows)throws Exception {
        // The generic transport can encode unknown identifiers; inspect must enforce the native schema.
        List<LibraryBackupCodec.Record> records=new ArrayList<>();for(Map.Entry<String,List<Map<String,Object>>> table:rows.entrySet())for(Map<String,Object> row:table.getValue())records.add(new LibraryBackupCodec.Record(table.getKey(),row));
        ByteArrayOutputStream out=new ByteArrayOutputStream();LibraryBackupCodec.write(out,new LibraryBackupCodec.Header(4,1L,"untrusted"),records);rejectBytes(out.toByteArray());
    }
    private static Map<String,List<Map<String,Object>>> mutable(XsLibraryBackup.ValidatedBackup backup){Map<String,List<Map<String,Object>>> result=new LinkedHashMap<>();for(String table:XsLibraryBackup.allowedColumns().keySet()){List<Map<String,Object>> rows=new ArrayList<>();for(Map<String,Object> row:backup.rows(table))rows.add(new LinkedHashMap<>(row));result.put(table,rows);}return result;}
    private static Map<String,Object> row(Object...values){Map<String,Object> row=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)row.put((String)values[i],values[i+1]);return row;}
    private static String repeat(String text,int count){StringBuilder out=new StringBuilder();for(int i=0;i<count;i++)out.append(text);return out.toString();}
    private interface Task<T>{T run()throws Exception;}
    private <T>T queued(Task<T> task)throws Exception {
        CountDownLatch complete=new CountDownLatch(1);AtomicReference<T> value=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();
        assertTrue(XsLibraryWrites.submit(ticket,()->{try{value.set(task.run());}catch(Throwable e){failure.set(e);}finally{complete.countDown();}}));
        assertTrue(complete.await(15,TimeUnit.SECONDS));if(failure.get()!=null)throw new AssertionError(failure.get());return value.get();
    }
}

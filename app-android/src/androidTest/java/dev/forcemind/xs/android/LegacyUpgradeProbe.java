package dev.forcemind.xs.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Process;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Current test APK can target the real old main APK: no Xs class appears in this
 * class's imports, signatures or fields. All product calls use the target loader.
 * External seed/verify/cleanup calls are separate from the ordinary current-API test. */
@RunWith(AndroidJUnit4.class)
public final class LegacyUpgradeProbe {
    private static final String FIXTURE="xs_legacy_upgrade_fixture";
    private static final String SPACE_PREFS="xs_manual_archive", SPACE_KEY="local_space";
    private static final String BROWSER_PREFS="browser.BrowserActivity", NOTICE="browser_notice_accepted";
    private static final String DATABASE="xs-local-history.db";
    private static final String ARCHIVE="dev.forcemind.xs.android.XsArchiveDatabase";
    private static final String SPACES="dev.forcemind.xs.android.XsLocalSpaces";
    private static final String FILTER="dev.forcemind.xs.core.ArchiveSearch$Filter";

    private Context app(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}
    private SharedPreferences fixture(){return app().getSharedPreferences(FIXTURE,Context.MODE_PRIVATE);}

    @Test public void legacyNativeDataRoundTripAndActualApkUpgrade()throws Exception {
        String phase=InstrumentationRegistry.getArguments().getString("xs_legacy_phase","");
        if("seed".equals(phase)){seed(true,UUID.randomUUID().toString());status();return;}
        if("verify".equals(phase)){verify(true);status();return;}
        if("cleanup".equals(phase)){cleanup(true);return;}
        assertEquals("Unknown legacy fixture phase","",phase);
        // A real current-native API/SQLite/FTS round trip also runs in the normal
        // suite. It is not mislabeled as an old APK installation or schema upgrade.
        assertFalse("Never clean an earlier fixture or failed external upgrade",fixture().getBoolean("active",false));
        String owner=UUID.randomUUID().toString();
        Throwable primary=null;
        try {seed(false,owner);verify(false);}
        catch(Exception|AssertionError failure){primary=failure;throw failure;}
        finally {
            if(owner.equals(fixture().getString("owner",null)))
                try{cleanup(false);}catch(Exception|AssertionError cleanup){if(primary!=null)primary.addSuppressed(cleanup);else throw cleanup;}
        }
    }

    private Class<?> target(String name)throws ClassNotFoundException {
        return Class.forName(name,true,app().getClassLoader());
    }
    private Object invoke(Object receiver,String name,Class<?>[] types,Object... args)throws Exception {
        try{return receiver.getClass().getMethod(name,types).invoke(receiver,args);}
        catch(InvocationTargetException failure){Throwable cause=failure.getCause();if(cause instanceof Exception)throw (Exception)cause;if(cause instanceof Error)throw (Error)cause;throw failure;}
    }
    private SQLiteOpenHelper archive()throws Exception {
        return (SQLiteOpenHelper)target(ARCHIVE).getConstructor(Context.class).newInstance(app());
    }
    private String currentSpace()throws Exception {
        return (String)target(SPACES).getMethod("current",Context.class).invoke(null,app());
    }
    private int version()throws Exception {
        PackageInfo info=app().getPackageManager().getPackageInfo(app().getPackageName(),0);
        return info.versionCode;
    }
    private static String id(){return Long.toString(UUID.randomUUID().getMostSignificantBits()&Long.MAX_VALUE);}
    private static String url(String id){return "https://x.com/i/web/status/"+id;}

    private void seed(boolean oldTarget,String owner)throws Exception {
        assertTrue("Instrumentation runs as an ordinary target app",Process.myUid()>=10000);
        SharedPreferences p=fixture();assertFalse("An earlier fixture must be explicitly verified/cleaned",p.contains("active"));
        SharedPreferences spaces=app().getSharedPreferences(SPACE_PREFS,Context.MODE_PRIVATE);
        SharedPreferences browser=app().getSharedPreferences(BROWSER_PREFS,Context.MODE_PRIVATE);
        String previousSpace=spaces.getString(SPACE_KEY,null);
        if(oldTarget){assertEquals(1,version());assertNull("The old target must be freshly installed",previousSpace);assertFalse(app().getDatabasePath(DATABASE).exists());assertFalse("Old main has no downloaded-media database",app().getDatabasePath("xs-media.db").exists());}
        String nonce=UUID.randomUUID().toString(),post=id(),saved=id(),otherPost=id();
        assertNotEquals(post,saved);assertNotEquals(post,otherPost);assertNotEquals(saved,otherPost);
        long base=System.currentTimeMillis();String token="LegacyUpgrade"+nonce.replace("-","");
        SharedPreferences.Editor marker=p.edit().putBoolean("active",true).putString("owner",owner).putBoolean("external",oldTarget)
            .putBoolean("space_had_key",spaces.contains(SPACE_KEY)).putBoolean("notice_had_key",browser.contains(NOTICE))
            .putBoolean("notice_previous",browser.getBoolean(NOTICE,false)).putString("post",post).putString("saved",saved)
            .putString("other_post",otherPost).putString("other_scope","local:"+UUID.randomUUID())
            .putString("category","legacy-fixture-"+nonce).putString("manual_category","legacy-manual-"+nonce).putString("token",token)
            .putString("body",token+" retained multilingual 升级记录").putString("author","legacy_fixture_author")
            .putLong("base",base).putInt("seed_pid",Process.myPid()).putInt("seed_uid",Process.myUid())
            .putString("db_path",app().getDatabasePath(DATABASE).getCanonicalPath())
            .putString("canary","xs-legacy-upgrade-fixture-"+nonce+".png");
        if(previousSpace!=null)marker.putString("space_previous",previousSpace);
        assertTrue(marker.commit());
        String scope=(String)target(SPACES).getMethod("createOnExplicitUserAction",Context.class).invoke(null,app());
        assertNotNull(scope);assertTrue(scope.startsWith("local:"));assertEquals(scope,currentSpace());
        assertTrue(p.edit().putString("scope",scope).commit());
        // The actual old Activity remains on its native introduction, so the host
        // can prove a foreground process without making an X network request.
        assertTrue(browser.edit().putBoolean(NOTICE,false).commit());
        try(SQLiteOpenHelper helper=archive()) {
            SQLiteDatabase db=helper.getWritableDatabase();int schema=db.getVersion();
            assertEquals(oldTarget?2:5,schema);assertTrue(p.edit().putInt("source_schema",schema).commit());
            write(scope,schema,()->{
                assertTrue((Boolean)invoke(helper,"recordOpened",new Class<?>[]{String.class,String.class,String.class,String.class,Long.class,long.class},scope,url(post),p.getString("author",null),p.getString("body",null),base-1000,base+10));
                assertTrue((Boolean)invoke(helper,"recordOpened",new Class<?>[]{String.class,String.class,String.class,String.class,Long.class,long.class},scope,url(post),p.getString("author",null),p.getString("body",null),base-1000,base+20));
                assertTrue((Boolean)invoke(helper,"recordFeedVisible",new Class<?>[]{String.class,String.class,String.class,String.class,String.class,Long.class,long.class},scope,url(post),"home_following",p.getString("author",null),p.getString("body",null),base-1000,base+25));
                assertTrue((Boolean)invoke(helper,"saveManualLink",new Class<?>[]{String.class,String.class,long.class},scope,url(saved),base+30));
                assertFalse((Boolean)invoke(helper,"saveManualLink",new Class<?>[]{String.class,String.class,long.class},scope,url(saved),base+40));
                invoke(helper,"putCategory",new Class<?>[]{String.class,String.class,String.class},scope,p.getString("category",null),"Legacy fixture 分类");
                assertTrue((Boolean)invoke(helper,"assignCategory",new Class<?>[]{String.class,String.class,String.class},scope,post,p.getString("category",null)));
                invoke(helper,"putCategory",new Class<?>[]{String.class,String.class,String.class},scope,p.getString("manual_category",null),"Legacy manual fixture");
                assertTrue((Boolean)invoke(helper,"assignCategory",new Class<?>[]{String.class,String.class,String.class},scope,saved,p.getString("manual_category",null)));
            });
            write(p.getString("other_scope",null),schema,()->assertTrue((Boolean)invoke(helper,"recordOpened",new Class<?>[]{String.class,String.class,String.class,String.class,Long.class,long.class},p.getString("other_scope",null),url(otherPost),"other_fixture","Other scope must remain separate",null,base+50)));
            long rowid=scalar(db,"SELECT rowid FROM posts WHERE account_id=? AND post_id=?",scope,post);
            long saveRowid=scalar(db,"SELECT rowid FROM posts WHERE account_id=? AND post_id=?",scope,saved);
            long otherRowid=scalar(db,"SELECT rowid FROM posts WHERE account_id=? AND post_id=?",p.getString("other_scope",null),otherPost);
            assertTrue(p.edit().putLong("post_rowid",rowid).putLong("saved_rowid",saveRowid).putLong("other_rowid",otherRowid).commit());
            assertSearch(helper,scope,token,null,null,1);assertSearch(helper,scope,"升级记录",p.getString("category",null),null,1);
            assertSearch(helper,scope,null,p.getString("manual_category",null),"LOCAL_SAVE",1);
        }
        File canary=canary();Bitmap bitmap=Bitmap.createBitmap(4,3,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff357ca1);
        try(FileOutputStream out=new FileOutputStream(canary)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));out.getFD().sync();}finally{bitmap.recycle();}
        assertTrue(p.edit().putString("canary_sha256",hash(canary)).putBoolean("seed_complete",true).commit());
    }

    private void verify(boolean upgraded)throws Exception {
        SharedPreferences p=fixture();assertTrue(p.getBoolean("seed_complete",false));
        String scope=p.getString("scope",null),post=p.getString("post",null),saved=p.getString("saved",null);long base=p.getLong("base",0);
        assertEquals("The app UID must survive installation",p.getInt("seed_uid",-1),Process.myUid());assertTrue(Process.myUid()>=10000);
        assertEquals(p.getString("db_path",null),app().getDatabasePath(DATABASE).getCanonicalPath());
        assertEquals(scope,currentSpace());assertEquals(scope,app().getSharedPreferences(SPACE_PREFS,Context.MODE_PRIVATE).getString(SPACE_KEY,null));
        SharedPreferences browser=app().getSharedPreferences(BROWSER_PREFS,Context.MODE_PRIVATE);
        assertTrue("The original preference key was preserved",browser.contains(NOTICE));assertFalse(browser.getBoolean(NOTICE,true));
        if(upgraded){
            Bundle args=InstrumentationRegistry.getArguments();assertEquals(3,version());assertEquals(2,p.getInt("source_schema",-1));
            assertEquals(Integer.parseInt(args.getString("xs_legacy_expected_uid","-1")),Process.myUid());
            assertEquals(args.getString("xs_legacy_expected_path"),app().getDatabasePath(DATABASE).getCanonicalPath());
            assertEquals(args.getString("xs_legacy_expected_scope"),scope);
            assertNotEquals(p.getInt("seed_pid",-1),Process.myPid());
            int stopped=Integer.parseInt(args.getString("xs_legacy_stopped_pid","-1"));assertTrue(stopped>0);assertNotEquals(stopped,Process.myPid());
        }else assertEquals(5,p.getInt("source_schema",-1));
        try(SQLiteOpenHelper helper=archive()) {
            SQLiteDatabase db=helper.getReadableDatabase();assertEquals(5,db.getVersion());
            assertEquals(2,scalar(db,"SELECT COUNT(*) FROM posts WHERE account_id=? AND post_id IN (?,?)",scope,post,saved));
            if(upgraded)assertEquals(2,scalar(db,"SELECT COUNT(*) FROM posts WHERE account_id=?",scope));
            assertEquals(p.getLong("post_rowid",-1),scalar(db,"SELECT rowid FROM posts WHERE account_id=? AND post_id=?",scope,post));
            assertEquals(p.getLong("saved_rowid",-1),scalar(db,"SELECT rowid FROM posts WHERE account_id=? AND post_id=?",scope,saved));
            assertEquals(p.getLong("other_rowid",-1),scalar(db,"SELECT rowid FROM posts WHERE account_id=? AND post_id=?",p.getString("other_scope",null),p.getString("other_post",null)));
            try(Cursor c=db.rawQuery("SELECT body,author_handle,published_at_ms,first_seen_at_ms,last_seen_at_ms,last_opened_at_ms,last_feed_seen_at_ms,opened_count FROM posts WHERE account_id=? AND post_id=?",new String[]{scope,post})) {
                assertTrue(c.moveToFirst());assertEquals(p.getString("body",null),c.getString(0));assertEquals(p.getString("author",null),c.getString(1));
                assertEquals(base-1000,c.getLong(2));assertEquals(base+10,c.getLong(3));assertEquals(base+25,c.getLong(4));assertEquals(base+20,c.getLong(5));assertEquals(base+25,c.getLong(6));assertEquals(2,c.getInt(7));
            }
            assertEquals(0,scalar(db,"SELECT opened_count FROM posts WHERE account_id=? AND post_id=?",scope,saved));
            assertEquals(2,scalar(db,"SELECT COUNT(*) FROM history_events WHERE account_id=? AND post_id=? AND event_type='OPEN_POST'",scope,post));
            assertEquals(base+30,scalar(db,"SELECT action_at_ms FROM user_actions WHERE account_id=? AND post_id=? AND action_type='LOCAL_SAVE' AND source='native_user_save'",scope,saved));
            assertEquals(1,scalar(db,"SELECT COUNT(*) FROM history_events WHERE account_id=? AND post_id=? AND event_type='FEED_VISIBLE' AND duration_ms IS NULL",scope,post));
            assertEquals(1,scalar(db,"SELECT COUNT(*) FROM categories c JOIN post_categories pc ON c.account_id=pc.account_id AND c.category_id=pc.category_id WHERE pc.account_id=? AND pc.post_id=? AND c.category_id=? AND c.label='Legacy fixture 分类'",scope,post,p.getString("category",null)));
            assertEquals(1,scalar(db,"SELECT COUNT(*) FROM posts p JOIN posts_fts f ON p.rowid=f.rowid WHERE p.account_id=? AND p.post_id=? AND f.body=p.body AND f.author_handle=p.author_handle",scope,post));
            assertSearch(helper,scope,p.getString("token",null),null,null,1);assertSearch(helper,scope,"retained multilingual",p.getString("category",null),null,1);
            assertSearch(helper,scope,"升级记录",p.getString("category",null),null,1);assertSearch(helper,scope,null,p.getString("category",null),null,1);
            assertSearch(helper,scope,null,p.getString("manual_category",null),"LOCAL_SAVE",1);assertSearch(helper,p.getString("other_scope",null),p.getString("token",null),null,null,0);
            if(upgraded){
                assertEquals(1,scalar(db,"SELECT COUNT(*) FROM legacy_scope_mapping WHERE legacy_scope=? AND library_id=?",scope,scope));
                assertEquals(1,scalar(db,"SELECT COUNT(*) FROM snapshot_versions WHERE account_id=? AND post_id=? AND snapshot_key='migration:v4' AND source='legacy_migration' AND quality='LEGACY_UNKNOWN' AND captured_at_ms IS NULL AND content_hash IS NULL",scope,post));
                assertEquals(1,scalar(db,"SELECT COUNT(*) FROM posts WHERE account_id=? AND post_id=? AND current_snapshot_key='migration:v4'",scope,post));
                assertEquals(0,scalar(db,"SELECT COUNT(*) FROM favorites WHERE account_id=?",scope));
            }
            try(Cursor check=db.rawQuery("PRAGMA foreign_key_check",null)){assertEquals(0,check.getCount());}
            try(Cursor check=db.rawQuery("PRAGMA integrity_check",null)){assertTrue(check.moveToFirst());assertEquals("ok",check.getString(0));}
        }
        assertEquals(p.getString("canary_sha256",null),hash(canary()));
        assertTrue(p.edit().putBoolean("verified",true).commit());
    }

    private void status()throws Exception {
        SharedPreferences p=fixture();Bundle result=new Bundle();
        try(SQLiteOpenHelper helper=archive()){result.putString("xs_legacy_schema",Integer.toString(helper.getReadableDatabase().getVersion()));}
        result.putString("xs_legacy_uid",Integer.toString(Process.myUid()));result.putString("xs_legacy_pid",Integer.toString(Process.myPid()));
        result.putString("xs_legacy_db_path",app().getDatabasePath(DATABASE).getCanonicalPath());result.putString("xs_legacy_scope",p.getString("scope",null));
        InstrumentationRegistry.getInstrumentation().sendStatus(0,result);
    }
    private void assertSearch(SQLiteOpenHelper helper,String scope,String keyword,String category,String action,int expected)throws Exception {
        Class<?> type=target(FILTER);Object filter=type.getConstructor().newInstance();
        type.getField("keyword").set(filter,keyword);type.getField("categoryId").set(filter,category);type.getField("actionType").set(filter,action);
        try(Cursor c=(Cursor)invoke(helper,"search",new Class<?>[]{String.class,type},scope,filter)){assertEquals(expected,c.getCount());}
    }
    private static long scalar(SQLiteDatabase db,String sql,String... args) {
        try(Cursor c=db.rawQuery(sql,args)){assertTrue(c.moveToFirst());assertFalse(c.isNull(0));return c.getLong(0);}
    }
    private interface Operation{void run()throws Exception;}
    private void write(String scope,int schema,Operation operation)throws Exception {
        if(schema<5){operation.run();return;}
        CountDownLatch done=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();
        Runnable work=()->{try{operation.run();}catch(Throwable problem){failure.set(problem);}finally{done.countDown();}};
        Method submit=target("dev.forcemind.xs.android.XsLibraryWrites").getMethod("submit",String.class,Runnable.class);
        assertTrue((Boolean)submit.invoke(null,scope,work));assertTrue(done.await(15,TimeUnit.SECONDS));
        Throwable problem=failure.get();if(problem instanceof Exception)throw (Exception)problem;if(problem instanceof Error)throw (Error)problem;if(problem!=null)throw new AssertionError(problem);
    }
    private File canary()throws Exception {
        String name=fixture().getString("canary","");assertTrue(name.matches("xs-legacy-upgrade-fixture-[a-f0-9-]{36}\\.png"));
        File file=new File(app().getFilesDir(),name);assertEquals(app().getFilesDir().getCanonicalFile(),file.getCanonicalFile().getParentFile());return file;
    }
    private static String hash(File file)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");try(FileInputStream in=new FileInputStream(file)){byte[] bytes=new byte[4096];int n;while((n=in.read(bytes))!=-1)digest.update(bytes,0,n);}
        StringBuilder out=new StringBuilder();for(byte value:digest.digest())out.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return out.toString();
    }
    private void cleanup(boolean external)throws Exception {
        SharedPreferences p=fixture();if(!p.getBoolean("active",false))return;
        if(external){assertTrue("Only a completely verified upgrade may clean its fixtures",p.getBoolean("external",false)&&p.getBoolean("verified",false));}
        String scope=p.getString("scope",null),other=p.getString("other_scope",null);
        if(scope!=null)try(SQLiteOpenHelper helper=archive()) {
            SQLiteDatabase db=helper.getWritableDatabase();
            cleanScope(db,scope,new String[]{p.getString("post",null),p.getString("saved",null)},new String[]{p.getString("category",null),p.getString("manual_category",null)},!p.getBoolean("space_had_key",false));
            cleanScope(db,other,new String[]{p.getString("other_post",null)},new String[0],true);
        }
        if(p.contains("canary")){File file=canary();assertTrue("Remove only the generated private canary",!file.exists()||file.delete());}
        SharedPreferences spaces=app().getSharedPreferences(SPACE_PREFS,Context.MODE_PRIVATE);
        if(scope!=null){assertEquals("Do not overwrite a changed default",scope,spaces.getString(SPACE_KEY,null));SharedPreferences.Editor edit=spaces.edit();if(p.getBoolean("space_had_key",false))edit.putString(SPACE_KEY,p.getString("space_previous",null));else edit.remove(SPACE_KEY);assertTrue(edit.commit());}
        SharedPreferences.Editor browser=app().getSharedPreferences(BROWSER_PREFS,Context.MODE_PRIVATE).edit();
        if(p.getBoolean("notice_had_key",false))browser.putBoolean(NOTICE,p.getBoolean("notice_previous",false));else browser.remove(NOTICE);assertTrue(browser.commit());
        assertTrue(p.edit().clear().commit());
    }
    private void cleanScope(SQLiteDatabase db,String scope,String[] ids,String[] categories,boolean removeEmptyLibrary)throws Exception {
        if(scope==null)return;
        write(scope,db.getVersion(),()->{
            db.beginTransaction();try{
                for(String id:ids)if(id!=null)db.delete("posts","account_id=? AND post_id=?",new String[]{scope,id});
                for(String category:categories)if(category!=null)db.delete("categories","account_id=? AND category_id=?",new String[]{scope,category});
                if(removeEmptyLibrary&&db.getVersion()>=3){
                    assertEquals(0,scalar(db,"SELECT COUNT(*) FROM posts WHERE account_id=?",scope));assertEquals(0,scalar(db,"SELECT COUNT(*) FROM categories WHERE account_id=?",scope));
                    db.delete("legacy_scope_mapping","legacy_scope=? AND library_id=?",new String[]{scope,scope});db.delete("libraries","library_id=? AND storage_scope=?",new String[]{scope,scope});
                }
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
        });
    }
}

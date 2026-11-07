package dev.forcemind.xs.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native, non-exported startup. No browser storage, networking or public provider operations. */
public final class XsRuntimeInitializer extends ContentProvider {
    private static final ExecutorService RECOVERY=Executors.newSingleThreadExecutor(r->new Thread(r,"xs-startup-recovery"));
    @Override public boolean onCreate() {
        Context context=getContext();if(context==null)return false;
        final Context app=context.getApplicationContext();
        // Install durable revocations before Activities, receivers or job services can take tickets.
        XsDeletedLibraries.initialize(app);
        RECOVERY.execute(()-> {
            boolean complete=true;
            try { XsFullBackup.recoverInterrupted(app); } catch(Exception failure) { complete=false; }
            try { if(app.getDatabasePath("xs-media.db").isFile())XsMediaRepository.recoverScheduled(app); }
            catch(Exception failure) { complete=false; }
            try { XsLibraryDeletion.retryPending(app); } catch(Exception failure) { complete=false; }
            // Only a fixed status bit: never exception messages, paths, URLs, passphrases or keys.
            app.getSharedPreferences("xs_runtime_recovery",Context.MODE_PRIVATE).edit().putBoolean("startup_dispatch_complete",complete).apply();
        });
        return true;
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] selectionArgs,String sortOrder){throw new SecurityException("No public runtime provider operations");}
    @Override public String getType(Uri uri){return null;}
    @Override public Uri insert(Uri uri,ContentValues values){throw new SecurityException("No public runtime provider operations");}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] selectionArgs){throw new SecurityException("No public runtime provider operations");}
    @Override public int delete(Uri uri,String selection,String[] selectionArgs){throw new SecurityException("No public runtime provider operations");}
}

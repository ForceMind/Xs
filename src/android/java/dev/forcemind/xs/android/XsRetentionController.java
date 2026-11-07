package dev.forcemind.xs.android;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/** Explicit/lazy maintenance, serialized with capture and erasure without revoking media tickets. */
public final class XsRetentionController {
    private XsRetentionController() {}
    public interface Callback {void onComplete(boolean success,long deletedEvents);}
    public static void apply(Context context,String library,int days,Callback callback) {
        if(!XsRetentionSettings.valid(days))throw new IllegalArgumentException("Unsupported retention");
        run(context,library,days,callback);
    }
    public static void cleanNow(Context context,String library,Callback callback) {run(context,library,null,callback);}
    private static void run(Context context,String library,Integer requestedDays,Callback callback) {
        Context app=context.getApplicationContext();Handler main=new Handler(Looper.getMainLooper());
        Runnable work=()->{
            boolean success=false;long deleted=0;
            try(XsArchiveDatabase archive=new XsArchiveDatabase(app)) {
                if(XsLibraryWrites.isDeleted(library))throw new IllegalStateException("Library deleted");
                int previous=XsRetentionSettings.getDays(archive,library);
                int days=requestedDays==null?previous:requestedDays;
                boolean changing=requestedDays!=null;
                try {
                    // A process stop between durable preference and prune is repaired by next lazy run.
                    if(changing&&!XsRetentionSettings.commit(archive,library,days))throw new IllegalStateException("Policy save failed");
                    long now=System.currentTimeMillis();
                    if(days!=0&&archive.retentionPreview(library,days,now)>0)deleted=archive.pruneHistory(library,days,now);
                    success=true;
                } catch(RuntimeException failure) {
                    // SharedPreferences changes its memory even when commit fails: restore both views.
                    if(changing)XsRetentionSettings.commit(archive,library,previous);
                }
            } catch(RuntimeException unavailable) { /* A deleted/busy/broken scope fails without widening erase. */ }
            final boolean ok=success;final long count=deleted;
            main.post(()->{if(callback!=null)callback.onComplete(ok,count);});
        };
        Runnable skipped=()->main.post(()->{if(callback!=null)callback.onComplete(false,0);});
        boolean accepted;
        // Retention expires history details, not library ownership or download operations.
        // Queued callbacks keep their tickets and recheck policy after obtaining the SQLite lock.
        try {accepted=XsLibraryWrites.submit(XsLibraryWrites.ticket(library),work,skipped);}
        catch(RuntimeException unavailable){accepted=false;}
        if(!accepted)skipped.run();
    }
}

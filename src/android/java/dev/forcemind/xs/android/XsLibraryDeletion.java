package dev.forcemind.xs.android;

import android.content.Context;
import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit whole-library erasure, with persistent revocation and retryable cleanup. */
public final class XsLibraryDeletion {
    private static final ThreadPoolExecutor IO=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(8));
    private static final AtomicBoolean RETRYING=new AtomicBoolean();
    private XsLibraryDeletion() {}
    /** Startup retries only scopes whose deletion was durably requested by the user. */
    public static void retryPending(Context context){
        if(!RETRYING.compareAndSet(false,true))return;
        Context app=context.getApplicationContext();
        retryNext(app,new ArrayList<>(XsDeletedLibraries.pending(app)),0);
    }
    private static void retryNext(Context context,List<String> pending,int index){
        if(index>=pending.size()){RETRYING.set(false);return;}
        delete(context,pending.get(index),(ok,replacement)->retryNext(context,pending,index+1));
    }
    public interface Callback { void onComplete(boolean success,String replacementDefault); }
    public static void delete(Context context,String library,Callback callback){
        Context app=context.getApplicationContext();Handler main=new Handler(Looper.getMainLooper());
        try{IO.execute(()->{
            try{
                boolean retry=XsDeletedLibraries.isDeleted(app,library);
                if(!retry)try(XsArchiveDatabase archive=new XsArchiveDatabase(app);Cursor rows=archive.libraries()){
                    boolean exists=false;while(rows.moveToNext())if(library.equals(rows.getString(3))){exists=true;break;}
                    if(!exists)throw new IllegalArgumentException("Existing library required");
                }
                XsDeletedLibraries.markDeleted(app,library);
                if(retry)XsDeletedLibraries.initialize(app);
                if(library.equals(XsLocalSpaces.current(app))){XsLibrarySettings.setOpenEnabled(app,false);XsLibrarySettings.setFeedEnabled(app,false);}
                Runnable erase=()->{
                    boolean ok=false;String replacement=null;
                    try{
                        replacement=XsLocalSpaces.rotateDeleted(app,library);
                        XsMediaRepository.get(app).eraseLibrary(library);
                        try(XsArchiveDatabase archive=new XsArchiveDatabase(app)){XsArchiveLibraryOperations.eraseLibrary(archive,library);}
                        XsDeletedLibraries.completeDeletion(app,library);ok=true;
                    }catch(RuntimeException failure){/* Persistent pending scope remains unavailable and retryable. */}
                    final boolean result=ok;final String active=replacement;
                    main.post(()->{if(callback!=null)callback.onComplete(result,active);});
                };
                boolean queued=retry?XsLibraryWrites.submitDeletion(library,erase):XsLibraryWrites.deleteAndSubmit(library,erase);
                if(!queued)main.post(()->{if(callback!=null)callback.onComplete(false,null);});
            }catch(RuntimeException failure){main.post(()->{if(callback!=null)callback.onComplete(false,null);});}
        });}catch(RuntimeException full){main.post(()->{if(callback!=null)callback.onComplete(false,null);});}
    }
}

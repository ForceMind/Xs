package dev.forcemind.xs.android;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.LinkedHashSet;
import java.util.Set;

/** Persistent revocation: a deleted scope is never reused by a later browser/task. */
public final class XsDeletedLibraries {
    private static final String PREFS="xs_deleted_libraries";
    private XsDeletedLibraries() {}
    private static SharedPreferences prefs(Context context){return context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    public static synchronized void initialize(Context context){XsLibraryWrites.installDeletedLibraries(new LinkedHashSet<>(prefs(context).getStringSet("deleted",new LinkedHashSet<>())));}
    public static synchronized boolean isDeleted(Context context,String library){return library!=null&&prefs(context).getStringSet("deleted",new LinkedHashSet<>()).contains(library);}
    public static synchronized Set<String> pending(Context context){return new LinkedHashSet<>(prefs(context).getStringSet("pending",new LinkedHashSet<>()));}
    /** Call on a background worker before the in-memory deletion barrier. */
    public static synchronized void markDeleted(Context context,String library){
        if(library==null||library.isEmpty()||library.length()>256)throw new IllegalArgumentException("Library required");
        SharedPreferences preferences=prefs(context);
        Set<String> beforeDeleted=new LinkedHashSet<>(preferences.getStringSet("deleted",new LinkedHashSet<>()));
        Set<String> beforePending=new LinkedHashSet<>(preferences.getStringSet("pending",new LinkedHashSet<>()));
        Set<String> deleted=new LinkedHashSet<>(beforeDeleted);
        Set<String> pending=new LinkedHashSet<>(beforePending);
        if(!deleted.contains(library)&&deleted.size()>=4096)throw new IllegalStateException("Deletion registry full");
        deleted.add(library);pending.add(library);
        if(!preferences.edit().putStringSet("deleted",deleted).putStringSet("pending",pending).commit()){
            // Android can mutate its cached map before a failing disk commit. Restore the
            // previous cached values too: callers must not observe an unpersisted revocation.
            preferences.edit().putStringSet("deleted",beforeDeleted).putStringSet("pending",beforePending).commit();
            throw new IllegalStateException("Could not persist deletion");
        }
    }
    public static synchronized void completeDeletion(Context context,String library){
        SharedPreferences preferences=prefs(context);Set<String> before=new LinkedHashSet<>(preferences.getStringSet("pending",new LinkedHashSet<>()));
        Set<String> pending=new LinkedHashSet<>(before);pending.remove(library);
        if(!preferences.edit().putStringSet("pending",pending).commit()){
            preferences.edit().putStringSet("pending",before).commit();
            throw new IllegalStateException("Could not save deletion result");
        }
    }
}

package dev.forcemind.xs.android;

import android.content.Context;
import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Native per-library quota only; no content, candidate URL, credential or export metadata. */
final class XsStorageSettings {
    private final SharedPreferences preferences;
    XsStorageSettings(Context context){preferences=context.getSharedPreferences("xs-storage-quotas",Context.MODE_PRIVATE);}
    private static String key(String library) {
        try{byte[] digest=MessageDigest.getInstance("SHA-256").digest(library.getBytes(StandardCharsets.UTF_8));StringBuilder value=new StringBuilder("limit.");for(byte b:digest)value.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return value.toString();}
        catch(java.security.GeneralSecurityException impossible){throw new IllegalStateException("Storage settings unavailable");}
    }
    long quota(String library) {
        try{long value=preferences.getLong(key(library),0);if(value<0)throw new IllegalStateException("Storage settings unavailable");return value;}
        catch(ClassCastException invalid){throw new IllegalStateException("Storage settings unavailable");}
    }
    /** Caller owns the SQLite write transaction and shared library writer. A failed commit is never success. */
    void save(String library,long bytes) {
        String key=key(library);boolean present=preferences.contains(key);long previous=quota(library);
        SharedPreferences.Editor edit=preferences.edit();if(bytes==0)edit.remove(key);else edit.putLong(key,bytes);
        if(!edit.commit()) {
            // commit() can update the in-memory map even when disk persistence fails: restore its previous value.
            SharedPreferences.Editor rollback=preferences.edit();if(present)rollback.putLong(key,previous);else rollback.remove(key);rollback.commit();
            throw new IllegalStateException("Storage settings could not be saved");
        }
    }
}

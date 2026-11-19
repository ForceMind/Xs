package dev.forcemind.xs.android;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.UUID;

/**
 * A private, local archive space that the user must explicitly create.
 * It is NEVER a claim of an authenticated X identity or cookie separation.
 */
public final class XsLocalSpaces {
    private static final String PREFS = "xs_manual_archive";
    private static final String KEY = "local_space";

    private XsLocalSpaces() {}

    public static String current(Context context) {
        if (context == null) return null;
        return context.getApplicationContext().getSharedPreferences(
            PREFS, Context.MODE_PRIVATE).getString(KEY, null);
    }

    /** Native app library identity is independent of every browser/X account. */
    public static String defaultLibrary(Context context) {
        return createOnExplicitUserAction(context);
    }

    public static synchronized String createOnExplicitUserAction(Context context) {
        if (context == null) throw new IllegalArgumentException("Context required");
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(
            PREFS, Context.MODE_PRIVATE);
        String existing = prefs.getString(KEY, null);
        if (existing != null && existing.startsWith("local:") && !XsLibraryWrites.isDeleted(existing)) return existing;
        String created = "local:" + UUID.randomUUID();
        if (!prefs.edit().putString(KEY, created).commit()) {
            throw new IllegalStateException("Cannot persist native local archive space");
        }
        return created;
    }
    /** Rotates only the deleted default. Separate library deletion never selects another default. */
    public static synchronized String rotateDeleted(Context context,String expected) {
        if(context==null)throw new IllegalArgumentException("Context required");
        SharedPreferences prefs=context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        String existing=prefs.getString(KEY,null);
        if(existing==null||!existing.equals(expected))return existing;
        String replacement="local:"+UUID.randomUUID();
        if(!prefs.edit().putString(KEY,replacement).commit())throw new IllegalStateException("Cannot persist replacement library");
        return replacement;
    }
}

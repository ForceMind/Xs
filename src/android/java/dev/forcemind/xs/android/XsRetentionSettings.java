package dev.forcemind.xs.android;

import android.content.Context;
import android.content.SharedPreferences;

/** Library-local explicit policy. Zero keeps event details forever; other entities never expire. */
public final class XsRetentionSettings {
    private static final String PREFS="xs_history_retention";
    private static final long DAY_MS=86_400_000L;
    private XsRetentionSettings() {}
    private static String key(String library) {
        if(library==null||library.trim().isEmpty())throw new IllegalArgumentException("Library required");
        return "days:"+library;
    }
    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);
    }
    public static int getDays(XsArchiveDatabase db,String library) {
        int value=prefs(db.applicationContext()).getInt(key(library),0);
        return valid(value)?value:0;
    }
    public static boolean valid(int days){return days==0||days==7||days==30||days==90||days==365;}
    public static long cutoffMs(int days,long nowMs) {
        if(!valid(days)||nowMs<=0)throw new IllegalArgumentException("Unsupported retention or clock");
        return days==0?0:Math.max(0,nowMs-days*DAY_MS);
    }
    /** Only the controller's shared writer may change policy. commit also updates in-memory reads. */
    static boolean commit(XsArchiveDatabase db,String library,int days) {
        if(!valid(days))throw new IllegalArgumentException("Unsupported retention");
        return prefs(db.applicationContext()).edit().putInt(key(library),days).commit();
    }
}

package dev.forcemind.xs.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.os.Handler;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewDatabase;

/** WebView-owned data only. Never touches the native library, files, settings or outgoing leases. */
final class BrowserPrivacy {
    private static final String PREFERENCES="xs_browser_privacy";
    private static boolean clearing;
    private static long operation;
    private static final Handler handler=new Handler(Looper.getMainLooper());
    private static Runnable timeout;
    private BrowserPrivacy() {}
    private static SharedPreferences preferences(Context context){
        return context.getApplicationContext().getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE);
    }
    static long sessionEpoch(Context context){return preferences(context).getLong("session_epoch",0);}
    static boolean pending(Context context){return preferences(context).getBoolean("session_clear_pending",false);}
    static boolean needsExplicitNavigation(Context context){
        // Existing installations that cleared a session before this flag was introduced also
        // get an empty landing view until one new native navigation is explicitly chosen.
        return preferences(context).getBoolean("needs_explicit_navigation",sessionEpoch(context)>0);
    }
    static boolean confirmExplicitNavigation(Context context){
        mainThread();if(pending(context))return false;
        return !needsExplicitNavigation(context) || preferences(context).edit()
            .putBoolean("needs_explicit_navigation",false).commit();
    }
    static int nextUploadRequest(Context context){
        mainThread();SharedPreferences prefs=preferences(context);int request=prefs.getInt("next_upload_request",4101);
        if(request==Integer.MAX_VALUE || !prefs.edit().putInt("next_upload_request",request+1).commit())
            throw new IllegalStateException("Upload identity unavailable");
        return request;
    }
    static void clearCache(WebView web){mainThread();if(web!=null)web.clearCache(true);}
    /** Persist invalidation before destroying old DOM. Pending survives rotation and process death. */
    static void beginSessionClear(Context context){
        mainThread();SharedPreferences prefs=preferences(context);long epoch=sessionEpoch(context);
        if(epoch==Long.MAX_VALUE || !prefs.edit().putLong("session_epoch",epoch+1)
                .putBoolean("session_clear_pending",true).putBoolean("needs_explicit_navigation",true).commit())throw new IllegalStateException("Session invalidation unavailable");
    }
    /** Called only for a previously confirmed pending clear, after every old browser view is gone. */
    @SuppressWarnings("deprecation") static void resumePendingClear(Context context){
        mainThread();if(clearing || !pending(context))return;
        final Context app=context.getApplicationContext();clearing=true;
        final long ticket=++operation;
        timeout=()->{if(operation==ticket){operation++;clearing=false;}};
        handler.postDelayed(timeout,14000);
        try {
            WebStorage.getInstance().deleteAllData();
            WebViewDatabase database=WebViewDatabase.getInstance(app);
            database.clearHttpAuthUsernamePassword();database.clearFormData();database.clearUsernamePassword();
            CookieManager.getInstance().removeAllCookies(removed->{
                if(ticket!=operation)return;
                try {
                    CookieManager.getInstance().flush();
                    // Queue storage deletion again after cookie completion. No old renderer may
                    // create new values, and the replacement opens no website automatically.
                    WebStorage.getInstance().deleteAllData();
                    WebStorage.getInstance().getOrigins(ignored->{
                        if(ticket!=operation)return;
                        try{preferences(app).edit().putBoolean("session_clear_pending",false).commit();}
                        catch(RuntimeException unavailable){/* Keep the persisted pending barrier. */}
                        finally{finish(ticket);}
                    });
                } catch(RuntimeException failure){finish(ticket);}
            });
        } catch(RuntimeException failure){finish(ticket);throw failure;}
    }
    private static void finish(long ticket){
        if(ticket!=operation)return;
        if(timeout!=null)handler.removeCallbacks(timeout);
        timeout=null;clearing=false;
    }
    private static void mainThread(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("Main thread required");}
}

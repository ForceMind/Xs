package dev.forcemind.xs.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

/** Test APK only. Signature-gated reset/read of its fixed synthetic DocumentsProvider files.
 * This endpoint never creates backup bytes or supplies an Activity result to the application.
 */
public final class GovernanceFixtureAdminProvider extends ContentProvider {
    static final String AUTHORITY="dev.forcemind.xs.harness.test.governance.admin";
    @Override public boolean onCreate(){return getContext()!=null;}
    @Override public Bundle call(String method,String arg,Bundle extras) {
        int caller=Binder.getCallingUid();
        if(caller!=Process.myUid()&&caller!=2000&&getContext().getPackageManager().checkSignatures(caller,Process.myUid())!=PackageManager.SIGNATURE_MATCH)
            throw new SecurityException("Synthetic fixture administration requires the signed target");
        if(!"xs_fixture_reset".equals(method)&&!"xs_fixture_read".equals(method))throw new SecurityException("Only fixed synthetic fixture operations are supported");
        // The provider UID owns the protected document root. The target never obtains a broad
        // MANAGE_DOCUMENTS grant; real backup UI still goes through the system SAF chooser.
        long identity=Binder.clearCallingIdentity();
        try {
            Bundle result=getContext().getContentResolver().call(Uri.parse("content://"+GovernanceDocumentsProvider.AUTHORITY),method,arg,null);
            if(result==null)throw new IllegalStateException("Synthetic document fixture is unavailable");return result;
        }finally{Binder.restoreCallingIdentity(identity);}
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){throw unsupported();}
    @Override public String getType(Uri uri){throw unsupported();}
    @Override public Uri insert(Uri uri,ContentValues values){throw unsupported();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw unsupported();}
    @Override public int delete(Uri uri,String selection,String[] args){throw unsupported();}
    private static SecurityException unsupported(){return new SecurityException("No generic fixture provider access");}
}

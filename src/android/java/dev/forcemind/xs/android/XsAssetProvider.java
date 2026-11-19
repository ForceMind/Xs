package dev.forcemind.xs.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.FileNotFoundException;

/** Non-exported grant-only provider. Only explicit, expiring copies can be read. */
public final class XsAssetProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    private XsMediaRepository.Lease resolve(Uri uri) {
        if(!"content".equals(uri.getScheme())||!getContext().getPackageName().concat(".xs.assets").equals(uri.getAuthority())||uri.getPathSegments().size()!=2||uri.getQuery()!=null||uri.getFragment()!=null)throw new IllegalArgumentException("Invalid file grant");
        return XsMediaRepository.get(getContext()).resolveLease(uri.getPathSegments().get(0),uri.getPathSegments().get(1));
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException {
        if(!"r".equals(mode))throw new FileNotFoundException("Read only");
        try {XsMediaRepository.Lease lease=resolve(uri);return ParcelFileDescriptor.open(XsMediaRepository.get(getContext()).leaseFile(lease),ParcelFileDescriptor.MODE_READ_ONLY);}
        catch(IllegalArgumentException|IllegalStateException failure){throw new FileNotFoundException("File grant unavailable");}
    }
    @Override public String getType(Uri uri){return resolve(uri).mime;}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] selectionArgs,String order) {
        XsMediaRepository.Lease lease=resolve(uri);String[] names=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;
        if(names.length>16)throw new IllegalArgumentException("Projection too large");
        MatrixCursor result=new MatrixCursor(names);Object[] row=new Object[names.length];
        for(int i=0;i<names.length;i++){if(OpenableColumns.DISPLAY_NAME.equals(names[i]))row[i]=lease.filename;else if(OpenableColumns.SIZE.equals(names[i]))row[i]=lease.bytes;}
        result.addRow(row);return result;
    }
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException("Read only");}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException("Read only");}
    @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException("Read only");}
}

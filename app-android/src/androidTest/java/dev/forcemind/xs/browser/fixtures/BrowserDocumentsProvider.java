package dev.forcemind.xs.browser.fixtures;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.util.Base64;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/** Test APK only: fixed read-only PNGs; the third document is never selected by any test. */
public final class BrowserDocumentsProvider extends DocumentsProvider {
    // Deliberately outside the app's private-provider namespace: it is a separate test APK UID.
    public static final String AUTHORITY="dev.forcemind.browserfixture.documents";
    public static final String TITLE="Xs browser fixture images";
    public static final String SELECTED="selected-png",BOUNDARY="boundary-png",NEVER_SELECTED="never-selected-png";
    public static final String PNG_BASE64="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";
    private static final String ROOT="browser-fixture-root";
    private static final String[] ROOT_COLUMNS={DocumentsContract.Root.COLUMN_ROOT_ID,DocumentsContract.Root.COLUMN_DOCUMENT_ID,
        DocumentsContract.Root.COLUMN_TITLE,DocumentsContract.Root.COLUMN_SUMMARY,DocumentsContract.Root.COLUMN_ICON,
        DocumentsContract.Root.COLUMN_FLAGS,DocumentsContract.Root.COLUMN_MIME_TYPES};
    private static final String[] DOC_COLUMNS={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_FLAGS,DocumentsContract.Document.COLUMN_SIZE};
    private File image;
    @Override public boolean onCreate(){
        image=new File(getContext().getFilesDir(),"browser-synthetic-fixture.png");
        try(FileOutputStream output=new FileOutputStream(image)){output.write(Base64.decode(PNG_BASE64,Base64.NO_WRAP));return true;}
        catch(IOException unavailable){return false;}
    }
    @Override public Cursor queryRoots(String[] projection){
        MatrixCursor cursor=new MatrixCursor(projection==null?ROOT_COLUMNS:projection);MatrixCursor.RowBuilder row=cursor.newRow();
        for(String key:cursor.getColumnNames()) {
            Object value=null;
            if(key.equals(DocumentsContract.Root.COLUMN_ROOT_ID)||key.equals(DocumentsContract.Root.COLUMN_DOCUMENT_ID))value=ROOT;
            else if(key.equals(DocumentsContract.Root.COLUMN_TITLE))value=TITLE;
            else if(key.equals(DocumentsContract.Root.COLUMN_SUMMARY))value="Fixed synthetic PNG files only";
            else if(key.equals(DocumentsContract.Root.COLUMN_ICON))value=android.R.drawable.ic_menu_gallery;
            else if(key.equals(DocumentsContract.Root.COLUMN_FLAGS))value=DocumentsContract.Root.FLAG_LOCAL_ONLY;
            else if(key.equals(DocumentsContract.Root.COLUMN_MIME_TYPES))value="image/png";
            row.add(value);
        }
        return cursor;
    }
    @Override public Cursor queryDocument(String id,String[] projection)throws FileNotFoundException {
        if(!ROOT.equals(id)&&!imageId(id))throw missing();
        MatrixCursor cursor=new MatrixCursor(projection==null?DOC_COLUMNS:projection);add(cursor,id);return cursor;
    }
    @Override public Cursor queryChildDocuments(String parent,String[] projection,String sort)throws FileNotFoundException {
        if(!ROOT.equals(parent))throw missing();MatrixCursor cursor=new MatrixCursor(projection==null?DOC_COLUMNS:projection);
        add(cursor,SELECTED);add(cursor,BOUNDARY);add(cursor,NEVER_SELECTED);return cursor;
    }
    @Override public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal)throws FileNotFoundException {
        if(!"r".equals(mode)||!imageId(id))throw missing();
        if(signal!=null)signal.throwIfCanceled();return ParcelFileDescriptor.open(image,ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public boolean isChildDocument(String parent,String child){return ROOT.equals(parent)&&imageId(child);}
    private static boolean imageId(String id){return SELECTED.equals(id)||BOUNDARY.equals(id)||NEVER_SELECTED.equals(id);}
    private void add(MatrixCursor cursor,String id){
        MatrixCursor.RowBuilder row=cursor.newRow();for(String key:cursor.getColumnNames()) {
            Object value=null;
            if(key.equals(DocumentsContract.Document.COLUMN_DOCUMENT_ID))value=id;
            else if(key.equals(DocumentsContract.Document.COLUMN_DISPLAY_NAME))value=ROOT.equals(id)?TITLE:SELECTED.equals(id)?"xs-browser-fixture.png":BOUNDARY.equals(id)?"xs-browser-boundary.png":"xs-browser-never-selected.png";
            else if(key.equals(DocumentsContract.Document.COLUMN_MIME_TYPE))value=ROOT.equals(id)?DocumentsContract.Document.MIME_TYPE_DIR:"image/png";
            else if(key.equals(DocumentsContract.Document.COLUMN_FLAGS))value=0;
            else if(key.equals(DocumentsContract.Document.COLUMN_SIZE))value=ROOT.equals(id)?0:image.length();
            row.add(value);
        }
    }
    private static FileNotFoundException missing(){return new FileNotFoundException("Only fixed read-only PNG fixtures exist");}
}

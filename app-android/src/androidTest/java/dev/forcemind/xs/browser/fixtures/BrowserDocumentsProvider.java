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
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;

/** Test APK only: fixed read-only PNG/MP4 files. NEVER_SELECTED is never selected by a test. */
public final class BrowserDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY="dev.forcemind.browserfixture.documents";
    public static final String TITLE="Xs browser fixture images";
    public static final String SELECTED="selected-png",BOUNDARY="boundary-png",NEVER_SELECTED="never-selected-png";
    // Each new successful flow uses distinct IDs: earlier legitimate SAF grants are not a negative control.
    public static final String MULTI_ONE="multi-one-png",MULTI_TWO="multi-two-png",VIDEO="selected-mp4",SPA_RESULT="spa-result-png";
    public static final String SELECTED_NAME="xs-browser-fixture.png",BOUNDARY_NAME="xs-browser-boundary.png";
    public static final String MULTI_ONE_NAME="xs-browser-multi-one.png",MULTI_TWO_NAME="xs-browser-multi-two.png";
    public static final String VIDEO_NAME="xs-browser-a-video.mp4",SPA_RESULT_NAME="xs-browser-b-spa-return.png";
    public static final String PNG_BASE64="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";
    public static final String SECOND_PNG_BASE64="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP4z8DwHwAFAAH/VscvDQAAAABJRU5ErkJggg==";
    public static final String VIDEO_SHA256="c87f572d025de1d8a116cd588806c702de337adf17262ce4da7e92a8cd03133b";
    public static final int VIDEO_BYTES=5783;
    private static final String ROOT="browser-fixture-root";
    private static final String[] ROOT_COLUMNS={DocumentsContract.Root.COLUMN_ROOT_ID,DocumentsContract.Root.COLUMN_DOCUMENT_ID,
        DocumentsContract.Root.COLUMN_TITLE,DocumentsContract.Root.COLUMN_SUMMARY,DocumentsContract.Root.COLUMN_ICON,
        DocumentsContract.Root.COLUMN_FLAGS,DocumentsContract.Root.COLUMN_MIME_TYPES};
    private static final String[] DOC_COLUMNS={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_FLAGS,DocumentsContract.Document.COLUMN_SIZE};
    private File image,secondImage,video;
    @Override public boolean onCreate(){
        image=new File(getContext().getFilesDir(),"browser-synthetic-fixture.png");
        secondImage=new File(getContext().getFilesDir(),"browser-synthetic-second.png");
        video=new File(getContext().getFilesDir(),"browser-synthetic-fixture.mp4");
        try {
            write(image,Base64.decode(PNG_BASE64,Base64.NO_WRAP));write(secondImage,Base64.decode(SECOND_PNG_BASE64,Base64.NO_WRAP));
            byte[] movie;
            try(InputStream input=getContext().getAssets().open("standard-video.mp4.b64");ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))!=-1){if(out.size()+n>16384)throw new IOException("Synthetic fixture exceeds bound");out.write(buffer,0,n);}
                movie=Base64.decode(out.toByteArray(),Base64.DEFAULT);
            }
            if(movie.length!=VIDEO_BYTES||!VIDEO_SHA256.equals(hex(MessageDigest.getInstance("SHA-256").digest(movie))))throw new IOException("Synthetic MP4 does not match its source evidence");
            write(video,movie);return true;
        }catch(Exception unavailable){return false;}
    }
    private static void write(File file,byte[] bytes)throws IOException{try(FileOutputStream output=new FileOutputStream(file)){output.write(bytes);}}
    @Override public Cursor queryRoots(String[] projection){
        MatrixCursor cursor=new MatrixCursor(projection==null?ROOT_COLUMNS:projection);MatrixCursor.RowBuilder row=cursor.newRow();
        for(String key:cursor.getColumnNames()) {
            Object value=null;
            if(key.equals(DocumentsContract.Root.COLUMN_ROOT_ID)||key.equals(DocumentsContract.Root.COLUMN_DOCUMENT_ID))value=ROOT;
            else if(key.equals(DocumentsContract.Root.COLUMN_TITLE))value=TITLE;
            else if(key.equals(DocumentsContract.Root.COLUMN_SUMMARY))value="Fixed synthetic PNG and MP4 files only";
            else if(key.equals(DocumentsContract.Root.COLUMN_ICON))value=android.R.drawable.ic_menu_gallery;
            else if(key.equals(DocumentsContract.Root.COLUMN_FLAGS))value=DocumentsContract.Root.FLAG_LOCAL_ONLY;
            else if(key.equals(DocumentsContract.Root.COLUMN_MIME_TYPES))value="image/png\nvideo/mp4";
            row.add(value);
        }
        return cursor;
    }
    @Override public Cursor queryDocument(String id,String[] projection)throws FileNotFoundException {
        if(!ROOT.equals(id)&&!mediaId(id))throw missing();
        MatrixCursor cursor=new MatrixCursor(projection==null?DOC_COLUMNS:projection);add(cursor,id);return cursor;
    }
    @Override public Cursor queryChildDocuments(String parent,String[] projection,String sort)throws FileNotFoundException {
        if(!ROOT.equals(parent))throw missing();MatrixCursor cursor=new MatrixCursor(projection==null?DOC_COLUMNS:projection);
        for(String id:new String[]{SELECTED,BOUNDARY,NEVER_SELECTED,MULTI_ONE,MULTI_TWO,VIDEO,SPA_RESULT})add(cursor,id);return cursor;
    }
    @Override public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal)throws FileNotFoundException {
        if(!"r".equals(mode)||!mediaId(id))throw missing();
        if(signal!=null)signal.throwIfCanceled();return ParcelFileDescriptor.open(file(id),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public boolean isChildDocument(String parent,String child){return ROOT.equals(parent)&&mediaId(child);}
    private static boolean mediaId(String id){return SELECTED.equals(id)||BOUNDARY.equals(id)||NEVER_SELECTED.equals(id)||MULTI_ONE.equals(id)||MULTI_TWO.equals(id)||VIDEO.equals(id)||SPA_RESULT.equals(id);}
    private File file(String id){return VIDEO.equals(id)?video:MULTI_TWO.equals(id)?secondImage:image;}
    private static String name(String id){return SELECTED.equals(id)?SELECTED_NAME:BOUNDARY.equals(id)?BOUNDARY_NAME:MULTI_ONE.equals(id)?MULTI_ONE_NAME:MULTI_TWO.equals(id)?MULTI_TWO_NAME:VIDEO.equals(id)?VIDEO_NAME:SPA_RESULT.equals(id)?SPA_RESULT_NAME:"xs-browser-never-selected.png";}
    private void add(MatrixCursor cursor,String id){
        MatrixCursor.RowBuilder row=cursor.newRow();for(String key:cursor.getColumnNames()) {
            Object value=null;
            if(key.equals(DocumentsContract.Document.COLUMN_DOCUMENT_ID))value=id;
            else if(key.equals(DocumentsContract.Document.COLUMN_DISPLAY_NAME))value=ROOT.equals(id)?TITLE:name(id);
            else if(key.equals(DocumentsContract.Document.COLUMN_MIME_TYPE))value=ROOT.equals(id)?DocumentsContract.Document.MIME_TYPE_DIR:VIDEO.equals(id)?"video/mp4":"image/png";
            else if(key.equals(DocumentsContract.Document.COLUMN_FLAGS))value=0;
            else if(key.equals(DocumentsContract.Document.COLUMN_SIZE))value=ROOT.equals(id)?0:file(id).length();
            row.add(value);
        }
    }
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder(bytes.length*2);for(byte value:bytes){out.append(Character.forDigit((value>>>4)&15,16));out.append(Character.forDigit(value&15,16));}return out.toString();}
    private static FileNotFoundException missing(){return new FileNotFoundException("Only fixed read-only synthetic media fixtures exist");}
}

package dev.forcemind.xs.android;

import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.util.Base64;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.UUID;

/** Test APK only: a SAF root holding generated fixture exports, never user files. */
public final class GovernanceDocumentsProvider extends DocumentsProvider {
    static final String AUTHORITY="dev.forcemind.xs.harness.test.governance.documents";
    static final String TITLE="Xs fixture documents";
    private static final String ROOT="governance-root";
    private static final String[] ROOT_COLUMNS={DocumentsContract.Root.COLUMN_ROOT_ID,DocumentsContract.Root.COLUMN_DOCUMENT_ID,
        DocumentsContract.Root.COLUMN_TITLE,DocumentsContract.Root.COLUMN_SUMMARY,DocumentsContract.Root.COLUMN_ICON,DocumentsContract.Root.COLUMN_FLAGS,
        DocumentsContract.Root.COLUMN_MIME_TYPES,DocumentsContract.Root.COLUMN_AVAILABLE_BYTES};
    private static final String[] DOCUMENT_COLUMNS={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_FLAGS,DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED};
    private File directory;
    @Override public boolean onCreate(){directory=new File(getContext().getFilesDir(),"governance-fixture-documents");return directory.isDirectory()||directory.mkdirs();}
    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor out=new MatrixCursor(projection==null?ROOT_COLUMNS:projection);
        add(out,ROOT,TITLE,DocumentsContract.Document.MIME_TYPE_DIR,0,0,true);return out;
    }
    @Override public Cursor queryDocument(String id,String[] projection)throws FileNotFoundException {
        MatrixCursor out=new MatrixCursor(projection==null?DOCUMENT_COLUMNS:projection);
        if(ROOT.equals(id))add(out,ROOT,TITLE,DocumentsContract.Document.MIME_TYPE_DIR,0,0,false);
        else {File file=data(id);add(out,id,name(id),"application/octet-stream",file.length(),file.lastModified(),false);}return out;
    }
    @Override public Cursor queryChildDocuments(String parent,String[] projection,String order)throws FileNotFoundException {
        if(!ROOT.equals(parent))throw missing();MatrixCursor out=new MatrixCursor(projection==null?DOCUMENT_COLUMNS:projection);
        File[] files=directory.listFiles();if(files==null)throw missing();Arrays.sort(files);
        for(File file:files)if(file.getName().matches("[0-9a-f-]{36}\\.data")) {
            String id=file.getName().substring(0,36);add(out,id,name(id),"application/octet-stream",file.length(),file.lastModified(),false);
        }
        out.setNotificationUri(getContext().getContentResolver(),DocumentsContract.buildChildDocumentsUri(AUTHORITY,ROOT));return out;
    }
    @Override public String createDocument(String parent,String mime,String displayName)throws FileNotFoundException {
        if(!ROOT.equals(parent)||!allowedName(displayName))throw missing();
        String id=UUID.randomUUID().toString();
        try {
            File file=new File(directory,id+".data");if(!file.createNewFile())throw missing();
            Files.write(new File(directory,id+".name").toPath(),displayName.getBytes(StandardCharsets.UTF_8));changed();return id;
        }catch(IOException failure){throw missing();}
    }
    @Override public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal)throws FileNotFoundException {
        if(!Arrays.asList("r","w","wt","wa","rw","rwt").contains(mode))throw missing();
        return ParcelFileDescriptor.open(data(id),ParcelFileDescriptor.parseMode(mode));
    }
    @Override public void deleteDocument(String id)throws FileNotFoundException {
        File file=data(id);if(!file.delete())throw missing();new File(directory,id+".name").delete();changed();
    }
    @Override public boolean isChildDocument(String parent,String child){return ROOT.equals(parent)&&child!=null&&child.matches("[0-9a-f-]{36}")&&new File(directory,child+".data").isFile();}
    @Override public Bundle call(String method,String arg,Bundle extras) {
        if(!method.startsWith("xs_fixture_"))return super.call(method,arg,extras);
        int caller=Binder.getCallingUid();
        if(caller!=Process.myUid()&&caller!=2000&&getContext().getPackageManager().checkSignatures(caller,Process.myUid())!=PackageManager.SIGNATURE_MATCH)
            throw new SecurityException("Fixture administration is restricted to the signed test target");
        Bundle out=new Bundle();
        try {
            if("xs_fixture_reset".equals(method)) {
                File[] files=directory.listFiles();if(files==null)throw missing();
                for(File file:files)if(file.getName().matches("[0-9a-f-]{36}\\.(data|name)")&&!file.delete())throw missing();
                changed();out.putBoolean("ok",true);
            }else if("xs_fixture_read".equals(method)&&allowedName(arg)) {
                File[] files=directory.listFiles();if(files==null)throw missing();
                for(File file:files)if(file.getName().matches("[0-9a-f-]{36}\\.data")&&arg.equals(name(file.getName().substring(0,36)))) {
                    if(file.length()>2*1024*1024)throw missing();out.putString("data",Base64.encodeToString(Files.readAllBytes(file.toPath()),Base64.NO_WRAP));out.putLong("bytes",file.length());return out;
                }
                out.putString("data","");out.putLong("bytes",0);
            }else throw missing();
            return out;
        }catch(IOException failure){throw new IllegalStateException("Synthetic fixture document unavailable");}
    }
    private File data(String id)throws FileNotFoundException {
        if(id==null||!id.matches("[0-9a-f-]{36}"))throw missing();File file=new File(directory,id+".data");
        if(!file.isFile()||!new File(directory,id+".name").isFile())throw missing();return file;
    }
    private String name(String id)throws FileNotFoundException {
        data(id);try{String value=new String(Files.readAllBytes(new File(directory,id+".name").toPath()),StandardCharsets.UTF_8);if(!allowedName(value))throw missing();return value;}
        catch(IOException failure){throw missing();}
    }
    private static boolean allowedName(String name){return name!=null&&Arrays.asList("xs-library-metadata.xsmeta","xs-library-encrypted.xsbackup","xs-selected-media.zip","Xs-selected-media.zip").contains(name);}
    private void changed(){getContext().getContentResolver().notifyChange(DocumentsContract.buildChildDocumentsUri(AUTHORITY,ROOT),null);}
    private static void add(MatrixCursor cursor,String id,String name,String mime,long size,long modified,boolean root) {
        MatrixCursor.RowBuilder row=cursor.newRow();for(String column:cursor.getColumnNames()) {
            Object value=null;
            if(column.equals(DocumentsContract.Root.COLUMN_ROOT_ID))value=ROOT;
            else if(column.equals(DocumentsContract.Root.COLUMN_DOCUMENT_ID)||column.equals(DocumentsContract.Document.COLUMN_DOCUMENT_ID))value=id;
            else if(column.equals(DocumentsContract.Root.COLUMN_TITLE)||column.equals(DocumentsContract.Document.COLUMN_DISPLAY_NAME))value=name;
            else if(column.equals(DocumentsContract.Root.COLUMN_SUMMARY))value="Generated synthetic files only";
            else if(column.equals(DocumentsContract.Root.COLUMN_ICON))value=android.R.drawable.ic_menu_save;
            else if(column.equals(DocumentsContract.Root.COLUMN_MIME_TYPES))value="application/octet-stream\napplication/zip";
            else if(column.equals(DocumentsContract.Document.COLUMN_MIME_TYPE))value=mime;
            else if(column.equals(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES))value=2L*1024*1024;
            else if(column.equals(DocumentsContract.Document.COLUMN_SIZE))value=size;
            else if(column.equals(DocumentsContract.Document.COLUMN_LAST_MODIFIED))value=modified;
            else if(column.equals(DocumentsContract.Root.COLUMN_FLAGS)||column.equals(DocumentsContract.Document.COLUMN_FLAGS))
                value=root?DocumentsContract.Root.FLAG_SUPPORTS_CREATE|DocumentsContract.Root.FLAG_LOCAL_ONLY:
                    ROOT.equals(id)?DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE:DocumentsContract.Document.FLAG_SUPPORTS_WRITE|DocumentsContract.Document.FLAG_SUPPORTS_DELETE;
            row.add(value);
        }
    }
    private static FileNotFoundException missing(){return new FileNotFoundException("Synthetic fixture document unavailable");}
}

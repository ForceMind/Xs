package dev.forcemind.xs.android;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.forcemind.xs.core.LibraryMergePlan;
import dev.forcemind.xs.core.ArchiveSchema;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Explicit immutable metadata preview followed by creation of a separate library. */
public final class XsLibraryConsolidation {
    private XsLibraryConsolidation() {}
    public static final class Prepared {
        private final XsLibraryBackup.ValidatedBackup backup;
        private final List<XsLibraryWrites.Ticket> tickets;
        private final AtomicBoolean consumed=new AtomicBoolean();
        private final AtomicReference<String> createdLibrary=new AtomicReference<>();
        private final AtomicBoolean rollbackActive=new AtomicBoolean(),rolledBack=new AtomicBoolean();
        private Prepared(XsLibraryBackup.ValidatedBackup backup,List<XsLibraryWrites.Ticket> tickets) {
            this.backup=backup;this.tickets=Collections.unmodifiableList(new ArrayList<>(tickets));
        }
        public Map<String,Integer> counts(){return backup.counts();}
        public String label(){return backup.label();}
        public boolean undoPending(){String target=createdLibrary.get();return target!=null&&!rolledBack.get()&&XsLibraryWrites.isDeleted(target);}
        private boolean current(){for(XsLibraryWrites.Ticket ticket:tickets)if(!XsLibraryWrites.isCurrent(ticket))return false;return true;}
    }
    /** Background thread only. Snapshots are taken on each source's shared write queue. */
    public static Prepared prepare(Context context,List<String> scopes,String label)throws Exception {
        if(context==null||scopes==null||scopes.size()<2||scopes.size()>4||new LinkedHashSet<>(scopes).size()!=scopes.size()
            ||label==null||label.trim().isEmpty()||label.trim().length()>120)throw new IllegalArgumentException("Select 2 to 4 libraries and a short label");
        List<LibraryMergePlan.Input> inputs=new ArrayList<>();List<XsLibraryWrites.Ticket> tickets=new ArrayList<>();
        long totalBytes=0;int totalRecords=0;
        for(String scope:new ArrayList<>(scopes)) {
            XsLibraryWrites.Ticket ticket=XsLibraryWrites.ticket(scope);tickets.add(ticket);
            AtomicReference<XsLibraryBackup.ValidatedBackup> snapshot=new AtomicReference<>();
            AtomicReference<Exception> failure=new AtomicReference<>();CountDownLatch done=new CountDownLatch(1);
            AtomicBoolean canceled=new AtomicBoolean();AtomicReference<Integer> bytes=new AtomicReference<>(0);
            boolean accepted=XsLibraryWrites.submit(ticket,()-> {
                try {
                    if(canceled.get())return;
                    ByteArrayOutputStream out=new ByteArrayOutputStream();
                    XsLibraryBackup.exportLibrary(context.getApplicationContext(),scope,out);
                    bytes.set(out.size());snapshot.set(XsLibraryBackup.inspect(new ByteArrayInputStream(out.toByteArray())));
                }catch(Exception problem){failure.set(problem);}finally{done.countDown();}
            },done::countDown);
            if(!accepted)throw new IllegalStateException("Source changed; preview again");
            try {if(!done.await(30,TimeUnit.SECONDS)){canceled.set(true);throw new IllegalStateException("Snapshot timed out; retry");}}
            catch(InterruptedException interrupted){canceled.set(true);Thread.currentThread().interrupt();throw interrupted;}
            if(failure.get()!=null)throw failure.get();
            XsLibraryBackup.ValidatedBackup source=snapshot.get();
            if(source==null||!XsLibraryWrites.isCurrent(ticket))throw new IllegalStateException("Source changed; preview again");
            totalBytes+=bytes.get();totalRecords+=source.recordCount();
            if(totalBytes>LibraryMergePlan.MAX_TEXT_BYTES||totalRecords>LibraryMergePlan.MAX_RECORDS)
                throw new IllegalArgumentException("Use a smaller metadata batch");
            Map<String,List<Map<String,Object>>> tables=new LinkedHashMap<>();
            for(String table:LibraryMergePlan.TABLES)tables.put(table,source.rows(table));
            inputs.add(new LibraryMergePlan.Input(scope,source.label(),tables));
        }
        LibraryMergePlan.Result merged=LibraryMergePlan.merge(inputs);
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        XsLibraryBackup.writeCandidate(merged.tables,ArchiveSchema.VERSION,label.trim(),out);
        Prepared preview=new Prepared(XsLibraryBackup.inspect(new ByteArrayInputStream(out.toByteArray())),tickets);
        if(!preview.current())throw new IllegalStateException("Source changed; preview again");return preview;
    }
    /** User must confirm this exact prepared snapshot first. Caller runs outside a source queue. */
    public static String commit(Context context,Prepared prepared)throws Exception {
        if(context==null||prepared==null||!prepared.consumed.compareAndSet(false,true)||!prepared.current())
            throw new IllegalStateException("Preview expired; prepare again");
        // Run the commit itself on the first source queue, so its destructive barrier is ordered.
        AtomicReference<String> result=new AtomicReference<>();AtomicReference<Exception> failure=new AtomicReference<>();
        CountDownLatch done=new CountDownLatch(1);AtomicBoolean canceled=new AtomicBoolean();
        boolean accepted=XsLibraryWrites.submit(prepared.tickets.get(0),()-> {
            try {
                String created=XsLibraryBackup.restoreNewLibrary(context.getApplicationContext(),prepared.backup,prepared.label(),
                    ()->!canceled.get()&&prepared.current());
                prepared.createdLibrary.set(created);result.set(created);
            }catch(Exception problem){failure.set(problem);}finally{done.countDown();}
        },done::countDown);
        if(!accepted)throw new IllegalStateException("Source changed; preview again");
        // Once confirmation was accepted, wait for a concrete commit/rollback outcome. Interrupts
        // cancel the guard but must not report cancellation while a transaction can still commit.
        boolean interrupted=false;
        for(;;)try{done.await();break;}catch(InterruptedException stop){interrupted=true;canceled.set(true);}
        if(interrupted)Thread.currentThread().interrupt();
        if(failure.get()!=null)throw failure.get();
        if(result.get()==null)throw new IllegalStateException("Source changed; no library created");return result.get();
    }
    /** Undo this preview's new result only while its metadata is unchanged and it has no media.
     * Background thread only; sources and default preferences are never edited.
     */
    public static void rollback(Context context,Prepared prepared)throws Exception {
        if(context==null||prepared==null||prepared.rolledBack.get()||!prepared.rollbackActive.compareAndSet(false,true))
            throw new IllegalStateException("No unchanged result is available to undo");
        try {
            Context app=context.getApplicationContext();String target=prepared.createdLibrary.get();
            if(target==null||!target.startsWith("local:")||target.equals(XsLocalSpaces.current(app)))throw cannotUndo();
            AtomicReference<Exception> failure=new AtomicReference<>();AtomicBoolean canceled=new AtomicBoolean();
            CountDownLatch done=new CountDownLatch(1);
            XsLibraryWrites.Ticket targetTicket=XsLibraryWrites.ticket(target);
            boolean accepted=XsLibraryWrites.submit(targetTicket,()-> {
                try {
                    if(canceled.get())throw cannotUndo();checkUnchanged(app,prepared,target);
                    try(XsArchiveDatabase archive=new XsArchiveDatabase(app)) {
                        SQLiteDatabase db=archive.getWritableDatabase();db.beginTransaction();
                        try {
                            try(Cursor row=db.rawQuery("SELECT library_id,label,origin FROM libraries WHERE storage_scope=?",new String[]{target})) {
                                if(!row.moveToFirst()||!target.equals(row.getString(0))||!prepared.label().equals(row.getString(1))||!"RESTORED".equals(row.getString(2)))throw cannotUndo();
                            }
                            if(canceled.get()||target.equals(XsLocalSpaces.current(app))||
                                !XsLibraryWrites.isCurrent(targetTicket)||!matchesRows(db,prepared.backup,target)||hasMedia(app,target))throw cannotUndo();
                            // All refusal checks precede durable revocation, under the actual SQLite
                            // writer lock and current target queue. Once persisted, this is an accepted
                            // deletion commitment: any IO failure is a pending purge, never a deferred
                            // rejection which could later delete newly changed data.
                            XsDeletedLibraries.markDeleted(app,target);
                            if(!XsLibraryWrites.deleteAndSubmit(target,()->{}))throw new IllegalStateException("Deletion queued for startup retry");
                            archive.deleteLibraryScope(target);
                            db.setTransactionSuccessful();
                        }finally{db.endTransaction();}
                    }
                    prepared.rolledBack.set(true);
                    try{XsDeletedLibraries.completeDeletion(app,target);}catch(RuntimeException housekeeping){/* idempotent durable retry */}
                }catch(Exception problem){failure.set(problem);}finally{done.countDown();}
            },()->{failure.set(cannotUndo());done.countDown();});
            if(!accepted)throw cannotUndo();
            boolean interrupted=false;
            for(;;)try{done.await();break;}catch(InterruptedException stop){interrupted=true;canceled.set(true);}
            if(interrupted)Thread.currentThread().interrupt();
            if(failure.get()!=null)throw failure.get();
            if(!prepared.rolledBack.get())throw cannotUndo();
        }finally{prepared.rollbackActive.set(false);}
    }
    private static void checkUnchanged(Context app,Prepared prepared,String target)throws Exception {
        if(target.equals(XsLocalSpaces.current(app)))throw cannotUndo();
        ByteArrayOutputStream out=new ByteArrayOutputStream();XsLibraryBackup.exportLibrary(app,target,out);
        XsLibraryBackup.ValidatedBackup current=XsLibraryBackup.inspect(new ByteArrayInputStream(out.toByteArray()));
        if(!sameMetadata(prepared.backup,current)||hasMedia(app,target))throw cannotUndo();
    }
    private static boolean sameMetadata(XsLibraryBackup.ValidatedBackup expected,XsLibraryBackup.ValidatedBackup current) {
        if(expected.schemaVersion()!=current.schemaVersion()||!expected.label().equals(current.label()))return false;
        // Native SQL row order is unspecified. Preserve duplicate legacy events as a multiset.
        for(String table:LibraryMergePlan.TABLES)if(!multiplicity(expected.rows(table)).equals(multiplicity(current.rows(table))))return false;
        return true;
    }
    private static Map<Map<String,Object>,Integer> multiplicity(List<Map<String,Object>> rows) {
        Map<Map<String,Object>,Integer> counts=new HashMap<>();for(Map<String,Object> row:rows)counts.put(row,counts.getOrDefault(row,0)+1);return counts;
    }
    /** Native allowlisted row comparison inside the erase transaction; no export of deleted data. */
    private static boolean matchesRows(SQLiteDatabase db,XsLibraryBackup.ValidatedBackup expected,String target) {
        for(Map.Entry<String,List<String>> table:XsLibraryBackup.allowedColumns().entrySet()) {
            Map<Map<String,Object>,Integer> counts=multiplicity(expected.rows(table.getKey()));
            int seen=0;
            try(Cursor cursor=db.rawQuery("SELECT "+String.join(",",table.getValue())+" FROM "+table.getKey()+" WHERE account_id=?",new String[]{target})) {
                while(cursor.moveToNext()) {
                    if(++seen>expected.rows(table.getKey()).size())return false;
                    Map<String,Object> row=new LinkedHashMap<>();
                    for(int i=0;i<table.getValue().size();i++) {
                        Object value;
                        if(cursor.isNull(i))value=null;
                        else if(cursor.getType(i)==Cursor.FIELD_TYPE_INTEGER)value=cursor.getLong(i);
                        else if(cursor.getType(i)==Cursor.FIELD_TYPE_STRING)value=cursor.getString(i);
                        else return false;
                        row.put(table.getValue().get(i),value);
                    }
                    Integer count=counts.get(row);if(count==null||count==0)return false;
                    if(count==1)counts.remove(row);else counts.put(row,count-1);
                }
            }
            if(!counts.isEmpty())return false;
        }
        return true;
    }
    private static boolean hasMedia(Context context,String target) {
        // Fixed app-private database only. Do not initialize the download repository or touch files.
        File database=context.getDatabasePath("xs-media.db");if(!database.exists())return false;
        try(SQLiteDatabase media=SQLiteDatabase.openDatabase(database.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY);
            Cursor rows=media.rawQuery("SELECT 1 FROM assets WHERE library_id=? LIMIT 1",new String[]{target})) {return rows.moveToFirst();}
    }
    private static IllegalStateException cannotUndo(){return new IllegalStateException("Result changed, contains media, or cannot be safely undone; existing libraries were preserved");}
}

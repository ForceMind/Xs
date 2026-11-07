package dev.forcemind.xs.android;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.forcemind.xs.core.ArchiveSchema;
import dev.forcemind.xs.core.LibraryBackupCodec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Explicit metadata portability. No browser storage, file paths, SQL, credentials or media files. */
public final class XsLibraryBackup {
    private static final LinkedHashMap<String,String[]> COLUMNS=new LinkedHashMap<>();
    static {
        COLUMNS.put("posts",csv("post_id,author_handle,body,published_at_ms,first_seen_at_ms,last_seen_at_ms,last_opened_at_ms,last_feed_seen_at_ms,opened_count,snapshot_state"));
        COLUMNS.put("categories",csv("category_id,label"));
        COLUMNS.put("history_events",csv("post_id,event_type,event_at_ms,source,event_key,duration_ms,threshold_ms,duration_quality"));
        COLUMNS.put("user_actions",csv("post_id,action_type,action_at_ms,source"));
        COLUMNS.put("favorites",csv("post_id,favorited_at_ms,note"));
        COLUMNS.put("post_categories",csv("post_id,category_id"));
        COLUMNS.put("post_tags",csv("post_id,tag"));
        COLUMNS.put("own_actions",csv("action_key,post_id,related_post_id,action_type,actor_id,actor_provenance,source,action_at_ms,observed_at_ms,state,state_at_ms"));
        COLUMNS.put("own_action_events",csv("action_key,event_key,event_type,event_at_ms,observed_at_ms,source"));
    }
    private XsLibraryBackup() {}
    public static final class ValidatedBackup {
        private final LibraryBackupCodec.Header header;
        private final int sourceSchemaVersion;
        private final List<LibraryBackupCodec.Record> records;
        private final Map<String,List<Map<String,Object>>> tables;
        private ValidatedBackup(LibraryBackupCodec.Header header,List<LibraryBackupCodec.Record> records,
                                Map<String,List<Map<String,Object>>> tables) {
            this(header,records,tables,header.schemaVersion);
        }
        private ValidatedBackup(LibraryBackupCodec.Header header,List<LibraryBackupCodec.Record> records,
                                Map<String,List<Map<String,Object>>> tables,int sourceSchemaVersion) {
            this.sourceSchemaVersion=sourceSchemaVersion;this.header=header;this.records=Collections.unmodifiableList(new ArrayList<>(records));
            LinkedHashMap<String,List<Map<String,Object>>> immutable=new LinkedHashMap<>();
            for(Map.Entry<String,List<Map<String,Object>>> table:tables.entrySet())
                immutable.put(table.getKey(),Collections.unmodifiableList(new ArrayList<>(table.getValue())));
            this.tables=Collections.unmodifiableMap(immutable);
        }
        public int schemaVersion(){return header.schemaVersion;}
        public int sourceSchemaVersion(){return sourceSchemaVersion;}
        public String label(){return header.label;}
        public long createdAtMs(){return header.createdAtMs;}
        public int recordCount(){return records.size();}
        public List<Map<String,Object>> rows(String table) {
            if(!COLUMNS.containsKey(table))throw invalid();
            List<Map<String,Object>> rows=tables.get(table);return rows==null?Collections.emptyList():rows;
        }
        public Map<String,Integer> counts(){LinkedHashMap<String,Integer> counts=new LinkedHashMap<>();for(String table:COLUMNS.keySet())counts.put(table,rows(table).size());return Collections.unmodifiableMap(counts);}
    }
    /** Immutable native allowlist for explicit consolidation; never executable SQL from a backup. */
    public static Map<String,List<String>> allowedColumns() {
        LinkedHashMap<String,List<String>> columns=new LinkedHashMap<>();
        for(Map.Entry<String,String[]> entry:COLUMNS.entrySet())columns.put(entry.getKey(),Collections.unmodifiableList(Arrays.asList(entry.getValue().clone())));
        return Collections.unmodifiableMap(columns);
    }
    public static ValidatedBackup inspect(InputStream in)throws IOException {
        LibraryBackupCodec.Data data=LibraryBackupCodec.read(in);
        ValidatedBackup backup=validate(data.header(),data.records());
        return backup.schemaVersion()==3&&ArchiveSchema.VERSION>=4?upgradeLegacy(backup):backup;
    }
    /** Caller uses the selected library's shared write ticket, then writes the native snapshot. */
    public static void exportLibrary(Context context,String library,OutputStream out)throws IOException {
        if(context==null||library==null||library.isEmpty()||out==null)throw invalid();
        List<LibraryBackupCodec.Record> records=new ArrayList<>();String label="Xs library";
        try(XsArchiveDatabase helper=new XsArchiveDatabase(context)) {
            SQLiteDatabase db=helper.getReadableDatabase();long estimate=0;db.beginTransactionNonExclusive();
            try {
                try(Cursor found=db.rawQuery("SELECT label FROM libraries WHERE storage_scope=?",new String[]{library})) {
                    if(!found.moveToFirst())throw invalid();label=found.getString(0);
                }
                for(Map.Entry<String,String[]> table:COLUMNS.entrySet()) {
                    if(ArchiveSchema.VERSION<4&&table.getKey().startsWith("own_"))continue;
                    String selection=join(table.getValue());
                    try(Cursor cursor=db.rawQuery("SELECT "+selection+" FROM "+table.getKey()+" WHERE account_id=?",new String[]{library})) {
                        while(cursor.moveToNext()) {
                            LinkedHashMap<String,Object> values=new LinkedHashMap<>();
                            for(int i=0;i<table.getValue().length;i++) {
                                Object value;
                                switch(cursor.getType(i)) {
                                    case Cursor.FIELD_TYPE_NULL:value=null;break;
                                    case Cursor.FIELD_TYPE_INTEGER:value=cursor.getLong(i);break;
                                    case Cursor.FIELD_TYPE_STRING:value=cursor.getString(i);break;
                                    default:throw invalid();
                                }
                                estimate+=table.getValue()[i].length()+32;
                                if(value instanceof String)estimate+=((String)value).getBytes(StandardCharsets.UTF_8).length;
                                if(estimate>LibraryBackupCodec.MAX_BYTES||records.size()>=LibraryBackupCodec.MAX_RECORDS)throw invalid();
                                values.put(table.getValue()[i],value);
                            }
                            records.add(new LibraryBackupCodec.Record(table.getKey(),values));
                        }
                    }
                }
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
        }
        if(label==null)label="Xs library";label=shortLabel(label);
        LibraryBackupCodec.Header header=new LibraryBackupCodec.Header(ArchiveSchema.VERSION,System.currentTimeMillis(),label);
        ValidatedBackup backup=validate(header,records);
        LibraryBackupCodec.write(out,header,backup.records);
    }
    /** Consolidation supplies only typed rows; full semantics are rechecked before encoding. */
    public static void writeCandidate(Map<String,List<Map<String,Object>>> tables,int schema,String label,OutputStream out)throws IOException {
        if(tables==null||tables.size()>COLUMNS.size())throw invalid();
        for(String table:tables.keySet())if(!COLUMNS.containsKey(table))throw invalid();
        List<LibraryBackupCodec.Record> records=new ArrayList<>();
        for(String table:COLUMNS.keySet()) {
            List<Map<String,Object>> rows=tables.get(table);if(rows==null)continue;
            for(Map<String,Object> row:rows) {
                if(records.size()>=LibraryBackupCodec.MAX_RECORDS)throw invalid();
                records.add(new LibraryBackupCodec.Record(table,row));
            }
        }
        LibraryBackupCodec.Header header=new LibraryBackupCodec.Header(schema,System.currentTimeMillis(),label);
        ValidatedBackup verified=validate(header,records);LibraryBackupCodec.write(out,header,verified.records);
    }
    /** Always creates another library. It never replaces the default preference or an existing scope. */
    public static String restoreNewLibrary(Context context,ValidatedBackup backup,String requestedLabel) {
        return restoreNewLibrary(context,backup,requestedLabel,()->true);
    }
    static String restoreNewLibrary(Context context,ValidatedBackup backup,String requestedLabel,
                                    java.util.function.BooleanSupplier guard) {
        if(context==null||backup==null)throw invalid();
        // The private constructor + frozen rows prevent post-preview substitution.
        validate(backup.header,backup.records);
        String library="local:"+UUID.randomUUID();
        String label=requestedLabel==null?"Restored · "+backup.label():requestedLabel.trim();
        if(requestedLabel==null)label=shortLabel(label);
        if(label.isEmpty()||label.length()>120)throw invalid();
        // Reserve editing capacity before publishing any rows. Failed restores release it.
        XsLibraryWrites.Ticket reservation=XsLibraryWrites.ticket(library);boolean published=false;
        try(XsArchiveDatabase helper=new XsArchiveDatabase(context)) {
            SQLiteDatabase db=helper.getWritableDatabase();db.beginTransaction();
            try {
                if(guard==null||!guard.getAsBoolean())throw invalid();
                ContentValues owner=new ContentValues();owner.put("library_id",library);owner.put("storage_scope",library);
                owner.put("label",label);owner.put("origin","RESTORED");owner.put("created_at_ms",System.currentTimeMillis());
                db.insertOrThrow("libraries",null,owner);
                for(String table:COLUMNS.keySet())for(Map<String,Object> row:backup.rows(table)) {
                    ContentValues values=new ContentValues();values.put("account_id",library);
                    for(Map.Entry<String,Object> field:row.entrySet()) {
                        if(field.getValue()==null)values.putNull(field.getKey());
                        else if(field.getValue() instanceof Long)values.put(field.getKey(),(Long)field.getValue());
                        else values.put(field.getKey(),(String)field.getValue());
                    }
                    db.insertOrThrow(table,null,values);
                }
                // FTS triggers apply during inserts. This checks every FK including v4 action links.
                try(Cursor check=db.rawQuery("PRAGMA foreign_key_check",null)){if(check.moveToFirst())throw invalid();}
                if(!guard.getAsBoolean()||!XsLibraryWrites.isCurrent(reservation))throw invalid();
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
            published=true;return library;
        }finally{if(!published)XsLibraryWrites.releaseUnused(reservation);}
    }
    private static ValidatedBackup upgradeLegacy(ValidatedBackup old) {
        List<LibraryBackupCodec.Record> records=new ArrayList<>(old.records);int next=0;
        for(Map<String,Object> row:old.rows("user_actions")) {
            if(!Arrays.asList("ORIGINAL","REPLY","QUOTE","REPOST").contains(row.get("action_type")))continue;
            String key="backup-legacy:"+(++next);boolean archived="local_x_archive".equals(row.get("source"));
            LinkedHashMap<String,Object> action=new LinkedHashMap<>();
            action.put("action_key",key);action.put("post_id",row.get("post_id"));action.put("related_post_id",null);
            action.put("action_type",row.get("action_type"));action.put("actor_id",null);
            action.put("actor_provenance",archived?"USER_CONFIRMED_ARCHIVE":"LEGACY_UNVERIFIED");
            action.put("source",row.get("source"));action.put("action_at_ms",row.get("action_at_ms"));
            action.put("observed_at_ms",null);action.put("state","UNKNOWN");action.put("state_at_ms",null);
            records.add(new LibraryBackupCodec.Record("own_actions",action));
            LinkedHashMap<String,Object> event=new LinkedHashMap<>();event.put("action_key",key);event.put("event_key",key);
            event.put("event_type",archived?"ARCHIVE_IMPORTED":"LEGACY_RECORDED");event.put("event_at_ms",row.get("action_at_ms"));
            event.put("observed_at_ms",null);event.put("source",row.get("source"));records.add(new LibraryBackupCodec.Record("own_action_events",event));
        }
        ValidatedBackup upgraded=validate(new LibraryBackupCodec.Header(4,old.createdAtMs(),old.label()),records);
        return new ValidatedBackup(upgraded.header,upgraded.records,upgraded.tables,3);
    }
    private static ValidatedBackup validate(LibraryBackupCodec.Header header,List<LibraryBackupCodec.Record> records) {
        if(header.schemaVersion>ArchiveSchema.VERSION||records.size()>LibraryBackupCodec.MAX_RECORDS)throw invalid();
        LinkedHashMap<String,List<Map<String,Object>>> tables=new LinkedHashMap<>();
        Map<String,Set<String>> keys=new HashMap<>();for(String name:COLUMNS.keySet()){tables.put(name,new ArrayList<>());keys.put(name,new HashSet<>());}
        long estimate=0;int sequence=0;
        for(LibraryBackupCodec.Record record:records) {
            String[] columns=COLUMNS.get(record.table);
            if(columns==null||(header.schemaVersion==3&&record.table.startsWith("own_"))||record.values.size()!=columns.length)throw invalid();
            for(String column:columns)if(!record.values.containsKey(column))throw invalid();
            Map<String,Object> row=record.values;validateRow(record.table,row);
            String key=primaryKey(record.table,row,sequence++);
            if(!keys.get(record.table).add(key))throw invalid();
            for(Map.Entry<String,Object> field:row.entrySet()){estimate+=field.getKey().length()+32;if(field.getValue() instanceof String)estimate+=((String)field.getValue()).getBytes(StandardCharsets.UTF_8).length;}
            if(estimate>LibraryBackupCodec.MAX_BYTES)throw invalid();
            tables.get(record.table).add(row);
        }
        Set<String> posts=keys.get("posts"),categories=keys.get("categories"),favorites=keys.get("favorites"),actions=keys.get("own_actions");
        for(Map.Entry<String,List<Map<String,Object>>> table:tables.entrySet())for(Map<String,Object> row:table.getValue()) {
            if(row.containsKey("post_id")&&!table.getKey().equals("posts")&&!posts.contains((String)row.get("post_id")))throw invalid();
            if(table.getKey().equals("post_categories")&&!categories.contains((String)row.get("category_id")))throw invalid();
            if(table.getKey().equals("post_tags")&&!favorites.contains((String)row.get("post_id")))throw invalid();
            if(table.getKey().equals("own_action_events")&&!actions.contains((String)row.get("action_key")))throw invalid();
        }
        return new ValidatedBackup(header,records,tables);
    }
    private static void validateRow(String table,Map<String,Object> row) {
        for(Map.Entry<String,Object> field:row.entrySet()) {
            String column=field.getKey();Object value=field.getValue();
            if(column.endsWith("_ms")) {if(value!=null&&(!(value instanceof Long)||(Long)value<0))throw invalid();}
            else if(column.equals("opened_count")){if(!(value instanceof Long)||(Long)value<0||(Long)value>Integer.MAX_VALUE)throw invalid();}
            else if(value!=null&&!(value instanceof String))throw invalid();
        }
        if(row.containsKey("post_id"))postId(row.get("post_id"),false);
        if(table.equals("posts")) {
            text(row,"author_handle",100,true,true);text(row,"body",16000,true,true);
            positive(row,"first_seen_at_ms",false);positive(row,"last_seen_at_ms",false);
            positive(row,"published_at_ms",true);positive(row,"last_opened_at_ms",true);positive(row,"last_feed_seen_at_ms",true);
            oneOf(row,"snapshot_state","LINK_ONLY","VISIBLE_TEXT");
        } else if(table.equals("categories")){text(row,"category_id",256,false);text(row,"label",120,false);}
        else if(table.equals("history_events")) {
            oneOf(row,"event_type","OPEN_POST","FEED_VISIBLE");positive(row,"event_at_ms",false);text(row,"source",128,false);text(row,"event_key",256,true);
            if("OPEN_POST".equals(row.get("event_type"))) {
                if(row.get("duration_ms")!=null||row.get("threshold_ms")!=null||row.get("duration_quality")!=null)throw invalid();
            } else {
                Object duration=row.get("duration_ms"),threshold=row.get("threshold_ms"),quality=row.get("duration_quality");
                if(duration==null){if(threshold!=null||quality!=null)throw invalid();}
                else if(!(threshold instanceof Long)||!Arrays.asList(1200L,3000L,5000L).contains(threshold)||
                        (Long)duration<(Long)threshold||(Long)duration>86_400_000L||
                        !("SAMPLED_LOWER_BOUND".equals(quality)||"CAPPED_LOWER_BOUND".equals(quality)))throw invalid();
            }
        } else if(table.equals("user_actions")) {
            oneOf(row,"action_type","ORIGINAL","REPLY","QUOTE","REPOST","BOOKMARK","LOCAL_SAVE");positive(row,"action_at_ms",true);text(row,"source",128,false);
        } else if(table.equals("favorites")){positive(row,"favorited_at_ms",false);text(row,"note",8000,false,true);}
        else if(table.equals("post_categories"))text(row,"category_id",256,false);
        else if(table.equals("post_tags"))text(row,"tag",64,false);
        else if(table.equals("own_actions")) {
            text(row,"action_key",256,false);postId(row.get("related_post_id"),true);text(row,"actor_id",128,true);
            oneOf(row,"action_type","ORIGINAL","REPLY","QUOTE","REPOST");oneOf(row,"actor_provenance","NATIVE_FIXTURE","USER_CONFIRMED_ARCHIVE","LEGACY_UNVERIFIED");
            text(row,"source",128,false);positive(row,"action_at_ms",true);positive(row,"observed_at_ms",true);positive(row,"state_at_ms",true);oneOf(row,"state","ACTIVE","REVERSED","UNKNOWN");
        } else if(table.equals("own_action_events")) {
            text(row,"action_key",256,false);text(row,"event_key",256,false);oneOf(row,"event_type","CONFIRMED","REVERSED","ARCHIVE_IMPORTED","LEGACY_RECORDED");
            text(row,"source",128,false);positive(row,"event_at_ms",true);positive(row,"observed_at_ms",true);
        }
    }
    private static String primaryKey(String table,Map<String,Object> row,int sequence) {
        if(table.equals("posts")||table.equals("favorites"))return(String)row.get("post_id");
        if(table.equals("categories"))return(String)row.get("category_id");
        if(table.equals("post_categories"))return tuple(row.get("post_id"),row.get("category_id"));
        if(table.equals("post_tags"))return tuple(row.get("post_id"),row.get("tag"));
        if(table.equals("own_actions"))return(String)row.get("action_key");
        if(table.equals("own_action_events"))return(String)row.get("event_key");
        if(table.equals("user_actions"))return tuple(row.get("post_id"),row.get("action_type"),row.get("action_at_ms"));
        return row.get("event_key")==null?"legacy:"+sequence:tuple(row.get("event_type"),row.get("event_key"));
    }
    private static String tuple(Object...parts){StringBuilder key=new StringBuilder();for(Object part:parts){String value=part==null?"":part.toString();key.append(value.length()).append(':').append(value);}return key.toString();}
    private static void postId(Object value,boolean nullable){if(value==null&&nullable)return;if(!(value instanceof String)||!((String)value).matches("[0-9]{1,20}"))throw invalid();}
    private static void positive(Map<String,Object> row,String column,boolean nullable){Object value=row.get(column);if(value==null&&nullable)return;if(!(value instanceof Long)||(Long)value<=0)throw invalid();}
    private static void text(Map<String,Object> row,String column,int max,boolean nullable){text(row,column,max,nullable,false);}
    private static void text(Map<String,Object> row,String column,int max,boolean nullable,boolean empty){Object value=row.get(column);if(value==null&&nullable)return;if(!(value instanceof String)||((String)value).length()>max||(!empty&&((String)value).isEmpty()))throw invalid();}
    private static void oneOf(Map<String,Object> row,String column,String...values){if(!Arrays.asList(values).contains(row.get(column)))throw invalid();}
    private static String[] csv(String value){return value.split(",");}
    private static String shortLabel(String label){if(label.length()<=120)return label;int end=120;if(Character.isHighSurrogate(label.charAt(end-1)))end--;return label.substring(0,end);}
    private static String join(String[] values){StringBuilder result=new StringBuilder();for(String value:values){if(result.length()>0)result.append(',');result.append(value);}return result.toString();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid or unsupported Xs metadata backup");}
}

package dev.forcemind.xs.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Metadata-only consolidation into a NEW library. Sources are never edited.
 * Inputs must come from the validated backup codec; output must be validated again.
 */
public final class LibraryMergePlan {
    public static final int MAX_SOURCES=4, MAX_RECORDS=20000;
    public static final long MAX_TEXT_BYTES=8L*1024*1024;
    public static final List<String> TABLES=Collections.unmodifiableList(Arrays.asList(
        "posts","history_events","user_actions","categories","post_categories",
        "favorites","post_tags","own_actions","own_action_events","snapshot_versions","timeline_promotions"));
    public static final class Input {
        public final String scope,label;
        private final Map<String,List<Map<String,Object>>> tables;
        public Input(String scope,String label,Map<String,List<Map<String,Object>>> tables) {
            if(scope==null||scope.isEmpty()||scope.length()>256||label==null||label.length()>120||tables==null)
                throw invalid("Invalid source library");
            this.scope=scope;this.label=label;this.tables=tables;
        }
    }
    public static final class Result {
        public final Map<String,List<Map<String,Object>>> tables;
        public final int sources,sourcePosts,posts,historyEvents;
        private Result(Map<String,List<Map<String,Object>>> tables,int sources,int sourcePosts) {
            Map<String,List<Map<String,Object>>> immutable=new LinkedHashMap<>();
            for(String table:TABLES) {
                List<Map<String,Object>> rows=new ArrayList<>();
                for(Map<String,Object> row:tables.get(table))rows.add(Collections.unmodifiableMap(row));
                immutable.put(table,Collections.unmodifiableList(rows));
            }
            this.tables=Collections.unmodifiableMap(immutable);this.sources=sources;this.sourcePosts=sourcePosts;
            posts=tables.get("posts").size();historyEvents=tables.get("history_events").size();
        }
    }
    private LibraryMergePlan() {}

    public static Result merge(List<Input> inputs) {
        if(inputs==null||inputs.size()<2||inputs.size()>MAX_SOURCES)throw invalid("Select 2 to 4 libraries");
        Set<String> scopes=new LinkedHashSet<>();long bytes=0;int records=0,sourcePosts=0;
        for(Input input:inputs) {
            if(input==null||!scopes.add(input.scope))throw invalid("Select each source once");
            for(String name:input.tables.keySet())if(!TABLES.contains(name))throw invalid("Unknown metadata table");
            for(List<Map<String,Object>> rows:input.tables.values()) {
                if(rows==null)throw invalid("Invalid metadata rows");
                records+=rows.size();if(records>MAX_RECORDS)throw invalid("Merge exceeds record budget");
                for(Map<String,Object> row:rows) {
                    if(row==null)throw invalid("Invalid metadata row");
                    for(Map.Entry<String,Object> field:row.entrySet()) {
                        bytes+=field.getKey().getBytes(StandardCharsets.UTF_8).length;
                        Object value=field.getValue();
                        if(value instanceof String)bytes+=((String)value).getBytes(StandardCharsets.UTF_8).length;
                        else if(value!=null&&!(value instanceof Long))throw invalid("Invalid metadata value");
                        if(bytes>MAX_TEXT_BYTES)throw invalid("Merge exceeds text budget; use smaller batches");
                    }
                }
            }
        }
        Map<String,List<Map<String,Object>>> out=new LinkedHashMap<>();
        for(String table:TABLES)out.put(table,new ArrayList<>());
        Map<String,Map<String,Object>> posts=new LinkedHashMap<>(),favorites=new LinkedHashMap<>();
        Map<String,SnapshotPolicy.Version> selectedVersions=new LinkedHashMap<>();
        Map<String,List<String>> notes=new LinkedHashMap<>();
        Map<String,String> firstNotes=new LinkedHashMap<>();
        Map<String,Map<String,Object>> actions=new LinkedHashMap<>();
        Set<String> memberships=new LinkedHashSet<>(),tags=new LinkedHashSet<>();
        int sourceOrdinal=0;
        for(Input input:inputs) {
            sourceOrdinal++;
            Map<String,Map<String,Object>> sourceVersions=new LinkedHashMap<>();
            for(Map<String,Object> original:rows(input,"snapshot_versions")) {
                String post=text(original,"post_id"),oldKey=text(original,"snapshot_key");
                sourceVersions.put(post+"\n"+oldKey,original);
                Map<String,Object> version=copy(original);
                version.put("snapshot_key",key("snapshot",input.scope,post+"\n"+oldKey));
                out.get("snapshot_versions").add(version);
            }
            for(Map<String,Object> original:rows(input,"posts")) {
                sourcePosts++;String id=text(original,"post_id");Map<String,Object> previous=posts.get(id);
                Map<String,Object> candidate=copy(original);SnapshotPolicy.Version version=null;
                Object pointer=original.get("current_snapshot_key");
                if(pointer!=null) {
                    Map<String,Object> snapshot=sourceVersions.get(id+"\n"+pointer);
                    if(snapshot==null||!java.util.Objects.equals(original.get("body"),snapshot.get("body")))throw invalid("Missing current snapshot reference");
                    candidate.put("current_snapshot_key",key("snapshot",input.scope,id+"\n"+pointer));
                    version=new SnapshotPolicy.Version((String)snapshot.get("body"),(String)snapshot.get("author_handle"),
                        (Long)snapshot.get("published_at_ms"),(Long)snapshot.get("captured_at_ms"),
                        SnapshotPolicy.Source.parse((String)snapshot.get("source")),SnapshotPolicy.Quality.valueOf((String)snapshot.get("quality")));
                }
                if(previous==null) {posts.put(id,candidate);if(version!=null)selectedVersions.put(id,version);}
                else {
                    long count=Math.addExact(number(previous,"opened_count",0),number(original,"opened_count",0));
                    if(count>Integer.MAX_VALUE)throw invalid("Merged open count exceeds supported range");
                    previous.put("opened_count",count);
                    previous.put("first_seen_at_ms",Math.min(number(previous,"first_seen_at_ms",0),number(original,"first_seen_at_ms",0)));
                    for(String field:Arrays.asList("last_seen_at_ms","last_opened_at_ms","last_feed_seen_at_ms"))
                        previous.put(field,max(previous.get(field),original.get(field)));
                    for(String field:Arrays.asList("author_handle","published_at_ms"))
                        if(previous.get(field)==null||"".equals(previous.get(field)))previous.put(field,original.get(field));
                    String body=(String)original.get("body"),old=(String)previous.get("body");
                    SnapshotPolicy.Version current=selectedVersions.get(id);
                    boolean select=version!=null?SnapshotPolicy.decide(current,null,version).selectCurrent:
                        current==null&&body!=null&&(old==null||body.length()>old.length());
                    if(select) {
                        previous.put("body",body);previous.put("snapshot_state",original.get("snapshot_state"));
                        if(candidate.containsKey("current_snapshot_key"))previous.put("current_snapshot_key",candidate.get("current_snapshot_key"));
                        if(version!=null)selectedVersions.put(id,version);
                    }
                }
            }
            Map<String,String> categoryIds=new LinkedHashMap<>();
            for(Map<String,Object> original:rows(input,"categories")) {
                Map<String,Object> row=copy(original);String old=text(row,"category_id");
                String key=key("category",input.scope,old);categoryIds.put(old,key);row.put("category_id",key);
                out.get("categories").add(row);
            }
            for(Map<String,Object> original:rows(input,"post_categories")) {
                Map<String,Object> row=copy(original);String category=categoryIds.get(text(row,"category_id"));
                if(category==null)throw invalid("Missing category reference");row.put("category_id",category);
                if(memberships.add(text(row,"post_id")+"\n"+category))out.get("post_categories").add(row);
            }
            int historyIndex=0;
            for(Map<String,Object> original:rows(input,"history_events")) {
                Map<String,Object> row=copy(original);
                // A visit in a separate source remains separate, even if an old event key collides.
                row.put("event_key",key("history",input.scope,(historyIndex++)+":"+String.valueOf(row.get("event_key"))));
                out.get("history_events").add(row);
            }
            for(Map<String,Object> original:rows(input,"user_actions")) {
                String identity=text(original,"post_id")+"\n"+text(original,"action_type")+"\n"+original.get("action_at_ms");
                if(!actions.containsKey(identity))actions.put(identity,copy(original));
                else if(!actions.get(identity).get("source").equals(original.get("source")))
                    throw invalid("Legacy action sources conflict; keep these libraries separate");
            }
            for(Map<String,Object> original:rows(input,"favorites")) {
                String id=text(original,"post_id");Map<String,Object> previous=favorites.get(id);
                if(previous==null) { previous=copy(original);favorites.put(id,previous); }
                else previous.put("favorited_at_ms",Math.min(number(previous,"favorited_at_ms",0),number(original,"favorited_at_ms",0)));
                String note=(String)original.get("note");
                if(note!=null&&!note.isEmpty()) {
                    List<String> existing=notes.computeIfAbsent(id,k->new ArrayList<>());
                    String formatted="["+input.label+" · "+sourceOrdinal+"]\n"+note;
                    // Keep different source notes, including identical notes with different provenance.
                    if(!firstNotes.containsKey(id))firstNotes.put(id,note);
                    if(!existing.contains(formatted))existing.add(formatted);
                }
            }
            for(Map<String,Object> original:rows(input,"post_tags")) {
                String identity=text(original,"post_id")+"\n"+text(original,"tag");
                if(tags.add(identity))out.get("post_tags").add(copy(original));
            }
            for(Map<String,Object> original:rows(input,"timeline_promotions")) {
                Map<String,Object> row=copy(original);
                row.put("promotion_id",key("timeline-promotion",input.scope,text(row,"promotion_id")));
                out.get("timeline_promotions").add(row);
            }
            for(Map<String,Object> original:rows(input,"own_actions")) {
                Map<String,Object> row=copy(original);row.put("action_key",key("own",input.scope,text(row,"action_key")));
                out.get("own_actions").add(row);
            }
            for(Map<String,Object> original:rows(input,"own_action_events")) {
                Map<String,Object> row=copy(original);row.put("action_key",key("own",input.scope,text(row,"action_key")));
                row.put("event_key",key("own-event",input.scope,text(row,"event_key")));
                out.get("own_action_events").add(row);
            }
        }
        for(Map.Entry<String,Map<String,Object>> favorite:favorites.entrySet()) {
            List<String> sourceNotes=notes.get(favorite.getKey());
            if(sourceNotes!=null&&sourceNotes.size()==1)favorite.getValue().put("note",firstNotes.get(favorite.getKey()));
            if(sourceNotes!=null&&sourceNotes.size()>1) {
                String merged=String.join("\n\n",sourceNotes);
                if(merged.length()>8000)throw invalid("Conflicting notes exceed the note limit; keep sources separate");
                favorite.getValue().put("note",merged);
            }
        }
        out.get("posts").addAll(posts.values());out.get("favorites").addAll(favorites.values());
        out.get("user_actions").addAll(actions.values());
        return new Result(out,inputs.size(),sourcePosts);
    }
    private static List<Map<String,Object>> rows(Input input,String table) {
        List<Map<String,Object>> rows=input.tables.get(table);return rows==null?Collections.emptyList():rows;
    }
    private static Map<String,Object> copy(Map<String,Object> original) {
        Map<String,Object> copy=new LinkedHashMap<>(original);copy.remove("account_id");copy.remove("id");return copy;
    }
    private static String text(Map<String,Object> row,String field) {
        Object value=row.get(field);if(!(value instanceof String)||((String)value).isEmpty())throw invalid("Required metadata key missing");return(String)value;
    }
    private static long number(Map<String,Object> row,String field,long fallback) {
        Object value=row.get(field);if(value==null)return fallback;if(!(value instanceof Long))throw invalid("Invalid metadata number");return(Long)value;
    }
    private static Object max(Object a,Object b) { if(a==null)return b;if(b==null)return a;return Math.max((Long)a,(Long)b); }
    private static String key(String kind,String scope,String original) {
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest((kind+"\0"+scope+"\0"+original).getBytes(StandardCharsets.UTF_8));
            StringBuilder value=new StringBuilder("merge:");for(byte item:digest)value.append(String.format(java.util.Locale.ROOT,"%02x",item&255));return value.toString();
        } catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}

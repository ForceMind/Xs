package dev.forcemind.xs.tests;

import dev.forcemind.xs.core.LibraryMergePlan;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Product-level invariants for explicit, non-destructive multi-library consolidation. */
public final class LibraryMergePlanTest {
    private static int assertions;
    private static void check(boolean value) { assertions++;if(!value)throw new AssertionError("Merge invariant "+assertions); }
    private static Map<String,Object> row(Object... fields) {
        Map<String,Object> row=new LinkedHashMap<>();
        for(int i=0;i<fields.length;i+=2)row.put((String)fields[i],fields[i+1]);return row;
    }
    private static Map<String,List<Map<String,Object>>> source(long time,String body,String note) {
        Map<String,List<Map<String,Object>>> data=new LinkedHashMap<>();
        for(String table:LibraryMergePlan.TABLES)data.put(table,new ArrayList<>());
        data.get("posts").add(row("post_id","100","author_handle",body==null?null:"alice","body",body,
            "published_at_ms",body==null?null:1L,"first_seen_at_ms",time,"last_seen_at_ms",time,
            "last_opened_at_ms",time,"last_feed_seen_at_ms",null,"opened_count",1L,
            "snapshot_state",body==null?"LINK_ONLY":"VISIBLE_TEXT"));
        data.get("history_events").add(row("post_id","100","event_type","OPEN_POST","event_at_ms",time,
            "source","post_detail","event_key","same-navigation","duration_ms",null,"threshold_ms",null,"duration_quality",null));
        data.get("categories").add(row("category_id","same-folder","label","工作"));
        data.get("post_categories").add(row("post_id","100","category_id","same-folder"));
        data.get("favorites").add(row("post_id","100","favorited_at_ms",time,"note",note));
        data.get("post_tags").add(row("post_id","100","tag","AI"));
        data.get("user_actions").add(row("post_id","100","action_type","REPOST","action_at_ms",2L,"source","local_x_archive"));
        data.get("own_actions").add(row("action_key","same-action","post_id","100","action_type","REPOST"));
        data.get("own_action_events").add(row("action_key","same-action","event_key","same-event","event_type","REVERSED"));
        return data;
    }
    private static void version(Map<String,List<Map<String,Object>>> data,String key,long time,String quality) {
        Map<String,Object> post=data.get("posts").get(0);post.put("current_snapshot_key",key);
        data.get("snapshot_versions").add(row("post_id",post.get("post_id"),"snapshot_key",key,
            "author_handle",post.get("author_handle"),"body",post.get("body"),"published_at_ms",post.get("published_at_ms"),
            "captured_at_ms",time,"source","native_post_detail","quality",quality,
            "content_hash",dev.forcemind.xs.core.SnapshotPolicy.hash((String)post.get("body"))));
    }
    private static LibraryMergePlan.Input input(String scope,Map<String,List<Map<String,Object>>> data) {
        return new LibraryMergePlan.Input(scope,scope,data);
    }
    public static void main(String[] args) {
        Map<String,List<Map<String,Object>>> a=source(5,null,"第一份备注 😀"), b=source(20,"较完整的正文","第二份备注");
        LibraryMergePlan.Result r=LibraryMergePlan.merge(Arrays.asList(input("source-A",a),input("source-B",b)));
        check(r.sources==2&&r.sourcePosts==2&&r.posts==1&&r.historyEvents==2);
        Map<String,Object> p=r.tables.get("posts").get(0);
        check(p.get("body").equals("较完整的正文")&&p.get("author_handle").equals("alice"));
        check(p.get("first_seen_at_ms").equals(5L)&&p.get("last_seen_at_ms").equals(20L)&&p.get("opened_count").equals(2L));
        check(a.get("posts").get(0).get("body")==null&&a.get("posts").get(0).get("opened_count").equals(1L));
        check(r.tables.get("history_events").size()==2);
        check(!r.tables.get("history_events").get(0).get("event_key").equals(r.tables.get("history_events").get(1).get("event_key")));
        check(r.tables.get("history_events").get(0).get("source").equals("post_detail"));
        check(r.tables.get("categories").size()==2&&r.tables.get("post_categories").size()==2);
        check(!r.tables.get("categories").get(0).get("category_id").equals(r.tables.get("categories").get(1).get("category_id")));
        check(r.tables.get("post_categories").get(1).get("category_id").equals(r.tables.get("categories").get(1).get("category_id")));
        check(r.tables.get("favorites").get(0).get("favorited_at_ms").equals(5L));
        String note=(String)r.tables.get("favorites").get(0).get("note");
        check(note.contains("第一份备注 😀")&&note.contains("第二份备注")&&note.contains("source-A")&&note.contains("source-B"));
        check(r.tables.get("post_tags").size()==1&&r.tables.get("user_actions").size()==1);
        check(r.tables.get("own_actions").size()==2&&r.tables.get("own_action_events").size()==2);
        check(r.tables.get("own_actions").get(1).get("action_key").equals(r.tables.get("own_action_events").get(1).get("action_key")));
        check(!r.tables.get("own_actions").get(0).get("action_key").equals(r.tables.get("own_actions").get(1).get("action_key")));
        LibraryMergePlan.Result again=LibraryMergePlan.merge(Arrays.asList(input("source-A",a),input("source-B",b)));
        check(r.tables.equals(again.tables));
        try { r.tables.get("posts").get(0).put("body","changed");check(false); } catch(UnsupportedOperationException expected) {check(true);}
        Map<String,List<Map<String,Object>>> emptyNote=source(1,null,"");
        LibraryMergePlan.Result one=LibraryMergePlan.merge(Arrays.asList(input("one",emptyNote),input("two",b)));
        check(one.tables.get("favorites").get(0).get("note").equals("第二份备注"));
        try {LibraryMergePlan.merge(Arrays.asList(input("same",a),input("same",b)));check(false);}catch(IllegalArgumentException expected){check(true);}
        Map<String,List<Map<String,Object>>> longNote=source(10,"long",new String(new char[5000]).replace('\0','x'));
        try {LibraryMergePlan.merge(Arrays.asList(input("one",longNote),input("two",longNote)));check(false);}catch(IllegalArgumentException expected){check(expected.getMessage().contains("notes"));}
        check(longNote.get("favorites").get(0).get("note").toString().length()==5000);
        LibraryMergePlan.Result sameLabel=LibraryMergePlan.merge(Arrays.asList(
            new LibraryMergePlan.Input("one","same",b),new LibraryMergePlan.Input("two","same",b)));
        String both=(String)sameLabel.tables.get("favorites").get(0).get("note");
        check(both.contains("same · 1")&&both.contains("same · 2"));
        Map<String,List<Map<String,Object>>> differentSource=source(20,"body","note");
        differentSource.get("user_actions").get(0).put("source","different");
        try {LibraryMergePlan.merge(Arrays.asList(input("one",a),input("two",differentSource)));check(false);}
        catch(IllegalArgumentException expected){check(expected.getMessage().contains("sources conflict"));}
        Map<String,List<Map<String,Object>>> oldFull=source(5,"older complete long body","note");
        Map<String,List<Map<String,Object>>> newFull=source(7,"edit","note");
        version(oldFull,"collision",10L,"COMPLETE");version(newFull,"collision",20L,"COMPLETE");
        LibraryMergePlan.Result changed=LibraryMergePlan.merge(Arrays.asList(input("old",oldFull),input("new",newFull)));
        check(changed.tables.get("snapshot_versions").size()==2);
        check(changed.tables.get("posts").get(0).get("body").equals("edit"));
        Object pointer=changed.tables.get("posts").get(0).get("current_snapshot_key");
        check(pointer.equals(changed.tables.get("snapshot_versions").get(1).get("snapshot_key")));
        check(!pointer.equals(changed.tables.get("snapshot_versions").get(0).get("snapshot_key")));
        check(oldFull.get("posts").get(0).get("current_snapshot_key").equals("collision"));
        newFull.get("snapshot_versions").get(0).put("quality","PARTIAL");
        LibraryMergePlan.Result partial=LibraryMergePlan.merge(Arrays.asList(input("old",oldFull),input("new",newFull)));
        check(partial.tables.get("posts").get(0).get("body").equals("older complete long body"));
        newFull.get("snapshot_versions").get(0).put("quality","COMPLETE");newFull.get("snapshot_versions").get(0).put("captured_at_ms",1L);
        LibraryMergePlan.Result late=LibraryMergePlan.merge(Arrays.asList(input("old",oldFull),input("new",newFull)));
        check(late.tables.get("posts").get(0).get("body").equals("older complete long body"));
        Map<String,List<Map<String,Object>>> missing=source(5,"text","note");missing.get("posts").get(0).put("current_snapshot_key","missing");
        try{LibraryMergePlan.merge(Arrays.asList(input("one",missing),input("two",newFull)));check(false);}
        catch(IllegalArgumentException expected){check(expected.getMessage().contains("snapshot reference"));}
        Map<String,List<Map<String,Object>>> savedA=source(10,"saved A",""),savedB=source(20,"saved B","");
        for(Map<String,List<Map<String,Object>>> saved:Arrays.asList(savedA,savedB)) {
            saved.get("timeline_promotions").add(row("promotion_id","same-native-id","post_id","100", "captured_at_ms",5L,
                "saved_at_ms",10L,"favorited_at_ms",null,"timeline_source","unknown","batch_id","native:batch",
                "batch_reason","native_navigation","provenance","native_dom_fixture","quality","PARTIAL","content_hash",null,"author_handle","author","published_at_ms",null,"snapshot_captured_at_ms",5L));
        }
        LibraryMergePlan.Result promoted=LibraryMergePlan.merge(Arrays.asList(input("saved-A",savedA),input("saved-B",savedB)));
        check(promoted.tables.get("timeline_promotions").size()==2);
        check(!promoted.tables.get("timeline_promotions").get(0).get("promotion_id").equals(promoted.tables.get("timeline_promotions").get(1).get("promotion_id")));
        check(promoted.tables.get("timeline_promotions").get(0).get("timeline_source").equals("unknown"));
        check(savedA.get("timeline_promotions").get(0).get("promotion_id").equals("same-native-id"));
        System.out.println("PASS "+assertions+" explicit library merge invariants");
    }
}

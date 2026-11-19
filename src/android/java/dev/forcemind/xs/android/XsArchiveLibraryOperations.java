package dev.forcemind.xs.android;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Native batch mutations. Call only from the library's shared write coordinator. */
public final class XsArchiveLibraryOperations {
    private XsArchiveLibraryOperations() {}
    public static final int MAX_BATCH = 200;

    public static void favorite(XsArchiveDatabase archive,String library,List<String> postIds) {
        List<String> ids=validate(archive,library,postIds);
        transaction(archive,library,()->{for(String id:ids)favoriteOne(archive,library,id);});
    }
    public static void assignCategory(XsArchiveDatabase archive,String library,List<String> postIds,String category) {
        List<String> ids=validate(archive,library,postIds);
        transaction(archive,library,()->{
            try(Cursor c=archive.getReadableDatabase().rawQuery(
                    "SELECT 1 FROM categories WHERE account_id=? AND category_id=?",new String[]{library,category})) {
                if(!c.moveToFirst())throw new IllegalArgumentException("Existing category required");
            }
            for(String id:ids)archive.assignCategory(library,id,category);
        });
    }
    /** Explicitly favorites selected posts and preserves notes; append or replace tags. */
    public static void tags(XsArchiveDatabase archive,String library,List<String> postIds,String commaSeparated,boolean append) {
        List<String> ids=validate(archive,library,postIds);
        Set<String> requested=parseTags(commaSeparated);
        transaction(archive,library,()->{
            for(String id:ids) {
                favoriteOne(archive,library,id);
                String note="";Set<String> tags=new LinkedHashSet<>();
                try(Cursor c=archive.getReadableDatabase().rawQuery(
                    "SELECT note FROM favorites WHERE account_id=? AND post_id=?",new String[]{library,id})) {
                    if(c.moveToFirst())note=c.getString(0);
                }
                if(append)try(Cursor c=archive.getReadableDatabase().rawQuery(
                    "SELECT tag FROM post_tags WHERE account_id=? AND post_id=? ORDER BY tag",new String[]{library,id})) {
                    while(c.moveToNext())tags.add(c.getString(0));
                }
                tags.addAll(requested);
                if(tags.size()>20)throw new IllegalArgumentException("Too many tags");
                archive.putFavoriteDetails(library,id,note,String.join(",",tags));
            }
        });
    }
    public static void unfavorite(XsArchiveDatabase archive,String library,List<String> postIds) {
        List<String> ids=validate(archive,library,postIds);
        transaction(archive,library,()->{for(String id:ids)archive.removeFavorite(library,id);});
    }
    /** Library deletion intentionally removes metadata rather than just history. */
    public static void eraseLibrary(XsArchiveDatabase archive,String library) {
        archive.deleteLibraryScope(library);
    }
    private static void favoriteOne(XsArchiveDatabase archive,String library,String id) {
        try(Cursor c=archive.getReadableDatabase().rawQuery(
            "SELECT author_handle,body,published_at_ms FROM posts WHERE account_id=? AND post_id=?",new String[]{library,id})) {
            if(!c.moveToFirst())throw new IllegalArgumentException("Post no longer exists");
            archive.favorite(library,"https://x.com/i/web/status/"+id,c.getString(0),c.getString(1),
                c.isNull(2)?null:c.getLong(2),System.currentTimeMillis());
        }
    }
    private static Set<String> parseTags(String input) {
        if(input==null||input.length()>1400)throw new IllegalArgumentException("Tags required");
        Set<String> tags=new LinkedHashSet<>();
        for(String raw:input.split(",")){String tag=raw.trim();if(tag.length()>64)throw new IllegalArgumentException("Tag too long");if(!tag.isEmpty())tags.add(tag);}
        if(tags.size()>20)throw new IllegalArgumentException("Too many tags");return tags;
    }
    private static List<String> validate(XsArchiveDatabase archive,String library,List<String> input) {
        if(archive==null||library==null||library.isEmpty()||input==null||input.isEmpty()||input.size()>MAX_BATCH)
            throw new IllegalArgumentException("Select 1 to 200 posts");
        List<String> ids=new ArrayList<>(new LinkedHashSet<>(input));
        for(String id:ids) {
            if(id==null||!id.matches("[0-9]{1,20}"))throw new IllegalArgumentException("Invalid post");
            try(Cursor c=archive.getReadableDatabase().rawQuery("SELECT 1 FROM posts WHERE account_id=? AND post_id=?",new String[]{library,id})) {
                if(!c.moveToFirst())throw new IllegalArgumentException("Post no longer exists");
            }
        }
        return ids;
    }
    private static void transaction(XsArchiveDatabase archive,String library,Runnable mutation) {
        SQLiteDatabase db=archive.getWritableDatabase();db.beginTransaction();
        try{if(XsLibraryWrites.isDeleted(library))throw new IllegalStateException("Library deleted");mutation.run();db.setTransactionSuccessful();}finally{db.endTransaction();}
    }
}

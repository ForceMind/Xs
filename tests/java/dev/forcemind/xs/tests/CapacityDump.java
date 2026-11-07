package dev.forcemind.xs.tests;

import dev.forcemind.xs.core.ArchiveSchema;
import dev.forcemind.xs.core.ArchiveSearch;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Exact runtime SQL for the repeatable desktop SQLite capacity experiment. */
public final class CapacityDump {
    private static void line(String kind,String text){System.out.println(kind+":"+Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)));}
    private static void query(String name,ArchiveSearch.Filter f){
        ArchiveSearch.Query q=ArchiveSearch.build("library:capacity",f);line("QUERY_"+name,q.sql);
        for(String arg:q.args)line("ARG_"+name,arg);
    }
    public static void main(String[] args){
        for(String ddl:ArchiveSchema.CREATE_V2)line("V2",ddl);
        for(String ddl:ArchiveSchema.MIGRATE_1_TO_2)line("M2",ddl);
        for(String ddl:ArchiveSchema.MIGRATE_2_TO_3)line("M3",ddl);
        for(String ddl:ArchiveSchema.MIGRATE_3_TO_4)line("M4",ddl);
        for(String ddl:ArchiveSchema.MIGRATE_4_TO_5)line("M5",ddl);
        query("RECENT",new ArchiveSearch.Filter());
        ArchiveSearch.Filter f=new ArchiveSearch.Filter();f.keyword="needle";query("LATIN",f);
        f=new ArchiveSearch.Filter();f.keyword="容量";query("CJK",f);
        f=new ArchiveSearch.Filter();f.author="@user7";query("AUTHOR",f);
        f=new ArchiveSearch.Filter();f.minDwellMs=3000L;f.neverOpened=true;f.sortByDwell=true;
        f.source="home_following";query("DWELL_NEVER_OPENED",f);
        f.dateField=ArchiveSearch.DateField.FEED_SEEN;f.fromMs=1_700_000_250_000L;f.toMs=1_700_000_750_000L;
        query("DWELL_DATE_SOURCE",f);
        f=new ArchiveSearch.Filter();f.categoryId="research";f.favoriteOnly=true;f.tag="capacity";query("FAVORITE_CATEGORY",f);
        f=new ArchiveSearch.Filter();f.keyword="superseded";f.includeSnapshotVersions=true;query("VERSION_HISTORY",f);
    }
}

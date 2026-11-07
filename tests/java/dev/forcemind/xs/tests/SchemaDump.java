package dev.forcemind.xs.tests;

import dev.forcemind.xs.core.ArchiveSchema;
import dev.forcemind.xs.core.ArchiveSearch;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Exports evaluated Java DDL strings for SQLite integration tests. */
public final class SchemaDump {
    public static void main(String[] args) {
        for (String ddl : ArchiveSchema.CREATE) {
            System.out.println(Base64.getEncoder().encodeToString(
                ddl.getBytes(StandardCharsets.UTF_8)));
        }
        for (String ddl : ArchiveSchema.CREATE_V2) {
            System.out.println("V2:" + Base64.getEncoder().encodeToString(
                ddl.getBytes(StandardCharsets.UTF_8)));
        }
        for (String migration : ArchiveSchema.MIGRATE_2_TO_3) {
            System.out.println("V3:" + Base64.getEncoder().encodeToString(
                migration.getBytes(StandardCharsets.UTF_8)));
        }
        for (String migration : ArchiveSchema.MIGRATE_3_TO_4) {
            System.out.println("V4:" + Base64.getEncoder().encodeToString(migration.getBytes(StandardCharsets.UTF_8)));
        }
        for (String migration : ArchiveSchema.MIGRATE_4_TO_5) {
            System.out.println("V5:" + Base64.getEncoder().encodeToString(migration.getBytes(StandardCharsets.UTF_8)));
        }
        for (String migration : ArchiveSchema.MIGRATE_1_TO_2) {
            System.out.println("MIGRATE:" + Base64.getEncoder().encodeToString(
                migration.getBytes(StandardCharsets.UTF_8)));
        }
        ArchiveSearch.Filter f = new ArchiveSearch.Filter();
        f.keyword = "robot";
        f.minDwellMs = 3000L;
        f.neverOpened = true;
        f.sortByDwell = true;
        ArchiveSearch.Query q = ArchiveSearch.build("local:test", f);
        System.out.println("QUERY:" + Base64.getEncoder().encodeToString(q.sql.getBytes(StandardCharsets.UTF_8)));
        for (String arg : q.args) System.out.println("ARG:" + Base64.getEncoder().encodeToString(arg.getBytes(StandardCharsets.UTF_8)));
        f = new ArchiveSearch.Filter();
        f.keyword="annotation"; f.favoriteOnly=true; f.tag="research"; f.author="@alice";
        f.fromMs=0L; f.toMs=1000L; f.dateField=ArchiveSearch.DateField.FAVORITED;
        q=ArchiveSearch.build("local:test",f);
        System.out.println("META_QUERY:" + Base64.getEncoder().encodeToString(q.sql.getBytes(StandardCharsets.UTF_8)));
        for (String arg:q.args) System.out.println("META_ARG:" + Base64.getEncoder().encodeToString(arg.getBytes(StandardCharsets.UTF_8)));
        f = new ArchiveSearch.Filter();
        f.dateField=ArchiveSearch.DateField.FEED_SEEN; f.fromMs=100L; f.toMs=200L;
        f.minDwellMs=3000L; f.sortByDwell=true;
        dumpQuery("DATE",ArchiveSearch.build("local:dates",f));
        f = new ArchiveSearch.Filter();
        f.source="home_following"; f.minDwellMs=3000L; f.sortByDwell=true;
        dumpQuery("SOURCE",ArchiveSearch.build("local:sources",f));
        f.dateField=ArchiveSearch.DateField.FEED_SEEN; f.fromMs=100L; f.toMs=200L;
        dumpQuery("SOURCE_DATE",ArchiveSearch.build("local:sources",f));
        f = new ArchiveSearch.Filter();
        f.dateField=ArchiveSearch.DateField.PUBLISHED; f.fromMs=100L; f.toMs=200L;
        f.minDwellMs=3000L; f.sortByDwell=true;
        dumpQuery("PUBLISHED",ArchiveSearch.build("local:dates",f));
        f=new ArchiveSearch.Filter(); f.ownOnly=true; f.actionType="REPOST";
        f.actorId="fixture:me"; f.actionState="REVERSED"; f.actionSource="native_fixture";
        f.dateField=ArchiveSearch.DateField.ACTION; f.fromMs=100L; f.toMs=200L;
        dumpQuery("OWN",ArchiveSearch.build("local:own",f));
        f=new ArchiveSearch.Filter();f.keyword="superseded";
        dumpQuery("SNAPSHOT_CURRENT",ArchiveSearch.build("local:snapshots",f));
        f.includeSnapshotVersions=true;
        dumpQuery("SNAPSHOT_HISTORY",ArchiveSearch.build("local:snapshots",f));
        dumpQuery("SNAPSHOT_VERSIONS",ArchiveSearch.buildSnapshots("local:snapshots","901","superseded",50,0));
    }    private static void dumpQuery(String name,ArchiveSearch.Query query) {
        System.out.println(name+"_QUERY:"+Base64.getEncoder().encodeToString(query.sql.getBytes(StandardCharsets.UTF_8)));
        for(String arg:query.args) System.out.println(name+"_ARG:"+Base64.getEncoder().encodeToString(arg.getBytes(StandardCharsets.UTF_8)));
    }

}

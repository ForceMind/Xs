package dev.forcemind.xs.tests;
import dev.forcemind.xs.core.SnapshotPolicy;
import dev.forcemind.xs.core.SnapshotPolicy.Version;
import dev.forcemind.xs.core.SnapshotPolicy.Source;
import dev.forcemind.xs.core.SnapshotPolicy.Quality;
import dev.forcemind.xs.core.ArchiveSearch;

public final class SnapshotPolicyTest {
    private static int checks;
    public static void main(String[] args) {
        Version longFull=v("Long complete original text",100L,Quality.COMPLETE);
        yes(SnapshotPolicy.decide(null,null,longFull).selectCurrent);
        Version shorter=v("edited",200L,Quality.COMPLETE);
        yes(SnapshotPolicy.decide(longFull,longFull,shorter).selectCurrent);
        yes(!SnapshotPolicy.decide(shorter,shorter,v("stale but much longer body",150L,Quality.COMPLETE)).selectCurrent);
        yes(!SnapshotPolicy.decide(shorter,shorter,v("same time conflicting body",200L,Quality.COMPLETE)).selectCurrent);
        Version truncated=v("partial",300L,Quality.PARTIAL);
        yes(SnapshotPolicy.decide(longFull,longFull,truncated).record);
        yes(!SnapshotPolicy.decide(longFull,longFull,truncated).selectCurrent);
        yes(!SnapshotPolicy.decide(longFull,truncated,v("partial",400L,Quality.PARTIAL)).record);
        yes(!SnapshotPolicy.decide(longFull,longFull,v("",500L,Quality.LINK_ONLY)).record);
        yes(!SnapshotPolicy.decide(longFull,longFull,v(longFull.body,500L,Quality.COMPLETE)).record);
        Version shorterPartial=v("short",200L,Quality.PARTIAL);
        yes(!SnapshotPolicy.decide(v("longer partial text",100L,Quality.PARTIAL),null,shorterPartial).selectCurrent);
        yes(SnapshotPolicy.decide(shorterPartial,null,v("longer partial text",300L,Quality.PARTIAL)).selectCurrent);
        yes(SnapshotPolicy.decide(v("old text",null,Quality.LEGACY_UNKNOWN),null,v("new",100L,Quality.COMPLETE)).selectCurrent);
        Version migrated=v("old legacy body",null,Quality.LEGACY_UNKNOWN);
        yes(SnapshotPolicy.decide(migrated,migrated,v("old legacy body",100L,Quality.PARTIAL)).selectCurrent);
        yes(!SnapshotPolicy.decide(longFull,longFull,new Version("old export",null,null,500L,Source.ARCHIVE,Quality.COMPLETE)).selectCurrent);
        yes(SnapshotPolicy.decide(shorter,shorter,v(longFull.body,600L,Quality.COMPLETE)).record);
        yes(SnapshotPolicy.decide(shorter,shorter,v(longFull.body,600L,Quality.COMPLETE)).selectCurrent);
        yes("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad".equals(SnapshotPolicy.hash("abc")));
        yes(!SnapshotPolicy.hash("正文😀").equals(SnapshotPolicy.hash("正文😃")));
        char[] oversized=new char[16001];java.util.Arrays.fill(oversized,'x');
        invalid(()->v(new String(oversized),100L,Quality.PARTIAL));
        Version historical=new Version("old\u0000"+new String(oversized),null,null,null,Source.MIGRATION,Quality.LEGACY_UNKNOWN);
        yes(SnapshotPolicy.decide(historical,historical,v("valid full text",100L,Quality.COMPLETE)).selectCurrent);
        invalid(()->v("a\u0000b",100L,Quality.PARTIAL));
        invalid(()->v("unpaired\uD800",100L,Quality.COMPLETE));
        invalid(()->v("text",-1L,Quality.PARTIAL));
        invalid(()->new Version("body",null,-1L,100L,Source.POST_DETAIL,Quality.COMPLETE));
        ArchiveSearch.Filter filter=new ArchiveSearch.Filter();filter.keyword="old snapshot";
        yes(!ArchiveSearch.build("local:test",filter).sql.contains("snapshot_versions_fts MATCH"));
        filter.includeSnapshotVersions=true;
        yes(ArchiveSearch.build("local:test",filter).sql.contains("snapshot_versions_fts MATCH ?"));
        ArchiveSearch.Query query=ArchiveSearch.buildSnapshots("local:test","42","quoted ' injection %_",100,0);
        yes(!query.sql.contains("injection"));
        yes(query.sql.contains("v.account_id=? AND v.post_id=?"));
        yes(query.args[2].equals("%quoted ' injection !%!_%"));
        invalid(()->ArchiveSearch.buildSnapshots("local:test","not-id",null,100,0));
        invalid(()->ArchiveSearch.buildSnapshots("local:test","42",null,100,-1));
        System.out.println("PASS "+checks+" snapshot selection/hash/query assertions");
    }
    private static Version v(String body,Long at,Quality quality){return new Version(body,"author",50L,at,Source.POST_DETAIL,quality);}
    private static void yes(boolean okay){checks++;if(!okay)throw new AssertionError("snapshot check "+checks);}
    private static void invalid(Runnable work){try{work.run();throw new AssertionError("expected invalid");}catch(IllegalArgumentException expected){checks++;}}
}

package dev.forcemind.xs.tests;

import dev.forcemind.xs.core.TimelinePromotionData;
import dev.forcemind.xs.core.SnapshotPolicy;

public final class TimelinePromotionDataTest {
    private static int assertions;
    public static void main(String[] args) {
        TimelinePromotionData item=data("https://x.com/i/web/status/123","visible 中文",SnapshotPolicy.Quality.PARTIAL,"unknown","native_dom_fixture");
        check("123".equals(item.postId));check("https://x.com/i/web/status/123".equals(item.url));
        check(item.capturedAt==1000);check(item.publishedAt==null);check(item.quality==SnapshotPolicy.Quality.PARTIAL);
        check(SnapshotPolicy.Source.parse("native_timeline_buffer")==SnapshotPolicy.Source.TIMELINE_BUFFER);
        reject(()->data("https://x.com/messages","visible",SnapshotPolicy.Quality.PARTIAL,"unknown","native_dom_fixture"));
        reject(()->data("https://x.com/demo/status/123","visible",SnapshotPolicy.Quality.COMPLETE,"unknown","native_dom_fixture"));
        reject(()->data("https://x.com/demo/status/123","visible",SnapshotPolicy.Quality.LINK_ONLY,"unknown","native_dom_fixture"));
        reject(()->data("https://x.com/demo/status/123","visible",SnapshotPolicy.Quality.PARTIAL,"invented","native_dom_fixture"));
        reject(()->data("https://x.com/demo/status/123","visible",SnapshotPolicy.Quality.PARTIAL,"unknown","remote_claim"));
        check(data("https://x.com/demo/status/123","",SnapshotPolicy.Quality.LINK_ONLY,"unknown","native_dom_fixture").body.isEmpty());
        System.out.println("PASS "+assertions+" native timeline promotion projection assertions");
    }
    private static TimelinePromotionData data(String url,String body,SnapshotPolicy.Quality quality,String source,String provenance) {
        return new TimelinePromotionData("promotion:1",url,body,"author",null,1000,source,"batch:1","native_refresh",provenance,quality);
    }
    private static void check(boolean ok){assertions++;if(!ok)throw new AssertionError("Timeline promotion assertion "+assertions);}
    private static void reject(Runnable op){try{op.run();throw new AssertionError("Rejected projection accepted");}catch(IllegalArgumentException expected){assertions++;}}
}

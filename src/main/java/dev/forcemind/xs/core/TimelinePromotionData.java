package dev.forcemind.xs.core;

import java.util.Arrays;

/** Immutable native projection accepted for an explicit permanent save. No observation event. */
public final class TimelinePromotionData {
    public final String promotionId,url,postId,body,author,source,batchId,batchReason,provenance;
    public final Long publishedAt;
    public final long capturedAt,snapshotCapturedAt;
    public final SnapshotPolicy.Quality quality;
    public TimelinePromotionData(String promotionId,String url,String body,String author,Long publishedAt,
            long capturedAt,String source,String batchId,String batchReason,String provenance,SnapshotPolicy.Quality quality) {
        this(promotionId,url,body,author,publishedAt,capturedAt,capturedAt,source,batchId,batchReason,provenance,quality);
    }
    public TimelinePromotionData(String promotionId,String url,String body,String author,Long publishedAt,
            long capturedAt,long snapshotCapturedAt,String source,String batchId,String batchReason,String provenance,SnapshotPolicy.Quality quality) {
        this.postId=PostUrls.extractPostId(url);
        if(this.postId==null||!bounded(promotionId,256)||!bounded(batchId,256)||capturedAt<=0||snapshotCapturedAt<=0||
                !Arrays.asList("home_for_you","home_following","search_results","unknown").contains(source)||
                !Arrays.asList("native_navigation","native_refresh","native_stream_switch","native_stream_change","native_session","suspected_refresh").contains(batchReason)||
                !Arrays.asList("native_dom_fixture","offline_dom_fixture").contains(provenance)||
                (quality!=SnapshotPolicy.Quality.LINK_ONLY&&quality!=SnapshotPolicy.Quality.PARTIAL))
            throw new IllegalArgumentException("Invalid native timeline promotion");
        new SnapshotPolicy.Version(body==null?"":body,author,publishedAt,snapshotCapturedAt,SnapshotPolicy.Source.TIMELINE_BUFFER,quality);
        if(quality==SnapshotPolicy.Quality.LINK_ONLY&&body!=null&&!body.isEmpty())throw new IllegalArgumentException("Link-only body");
        this.promotionId=promotionId;this.url=PostUrls.canonicalLink(url);this.body=body==null?"":body;
        this.author=author;this.publishedAt=publishedAt;this.capturedAt=capturedAt;this.snapshotCapturedAt=snapshotCapturedAt;this.source=source;
        this.batchId=batchId;this.batchReason=batchReason;this.provenance=provenance;this.quality=quality;
    }
    private static boolean bounded(String value,int limit) {
        return value!=null&&!value.isEmpty()&&value.length()<=limit&&value.indexOf('\0')<0;
    }
}

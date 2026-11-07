package dev.forcemind.xs.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Native-owned fixture capture state. It has no network or storage capability. */
public final class CaptureSession {
    public static final long DEFAULT_THRESHOLD_MS = 3000;
    public static final long MAX_SAMPLE_GAP_MS = 600;
    public static final long MAX_DWELL_MS = 600_000;
    /** Persist qualification immediately, then coalesce updates; departure flushes the last sample. */
    public static final long UPDATE_INTERVAL_MS = 1000;
    public static final int MAX_CARDS = 24;
    public interface Sink {
        void opened(Event event);
        void enriched(Event event);
        void feed(Event event);
    }
    public enum Kind { OPEN, ENRICH, FEED }
    public static final class Event {
        public final Kind kind;
        public final long generation, wallTimeMs, capturedAtMs, durationMs, thresholdMs;
        public final String library, url, author, body, navigationId, exposureId, source, durationQuality;
        public final Long publishedAtMs;
        /** COMPLETE means the accepted visible DOM body was wholly captured, not server全文. */
        public final SnapshotPolicy.Quality snapshotQuality;
        private final long consentEpoch;
        private final String ownerSession;
        Event(Kind kind, long generation, String library, Card card, String navigationId,
              String exposureId, String source, long wallTime, long capturedAt, long duration, long threshold,
              long consentEpoch,String ownerSession) {
            this.kind=kind; this.generation=generation; this.library=library; url=card.url;
            author=card.author; body=card.body; publishedAtMs=card.publishedAtMs;
            this.navigationId=navigationId; this.exposureId=exposureId; this.source=source;
            wallTimeMs=wallTime; capturedAtMs=capturedAt; durationMs=duration; thresholdMs=threshold;
            this.consentEpoch=consentEpoch;
            this.ownerSession=ownerSession;
            snapshotQuality=card.body==null || card.body.isEmpty()?SnapshotPolicy.Quality.LINK_ONLY:
                kind!=Kind.FEED && card.completeText?SnapshotPolicy.Quality.COMPLETE:SnapshotPolicy.Quality.PARTIAL;
            durationQuality=duration>=MAX_DWELL_MS ? "CAPPED_LOWER_BOUND" : "SAMPLED_LOWER_BOUND";
        }
    }
    /** The adapter validates these untrusted DOM observations again before construction. */
    public static final class Card {
        public final String url, author, body, run;
        public final Long publishedAtMs;
        public final double readableFraction;
        public final boolean completeText;
        public Card(String url,String author,String body,Long published,double fraction,String run) {
            this(url,author,body,published,fraction,run,false);
        }
        /** Native adapter only: completeText requires independently checked structural evidence. */
        public Card(String url,String author,String body,Long published,double fraction,String run,boolean completeText) {
            this.url=url; this.author=author; this.body=body; publishedAtMs=published;
            readableFraction=fraction; this.run=run;this.completeText=completeText;
        }
    }
    private static final class Exposure {
        long start, last, qualifiedAt, capturedAt, publishedDuration=-1; final long threshold;
        final String id, run, source; Card card;
        Exposure(long now, long threshold, String id, Card card, String source) {
            start=last=now; this.threshold=threshold; this.id=id; run=card.run; this.card=card; this.source=source;
        }
    }
    private final String library;
    private final Sink sink;
    private final String sessionId=UUID.randomUUID().toString();
    private final Map<String,Exposure> active=new HashMap<>();
    private volatile long generation=1;
    private volatile long openEpoch=1,feedEpoch=1;
    private long sequence, lastSample=-1, threshold=DEFAULT_THRESHOLD_MS;
    private volatile boolean openConsent, feedConsent, foreground, overlay;
    private boolean destroyed;
    private volatile String url;
    private String navigationId, openedPost;
    private Card lastDetail;

    public CaptureSession(String library,Sink sink) {
        if (library==null || library.isEmpty() || sink==null) throw new IllegalArgumentException();
        this.library=library; this.sink=sink;
    }
    public synchronized long generation() { return generation; }
    public boolean isCurrent(Event e) {
        if(e==null || !library.equals(e.library) || !sessionId.equals(e.ownerSession))return false;
        // Accepted native events may finish persisting after navigation. The library writer
        // ticket remains mandatory to reject erasure/consent revocation races.
        if(e.kind==Kind.OPEN)return openConsent && e.consentEpoch==openEpoch;
        if(e.kind==Kind.FEED)return feedConsent && e.consentEpoch==feedEpoch;
        return e.generation==generation && e.consentEpoch==openEpoch && foreground && !overlay && openConsent &&
            ObservationRoutes.classify(url)==ObservationRoutes.Kind.POST_DETAIL;
    }
    public synchronized void setConsent(boolean opens,boolean feed,long thresholdMs) {
        if (thresholdMs!=1200 && thresholdMs!=3000 && thresholdMs!=5000)
            throw new IllegalArgumentException("Unsupported dwell threshold");
        if(destroyed)return;
        // A new setting applies only to future exposures. Revocation cancels outstanding writes.
        if(openConsent!=opens)openEpoch++;
        if(feedConsent!=feed)feedEpoch++;
        if (openConsent!=opens || feedConsent!=feed) invalidate();
        openConsent=opens; feedConsent=feed; threshold=thresholdMs;
    }
    public synchronized void setForeground(boolean visible) {
        if(destroyed)return;
        if(foreground && !visible)flushActive();
        if (foreground!=visible) invalidate();
        foreground=visible;
    }
    public synchronized void setOverlay(boolean covered) {
        if(destroyed)return;
        if(!overlay && covered)flushActive();
        if (overlay!=covered) invalidate();
        overlay=covered;
    }
    /** Call for native-confirmed successful navigation, never a page-supplied gesture flag. */
    public synchronized void navigation(String nextUrl,String nativeNavigationId,
                                         boolean deliberate,long wallTimeMs) {
        if(destroyed)return;
        if(!equal(nextUrl,url) || !equal(nativeNavigationId,navigationId))flushActive();
        if (!safeUrl(nextUrl)) { invalidate(); url=nextUrl; navigationId=null; openedPost=null; return; }
        boolean same = nextUrl.equals(url) && equal(nativeNavigationId,navigationId);
        if (!same) { invalidate(); lastDetail=null; openedPost=null; }
        url=nextUrl; navigationId=nativeNavigationId;
        String id=PostUrls.extractPostId(nextUrl);
        if (foreground && !overlay && openConsent && deliberate && id!=null &&
                nativeNavigationId!=null && !nativeNavigationId.isEmpty() &&
                !id.equals(openedPost) && wallTimeMs>0) {
            openedPost=id;
            sink.opened(new Event(Kind.OPEN,generation,library,new Card(nextUrl,null,null,null,1,"detail"),
                nativeNavigationId,null,"post_detail",wallTimeMs,wallTimeMs,0,0,openEpoch,sessionId));
        }
    }
    /** Enrichment may arrive later and never creates another OPEN_POST event. */
    public synchronized void detail(Card card,long wallTimeMs) {
        if (destroyed || !foreground || overlay || !openConsent || openedPost==null || !valid(card) ||
                !openedPost.equals(PostUrls.extractPostId(card.url)) ||
                ObservationRoutes.classify(url)!=ObservationRoutes.Kind.POST_DETAIL) return;
        if (lastDetail!=null && equal(lastDetail.body,card.body) && equal(lastDetail.author,card.author) &&
            equal(lastDetail.publishedAtMs,card.publishedAtMs) && lastDetail.completeText==card.completeText) return;
        lastDetail=card;
        sink.enriched(new Event(Kind.ENRICH,generation,library,card,navigationId,null,"post_detail",wallTimeMs,wallTimeMs,0,0,openEpoch,sessionId));
    }
    /** Sample only foreground top-level cards; discontinuities reset each continuous exposure. */
    public synchronized void sample(List<Card> cards,String source,long monotonicMs,long wallTimeMs) {
        if(destroyed)return;
        if (!foreground || overlay || !feedConsent ||
                ObservationRoutes.classify(url)!=ObservationRoutes.Kind.FEED ||
                !FeedCapturePolicy.supportedSource(source) || monotonicMs<0 || wallTimeMs<=0 ||
                cards==null || cards.size()>MAX_CARDS) { flushActive(); active.clear(); lastSample=-1; return; }
        if (lastSample>=0 && (monotonicMs<lastSample || monotonicMs-lastSample>MAX_SAMPLE_GAP_MS)) {
            flushActive(); active.clear();
        }
        lastSample=monotonicMs;
        final long sampleGeneration=generation;
        Set<String> seen=new HashSet<>();
        for (Card card:cards) {
            if (!valid(card) || card.readableFraction<0.60 || card.readableFraction>1 ||
                Double.isNaN(card.readableFraction) || card.run==null || card.run.isEmpty() || card.run.length()>80) continue;
            String id=PostUrls.extractPostId(card.url);
            if (!seen.add(id)) continue;
            Exposure e=active.get(id);
            if (e==null || !e.run.equals(card.run) || !e.source.equals(source)) {
                if(e!=null)publish(e,true);
                if(generation!=sampleGeneration)return;
                e=new Exposure(monotonicMs,threshold,sessionId+":"+(++sequence),card,source);
                active.put(id,e);
            }
            e.last=monotonicMs; e.card=card; e.capturedAt=wallTimeMs;
            long duration=Math.min(MAX_DWELL_MS,monotonicMs-e.start);
            if (duration>=e.threshold) {
                if (e.qualifiedAt==0) e.qualifiedAt=wallTimeMs;
                publish(e,false);
                if(generation!=sampleGeneration)return;
            }
        }
        for(Map.Entry<String,Exposure> entry:new ArrayList<>(active.entrySet()))
            if(!seen.contains(entry.getKey()))publish(entry.getValue(),true);
        active.keySet().retainAll(seen);
    }
    private void publish(Exposure e,boolean finalSample) {
        long duration=Math.min(MAX_DWELL_MS,e.last-e.start);
        if(destroyed || !feedConsent || e.qualifiedAt==0 || duration<=e.publishedDuration)return;
        if(!finalSample && e.publishedDuration>=0 && duration<MAX_DWELL_MS &&
                duration-e.publishedDuration<UPDATE_INTERVAL_MS)return;
        e.publishedDuration=duration;
        sink.feed(new Event(Kind.FEED,generation,library,e.card,null,e.id,e.source,
            e.qualifiedAt,e.capturedAt,duration,e.threshold,feedEpoch,sessionId));
    }
    private void flushActive() {
        for(Exposure e:new ArrayList<>(active.values()))publish(e,true);
    }
    /** Epochs never reset, including suspend/clear/resume. Call before an erasure barrier. */
    public synchronized void invalidate() { generation++; active.clear(); lastSample=-1; }
    public synchronized void destroy() { invalidate(); destroyed=true;openEpoch++;feedEpoch++; foreground=false; openConsent=feedConsent=false; }
    private static boolean safeUrl(String s) { return ObservationRoutes.classify(s)!=ObservationRoutes.Kind.DENIED; }
    private static boolean valid(Card c) {
        return c!=null && ObservationRoutes.classify(c.url)==ObservationRoutes.Kind.POST_DETAIL &&
            PostUrls.extractPostId(c.url)!=null && c.url.length()<=8192 &&
            (c.body==null || c.body.length()<=16000) && (c.author==null || c.author.length()<=100) &&
            (c.publishedAtMs==null || c.publishedAtMs>0);
    }
    private static boolean equal(Object a,Object b) { return a==null?b==null:a.equals(b); }
}

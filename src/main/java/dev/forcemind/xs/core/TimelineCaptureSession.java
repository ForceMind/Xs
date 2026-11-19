package dev.forcemind.xs.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Independent native admission for presented timeline cards. No dwell/open/favorite side effects. */
public final class TimelineCaptureSession {
    public static final int MAX_POSTS_PER_BATCH = 4096;
    public static final int MAX_BATCHES_PER_SESSION = 512;
    public static final String PROVENANCE = "offline_dom_fixture";
    public interface Sink { void observed(Event event); default void dropped(long count,String reason) {} }
    public static final class Event {
        public final String library,url,author,body,batchId,batchReason,source,provenance;
        public final Long publishedAtMs;
        public final long observedAtMs,sequence;
        public final SnapshotPolicy.Quality quality;
        private final String owner;
        private final Revocation revocation;
        private Event(TimelineCaptureSession session,CaptureSession.Card card,long wall,long sequence) {
            library=session.library;url=PostUrls.canonicalLink(card.url);author=card.author;body=card.body;
            publishedAtMs=card.publishedAtMs;observedAtMs=wall;this.sequence=sequence;
            batchId=session.batchId;batchReason=session.batchReason;source=session.source;provenance=PROVENANCE;
            quality=body==null || body.isEmpty()?SnapshotPolicy.Quality.LINK_ONLY:SnapshotPolicy.Quality.PARTIAL;
            owner=session.owner;revocation=session.revocation;
        }
    }
    /** One atomic publication avoids mixed consent/epoch reads without acquiring the capture monitor. */
    private static final class Revocation {
        final long epoch;final boolean allowed;
        Revocation(long epoch,boolean allowed){this.epoch=epoch;this.allowed=allowed;}
    }
    private static final class Seen {
        final long sequence;String author,body;Long published;
        Seen(long sequence,CaptureSession.Card card){this.sequence=sequence;update(card);}
        void update(CaptureSession.Card card){author=card.author;body=card.body;published=card.publishedAtMs;}
        boolean equal(CaptureSession.Card card){return same(author,card.author)&&same(body,card.body)&&same(published,card.publishedAtMs);}
    }
    private final String library,owner=UUID.randomUUID().toString();
    private final Map<String,Seen> seen=new HashMap<>();
    private Sink sink;
    private boolean consent,foreground,overlay,destroyed;
    private volatile Revocation revocation=new Revocation(1,false);
    private long epoch=1,sequence,batches,dropped,batchStartedElapsed=-1;
    private String url,navigationId,context,source="unknown",batchId,batchReason;
    public TimelineCaptureSession(String library){
        if(library==null || library.isEmpty())throw new IllegalArgumentException();this.library=library;
    }
    public synchronized void setSink(Sink sink){if(this.sink!=sink){epoch++;this.sink=sink;seen.clear();publishRevocation();}}
    public synchronized void setConsent(boolean value){if(destroyed)return;if(consent!=value){epoch++;startBatch("native_session");consent=value;publishRevocation();}}
    public synchronized void setForeground(boolean value){foreground=value;}
    public synchronized void setOverlay(boolean value){overlay=value;}
    /** Immutable admissions survive ordinary navigation; revocation/clear/account invalidation never revives. */
    public boolean isCurrent(Event event){
        Revocation current=revocation;
        return event!=null && current.allowed && event.revocation==current &&
            owner.equals(event.owner) && library.equals(event.library);
    }
    private void publishRevocation(){revocation=new Revocation(epoch,consent&&!destroyed);}
    public synchronized void navigation(String nextUrl,String nativeNavigationId){
        if(destroyed)return;
        if(!same(url,nextUrl)||!same(navigationId,nativeNavigationId)){
            url=nextUrl;navigationId=nativeNavigationId;startBatch("native_navigation");
        }
    }
    /** Only native verified tab/context changes can label a stream switch. */
    public synchronized void setFeedContext(String verifiedNativeContext){
        if(destroyed)return;
        if(verifiedNativeContext!=null && !"home_for_you".equals(verifiedNativeContext)&&
            !"home_following".equals(verifiedNativeContext))throw new IllegalArgumentException();
        if(!same(context,verifiedNativeContext)){context=verifiedNativeContext;startBatch("native_stream_switch");}
    }
    /** A native reload callback, not a page message, issues this batch identity. */
    public synchronized void refreshed(){if(!destroyed)startBatch("native_refresh");}
    /** A bounded adapter may report structural replacement only as uncertain provenance. */
    public synchronized void suspectedRefresh(){if(!destroyed)startBatch("suspected_refresh");}
    private void startBatch(String reason){
        seen.clear();sequence=0;batchId=null;batchReason=reason;batchStartedElapsed=-1;
        if(ObservationRoutes.classify(url)!=ObservationRoutes.Kind.FEED || batches>=MAX_BATCHES_PER_SESSION)return;
        String verified=ObservationRoutes.feedSource(url,context);source=verified==null?"unknown":verified;
        batchId=owner+":"+(++batches);
    }
    public synchronized void sample(List<CaptureSession.Card> cards,long wallTimeMs){sample(cards,wallTimeMs,wallTimeMs);}
    public synchronized void sample(List<CaptureSession.Card> cards,long wallTimeMs,long monotonicMs){
        if(destroyed||!consent||!foreground||overlay||sink==null||wallTimeMs<=0||
            ObservationRoutes.classify(url)!=ObservationRoutes.Kind.FEED||cards==null||cards.size()>CaptureSession.MAX_CARDS||monotonicMs<0)return;
        if(batchStartedElapsed>=0&&(monotonicMs<batchStartedElapsed||monotonicMs-batchStartedElapsed>=3600000L))startBatch("native_session");
        if(batchId==null){drop(cards.size(),"NATIVE_BATCH_CAP");return;}
        if(batchStartedElapsed<0)batchStartedElapsed=monotonicMs;
        Set<String> ids=new HashSet<>();final long acceptedEpoch=epoch;
        for(CaptureSession.Card card:cards){
            if(!valid(card))continue;
            String id=PostUrls.extractPostId(card.url);if(!ids.add(id))continue;
            Seen previous=seen.get(id);
            if(previous==null){
                if(seen.size()>=MAX_POSTS_PER_BATCH){drop(1,"NATIVE_CARD_CAP");continue;}
                previous=new Seen(++sequence,card);seen.put(id,previous);
            }else {if(previous.equal(card))continue;previous.update(card);}
            sink.observed(new Event(this,card,wallTimeMs,previous.sequence));
            if(epoch!=acceptedEpoch||!consent||destroyed)return;
        }
    }
    private void drop(long count,String reason){dropped+=count;if(sink!=null&&count>0)sink.dropped(count,reason);}
    public synchronized long dropped(){return dropped;}
    /** Call on clear or account-context invalidation before the writer barrier. */
    public synchronized void invalidate(){epoch++;startBatch("native_navigation");publishRevocation();}
    public synchronized void destroy(){epoch++;destroyed=true;consent=foreground=false;seen.clear();sink=null;publishRevocation();}
    private static boolean valid(CaptureSession.Card card){return card!=null&&
        ObservationRoutes.classify(card.url)==ObservationRoutes.Kind.POST_DETAIL&&PostUrls.extractPostId(card.url)!=null&&
        card.readableFraction>0&&card.readableFraction<=1&&!Double.isNaN(card.readableFraction)&&
        (card.author==null||card.author.length()<=100)&&(card.body==null||card.body.length()<=2048)&&
        (card.publishedAtMs==null||card.publishedAtMs>0);}
    private static boolean same(Object a,Object b){return a==null?b==null:a.equals(b);}
}

package dev.forcemind.xs.core;

/**
 * INACTIVE until W0 real-login/device gate passes and a vetted native listener
 * exists. This purely local policy admits typed observations; it never writes
 * data, injects web scripts, reads cookies, or exposes native archive operations.
 * Source-origin trust does not authenticate same-origin JavaScript.
 */
public final class ObservationGate {
    public static final int VERSION = 1;
    public static final int MAX_BYTES = 65536;
    private static final int BURST = 40;
    private static final long REFILL_PERIOD_MS = 50; // 20 messages/second

    public enum Type { POST_OPENED, FEED_VISIBLE }
    public enum Verdict { ACCEPT, DISARMED, STALE, BAD_ORIGIN, BACKGROUND,
        ROUTE_DENIED, BAD_MESSAGE, NOT_DELIBERATE, WRONG_POST, REPLAY, THROTTLED }

    /** Typed fields must be parsed from bounded JSON by a future native adapter. */
    public static final class Message {
        public final Type type;
        public final long epoch, sequence;
        public final int encodedBytes;
        public final String id, postUrl, author, body, source;
        public final double visibleFraction;
        public final long dwellMs;
        public Message(Type type, long epoch, long sequence, int bytes, String id,
                       String postUrl, String author, String body, String source,
                       double visibleFraction, long dwellMs) {
            this.type=type; this.epoch=epoch; this.sequence=sequence;
            this.encodedBytes=bytes; this.id=id; this.postUrl=postUrl;
            this.author=author; this.body=body; this.source=source;
            this.visibleFraction=visibleFraction; this.dwellMs=dwellMs;
        }
    }

    /** These fields must be decided and verified by native code, NEVER the web page. */
    public static final class NativeState {
        public final String localScope, origin, topLevelUrl;
        public final boolean consent, foreground, mainFrame, deliberateNavigation;
        public final long epoch, monotonicMs;
        public NativeState(String scope, boolean consent, boolean foreground,
                           boolean mainFrame, boolean deliberate, String origin,
                           String topLevel, long epoch, long monotonicMs) {
            localScope=scope; this.consent=consent; this.foreground=foreground;
            this.mainFrame=mainFrame; deliberateNavigation=deliberate;
            this.origin=origin; topLevelUrl=topLevel;
            this.epoch=epoch; this.monotonicMs=monotonicMs;
        }
    }

    private long activeEpoch=-1, lastSequence=0, bucketAt=-1;
    private int tokens=BURST;

    public synchronized void changeDocument(long nextEpoch) {
        if (nextEpoch < 0 || (activeEpoch >= 0 && nextEpoch <= activeEpoch))
            throw new IllegalArgumentException("Native epoch must increase");
        activeEpoch=nextEpoch;
        lastSequence=0;
        bucketAt=-1;
        tokens=BURST;
    }
    public synchronized void suspend() {
        activeEpoch=-1; lastSequence=0; bucketAt=-1; tokens=0;
    }

    public synchronized Verdict admit(NativeState n, Message m) {
        if (activeEpoch<0 || n==null || m==null || !n.consent ||
            n.localScope==null || n.localScope.trim().isEmpty()) return Verdict.DISARMED;
        if (n.epoch!=activeEpoch || m.epoch!=activeEpoch) return Verdict.STALE;
        if (!n.mainFrame || !ObservationRoutes.trustedOrigin(n.origin))
            return Verdict.BAD_ORIGIN;
        if (!n.foreground) return Verdict.BACKGROUND;
        ObservationRoutes.Kind kind=ObservationRoutes.classify(n.topLevelUrl);
        if (kind==ObservationRoutes.Kind.DENIED) return Verdict.ROUTE_DENIED;
        if (m.type==null || m.id==null || !m.id.matches("[0-9]{1,20}") ||
            m.postUrl==null || !m.id.equals(PostUrls.extractPostId(m.postUrl)) ||
            m.encodedBytes<1 || m.encodedBytes>MAX_BYTES || m.sequence<1 ||
            (m.author!=null && m.author.length()>100) ||
            (m.body!=null && m.body.length()>16000)) return Verdict.BAD_MESSAGE;
        if (m.sequence<=lastSequence) return Verdict.REPLAY;
        if (m.type==Type.POST_OPENED) {
            if (kind!=ObservationRoutes.Kind.POST_DETAIL) return Verdict.ROUTE_DENIED;
            if (!n.deliberateNavigation) return Verdict.NOT_DELIBERATE;
            if (!m.id.equals(PostUrls.extractPostId(n.topLevelUrl)))
                return Verdict.WRONG_POST;
        } else if (m.type==Type.FEED_VISIBLE) {
            if (kind!=ObservationRoutes.Kind.FEED) return Verdict.ROUTE_DENIED;
            if (!FeedCapturePolicy.confirmedVisible(true, m.visibleFraction,
                 m.dwellMs, m.source, 1)) return Verdict.BAD_MESSAGE;
        } else return Verdict.BAD_MESSAGE;
        if (n.monotonicMs<0) return Verdict.BAD_MESSAGE;
        if (bucketAt<0) bucketAt=n.monotonicMs;
        if (n.monotonicMs<bucketAt) return Verdict.BAD_MESSAGE;
        long replenished=(n.monotonicMs-bucketAt)/REFILL_PERIOD_MS;
        if (replenished>0) {
            tokens=(int)Math.min(BURST,(long)tokens+replenished);
            bucketAt+=replenished*REFILL_PERIOD_MS;
        }
        if (tokens==0) return Verdict.THROTTLED;
        tokens--;
        lastSequence=m.sequence;
        return Verdict.ACCEPT;
    }
}

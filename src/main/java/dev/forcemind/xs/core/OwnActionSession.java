package dev.forcemind.xs.core;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Native-owned confirmation boundary. No page payload, author label, menu click or
 * scraped username is accepted here. Live adapters have not been validated and
 * cannot issue operations. The enabled factory is explicitly for offline fixtures.
 */
public final class OwnActionSession {
    public enum Type { ORIGINAL, REPLY, QUOTE, REPOST }
    private static final int MAX_OPERATIONS = 256;
    private final String libraryId, epoch;
    private final boolean fixture;
    private final Map<Operation, Boolean> pending = new IdentityHashMap<>();
    private int issued;
    private boolean current = true;

    private OwnActionSession(String libraryId, String epoch, boolean fixture) {
        if (!bounded(libraryId, 256) || !bounded(epoch, 128)) {
            throw new IllegalArgumentException("Native library and epoch required");
        }
        this.libraryId = libraryId;
        this.epoch = epoch + ":" + UUID.randomUUID();
        this.fixture = fixture;
    }

    public static OwnActionSession liveDisabled(String libraryId, String epoch) {
        return new OwnActionSession(libraryId, epoch, false);
    }

    /** Never wire this factory to X-page observations; fixture actors use fixture: IDs. */
    public static OwnActionSession forOfflineFixture(String libraryId, String nativeEpoch) {
        return new OwnActionSession(libraryId, nativeEpoch, true);
    }

    public static final class Operation {
        private final OwnActionSession owner;
        private final String key, postId, actorId, targetKey;
        private final Type type;
        private Operation(OwnActionSession owner, String key, String postId,
                          Type type, String actorId, String targetKey) {
            this.owner=owner; this.key=key; this.postId=postId; this.type=type;
            this.actorId=actorId; this.targetKey=targetKey;
        }
    }

    /** Immutable proof issued only after a matching native fixture result. */
    public static final class Admission {
        public final String libraryId, actionKey, eventKey, postId, relatedPostId, actorId;
        public final Type type;
        public final Long actionAtMs;
        public final long observedAtMs;
        public final boolean reversal;
        private final OwnActionSession owner;
        private Admission(Operation operation, String resultPostId, Long actionAtMs, long observedAtMs) {
            owner=operation.owner;
            libraryId=owner.libraryId;
            actionKey=operation.targetKey == null ? operation.key : operation.targetKey;
            eventKey=operation.key;
            postId=resultPostId;
            relatedPostId=(operation.type == Type.REPLY || operation.type == Type.QUOTE) ? operation.postId : null;
            actorId=operation.actorId; type=operation.type;
            reversal=operation.targetKey != null;
            this.actionAtMs=actionAtMs; this.observedAtMs=observedAtMs;
        }
        /** Recheck immediately before persistence, after any queued work. */
        public boolean isCurrent() { synchronized (owner) { return owner.current; } }
        /** Serializes the short native transaction with invalidation, including its commit. */
        public boolean persistIfCurrent(BooleanSupplier transaction) {
            if (transaction == null) throw new IllegalArgumentException("Transaction required");
            synchronized (owner) { return owner.current && transaction.getAsBoolean(); }
        }
    }

    /** ORIGINAL has no parent URL. REPLY/QUOTE carry the parent; success supplies the new post URL. */
    public synchronized Operation begin(Type type, String postUrl, String nativeActorId) {
        return beginInternal(type, postUrl, nativeActorId, null);
    }

    public synchronized Operation beginReversal(Admission confirmed) {
        if (confirmed == null || confirmed.owner != this || confirmed.reversal ||
                confirmed.type != Type.REPOST || !confirmed.isCurrent()) return null;
        return beginInternal(Type.REPOST, "https://x.com/i/status/" + confirmed.postId,
            confirmed.actorId, confirmed.actionKey);
    }

    private Operation beginInternal(Type type, String postUrl, String actor, String target) {
        String id=PostUrls.extractPostId(postUrl);
        if (!current || !fixture || type == null ||
                (type == Type.ORIGINAL ? postUrl != null : id == null) || !bounded(actor,128) ||
                !actor.startsWith("fixture:") || actor.length() == 8 ||
                pending.size() >= 32 || issued >= MAX_OPERATIONS) return null;
        Operation operation=new Operation(this, epoch + ":" + (++issued), id, type, actor,target);
        pending.put(operation,Boolean.TRUE);
        return operation;
    }

    /**
     * Called by the native operation result owner, never from a JavaScript event.
     * Failure/cancel/mismatched identity consume the operation without admission.
     * Unknown real action time stays null; observed time is never substituted.
     */
    public synchronized Admission confirm(Operation operation, String nativeResultActorId,
            String nativeResultPostUrl, Type nativeResultType, boolean succeeded,
            Long realActionAtMs, long nativeObservedAtMs) {
        if (operation == null || operation.owner != this || pending.remove(operation) == null) return null;
        if (!current || !fixture || !succeeded || nativeObservedAtMs <= 0 ||
                (realActionAtMs != null && (realActionAtMs <= 0 || realActionAtMs > nativeObservedAtMs)) ||
                !operation.actorId.equals(nativeResultActorId) || operation.type != nativeResultType ||
                PostUrls.extractPostId(nativeResultPostUrl) == null) return null;
        String resultId=PostUrls.extractPostId(nativeResultPostUrl);
        if (operation.type == Type.REPOST && !operation.postId.equals(resultId)) return null;
        if ((operation.type == Type.REPLY || operation.type == Type.QUOTE) && operation.postId.equals(resultId)) return null;
        return new Admission(operation,resultId,realActionAtMs,nativeObservedAtMs);
    }

    /** Background/account switch/library erase retires queued confirmations. */
    public synchronized void invalidate() { current=false; pending.clear(); }

    private static boolean bounded(String value,int max) {
        return value != null && !value.trim().isEmpty() && value.length() <= max &&
            value.indexOf('\u0000') < 0;
    }
}

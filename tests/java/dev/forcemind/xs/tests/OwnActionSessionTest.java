package dev.forcemind.xs.tests;

import dev.forcemind.xs.core.OwnActionSession;
import dev.forcemind.xs.core.OwnActionSession.Admission;
import dev.forcemind.xs.core.OwnActionSession.Operation;
import dev.forcemind.xs.core.OwnActionSession.Type;
import dev.forcemind.xs.core.ArchiveSearch;

/** Native operation/result admission, deliberately independent of page parsing. */
public final class OwnActionSessionTest {
    private static int checks;
    private static final String URL="https://x.com/somebody/status/75";
    public static void main(String[] args) {
        OwnActionSession live=OwnActionSession.liveDisabled("local:A","native-epoch");
        yes(live.begin(Type.REPOST,URL,"fixture:me") == null);
        yes(live.begin(Type.ORIGINAL,null,"fixture:me") == null);
        OwnActionSession session=OwnActionSession.forOfflineFixture("local:A","native-epoch");
        yes(session.begin(Type.REPOST,URL,"scraped-author") == null);
        yes(session.begin(Type.REPOST,"https://evil.test/status/75","fixture:me") == null);
        yes(session.begin(Type.ORIGINAL,URL,"fixture:me") == null);
        yes(session.begin(Type.REPLY,null,"fixture:me") == null);
        Operation failed=session.begin(Type.REPOST,URL,"fixture:me");
        yes(session.confirm(failed,"fixture:me",URL,Type.REPOST,false,null,100) == null);
        yes(session.confirm(failed,"fixture:me",URL,Type.REPOST,true,null,100) == null);
        Operation otherActor=session.begin(Type.REPOST,URL,"fixture:me");
        yes(session.confirm(otherActor,"fixture:other",URL,Type.REPOST,true,null,100) == null);
        Operation wrongPost=session.begin(Type.REPOST,URL,"fixture:me");
        yes(session.confirm(wrongPost,"fixture:me","https://x.com/i/status/76",Type.REPOST,true,null,100) == null);
        Operation wrongType=session.begin(Type.REPOST,URL,"fixture:me");
        yes(session.confirm(wrongType,"fixture:me",URL,Type.QUOTE,true,null,100) == null);
        Operation future=session.begin(Type.REPOST,URL,"fixture:me");
        yes(session.confirm(future,"fixture:me",URL,Type.REPOST,true,101L,100) == null);
        Operation operation=session.begin(Type.REPOST,URL,"fixture:me");
        Admission admitted=session.confirm(operation,"fixture:me",URL,Type.REPOST,true,null,100);
        yes(admitted != null && admitted.isCurrent());
        yes(admitted.type == Type.REPOST && admitted.actionAtMs == null && admitted.observedAtMs == 100);
        yes("local:A".equals(admitted.libraryId) && "75".equals(admitted.postId));
        yes(admitted.relatedPostId == null && !admitted.reversal);
        yes(session.confirm(operation,"fixture:me",URL,Type.REPOST,true,null,101) == null);
        Operation reversal=session.beginReversal(admitted);
        Admission reversed=session.confirm(reversal,"fixture:me",URL,Type.REPOST,true,150L,151);
        yes(reversed != null && reversed.reversal && reversed.actionKey.equals(admitted.actionKey));
        yes(!reversed.eventKey.equals(admitted.eventKey));
        yes(session.beginReversal(reversed) == null);
        OwnActionSession sameEpoch=OwnActionSession.forOfflineFixture("local:A","native-epoch");
        yes(sameEpoch.beginReversal(admitted) == null);
        Admission separate=success(sameEpoch,Type.REPOST,URL,URL,200);
        yes(separate != null && !admitted.actionKey.equals(separate.actionKey));
        Admission original=success(session,Type.ORIGINAL,null,"https://x.com/i/status/81",200);
        Admission reply=success(session,Type.REPLY,URL,"https://x.com/i/status/82",200);
        Admission quote=success(session,Type.QUOTE,URL,"https://x.com/i/status/83",200);
        yes(original != null && original.relatedPostId == null && "81".equals(original.postId));
        yes(reply != null && "75".equals(reply.relatedPostId) && "82".equals(reply.postId));
        yes(quote != null && "75".equals(quote.relatedPostId) && "83".equals(quote.postId));
        yes(success(session,Type.REPLY,URL,URL,200) == null);
        yes(success(session,Type.QUOTE,URL,URL,200) == null);
        Operation pending=session.begin(Type.REPOST,URL,"fixture:me");
        session.invalidate();
        yes(!admitted.isCurrent() && !original.isCurrent());
        yes(session.confirm(pending,"fixture:me",URL,Type.REPOST,true,null,200) == null);
        yes(session.begin(Type.REPOST,URL,"fixture:me") == null);
        yes(session.beginReversal(admitted) == null);
        OwnActionSession full=OwnActionSession.forOfflineFixture("local:A","budget");
        for(int i=0;i<32;i++) yes(full.begin(Type.REPOST,URL,"fixture:me") != null);
        yes(full.begin(Type.REPOST,URL,"fixture:me") == null);
        ArchiveSearch.Filter filter=new ArchiveSearch.Filter();
        filter.ownOnly=true; filter.actorId="fixture:me"; filter.actionState="REVERSED";
        filter.actionType="REPOST"; filter.actionSource="native_fixture";
        filter.dateField=ArchiveSearch.DateField.ACTION; filter.fromMs=100L;
        ArchiveSearch.Query query=ArchiveSearch.build("local:A",filter);
        yes(query.sql.contains("matched_action.post_id IS NOT NULL"));
        yes(query.sql.contains("a.actor_id=?") && query.sql.contains("a.state=?"));
        yes(query.sql.contains("a.action_at_ms>=CAST(? AS INTEGER)"));
        filter.actionState="ACTIVE' OR 1=1";
        try { ArchiveSearch.build("local:A",filter); throw new AssertionError("state injection"); }
        catch(IllegalArgumentException expected) { checks++; }
        atomicCommitBoundary();
        System.out.println("PASS " + checks + " native own-action admission assertions (live disabled)");
    }
    private static void atomicCommitBoundary() {
        OwnActionSession session=OwnActionSession.forOfflineFixture("local:A","atomic");
        Admission admitted=success(session,Type.REPOST,URL,URL,200);
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch finish=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean committed=new java.util.concurrent.atomic.AtomicBoolean();
        Thread writer=new Thread(() -> committed.set(admitted.persistIfCurrent(() -> {
            entered.countDown();
            try { return finish.await(2,java.util.concurrent.TimeUnit.SECONDS); }
            catch(InterruptedException interrupted) { throw new AssertionError(interrupted); }
        })));
        Thread invalidator=new Thread(session::invalidate);
        try {
            writer.start(); yes(entered.await(2,java.util.concurrent.TimeUnit.SECONDS)); invalidator.start();
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while(invalidator.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield();
            yes(invalidator.getState() == Thread.State.BLOCKED);
            finish.countDown(); writer.join(2000); invalidator.join(2000);
            yes(!writer.isAlive() && !invalidator.isAlive() && committed.get() && !admitted.isCurrent());
            java.util.concurrent.atomic.AtomicBoolean called=new java.util.concurrent.atomic.AtomicBoolean();
            yes(!admitted.persistIfCurrent(() -> { called.set(true); return true; }) && !called.get());
        } catch(InterruptedException interrupted) { throw new AssertionError(interrupted); }
        finally { finish.countDown(); }
    }
    private static Admission success(OwnActionSession session,Type type,String parent,String result,long when) {
        return session.confirm(session.begin(type,parent,"fixture:me"),"fixture:me",result,type,true,when,when);
    }
    private static void yes(boolean value) { checks++; if (!value) throw new AssertionError("check "+checks); }
}

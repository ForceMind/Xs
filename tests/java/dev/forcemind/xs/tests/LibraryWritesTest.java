package dev.forcemind.xs.tests;

import dev.forcemind.xs.android.XsLibraryWrites;
import dev.forcemind.xs.core.SerialWriteCoordinator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** JVM regressions for writer lifetime, bounded admission and stale cleanup. */
public final class LibraryWritesTest {
    private static int checks;
    private static final long WAIT_SECONDS=10;

    public static void main(String[] args)throws Exception {
        idleLibrariesCanBeReclaimedWithoutRevivingTickets();
        unpublishedReservationCanBeReleased();
        busyWriterSurvivesIdleReclamation();
        allBusyWritersRejectAnotherLibrary();
        throwingStaleCleanupStillFinishesEveryDiscardedTask();
        durableDeletionNeverReopensTheOldNamespace();
        System.out.println("PASS "+checks+" library writer lifetime/concurrency assertions");
    }

    private static void idleLibrariesCanBeReclaimedWithoutRevivingTickets()throws Exception {
        List<XsLibraryWrites.Ticket> tickets=new ArrayList<>();
        try {
            for(int i=0;i<64;i++)tickets.add(XsLibraryWrites.ticket("idle:"+i));
            for(int i=0;i<64;i++)yes(XsLibraryWrites.isCurrent(tickets.get(i))==(i>=32),"Only newest 32 idle writers remain");
            AtomicInteger staleRuns=new AtomicInteger();
            yes(!XsLibraryWrites.submit(tickets.get(0),staleRuns::incrementAndGet),"Retired ticket cannot submit");
            XsLibraryWrites.Ticket recreated=XsLibraryWrites.ticket("idle:0");tickets.add(recreated);
            yes(XsLibraryWrites.isCurrent(recreated),"Recreated writer admits its new ticket");
            yes(!XsLibraryWrites.isCurrent(tickets.get(0)),"Old ticket never revives after scope recreation");
            yes(!XsLibraryWrites.submit(tickets.get(0),staleRuns::incrementAndGet),"Old ticket cannot target recreated writer");
            yes(staleRuns.get()==0,"Retired operations did not run");
        }finally{retire(tickets);}
    }

    private static void unpublishedReservationCanBeReleased()throws Exception {
        List<XsLibraryWrites.Ticket> tickets=new ArrayList<>();
        try {
            XsLibraryWrites.Ticket old=XsLibraryWrites.ticket("reservation");tickets.add(old);
            yes(XsLibraryWrites.releaseUnused(old),"Unused reservation released");
            yes(!XsLibraryWrites.isCurrent(old),"Released reservation is invalid");
            yes(!XsLibraryWrites.releaseUnused(old),"Release is identity scoped");
            XsLibraryWrites.Ticket replacement=XsLibraryWrites.ticket("reservation");tickets.add(replacement);
            yes(XsLibraryWrites.isCurrent(replacement),"Fresh reservation is usable");
            yes(!XsLibraryWrites.releaseUnused(old),"Old reservation cannot release its replacement");
            yes(XsLibraryWrites.isCurrent(replacement),"Replacement survived stale release");
            XsLibraryWrites.invalidate("reservation");
            yes(!XsLibraryWrites.isCurrent(replacement),"Explicit invalidation revokes current ticket");
            XsLibraryWrites.Ticket renewed=XsLibraryWrites.ticket("reservation");tickets.add(renewed);
            yes(XsLibraryWrites.isCurrent(renewed),"Existing writer issues a renewed epoch");
            yes(!XsLibraryWrites.isCurrent(replacement),"Renewed epoch does not revive previous ticket");
            yes(!XsLibraryWrites.releaseUnused(replacement),"Stale epoch cannot release a renewed reservation");
            yes(XsLibraryWrites.isCurrent(renewed),"Renewed reservation survived stale epoch release");
            yes(XsLibraryWrites.releaseUnused(renewed),"Current renewed reservation can be released");
        }finally{retire(tickets);}
    }

    private static void busyWriterSurvivesIdleReclamation()throws Exception {
        List<XsLibraryWrites.Ticket> tickets=new ArrayList<>();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),done=new CountDownLatch(1);
        try {
            XsLibraryWrites.Ticket busy=XsLibraryWrites.ticket("one-busy");tickets.add(busy);
            yes(XsLibraryWrites.submit(busy,()->block(entered,release,done)),"Busy work accepted");
            await(entered,"Busy work started");
            yes(!XsLibraryWrites.releaseUnused(busy),"Running writer cannot be released");
            for(int i=0;i<64;i++)tickets.add(XsLibraryWrites.ticket("mixed-idle:"+i));
            yes(XsLibraryWrites.isCurrent(busy),"Idle reclamation preserves running writer");
            yes(!XsLibraryWrites.releaseUnused(busy),"Busy writer still cannot retire");
        }finally{release.countDown();try{await(done,"Busy work completed");}finally{retire(tickets);}}
    }

    private static void allBusyWritersRejectAnotherLibrary()throws Exception {
        List<XsLibraryWrites.Ticket> tickets=new ArrayList<>();
        CountDownLatch entered=new CountDownLatch(32),release=new CountDownLatch(1),done=new CountDownLatch(32);
        try {
            for(int i=0;i<32;i++) {
                XsLibraryWrites.Ticket ticket=XsLibraryWrites.ticket("all-busy:"+i);tickets.add(ticket);
                yes(XsLibraryWrites.submit(ticket,()->block(entered,release,done)),"Busy slot accepted");
            }
            await(entered,"All busy workers started");
            try {
                XsLibraryWrites.ticket("all-busy:overflow");
                throw new AssertionError("All 32 busy writers must reject another library");
            }catch(IllegalStateException expected) {
                yes(expected.getMessage()!=null&&expected.getMessage().contains("busy"),"Busy rejection is explicit");
            }
            for(XsLibraryWrites.Ticket ticket:tickets) {
                yes(XsLibraryWrites.isCurrent(ticket),"Capacity rejection preserved active writer");
                yes(!XsLibraryWrites.releaseUnused(ticket),"No busy writer was reclaimed");
            }
        }finally{release.countDown();try{await(done,"All busy workers completed");}finally{retire(tickets);}}
    }

    private static void throwingStaleCleanupStillFinishesEveryDiscardedTask()throws Exception {
        SerialWriteCoordinator writer=new SerialWriteCoordinator();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),runningDone=new CountDownLatch(1),erased=new CountDownLatch(1),reported=new CountDownLatch(1);
        AtomicInteger staleWrites=new AtomicInteger(),skipped=new AtomicInteger();
        AtomicReference<Throwable> failure=new AtomicReference<>();
        try {
            long old=writer.token();
            yes(writer.submit(old,()->{
                Thread.currentThread().setUncaughtExceptionHandler((thread,error)->{failure.set(error);reported.countDown();});
                block(entered,release,runningDone);
            }),"Coordinator blocking work accepted");
            await(entered,"Coordinator work started");
            yes(writer.submit(old,staleWrites::incrementAndGet,()->{skipped.incrementAndGet();throw new IllegalStateException("Synthetic cleanup failure");}),"Throwing stale cleanup queued");
            yes(writer.submit(old,staleWrites::incrementAndGet,skipped::incrementAndGet),"Later stale cleanup queued");
            yes(writer.barrier(erased::countDown),"Erasure barrier accepted");
            yes(!writer.isCurrent(old),"Barrier immediately revokes stale token");
            yes(!writer.retireIfIdle(),"Queued/running work prevents retirement");
            release.countDown();await(erased,"Erasure ran despite cleanup failure");await(runningDone,"Blocking work finished");
            yes(staleWrites.get()==0,"Drained stale writes never ran");
            yes(skipped.get()==2,"Every stale callback ran despite an earlier exception");
            await(reported,"Cleanup exception was surfaced after completing the barrier");
            yes(failure.get() instanceof IllegalStateException&&"Synthetic cleanup failure".equals(failure.get().getMessage()),"Original cleanup failure is preserved");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(WAIT_SECONDS);boolean retired=false;
            do {if(writer.retireIfIdle()){retired=true;break;}Thread.sleep(1);}while(System.nanoTime()<deadline);
            yes(retired,"All drained work finished accounting; idle writer retires");
            yes(!writer.isCurrent(writer.token()),"Retired coordinator stays closed");
        }finally{release.countDown();writer.close();}
    }

    private static void durableDeletionNeverReopensTheOldNamespace()throws Exception {
        String scope="delete:closed";XsLibraryWrites.Ticket old=XsLibraryWrites.ticket(scope);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),runningDone=new CountDownLatch(1),erased=new CountDownLatch(1);
        AtomicInteger stale=new AtomicInteger(),skipped=new AtomicInteger();
        try {
            yes(XsLibraryWrites.submit(old,()->block(entered,release,runningDone)),"Pre-deletion operation accepted");await(entered,"Pre-deletion operation started");
            yes(XsLibraryWrites.submit(old,stale::incrementAndGet,skipped::incrementAndGet),"Stale mutation was queued");
            yes(XsLibraryWrites.deleteAndSubmit(scope,erased::countDown),"Deletion barrier accepted");
            yes(XsLibraryWrites.isDeleted(scope),"Deletion closes the namespace immediately");
            yes(!XsLibraryWrites.isCurrent(old),"Deletion invalidates the old ticket");
            yes(!XsLibraryWrites.submit(old,stale::incrementAndGet),"Deleted ticket cannot submit");
            yes(!XsLibraryWrites.submit(scope,stale::incrementAndGet),"Stale UI callback is rejected without throwing");
            try{XsLibraryWrites.ticket(scope);throw new AssertionError("Deleted namespace reopened");}catch(IllegalStateException expected){yes(true,"New ticket cannot reopen deleted namespace");}
            yes(!XsLibraryWrites.invalidateAndSubmit(scope,stale::incrementAndGet),"Ordinary erasure cannot reopen deleted namespace");
            release.countDown();await(runningDone,"Old operation ended before deletion");await(erased,"Mandatory deletion executed");
            yes(stale.get()==0&&skipped.get()==1,"Queued stale writes cannot revive deleted data");
            CountDownLatch retry=new CountDownLatch(1);yes(XsLibraryWrites.submitDeletion(scope,retry::countDown),"Only explicit deletion retry is permitted");await(retry,"Retry cleanup completed");
            String loaded="delete:persistent";XsLibraryWrites.Ticket before=XsLibraryWrites.ticket(loaded);
            XsLibraryWrites.installDeletedLibraries(java.util.Collections.singleton(loaded));
            yes(!XsLibraryWrites.isCurrent(before)&&XsLibraryWrites.isDeleted(loaded),"Loaded native tombstone revokes an existing ticket");
            yes(XsLibraryWrites.releaseUnused(before),"Deleted idle writer can retire without reopening it");
            XsLibraryWrites.installDeletedLibraries(java.util.Collections.singleton(loaded));
            try{XsLibraryWrites.ticket(loaded);throw new AssertionError("Reloaded tombstone lost");}catch(IllegalStateException expected){yes(true,"Tombstones are additive across initialization");}
            yes(!XsLibraryWrites.submitDeletion("never-deleted",stale::incrementAndGet),"Retry cannot delete an arbitrary live namespace");
        }finally{
            release.countDown();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while(!XsLibraryWrites.releaseUnused(old)&&System.nanoTime()<until)Thread.sleep(1);
        }
    }

    private static void block(CountDownLatch entered,CountDownLatch release,CountDownLatch done) {
        entered.countDown();
        try {if(!release.await(WAIT_SECONDS,TimeUnit.SECONDS))throw new AssertionError("Test failed to release writer");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
        finally{done.countDown();}
    }
    private static void await(CountDownLatch latch,String message)throws InterruptedException {
        yes(latch.await(WAIT_SECONDS,TimeUnit.SECONDS),message);
    }
    /** Release even invalidated tickets so executor threads cannot keep this JVM alive. */
    private static void retire(List<XsLibraryWrites.Ticket> tickets)throws InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        do {
            boolean current=false;
            for(XsLibraryWrites.Ticket ticket:tickets) {
                if(XsLibraryWrites.releaseUnused(ticket))continue;
                if(XsLibraryWrites.isCurrent(ticket))current=true;
            }
            if(!current)return;
            Thread.sleep(1);
        }while(System.nanoTime()<deadline);
        throw new AssertionError("Writer cleanup did not become idle");
    }
    private static void yes(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
}

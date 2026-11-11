package dev.forcemind.xs.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One bounded mutation queue; revocation is checked when each serialized operation starts. */
public final class SerialWriteCoordinator implements AutoCloseable {
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<Runnable>(128), r -> new Thread(r,"xs-library-writer"),
        new ThreadPoolExecutor.AbortPolicy());
    private long epoch=1;
    private int pending;
    private boolean closed;
    private final class Work implements Runnable {
        final long token; final Runnable write,skipped; final boolean mandatory;
        private boolean finished;
        Work(long token,Runnable write,Runnable skipped){this(token,write,skipped,false);}
        Work(long token,Runnable write,Runnable skipped,boolean mandatory){this.token=token;this.write=write;this.skipped=skipped;this.mandatory=mandatory;}
        public void run(){
            boolean accepted;
            synchronized(SerialWriteCoordinator.this){accepted=mandatory||isCurrent(token);}
            // A barrier enqueued during this operation executes immediately after it.
            // Never hold the enqueue lock across database/file IO or host callbacks.
            try { if(accepted)write.run();else skipped.run(); } finally { finish(); }
        }
        void discardQueued() {
            try { if(mandatory)write.run();else skipped.run(); } finally { finish(); }
        }
        private void finish() {
            synchronized(SerialWriteCoordinator.this) {
                if(!finished) { finished=true;pending--; }
            }
        }
    }
    public synchronized long token() { return epoch; }
    public synchronized boolean isCurrent(long token) { return !closed && token==epoch; }
    public boolean submit(long token,Runnable write) { return submit(token,write,() -> {}); }
    public synchronized boolean submit(long token,Runnable write,Runnable skipped) {
        if(write==null||skipped==null)throw new IllegalArgumentException();
        if(!isCurrent(token))return false;
        pending++;
        try{executor.execute(new Work(token,write,skipped));return true;}
        catch(java.util.concurrent.RejectedExecutionException full){pending--;return false;}
    }
    /** Invalidate at enqueue time, then clean stale work and erase before future writes. */
    public synchronized boolean barrier(Runnable erase) {
        if(closed||erase==null)return false;
        epoch++;
        List<Runnable> stale=new ArrayList<>();executor.getQueue().drainTo(stale);
        Runnable discard=() -> {
            Throwable failure=null;
            for(Runnable task:stale) {
                try { if(task instanceof SerialWriteCoordinator.Work)((Work)task).discardQueued(); }
                catch(RuntimeException|Error problem) { if(failure==null)failure=problem;else if(problem!=failure)failure.addSuppressed(problem); }
            }
            if(failure instanceof Error)throw (Error)failure;
            if(failure!=null)throw (RuntimeException)failure;
        };
        // Queue was drained atomically with submissions; the sole worker preserves erasure order.
        pending++;
        executor.execute(new Work(epoch,() -> {try{discard.run();}finally{erase.run();}},discard,true));
        return true;
    }
    public synchronized long invalidate() { epoch++; return epoch; }
    /** Retire only truly idle queues; issued tickets can never become current again. */
    public synchronized boolean retireIfIdle() {
        if(pending!=0)return false;
        closed=true;epoch++;executor.shutdown();return true;
    }
    @Override public synchronized void close() { closed=true;epoch++;executor.shutdown(); }
}

package dev.forcemind.xs.android;

import dev.forcemind.xs.core.SerialWriteCoordinator;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/** Application-wide bounded queues. All native mutation entry points share library epochs. */
public final class XsLibraryWrites {
    private static final Map<String,SerialWriteCoordinator> WRITERS=new LinkedHashMap<>();
    private static final Set<String> DELETED=new HashSet<>();
    private XsLibraryWrites() {}
    public static final class Ticket {
        private final String library; private final long epoch;
        private final SerialWriteCoordinator writer;
        private Ticket(String library,SerialWriteCoordinator writer,long epoch) { this.library=library;this.writer=writer;this.epoch=epoch; }
    }
    private static synchronized SerialWriteCoordinator writer(String library) {
        if (library==null || library.isEmpty()) throw new IllegalArgumentException("Library required");
        SerialWriteCoordinator writer=WRITERS.get(library);
        if (writer==null) {
            if (WRITERS.size()>=32) {
                Iterator<Map.Entry<String,SerialWriteCoordinator>> idle=WRITERS.entrySet().iterator();
                while(idle.hasNext()) {
                    if(idle.next().getValue().retireIfIdle()) { idle.remove();break; }
                }
                if(WRITERS.size()>=32)throw new IllegalStateException("All library writers are busy; retry later");
            }
            writer=new SerialWriteCoordinator(); WRITERS.put(library,writer);
        }
        return writer;
    }
    public static synchronized Ticket ticket(String library) {
        if(DELETED.contains(library))throw new IllegalStateException("Library has been deleted");
        SerialWriteCoordinator writer=writer(library);
        return new Ticket(library,writer,writer.token());
    }
    public static synchronized boolean isCurrent(Ticket ticket) {
        return ticket!=null && !DELETED.contains(ticket.library) && WRITERS.get(ticket.library)==ticket.writer && ticket.writer.isCurrent(ticket.epoch);
    }
    public static boolean submit(String library,Runnable operation) {
        // UI/legacy callbacks use this boolean admission API. A scope deleted between a
        // gesture and its callback is a normal rejection, not a main-thread exception.
        try { return submit(ticket(library),operation); }
        catch(IllegalStateException unavailable) { return false; }
    }
    public static synchronized boolean submit(Ticket ticket,Runnable operation) {
        return isCurrent(ticket) && ticket.writer.submit(ticket.epoch,operation);
    }
    public static synchronized boolean submit(Ticket ticket,Runnable operation,Runnable skipped) {
        return isCurrent(ticket) && ticket.writer.submit(ticket.epoch,operation,skipped);
    }
    public static synchronized boolean invalidateAndSubmit(String library,Runnable erasure) {
        if(DELETED.contains(library))return false;
        return writer(library).barrier(erasure);
    }
    public static synchronized void invalidate(String library) {
        SerialWriteCoordinator current=WRITERS.get(library);
        if(current!=null)current.invalidate();else if(!DELETED.contains(library))writer(library).invalidate();
    }
    /** Additive initialization from native durable tombstones, before application entry points. */
    public static synchronized void installDeletedLibraries(Set<String> libraries) {
        if(libraries==null)throw new IllegalArgumentException("Deleted libraries required");
        for(String library:libraries) {
            if(library==null||library.isEmpty())throw new IllegalArgumentException("Library required");
            if(DELETED.add(library)) {
                SerialWriteCoordinator current=WRITERS.get(library);
                if(current!=null)current.invalidate();
            }
        }
    }
    public static synchronized boolean isDeleted(String library) { return DELETED.contains(library); }
    /** Persist the native tombstone first; normal tickets can never reopen this namespace. */
    public static synchronized boolean deleteAndSubmit(String library,Runnable erasure) {
        if(library==null||library.isEmpty()||erasure==null)throw new IllegalArgumentException();
        DELETED.add(library);
        return writer(library).barrier(erasure);
    }
    /** A failed deletion stays closed to normal writes, but its erasure may be retried. */
    public static synchronized boolean submitDeletion(String library,Runnable erasure) {
        if(!DELETED.contains(library)||erasure==null)return false;
        return writer(library).barrier(erasure);
    }
    /** Only for a newly reserved, unpublished library after transaction failure. */
    public static synchronized boolean releaseUnused(Ticket ticket) {
        if(ticket==null||WRITERS.get(ticket.library)!=ticket.writer||
            (!DELETED.contains(ticket.library)&&!ticket.writer.isCurrent(ticket.epoch))||!ticket.writer.retireIfIdle())return false;
        WRITERS.remove(ticket.library);return true;
    }
}

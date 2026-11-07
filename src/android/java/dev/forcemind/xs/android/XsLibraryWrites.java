package dev.forcemind.xs.android;

import dev.forcemind.xs.core.SerialWriteCoordinator;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;

/** Application-wide bounded queues. All native mutation entry points share library epochs. */
public final class XsLibraryWrites {
    private static final Map<String,SerialWriteCoordinator> WRITERS=new LinkedHashMap<>();
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
        SerialWriteCoordinator writer=writer(library);
        return new Ticket(library,writer,writer.token());
    }
    public static synchronized boolean isCurrent(Ticket ticket) {
        return ticket!=null && WRITERS.get(ticket.library)==ticket.writer && ticket.writer.isCurrent(ticket.epoch);
    }
    public static boolean submit(String library,Runnable operation) {
        return submit(ticket(library),operation);
    }
    public static synchronized boolean submit(Ticket ticket,Runnable operation) {
        return isCurrent(ticket) && ticket.writer.submit(ticket.epoch,operation);
    }
    public static synchronized boolean submit(Ticket ticket,Runnable operation,Runnable skipped) {
        return isCurrent(ticket) && ticket.writer.submit(ticket.epoch,operation,skipped);
    }
    public static synchronized boolean invalidateAndSubmit(String library,Runnable erasure) {
        return writer(library).barrier(erasure);
    }
    public static synchronized void invalidate(String library) { writer(library).invalidate(); }
    /** Only for a newly reserved, unpublished library after transaction failure. */
    public static synchronized boolean releaseUnused(Ticket ticket) {
        if(!isCurrent(ticket)||!ticket.writer.retireIfIdle())return false;
        WRITERS.remove(ticket.library);return true;
    }
}

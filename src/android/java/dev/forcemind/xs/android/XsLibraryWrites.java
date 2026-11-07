package dev.forcemind.xs.android;

import dev.forcemind.xs.core.SerialWriteCoordinator;
import java.util.HashMap;
import java.util.Map;

/** Application-wide bounded queues. All native mutation entry points share library epochs. */
public final class XsLibraryWrites {
    private static final Map<String,SerialWriteCoordinator> WRITERS=new HashMap<>();
    private XsLibraryWrites() {}
    public static final class Ticket {
        private final String library; private final long epoch;
        private Ticket(String library,long epoch) { this.library=library; this.epoch=epoch; }
    }
    private static synchronized SerialWriteCoordinator writer(String library) {
        if (library==null || library.isEmpty()) throw new IllegalArgumentException("Library required");
        SerialWriteCoordinator writer=WRITERS.get(library);
        if (writer==null) {
            if (WRITERS.size()>=32) throw new IllegalStateException("Too many active library writers");
            writer=new SerialWriteCoordinator(); WRITERS.put(library,writer);
        }
        return writer;
    }
    public static Ticket ticket(String library) {
        SerialWriteCoordinator writer=writer(library);
        return new Ticket(library,writer.token());
    }
    public static boolean isCurrent(Ticket ticket) {
        return ticket!=null && writer(ticket.library).isCurrent(ticket.epoch);
    }
    public static boolean submit(String library,Runnable operation) {
        return submit(ticket(library),operation);
    }
    public static boolean submit(Ticket ticket,Runnable operation) {
        return ticket!=null && writer(ticket.library).submit(ticket.epoch,operation);
    }
    public static boolean submit(Ticket ticket,Runnable operation,Runnable skipped) {
        return ticket!=null && writer(ticket.library).submit(ticket.epoch,operation,skipped);
    }
    public static boolean invalidateAndSubmit(String library,Runnable erasure) {
        return writer(library).barrier(erasure);
    }
    public static void invalidate(String library) { writer(library).invalidate(); }
}

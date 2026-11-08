package dev.forcemind.xs.android;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/** One process-local SAF operation. Never place this object or its password in saved state.
 * It contains no Activity/Context/View and may be retained across configuration changes.
 */
final class XsBackupRequest implements AutoCloseable {
    private static final AtomicInteger NEXT_CODE=new AtomicInteger(1000);
    final String library;
    final int mode,requestCode;
    final XsLibraryWrites.Ticket ticket;
    private char[] password;
    private boolean consumed;

    XsBackupRequest(String library,int mode,char[] password) {
        // Request codes are unique for this process and never reused for a later operation.
        // Android accepts only the lower 16 bits. Fail closed instead of wrapping old IDs.
        int code=NEXT_CODE.getAndIncrement();
        if(code>65534)throw new IllegalStateException("Document request identities exhausted");
        this.ticket=XsLibraryWrites.ticket(library);
        this.library=library;this.mode=mode;this.requestCode=code;this.password=password;
    }
    synchronized boolean matches(int code) {return !consumed&&requestCode==code;}
    synchronized boolean isCurrent(String library) {
        return !consumed&&this.library.equals(library)&&XsLibraryWrites.isCurrent(ticket);
    }
    synchronized char[] takePassword() {
        if(consumed)throw new IllegalStateException("Document request already consumed");
        char[] result=password;password=null;return result;
    }
    @Override public synchronized void close() {
        consumed=true;
        if(password!=null){Arrays.fill(password,'\0');password=null;}
    }
}

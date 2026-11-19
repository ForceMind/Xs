package dev.forcemind.xs.android;

/** Process-local request lifetime is independent of Activity instances. */
public final class BackupRequestTest {
    private static int checks;
    public static void main(String[] args) {
        String library="fixture:backup-request";
        char[] password="fixture-passphrase".toCharArray();
        XsBackupRequest original=new XsBackupRequest(library,903,password);
        // Android hands this exact non-Activity object to the recreated owner.
        XsBackupRequest recreated=original;
        check(recreated.matches(original.requestCode));
        check(recreated.isCurrent(library));check(!recreated.isCurrent("fixture:other"));
        check(recreated.mode==903);check(recreated.takePassword()==password);
        recreated.close();check(!recreated.matches(original.requestCode));
        // Once handed off, closing the request must not wipe the worker-owned password.
        check(password[0]=='f');java.util.Arrays.fill(password,'\0');
        char[] canceled="fixture-canceled".toCharArray();
        XsBackupRequest cancel=new XsBackupRequest(library,904,canceled);
        cancel.close();check(canceled[0]=='\0');check(!cancel.isCurrent(library));
        XsBackupRequest next=new XsBackupRequest(library,902,null);
        check(next.requestCode!=cancel.requestCode);check(!next.matches(cancel.requestCode));
        check(next.matches(next.requestCode));check(next.isCurrent(library));
        XsLibraryWrites.invalidate(library);check(!next.isCurrent(library));next.close();
        char[] abandoned="fixture-abandoned".toCharArray();
        XsBackupRequest finished=new XsBackupRequest(library,903,abandoned);
        finished.close();check(abandoned[0]=='\0');
        boolean rejected=false;try{finished.takePassword();}catch(IllegalStateException consumed){rejected=true;}
        check(rejected);
        System.out.println("PASS BackupRequestTest "+checks+" assertions");
    }
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("Backup request assertion "+checks);}
}

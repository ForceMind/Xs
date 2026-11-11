package dev.forcemind.xs.tests;

import dev.forcemind.xs.core.TimelineBufferPolicy;
import dev.forcemind.xs.core.TimelineBufferPolicy.ClockState;
import dev.forcemind.xs.core.TimelineBufferPolicy.ClockResult;

public final class TimelineBufferPolicyTest {
    private static int checks;
    public static void main(String[] args){
        TimelineBufferPolicy defaults=TimelineBufferPolicy.defaults();
        yes(!defaults.enabled&&defaults.ttlHours==48&&defaults.budgetMiB==100);
        yes(defaults.budgetBytes()==104857600L);
        new TimelineBufferPolicy(true,1,25);new TimelineBufferPolicy(true,168,500);
        invalid(()->new TimelineBufferPolicy(false,0,100));invalid(()->new TimelineBufferPolicy(false,169,100));
        invalid(()->new TimelineBufferPolicy(false,48,24));invalid(()->new TimelineBufferPolicy(false,48,501));
        long first=1700000000000L,expires=TimelineBufferPolicy.expiresAt(first,48);
        yes(!TimelineBufferPolicy.isExpired(expires,expires-1));yes(TimelineBufferPolicy.isExpired(expires,expires));
        yes(TimelineBufferPolicy.expiresAt(Long.MAX_VALUE-1,168)==Long.MAX_VALUE);
        invalid(()->TimelineBufferPolicy.expiresAt(0,48));
        yes(TimelineBufferPolicy.validMetadata("author","中文😀",null));
        yes(!TimelineBufferPolicy.validMetadata("author","hidden\u0000body",1L));
        yes(!TimelineBufferPolicy.validMetadata("author","unpaired\uD800",1L));
        yes(!TimelineBufferPolicy.validMetadata("author","unpaired\uDC00",1L));
        yes(!TimelineBufferPolicy.validMetadata("author","valid",0L));
        yes(!TimelineBufferPolicy.validMetadata(repeat('a',129),"body",null));
        yes(!TimelineBufferPolicy.validMetadata("author",repeat('b',16001),null));
        yes(TimelineBufferPolicy.validIdentity("native:page_1.2-test"));
        yes(!TimelineBufferPolicy.validIdentity("page:selected key"));
        yes(!TimelineBufferPolicy.validIdentity("key\nwithbreak"));
        yes(!TimelineBufferPolicy.validLibrary(" "));yes(TimelineBufferPolicy.validLibrary("legacy.user@example"));

        ClockResult initial=TimelineBufferPolicy.advance(null,first,1000,"boot-a");
        yes(initial.allowCapture&&initial.nowMs==first);
        ClockResult normal=TimelineBufferPolicy.advance(initial.state,first+1000,2000,"boot-a");
        yes(normal.allowCapture&&normal.nowMs==first+1000);
        // Modest RTC correction cannot roll back TTL; elapsed time keeps advancing.
        ClockResult slightBack=TimelineBufferPolicy.advance(normal.state,first-1000,3000,"boot-a");
        yes(slightBack.allowCapture&&slightBack.nowMs==first+2000);
        ClockResult rollback=TimelineBufferPolicy.advance(slightBack.state,first-3600000,4000,"boot-a");
        yes(!rollback.allowCapture&&rollback.nowMs==first+3000);
        ClockResult corrected=TimelineBufferPolicy.advance(rollback.state,first+4000,5000,"boot-a");
        yes(!corrected.allowCapture&&corrected.nowMs==first+4000);
        ClockState acknowledged=TimelineBufferPolicy.reanchor(corrected.state,first+4000,5000,"boot-a");
        yes(!acknowledged.paused&&acknowledged.logicalNowMs==corrected.nowMs);
        ClockResult resumed=TimelineBufferPolicy.advance(acknowledged,first+5000,6000,"boot-a");
        yes(resumed.allowCapture&&resumed.nowMs==first+5000);
        // Forward jump expires content immediately, then a rollback cannot revive it.
        ClockResult forward=TimelineBufferPolicy.advance(normal.state,expires+1,3000,"boot-a");
        yes(!forward.allowCapture&&TimelineBufferPolicy.isExpired(expires,forward.nowMs));
        ClockResult backAfterForward=TimelineBufferPolicy.advance(forward.state,first+3000,4000,"boot-a");
        yes(TimelineBufferPolicy.isExpired(expires,backAfterForward.nowMs));
        yes(backAfterForward.nowMs>=forward.nowMs);
        // Reboots must retain persisted high-water when wall time goes backwards.
        ClockResult reboot=TimelineBufferPolicy.advance(forward.state,first,200,"boot-b");
        yes(!reboot.allowCapture&&reboot.nowMs==forward.nowMs);
        ClockResult rebootNormal=TimelineBufferPolicy.advance(normal.state,first+7200000,200,"boot-b");
        yes(rebootNormal.allowCapture&&rebootNormal.nowMs==first+7200000);
        ClockResult elapsedReset=TimelineBufferPolicy.advance(normal.state,first+1000,1,"boot-a");
        yes(!elapsedReset.allowCapture&&elapsedReset.nowMs>=normal.nowMs);
        ClockState nearLimit=new ClockState(Long.MAX_VALUE-10,Long.MAX_VALUE-10,100,"boot-a",false);
        ClockResult saturated=TimelineBufferPolicy.advance(nearLimit,Long.MAX_VALUE-1,200,"boot-a");
        yes(saturated.nowMs==Long.MAX_VALUE);
        invalid(()->TimelineBufferPolicy.advance(initial.state,0,2000,"boot-a"));
        invalid(()->TimelineBufferPolicy.advance(initial.state,first,-1,"boot-a"));
        invalid(()->TimelineBufferPolicy.advance(initial.state,first,2000,""));
        System.out.println("PASS "+checks+" timeline policy/clock assertions");
    }
    private static String repeat(char c,int n){char[] values=new char[n];java.util.Arrays.fill(values,c);return new String(values);}
    private static void yes(boolean okay){checks++;if(!okay)throw new AssertionError("timeline policy check "+checks);}
    private static void invalid(Runnable operation){try{operation.run();throw new AssertionError("Expected invalid");}catch(IllegalArgumentException expected){checks++;}}
}

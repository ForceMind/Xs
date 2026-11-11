package dev.forcemind.xs.core;

/** Limits and native clock semantics for the independent temporary timeline. */
public final class TimelineBufferPolicy {
    public static final int DEFAULT_TTL_HOURS=48,MIN_TTL_HOURS=1,MAX_TTL_HOURS=168;
    public static final int DEFAULT_BUDGET_MIB=100,MIN_BUDGET_MIB=25,MAX_BUDGET_MIB=500;
    public static final int MAX_BODY_CHARS=16000,MAX_AUTHOR_CHARS=128,MAX_ID_CHARS=256;
    public static final int MAX_APPEARANCES=20000,MAX_BATCHES=1024,MAX_SNAPSHOTS=40000,MAX_SEEN=40000,MAX_PAGE=100;
    public static final long MIB=1024L*1024L,MIN_HEADROOM_BYTES=MIB;
    public static final long CLOCK_JUMP_TOLERANCE_MS=5L*60L*1000L;
    public final boolean enabled;
    public final int ttlHours,budgetMiB;

    public TimelineBufferPolicy(boolean enabled,int ttlHours,int budgetMiB) {
        if(ttlHours<MIN_TTL_HOURS||ttlHours>MAX_TTL_HOURS||budgetMiB<MIN_BUDGET_MIB||budgetMiB>MAX_BUDGET_MIB)
            throw new IllegalArgumentException("Timeline policy out of range");
        this.enabled=enabled;this.ttlHours=ttlHours;this.budgetMiB=budgetMiB;
    }
    public static TimelineBufferPolicy defaults(){return new TimelineBufferPolicy(false,DEFAULT_TTL_HOURS,DEFAULT_BUDGET_MIB);}
    public long budgetBytes(){return budgetMiB*MIB;}
    public static long expiresAt(long firstAtMs,int hours){
        if(firstAtMs<=0||hours<MIN_TTL_HOURS||hours>MAX_TTL_HOURS)throw new IllegalArgumentException("Invalid timeline expiry");
        return add(firstAtMs,hours*3600000L);
    }
    public static boolean isExpired(long expiresAtMs,long nowMs){return expiresAtMs<=nowMs;}
    public static boolean validText(String value,int maxChars){
        if(value==null)return true;
        if(value.length()>maxChars)return false;
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);
            if(c==0||Character.isLowSurrogate(c))return false;
            if(Character.isHighSurrogate(c)){
                if(i+1>=value.length()||!Character.isLowSurrogate(value.charAt(++i)))return false;
            }
        }
        return true;
    }
    /** Native-issued keys, not page-selected namespaces or arbitrary payloads. */
    public static boolean validIdentity(String value){
        if(value==null||value.isEmpty()||value.length()>MAX_ID_CHARS)return false;
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);
            if(!((c>='a'&&c<='z')||(c>='A'&&c<='Z')||(c>='0'&&c<='9')||c==':'||c=='-'||c=='_'||c=='.'))return false;
        }
        return true;
    }
    public static boolean validLibrary(String value){return value!=null&&!value.trim().isEmpty()&&validText(value,MAX_ID_CHARS);}
    public static boolean validMetadata(String author,String body,Long publishedAtMs){
        return validText(author,MAX_AUTHOR_CHARS)&&validText(body,MAX_BODY_CHARS)&&(publishedAtMs==null||publishedAtMs>0);
    }
    private static long add(long a,long b){return a>Long.MAX_VALUE-b?Long.MAX_VALUE:a+b;}
    private static long positiveDifference(long a,long b){return a>=b?a-b:Long.MAX_VALUE;}
    private static boolean differs(long a,long b,long tolerance){return a>=b?a-b>tolerance:b-a>tolerance;}

    /** Persisted high-water plus an elapsedRealtime anchor from the same boot. No time-zone dependence. */
    public static final class ClockState {
        public final long logicalNowMs,wallMs,elapsedMs;
        public final String bootId;
        public final boolean paused;
        public ClockState(long logicalNowMs,long wallMs,long elapsedMs,String bootId,boolean paused){
            if(logicalNowMs<0||wallMs<0||elapsedMs<0||bootId==null||bootId.length()>128)throw new IllegalArgumentException("Invalid native clock state");
            this.logicalNowMs=logicalNowMs;this.wallMs=wallMs;this.elapsedMs=elapsedMs;this.bootId=bootId;this.paused=paused;
        }
    }
    public static final class ClockResult {
        public final long nowMs;
        public final boolean allowCapture;
        public final ClockState state;
        private ClockResult(ClockState state){this.state=state;this.nowMs=state.logicalNowMs;this.allowCapture=!state.paused;}
    }
    public static ClockResult advance(ClockState previous,long wallMs,long elapsedMs,String bootId){
        if(wallMs<=0||elapsedMs<0||bootId==null||bootId.isEmpty()||bootId.length()>128)throw new IllegalArgumentException("Invalid native clock sample");
        if(previous==null||previous.wallMs==0)return new ClockResult(new ClockState(wallMs,wallMs,elapsedMs,bootId,false));
        boolean sameBoot=bootId.equals(previous.bootId);
        boolean paused=previous.paused;
        long logical,anchor;
        if(sameBoot){
            long delta=positiveDifference(elapsedMs,previous.elapsedMs);
            if(delta==Long.MAX_VALUE){delta=0;paused=true;}
            anchor=add(previous.wallMs,delta);
            logical=Math.max(add(previous.logicalNowMs,delta),wallMs);
            if(differs(wallMs,anchor,CLOCK_JUMP_TOLERANCE_MS))paused=true;
        }else{
            // During a reboot there is no trustworthy monotonic elapsed duration. Never roll back
            // the persisted logical high-water, and pause capture if the RTC has rolled back.
            anchor=wallMs;logical=Math.max(previous.logicalNowMs,wallMs);
            if(previous.logicalNowMs>wallMs&&previous.logicalNowMs-wallMs>CLOCK_JUMP_TOLERANCE_MS)paused=true;
        }
        return new ClockResult(new ClockState(logical,anchor,elapsedMs,bootId,paused));
    }
    /** Only an explicit native user acknowledgement may resume after an anomalous clock jump. */
    public static ClockState reanchor(ClockState previous,long wallMs,long elapsedMs,String bootId){
        ClockResult valid=advance(null,wallMs,elapsedMs,bootId);
        return new ClockState(Math.max(previous==null?0:previous.logicalNowMs,valid.nowMs),wallMs,elapsedMs,bootId,false);
    }
}

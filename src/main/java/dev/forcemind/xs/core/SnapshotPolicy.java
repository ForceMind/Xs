package dev.forcemind.xs.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Snapshot completeness is native evidence, never a webpage-provided success claim. */
public final class SnapshotPolicy {
    public static final int MAX_TEXT_CHARS=16000;
    public enum Quality { LINK_ONLY, PARTIAL, COMPLETE, LEGACY_UNKNOWN }
    public enum Source {
        POST_DETAIL("native_post_detail"), FEED_VISIBLE("native_feed_visible"),
        FAVORITE("native_favorite"), TIMELINE_BUFFER("native_timeline_buffer"), OWN_ACTION("native_fixture_action"),
        ARCHIVE("local_x_archive"), LEGACY_API("legacy_api"), MIGRATION("legacy_migration");
        public final String value;
        Source(String value){this.value=value;}
        public static Source parse(String value){for(Source source:values())if(source.value.equals(value))return source;throw new IllegalArgumentException("Snapshot source");}
    }
    public static final class Version {
        public final String body,author;
        public final Long publishedAtMs,capturedAtMs;
        public final Quality quality;
        public final Source source;
        public Version(String body,String author,Long publishedAtMs,Long capturedAtMs,Source source,Quality quality) {
            boolean legacy=source==Source.MIGRATION&&quality==Quality.LEGACY_UNKNOWN&&capturedAtMs==null;
            if(body==null||source==null||quality==null||(!legacy&&(body.length()>MAX_TEXT_CHARS||
                    (author!=null&&author.length()>100)||(publishedAtMs!=null&&publishedAtMs<=0)))||
                    (capturedAtMs!=null&&capturedAtMs<=0))throw invalid();
            // Migration preserves historical bytes even if an old API did not enforce today's
            // text budget. Such values cannot be newly admitted through the native DB API.
            if(!legacy){unicode(body);if(author!=null)unicode(author);}
            this.body=body;this.author=author;this.publishedAtMs=publishedAtMs;
            this.capturedAtMs=capturedAtMs;this.source=source;this.quality=quality;
        }
    }
    public static final class Decision {
        public final boolean record,selectCurrent;
        private Decision(boolean record,boolean selectCurrent){this.record=record;this.selectCurrent=selectCurrent;}
    }
    private SnapshotPolicy() {}
    public static Decision decide(Version current,Version last,Version candidate) {
        if(candidate==null||candidate.body.isEmpty()||candidate.quality==Quality.LINK_ONLY)return new Decision(false,false);
        boolean select=canSelect(current,candidate);
        if(current!=null&&current.body.equals(candidate.body)&&rank(candidate.quality)<=rank(current.quality)) {
            // A redraw or source change is not another textual version. A genuinely known
            // capture may upgrade a migrated/legacy version whose capture time was unknown.
            if(current.capturedAtMs!=null||candidate.capturedAtMs==null)return new Decision(false,false);
        }
        if(!select&&last!=null&&last.body.equals(candidate.body)&&last.quality==candidate.quality&&last.source==candidate.source)
            return new Decision(false,false);
        return new Decision(true,select);
    }
    private static boolean canSelect(Version current,Version candidate) {
        if(current==null)return true;
        if(current.capturedAtMs!=null&&(candidate.capturedAtMs==null||candidate.capturedAtMs<=current.capturedAtMs))return false;
        if(candidate.source==Source.ARCHIVE&&current.source!=Source.ARCHIVE) {
            // Reading an old export today does not establish a newer website edit.
            if(current.quality==Quality.COMPLETE||candidate.body.length()<current.body.length())return false;
        }
        if(current.quality==Quality.COMPLETE&&candidate.quality!=Quality.COMPLETE)return false;
        if(candidate.quality==Quality.COMPLETE)return true;
        return candidate.body.length()>=current.body.length();
    }
    private static int rank(Quality quality){return quality==Quality.COMPLETE?3:quality==Quality.PARTIAL?2:quality==Quality.LEGACY_UNKNOWN?1:0;}
    public static String hash(String text) {
        if(text==null)throw invalid();unicode(text);
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out=new StringBuilder(64);for(byte value:digest){out.append(Character.forDigit((value>>>4)&15,16));out.append(Character.forDigit(value&15,16));}return out.toString();
        }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static void unicode(String value) {
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);if(c==0)throw invalid();
            if(Character.isHighSurrogate(c)){if(++i>=value.length()||!Character.isLowSurrogate(value.charAt(i)))throw invalid();}
            else if(Character.isLowSurrogate(c))throw invalid();
        }
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid snapshot");}
}

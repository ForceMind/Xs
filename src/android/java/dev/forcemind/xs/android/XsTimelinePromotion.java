package dev.forcemind.xs.android;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import dev.forcemind.xs.core.TimelinePromotionData;

/** Explicit permanent save. Archive commit precedes best-effort temporary acknowledgement. */
public final class XsTimelinePromotion {
    private XsTimelinePromotion() {}
    public interface Callback {void onComplete(Result result);}
    public static final class Result {
        public final boolean committed,favorite,acknowledged;
        private Result(boolean committed,boolean favorite,boolean acknowledged) {
            this.committed=committed;this.favorite=favorite;this.acknowledged=acknowledged;
        }
    }
    private static Result rejected(boolean favorite){return new Result(false,favorite,false);}

    /** Captures the buffer/writer generation at the gesture; callbacks run on the main thread. */
    public static boolean promote(Context context,String library,long appearanceId,boolean favorite,Callback callback) {
        Context app=context.getApplicationContext();Handler main=new Handler(Looper.getMainLooper());
        XsTimelineBuffer buffer=XsTimelineBuffer.get(app);final XsTimelineBuffer.Ticket ticket;
        Runnable skipped=()->main.post(()->{if(callback!=null)callback.onComplete(rejected(favorite));});
        try{ticket=buffer.ticket(library);}catch(RuntimeException unavailable){skipped.run();return false;}
        boolean queued=XsLibraryWrites.submit(buffer.writerTicket(ticket),()->{
            Result result;
            try{result=promote(app,buffer,ticket,appearanceId,favorite,System.currentTimeMillis());}
            catch(RuntimeException unavailable){result=rejected(favorite);}
            final Result completed=result;main.post(()->{if(callback!=null)callback.onComplete(completed);});
        },skipped);
        if(!queued)skipped.run();return queued;
    }

    /** Synchronous worker seam for fault/SQLite tests. Never claims a cross-database transaction. */
    static Result promote(Context context,XsTimelineBuffer buffer,XsTimelineBuffer.Ticket ticket,
                          long appearanceId,boolean favorite,long savedAtMs) {
        XsTimelineBuffer.Entry entry=buffer.entry(ticket,appearanceId);
        if(entry==null||savedAtMs<=0)return rejected(favorite);
        TimelinePromotionData data=new TimelinePromotionData(entry.promotionId,entry.url,entry.body,entry.author,
            entry.publishedAt,entry.capturedAt,entry.snapshotCapturedAt,entry.source,entry.batchId,entry.batchReason,entry.provenance,entry.quality);
        boolean committed;
        try(XsArchiveDatabase archive=new XsArchiveDatabase(context)) {
            committed=archive.promoteTimeline(entry.libraryId,data,favorite,savedAtMs,()->{
                XsTimelineBuffer.Entry current=buffer.entry(ticket,appearanceId);
                return current!=null&&entry.promotionId.equals(current.promotionId);
            });
        }
        if(!committed)return rejected(favorite);
        // A crash, GC or clear here must not roll back or duplicate the committed archive result.
        boolean acknowledged=false;
        try{
            if(buffer.isCurrent(ticket))acknowledged=buffer.acknowledgePromotion(entry.libraryId,appearanceId,entry.promotionId,favorite,savedAtMs);
        }catch(RuntimeException unavailable){/* The permanent result is already durable. */}
        return new Result(true,favorite,acknowledged);
    }
}

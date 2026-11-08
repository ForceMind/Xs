package dev.forcemind.xs.android;

import android.app.job.JobParameters;
import android.app.job.JobService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Deferrable local GC only. Android may delay/stop it; every query enforces TTL independently. */
public final class XsTimelineMaintenanceJobService extends JobService {
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->new Thread(r,"xs-timeline-gc"));
    private final AtomicLong generation=new AtomicLong();
    @Override public boolean onStartJob(JobParameters parameters){
        final long run=generation.incrementAndGet();
        worker.execute(()->{
            try{
                if(generation.get()==run)XsTimelineBuffer.get(getApplicationContext()).maintainAsync(()->generation.get()==run,ok->{if(generation.get()==run)jobFinished(parameters,!ok);});
            }catch(RuntimeException unavailable){if(generation.get()==run)jobFinished(parameters,true);}
        });
        return true;
    }
    @Override public boolean onStopJob(JobParameters parameters){generation.incrementAndGet();return true;}
    @Override public void onDestroy(){generation.incrementAndGet();worker.shutdownNow();super.onDestroy();}
}

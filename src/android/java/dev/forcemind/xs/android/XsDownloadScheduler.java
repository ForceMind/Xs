package dev.forcemind.xs.android;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.PersistableBundle;

/** JobScheduler owns background lifetime; Android may defer/stop work for network, quota or health. */
final class XsDownloadScheduler implements XsMediaRepository.Scheduler {
    private final Context context;
    XsDownloadScheduler(Context context){this.context=context.getApplicationContext();}
    static JobInfo request(Context context,String task,String library,int jobId,long estimate,boolean userInitiated) {
        PersistableBundle extras=new PersistableBundle();extras.putString("task",task);extras.putString("library",library);
        JobInfo.Builder request=new JobInfo.Builder(jobId,new ComponentName(context,XsDownloadJobService.class))
            .setExtras(extras).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
            .setRequiresStorageNotLow(true).setBackoffCriteria(30000,JobInfo.BACKOFF_POLICY_EXPONENTIAL);
        if(Build.VERSION.SDK_INT>=28)request.setEstimatedNetworkBytes(estimate,0);
        if(Build.VERSION.SDK_INT>=34&&userInitiated)request.setUserInitiated(true);
        return request.build();
    }
    @Override public boolean schedule(String task,String library,int jobId,long estimate,boolean userInitiated) {
        JobScheduler jobs=context.getSystemService(JobScheduler.class);if(jobs==null)return false;
        if(Build.VERSION.SDK_INT>=34&&userInitiated)try{
            if(jobs.schedule(request(context,task,library,jobId,estimate,true))==JobScheduler.RESULT_SUCCESS)return true;
        }catch(RuntimeException rejected){/* Not visible / unsupported / exhausted: ordinary job is explicitly deferrable. */}
        try{return jobs.schedule(request(context,task,library,jobId,estimate,false))==JobScheduler.RESULT_SUCCESS;}
        catch(RuntimeException rejected){return false;}
    }
    @Override public boolean isPending(int jobId){JobScheduler jobs=context.getSystemService(JobScheduler.class);return jobs!=null&&jobs.getPendingJob(jobId)!=null;}
    @Override public void cancel(int jobId){JobScheduler jobs=context.getSystemService(JobScheduler.class);if(jobs!=null)jobs.cancel(jobId);}
}

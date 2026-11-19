package dev.forcemind.xs.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.util.HashMap;
import java.util.Map;

/** BIND_JOB_SERVICE only. No boot-started foreground service, cookies or remote command interface. */
public final class XsDownloadJobService extends JobService {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Map<Integer,JobParameters> running=new HashMap<>();
    private static final String CHANNEL="xs-downloads";
    static boolean accepts(String library,String task) {
        return library!=null&&!library.trim().isEmpty()&&library.length()<=256
            &&task!=null&&task.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
            &&!XsLibraryWrites.isDeleted(library);
    }
    private void reject(String library,String task,String message) {
        if(!accepts(library,task))return;
        try{XsMediaRepository.get(this).schedulingRejected(library,task,message);}catch(RuntimeException unavailable){/* Status failure must not crash a system callback. */}
    }
    @Override public boolean onStartJob(JobParameters params) {
        String task=params.getExtras().getString("task"),library=params.getExtras().getString("library");
        // Reject persisted callbacks for erased namespaces before opening the media store or UI intent.
        if(!accepts(library,task))return false;
        try {
            NotificationManager manager=getSystemService(NotificationManager.class);
            if(manager!=null)manager.createNotificationChannel(new NotificationChannel(CHANNEL,"Xs downloads",NotificationManager.IMPORTANCE_LOW));
            Notification notification=notification(library,task);
            if(Build.VERSION.SDK_INT>=34)setNotification(params,params.getJobId(),notification,JOB_END_NOTIFICATION_POLICY_REMOVE);
            else if(manager!=null)postNotification(manager,params.getJobId(),notification);
        }catch(RuntimeException rejected) {
            reject(library,task,"Download notification unavailable; retry from the visible app");return false;
        }
        running.put(params.getJobId(),params);
        boolean started;
        try{started=accepts(library,task)&&XsMediaRepository.get(this).runScheduled(library,task,()->main.post(()->finish(params)));}
        catch(RuntimeException unavailable){started=false;}
        if(!started){running.remove(params.getJobId());clearNotification(params);reject(library,task,"Download queue rejected the job; retry later");return false;}
        main.postDelayed(new Runnable(){@Override public void run(){
            if(running.get(params.getJobId())!=params)return;
            if(!accepts(library,task)){finish(params);return;}
            try{NotificationManager manager=getSystemService(NotificationManager.class);if(manager!=null)postNotification(manager,params.getJobId(),notification(library,task));}catch(RuntimeException ignored){}
            main.postDelayed(this,1000);
        }},1000);
        return true;
    }
    @android.annotation.SuppressLint("MissingPermission")
    private void postNotification(NotificationManager manager,int id,Notification notification) {
        // The permission is optional for ordinary jobs; notification denial never starts a forbidden service.
        if(Build.VERSION.SDK_INT<33||checkSelfPermission("android.permission.POST_NOTIFICATIONS")==android.content.pm.PackageManager.PERMISSION_GRANTED)manager.notify(id,notification);
    }
    private Notification notification(String library,String task) {
        boolean chinese=getResources().getConfiguration().getLocales().get(0).getLanguage().equals("zh");
        XsMediaRepository.Asset asset=XsMediaRepository.get(this).find(library,task);
        PendingIntent open=PendingIntent.getActivity(this,task.hashCode(),XsAssetsActivity.intent(this,library),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder notice=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(chinese?"Xs 下载":"Xs download").setContentText(chinese?"点击查看进度或取消；系统可能暂停任务":"Open to see progress or cancel; Android may pause this task")
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open);
        if(asset!=null&&asset.total>0)notice.setProgress(100,(int)Math.min(100,asset.bytes*100/asset.total),false);else notice.setProgress(0,0,true);
        return notice.build();
    }
    private void finish(JobParameters params) {
        if(running.get(params.getJobId())!=params)return;
        running.remove(params.getJobId());clearNotification(params);
        String library=params.getExtras().getString("library"),task=params.getExtras().getString("task");
        boolean retry=false;
        if(accepts(library,task))try{retry=XsMediaRepository.get(this).shouldRetry(library,task);}catch(RuntimeException unavailable){/* Finish without rescheduling an unavailable/deleted task. */}
        try{jobFinished(params,retry);}catch(RuntimeException stale){/* A stopped system job may already have released its lifecycle. */}
    }
    private void clearNotification(JobParameters params){try{NotificationManager manager=getSystemService(NotificationManager.class);if(manager!=null)manager.cancel(params.getJobId());}catch(RuntimeException unavailable){/* Best-effort removal after a system stop. */}}
    static boolean shouldRescheduleAfterStop(int sdk,int reason) {return sdk<31||(reason!=JobParameters.STOP_REASON_USER&&reason!=JobParameters.STOP_REASON_CANCELLED_BY_APP);}
    @Override public boolean onStopJob(JobParameters params) {
        if(running.get(params.getJobId())!=params)return false;
        running.remove(params.getJobId());clearNotification(params);
        String library=params.getExtras().getString("library"),task=params.getExtras().getString("task");
        if(!accepts(library,task))return false;
        int reason=Build.VERSION.SDK_INT>=31?params.getStopReason():0;
        try{XsMediaRepository.get(this).pauseScheduled(library,task,reason);}catch(RuntimeException queueUnavailable){reject(library,task,"Android stopped transfer; retry from the visible app");}
        // Express user/app stop intent; Android remains authoritative about rescheduling.
        return shouldRescheduleAfterStop(Build.VERSION.SDK_INT,reason);
    }
}

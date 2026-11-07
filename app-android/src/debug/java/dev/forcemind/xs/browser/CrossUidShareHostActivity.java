package dev.forcemind.xs.browser;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import java.util.concurrent.CountDownLatch;

/** Debug-only native launch/result owner for a genuinely separate UID. */
public final class CrossUidShareHostActivity extends Activity {
    private static final int REQUEST=9264;
    public static final String RECEIVER_PACKAGE="dev.forcemind.xs.sharefixture";
    public static final String RECEIVER_ACTIVITY=RECEIVER_PACKAGE+".ReceiverActivity";
    private Probe active;
    public static final class Probe {
        public final CountDownLatch ready=new CountDownLatch(1),done=new CountDownLatch(1);
        public volatile Bundle summary;
        private volatile ResultReceiver continuation;
        public void readNow(){if(continuation==null)throw new IllegalStateException("Receiver not ready");continuation.send(1,null);}
        public void cancel(){ResultReceiver receiver=continuation;if(receiver!=null)receiver.send(2,null);}
    }
    public void launch(Intent share,Probe probe) {
        if(active!=null)throw new IllegalStateException("One probe at a time");active=probe;
        Intent outgoing=new Intent(share).setClassName(RECEIVER_PACKAGE,RECEIVER_ACTIVITY);
        outgoing.putExtra("xs.fixture.ready",new ResultReceiver(new Handler(Looper.getMainLooper())) {
            @Override protected void onReceiveResult(int code,Bundle controls) {
                if(active!=probe||code!=1||controls==null)return;
                probe.continuation=controls.getParcelable("xs.fixture.continue");probe.ready.countDown();
            }
        });
        try{startActivityForResult(outgoing,REQUEST);}
        catch(RuntimeException failure){probe.summary=new Bundle();probe.summary.putString("status","LAUNCH_FAILED");probe.ready.countDown();probe.done.countDown();active=null;}
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);if(request!=REQUEST||active==null)return;
        Probe completed=active;active=null;completed.summary=data==null?new Bundle():data.getExtras();
        if(completed.summary==null)completed.summary=new Bundle();
        completed.summary.putInt("activity_result",result);completed.ready.countDown();completed.done.countDown();
    }
    @Override protected void onDestroy(){if(active!=null)active.cancel();super.onDestroy();}
}

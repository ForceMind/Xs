package dev.forcemind.xs.sharefixture;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.ResultReceiver;
import android.widget.TextView;
import java.io.InputStream;
import java.io.FileNotFoundException;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Separate-UID, permission-free receiver used only by instrumentation.
 * It never returns content, paths, URIs or exception text. No manual grant API.
 */
public final class ReceiverActivity extends Activity {
    private static final String READY="xs.fixture.ready",CONTINUE="xs.fixture.continue";
    private static final String PROBE="xs.fixture.probe_uri";
    private static final long MAX_BYTES=128L*1024*1024;
    private final AtomicBoolean started=new AtomicBoolean(),canceled=new AtomicBoolean();
    private Uri selected,probe;
    private ResultReceiver continuation;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        TextView label=new TextView(this);label.setText(R.string.fixture_waiting);setContentView(label);
        Intent incoming=getIntent();
        if(!"dev.forcemind.xs".equals(getCallingPackage())||incoming==null||!Intent.ACTION_SEND.equals(incoming.getAction())){finishReport("INVALID",0,null,true,true);return;}
        selected=incoming.getParcelableExtra(Intent.EXTRA_STREAM);
        String other=incoming.getStringExtra(PROBE);probe=other==null?null:Uri.parse(other);
        if(!allowed(selected)||(probe!=null&&!allowed(probe))){finishReport("INVALID",0,null,true,true);return;}
        ResultReceiver ready=incoming.getParcelableExtra(READY);
        if(ready==null){finishReport("INVALID",0,null,true,true);return;}
        continuation=new ResultReceiver(new Handler(Looper.getMainLooper())) {
            @Override protected void onReceiveResult(int resultCode,Bundle ignored) {
                if(resultCode==2){canceled.set(true);finishReport("CANCELED",0,null,true,true);return;}
                if(resultCode!=1||!started.compareAndSet(false,true))return;
                new Thread(ReceiverActivity.this::readSelected,"xs-cross-uid-fixture-read").start();
            }
        };
        // The caller can finish deletion before allowing the very first openFile call.
        Bundle control=new Bundle();control.putParcelable(CONTINUE,frameworkReceiver(continuation));ready.send(1,control);
    }
    /** Marshal only the framework class; the sender APK cannot load this anonymous callback. */
    private static ResultReceiver frameworkReceiver(ResultReceiver local) {
        Parcel parcel=Parcel.obtain();
        try{local.writeToParcel(parcel,0);parcel.setDataPosition(0);return ResultReceiver.CREATOR.createFromParcel(parcel);}
        finally{parcel.recycle();}
    }
    private void readSelected() {
        String status="UNAVAILABLE",hash=null;long bytes=0;
        boolean writeDenied=true,probeDenied=true;
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(InputStream input=getContentResolver().openInputStream(selected)) {
                if(input==null)throw new FileNotFoundException();
                byte[] buffer=new byte[8192];int count;
                while((count=input.read(buffer))!=-1){if(canceled.get())return;bytes+=count;if(bytes>MAX_BYTES)throw new FileNotFoundException();digest.update(buffer,0,count);}
            }
            hash=hex(digest.digest());status="READABLE";
        }catch(SecurityException denied){status="DENIED";bytes=0;}
        catch(Exception unavailable){status="UNAVAILABLE";bytes=0;}
        if("READABLE".equals(status)) {
            try(ParcelFileDescriptor writable=getContentResolver().openFileDescriptor(selected,"w")) {writeDenied=writable==null;}
            catch(Exception denied){writeDenied=true;}
        }
        if(probe!=null) {
            try(ParcelFileDescriptor other=getContentResolver().openFileDescriptor(probe,"r")){probeDenied=other==null;}
            catch(Exception denied){probeDenied=true;}
        }
        final String resultStatus=status,resultHash=hash;final long resultBytes=bytes;
        final boolean resultWriteDenied=writeDenied,resultProbeDenied=probeDenied;
        runOnUiThread(()->{if(!canceled.get())finishReport(resultStatus,resultBytes,resultHash,resultWriteDenied,resultProbeDenied);});
    }
    private static boolean allowed(Uri uri) {
        // This is not a general content-provider proxy. Deliberately permit invalid paths
        // on Xs's exact authority so tests exercise the provider's database/path rejection.
        return uri!=null&&"content".equals(uri.getScheme())&&"dev.forcemind.xs.xs.assets".equals(uri.getAuthority());
    }
    private void finishReport(String status,long bytes,String hash,boolean writeDenied,boolean probeDenied) {
        if(isFinishing())return;
        Intent summary=new Intent().putExtra("status",status).putExtra("bytes",bytes)
            .putExtra("sha256",hash).putExtra("write_denied",writeDenied)
            .putExtra("probe_denied",probeDenied).putExtra("receiver_uid",Process.myUid())
            .putExtra("read_granted",selected!=null&&checkUriPermission(selected,Process.myPid(),Process.myUid(),Intent.FLAG_GRANT_READ_URI_PERMISSION)==PackageManager.PERMISSION_GRANTED);
        setResult(RESULT_OK,summary);finish();
    }
    @Override protected void onDestroy(){canceled.set(true);super.onDestroy();}
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder(bytes.length*2);for(byte value:bytes){out.append(Character.forDigit((value>>>4)&15,16));out.append(Character.forDigit(value&15,16));}return out.toString();}
}

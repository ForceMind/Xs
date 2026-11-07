package dev.forcemind.xs.android;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import dev.forcemind.xs.core.PostUrls;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Chooser invocation is never treated as a sending or delivery receipt. */
public final class XsShareCoordinator {
    private XsShareCoordinator(){}
    public static Intent textIntent(String postUrl,String visibleText) {
        String canonical=PostUrls.canonicalLink(postUrl);
        if(canonical==null)throw new IllegalArgumentException("Valid post required");
        String body=visibleText==null?"":visibleText.trim();
        if(body.length()>20000)body=body.substring(0,20000)+"\n[truncated]";
        return new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,body.isEmpty()?canonical:body+"\n\n"+canonical);
    }
    public static void shareText(Activity activity,String postUrl,String visibleText){activity.startActivity(Intent.createChooser(textIntent(postUrl,visibleText),null));}
    /** Copies preserve asynchronous receiver reads if the source is deleted afterward. */
    public static Intent assetIntent(Context context,String libraryId,List<String> assetIds)throws IOException {
        if(assetIds==null||assetIds.isEmpty()||assetIds.size()>10)throw new IllegalArgumentException("Select 1 to 10 completed images");
        XsMediaRepository repository=XsMediaRepository.get(context);ArrayList<Uri> uris=new ArrayList<>();String firstMime=null;boolean same=true;
        ArrayList<XsMediaRepository.Lease> copies=new ArrayList<>();
        try {
            for(String id:assetIds){XsMediaRepository.Lease l=repository.lease(libraryId,id);copies.add(l);if(firstMime==null)firstMime=l.mime;else if(!firstMime.equals(l.mime))same=false;
                uris.add(new Uri.Builder().scheme("content").authority(context.getPackageName()+".xs.assets").appendPath(l.token).appendPath(l.filename).build());}
        } catch(IOException|RuntimeException failure) {
            for(XsMediaRepository.Lease copy:copies)repository.discardLease(copy);
            throw failure;
        }
        String mime=same?firstMime:"image/*";
        Intent send=new Intent(uris.size()==1?Intent.ACTION_SEND:Intent.ACTION_SEND_MULTIPLE).setType(mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if(uris.size()==1)send.putExtra(Intent.EXTRA_STREAM,uris.get(0));else send.putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris);
        ClipData clip=new ClipData("Selected Xs images",new String[]{mime},new ClipData.Item(uris.get(0)));for(int i=1;i<uris.size();i++)clip.addItem(new ClipData.Item(uris.get(i)));send.setClipData(clip);return send;
    }
    public static void shareAssets(Activity activity,String libraryId,List<String> assetIds)throws IOException {activity.startActivity(Intent.createChooser(assetIntent(activity,libraryId,assetIds),null));}
}

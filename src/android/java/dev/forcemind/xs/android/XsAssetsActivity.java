package dev.forcemind.xs.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import android.widget.MediaController;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Offline saved-media/task list, independent of the currently logged in X account. */
public final class XsAssetsActivity extends Activity {
    private static final String LIBRARY="library_id";
    private static final int EXPORT=61;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Set<String> selected=new HashSet<>();
    private VideoView currentVideo;
    private String library,exportId;private LinearLayout rows;private EditText search;private TextView status;
    private final Runnable refresh=new Runnable(){@Override public void run(){render();handler.postDelayed(this,1500);}};
    public static Intent intent(Context context,String library){return new Intent(context,XsAssetsActivity.class).putExtra(LIBRARY,library);}
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);library=getIntent().getStringExtra(LIBRARY);if(library==null||library.isEmpty()){finish();return;}
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(16,8,16,8);
        TextView title=new TextView(this);title.setText("Xs · "+tr("保存的媒体","Saved media"));title.setTextSize(22);root.addView(title);
        status=new TextView(this);status.setText(tr("下载由你主动选择；完整 MP4 经校验后才可播放/分享。不支持播放列表、DRM 或续传。中断后从来源重新选择。","Downloads require your selection. Only validated complete MP4s can play/share. Playlists, DRM and resume are unsupported. Re-select at the source after interruption."));root.addView(status);
        search=new EditText(this);search.setSingleLine(true);search.setHint(tr("来源、分类、状态","Source, category, state"));root.addView(search);
        LinearLayout actions=new LinearLayout(this);root.addView(actions);button(actions,tr("查找","Search"),this::render);button(actions,tr("分享所选","Share selected"),this::share);button(actions,tr("删除所选","Delete selected"),this::deleteSelected);
        ScrollView scroll=new ScrollView(this);rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);scroll.addView(rows);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
    }
    @Override protected void onResume(){super.onResume();handler.post(refresh);}
    @Override protected void onPause(){handler.removeCallbacks(refresh);if(currentVideo!=null)currentVideo.pause();super.onPause();}
    @Override protected void onDestroy(){if(currentVideo!=null)currentVideo.stopPlayback();io.shutdown();super.onDestroy();}
    private String tr(String chinese,String english){return getResources().getConfiguration().getLocales().get(0).getLanguage().equals("zh")?chinese:english;}
    private XsMediaRepository repo(){return XsMediaRepository.get(this);}
    private void render(){
        if(isFinishing()||rows==null)return;String needle=search.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);rows.removeAllViews();
        List<XsMediaRepository.Asset> list=repo().list(library);int visible=0;
        for(XsMediaRepository.Asset asset:list){
            if(!needle.isEmpty()&&!(asset.postUrl+" "+asset.category+" "+asset.state+" "+asset.mime).toLowerCase(java.util.Locale.ROOT).contains(needle))continue;
            visible++;LinearLayout item=new LinearLayout(this);item.setOrientation(LinearLayout.VERTICAL);item.setPadding(0,12,0,12);rows.addView(item);
            CheckBox choose=new CheckBox(this);choose.setText(asset.state+" · "+(asset.mime==null?asset.mediaKind:asset.mime)+" · "+asset.bytes+(asset.total>0?" / "+asset.total:"")+" bytes\n"+asset.postUrl+(asset.category.isEmpty()?"":"\n"+asset.category));choose.setChecked(selected.contains(asset.id));choose.setOnCheckedChangeListener((v,checked)->{if(checked)selected.add(asset.id);else selected.remove(asset.id);});item.addView(choose);
            if("VIDEO".equals(asset.mediaKind)&&"COMPLETED".equals(asset.state)){TextView info=new TextView(this);info.setText(asset.width+"×"+asset.height+" · "+(asset.durationMs/1000.0)+" s · "+tr("音轨 ","Audio tracks ")+asset.audioTracks+" · "+asset.rotationDegrees+"°");item.addView(info);}
            if(asset.error!=null){TextView error=new TextView(this);error.setText(tr("未完成；重新打开来源选择媒体以重试。","Incomplete. Reopen the source and select its media to restart."));item.addView(error);}
            LinearLayout commands=new LinearLayout(this);item.addView(commands);
            if("COMPLETED".equals(asset.state)){button(commands,tr("预览","Preview"),()->preview(asset.id));button(commands,tr("分类","Category"),()->category(asset));button(commands,tr("导出","Export"),()->export(asset));}
            else if("QUEUED".equals(asset.state)||"DOWNLOADING".equals(asset.state))button(commands,tr("取消","Cancel"),()->{repo().cancel(library,asset.id);render();});
            button(commands,tr("来源","Source"),()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(asset.postUrl))));
        }
        if(visible==0){TextView empty=new TextView(this);empty.setText(tr("没有匹配的资源。","No matching resources."));rows.addView(empty);}
    }
    private void preview(String id){
        try{XsMediaRepository.Asset asset=repo().find(library,id);
            if(asset!=null&&"video/mp4".equals(asset.mime)){previewVideo(id);return;}
            File file=repo().completedFile(library,id);BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),bounds);
            BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=Math.max(1,Math.max(bounds.outWidth,bounds.outHeight)/1600);Bitmap image=BitmapFactory.decodeFile(file.getPath(),options);if(image==null)throw new IllegalStateException();
            ImageView view=new ImageView(this);view.setAdjustViewBounds(true);view.setImageBitmap(image);ScrollView scroll=new ScrollView(this);scroll.addView(view,new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
            new AlertDialog.Builder(this).setView(scroll).setPositiveButton(android.R.string.ok,null).setOnDismissListener(d->image.recycle()).show();
        }catch(RuntimeException unavailable){toast(tr("文件不可用","File unavailable"));}
    }
    private void previewVideo(String id){
        File file=repo().completedFile(library,id);
        if(currentVideo!=null)currentVideo.stopPlayback();
        VideoView player=new VideoView(this);currentVideo=player;
        MediaController controls=new MediaController(this);controls.setAnchorView(player);player.setMediaController(controls);
        player.setVideoPath(file.getPath());
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(tr("本地完整视频","Local complete video"))
            .setView(player).setPositiveButton(android.R.string.ok,null).create();
        player.setOnPreparedListener(media->{if(!isFinishing())player.start();});
        player.setOnErrorListener((media,what,extra)->{toast(tr("当前设备无法播放此文件，原件保留。","This device cannot play the file. The original is preserved."));return true;});
        dialog.setOnDismissListener(d->{player.stopPlayback();if(currentVideo==player)currentVideo=null;});
        dialog.show();
        player.getLayoutParams().height=Math.max(180,getResources().getDisplayMetrics().heightPixels/2);player.requestLayout();
    }
    private void category(XsMediaRepository.Asset asset){EditText edit=new EditText(this);edit.setText(asset.category);new AlertDialog.Builder(this).setTitle(tr("资源分类","Asset category")).setView(edit).setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok,(d,w)->{try{repo().setCategory(library,asset.id,edit.getText().toString());render();}catch(RuntimeException error){toast(tr("分类太长","Category too long"));}}).show();}
    private void share(){
        ArrayList<String> ids=new ArrayList<>(selected);if(ids.isEmpty()){toast(tr("先选择已完成的媒体","Select completed media first"));return;}
        io.execute(()->{try{Intent intent=XsShareCoordinator.assetIntent(this,library,ids);runOnUiThread(()->{if(!isFinishing())startActivity(Intent.createChooser(intent,null));});}catch(Exception failure){runOnUiThread(()->toast(tr("请选择单个完整视频或 1–10 张图片；混合资源请分开分享。","Select one complete video or 1–10 images. Share mixed media separately.")));}});
    }
    private void deleteSelected(){
        if(selected.isEmpty())return;new AlertDialog.Builder(this).setMessage(tr("删除所选资源或任务？历史、收藏与已分享的临时副本保留。","Delete selected assets/tasks? History, favorites and current share copies are preserved.")).setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok,(d,w)->{for(String id:new ArrayList<>(selected))repo().delete(library,id);selected.clear();render();}).show();
    }
    private void export(XsMediaRepository.Asset asset){exportId=asset.id;startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(asset.mime).putExtra(Intent.EXTRA_TITLE,asset.filename),EXPORT);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=EXPORT||result!=RESULT_OK||data==null||data.getData()==null||exportId==null)return;String id=exportId;exportId=null;Uri destination=data.getData();
        io.execute(()->{try(FileInputStream input=new FileInputStream(repo().completedFile(library,id));OutputStream output=getContentResolver().openOutputStream(destination)){if(output==null)throw new java.io.IOException();byte[] b=new byte[16384];int n;while((n=input.read(b))!=-1)output.write(b,0,n);runOnUiThread(()->toast(tr("已导出","Exported")));}catch(Exception failure){runOnUiThread(()->toast(tr("导出未完成，源文件仍保留。","Export incomplete; the original is preserved.")));}});
    }
    private void button(LinearLayout parent,String label,Runnable action){Button b=new Button(this);b.setText(label);b.setTextSize(12);b.setOnClickListener(v->{try{action.run();}catch(RuntimeException failure){toast(tr("操作未完成，请稍后重试。","Action incomplete; try again later."));}});parent.addView(b,new LinearLayout.LayoutParams(0,-2,1));}
    private void toast(String message){if(!isFinishing())Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
}

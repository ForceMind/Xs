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
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import android.widget.MediaController;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Paged offline assets with explicit selection; browser cache is never a saved asset. */
public final class XsAssetsActivity extends Activity {
    private static final String LIBRARY="library_id";
    private static final int EXPORT=61,PAGE=80;
    public static final int ID_SEARCH=0x8201,ID_RESULTS=0x8202,ID_FILTER=0x8203,ID_ACTIONS=0x8204,ID_STATUS=0x8205;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor io=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(4));
    private final Set<String> selected=new LinkedHashSet<>();
    private final List<XsMediaRepository.Asset> visible=new ArrayList<>();
    private final List<String> labels=new ArrayList<>();
    private VideoView currentVideo;private String library,kind,state;private ListView rows;private EditText search;private TextView status;
    private ArrayAdapter<String> adapter;private Button more,actions;private int request,nextOffset;private boolean destroyed,reading,resumed;
    private static final class ExportRequest {final List<String> ids;final XsLibraryWrites.Ticket ticket;ExportRequest(List<String> ids,XsLibraryWrites.Ticket ticket){this.ids=ids;this.ticket=ticket;}}
    private ExportRequest pendingExport;
    private final Runnable delayed=()->load(false);
    private final Runnable poll=new Runnable(){public void run(){if(resumed&&!destroyed){if(!reading&&nextOffset<=PAGE)load(false);handler.postDelayed(this,2000);}}};
    public static Intent intent(Context context,String library){return new Intent(context,XsAssetsActivity.class).putExtra(LIBRARY,library);}
    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);library=getIntent().getStringExtra(LIBRARY);if(library==null||library.isEmpty()||library.length()>256){finish();return;}
        LinearLayout root=column();root.setPadding(px(12),px(8),px(12),px(8));
        LinearLayout heading=line();TextView title=text(tr("Xs 保存的媒体","Xs saved media"));title.setTextSize(22);heading.addView(title,weighted());button(heading,tr("返回","Back"),this::finish);root.addView(heading);
        status=text(tr("选择资源后操作；清缓存不会删除这些文件。","Select resources for actions. Clearing cache preserves these files."));status.setId(ID_STATUS);root.addView(status);
        search=new EditText(this);search.setSingleLine(true);search.setHint(tr("搜索来源帖子、资源分类、格式或状态","Search source post, category, format or state"));search.setId(ID_SEARCH);root.addView(search);
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void afterTextChanged(Editable e){}public void onTextChanged(CharSequence s,int start,int before,int count){handler.removeCallbacks(delayed);handler.postDelayed(delayed,250);}});
        LinearLayout commands=line();Button filter=button(commands,tr("类型 / 状态","Type / state"),this::filters);filter.setId(ID_FILTER);
        actions=button(commands,tr("所选 0","Selected 0"),this::selectionMenu);actions.setId(ID_ACTIONS);root.addView(commands);
        rows=new ListView(this);rows.setId(ID_RESULTS);rows.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        adapter=new ArrayAdapter<>(this,android.R.layout.simple_list_item_multiple_choice,labels);rows.setAdapter(adapter);
        rows.setOnItemClickListener((p,v,n,id)->{String asset=visible.get(n).id;if(selected.contains(asset))selected.remove(asset);else if(selected.size()<200)selected.add(asset);else toast(tr("每批最多 200 个资源","Select up to 200 resources per batch"));syncSelection();});
        rows.setOnItemLongClickListener((p,v,n,id)->{assetMenu(visible.get(n));return true;});root.addView(rows,new LinearLayout.LayoutParams(-1,0,1));
        more=button(root,tr("加载更多","Load more"),()->load(true));more.setVisibility(View.GONE);setContentView(root);
    }
    @Override protected void onResume(){super.onResume();if(XsDeletedLibraries.isDeleted(this,library)){finish();return;}resumed=true;handler.post(poll);}
    @Override protected void onPause(){resumed=false;handler.removeCallbacks(poll);if(currentVideo!=null)currentVideo.pause();super.onPause();}
    @Override protected void onDestroy(){destroyed=true;request++;handler.removeCallbacks(delayed);handler.removeCallbacks(poll);if(currentVideo!=null)currentVideo.stopPlayback();io.shutdown();super.onDestroy();}
    private XsMediaRepository repo(){return XsMediaRepository.get(this);}
    private void load(boolean append){
        if(destroyed)return;if(XsLibraryWrites.isDeleted(library)){visible.clear();labels.clear();adapter.notifyDataSetChanged();finish();return;}final int generation=++request,offset=append?nextOffset:0;final String keyword=search.getText().toString().trim(),selectedKind=kind,selectedState=state;
        reading=true;more.setEnabled(false);
        execute(()->{
            try{List<XsMediaRepository.Asset> found=repo().search(library,keyword,selectedKind,selectedState,offset,PAGE);
                handler.post(()->{if(destroyed||generation!=request)return;if(XsLibraryWrites.isDeleted(library)){visible.clear();labels.clear();adapter.notifyDataSetChanged();finish();return;}reading=false;
                    if(!append){visible.clear();labels.clear();}for(XsMediaRepository.Asset asset:found){visible.add(asset);labels.add(assetLabel(asset));}
                    adapter.notifyDataSetChanged();syncSelection();nextOffset=offset+found.size();more.setVisibility(found.size()==PAGE?View.VISIBLE:View.GONE);more.setEnabled(true);
                    status.setText(visible.size()+tr(" 个匹配资源 · 长按查看或重试"," matching resources · Long press to preview or retry"));
                });
            }catch(RuntimeException failure){handler.post(()->{if(!destroyed&&generation==request){reading=false;more.setEnabled(true);status.setText(tr("查询未完成，请重试","Search incomplete; retry"));}});}
        });
    }
    private String assetLabel(XsMediaRepository.Asset a){return stateLabel(a.state)+" · "+(a.mime==null?a.mediaKind:a.mime)+" · "+a.bytes+(a.total>0?" / "+a.total:"")+tr(" 字节"," bytes")+"\n"+a.postUrl+(a.category.isEmpty()?"":"\n"+a.category);}
    private void syncSelection(){actions.setText(tr("所选 ","Selected ")+selected.size());for(int i=0;i<visible.size();i++)rows.setItemChecked(i,selected.contains(visible.get(i).id));}
    private void filters(){
        LinearLayout fields=column();Spinner type=new Spinner(this),status=new Spinner(this);
        String[] types={null,"IMAGE","VIDEO"},states={null,"COMPLETED","ACTIVE","FAILED","CANCELED"};
        type.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{tr("所有类型","All types"),tr("图片","Images"),tr("视频","Videos")}));
        status.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{tr("全部状态","All states"),tr("已完成","Completed"),tr("进行中 / 暂停","Active / paused"),tr("失败","Failed"),tr("已取消","Canceled")}));
        type.setSelection(index(types,kind));status.setSelection(index(states,state));fields.addView(type);fields.addView(status);
        new AlertDialog.Builder(this).setTitle(tr("资源筛选","Asset filters")).setView(fields).setNegativeButton(tr("取消","Cancel"),null)
            .setPositiveButton(tr("应用","Apply"),(d,n)->{kind=types[type.getSelectedItemPosition()];state=states[status.getSelectedItemPosition()];load(false);}).show();
    }
    private static int index(String[] values,String value){for(int i=0;i<values.length;i++)if(value==null?values[i]==null:value.equals(values[i]))return i;return 0;}
    private void selectionMenu(){
        final List<String> ids=new ArrayList<>(selected);
        new AlertDialog.Builder(this).setTitle(tr("所选资源：","Selected resources: ")+ids.size()).setItems(new String[]{tr("选择已加载资源","Select loaded resources"),tr("清空选择","Clear selection"),
            tr("预览单个资源","Preview one resource"),tr("批量分类","Set category"),tr("分享实际文件","Share actual files"),tr("导出所选文件（多选 ZIP）","Export selected files (multiple as ZIP)"),tr("删除所选资源 / 任务","Delete selected assets / tasks")},(d,n)->{
                if(n==0){for(XsMediaRepository.Asset asset:visible)if(selected.size()<200)selected.add(asset.id);syncSelection();return;}
                if(n==1){selected.clear();syncSelection();return;}
                if(ids.isEmpty()){toast(tr("请先选择资源","Select resources first"));return;}
                if(n==2){if(ids.size()!=1)toast(tr("一次预览一个资源","Preview one resource at a time"));else preview(ids.get(0));}
                if(n==3)category(ids);else if(n==4)share(ids);else if(n==5)export(ids);else if(n==6)deleteSelected(ids);
            }).show();
    }
    private void assetMenu(XsMediaRepository.Asset asset){
        boolean completed="COMPLETED".equals(asset.state);
        List<String> labels=new ArrayList<>(),ops=new ArrayList<>();
        if(completed){labels.add(tr("预览","Preview"));ops.add("preview");}else{labels.add(tr("重试保存的下载","Retry saved download"));ops.add("retry");labels.add(tr("取消任务","Cancel task"));ops.add("cancel");}
        labels.add(tr("打开来源","Open source"));ops.add("source");
        new AlertDialog.Builder(this).setTitle(stateLabel(asset.state)).setItems(labels.toArray(new String[0]),(d,n)->{
            String op=ops.get(n);if(op.equals("preview"))preview(asset.id);else if(op.equals("retry"))mutate(()->repo().retryStored(library,asset.id));else if(op.equals("cancel"))mutate(()->repo().cancel(library,asset.id));
            else try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(asset.postUrl)).setPackage(getPackageName()));}catch(RuntimeException failure){toast(tr("无法打开来源帖子","Could not open source post"));}
        }).show();
    }
    private void category(List<String> ids){EditText edit=new EditText(this);edit.setSingleLine(true);edit.setContentDescription(tr("资源分类","Asset category"));
        new AlertDialog.Builder(this).setTitle(tr("批量资源分类","Category for selected resources")).setView(edit).setNegativeButton(tr("取消","Cancel"),null)
            .setPositiveButton(tr("保存","Save"),(d,n)->mutate(()->repo().setCategoriesNow(library,ids,edit.getText().toString()))).show();}
    private void deleteSelected(List<String> ids){new AlertDialog.Builder(this).setMessage(tr("删除所选资源和任务？帖子历史、收藏和已分享的接收副本保留。","Delete selected assets and tasks? Post history, favorites and existing receiver copies remain."))
        .setNegativeButton(tr("取消","Cancel"),null).setPositiveButton(tr("删除","Delete"),(d,n)->mutate(()->{repo().deleteManyNow(library,ids);handler.post(()->{selected.removeAll(ids);syncSelection();});})).show();}
    private void mutate(Runnable write){
        try{boolean accepted=XsLibraryWrites.submit(library,()->{try{write.run();handler.post(()->{if(!destroyed)load(false);});}catch(RuntimeException failure){handler.post(()->toast(tr("操作未完成，请检查资源状态并重试。重试下载失败时可重新选择来源。","Action incomplete; check resource state and retry. Reselect the source if a download retry fails.")));}});
            if(!accepted)toast(tr("资料空间已失效或任务繁忙","Library expired or tasks busy"));
        }catch(RuntimeException failure){toast(tr("无法执行此操作","Could not perform this action"));}
    }
    private void share(List<String> ids){
        mutate(()->{try{Intent intent=XsShareCoordinator.assetIntent(this,library,ids);handler.post(()->{if(!destroyed)startActivity(Intent.createChooser(intent,null));});}
            catch(Exception failure){handler.post(()->toast(tr("请选择已完成的 1–10 张图片或一个完整视频。混合资源请分开分享。","Select 1–10 completed images or one complete video. Share mixed media separately.")));}});
    }
    private void export(List<String> ids){
        execute(()->{try{for(String id:ids)repo().completedFile(library,id);XsMediaRepository.Asset first=repo().find(library,ids.get(0));
            ExportRequest request=new ExportRequest(new ArrayList<>(ids),XsLibraryWrites.ticket(library));
            Intent picker=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(ids.size()==1?first.mime:"application/zip")
                .putExtra(Intent.EXTRA_TITLE,ids.size()==1?first.filename:"Xs-selected-media.zip");
            handler.post(()->{if(!destroyed){pendingExport=request;try{startActivityForResult(picker,EXPORT);}catch(RuntimeException unavailable){pendingExport=null;toast(tr("没有可用的文件选择器","No document picker available"));}}});
        }catch(RuntimeException failure){handler.post(()->toast(tr("只可导出已完成的实际文件；多选将打包为 ZIP。","Only completed files can be exported. Multiple files are saved as a ZIP.")));}});
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(request!=EXPORT)return;ExportRequest chosen=pendingExport;pendingExport=null;
        if(result!=RESULT_OK||data==null||data.getData()==null||chosen==null)return;Uri destination=data.getData();
        if(!XsLibraryWrites.isCurrent(chosen.ticket)){toast(tr("资料空间已改变，请重新选择导出","Library context changed; select export again"));return;}
        try {
            boolean queued=XsLibraryWrites.submit(chosen.ticket,()->{
                List<XsMediaRepository.Lease> copies=new ArrayList<>();
                try {
                    long size=0;
                    for(String id:chosen.ids){XsMediaRepository.Asset asset=repo().find(library,id);if(asset==null||!"COMPLETED".equals(asset.state))throw new IllegalStateException();size+=asset.bytes;if(size>200L*1024*1024)throw new IllegalArgumentException();copies.add(repo().lease(library,id));}
                    exportCopies(destination,copies);
                } catch(Exception failure){for(XsMediaRepository.Lease lease:copies)repo().discardLease(lease);handler.post(()->toast(tr("仅可导出已完成的文件，每批上限 200 MiB。","Export requires completed files, up to 200 MiB per batch.")));}
            });
            if(!queued)toast(tr("资料空间已改变，请重新选择导出","Library context changed; select export again"));
        } catch(RuntimeException busy){toast(tr("本地任务繁忙，请重试","Local tasks busy; retry"));}
    }
    private void exportCopies(Uri destination,List<XsMediaRepository.Lease> copies){
        try { io.execute(()->{
            try(OutputStream output=getContentResolver().openOutputStream(destination,"w")){
                if(output==null)throw new java.io.IOException();
                if(copies.size()==1)copy(repo().leaseFile(copies.get(0)),output);
                else try(ZipOutputStream zip=new ZipOutputStream(output)){
                    for(XsMediaRepository.Lease lease:copies){zip.putNextEntry(new ZipEntry(lease.filename));copy(repo().leaseFile(lease),zip);zip.closeEntry();}zip.finish();
                }
                handler.post(()->toast(tr("所选文件已导出","Selected files exported")));
            }catch(Exception failure){handler.post(()->toast(tr("导出未完成；已保存的原始资源保留。","Export incomplete; saved originals remain.")));}
            finally{for(XsMediaRepository.Lease lease:copies)repo().discardLease(lease);}
        });}catch(RuntimeException busy){for(XsMediaRepository.Lease lease:copies)repo().discardLease(lease);handler.post(()->toast(tr("本地任务繁忙，请重试导出","Local tasks busy; retry export")));}
    }
    private static void copy(File source,OutputStream output)throws java.io.IOException{try(FileInputStream input=new FileInputStream(source)){byte[] buffer=new byte[16384];int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);}}
    private void preview(String id){
        execute(()->{try{XsMediaRepository.Asset asset=repo().find(library,id);File file=repo().completedFile(library,id);
            if("video/mp4".equals(asset.mime)){handler.post(()->previewVideo(file));return;}
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),bounds);
            BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=Math.max(1,Math.max(bounds.outWidth,bounds.outHeight)/1600);Bitmap image=BitmapFactory.decodeFile(file.getPath(),options);if(image==null)throw new IllegalStateException();
            handler.post(()->{if(destroyed){image.recycle();return;}ImageView view=new ImageView(this);view.setAdjustViewBounds(true);view.setImageBitmap(image);ScrollView scroll=new ScrollView(this);scroll.addView(view,new ScrollView.LayoutParams(-1,-2));
                new AlertDialog.Builder(this).setView(scroll).setPositiveButton(android.R.string.ok,null).setOnDismissListener(d->image.recycle()).show();});
        }catch(RuntimeException failure){handler.post(()->toast(tr("资源文件尚未完成或不可用","Resource is incomplete or unavailable")));}});
    }
    private void previewVideo(File file){if(destroyed)return;if(currentVideo!=null)currentVideo.stopPlayback();VideoView player=new VideoView(this);currentVideo=player;MediaController controls=new MediaController(this);controls.setAnchorView(player);player.setMediaController(controls);player.setVideoPath(file.getPath());
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(tr("本地完整视频","Local complete video")).setView(player).setPositiveButton(android.R.string.ok,null).create();
        player.setOnPreparedListener(media->{if(!destroyed)player.start();});player.setOnErrorListener((media,what,extra)->{toast(tr("当前设备无法播放；原文件保留","This device could not play the file; the original remains"));return true;});dialog.setOnDismissListener(d->{player.stopPlayback();if(currentVideo==player)currentVideo=null;});dialog.show();player.getLayoutParams().height=Math.max(px(180),getResources().getDisplayMetrics().heightPixels/2);player.requestLayout();}
    private void execute(Runnable task){try{io.execute(task);}catch(RuntimeException busy){reading=false;if(more!=null)more.setEnabled(true);toast(tr("本地任务繁忙，请重试","Local tasks busy; retry"));}}
    private String stateLabel(String value){if("COMPLETED".equals(value))return tr("已完成","Completed");if("FAILED".equals(value))return tr("失败","Failed");if("CANCELED".equals(value))return tr("已取消","Canceled");if("PAUSED".equals(value))return tr("已暂停","Paused");if("WAITING_NETWORK".equals(value))return tr("等待网络","Waiting for network");if("RETRY_WAIT".equals(value))return tr("等待重试","Waiting to retry");return tr("下载中","Downloading");}
    private Button button(LinearLayout parent,String label,Runnable action){Button b=new Button(this);b.setAllCaps(false);b.setText(label);b.setOnClickListener(v->action.run());parent.addView(b,parent.getOrientation()==LinearLayout.HORIZONTAL?weighted():new LinearLayout.LayoutParams(-1,-2));return b;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout line(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}
    private LinearLayout.LayoutParams weighted(){return new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1);}
    private TextView text(String value){TextView view=new TextView(this);view.setText(value);return view;}
    private int px(int dp){return Math.round(dp*getResources().getDisplayMetrics().density);}
    private String tr(String zh,String en){return getResources().getConfiguration().getLocales().get(0).getLanguage().equals("zh")?zh:en;}
    private void toast(String message){if(!destroyed)Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
}

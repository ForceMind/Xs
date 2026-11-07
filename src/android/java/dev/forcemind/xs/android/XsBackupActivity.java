package dev.forcemind.xs.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** User-selected metadata files only. Restoring always creates another local library. */
public final class XsBackupActivity extends Activity {
    private static final String EXTRA_LIBRARY="backup_library";
    private static final int EXPORT=901,IMPORT=902;
    private final ThreadPoolExecutor reader=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(2));
    private String library;private TextView status;private boolean busy,destroyed;
    private XsLibraryWrites.Ticket requestTicket;
    public static Intent intent(Context context,String library){return new Intent(context,XsBackupActivity.class).putExtra(EXTRA_LIBRARY,library);}
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        library=getIntent().getStringExtra(EXTRA_LIBRARY);
        if(library==null||library.isEmpty()||library.length()>256){finish();return;}
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(24,16,24,16);
        TextView title=new TextView(this);title.setText(tr("Xs 资料备份与恢复","Xs library backup & restore"));title.setTextSize(22);root.addView(title);
        TextView scope=new TextView(this);scope.setText(tr(
            "备份当前资料空间的帖子、打开/停留记录、收藏、分类、标签、备注与本人行为元数据。\n不含图片/视频文件、下载任务、网页登录状态或密钥。文件是明文，所选文档应用可能同步它。\n\n恢复先检查完整性，再创建独立资料空间；不会覆盖现有资料或更换默认库。每个文件上限16MiB、20000条记录。校验和不证明文件作者或账号身份。",
            "Backs up this library's posts, open/dwell events, favorites, folders, tags, notes and own-action metadata.\nImages/videos, download tasks, web sessions and keys are excluded. The file is plain text; your selected document app may sync it.\n\nRestore validates the file and creates a separate library, preserving existing libraries and your default. Limit: 16 MiB / 20,000 records. A checksum does not verify authorship or account identity."));root.addView(scope);
        status=new TextView(this);status.setPadding(0,16,0,16);root.addView(status);
        add(root,tr("导出当前资料空间","Export this library"),()->choose(EXPORT));
        add(root,tr("选择备份文件并预览","Choose backup & preview"),()->choose(IMPORT));
        add(root,tr("返回","Back"),this::finish);ScrollView scroll=new ScrollView(this);scroll.addView(root);setContentView(scroll);
    }
    private void choose(int mode) {
        if(busy)return;
        try {
            requestTicket=XsLibraryWrites.ticket(library);
            Intent pick=new Intent(mode==EXPORT?Intent.ACTION_CREATE_DOCUMENT:Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType(mode==EXPORT?"application/octet-stream":"*/*");
            if(mode==EXPORT)pick.putExtra(Intent.EXTRA_TITLE,"xs-library-metadata.xsmeta");
            startActivityForResult(pick,mode);
        }catch(ActivityNotFoundException failure){toast(tr("没有可用的文件选择器","No document picker available"));}
        catch(RuntimeException failure){toast(tr("资料任务繁忙，请重试","Library tasks busy; retry"));}
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if((request!=EXPORT&&request!=IMPORT)||result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();final XsLibraryWrites.Ticket ticket=requestTicket;requestTicket=null;
        if(ticket==null||!XsLibraryWrites.isCurrent(ticket)||!"content".equals(uri.getScheme())) {
            toast(tr("资料状态已改变，请重新选择文件","Library state changed; choose the file again"));return;
        }
        busy=true;status.setText(tr("正在处理所选文件…","Processing the selected file…"));
        if(request==EXPORT) {
            boolean accepted=XsLibraryWrites.submit(ticket,()-> {
                boolean success=false;
                try(OutputStream out=getContentResolver().openOutputStream(uri,"w")) {
                    if(out==null)throw new java.io.IOException();
                    XsLibraryBackup.exportLibrary(getApplicationContext(),library,out);
                    success=XsLibraryWrites.isCurrent(ticket);
                }catch(Exception failure){/* No raw metadata, paths or provider errors in logs. */}
                final boolean saved=success;
                runOnUiThread(()->done(saved?tr("元数据备份已导出；媒体文件未包含。","Metadata exported; media files are excluded."):
                    tr("导出未完成，请删除不完整文件后重试；原资料保留。","Export incomplete. Delete the partial file and retry; the original library is preserved.")));
            });
            if(!accepted)done(tr("资料状态已改变，请重试","Library state changed; retry"));
        }else {
            try { reader.execute(()-> {
                try(InputStream in=getContentResolver().openInputStream(uri)) {
                    if(in==null)throw new java.io.IOException();
                    XsLibraryBackup.ValidatedBackup backup=XsLibraryBackup.inspect(in);
                    runOnUiThread(()-> {
                        if(destroyed)return;
                        if(!XsLibraryWrites.isCurrent(ticket)){done(tr("资料状态已改变，请重新选择文件","Library state changed; choose the file again"));return;}
                        preview(backup,ticket);
                    });
                }catch(Exception failure){runOnUiThread(()->done(tr("文件不受支持、损坏或超过上限；原资料未修改。","Unsupported, damaged or oversized file; existing libraries were not changed.")));}
            }); }catch(RuntimeException busyQueue){done(tr("任务繁忙，请重试","Tasks busy; retry"));}
        }
    }
    private void preview(XsLibraryBackup.ValidatedBackup backup,XsLibraryWrites.Ticket ticket) {
        StringBuilder counts=new StringBuilder();
        counts.append(tr("来源名称：","Source label: ")).append(backup.label()).append('\n');
        counts.append(tr("文件Schema / 恢复Schema：","File schema / restore schema: "))
            .append(backup.sourceSchemaVersion()).append(" / ").append(backup.schemaVersion()).append('\n');
        for(Map.Entry<String,Integer> entry:backup.counts().entrySet())if(entry.getValue()>0)
            counts.append(tableName(entry.getKey())).append(": ").append(entry.getValue()).append('\n');
        counts.append(tr("\n仅创建独立资料空间。媒体文件未包含，已有资料不合并、不覆盖。备份中的行为来源只是原记录声明，不是当前账号身份验证。",
            "\nCreates another library only. Media files are excluded; existing data is not merged or replaced. Action provenance in a backup is a stored claim, not current account verification."));
        status.setText(tr("文件已验证，等待你的选择。","File validated; waiting for your choice."));
        new AlertDialog.Builder(this).setTitle(tr("恢复到新的资料空间？","Restore into a new library?"))
            .setMessage(counts).setNegativeButton(android.R.string.cancel,(d,w)->done(tr("已取消；未恢复资料。","Canceled; no data restored.")))
            .setOnCancelListener(d->done(tr("已取消；未恢复资料。","Canceled; no data restored.")))
            .setPositiveButton(tr("创建独立资料空间","Create separate library"),(d,w)-> {
                boolean accepted=XsLibraryWrites.submit(ticket,()-> {
                    try {
                        String restored=XsLibraryBackup.restoreNewLibrary(getApplicationContext(),backup,null,()->XsLibraryWrites.isCurrent(ticket));
                        runOnUiThread(()-> {
                            if(destroyed)return;done(tr("已恢复到独立资料空间，默认库保持原样。","Restored separately; your default library is unchanged."));
                            new AlertDialog.Builder(this).setMessage(tr("打开恢复的资料空间？","Open the restored library?"))
                                .setNegativeButton(android.R.string.cancel,null)
                                .setPositiveButton(android.R.string.ok,(dialog,which)->startActivity(new Intent(this,XsHistoryActivity.class)
                                    .putExtra(XsHistoryActivity.EXTRA_LIBRARY,restored))).show();
                        });
                    }catch(Exception failure){runOnUiThread(()->done(tr("恢复未完成；原资料保留。","Restore incomplete; existing libraries are preserved.")));}
                });
                if(!accepted)done(tr("资料状态已改变，请重新选择文件","Library state changed; choose the file again"));
            }).show();
    }
    private String tableName(String table) {
        switch(table){case "posts":return tr("帖子","Posts");case "categories":return tr("分类","Folders");
            case "history_events":return tr("打开/停留事件","Open/dwell events");case "user_actions":return tr("旧行为索引","Legacy actions");
            case "favorites":return tr("收藏","Favorites");case "post_categories":return tr("分类关联","Folder links");case "post_tags":return tr("标签","Tags");
            case "own_actions":return tr("本人行为记录","Own-action records");default:return tr("行为事件","Action events");}
    }
    private void done(String message){if(destroyed)return;busy=false;status.setText(message);toast(message);}
    private void toast(String message){if(!destroyed)Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    private void add(LinearLayout root,String title,Runnable action){Button button=new Button(this);button.setAllCaps(false);button.setText(title);button.setOnClickListener(v->action.run());root.addView(button);}
    private String tr(String chinese,String english){return getResources().getConfiguration().getLocales().get(0).getLanguage().equals("zh")?chinese:english;}
    @Override protected void onDestroy(){destroyed=true;reader.shutdownNow();super.onDestroy();}
}

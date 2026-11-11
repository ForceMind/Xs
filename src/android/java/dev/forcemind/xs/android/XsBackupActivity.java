package dev.forcemind.xs.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Explicit metadata and password-protected resource portability through user-selected documents. */
public final class XsBackupActivity extends Activity {
    private static final String EXTRA_LIBRARY="backup_library";
    private static final int EXPORT=901,IMPORT=902,EXPORT_FULL=903,IMPORT_FULL=904;
    private final ThreadPoolExecutor reader=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(2));
    private String library;private TextView status;private boolean busy;private volatile boolean destroyed;
    private XsBackupRequest pendingRequest;
    private char[] pendingPassword;private volatile char[] processingPassword;
    private XsFullBackup.Prepared fullPreview;
    private AlertDialog passwordDialog;
    public static Intent intent(Context context,String library){return new Intent(context,XsBackupActivity.class).putExtra(EXTRA_LIBRARY,library);}
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        library=getIntent().getStringExtra(EXTRA_LIBRARY);
        Object retained=getLastNonConfigurationInstance();
        if(library==null||library.isEmpty()||library.length()>256){
            if(retained instanceof XsBackupRequest)((XsBackupRequest)retained).close();
            finish();return;
        }
        if(retained instanceof XsBackupRequest) {
            XsBackupRequest request=(XsBackupRequest)retained;
            if(request.isCurrent(library)){pendingRequest=request;busy=true;}
            else request.close();
        }
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(24,16,24,16);
        TextView title=new TextView(this);title.setText(tr("Xs 资料备份与恢复","Xs library backup & restore"));title.setTextSize(22);root.addView(title);
        TextView scope=new TextView(this);scope.setText(tr(
            "加密完整备份包含当前资料空间的元数据、正文版本与已完成的图片/视频文件。请妥善保存你设置的口令；Xs 无法找回口令。上限 512 MiB、500 个资源，超过时整项失败。\n明文元数据备份包含正文版本，不含媒体，限 16 MiB / 20000 条。\n\n两种方式都不含下载任务、网页登录状态或密钥。所选文档应用可能同步文件。恢复先验证，再创建独立资料空间；不会覆盖现有资料或更换默认库。备份不证明文件作者或当前账号身份。",
            "An encrypted full backup includes this library's metadata, snapshot versions and completed image/video files. Keep your chosen password: Xs cannot recover it. Limit: 512 MiB / 500 resources; an oversized export fails as a whole.\nPlain metadata backups include snapshot versions, exclude media and are limited to 16 MiB / 20,000 records.\n\nNeither format includes download tasks, web sessions or keys. Your selected document app may sync the file. Restore validates it before creating a separate library; existing libraries and your default are preserved. A backup does not verify authorship or current account identity."));root.addView(scope);
        status=new TextView(this);status.setPadding(0,16,0,16);root.addView(status);
        add(root,tr("导出加密完整备份","Export encrypted full backup"),()->choose(EXPORT_FULL));
        add(root,tr("恢复加密完整备份","Restore encrypted full backup"),()->choose(IMPORT_FULL));
        add(root,tr("导出明文元数据","Export plain metadata"),()->choose(EXPORT));
        add(root,tr("选择明文元数据并预览","Choose plain metadata & preview"),()->choose(IMPORT));
        add(root,tr("返回","Back"),this::finish);ScrollView scroll=new ScrollView(this);scroll.addView(root);setContentView(scroll);
    }
    private void choose(int mode) {
        if(busy)return;
        if(mode==EXPORT_FULL||mode==IMPORT_FULL){password(mode);return;}
        picker(mode);
    }
    private void picker(int mode) {
        try {
            if(pendingRequest!=null)return;
            XsBackupRequest request=new XsBackupRequest(library,mode,pendingPassword);
            pendingPassword=null;pendingRequest=request;busy=true;
            boolean exporting=mode==EXPORT||mode==EXPORT_FULL;
            Intent pick=new Intent(exporting?Intent.ACTION_CREATE_DOCUMENT:Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType(exporting?"application/octet-stream":"*/*");
            if(exporting)pick.putExtra(Intent.EXTRA_TITLE,mode==EXPORT_FULL?"xs-library-encrypted.xsbackup":"xs-library-metadata.xsmeta");
            startActivityForResult(pick,request.requestCode);
        }catch(ActivityNotFoundException failure){clearRequest();wipePassword();busy=false;toast(tr("没有可用的文件选择器","No document picker available"));}
        catch(RuntimeException failure){clearRequest();wipePassword();busy=false;toast(tr("资料任务繁忙，请重试","Library tasks busy; retry"));}
    }
    private void password(int mode) {
        busy=true;LinearLayout fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);fields.setPadding(32,8,32,8);
        TextView explanation=new TextView(this);explanation.setText(mode==EXPORT_FULL?tr("设置至少 8 字符的备份口令；遗失后无法恢复。","Choose a backup password of at least 8 characters. A lost password cannot be recovered."):tr("输入创建此备份时设置的口令。","Enter the password chosen when this backup was created."));fields.addView(explanation);
        EditText first=passwordField(tr("备份口令","Backup password"));fields.addView(first);
        EditText again=mode==EXPORT_FULL?passwordField(tr("再次输入口令","Repeat password")):null;if(again!=null)fields.addView(again);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(tr("备份口令","Backup password")).setView(fields)
            .setNegativeButton(android.R.string.cancel,(d,w)->{first.getText().clear();if(again!=null)again.getText().clear();busy=false;})
            .setOnCancelListener(d->{first.getText().clear();if(again!=null)again.getText().clear();busy=false;})
            .setPositiveButton(android.R.string.ok,null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            char[] chosen=characters(first),repeated=again==null?null:characters(again);
            if(chosen.length<8||chosen.length>1024||(repeated!=null&&!Arrays.equals(chosen,repeated))){Arrays.fill(chosen,'\0');if(repeated!=null)Arrays.fill(repeated,'\0');toast(tr("口令需 8–1024 字符，两次输入须相同。","Use 8–1024 characters and matching passwords."));return;}
            wipePassword();pendingPassword=chosen;if(repeated!=null)Arrays.fill(repeated,'\0');first.getText().clear();if(again!=null)again.getText().clear();busy=false;dialog.dismiss();picker(mode);
        }));dialog.setOnDismissListener(d->{first.getText().clear();if(again!=null)again.getText().clear();if(passwordDialog==dialog)passwordDialog=null;});
        passwordDialog=dialog;dialog.show();dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    private EditText passwordField(String hint) {
        EditText field=new EditText(this);field.setHint(hint);field.setSingleLine(true);field.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1024)});field.setSaveEnabled(false);field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);return field;
    }
    private static char[] characters(EditText field){char[] chars=new char[field.length()];TextUtils.getChars(field.getText(),0,chars.length,chars,0);return chars;}
    private void wipePassword(){if(pendingPassword!=null){Arrays.fill(pendingPassword,'\0');pendingPassword=null;}}
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        // A late or duplicate result must not cancel/consume another pending operation.
        XsBackupRequest selected=pendingRequest;
        if(selected==null||!selected.matches(request))return;
        pendingRequest=null;
        if(result!=RESULT_OK||data==null||data.getData()==null){selected.close();wipePassword();busy=false;return;}
        Uri uri=data.getData();final XsLibraryWrites.Ticket ticket=selected.ticket;final int mode=selected.mode;
        if(!selected.isCurrent(library)||!"content".equals(uri.getScheme())) {
            selected.close();wipePassword();busy=false;toast(tr("资料状态已改变，请重新选择文件","Library state changed; choose the file again"));return;
        }
        char[] password=selected.takePassword();selected.close();
        busy=true;status.setText(tr("正在处理所选文件…","Processing the selected file…"));
        if(mode==EXPORT_FULL||mode==IMPORT_FULL){fullFile(mode,uri,ticket,password);return;}
        if(password!=null)Arrays.fill(password,'\0');
        if(mode==EXPORT) {
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
    private void fullFile(int mode,Uri uri,XsLibraryWrites.Ticket ticket,final char[] password) {
        if(password==null){done(tr("请重新输入备份口令并选择文件。","Enter the backup password and choose the file again."));return;}
        processingPassword=password;
        try {reader.execute(()-> {
            try {
                if(mode==EXPORT_FULL) {
                    try(OutputStream out=getContentResolver().openOutputStream(uri,"w")) {
                        if(out==null)throw new java.io.IOException();XsFullBackup.exportEncrypted(getApplicationContext(),library,ticket,out,password);
                    }
                    runOnUiThread(()->done(tr("加密完整备份已导出，包含预算内全部已完成资源。","Encrypted full backup exported with every completed resource within its limits.")));
                }else {
                    XsFullBackup.Prepared prepared;
                    try(InputStream in=getContentResolver().openInputStream(uri)) {
                        if(in==null)throw new java.io.IOException();prepared=XsFullBackup.inspectEncrypted(getApplicationContext(),library,ticket,in,password);
                    }
                    runOnUiThread(()->{if(destroyed){closeFull(prepared);return;}fullPreview=prepared;previewFull(prepared);});
                }
            }catch(Exception failure){runOnUiThread(()->done(mode==EXPORT_FULL?
                tr("导出未完成。请删除不完整文件后重试；原资料保留。","Export incomplete. Delete the partial file and retry; the source library is preserved."):
                tr("口令错误、文件损坏、格式不受支持或空间不足；原资料未修改。","Wrong password, damaged or unsupported file, or insufficient space; existing libraries were not changed.")));}
            finally{Arrays.fill(password,'\0');if(processingPassword==password)processingPassword=null;}
        });}catch(RuntimeException rejected){Arrays.fill(password,'\0');processingPassword=null;done(tr("任务繁忙，请重试。","Tasks busy; retry."));}
    }
    private void previewFull(XsFullBackup.Prepared prepared) {
        StringBuilder details=new StringBuilder(tr("来源名称：","Source label: ")).append(prepared.label()).append('\n');
        for(Map.Entry<String,Integer> entry:prepared.counts().entrySet())if(entry.getValue()>0)details.append(tableName(entry.getKey())).append(": ").append(entry.getValue()).append('\n');
        details.append(tr("已验证资源：","Verified resources: ")).append(prepared.assetCount()).append('\n')
            .append(tr("资源字节：","Resource bytes: ")).append(prepared.assetBytes()).append('\n')
            .append(tr("\n文件口令认证、资源校验与类型检查已完成。确认后复制到新的独立资料空间；原资料及默认库保留。不会恢复网页会话或下载任务。本人行为来源只是原记录声明。",
                "\nPassword authentication, resource checksums and type checks have completed. Confirmation copies them into a separate library and preserves the sources and default. Web sessions and download tasks are excluded. Own-action provenance is the original stored claim."));
        status.setText(tr("完整备份已验证，等待你的确认。","Full backup validated; waiting for your confirmation."));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(tr("恢复加密完整备份？","Restore encrypted full backup?"))
            .setMessage(details).setNegativeButton(android.R.string.cancel,(d,w)->cancelFull(prepared))
            .setOnCancelListener(d->cancelFull(prepared)).setPositiveButton(tr("创建独立资料空间","Create separate library"),(d,w)-> {
                status.setText(tr("正在复制资源并创建独立资料空间…","Copying resources and creating a separate library…"));
                try{reader.execute(()-> {
                    try {
                        String restored=XsFullBackup.restoreNewLibrary(getApplicationContext(),prepared,null);
                        runOnUiThread(()-> {
                            if(fullPreview==prepared)fullPreview=null;if(destroyed)return;
                            done(tr("完整资料已恢复到独立空间，默认库保持原样。","Full library restored separately; your default is unchanged."));
                            new AlertDialog.Builder(this).setMessage(tr("打开恢复的资料空间？","Open the restored library?"))
                                .setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok,(next,which)->
                                    startActivity(new Intent(this,XsHistoryActivity.class).putExtra(XsHistoryActivity.EXTRA_LIBRARY,restored))).show();
                        });
                    }catch(Exception failure){runOnUiThread(()->{if(fullPreview==prepared)fullPreview=null;done(tr("恢复未完成；原资料保留，请重新选择文件。","Restore incomplete; existing libraries are preserved. Choose the file again."));});}
                });}catch(RuntimeException rejected){cancelFull(prepared);}
            }).create();dialog.show();dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    private void cancelFull(XsFullBackup.Prepared prepared){boolean cleared=closeFull(prepared);if(fullPreview==prepared)fullPreview=null;done(cleared?tr("已取消，暂存文件已清理。","Canceled; private staging cleared."):tr("已取消；私有暂存将在下次启动重试清理。","Canceled; private staging cleanup will retry at next start."));}
    private static boolean closeFull(XsFullBackup.Prepared prepared){try{prepared.close();return true;}catch(java.io.IOException ignored){return false;}}
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
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(tr("恢复到新的资料空间？","Restore into a new library?"))
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
                                .setPositiveButton(android.R.string.ok,(openResult,which)->startActivity(new Intent(this,XsHistoryActivity.class)
                                    .putExtra(XsHistoryActivity.EXTRA_LIBRARY,restored))).show();
                        });
                    }catch(Exception failure){runOnUiThread(()->done(tr("恢复未完成；原资料保留。","Restore incomplete; existing libraries are preserved.")));}
                });
                if(!accepted)done(tr("资料状态已改变，请重新选择文件","Library state changed; choose the file again"));
            }).create();dialog.show();dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    private String tableName(String table) {
        switch(table){case "posts":return tr("帖子","Posts");case "snapshot_versions":return tr("正文版本","Snapshot versions");case "categories":return tr("分类","Folders");
            case "history_events":return tr("打开/停留事件","Open/dwell events");case "user_actions":return tr("旧行为索引","Legacy actions");
            case "favorites":return tr("收藏","Favorites");case "post_categories":return tr("分类关联","Folder links");case "post_tags":return tr("标签","Tags");
            case "own_actions":return tr("本人行为记录","Own-action records");default:return tr("行为事件","Action events");}
    }
    private void clearRequest(){if(pendingRequest!=null){pendingRequest.close();pendingRequest=null;}}
    @Override public Object onRetainNonConfigurationInstance() {
        // The old Activity detaches before destruction. Framework saved state never sees
        // the ticket or password; a killed process cannot reconstruct this authorization.
        XsBackupRequest retained=pendingRequest;pendingRequest=null;return retained;
    }
    private void done(String message){if(destroyed)return;busy=false;status.setText(message);toast(message);}
    private void toast(String message){if(!destroyed)Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    private void add(LinearLayout root,String title,Runnable action){Button button=new Button(this);button.setAllCaps(false);button.setText(title);button.setOnClickListener(v->action.run());root.addView(button);}
    private String tr(String chinese,String english){return getResources().getConfiguration().getLocales().get(0).getLanguage().equals("zh")?chinese:english;}
    @Override protected void onDestroy(){destroyed=true;clearRequest();wipePassword();char[] active=processingPassword;if(active!=null)Arrays.fill(active,'\0');if(passwordDialog!=null)passwordDialog.dismiss();if(fullPreview!=null)closeFull(fullPreview);reader.shutdownNow();super.onDestroy();}
}

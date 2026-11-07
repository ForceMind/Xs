package dev.forcemind.xs.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Clear scopes are separate explicit operations; no session/cache files are exposed here. */
public final class XsLibraryManagementActivity extends Activity {
    public static final int ID_DELETE=0x8130,ID_CONFIRM=0x8131,ID_STATUS=0x8132;
    private static final String LIBRARY="library_id";
    private final ThreadPoolExecutor io=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(2));
    private String library,label;private TextView status;private Button erase;private boolean busy,destroyed;
    public static Intent intent(Context context,String library){return new Intent(context,XsLibraryManagementActivity.class).putExtra(LIBRARY,library);}
    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        library=getIntent().getStringExtra(LIBRARY);if(library==null||library.isEmpty()||library.length()>256){finish();return;}
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(24,16,24,16);
        TextView title=new TextView(this);title.setText(tr("管理本机资料空间","Manage local library"));title.setTextSize(22);root.addView(title);
        TextView explanation=new TextView(this);explanation.setText(tr("清历史只清浏览事件。删除资源只删所选文件。完整删除会移除此空间的帖子、所有快照、历史、收藏、标签、备注、分类、本人行为和下载文件；其他资料空间与 X 网页会话保留。已有分享副本保留到接收租约到期。",
            "Clearing history removes browsing events. Deleting assets removes chosen files. Whole-library deletion removes this library's posts, all snapshots, history, favorites, tags, notes, folders, own activity and downloads. Other libraries and X sessions remain. Existing share copies remain until their receiver lease expires."));root.addView(explanation);
        status=new TextView(this);status.setId(ID_STATUS);root.addView(status);
        add(root,tr("备份 / 恢复","Backup / restore"),()->startActivity(XsBackupActivity.intent(this,library)));
        erase=add(root,tr("完整删除此资料空间","Delete this whole library"),this::confirm);erase.setId(ID_DELETE);erase.setEnabled(false);
        add(root,tr("返回","Back"),this::finish);
        ScrollView scroll=new ScrollView(this);scroll.addView(root);setContentView(scroll);load();
    }
    private void load(){io.execute(()->{
        String found=null;try(XsArchiveDatabase archive=new XsArchiveDatabase(getApplicationContext());Cursor rows=archive.libraries()){
            while(rows.moveToNext())if(library.equals(rows.getString(3))){found=rows.getString(1);break;}
        }
        catch(RuntimeException unavailable){runOnUiThread(()->{if(!destroyed)status.setText(tr("资料空间状态读取失败，请重新进入","Could not read library state; reopen this page"));});return;}
        final String name=found;runOnUiThread(()->{
            if(destroyed)return;label=name;boolean pending=XsDeletedLibraries.pending(this).contains(library);
            status.setText(name!=null?name:pending?tr("删除未完成；此空间已停用，可重试清理。","Deletion incomplete; this scope is disabled. Retry cleanup."):tr("资料空间不存在或已删除。","Library does not exist or was deleted."));
            erase.setEnabled(name!=null||pending);if(pending)erase.setText(tr("重试删除清理","Retry deletion cleanup"));
        });
    });}
    private void confirm(){
        if(busy)return;EditText confirm=new EditText(this);confirm.setId(ID_CONFIRM);confirm.setSingleLine(true);
        String word=tr("删除","DELETE");confirm.setHint(tr("输入“删除”确认","Type DELETE to confirm"));confirm.setContentDescription(tr("输入“删除”确认","Type DELETE to confirm"));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(tr("完整删除：","Delete whole library: ")+(label==null?tr("待清理空间","Pending scope"):label))
            .setMessage(tr("此操作清除该空间的所有资料和下载。若为默认库，将建立新的空白本机库。请先完成需要的备份。","This erases this library's data and downloads. Deleting the default creates a new blank local library. Complete any backup you need first."))
            .setView(confirm).setNegativeButton(tr("取消","Cancel"),null).setPositiveButton(tr("完整删除","Delete whole library"),null).create();
        dialog.setOnShowListener(d->{Button positive=dialog.getButton(AlertDialog.BUTTON_POSITIVE);positive.setEnabled(false);
            confirm.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){positive.setEnabled(word.contentEquals(s));}public void afterTextChanged(Editable e){}});
            positive.setOnClickListener(v->{dialog.dismiss();busy=true;erase.setEnabled(false);status.setText(tr("正在删除…","Deleting…"));
                XsLibraryDeletion.delete(this,library,(ok,replacement)->{if(destroyed)return;busy=false;
                    status.setText(ok?tr("资料空间已删除；其他空间和网页会话保留。","Library deleted; other libraries and web sessions remain."):
                        (XsDeletedLibraries.isDeleted(this,library)?tr("删除未完成。此空间已停用，可重试清理。","Deletion incomplete. This scope is disabled; retry cleanup."):tr("未能持久保存删除请求，资料空间尚未删除，请重试。","Could not persist the deletion request. Library deletion has not started; retry.")));
                    erase.setEnabled(!ok);erase.setText(tr("重试删除清理","Retry deletion cleanup"));
                });});});dialog.show();
    }
    private Button add(LinearLayout root,String label,Runnable action){Button button=new Button(this);button.setAllCaps(false);button.setText(label);button.setOnClickListener(v->action.run());root.addView(button);return button;}
    private String tr(String zh,String en){return getResources().getConfiguration().getLocales().get(0).getLanguage().equals("zh")?zh:en;}
    @Override protected void onDestroy(){destroyed=true;io.shutdown();super.onDestroy();}
}

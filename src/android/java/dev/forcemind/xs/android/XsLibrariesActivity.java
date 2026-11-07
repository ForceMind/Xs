package dev.forcemind.xs.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Preview and explicitly copy selected metadata into a separate app-owned library. */
public final class XsLibrariesActivity extends Activity {
    private final ThreadPoolExecutor reader=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(2));
    private final Set<String> selected=new LinkedHashSet<>();
    private final Map<String,String> sourceLabels=new LinkedHashMap<>();
    private final List<CheckBox> choices=new ArrayList<>();
    private LinearLayout sources;private EditText label;private TextView status;
    private Button preview,reload;private boolean busy,destroyed;
    private static final class Source {
        final String scope,label;
        Source(String scope,String label){this.scope=scope;this.label=label;}
    }
    public static Intent intent(Context context){return new Intent(context,XsLibrariesActivity.class);}
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(24,16,24,16);
        TextView title=new TextView(this);title.setText(tr("合并到独立资料空间","Combine into a separate library"));title.setTextSize(22);root.addView(title);
        TextView scope=new TextView(this);scope.setText(tr(
            "选择 2–4 个已有资料空间，检查预览后再创建新空间。复制帖子、打开/停留事件、收藏、分类、标签、备注与本人行为元数据。来源保留，不删除或覆盖，不更换默认资料库。\n图片/视频文件、下载任务与浏览会话不包含在内。所选元数据合计上限 8 MiB、20000 条记录；详细合并规则在预览中列明。",
            "Select 2–4 existing libraries, review the preview, then create a separate library. Copies posts, open/dwell events, favorites, folders, tags, notes and own-action metadata. Sources are preserved and your default library stays unchanged.\nImages/videos, download tasks and browser sessions are excluded. Selected metadata is limited to 8 MiB / 20,000 records; the preview explains merge rules."));root.addView(scope);
        label=new EditText(this);label.setSingleLine(true);label.setFilters(new InputFilter[]{new InputFilter.LengthFilter(120)});
        label.setHint(tr("新空间名称（最多 120 字符）","New library name (up to 120 characters)"));root.addView(label);
        sources=new LinearLayout(this);sources.setOrientation(LinearLayout.VERTICAL);root.addView(sources);
        status=new TextView(this);status.setPadding(0,16,0,16);root.addView(status);
        reload=add(root,tr("刷新来源","Refresh sources"),this::load);
        preview=add(root,tr("检查并预览","Check & preview"),this::prepare);
        add(root,tr("返回","Back"),this::finish);
        ScrollView scroll=new ScrollView(this);scroll.addView(root);setContentView(scroll);load();
    }
    private void load(){load(null);}
    private void load(String successMessage) {
        if(busy)return;working(tr("正在读取资料空间…","Loading libraries…"));
        execute(()-> {
            List<Source> found=new ArrayList<>();
            try(XsArchiveDatabase archive=new XsArchiveDatabase(getApplicationContext());Cursor rows=archive.libraries()) {
                Set<String> scopes=new LinkedHashSet<>();
                while(rows.moveToNext()) {
                    String storage=rows.getString(3),name=rows.getString(1);
                    if(storage==null||storage.isEmpty()||storage.length()>256||!scopes.add(storage))continue;
                    if(found.size()>=128)throw new IllegalStateException();
                    found.add(new Source(storage,name==null||name.isEmpty()?tr("未命名资料空间","Unnamed library"):name));
                }
                runOnUiThread(()-> {
                    if(destroyed)return;sources.removeAllViews();selected.clear();choices.clear();sourceLabels.clear();
                    for(Source source:found) {
                        sourceLabels.put(source.scope,source.label);
                        CheckBox choice=new CheckBox(this);choice.setText(source.label);
                        choice.setOnCheckedChangeListener((button,checked)-> {
                            if(checked&&selected.size()>=4){button.setChecked(false);toast(tr("最多选择 4 个来源","Select up to 4 sources"));return;}
                            if(checked)selected.add(source.scope);else selected.remove(source.scope);
                        });choices.add(choice);sources.addView(choice);
                    }
                    done(successMessage!=null?successMessage:found.size()<2?tr("至少需要两个已有资料空间；来源不会被删除。","At least two existing libraries are required. Sources will be preserved."):
                        tr("选择 2–4 个来源并填写新空间名称。","Select 2–4 sources and name the new library."));
                });
            }catch(Exception failure){runOnUiThread(()->done(successMessage!=null?
                tr("新空间已撤销，来源保留；列表刷新未完成，请刷新。","The new library was undone and sources preserved. Refresh the list again."):
                tr("未能读取资料空间，请重试。","Could not load libraries; retry.")));}
        });
    }
    private void prepare() {
        if(busy)return;
        final String name=label.getText().toString().trim();
        if(selected.size()<2||selected.size()>4||name.isEmpty()||name.length()>120){toast(tr("请选择 2–4 个来源并填写不超过 120 字符的名称。","Select 2–4 sources and enter a name up to 120 characters."));return;}
        final List<String> scopes=new ArrayList<>(selected),names=new ArrayList<>();
        for(String scope:scopes)names.add(sourceLabels.get(scope));
        working(tr("正在检查所选资料…","Checking selected metadata…"));
        execute(()-> {
            try {
                XsLibraryConsolidation.Prepared prepared=XsLibraryConsolidation.prepare(getApplicationContext(),scopes,name);
                runOnUiThread(()-> {if(!destroyed)confirm(prepared,names);});
            }catch(Exception failure){runOnUiThread(()->done(tr("预览未完成：可能超过上限、资料改变或存在冲突。来源保留；请用较小批次，或让有冲突的来源保持分开，再重新预览。","Preview incomplete: sources may exceed limits, have changed or conflict. They are preserved. Use a smaller batch or keep conflicting sources separate, then preview again.")));}
        });
    }
    private void confirm(XsLibraryConsolidation.Prepared prepared,List<String> names) {
        StringBuilder summary=new StringBuilder(tr("新空间：","New library: ")).append(prepared.label()).append('\n');
        summary.append(tr("所选来源顺序：\n","Selected source order:\n"));
        for(int i=0;i<names.size();i++)summary.append(i+1).append(". ").append(names.get(i)).append('\n');
        for(Map.Entry<String,Integer> count:prepared.counts().entrySet())if(count.getValue()!=null&&count.getValue()>0)
            summary.append(tableName(count.getKey())).append(": ").append(count.getValue()).append('\n');
        summary.append(tr(
            "\n同一帖子保留较长正文；作者和发布时间按来源顺序取首个已知值。打开次数相加，每条历史事件都保留。同名分类保持独立，标签按帖子去重。单份备注原样保留，多份备注按来源顺序标明来源。\n旧行为的同一帖子、类型和时间对应不同来源，或合并备注超过 8000 字符时会中止，请让冲突来源保持分开。所选元数据合计最多 8 MiB、20000 条记录。\n\n提交的是此次预览的快照；之后的普通编辑不会自动补入。要包含后续改动，请取消并重新预览。清除来源空间会使预览失效。\n\n确认后只创建一个独立资料空间。所有来源和默认库保留；不复制媒体文件或浏览会话。本人行为来源仍是原记录声明，不验证当前 X 账号身份。",
            "\nFor the same post, the longer body is retained; author and publication time use the first known value in source order. Open counts are added and every history event is retained. Same-named folders remain separate; tags are deduplicated per post. A single note stays unchanged; multiple notes include their sources in source order.\nLegacy actions for the same post/type/time with conflicting sources, or combined notes over 8,000 characters, stop the operation. Keep conflicting sources separate. Selected metadata is limited to 8 MiB / 20,000 records.\n\nThis commits the snapshot shown in this preview. Later ordinary edits are not added automatically. Cancel and preview again to include subsequent changes. Clearing a source library invalidates the preview.\n\nConfirmation creates a separate library only. All sources and the default library are preserved. Media files and browser sessions are excluded. Own-action provenance remains the original stored claim and does not verify the current X account."));
        status.setText(tr("检查完成，等待你的确认。","Checked; waiting for your confirmation."));
        new AlertDialog.Builder(this).setTitle(tr("创建独立资料空间？","Create a separate library?"))
            .setMessage(summary).setNegativeButton(android.R.string.cancel,(dialog,which)->done(tr("已取消；未创建资料空间。","Canceled; no library created.")))
            .setOnCancelListener(dialog->done(tr("已取消；未创建资料空间。","Canceled; no library created.")))
            .setPositiveButton(tr("确认创建","Create library"),(dialog,which)->commit(prepared)).show();
    }
    private void commit(XsLibraryConsolidation.Prepared prepared) {
        working(tr("正在创建独立资料空间…","Creating the separate library…"));
        execute(()-> {
            try {
                String created=XsLibraryConsolidation.commit(getApplicationContext(),prepared);
                runOnUiThread(()-> {
                    if(destroyed)return;done(tr("已创建独立资料空间；来源与默认库保留。","Separate library created; sources and default preserved."));
                    new AlertDialog.Builder(this).setMessage(tr("打开新资料空间？","Open the new library?"))
                        .setNeutralButton(tr("撤销新空间","Undo new library"),(dialog,which)->confirmRollback(prepared))
                        .setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok,(dialog,which)->
                            startActivity(new Intent(this,XsHistoryActivity.class).putExtra(XsHistoryActivity.EXTRA_LIBRARY,created))).show();
                });
            }catch(Exception failure){runOnUiThread(()->done(tr("创建未完成；来源保留。请重新预览，或用较小批次、让冲突来源保持分开。","Creation incomplete; sources are preserved. Preview again, use a smaller batch or keep conflicting sources separate.")));}
        });
    }
    private void confirmRollback(XsLibraryConsolidation.Prepared prepared) {
        new AlertDialog.Builder(this).setTitle(tr("撤销刚创建的新空间？","Undo the newly created library?"))
            .setMessage(tr("只删除刚刚创建的结果，所有来源和默认库保留。如果新空间已有后续修改或媒体文件，会拒绝撤销。",
                "Deletes only the result just created; all sources and your default library are preserved. Undo is refused if the new library has later changes or media files."))
            .setNegativeButton(android.R.string.cancel,null)
            .setPositiveButton(tr("确认撤销","Undo new library"),(dialog,which)-> {
                working(tr("正在撤销新空间…","Undoing the new library…"));
                execute(()-> {
                    try {
                        XsLibraryConsolidation.rollback(getApplicationContext(),prepared);
                        runOnUiThread(()-> {if(!destroyed){done(tr("新空间已撤销，来源和默认库保留。","New library undone; sources and default preserved."));
                            load(tr("新空间已撤销，来源和默认库保留。","New library undone; sources and default preserved."));}});
                    }catch(Exception failure){runOnUiThread(()->done(tr("无法安全撤销：新空间可能已有修改或媒体，或删除未完成。请保留并检查结果；来源不受影响。",
                        "Undo could not complete safely: the result may have changes or media, or deletion did not complete. Keep and inspect the result; source libraries are unaffected.")));}
                });
            }).show();
    }
    private void execute(Runnable task){try{reader.execute(task);}catch(RuntimeException rejected){done(tr("任务繁忙，请重试。","Tasks busy; retry."));}}
    private void working(String message){busy=true;status.setText(message);preview.setEnabled(false);reload.setEnabled(false);label.setEnabled(false);for(CheckBox choice:choices)choice.setEnabled(false);}
    private void done(String message){if(destroyed)return;busy=false;status.setText(message);preview.setEnabled(true);reload.setEnabled(true);label.setEnabled(true);for(CheckBox choice:choices)choice.setEnabled(true);}
    private Button add(LinearLayout root,String text,Runnable action){Button button=new Button(this);button.setAllCaps(false);button.setText(text);button.setOnClickListener(view->action.run());root.addView(button);return button;}
    private String tableName(String table){
        switch(table){case "posts":return tr("帖子","Posts");case "categories":return tr("分类","Folders");
            case "history_events":return tr("打开/停留事件","Open/dwell events");case "user_actions":return tr("旧行为索引","Legacy actions");
            case "favorites":return tr("收藏","Favorites");case "post_categories":return tr("分类关联","Folder links");case "post_tags":return tr("标签","Tags");
            case "own_actions":return tr("本人行为记录","Own-action records");case "own_action_events":return tr("行为事件","Action events");default:return tr("记录","Records");}
    }
    private void toast(String message){if(!destroyed)Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    private String tr(String chinese,String english){return getResources().getConfiguration().getLocales().get(0).getLanguage().equals("zh")?chinese:english;}
    @Override protected void onDestroy(){destroyed=true;reader.shutdownNow();super.onDestroy();}
}

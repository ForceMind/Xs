package dev.forcemind.xs.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Offline, explicitly temporary appearances. Previewing never creates a browsing event. */
public final class XsRecentTimelineActivity extends Activity {
    public static final int ID_QUERY=0x8601, ID_RESULTS=0x8602, ID_SETTINGS=0x8603,
        ID_BATCHES=0x8604, ID_STATUS=0x8605, ID_PREVIOUS=0x8606, ID_NEXT=0x8607,
        ID_CLEAR=0x8608, ID_SELECT=0x8609, ID_SAVE=0x860a, ID_FAVORITE=0x860b,
        ID_ENABLED=0x860c, ID_HOURS=0x860d, ID_BUDGET=0x860e;
    private static final int PAGE=50;
    private final ThreadPoolExecutor reader=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<Runnable>(4),r->new Thread(r,"xs-recent-reader"),new ThreadPoolExecutor.AbortPolicy());
    private final Handler main=new Handler(Looper.getMainLooper());
    private final List<XsTimelineBuffer.Entry> entries=new ArrayList<>();
    private final List<String> labels=new ArrayList<>();
    private final Set<Long> selected=new LinkedHashSet<>();
    private final Runnable search=()->{selected.clear();offset=0;load();};
    private final Runnable expiryRefresh=this::load;
    private final Runnable previewRefresh=this::checkPreview;
    private final Set<AlertDialog> dialogs=new LinkedHashSet<>();
    private AlertDialog previewDialog;
    private TextView previewBody,previewMetadata;
    private long previewId=-1,lifecycleEpoch,dialogRequest;
    private XsTimelineBuffer buffer;
    private Context ui;
    private String library,batchId;
    private int offset,request;
    private boolean destroyed,busy,selecting,resumed;
    private EditText query;
    private TextView status;
    private ListView results;
    private ArrayAdapter<String> adapter;
    private Button previous,next,batches,select,save,favorite,settings,clear;

    public static Intent intent(Context context,String library) {
        return new Intent(context,XsRecentTimelineActivity.class).putExtra(XsHistoryActivity.EXTRA_LIBRARY,library);
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        library=getIntent().getStringExtra(XsHistoryActivity.EXTRA_LIBRARY);
        if(library==null||library.isEmpty()||library.length()>256||XsDeletedLibraries.isDeleted(this,library)){finish();return;}
        buffer=XsTimelineBuffer.get(this);
        setContentView(buildPanel(this));
        XsTimelineBuffer.scheduleMaintenance(this);
    }
    /** The production layout can be measured under isolated locale/font/width configurations. */
    ScrollView buildPanel(Context context) {
        ui=context;
        LinearLayout root=column();root.setPadding(dp(12),dp(8),dp(12),dp(8));
        TextView title=text(tr("最近时间线","Recent timeline"));title.setTextSize(23);root.addView(title);
        TextView note=text(tr("找回本机已捕获的卡片；预览不算打开或已读。到期会清理，重要内容请长期保存。",
            "Find cards captured on this device. Previewing is not an open or proof of reading. Save important posts before they expire."));root.addView(note);
        LinearLayout tools=row();
        batches=button(tr("全部批次","All batches"),ID_BATCHES,this::chooseBatch);tools.addView(batches,weight());
        settings=button(tr("设置","Settings"),ID_SETTINGS,this::settings);tools.addView(settings,weight());root.addView(tools);
        query=new EditText(ui);query.setId(ID_QUERY);query.setSingleLine(true);
        query.setHint(tr("搜索正文、作者或帖子 ID","Search text, author or post ID"));
        query.setFilters(new InputFilter[]{new InputFilter.LengthFilter(256)});root.addView(query);
        query.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}
            public void onTextChanged(CharSequence s,int a,int b,int c){main.removeCallbacks(search);main.postDelayed(search,250);}
            public void afterTextChanged(Editable e){}});
        status=text("");status.setId(ID_STATUS);status.setTextSize(12);root.addView(status);
        results=new ListView(ui);results.setId(ID_RESULTS);
        adapter=new ArrayAdapter<>(ui,android.R.layout.simple_list_item_1,labels);results.setAdapter(adapter);
        results.setNestedScrollingEnabled(true);root.addView(results,new LinearLayout.LayoutParams(-1,dp(280)));
        results.setOnItemClickListener((p,v,n,id)->{if(n>=entries.size()||busy)return;XsTimelineBuffer.Entry entry=entries.get(n);
            if(selecting){if(!selected.remove(entry.appearanceId))selected.add(entry.appearanceId);updateLabels();}else preview(entry.appearanceId);});
        LinearLayout paging=row();previous=button(tr("上一页","Previous"),ID_PREVIOUS,()->{selected.clear();offset=Math.max(0,offset-PAGE);load();});
        next=button(tr("下一页","Next"),ID_NEXT,()->{selected.clear();offset+=PAGE;load();});paging.addView(previous,weight());paging.addView(next,weight());root.addView(paging);
        LinearLayout actions=row();select=button(tr("选择","Select"),ID_SELECT,()->{selecting=!selecting;selected.clear();updateLabels();});
        clear=button(tr("清空此缓冲","Clear this buffer"),ID_CLEAR,this::confirmClear);actions.addView(select,weight());actions.addView(clear,weight());root.addView(actions);
        LinearLayout promotions=row();save=button(tr("长期保存","Save long term"),ID_SAVE,()->promoteSelected(false));
        favorite=button(tr("收藏到 Xs","Favorite in Xs"),ID_FAVORITE,()->promoteSelected(true));promotions.addView(save,weight());promotions.addView(favorite,weight());root.addView(promotions);
        updateLabels();ScrollView panel=new ScrollView(ui);panel.addView(root);return panel;
    }
    @Override protected void onResume(){super.onResume();resumed=true;if(buffer!=null)load();}
    private void load(){
        if(destroyed||!resumed||buffer==null)return;
        if(XsDeletedLibraries.isDeleted(this,library)){finish();return;}
        final int generation=++request,start=offset;final String term=query.getText().toString(),batch=batchId;
        execute(()->{
            try {
                List<XsTimelineBuffer.Entry> page=buffer.list(library,term,batch,PAGE+1,start);
                XsTimelineBuffer.Stats stats=buffer.stats(library);XsTimelineBuffer.Settings config=buffer.settings();
                main.post(()->{if(destroyed||!resumed||generation!=request)return;
                    entries.clear();entries.addAll(page.subList(0,Math.min(PAGE,page.size())));
                    Set<Long> present=new LinkedHashSet<>();for(XsTimelineBuffer.Entry entry:entries)present.add(entry.appearanceId);
                    selected.retainAll(present);
                    previous.setEnabled(!busy&&start>0);next.setEnabled(!busy&&page.size()>PAGE);
                    status.setText(summary(stats,config));updateLabels();scheduleExpiry();});
            }catch(Exception failure){main.post(()->{if(!destroyed&&resumed&&generation==request){entries.clear();selected.clear();updateLabels();status.setText(tr("暂时无法读取缓冲，请重试。长期资料保留。","Could not read the buffer. Retry; long-term data is preserved."));}});}
        });
    }
    private void scheduleExpiry(){
        main.removeCallbacks(expiryRefresh);final long epoch=lifecycleEpoch;final int generation=request;
        execute(()->{try{long delay=buffer.nextExpiryDelayMillis(library);main.post(()->{
            if(active(epoch)&&generation==request){main.removeCallbacks(expiryRefresh);
                main.postDelayed(expiryRefresh,delay<0?60000:Math.max(1,Math.min(60000,delay)));}
        });}catch(Exception failure){/* A fresh query on resume still filters expiration. */}});
    }
    private boolean active(long epoch){return !destroyed&&resumed&&lifecycleEpoch==epoch;}
    private boolean currentDialog(long epoch,long read){return active(epoch)&&dialogRequest==read;}
    @Override protected void onPause(){
        resumed=false;++lifecycleEpoch;++dialogRequest;++request;
        main.removeCallbacks(search);main.removeCallbacks(expiryRefresh);dismissDialogs();super.onPause();
    }
    private String summary(XsTimelineBuffer.Stats s,XsTimelineBuffer.Settings c){
        String state=c.enabled?tr("已开启","On"):tr("已关闭 · 停止新收录","Off · no new capture");
        String coverage=s.count==0?tr("尚无有效卡片","No unexpired cards"):s.count+tr(" 条 · 覆盖 "," cards · coverage ")+date(s.oldest)+" – "+date(s.latest);
        String value=state+" · "+c.hours+tr(" 小时"," hours")+"\n"+coverage+"\n"+
            tr("全应用临时空间 ","App-wide temporary space ")+String.format(Locale.ROOT,"%.1f",s.actualBytes/1048576d)+" / "+c.budgetMiB+" MiB";
        if(s.dropped>0)value+=" · "+s.dropped+tr(" 条丢弃"," dropped");
        if(s.pauseReason!=null&&!s.pauseReason.isEmpty())value+="\n"+tr("追加暂停：","Capture paused: ")+reason(s.pauseReason);
        if(s.reclaimReason!=null&&!s.reclaimReason.isEmpty())value+="\n"+tr("空间回收：","Reclamation: ")+reason(s.reclaimReason);
        return value;
    }
    private String reason(String value){
        switch(value){case "SQLITE_FULL":return tr("临时数据库空间不足，已拒绝本次写入","temporary database full; capture rejected");
            case "SPACE_BUDGET":case "CAPACITY_EVICTION":case "quota":case "quota_exceeded":return tr("临时空间预算不足","temporary space budget reached");
            case "low_space":return tr("设备可用空间不足","device storage low");
            case "CLOCK_CHANGED":case "clock_jump":case "clock":return tr("设备时间发生跳变，请检查时间设置","device clock changed; check time settings");
            case "READERS_BUSY":case "busy":case "checkpoint_busy":return tr("数据库正在使用，稍后重试","database in use; retry later");
            case "DISABLED":case "disabled":return tr("收录开关关闭","capture is off");
            case "COMPACTION_HEADROOM":return tr("整理所需临时空间不足，稍后重试","insufficient compaction headroom; retry later");
            case "ROW_BUDGET":return tr("批次或卡片数量达到上限","batch or card count limit reached");
            case "QUEUE_FULL":return tr("观察队列繁忙，部分卡片未能收录","capture queue busy; some cards were missed");
            case "EXPIRED_BEFORE_WRITE":return tr("排队期间卡片已到期","card expired while queued");
            default:return tr("维护尚未完成，请刷新或稍后重试","maintenance pending; refresh or retry later");}
    }
    private void updateLabels(){
        labels.clear();for(XsTimelineBuffer.Entry e:entries)labels.add((selecting?(selected.contains(e.appearanceId)?"☑ ":"☐ "):"")+
            (e.author==null||e.author.isEmpty()?"#"+e.postId:e.author)+" · "+date(e.capturedAt)+"\n"+
            (e.body==null||e.body.isEmpty()?tr("仅链接","Link only"):e.body.substring(0,Math.min(150,e.body.length())))+"\n"+
            source(e.source)+" · "+batchReason(e.batchReason)+" · #"+e.sequence+" · "+quality(e));
        if(adapter!=null)adapter.notifyDataSetChanged();
        if(select!=null)select.setText(selecting?tr("完成选择","Done selecting")+" ("+selected.size()+")":tr("选择","Select"));
        if(save!=null)save.setEnabled(!busy&&!selected.isEmpty());if(favorite!=null)favorite.setEnabled(!busy&&!selected.isEmpty());
    }
    private void chooseBatch(){
        if(!resumed||destroyed)return;final long epoch=lifecycleEpoch,read=++dialogRequest;
        execute(()->{try{List<XsTimelineBuffer.Batch> choices=buffer.batches(library);String[] names=new String[choices.size()+1];names[0]=tr("全部批次","All batches");
            for(int i=0;i<choices.size();i++){XsTimelineBuffer.Batch b=choices.get(i);names[i+1]=date(b.createdAt)+" · "+source(b.source)+" · "+batchReason(b.reason)+" ("+b.count+")";}
            main.post(()->{if(!currentDialog(epoch,read))return;dismissDialogs();
                showDialog(new AlertDialog.Builder(this).setTitle(tr("刷新与浏览批次","Refresh and browsing batches")).setItems(names,(d,n)->{
                    if(!active(epoch))return;selected.clear();batchId=n==0?null:choices.get(n-1).id;
                    batches.setText(n==0?names[0]:date(choices.get(n-1).createdAt));offset=0;load();}).create());});
        }catch(Exception failure){main.post(()->{if(currentDialog(epoch,read))toast(tr("无法读取批次","Could not read batches"));});}});
    }
    private void preview(long id){
        if(!resumed||destroyed)return;final long epoch=lifecycleEpoch,read=++dialogRequest;
        execute(()->{try{XsTimelineBuffer.Entry e=buffer.entry(library,id);main.post(()->{
            if(!currentDialog(epoch,read))return;
            if(e==null){toast(tr("卡片已到期或已清理","This card expired or was cleared"));load();return;}
            dismissDialogs();LinearLayout fields=column();fields.setPadding(dp(16),dp(8),dp(16),dp(8));
            TextView metadata=text((e.author==null?"":e.author)+"\n"+e.url+"\n"+tr("收录：","Captured: ")+date(e.capturedAt)+
                (e.publishedAt==null?"":"\n"+tr("发布：","Published: ")+date(e.publishedAt))+"\n"+source(e.source)+" · "+batchReason(e.batchReason)+" · "+quality(e));fields.addView(metadata);
            TextView body=text(e.body==null||e.body.isEmpty()?tr("尚未捕获可见正文。","No visible text captured."):e.body);body.setTextIsSelectable(true);body.setPadding(0,dp(16),0,dp(16));fields.addView(body);
            Button original=button(tr("打开原帖","Open original post"),0,()->openOriginal(id));fields.addView(original);
            ScrollView scroll=new ScrollView(ui);scroll.addView(fields);
            previewId=id;previewBody=body;previewMetadata=metadata;
            previewDialog=new AlertDialog.Builder(this).setTitle(tr("本机临时预览","Local temporary preview")).setView(scroll)
                .setNegativeButton(tr("关闭","Close"),null).setNeutralButton(tr("长期保存","Save long term"),(d,n)->promote(id,false,null))
                .setPositiveButton(tr("收藏到 Xs","Favorite in Xs"),(d,n)->promote(id,true,null)).create();
            showDialog(previewDialog);checkPreview();});
        }catch(Exception failure){main.post(()->{if(currentDialog(epoch,read))toast(tr("预览未完成，请重试","Preview unavailable; retry"));});}});
    }
    /** The open dialog is a temporary view too: expire/redact it without another user gesture. */
    private void checkPreview(){
        main.removeCallbacks(previewRefresh);
        if(!resumed||destroyed||previewDialog==null)return;
        final AlertDialog shown=previewDialog;final long id=previewId,epoch=lifecycleEpoch;
        boolean accepted=execute(()->{
            boolean present=false;long delay=-1;
            try{present=buffer.entry(library,id)!=null;if(present)delay=buffer.nextExpiryDelayMillis(library);}
            catch(RuntimeException unavailable){/* A cleared/deleted/unavailable scope cannot keep a stale preview. */}
            final boolean exists=present;final long expires=delay;
            main.post(()->{
                if(!active(epoch)||previewDialog!=shown||previewId!=id)return;
                if(!exists){shown.dismiss();load();return;}
                // Exact nearest TTL, capped at one second only while a dialog is visible,
                // also detects external clears/policy changes without background polling.
                main.postDelayed(previewRefresh,expires<0?1000:Math.max(1,Math.min(1000,expires)));
            });
        });
        if(!accepted&&previewDialog==shown)shown.dismiss();
    }
    private void clearPreview(){
        main.removeCallbacks(previewRefresh);
        if(previewBody!=null)previewBody.setText("");if(previewMetadata!=null)previewMetadata.setText("");
        previewBody=previewMetadata=null;previewDialog=null;previewId=-1;
    }
    private void showDialog(AlertDialog dialog){
        dialogs.add(dialog);dialog.setOnDismissListener(d->{dialogs.remove(dialog);if(previewDialog==dialog)clearPreview();});
        if(dialog.getWindow()!=null)dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);dialog.show();
    }
    private void dismissDialogs(){
        for(AlertDialog dialog:new ArrayList<>(dialogs))try{dialog.dismiss();}catch(RuntimeException removed){/* Activity window already removed. */}
        dialogs.clear();clearPreview();
    }
    private void openOriginal(long id){
        if(!resumed||destroyed)return;final long epoch=lifecycleEpoch;
        execute(()->{try{XsTimelineBuffer.Entry e=buffer.entry(library,id);main.post(()->{if(!active(epoch))return;
            if(e==null){toast(tr("卡片已到期","This card expired"));return;}
            try{Intent intent=new Intent(Intent.ACTION_VIEW,Uri.parse(e.url)).setPackage(getPackageName());
                if(intent.resolveActivity(getPackageManager())==null)intent.setPackage(null);startActivity(intent);
            }catch(RuntimeException unavailable){toast(tr("没有可打开原帖的应用","No app can open this post"));}});
        }catch(Exception failure){main.post(()->toast(tr("无法读取原帖链接","Could not read post link")));}});
    }
    private void promoteSelected(boolean favorite){
        if(busy||selected.isEmpty())return;List<Long> ids=new ArrayList<>(selected);setBusy(true);
        promoteNext(ids,0,favorite,0);
    }
    private void promoteNext(List<Long> ids,int index,boolean favorite,int success){
        if(index==ids.size()||destroyed){if(!destroyed){setBusy(false);toast(success+" / "+ids.size()+tr(" 条已保存"," saved"));load();}return;}
        promote(ids.get(index),favorite,ok->promoteNext(ids,index+1,favorite,success+(ok?1:0)));
    }
    private interface Saved{void done(boolean success);}
    private void promote(long id,boolean favorite,Saved next){
        XsTimelinePromotion.promote(this,library,id,favorite,result->{
            boolean ok=result.committed;
            if(next!=null){next.done(ok);return;}
            if(destroyed)return;toast(ok?(favorite?tr("已收藏到 Xs","Favorited in Xs"):tr("已长期保存","Saved long term")):
                tr("保存未完成：卡片可能已到期、清理或空间不足。","Save incomplete: the card may have expired, been cleared, or storage is low."));load();});
    }
    private void settings(){
        if(!resumed||destroyed)return;final long epoch=lifecycleEpoch,read=++dialogRequest;
        execute(()->{try{XsTimelineBuffer.Settings c=buffer.settings();main.post(()->{
            if(currentDialog(epoch,read)){dismissDialogs();showSettings(c);}});}
            catch(Exception failure){main.post(()->{if(currentDialog(epoch,read))toast(tr("无法读取设置","Could not read settings"));});}});
    }
    private void showSettings(XsTimelineBuffer.Settings c){
        LinearLayout fields=column();fields.setPadding(dp(16),dp(8),dp(16),dp(8));
        fields.addView(text(tr("独立开关，不改变打开、停留和收藏记录。关闭后已有卡片继续到期。当前真实 X 自动收录仍未启用。",
            "Independent of opens, dwell and favorites. Existing cards still expire when off. Automatic live X capture remains disabled.")));
        Switch enabled=new Switch(ui);enabled.setId(ID_ENABLED);enabled.setText(tr("开启最近时间线","Enable recent timeline"));enabled.setChecked(c.enabled);fields.addView(enabled);
        fields.addView(text(tr("保留小时数（1–168）","Retention hours (1–168)")));EditText hours=number(ID_HOURS,c.hours);fields.addView(hours);
        fields.addView(button(tr("选择 24 / 48 / 72 小时 / 7 天","Choose 24 / 48 / 72 hours / 7 days"),0,()->
            showDialog(new AlertDialog.Builder(this).setItems(new String[]{"24", "48", "72", "168"},(d,n)->hours.setText(new String[]{"24","48","72","168"}[n])).create())));
        fields.addView(text(tr("全应用临时预算 MiB（25–500）","App-wide temporary budget MiB (25–500)")));EditText budget=number(ID_BUDGET,c.budgetMiB);fields.addView(budget);
        fields.addView(button(tr("重置所有临时缓冲与时间","Reset all temporary buffers and clock"),0,this::confirmClockReset));
        fields.addView(text(tr("容量不足会先淘汰较旧卡片。仅覆盖实际捕获的内容，不能保证完整保留所选时长。后台清理可能由系统延迟，查询会立即排除过期项。",
            "Older cards are evicted under pressure. Coverage includes only captured content and may be shorter than this window. Android can delay background cleanup; queries exclude expired cards immediately.")));
        ScrollView scroll=new ScrollView(ui);scroll.addView(fields);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(tr("最近时间线设置","Recent timeline settings")).setView(scroll)
            .setNegativeButton(android.R.string.cancel,null).setPositiveButton(tr("保存","Save"),null).create();showDialog(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(!resumed||destroyed)return;
            final int h,m;try{h=Integer.parseInt(hours.getText().toString());m=Integer.parseInt(budget.getText().toString());
                if(h<1||h>168||m<25||m>500)throw new IllegalArgumentException();
            }catch(RuntimeException invalid){toast(tr("请输入 1–168 小时和 25–500 MiB。","Enter 1–168 hours and 25–500 MiB."));return;}
            boolean active=enabled.isChecked();dialog.dismiss();setBusy(true);execute(()->{
                boolean ok;try{buffer.configure(active,h,m);ok=true;}catch(Exception failure){ok=false;}final boolean saved=ok;
                main.post(()->{if(!destroyed){setBusy(false);toast(saved?tr("设置已保存","Settings saved"):tr("设置未能保存，请重试","Settings could not be saved; retry"));selected.clear();offset=0;load();}});});
        });
    }
    private void confirmClockReset(){
        if(!resumed||destroyed||busy)return;final long epoch=lifecycleEpoch;
        showDialog(new AlertDialog.Builder(this).setTitle(tr("重置所有空间的临时缓冲与时间？","Reset temporary buffers and clock for every library?"))
            .setMessage(tr("设备时间曾大幅前跳后恢复时，可以用此操作重新开始收录。确认会清空所有本机资料空间的临时时间线卡片、正文和搜索索引，并重置缓冲时钟。收录会保持关闭；请先检查设备时间，再按需重新开启。保留小时数、空间预算、长期保存、收藏、历史和下载文件不变。此操作不能撤销。",
                "Use this after correcting a device clock that jumped far into the future. Confirmation clears temporary timeline cards, text and search indexes from ALL local libraries and resets the buffer clock. Capture remains OFF: check the device time, then enable capture again if wanted. Retention hours, space budget, long-term saves, favorites, history and downloads are preserved. This cannot be undone."))
            .setNegativeButton(android.R.string.cancel,null)
            .setPositiveButton(tr("确认清空并重置","Clear and reset"),(d,n)->{
                if(!active(epoch)||busy)return;dismissDialogs();selected.clear();setBusy(true);
                try{buffer.resetAfterClockChange(library,ok->main.post(()->{
                    if(destroyed)return;setBusy(false);
                    if(active(epoch)){batchId=null;offset=0;toast(ok?
                        tr("所有空间的临时缓冲与时间已重置，收录保持关闭。检查时间后可重新开启。","All temporary buffers and the clock were reset. Capture remains off; check the time before enabling it again."):
                        tr("重置未完成，请重试。现有长期资料保留。","Reset did not complete. Retry; long-term data is preserved."));}
                    if(resumed)load();
                }));}catch(RuntimeException unavailable){setBusy(false);if(active(epoch))toast(tr("重置未完成，请重试","Reset did not complete; retry"));}
            }).create());
    }
    private void confirmClear(){
        if(!resumed||destroyed)return;
        showDialog(new AlertDialog.Builder(this).setTitle(tr("清空此空间的临时时间线？","Clear this library's temporary timeline?"))
            .setMessage(tr("仅清除此空间的临时卡片和搜索索引。长期保存、收藏、历史、下载和网页会话保留。",
                "Clears this library's temporary cards and search index. Long-term saves, favorites, history, downloads and web session are preserved."))
            .setNegativeButton(android.R.string.cancel,null).setPositiveButton(tr("清空缓冲","Clear buffer"),(d,n)->{
                if(!resumed||destroyed)return;selected.clear();setBusy(true);buffer.clear(library,ok->main.post(()->{if(!destroyed){setBusy(false);batchId=null;offset=0;
                    toast(ok?tr("临时缓冲已清空","Temporary buffer cleared"):tr("清理尚未完成，请重试","Cleanup pending; retry"));load();}}));}).create());
    }
    private void setBusy(boolean value){busy=value;if(value){previous.setEnabled(false);next.setEnabled(false);}settings.setEnabled(!value);clear.setEnabled(!value);select.setEnabled(!value);batches.setEnabled(!value);query.setEnabled(!value);updateLabels();}
    private boolean execute(Runnable work){
        try{reader.execute(work);return true;}
        catch(RuntimeException rejected){if(!destroyed){setBusy(false);toast(tr("操作繁忙，请重试","Busy; retry"));
            if(resumed){main.removeCallbacks(expiryRefresh);main.postDelayed(expiryRefresh,1000);}}
            return false;}
    }
    private String source(String value){if("home_for_you".equals(value))return tr("为你推荐","For you");if("home_following".equals(value))return tr("正在关注","Following");if("search_results".equals(value))return tr("搜索结果","Search results");return tr("来源未知","Unknown source");}
    private String batchReason(String value){if("refresh".equals(value)||"native_refresh".equals(value))return tr("原生刷新","Native refresh");if("suspected_refresh".equals(value))return tr("疑似刷新","Suspected refresh");if("native_stream_switch".equals(value)||"native_stream_change".equals(value)||"feed_switch".equals(value))return tr("切换时间线","Feed switch");return tr("浏览批次","Browsing batch");}
    private String quality(XsTimelineBuffer.Entry e){return e.body==null||e.body.isEmpty()?tr("仅链接","Link only"):tr("可见的部分正文","Partial visible text");}
    private String date(long time){return DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(time));}
    private EditText number(int id,int value){EditText field=new EditText(ui);field.setId(id);field.setSingleLine(true);field.setInputType(InputType.TYPE_CLASS_NUMBER);field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(3)});field.setText(Integer.toString(value));return field;}
    private LinearLayout column(){LinearLayout v=new LinearLayout(ui);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(ui);v.setOrientation(LinearLayout.HORIZONTAL);return v;}
    private LinearLayout.LayoutParams weight(){return new LinearLayout.LayoutParams(0,-2,1);}
    private TextView text(String label){TextView v=new TextView(ui);v.setText(label);return v;}
    private Button button(String label,int id,Runnable click){Button v=new Button(ui);if(id!=0)v.setId(id);v.setText(label);v.setAllCaps(false);v.setMinWidth(0);v.setPadding(dp(4),dp(6),dp(4),dp(6));v.setOnClickListener(w->click.run());return v;}
    private String tr(String zh,String en){return ui.getResources().getConfiguration().getLocales().get(0).getLanguage().equals("zh")?zh:en;}
    private int dp(int value){return Math.round(value*ui.getResources().getDisplayMetrics().density);}
    private void toast(String text){if(!destroyed&&resumed)Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    @Override protected void onDestroy(){destroyed=true;resumed=false;++lifecycleEpoch;++dialogRequest;++request;main.removeCallbacks(search);main.removeCallbacks(expiryRefresh);dismissDialogs();reader.shutdownNow();super.onDestroy();}
}

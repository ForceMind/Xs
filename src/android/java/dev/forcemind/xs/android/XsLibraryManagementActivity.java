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
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Explicit storage controls; no session/cache files are exposed or implicitly removed here. */
public final class XsLibraryManagementActivity extends Activity {
    public static final int ID_DELETE=0x8130,ID_CONFIRM=0x8131,ID_STATUS=0x8132,
        ID_STATS=0x8133,ID_RETENTION=0x8134,ID_QUOTA=0x8135,ID_USAGE=0x8136,
        ID_CLEAN=0x8137,ID_REFRESH=0x8138;
    private static final String LIBRARY="library_id";
    private static final int[] DAYS={0,7,30,90,365};
    private static final long MIB=1024L*1024L;
    private static final long[] QUOTAS={0,100*MIB,500*MIB,1024*MIB};
    private final ThreadPoolExecutor io=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(4),r->new Thread(r,"xs-storage-ui"));
    private String library,label,lastMessage;private Panel panel;private boolean busy,destroyed,loading;
    private int retentionDays;private long quotaBytes;
    public static Intent intent(Context context,String library){return new Intent(context,XsLibraryManagementActivity.class).putExtra(LIBRARY,library);}
    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        library=getIntent().getStringExtra(LIBRARY);if(library==null||library.isEmpty()||library.length()>256){finish();return;}
        panel=new Panel(this,new Actions(){
            public void backup(){if(!busy)startActivity(XsBackupActivity.intent(XsLibraryManagementActivity.this,library));}
            public void retention(){chooseRetention();}public void quota(){chooseQuota();}
            public void clean(){previewRetention(retentionDays,true);}public void refresh(){load();}
            public void erase(){confirm();}public void back(){finish();}
        });
        setContentView(panel.root);XsRetentionController.cleanNow(this,library,(ok,count)->{if(!destroyed)load();});
    }
    /** The same bounded, scrollable controls can be measured with an isolated display configuration. */
    interface Actions {void backup();void retention();void quota();void clean();void refresh();void erase();void back();}
    static final class Panel {
        final ScrollView root;final TextView status,stats,usage;final Button erase,retention,quota,clean,refresh,backup;
        Panel(Context c,Actions actions){
            LinearLayout content=new LinearLayout(c);content.setOrientation(LinearLayout.VERTICAL);int p=dp(c,16);content.setPadding(p,p,p,p);
            TextView title=text(c,tr(c,"管理本机资料空间","Manage local library"));title.setTextSize(22);content.addView(title);
            content.addView(text(c,tr(c,"资料空间属于 Xs，与当前 X 账号无关。保留策略与媒体配额仅用于此空间。","This Xs library is independent of the current X account. Retention and media limits apply only to this library.")));
            status=text(c,"");status.setId(ID_STATUS);content.addView(status);
            stats=text(c,tr(c,"正在读取资料统计…","Loading library statistics…"));stats.setId(ID_STATS);content.addView(stats);
            retention=button(c,content,ID_RETENTION,tr(c,"历史保留：读取中","History retention: loading"),actions::retention);
            content.addView(text(c,tr(c,"只清理过期打开/停留事件明细；累计打开次数和最近打开/停留事实保留。帖子、正文版本、收藏、备注、分类、本人行为和资源保留。旧帖子不会变成“从未打开”；只有“清空历史”会主动忘记该事实。进入资料库/管理页或手动清理时维护，不保证后台定时清理。","Only expired open/dwell event details are removed. Cumulative open counts and latest open/dwell facts remain, along with posts, snapshots, favorites, notes, folders, own activity and assets. Old posts do not become ‘never opened’; only Clear history explicitly forgets that fact. Maintenance runs on library/management entry or manual cleanup, with no background timing guarantee.")));
            clean=button(c,content,ID_CLEAN,tr(c,"按当前设置清理旧事件","Clean expired events using current setting"),actions::clean);
            usage=text(c,tr(c,"正在读取媒体用量…","Loading media usage…"));usage.setId(ID_USAGE);content.addView(usage);
            quota=button(c,content,ID_QUOTA,tr(c,"媒体配额：读取中","Media limit: loading"),actions::quota);
            content.addView(text(c,tr(c,"默认不限，已完成文件不会被删除。如果完成文件与预留总量超过新配额，所有未完成任务会失败并停止，可在配额允许时显式重试。未知大小每图预留20 MiB、每视频100 MiB；合法大小信息可缩减预留。","Unlimited by default; completed files are never deleted. If completed plus reserved bytes exceed a new limit, all unfinished tasks fail and stop; explicitly retry when the limit allows. Unknown sizes reserve 20 MiB per image or 100 MiB per video; valid size information may reduce reservations.")));
            refresh=button(c,content,ID_REFRESH,tr(c,"刷新统计","Refresh statistics"),actions::refresh);
            backup=button(c,content,0,tr(c,"备份 / 恢复","Backup / restore"),actions::backup);
            content.addView(text(c,tr(c,"清历史只清浏览事件。删除资源只删所选文件。完整删除会移除此空间的帖子、所有快照、历史、收藏、标签、备注、分类、本人行为和下载文件；其他空间与 X 网页会话保留。已有分享副本保留到接收租约到期。","Clearing history removes browsing events. Deleting assets removes chosen files. Whole-library deletion removes this library's posts, snapshots, history, favorites, tags, notes, folders, own activity and downloads. Other libraries and X sessions remain. Existing share copies remain until their receiver lease expires.")));
            erase=button(c,content,ID_DELETE,tr(c,"完整删除此资料空间","Delete this whole library"),actions::erase);
            button(c,content,0,tr(c,"返回","Back"),actions::back);
            root=new ScrollView(c);root.setFillViewport(true);root.addView(content);enabled(false,false);
        }
        void enabled(boolean available,boolean pending){retention.setEnabled(available);quota.setEnabled(available);clean.setEnabled(available);backup.setEnabled(available);erase.setEnabled(available||pending);}
        private static TextView text(Context c,String value){TextView text=new TextView(c);text.setText(value);text.setPadding(0,dp(c,5),0,dp(c,5));return text;}
        private static Button button(Context c,LinearLayout parent,int id,String label,Runnable action){Button button=new Button(c);if(id!=0)button.setId(id);button.setAllCaps(false);button.setSingleLine(false);button.setText(label);button.setOnClickListener(v->action.run());parent.addView(button,new LinearLayout.LayoutParams(-1,-2));return button;}
    }
    private void load(){
        if(destroyed||loading||busy)return;loading=true;panel.refresh.setEnabled(false);
        try{io.execute(()->{
            String found=null;XsArchiveDatabase.HistoryStats stats=null;XsMediaRepository.StorageUsage usage=null;int days=0;
            try{
                if(!XsLibraryWrites.isDeleted(library))try(XsArchiveDatabase archive=new XsArchiveDatabase(getApplicationContext());Cursor rows=archive.libraries()){
                    while(rows.moveToNext())if(library.equals(rows.getString(3))){found=rows.getString(1);break;}
                    if(found!=null){stats=archive.historyStats(library);days=XsRetentionSettings.getDays(archive,library);usage=XsMediaRepository.get(getApplicationContext()).usage(library);}
                }
                final String name=found;final XsArchiveDatabase.HistoryStats result=stats;final XsMediaRepository.StorageUsage media=usage;final int chosenDays=days;
                runOnUiThread(()->{
                    if(destroyed)return;loading=false;panel.refresh.setEnabled(true);label=name;
                    boolean pending=XsDeletedLibraries.pending(this).contains(library),available=name!=null&&!XsLibraryWrites.isDeleted(library);
                    panel.status.setText(available?(lastMessage==null?name:lastMessage):pending?tr("删除未完成；此空间已停用，可重试清理。","Deletion incomplete; this scope is disabled. Retry cleanup."):tr("资料空间不存在或已删除。","Library does not exist or was deleted."));
                    panel.enabled(available&&!busy,pending&&!busy);
                    if(pending)panel.erase.setText(tr("重试删除清理","Retry deletion cleanup"));
                    if(!available){panel.stats.setText(tr("此空间已不可读取。","This library is unavailable."));panel.usage.setText("");return;}
                    retentionDays=chosenDays;quotaBytes=media.quotaBytes;
                    panel.retention.setText(tr("历史保留：","History retention: ")+daysLabel(retentionDays));
                    panel.quota.setText(tr("媒体配额：","Media limit: ")+quotaLabel(quotaBytes));
                    panel.usage.setText(tr("完成文件：","Completed files: ")+bytes(media.completedBytes)+tr("\n进行中预留：","\nActive reservations: ")+bytes(media.reservedBytes));
                    panel.stats.setText(statsLabel(result));
                });
            }catch(RuntimeException unavailable){runOnUiThread(()->{if(destroyed)return;loading=false;panel.refresh.setEnabled(true);panel.status.setText(tr("资料空间状态读取失败，请刷新或重新进入","Could not read library state; refresh or reopen this page"));});}
        });}catch(RuntimeException full){loading=false;panel.refresh.setEnabled(true);panel.status.setText(tr("读取任务繁忙，请重试","Reader is busy; retry"));}
    }
    private String statsLabel(XsArchiveDatabase.HistoryStats s){return tr("帖子 ","Posts ")+s.postCount+tr(" · 正文版本 "," · Snapshots ")+s.snapshotCount+tr(" · 收藏 "," · Favorites ")+s.favoriteCount+
        tr("\n累计打开次数 ","\nLifetime opens ")+s.lifetimeOpenedCount+tr("（曾打开帖子 "," (ever-opened posts ")+s.everOpenedPostCount+")"+tr("\n保留打开事件 ","\nRetained open events ")+s.openEventCount+tr("（帖子 "," (posts ")+s.distinctOpenPosts+")"+tr(" · 保留停留事件 "," · Retained dwell events ")+s.feedEventCount+tr("（帖子 "," (posts ")+s.distinctFeedPosts+")"+
        tr("\n从未主动打开的帖子 ","\nNever deliberately opened posts ")+s.neverOpenedPostCount+
        tr("\n保留事件的曝光时长下界总和 ≥","\nRetained exposure lower-bound sum ≥")+seconds(s.totalDwellLowerBoundMs)+tr("秒；单次最大 ≥"," s; maximum event ≥")+seconds(s.maxDwellLowerBoundMs)+tr("秒"," s")+
        tr("\n未知时长事件 ","\nUnknown-duration events ")+s.unknownDwellCount+tr(" · 达测量上限事件 "," · Capped events ")+s.cappedDwellCount+
        tr("\n曝光可能重叠；不是阅读时长或完成率。","\nExposures can overlap; this is not reading time or completion.");}
    private void chooseRetention(){if(busy||loading||XsLibraryWrites.isDeleted(library))return;String[] labels=new String[DAYS.length];int selected=0;for(int i=0;i<DAYS.length;i++){labels[i]=daysLabel(DAYS[i]);if(DAYS[i]==retentionDays)selected=i;}
        new AlertDialog.Builder(this).setTitle(tr("历史事件保留期限","History event retention")).setSingleChoiceItems(labels,selected,(dialog,which)->{dialog.dismiss();previewRetention(DAYS[which],false);}).setNegativeButton(tr("取消","Cancel"),null).show();}
    private void previewRetention(int days,boolean cleanOnly){
        if(busy||loading||XsLibraryWrites.isDeleted(library))return;busy=true;panel.enabled(false,false);
        try{io.execute(()->{try(XsArchiveDatabase archive=new XsArchiveDatabase(getApplicationContext())){
            long estimate=archive.retentionPreview(library,days,System.currentTimeMillis());runOnUiThread(()->{
                if(destroyed)return;if(XsLibraryWrites.isDeleted(library)){complete(false,0);return;}
                AlertDialog dialog=new AlertDialog.Builder(this).setTitle(tr("确认历史保留设置","Confirm history retention"))
                    .setMessage(daysLabel(days)+tr("\n预计移除旧事件：","\nEstimated expired events: ")+estimate+tr("\n实际数量按执行时间计算。只移除过期事件；收藏、正文版本和文件保留，曾打开事实保留。","\nThe final count uses execution time. Only expired events are removed; favorites, snapshots, files and previous-open facts remain."))
                    .setNegativeButton(tr("取消","Cancel"),(d,n)->complete(true,-1)).setPositiveButton(tr("确认并清理","Confirm and clean"),(d,n)->{
                        panel.status.setText(tr("正在应用保留设置…","Applying retention…"));
                        if(cleanOnly)XsRetentionController.cleanNow(this,library,this::complete);else XsRetentionController.apply(this,library,days,this::complete);
                    }).create();dialog.setOnCancelListener(d->complete(true,-1));dialog.show();
            });
        }catch(RuntimeException failure){runOnUiThread(()->complete(false,0));}});}catch(RuntimeException full){complete(false,0);}
    }
    private void complete(boolean success,long deleted){if(destroyed)return;busy=false;if(!success)lastMessage=tr("操作未完成；请刷新后重试。已有收藏和文件保留。","Operation incomplete; refresh and retry. Existing favorites and files remain.");else if(deleted>=0)lastMessage=tr("已清理旧事件：","Expired events removed: ")+deleted;if(lastMessage!=null)panel.status.setText(lastMessage);load();}
    private void chooseQuota(){
        if(busy||loading||XsLibraryWrites.isDeleted(library))return;long[] choices=QUOTAS;boolean custom=true;for(long q:QUOTAS)if(q==quotaBytes)custom=false;
        if(custom){choices=java.util.Arrays.copyOf(QUOTAS,QUOTAS.length+1);choices[QUOTAS.length]=quotaBytes;}
        final long[] limits=choices;String[] labels=new String[limits.length];int selected=0;for(int i=0;i<limits.length;i++){labels[i]=quotaLabel(limits[i]);if(limits[i]==quotaBytes)selected=i;}
        new AlertDialog.Builder(this).setTitle(tr("此空间媒体配额","Media limit for this library")).setSingleChoiceItems(labels,selected,(dialog,which)->{
            dialog.dismiss();long limit=limits[which];new AlertDialog.Builder(this).setTitle(tr("确认媒体配额","Confirm media limit")).setMessage(quotaLabel(limit)+tr("\n已完成文件保留。若完成文件与预留总量超过新配额，所有未完成下载将停止并标记失败，可在配额允许后显式重试；后续超限下载会被拒绝。","\nCompleted files remain. If completed plus reserved bytes exceed the new limit, every unfinished download stops and becomes failed; explicitly retry when the limit allows. Later downloads exceeding it are rejected."))
                .setNegativeButton(tr("取消","Cancel"),null).setPositiveButton(tr("保存配额","Save limit"),(d,n)->setQuota(limit)).show();
        }).setNegativeButton(tr("取消","Cancel"),null).show();
    }
    private void setQuota(long limit){if(busy||XsLibraryWrites.isDeleted(library))return;busy=true;panel.enabled(false,false);panel.status.setText(tr("正在保存配额…","Saving media limit…"));
        try{if(!XsLibraryWrites.submit(library,()->{boolean ok=false;try{XsMediaRepository.get(getApplicationContext()).setQuotaBytes(library,limit);ok=true;}catch(RuntimeException failure){}final boolean saved=ok;runOnUiThread(()->complete(saved,-1));}))complete(false,0);}catch(RuntimeException invalid){complete(false,0);}
    }
    private void confirm(){
        if(busy)return;EditText confirm=new EditText(this);confirm.setId(ID_CONFIRM);confirm.setSingleLine(true);
        String word=tr("删除","DELETE");confirm.setHint(tr("输入“删除”确认","Type DELETE to confirm"));confirm.setContentDescription(tr("输入“删除”确认","Type DELETE to confirm"));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(tr("完整删除：","Delete whole library: ")+(label==null?tr("待清理空间","Pending scope"):label))
            .setMessage(tr("此操作清除该空间的所有资料和下载。若为默认库，将建立新的空白本机库。请先完成需要的备份。","This erases this library's data and downloads. Deleting the default creates a new blank local library. Complete any backup you need first."))
            .setView(confirm).setNegativeButton(tr("取消","Cancel"),null).setPositiveButton(tr("完整删除","Delete whole library"),null).create();
        dialog.setOnShowListener(d->{Button positive=dialog.getButton(AlertDialog.BUTTON_POSITIVE);positive.setEnabled(false);
            confirm.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){positive.setEnabled(word.contentEquals(s));}public void afterTextChanged(Editable e){}});
            positive.setOnClickListener(v->{dialog.dismiss();busy=true;panel.enabled(false,false);panel.status.setText(tr("正在删除…","Deleting…"));
                XsLibraryDeletion.delete(this,library,(ok,replacement)->{if(destroyed)return;busy=false;
                    panel.status.setText(ok?tr("资料空间已删除；其他空间和网页会话保留。","Library deleted; other libraries and web sessions remain."):
                        (XsDeletedLibraries.isDeleted(this,library)?tr("删除未完成。此空间已停用，可重试清理。","Deletion incomplete. This scope is disabled; retry cleanup."):tr("未能持久保存删除请求，资料空间尚未删除，请重试。","Could not persist the deletion request. Library deletion has not started; retry.")));
                    panel.enabled(false,!ok);panel.erase.setText(tr("重试删除清理","Retry deletion cleanup"));
                    if(XsLibraryWrites.isDeleted(library)){panel.stats.setText(tr("此空间已不可读取。","This library is unavailable."));panel.usage.setText("");}
                });});});dialog.show();
    }
    private String daysLabel(int days){return days==0?tr("永久保留","Keep forever"):days+tr("天"," days");}
    private String quotaLabel(long value){return value==0?tr("不限","Unlimited"):bytes(value);}
    private static String bytes(long value){return value>=1024*MIB&&value%(1024*MIB)==0?(value/(1024*MIB))+" GiB":value>=MIB?String.format(Locale.ROOT,"%.1f MiB",value/(double)MIB):value+" B";}
    private static String seconds(long value){return String.format(Locale.ROOT,"%.1f",value/1000.0);}
    private static int dp(Context c,int value){return Math.round(c.getResources().getDisplayMetrics().density*value);}
    private String tr(String zh,String en){return tr(this,zh,en);}
    private static String tr(Context c,String zh,String en){return c.getResources().getConfiguration().getLocales().get(0).getLanguage().equals("zh")?zh:en;}
    @Override protected void onDestroy(){destroyed=true;io.shutdown();super.onDestroy();}
}

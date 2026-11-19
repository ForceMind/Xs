package dev.forcemind.xs.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SimpleAdapter;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import dev.forcemind.xs.core.ArchiveSearch;

import java.io.InputStream;
import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Native, offline app-owned library. Browsing identities never select its scope. */
public final class XsHistoryActivity extends Activity {
    private enum Mode { HISTORY, FEED, FAVORITES, SEARCH, FOLDERS, OWN }
    private static final int PAGE_SIZE = 80;
    public static final int ID_BATCH = 0x8120;
    public static final int ID_MANAGE = 0x8121;
    public static final int SNAPSHOT_PAGE_SIZE = 50;
    public static final String EXTRA_LIBRARY = "dev.forcemind.xs.LIBRARY";
    public static final String EXTRA_LOCAL_SPACE = "dev.forcemind.xs.LOCAL_SPACE";
    private static final int REQUEST_POSTS_JS = 7167;
    // Stable native IDs allow instrumentation to exercise the actual UI.
    public static final int ID_QUERY = 0x8101;
    public static final int ID_RESULTS = 0x8102;
    public static final int ID_FILTERS = 0x8103;
    public static final int ID_MIN_DWELL = 0x8104;
    public static final int ID_NEVER_OPENED = 0x8105;
    public static final int ID_FAVORITES = 0x8106;
    public static final int ID_SORT_DWELL = 0x8107;
    public static final int ID_SEARCH = 0x8108;
    public static final int ID_CLEAR_HISTORY = 0x8109;
    public static final int ID_OWN_ACTIONS = 0x810a;
    public static final int ID_BACKUP = 0x810b;
    public static final int ID_RECENT_TIMELINE = 0x810c;

    private static final class Row {
        final String id, headline, detail, author, body, note, tags;
        final Long publishedAt;
        final boolean folder, favorite;
        Row(String id, String headline, String detail, boolean folder) {
            this(id, headline, detail, folder, null, null, null, false, null, null);
        }
        Row(String id, String headline, String detail, boolean folder,
                String author, String body, Long publishedAt, boolean favorite,
                String note, String tags) {
            this.id = id; this.headline = headline; this.detail = detail;
            this.folder = folder; this.author = author; this.body = body;
            this.publishedAt = publishedAt; this.favorite = favorite;
            this.note = note; this.tags = tags;
        }
    }

    private final ThreadPoolExecutor reader = new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<Runnable>(8), r -> new Thread(r,"xs-library-reader"),
        new ThreadPoolExecutor.AbortPolicy());
    private final AtomicInteger requested = new AtomicInteger();
    private final AtomicInteger databaseUsers = new AtomicInteger(1);
    private final List<Row> visible = new ArrayList<>();
    private final Set<String> selectedPosts = new LinkedHashSet<>();
    private boolean selecting;
    private ListView results;
    private Button batchButton;
    private final List<Map<String,String>> displayedData = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable delayedSearch = this::refresh;
    private XsArchiveDatabase archive;
    private SimpleAdapter adapter;
    private Button more, dateFilter, folderFilter, typeFilter, extraFilter, libraryButton;
    private TextView status;
    private EditText query;
    private Mode mode = Mode.HISTORY;
    private String library, defaultLibrary, selectedFolder, actionType;
    private String pendingImportLibrary;
    private XsLibraryWrites.Ticket pendingImportTicket;
    private String authorFilter, sourceFilter, tagFilter, actionStateFilter;
    private Long minTime, minDwellMs;
    private boolean neverOpened, favoriteOnly, sortByDwell, includeSnapshotVersions, clearing, destroyed;
    private int nextOffset, dateChoice;
    private ArchiveSearch.DateField dateBasis = ArchiveSearch.DateField.LAST_SEEN;
    private boolean zh;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        zh = Locale.getDefault().getLanguage().equalsIgnoreCase("zh");
        archive = new XsArchiveDatabase(this);
        defaultLibrary = XsLocalSpaces.createOnExplicitUserAction(this);
        library = defaultLibrary;
        // Existing namespaces remain separate and selectable; no automatic consolidation.
        write(defaultLibrary, () -> archive.ensureLibrary(defaultLibrary, tr("Xs 本机资料库", "Xs local library")), null);
        buildScreen();
        openRequestedLibrary();
    }

    @Override protected void onResume() {
        super.onResume();
        if (XsDeletedLibraries.isDeleted(this, library)) {
            defaultLibrary = XsLocalSpaces.defaultLibrary(this); library = defaultLibrary;
            resetSelection(); selectedFolder = null; actionType = null;
            libraryButton.setText(tr("Xs 本机资料库", "Xs local library"));
            write(library, () -> archive.ensureLibrary(library, tr("Xs 本机资料库", "Xs local library")), null);
        }
        maintainAndRefresh();
    }

    private void maintainAndRefresh() {
        final String owner = library;
        XsRetentionController.cleanNow(this, owner, (ok, count) -> {
            if (!destroyed && owner.equals(library)) refresh();
        });
    }

    private void buildScreen() {
        LinearLayout root = column();
        root.setPadding(px(12), px(8), px(12), px(6));
        setContentView(root);
        LinearLayout heading = horizontal();
        TextView title = text("Xs " + tr("资料库", "Library"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.addView(title, weighted());
        Button settings = button(tr("设置", "Settings"));
        settings.setOnClickListener(v -> showSettings());
        heading.addView(settings);
        Button close = button(tr("关闭", "Close"));
        close.setOnClickListener(v -> finish());
        heading.addView(close);
        root.addView(heading);
        libraryButton = button(tr("Xs 本机资料库 · 切换旧空间", "Xs local library · legacy spaces"));
        libraryButton.setOnClickListener(v -> chooseLibrary());
        root.addView(libraryButton);
        status = text(tr("资料属于 Xs；退出或切换 X 账号不会删除。",
            "Your Xs library remains when you switch or sign out of X."));
        root.addView(status);

        LinearLayout tabs = horizontal();
        addTab(tabs, tr("打开", "Opens"), Mode.HISTORY, 0);
        addTab(tabs, tr("停留", "Dwell"), Mode.FEED, 0);
        addTab(tabs, tr("收藏", "Saved"), Mode.FAVORITES, ID_FAVORITES);
        addTab(tabs, tr("搜索", "Search"), Mode.SEARCH, ID_SEARCH);
        addTab(tabs, tr("分类", "Folders"), Mode.FOLDERS, 0);
        root.addView(tabs);
        query = new EditText(this);
        query.setId(ID_QUERY);
        query.setSingleLine(true);
        query.setHint(tr("离线搜索正文或作者", "Search local text or author offline"));
        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable e) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (mode == Mode.SEARCH || mode == Mode.FAVORITES || mode == Mode.OWN) {
                    main.removeCallbacks(delayedSearch);
                    main.postDelayed(delayedSearch, 250);
                }
            }
        });
        root.addView(query);
        LinearLayout filters = horizontal();
        dateFilter = button(tr("全部时间", "All time"));
        folderFilter = button(tr("全部分类", "All folders"));
        typeFilter = button(tr("全部类型", "All types"));
        filters.addView(dateFilter, weighted());
        dateFilter.setOnClickListener(v -> chooseDate());
        folderFilter.setOnClickListener(v -> chooseFolder());
        typeFilter.setOnClickListener(v -> chooseActionType());
        extraFilter = button(tr("筛选与排序", "Filter & sort"));
        extraFilter.setId(ID_FILTERS);
        extraFilter.setOnClickListener(v -> showSearchFilters());
        filters.addView(extraFilter, weighted());
        root.addView(filters);

        results = new ListView(this);
        results.setId(ID_RESULTS);
        adapter = new SimpleAdapter(this, displayedData, android.R.layout.simple_list_item_activated_2,
            new String[]{"title", "detail"}, new int[]{android.R.id.text1, android.R.id.text2});
        results.setAdapter(adapter);
        root.addView(results, new LinearLayout.LayoutParams(-1, 0, 1));
        results.setOnItemClickListener((p, v, position, id) -> {
            if (position >= visible.size()) return;
            Row row = visible.get(position);
            if (selecting && !row.folder) {
                if (selectedPosts.contains(row.id)) selectedPosts.remove(row.id);
                else if (selectedPosts.size() < XsArchiveLibraryOperations.MAX_BATCH) selectedPosts.add(row.id);
                else toast(tr("每批最多选择 200 条", "Select up to 200 posts per batch"));
                updateSelection(); return;
            }
            if (row.folder) {
                selectedFolder = row.id;
                folderFilter.setText(row.headline);
                setMode(Mode.SEARCH);
            } else showPost(row);
        });
        results.setOnItemLongClickListener((p, v, position, id) -> {
            if (position >= visible.size()) return false;
            Row row = visible.get(position);
            if (row.folder) editFolder(row); else showPostActions(row);
            return true;
        });
        more = button(tr("加载更多", "Load more"));
        more.setOnClickListener(v -> loadPage(true));
        root.addView(more);
        LinearLayout controls = horizontal();
        Button addFolder = button(tr("新建分类", "New folder"));
        Button importPosts = button(tr("导入", "Import"));
        Button erase = button(tr("清历史", "Clear history"));
        erase.setId(ID_CLEAR_HISTORY);
        controls.addView(addFolder, weighted());
        controls.addView(importPosts, weighted());
        controls.addView(erase, weighted());
        root.addView(controls);
        addFolder.setOnClickListener(v -> showFolderEditor(null, null));
        importPosts.setOnClickListener(v -> chooseImportFile());
        erase.setOnClickListener(v -> confirmErase());
        LinearLayout indexControls=horizontal();
        Button own=button(tr("本人内容索引", "Own activity index")); own.setId(ID_OWN_ACTIONS);
        own.setOnClickListener(v -> setMode(Mode.OWN)); indexControls.addView(own,weighted());
        Button backup=button(tr("备份 / 恢复", "Backup / restore")); backup.setId(ID_BACKUP);
        backup.setOnClickListener(v -> startActivity(XsBackupActivity.intent(this,library)));
        indexControls.addView(backup,weighted());
        batchButton=button(tr("批量", "Select")); batchButton.setId(ID_BATCH);
        batchButton.setOnClickListener(v -> batchMenu()); indexControls.addView(batchButton,weighted());
        Button manage=button(tr("管理", "Manage")); manage.setId(ID_MANAGE);
        manage.setOnClickListener(v -> startActivity(XsLibraryManagementActivity.intent(this,library)));
        indexControls.addView(manage,weighted()); root.addView(indexControls);
        Button recent=button(tr("最近时间线 · 临时缓冲", "Recent timeline · temporary"));recent.setId(ID_RECENT_TIMELINE);
        recent.setOnClickListener(v -> startActivity(XsRecentTimelineActivity.intent(this,library)));root.addView(recent);
        setMode(Mode.HISTORY);
    }

    private void addTab(LinearLayout tabs, String label, Mode tab, int id) {
        Button button = button(label);
        if (id != 0) button.setId(id);
        button.setPadding(px(2), 0, px(2), 0);
        button.setMinWidth(0);
        tabs.addView(button, weighted());
        button.setOnClickListener(v -> setMode(tab));
    }

    private void setMode(Mode value) {
        if (value==Mode.FEED && mode!=Mode.FEED) dateBasis=ArchiveSearch.DateField.FEED_SEEN;
        else if (value==Mode.FAVORITES && mode!=Mode.FAVORITES) dateBasis=ArchiveSearch.DateField.FAVORITED;
        else if (value==Mode.OWN && mode!=Mode.OWN) dateBasis=ArchiveSearch.DateField.ACTION;
        else if (value==Mode.HISTORY) dateBasis=ArchiveSearch.DateField.LAST_OPENED;
        mode = value;
        boolean search = value == Mode.SEARCH || value == Mode.FAVORITES || value == Mode.OWN;
        query.setVisibility(search ? View.VISIBLE : View.GONE);
        folderFilter.setVisibility(search ? View.VISIBLE : View.GONE);
        typeFilter.setVisibility(search ? View.VISIBLE : View.GONE);
        dateFilter.setVisibility(value == Mode.FOLDERS ? View.GONE : View.VISIBLE);
        extraFilter.setVisibility(value == Mode.FOLDERS || value == Mode.HISTORY ? View.GONE : View.VISIBLE);
        refresh();
    }

    private void refresh() { loadPage(false); }

    private void loadPage(boolean append) {
        if (destroyed) return;
        if(XsLibraryWrites.isDeleted(library)){requested.incrementAndGet();visible.clear();displayedData.clear();adapter.notifyDataSetChanged();resetSelection();more.setVisibility(View.GONE);status.setText(tr("此资料空间已停用；请切换本机库或重试删除清理。","This library is disabled. Choose the local library or retry deletion cleanup."));return;}
        final int request = requested.incrementAndGet();
        final int offset = append ? nextOffset : 0;
        more.setEnabled(false);
        if (!append) more.setVisibility(View.GONE);
        final String owner = library;
        final Mode selectedMode = mode;
        final ArchiveSearch.Filter filter = snapshotFilter(offset);
        read(() -> {
            if (requested.get() != request) return;
            List<Row> rows = new ArrayList<>();
            try {
                if (selectedMode == Mode.FOLDERS) {
                    try (Cursor c = archive.categories(owner)) {
                        while (c.moveToNext()) rows.add(new Row(c.getString(0), c.getString(1),
                            tr("长按重命名或删除", "Long press to rename or delete"), true));
                    }
                } else if (selectedMode == Mode.HISTORY || selectedMode == Mode.FEED) {
                    boolean open = selectedMode == Mode.HISTORY;
                    try (Cursor c = archive.timeline(owner, filter.fromMs, PAGE_SIZE,
                            open ? "OPEN_POST" : "FEED_VISIBLE", offset)) {
                        while (c.moveToNext()) {
                            String id = c.getString(1);
                            String detail = date(c.getLong(2)) + (open ? tr(" · 主动打开", " · Deliberate open") :
                                tr(" · 可见停留 ", " · Visible dwell ") + duration(c.isNull(8) ? null : c.getLong(8), quality(c, "duration_quality")) +
                                " · " + sourceLabel(c.getString(7)));
                            rows.add(new Row(id, headline(c.getString(4), c.getString(5), id),
                                detail, false, c.getString(4), c.getString(5),
                                c.isNull(6) ? null : c.getLong(6), false, null, null));
                        }
                    }
                } else {
                    try (Cursor c = archive.search(owner, filter)) {
                        while (c.moveToNext()) {
                            String id = c.getString(1);
                            String at = c.isNull(8) ? tr("时间未知", "Unknown time") : date(c.getLong(8));
                            int opens = c.getInt(7);
                            Long dwell = c.isNull(9) ? null : c.getLong(9);
                            boolean favorite = !c.isNull(10);
                            String detail = at + " · " + (opens == 0 ? tr("从未打开", "Never opened") :
                                tr("打开 ", "Opens ") + opens) +
                                (dwell == null ? "" : tr(" · 最长停留 ", " · Max dwell ") + duration(dwell, quality(c, "dwell_quality"))) +
                                (favorite ? tr(" · Xs 收藏", " · Xs favorite") : "") +
                                (c.isNull(14) ? "" : "\n" + c.getString(14)) +
                                (c.getColumnCount()>18 && c.getInt(18)>0 ? tr(" · 快照 ", " · Snapshots ")+c.getInt(18)+" · "+c.getString(16) : "");
                            rows.add(new Row(id, headline(c.getString(2), c.getString(3), id), detail,
                                false, c.getString(2), c.getString(3), c.isNull(4) ? null : c.getLong(4),
                                favorite, c.getString(11), c.getString(12)));
                        }
                    }
                }
            } catch (RuntimeException failure) {
                main.post(() -> {
                    if (requested.get() == request && !destroyed) {
                        more.setEnabled(true);
                        toast(tr("本地查询失败", "Local search failed"));
                    }
                });
                return;
            }
            main.post(() -> {
                if (destroyed || requested.get() != request || !owner.equals(library) || selectedMode != mode) return;
                if(XsLibraryWrites.isDeleted(owner)){loadPage(false);return;}
                showRows(rows, append);
                nextOffset = offset + rows.size();
                more.setVisibility(selectedMode != Mode.FOLDERS && rows.size() == PAGE_SIZE ? View.VISIBLE : View.GONE);
                more.setEnabled(true);
            });
        });
    }

    private ArchiveSearch.Filter snapshotFilter(int offset) {
        ArchiveSearch.Filter filter = new ArchiveSearch.Filter();
        filter.keyword = query.getText().toString();
        filter.categoryId = selectedFolder;
        filter.actionType = actionType;
        filter.ownOnly = mode == Mode.OWN;
        filter.actionState = actionStateFilter;
        filter.fromMs = minTime;
        filter.dateField = dateBasis;
        filter.minDwellMs = minDwellMs;
        filter.neverOpened = neverOpened;
        filter.favoriteOnly = mode == Mode.FAVORITES || favoriteOnly;
        filter.sortByDwell = sortByDwell;
        filter.includeSnapshotVersions = includeSnapshotVersions;
        filter.author = authorFilter;
        filter.source = sourceFilter;
        filter.tag = tagFilter;
        filter.limit = PAGE_SIZE;
        filter.offset = offset;
        return filter;
    }

    private void showRows(List<Row> rows, boolean append) {
        if (!append) { visible.clear(); displayedData.clear(); }
        for (Row row : rows) {
            visible.add(row);
            Map<String,String> data = new HashMap<>();
            data.put("title", row.headline); data.put("detail", row.detail);
            displayedData.add(data);
        }
        adapter.notifyDataSetChanged();
        updateSelection();
        status.setText((library.equals(defaultLibrary) ? tr("Xs 本机资料库", "Xs local library") :
            tr("独立资料空间", "Separate library")) + " · " + visible.size() + tr(" 条结果", " results"));
    }

    private void showSearchFilters() {
        LinearLayout fields = paddedColumn();
        LinearLayout folderAndType = horizontal();
        final String[] categoryChoice = {selectedFolder};
        final String[] activityChoice = {actionType};
        Button chooseCategory = button(folderFilter.getText().toString());
        chooseCategory.setOnClickListener(v -> chooseFolder((id, label) -> {
            categoryChoice[0] = id; chooseCategory.setText(label);
        }));
        Button chooseType = button(typeFilter.getText().toString());
        chooseType.setOnClickListener(v -> chooseActionType((id, label) -> {
            activityChoice[0] = id; chooseType.setText(label);
        }));
        folderAndType.addView(chooseCategory, weighted());
        folderAndType.addView(chooseType, weighted());
        fields.addView(folderAndType);
        final String[] actionState={actionStateFilter};
        Button state=button(tr("本人行为状态：", "Own activity state: ") + (actionState[0] == null ? tr("全部", "All") : actionState[0]));
        state.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle(tr("当前平台状态", "Current platform state"))
            .setItems(new String[]{tr("全部", "All"),"ACTIVE","REVERSED","UNKNOWN"},(d,n) -> {
                actionState[0]=new String[]{null,"ACTIVE","REVERSED","UNKNOWN"}[n];
                state.setText(tr("本人行为状态：", "Own activity state: ") + (actionState[0] == null ? tr("全部", "All") : actionState[0]));
            }).show());
        fields.addView(state);
        fields.addView(text(tr("单次连续停留达到指定秒数；不会累加不同曝光。",
            "Minimum duration of one continuous exposure; separate visits are not added together.")));
        EditText dwell = entry(tr("最低停留秒数（空白为不限）", "Minimum dwell seconds (blank: any)"),
            minDwellMs == null ? "" : Double.toString(minDwellMs / 1000.0));
        dwell.setId(ID_MIN_DWELL); dwell.setContentDescription(tr("最低停留秒数", "Minimum dwell seconds"));
        dwell.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        fields.addView(dwell);
        CheckBox never = check(tr("从未主动打开", "Never deliberately opened"), neverOpened);
        never.setId(ID_NEVER_OPENED); never.setContentDescription(tr("从未主动打开", "Never deliberately opened")); fields.addView(never);
        CheckBox favorites = check(tr("仅 Xs 收藏", "Xs favorites only"), favoriteOnly || mode == Mode.FAVORITES);
        fields.addView(favorites);
        CheckBox versions=check(tr("搜索包含历史快照正文（结果显示当前版本）","Search historical snapshot text (results show current version)"),includeSnapshotVersions);
        versions.setContentDescription(tr("包含历史快照正文","Include historical snapshots")); fields.addView(versions);
        CheckBox durationSort = check(tr("按最长单次停留排序", "Sort by longest single dwell"), sortByDwell);
        durationSort.setId(ID_SORT_DWELL); fields.addView(durationSort);
        EditText author = entry(tr("作者（不含 @）", "Author (without @)"), authorFilter);
        EditText source = entry(tr("来源：home_for_you / home_following / profile / search_results", "Source: home_for_you / home_following / profile / search_results"), sourceFilter);
        EditText tag = entry(tr("标签", "Tag"), tagFilter);
        fields.addView(author); fields.addView(source); fields.addView(tag);
        Button basis = button(tr("时间依据：", "Date basis: ") + basisName(dateBasis));
        fields.addView(basis);
        final ArchiveSearch.DateField[] selectedBasis = {dateBasis};
        basis.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle(tr("时间依据", "Date basis"))
            .setItems(basisNames(), (d, n) -> {
                selectedBasis[0] = ArchiveSearch.DateField.values()[n];
                basis.setText(tr("时间依据：", "Date basis: ") + basisName(selectedBasis[0]));
            }).show());
        ScrollView scroll = new ScrollView(this); scroll.addView(fields);
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(tr("离线筛选与排序", "Offline filters & sort"))
            .setView(scroll)
            .setNegativeButton(tr("取消", "Cancel"), null)
            .setNeutralButton(tr("重置", "Reset"), (d, n) -> {
                minDwellMs = null; neverOpened = false; favoriteOnly = false; sortByDwell = false; includeSnapshotVersions=false;
                authorFilter = null; sourceFilter = null; tagFilter = null; actionStateFilter = null;
                dateBasis = ArchiveSearch.DateField.LAST_SEEN;
                selectedFolder = null; actionType = null;
                folderFilter.setText(tr("全部分类", "All folders"));
                typeFilter.setText(tr("全部类型", "All types"));
                extraFilter.setText(tr("筛选与排序", "Filter & sort"));
                if (mode == Mode.FEED) setMode(Mode.SEARCH); else refresh();
            })
            .setPositiveButton(tr("应用", "Apply"), null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            Long threshold = null;
            String value = dwell.getText().toString().trim();
            if (!value.isEmpty()) {
                try {
                    double seconds = Double.parseDouble(value);
                    if (Double.isNaN(seconds) || Double.isInfinite(seconds) || seconds < 0 || seconds > 86400) {
                        throw new NumberFormatException();
                    }
                    threshold = (long) Math.ceil(seconds * 1000.0);
                } catch (NumberFormatException bad) {
                    dwell.setError(tr("请输入 0～86400 秒", "Enter 0–86400 seconds")); return;
                }
            }
            actionStateFilter=actionState[0];
            minDwellMs = threshold; neverOpened = never.isChecked();
            favoriteOnly = favorites.isChecked(); sortByDwell = durationSort.isChecked(); includeSnapshotVersions=versions.isChecked();
            authorFilter = emptyToNull(author.getText().toString().trim().replaceFirst("^@", ""));
            sourceFilter = emptyToNull(source.getText().toString().trim());
            tagFilter = emptyToNull(tag.getText().toString().trim());
            dateBasis = selectedBasis[0];
            selectedFolder = categoryChoice[0]; actionType = activityChoice[0];
            folderFilter.setText(chooseCategory.getText()); typeFilter.setText(chooseType.getText());
            extraFilter.setText(tr("已应用筛选 · 修改", "Filters applied · Edit"));
            dialog.dismiss();
            if (mode == Mode.FEED) setMode(Mode.SEARCH); else refresh();
        }));
        dialog.show();
    }

    private void showPost(Row row) {
        String snapshot = row.body == null || row.body.trim().isEmpty() ?
            tr("仅链接；尚无正文快照。", "Link only; no text snapshot yet.") : row.body;
        TextView body = text(row.detail + "\n\n" + snapshot);
        body.setTextIsSelectable(true); body.setPadding(px(16), px(8), px(16), px(8));
        ScrollView scroll = new ScrollView(this); scroll.addView(body);
        new AlertDialog.Builder(this).setTitle("#" + row.id).setView(scroll)
            .setNegativeButton(tr("关闭", "Close"), null)
            .setNeutralButton(tr("整理", "Organize"), (d, n) -> showPostActions(row))
            .setPositiveButton(tr("打开原帖", "Open original"), (d, n) -> openPost(row.id)).show();
    }

    private void showPostActions(Row row) {
        if (clearing) return;
        new AlertDialog.Builder(this).setTitle(tr("本地资料操作", "Local library actions"))
            .setItems(new String[]{tr("收藏到 Xs", "Favorite in Xs"), tr("分类", "Folders"),
                tr("编辑收藏备注与标签", "Edit favorite note & tags"),
                tr("取消 Xs 收藏", "Remove Xs favorite"), tr("分享链接", "Share link"), tr("本人行为轨迹", "Own activity trail"), tr("快照版本", "Snapshot versions")}, (d, n) -> {
                if (n == 0) saveFavorite(row, false);
                else if (n == 1) organizePost(row.id);
                else if (n == 2) saveFavorite(row, true);
                else if (n == 3) {
                    final String owner = library;
                    write(owner, () -> archive.removeFavorite(owner, row.id),
                        tr("已取消收藏；历史和资源保留", "Favorite removed; history and assets kept"));
                }
                else if (n == 5) showOwnActionTrail(row.id);
                else if (n == 6) showSnapshots(row.id);
                else {
                    Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, postUrl(row.id));
                    try { startActivity(Intent.createChooser(share, tr("分享原帖链接", "Share original link"))); }
                    catch (ActivityNotFoundException ignored) { toast(tr("没有兼容的分享应用", "No compatible share app")); }
                }
            }).show();
    }

    private void resetSelection() { selecting=false;selectedPosts.clear();updateSelection(); }
    private void updateSelection() {
        if(batchButton==null||results==null)return;
        batchButton.setText(selecting ? tr("已选 ","Selected ")+selectedPosts.size() : tr("批量","Select"));
        results.setChoiceMode(selecting?ListView.CHOICE_MODE_MULTIPLE:ListView.CHOICE_MODE_NONE);
        for(int i=0;i<visible.size();i++)results.setItemChecked(i,selecting&&selectedPosts.contains(visible.get(i).id));
    }
    private void batchMenu() {
        if(clearing||mode==Mode.FOLDERS)return;
        if(!selecting){selecting=true;updateSelection();toast(tr("点选帖子，可跨页选择最多 200 条。再次点“已选”执行批量操作。","Tap posts to select up to 200 across pages, then tap Selected for actions."));return;}
        final String owner=library;
        final List<String> ids=new ArrayList<>(selectedPosts);
        new AlertDialog.Builder(this).setTitle(tr("所选帖子：","Selected posts: ")+ids.size())
            .setItems(new String[]{tr("选择已加载的帖子","Select loaded posts"),tr("清空选择","Clear selection"),
                tr("收藏所选到 Xs","Favorite selected in Xs"),tr("加入分类","Add to folder"),
                tr("收藏并追加标签","Favorite & append tags"),tr("收藏并替换标签","Favorite & replace tags"),
                tr("取消所选收藏","Unfavorite selected"),tr("退出选择","Exit selection")},(dialog,which)->{
                if(which==0){for(Row row:visible)if(!row.folder&&selectedPosts.size()<200)selectedPosts.add(row.id);updateSelection();return;}
                if(which==1){selectedPosts.clear();updateSelection();return;}
                if(which==7){resetSelection();return;}
                if(ids.isEmpty()){toast(tr("请先选择帖子","Select posts first"));return;}
                if(which==2)write(owner,()->XsArchiveLibraryOperations.favorite(archive,owner,ids),tr("已收藏所选帖子","Selected posts saved"),this::resetSelection);
                else if(which==3)chooseFolder((id,label)->{
                    if(id!=null)write(owner,()->XsArchiveLibraryOperations.assignCategory(archive,owner,ids,id),tr("所选已分类","Selected posts organized"),this::resetSelection);
                });
                else if(which==4||which==5){
                    EditText tags=entry(tr("标签，以逗号分隔；最多 20 个","Comma-separated tags; up to 20"),null);
                    tags.setContentDescription(tr("批量标签","Batch tags"));
                    new AlertDialog.Builder(this).setTitle(which==4?tr("收藏并追加标签","Favorite & append tags"):tr("收藏并替换标签","Favorite & replace tags"))
                        .setView(tags).setNegativeButton(tr("取消","Cancel"),null).setPositiveButton(tr("应用","Apply"),(d,n)->
                            write(owner,()->XsArchiveLibraryOperations.tags(archive,owner,ids,tags.getText().toString(),which==4),tr("所选收藏标签已保存","Selected favorite tags saved"),this::resetSelection)).show();
                } else if(which==6)new AlertDialog.Builder(this).setMessage(tr("取消所选 Xs 收藏？历史、分类和已下载资源保留。","Remove selected Xs favorites? History, folders and downloaded files remain."))
                    .setNegativeButton(tr("取消","Cancel"),null).setPositiveButton(tr("取消收藏","Unfavorite"),(d,n)->
                        write(owner,()->XsArchiveLibraryOperations.unfavorite(archive,owner,ids),tr("所选收藏已取消","Selected favorites removed"),this::resetSelection)).show();
            }).show();
    }
    private void showSnapshots(String postId) { new SnapshotBrowser(library,postId).show(); }

    /** One bounded page is retained. Text bodies are fetched only after a row is selected. */
    private final class SnapshotBrowser {
        private final String owner,postId;
        private final List<String> keys=new ArrayList<>(),labels=new ArrayList<>();
        private final EditText keyword=entry(tr("搜索各版本正文","Search version text"),null);
        private final TextView pageStatus=text("");
        private final ListView versions=new ListView(XsHistoryActivity.this);
        private final ArrayAdapter<String> versionsAdapter=new ArrayAdapter<>(XsHistoryActivity.this,android.R.layout.simple_list_item_1,labels);
        private final Button previous=button(tr("上一页","Previous")),next=button(tr("下一页","Next"));
        private AlertDialog dialog;private boolean active=true;private int offset,generation;
        private final Runnable changed=()->{offset=0;load();};
        SnapshotBrowser(String owner,String postId){this.owner=owner;this.postId=postId;}
        boolean current(){return active&&!destroyed&&owner.equals(library)&&!XsLibraryWrites.isDeleted(owner);}
        void show(){
            LinearLayout fields=paddedColumn();
            fields.addView(text(tr("仅浏览本机版本，不替换当前正文。每页50条，可翻页或搜索旧版本。","Read local versions without replacing current text. Pages hold 50 versions; browse or search older text.")));
            keyword.setSingleLine(true);keyword.setContentDescription(tr("版本正文关键词","Snapshot text keyword"));fields.addView(keyword);
            pageStatus.setContentDescription(tr("版本页状态","Snapshot page status"));fields.addView(pageStatus);
            versions.setContentDescription(tr("快照版本列表","Snapshot version list"));versions.setAdapter(versionsAdapter);
            int height=Math.max(px(120),Math.min(px(280),getResources().getDisplayMetrics().heightPixels/3));
            fields.addView(versions,new LinearLayout.LayoutParams(-1,height));
            LinearLayout navigation=horizontal();previous.setContentDescription(tr("上一页版本","Previous snapshot page"));next.setContentDescription(tr("下一页版本","Next snapshot page"));
            navigation.addView(previous,weighted());navigation.addView(next,weighted());fields.addView(navigation);
            previous.setOnClickListener(v->{if(offset>0){offset=Math.max(0,offset-SNAPSHOT_PAGE_SIZE);load();}});
            next.setOnClickListener(v->{if(offset<=Integer.MAX_VALUE-SNAPSHOT_PAGE_SIZE){offset+=SNAPSHOT_PAGE_SIZE;load();}});
            versions.setOnItemClickListener((parent,view,position,id)->{if(position<keys.size())open(keys.get(position),labels.get(position));});
            keyword.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence value,int start,int count,int after){}public void afterTextChanged(Editable value){}public void onTextChanged(CharSequence value,int start,int before,int count){main.removeCallbacks(changed);main.postDelayed(changed,250);}});
            dialog=new AlertDialog.Builder(XsHistoryActivity.this).setTitle(tr("本机快照版本","Local snapshot versions")).setView(fields)
                .setNeutralButton(tr("重新查询","Reload"),null).setNegativeButton(tr("关闭","Close"),null).create();
            dialog.setOnDismissListener(d->{active=false;generation++;main.removeCallbacks(changed);});dialog.show();
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->load());load();
        }
        void load(){
            if(!current()){if(dialog!=null)dialog.dismiss();return;}
            final int request=++generation,pageOffset=offset;final String query=keyword.getText().toString().trim();
            keys.clear();labels.clear();versionsAdapter.notifyDataSetChanged();versions.setEnabled(false);previous.setEnabled(false);next.setEnabled(false);
            pageStatus.setText(tr("正在查询版本…","Loading versions…"));
            read(()->{
                List<String> foundKeys=new ArrayList<>(),foundLabels=new ArrayList<>();boolean more=false;
                try(Cursor rows=archive.searchSnapshots(owner,postId,query,SNAPSHOT_PAGE_SIZE+1,pageOffset)){
                    while(rows.moveToNext()){
                        if(foundKeys.size()==SNAPSHOT_PAGE_SIZE){more=true;break;}
                        foundKeys.add(rows.getString(0));String body=rows.isNull(2)?tr("仅链接","Link only"):rows.getString(2).replace('\n',' ');
                        String preview=body.length()>90?body.substring(0,90)+"…":body;
                        foundLabels.add((rows.getInt(8)!=0?tr("当前 · ","Current · "):"")+(rows.isNull(4)?tr("时间未知","Time unknown"):date(rows.getLong(4)))+" · "+rows.getString(6)+"\n"+preview);
                    }
                }catch(RuntimeException failure){main.post(()->{if(current()&&request==generation){pageStatus.setText(tr("版本查询失败，请重新查询","Version query failed; reload"));previous.setEnabled(pageOffset>0);}});return;}
                final boolean hasNext=more;
                main.post(()->{
                    if(!current()){if(dialog!=null)dialog.dismiss();return;}if(request!=generation)return;
                    keys.addAll(foundKeys);labels.addAll(foundLabels);versionsAdapter.notifyDataSetChanged();versions.setEnabled(true);
                    previous.setEnabled(pageOffset>0);next.setEnabled(hasNext&&pageOffset<=Integer.MAX_VALUE-SNAPSHOT_PAGE_SIZE);
                    pageStatus.setText(tr("第 ","Page ")+(pageOffset/SNAPSHOT_PAGE_SIZE+1)+tr(" 页 · "," · ")+foundKeys.size()+tr(" 条（每页50条）"," shown (50 per page)"));
                });
            });
        }
        void open(String key,String title){
            read(()->{
                String detail=null;try(Cursor row=archive.getSnapshot(owner,postId,key)){
                    if(row.moveToFirst())detail=(row.isNull(1)?"":"@"+row.getString(1)+"\n")+tr("来源：","Source: ")+row.getString(5)+"\n\n"+(row.isNull(2)?tr("仅链接","Link only"):row.getString(2));
                }catch(RuntimeException unavailable){/* Missing/deleted versions are not restored by browsing. */}
                final String body=detail;main.post(()->{
                    if(!current())return;if(body==null){toast(tr("此版本已不可用，请重新查询","Version unavailable; reload"));return;}
                    TextView text=text(body);text.setTextIsSelectable(true);ScrollView scroll=new ScrollView(XsHistoryActivity.this);scroll.addView(text);
                    new AlertDialog.Builder(XsHistoryActivity.this).setTitle(title).setView(scroll).setPositiveButton(tr("关闭","Close"),null).show();
                });
            });
        }
    }

    private void showOwnActionTrail(String postId) {
        final String owner=library;
        read(() -> {
            StringBuilder details=new StringBuilder(tr("导入与旧记录的当前平台状态可能未知；身份来源不是网页登录凭证。\n\n",
                "Imported and legacy entries may have unknown current state. Provenance does not verify a web login.\n\n"));
            try (Cursor c=archive.ownActions(owner,postId)) {
                while (c.moveToNext()) {
                    details.append(c.getString(2)).append(" / ").append(c.getString(8)).append("\n")
                        .append(tr("执行者：", "Actor: ")).append(c.isNull(3) ? tr("未知", "Unknown") : c.getString(3)).append("\n")
                        .append(c.getString(4)).append(" · ").append(c.getString(5)).append("\n")
                        .append(tr("行为时间：", "Action time: ")).append(c.isNull(6) ? tr("未知", "Unknown") : date(c.getLong(6))).append("\n")
                        .append(c.getString(10)).append(" · ").append(c.isNull(11) ? tr("事件时间未知", "Event time unknown") : date(c.getLong(11)))
                        .append(tr(" · 本机收录 ", " · Indexed ")).append(c.isNull(12) ? tr("时间未知", "time unknown") : date(c.getLong(12))).append("\n");
                    if (!c.isNull(14)) details.append(tr("关联原帖：", "Related post: ")).append(postUrl(c.getString(14))).append("\n");
                    details.append("\n");
                }
            }
            main.post(() -> {
                if (destroyed || !owner.equals(library)) return;
                TextView text=text(details.toString()); text.setTextIsSelectable(true);
                text.setPadding(px(16),px(12),px(16),px(12)); ScrollView scroll=new ScrollView(this); scroll.addView(text);
                new AlertDialog.Builder(this).setTitle(tr("本人行为轨迹", "Own activity trail")).setView(scroll)
                    .setPositiveButton(tr("关闭", "Close"),null).show();
            });
        });
    }

    private void saveFavorite(Row row, boolean editAfter) {
        final String owner = library;
        write(owner, () -> archive.favorite(owner, postUrl(row.id), row.author, row.body,
            row.publishedAt, System.currentTimeMillis()), null, () -> {
                toast(tr("已收藏到 Xs", "Saved in Xs"));
                if (editAfter) editFavorite(row); else organizePost(row.id);
            });
    }

    private void editFavorite(Row row) {
        final String owner = library;
        // Fetch details freshly: a timeline row need not include favorite metadata.
        read(() -> {
            String note = "", tags = "";
            try (Cursor c = archive.getReadableDatabase().rawQuery(
                "SELECT f.note,(SELECT GROUP_CONCAT(t.tag, ', ') FROM post_tags t " +
                "WHERE t.account_id=f.account_id AND t.post_id=f.post_id) AS tags " +
                "FROM favorites f WHERE f.account_id=? AND f.post_id=?", new String[]{owner, row.id})) {
                if (c.moveToFirst()) { note = c.getString(0); tags = c.getString(1); }
            } catch (RuntimeException failure) { main.post(() -> toast(tr("无法读取收藏", "Unable to read favorite"))); return; }
            final String initialNote = note, initialTags = tags;
            main.post(() -> {
                if (destroyed || !owner.equals(library)) return;
                LinearLayout fields = paddedColumn();
                EditText noteField = entry(tr("私人备注（默认不分享）", "Private note (excluded from sharing)"), initialNote);
                noteField.setSingleLine(false); noteField.setMinLines(3);
                noteField.setContentDescription(tr("收藏备注", "Favorite note"));
                EditText tagsField = entry(tr("标签，以逗号分隔", "Tags, separated by commas"), initialTags);
                tagsField.setContentDescription(tr("收藏标签", "Favorite tags"));
                fields.addView(noteField); fields.addView(tagsField);
                new AlertDialog.Builder(this).setTitle(tr("Xs 收藏备注与标签", "Xs favorite note & tags"))
                    .setView(fields).setNegativeButton(tr("取消", "Cancel"), null)
                    .setPositiveButton(tr("保存", "Save"), (d, n) -> {
                        String value = noteField.getText().toString();
                        String labels = tagsField.getText().toString();
                        write(owner, () -> archive.putFavoriteDetails(owner, row.id, value, labels),
                            tr("备注与标签已保存", "Note & tags saved"));
                    }).show();
            });
        });
    }

    /** Native backup/consolidation may select an existing scope; never create one from an intent. */
    private void openRequestedLibrary() {
        final String wanted=getIntent().getStringExtra(EXTRA_LIBRARY);
        if(wanted == null || wanted.length() > 256 || wanted.indexOf('\u0000') >= 0) return;
        read(() -> {
            String found=null,label=null;
            try(Cursor libraries=archive.libraries()) {
                while(libraries.moveToNext()) if(wanted.equals(libraries.getString(3)) && !XsDeletedLibraries.isDeleted(this,wanted)) {
                    found=libraries.getString(3); label=libraries.getString(1); break;
                }
            }
            final String selected=found, selectedLabel=label;
            main.post(() -> {
                if(destroyed || !defaultLibrary.equals(library)) return;
                if(selected == null || XsLibraryWrites.isDeleted(selected)) { toast(tr("指定资料空间不存在", "Requested library does not exist")); return; }
                requested.incrementAndGet(); library=selected; resetSelection();
                libraryButton.setText(library.equals(defaultLibrary) ? tr("Xs 本机资料库", "Xs local library") :
                    tr("独立资料空间：", "Separate library: ") + selectedLabel);
                maintainAndRefresh();
            });
        });
    }

    private void chooseLibrary() {
        if (clearing) return;
        read(() -> {
            List<String> ids = new ArrayList<>(), names = new ArrayList<>();
            try (Cursor c = archive.libraries()) {
                while (c.moveToNext()) {
                    String id = c.getString(3); // physical scope is intentionally retained
                    if(XsDeletedLibraries.isDeleted(this,id))continue;
                    ids.add(id);
                    names.add((id.equals(defaultLibrary) ? tr("Xs 本机资料库", "Xs local library") :
                        tr("独立资料空间：", "Separate library: ") + c.getString(1)));
                }
            }
            for(String pending:XsDeletedLibraries.pending(this)){ids.add(pending);names.add(tr("待清理资料空间：", "Pending cleanup: ")+pending);}
            main.post(() -> {
                if (destroyed) return;
                new AlertDialog.Builder(this).setTitle(tr("选择独立资料空间（不会合并）", "Choose a separate library"))
                    .setItems(names.toArray(new String[0]), (d, n) -> {
                        if(XsDeletedLibraries.isDeleted(this,ids.get(n))){startActivity(XsLibraryManagementActivity.intent(this,ids.get(n)));return;}
                        requested.incrementAndGet(); library = ids.get(n); resetSelection();
                        selectedFolder = null; folderFilter.setText(tr("全部分类", "All folders"));
                        libraryButton.setText(names.get(n)); maintainAndRefresh();
                    }).setNeutralButton(tr("合并资料空间", "Combine libraries"),(d,n) ->
                        startActivity(XsLibrariesActivity.intent(this)))
                    .setNegativeButton(tr("取消", "Cancel"), null).show();
            });
        });
    }

    private void showSettings() {
        LinearLayout fields = paddedColumn();
        fields.addView(text(tr("偏好仅在经验证的观察页面生效。当前真实 X 自动采集尚未验收，只有合成测试页面可启用。",
            "Preferences apply only to validated observer pages. Live X capture is unverified and disabled; synthetic test pages only.")));
        Switch opens = new Switch(this); opens.setText(tr("记录主动打开的帖子", "Record deliberate post opens"));
        opens.setChecked(XsLibrarySettings.isOpenEnabled(this)); fields.addView(opens);
        Switch feed = new Switch(this); feed.setText(tr("记录时间线停留（独立开关）", "Record feed dwell (independent)"));
        feed.setChecked(XsLibrarySettings.isFeedEnabled(this)); fields.addView(feed);
        long initial = XsLibrarySettings.getDwellThresholdMs(this);
        final long[] threshold = {initial};
        Button duration = button(tr("停留阈值：", "Dwell threshold: ") + duration(initial));
        fields.addView(duration);
        duration.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle(tr("仅影响后续曝光", "Applies to future exposures"))
            .setItems(new String[]{tr("1.2 秒 · 灵敏", "1.2 s · Sensitive"),
                tr("3 秒 · 推荐默认", "3 s · Recommended"), tr("5 秒 · 精选", "5 s · Selective")}, (d, n) -> {
                    threshold[0] = new long[]{1200,3000,5000}[n];
                    duration.setText(tr("停留阈值：", "Dwell threshold: ") + duration(threshold[0]));
                }).show());
        fields.addView(text(tr("停留表示可见曝光，不是已读证明。关闭记录会保留已有资料。",
            "Dwell measures visible exposure, not completed reading. Turning recording off preserves saved data.")));
        Switch ads=new Switch(this); ads.setText(tr("隐藏推广（合成夹具）", "Hide ads (synthetic fixtures)"));
        ads.setChecked(XsLibrarySettings.isAdCleanupEnabled(this)); fields.addView(ads);
        Switch subscription=new Switch(this); subscription.setText(tr("隐藏会员营销（合成夹具）", "Hide subscription marketing (synthetic fixtures)"));
        subscription.setChecked(XsLibrarySettings.isSubscriptionCleanupEnabled(this)); fields.addView(subscription);
        Switch recommendations=new Switch(this); recommendations.setText(tr("隐藏推荐（合成夹具）", "Hide recommendations (synthetic fixtures)"));
        recommendations.setChecked(XsLibrarySettings.isRecommendationCleanupEnabled(this)); fields.addView(recommendations);
        fields.addView(text(tr("精简关闭时恢复页面；真实 X 规则尚未验收，偏好不启用真实网页精简。", "Turning cleanup off restores the page. Live X rules remain unvalidated; these preferences do not enable live cleanup.")));
        ScrollView preferences=new ScrollView(this); preferences.addView(fields);
        new AlertDialog.Builder(this).setTitle(tr("记录设置", "Recording preferences"))
            .setView(preferences).setNegativeButton(tr("取消", "Cancel"), null)
            .setPositiveButton(tr("保存", "Save"), (d, n) -> {
                XsLibrarySettings.setOpenEnabled(this, opens.isChecked());
                XsLibrarySettings.setFeedEnabled(this, feed.isChecked());
                XsLibrarySettings.setDwellThresholdMs(this, threshold[0]);
                XsLibrarySettings.setAdCleanupEnabled(this, ads.isChecked());
                XsLibrarySettings.setSubscriptionCleanupEnabled(this, subscription.isChecked());
                XsLibrarySettings.setRecommendationCleanupEnabled(this, recommendations.isChecked());
                XsLibraryWrites.invalidate(defaultLibrary);
                toast(tr("偏好已保存；真实 X 采集仍待验收", "Preferences saved; live X capture remains gated"));
            }).show();
    }

    private void confirmErase() {
        if (clearing) return;
        final String owner = library;
        new AlertDialog.Builder(this).setTitle(tr("清空此空间的浏览历史？", "Clear this library's browsing history?"))
            .setMessage(tr("仅删除主动打开和停留事件，重置浏览计数。收藏、备注、分类、已下载资源与网页会话均保留。记录开关会关闭。",
                "Deletes open and dwell events and resets visit counts. Favorites, notes, folders, downloaded assets and web sessions remain. Recording is turned off."))
            .setNegativeButton(tr("取消", "Cancel"), null)
            .setPositiveButton(tr("清历史", "Clear history"), (d, n) -> {
                clearing = true;
                XsLibrarySettings.setOpenEnabled(this, false); XsLibrarySettings.setFeedEnabled(this, false);
                databaseUsers.incrementAndGet();
                boolean accepted = XsLibraryWrites.invalidateAndSubmit(owner, () -> {
                    boolean ok = true;
                    try { archive.clearHistory(owner); } catch (RuntimeException failure) { ok = false; }
                    finally { releaseDatabase(); }
                    final boolean result = ok;
                    main.post(() -> {
                        clearing = false;
                        if (!destroyed && owner.equals(library)) refresh();
                        toast(result ? tr("历史已清空；收藏和资源保留", "History cleared; favorites and assets kept") :
                            tr("清理失败", "Unable to clear history"));
                    });
                });
                if (!accepted) { releaseDatabase(); clearing = false; toast(tr("本地任务繁忙，请重试", "Local queue busy; retry")); }
            }).show();
    }

    private void chooseDate() {
        String[] labels = zh ? new String[]{"全部时间","最近 24 小时","最近 7 天","最近 30 天"} :
            new String[]{"All time","Last 24 hours","Last 7 days","Last 30 days"};
        new AlertDialog.Builder(this).setTitle(tr("时间筛选", "Date range"))
            .setSingleChoiceItems(labels, dateChoice, (d, n) -> {
                dateChoice = n; minTime = n == 0 ? null : System.currentTimeMillis() -
                    86400000L * (n == 1 ? 1 : n == 2 ? 7 : 30);
                dateFilter.setText(labels[n]); d.dismiss(); refresh();
            }).setNegativeButton(tr("取消", "Cancel"), null).show();
    }

    private String[] basisNames() {
        return zh ? new String[]{"最近收录时间","主动打开时间","停留时间","帖子发布时间","本人操作时间","Xs 收藏时间"} :
            new String[]{"Last saved","Last opened","Dwell time","Published","Own action","Xs favorite time"};
    }
    private String basisName(ArchiveSearch.DateField field) { return basisNames()[field.ordinal()]; }

    private interface Choice { void selected(String id, String label); }
    private void chooseActionType() {
        chooseActionType((id, label) -> { actionType = id; typeFilter.setText(label); refresh(); });
    }
    private void chooseActionType(Choice choice) {
        String[] types = {null,"ORIGINAL","REPLY","QUOTE","REPOST","BOOKMARK","LOCAL_SAVE"};
        String[] names = zh ? new String[]{"全部类型","原创","回复","引用","转帖","X 书签记录","手动链接"} :
            new String[]{"All types","Original","Reply","Quote","Repost","X bookmark record","Manual link"};
        new AlertDialog.Builder(this).setTitle(tr("本地收录类型（查看归属来源）", "Indexed type (check provenance)"))
            .setItems(names, (d, n) -> choice.selected(types[n], names[n])).show();
    }

    private void chooseFolder() {
        chooseFolder((id, label) -> { selectedFolder = id; folderFilter.setText(label); refresh(); });
    }
    private void chooseFolder(Choice choice) {
        final String owner = library;
        read(() -> {
            List<String> ids = new ArrayList<>(), labels = new ArrayList<>();
            ids.add(null); labels.add(tr("全部分类", "All folders"));
            try (Cursor c = archive.categories(owner)) {
                while (c.moveToNext()) { ids.add(c.getString(0)); labels.add(c.getString(1)); }
            }
            main.post(() -> {
                if (destroyed || !owner.equals(library)) return;
                new AlertDialog.Builder(this).setTitle(tr("筛选分类", "Filter folder"))
                    .setItems(labels.toArray(new String[0]), (d, n) -> choice.selected(ids.get(n), labels.get(n))).show();
            });
        });
    }

    private void organizePost(String postId) {
        final String owner = library;
        read(() -> {
            List<String> ids = new ArrayList<>(), labels = new ArrayList<>();
            try (Cursor c = archive.categories(owner)) {
                while (c.moveToNext()) { ids.add(c.getString(0)); labels.add(c.getString(1)); }
            }
            main.post(() -> {
                if (destroyed || !owner.equals(library)) return;
                if (ids.isEmpty()) { toast(tr("请先新建分类，再整理此帖", "Create a folder to organize this post")); return; }
                new AlertDialog.Builder(this).setTitle(tr("加入本地分类", "Add to local folder"))
                    .setItems(labels.toArray(new String[0]), (d, n) ->
                        write(owner, () -> archive.assignCategory(owner, postId, ids.get(n)),
                            tr("已加入分类", "Added to folder"))).show();
            });
        });
    }

    private void editFolder(Row row) {
        final String owner = library;
        new AlertDialog.Builder(this).setTitle(row.headline)
            .setItems(new String[]{tr("重命名", "Rename"),tr("删除分类", "Delete folder")}, (d, n) -> {
                if (n == 0) showFolderEditor(row.id, row.headline);
                else new AlertDialog.Builder(this).setMessage(tr("只删除分类，帖子和收藏保留。", "Deletes only the folder; posts and favorites remain."))
                    .setNegativeButton(tr("取消", "Cancel"), null)
                    .setPositiveButton(tr("删除", "Delete"), (dialog, choice) ->
                        write(owner, () -> archive.deleteCategory(owner, row.id), null, () -> {
                            if (row.id.equals(selectedFolder)) { selectedFolder = null; folderFilter.setText(tr("全部分类", "All folders")); }
                        })).show();
            }).show();
    }

    private void showFolderEditor(String existingId, String initial) {
        if (clearing) return;
        final String owner = library;
        EditText field = entry(tr("分类名称", "Folder name"), initial);
        new AlertDialog.Builder(this).setTitle(tr("本地分类", "Local folder")).setView(field)
            .setNegativeButton(tr("取消", "Cancel"), null)
            .setPositiveButton(tr("保存", "Save"), (d, n) -> {
                String value = field.getText().toString().trim();
                if (value.isEmpty() || value.length() > 120) { toast(tr("名称需为 1～120 字", "Name must be 1–120 characters")); return; }
                String id = existingId == null ? UUID.randomUUID().toString() : existingId;
                write(owner, () -> archive.putCategory(owner, id, value), tr("分类已保存", "Folder saved"));
            }).show();
    }

    private void chooseImportFile() {
        if (clearing) return;
        new AlertDialog.Builder(this).setTitle(tr("导入本人帖子档案", "Import my posts archive"))
            .setMessage(tr("选择 X 官方档案中的 tweets.js 单个分段。请确认文件中的帖子属于你本人；它将保存到当前 Xs 空间，不表示网页已登录此账号。仅本机读取，不上传。",
                "Select one tweets.js part from your official X archive. Confirm the posts are yours. They are saved to this Xs space; this does not verify a web login. The selected file is read locally."))
            .setNegativeButton(tr("取消", "Cancel"), null)
            .setPositiveButton(tr("确认本人归属并选文件", "Confirm ownership & choose"), (d, n) -> {
                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                pendingImportLibrary = library;
                pendingImportTicket = XsLibraryWrites.ticket(library);
                try { startActivityForResult(pick, REQUEST_POSTS_JS); }
                catch (ActivityNotFoundException failure) { toast(tr("没有可用文件选择器", "No document picker available")); }
            }).show();
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != REQUEST_POSTS_JS || result != RESULT_OK || data == null || data.getData() == null || clearing) return;
        final String owner = pendingImportLibrary;
        final XsLibraryWrites.Ticket ticket = pendingImportTicket;
        pendingImportLibrary = null; pendingImportTicket = null;
        if (owner == null || !XsLibraryWrites.isCurrent(ticket)) {
            toast(tr("资料空间已变化，请重新选择文件", "Library context changed; select the file again"));
            return;
        }
        final Uri uri = data.getData();
        write(owner, ticket, () -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("No document");
                XsArchiveImporter.importJs(input, owner, archive, System.currentTimeMillis());
            } catch (IOException failure) { throw new IllegalStateException("Cannot read selected post archive"); }
        }, tr("档案已导入本机；未创建打开或停留事件", "Imported locally without open or dwell events"), () -> setMode(Mode.SEARCH));
    }

    private void read(Runnable operation) {
        if (destroyed) return;
        databaseUsers.incrementAndGet();
        try {
            reader.execute(() -> { try { operation.run(); } finally { releaseDatabase(); } });
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            releaseDatabase();
            if (!destroyed) { more.setEnabled(true); toast(tr("本地查询繁忙，请重试", "Local search busy; retry")); }
        }
    }

    private void releaseDatabase() {
        if (databaseUsers.decrementAndGet() == 0) archive.close();
    }

    private void write(String owner, Runnable work, String success) { write(owner, work, success, null); }
    private void write(String owner, Runnable work, String success, Runnable after) {
        if(clearing||destroyed)return;
        try{write(owner, XsLibraryWrites.ticket(owner), work, success, after);}
        catch(IllegalStateException expired){toast(tr("资料空间已失效或任务繁忙，请重试","Library expired or tasks busy; retry"));}
    }
    private void write(String owner, XsLibraryWrites.Ticket ticket, Runnable work, String success, Runnable after) {
        if (clearing || destroyed) return;
        databaseUsers.incrementAndGet();
        boolean queued = XsLibraryWrites.submit(ticket, () -> {
            boolean ok = true;
            try { work.run(); } catch (RuntimeException failure) { ok = false; }
            finally { releaseDatabase(); }
            final boolean result = ok;
            main.post(() -> {
                if (destroyed || !owner.equals(library)) return;
                if (result) {
                    if (success != null) toast(success);
                    if (after != null) after.run();
                    refresh();
                } else toast(tr("保存失败，请检查输入或存储空间", "Save failed; check input or available storage"));
            });
        }, this::releaseDatabase);
        if (!queued) { releaseDatabase(); toast(tr("本地任务已失效或队列繁忙，请重试", "Local task expired or queue busy; retry")); }
    }

    private void openPost(String id) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(postUrl(id))).setPackage(getPackageName());
        try { startActivity(intent); }
        catch (ActivityNotFoundException missing) { toast(tr("当前客户端无法打开原帖", "This client cannot open the post")); }
    }
    private static String postUrl(String id) { return "https://x.com/i/web/status/" + id; }
    private String headline(String author, String body, String id) {
        String value = (author == null || author.isEmpty() ? "" : "@" + author + "  ") +
            (body == null || body.trim().isEmpty() ? tr("仅链接 #", "Link only #") + id : body.replace('\n',' ').trim());
        return value.length() > 110 ? value.substring(0,110) + "…" : value;
    }
    private String duration(Long ms) {
        return ms == null ? tr("时长未知", "duration unknown") : String.format(Locale.getDefault(), "%.1f s", ms / 1000.0);
    }
    private String duration(Long ms, String quality) {
        if (ms == null) return duration(ms);
        boolean lowerBound = "SAMPLED_LOWER_BOUND".equals(quality) || "CAPPED_LOWER_BOUND".equals(quality);
        return (lowerBound ? "≥" : "") + duration(ms) + ("CAPPED_LOWER_BOUND".equals(quality) ?
            tr("（已达测量上限）", " (measurement cap)") : "");
    }
    private static String quality(Cursor cursor, String name) {
        int index = cursor.getColumnIndex(name);
        return index < 0 || cursor.isNull(index) ? null : cursor.getString(index);
    }
    private String sourceLabel(String value) {
        if ("home_for_you".equals(value)) return tr("为你推荐", "For you");
        if ("home_following".equals(value)) return tr("正在关注", "Following");
        if ("profile".equals(value)) return tr("个人主页", "Profile");
        if ("search_results".equals(value)) return tr("搜索", "Search");
        return value == null ? tr("来源未知", "Unknown source") : value;
    }
    private String date(long value) {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()).format(new Date(value));
    }
    private Button button(String label) { Button v = new Button(this); v.setAllCaps(false); v.setText(label); return v; }
    private TextView text(String value) { TextView v = new TextView(this); v.setText(value); v.setPadding(0,px(3),0,px(3)); return v; }
    private EditText entry(String hint, String initial) {
        EditText v = new EditText(this); v.setHint(hint); v.setSingleLine(true);
        if (initial != null) v.setText(initial); return v;
    }
    private CheckBox check(String label, boolean enabled) { CheckBox v = new CheckBox(this); v.setText(label); v.setChecked(enabled); return v; }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout paddedColumn() { LinearLayout v = column(); v.setPadding(px(16),px(8),px(16),px(8)); return v; }
    private LinearLayout horizontal() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); return v; }
    private LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,1); }
    private int px(int value) { return (int)(value * getResources().getDisplayMetrics().density + .5f); }
    private String tr(String chinese, String english) { return zh ? chinese : english; }
    private static String emptyToNull(String value) { return value.isEmpty() ? null : value; }
    private void toast(String value) { if (!destroyed) Toast.makeText(this,value,Toast.LENGTH_SHORT).show(); }

    @Override protected void onDestroy() {
        destroyed = true; requested.incrementAndGet(); main.removeCallbacks(delayedSearch);
        // Keep the helper alive until both queued reads and shared writes release it.
        reader.shutdown();
        releaseDatabase();
        super.onDestroy();
    }
}

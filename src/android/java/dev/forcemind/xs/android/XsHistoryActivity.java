package dev.forcemind.xs.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import dev.forcemind.xs.core.ArchiveSearch;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Native local archive screen, intended for *internal* activity launch from a
 * verified client integration. No X APIs, account tokens or network requests.
 */
public final class XsHistoryActivity extends Activity {
    private enum Mode { HISTORY, SEARCH, FOLDERS }

    private static final class Row {
        final String id;
        final String headline;
        final String detail;
        final boolean folder;

        Row(String id, String headline, String detail, boolean folder) {
            this.id = id;
            this.headline = headline;
            this.detail = detail;
            this.folder = folder;
        }
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicInteger requested = new AtomicInteger();
    private final List<Row> visible = new ArrayList<>();
    private XsArchiveDatabase archive;
    private Mode mode = Mode.HISTORY;
    private String account;
    private String selectedFolder;
    private String actionType;
    private Long minTime;
    private int dateChoice;
    private boolean zh;

    private TextView status;
    private EditText query;
    private ListView results;
    private Switch recorder;
    private Button folderFilter;
    private Button dateFilter;
    private Button typeFilter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        zh = Locale.getDefault().getLanguage().equalsIgnoreCase("zh");
        archive = new XsArchiveDatabase(this);
        buildScreen();
    }

    @Override
    protected void onResume() {
        super.onResume();
        String active = XsCaptureBridge.getActiveAccountId();
        if (account == null ? active != null : !account.equals(active)) {
            account = active;
            selectedFolder = null;
            actionType = null;
        }
        refreshRecordingState();
        refresh();
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px(14), px(12), px(14), px(10));
        setContentView(root);

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        TextView name = new TextView(this);
        name.setText("Xs");
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 23);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.addView(name, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button close = button(tr("关闭", "Close"));
        close.setOnClickListener(v -> finish());
        heading.addView(close);
        root.addView(heading);

        TextView intro = text(tr("本地历史 · 搜索 · 分类",
                                 "Local history · search · folders"));
        root.addView(intro);

        status = text("");
        root.addView(status);

        recorder = new Switch(this);
        recorder.setText(tr("记录主动打开的帖子（仅本机）",
                            "Save deliberate post opens (on-device only)"));
        recorder.setChecked(false);
        recorder.setOnCheckedChangeListener((view, enabled) -> {
            if (!XsCaptureBridge.setRecordingEnabled(this, enabled)) {
                refreshRecordingState();
                return;
            }
            status.setText(enabled ?
                tr("已为当前账号开启本地记录", "Local recording enabled") :
                tr("已关闭记录；原有历史仍保留", "Recording off; existing history kept"));
        });
        root.addView(recorder);

        LinearLayout tabs = horizontal();
        Button history = button(tr("历史", "History"));
        Button search = button(tr("搜索", "Search"));
        Button folders = button(tr("分类", "Folders"));
        tabs.addView(history, weighted());
        tabs.addView(search, weighted());
        tabs.addView(folders, weighted());
        root.addView(tabs);
        history.setOnClickListener(v -> setMode(Mode.HISTORY));
        search.setOnClickListener(v -> setMode(Mode.SEARCH));
        folders.setOnClickListener(v -> setMode(Mode.FOLDERS));

        query = new EditText(this);
        query.setSingleLine(true);
        query.setHint(tr("搜索本地正文或作者", "Search local text or author"));
        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (mode == Mode.SEARCH) refresh();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        root.addView(query);

        LinearLayout filters = horizontal();
        dateFilter = button(tr("全部时间", "All time"));
        folderFilter = button(tr("全部分类", "All folders"));
        typeFilter = button(tr("全部类型", "All types"));
        filters.addView(dateFilter, weighted());
        filters.addView(folderFilter, weighted());
        filters.addView(typeFilter, weighted());
        root.addView(filters);
        dateFilter.setOnClickListener(v -> chooseDate());
        folderFilter.setOnClickListener(v -> chooseFolder());
        typeFilter.setOnClickListener(v -> chooseActionType());

        results = new ListView(this);
        root.addView(results, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        results.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= visible.size()) return;
            Row entry = visible.get(position);
            if (entry.folder) {
                selectedFolder = entry.id;
                setMode(Mode.SEARCH);
            } else {
                openPost(entry.id);
            }
        });
        results.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= visible.size()) return false;
            Row row = visible.get(position);
            if (row.folder) editFolder(row);
            else organizePost(row.id);
            return true;
        });

        LinearLayout controls = horizontal();
        Button addFolder = button(tr("新建分类", "New folder"));
        Button erase = button(tr("清空本地记录", "Clear local history"));
        controls.addView(addFolder, weighted());
        controls.addView(erase, weighted());
        root.addView(controls);
        addFolder.setOnClickListener(v -> showFolderEditor(null, null));
        erase.setOnClickListener(v -> confirmErase());
        setMode(Mode.HISTORY);
    }

    private void refreshRecordingState() {
        boolean hasAccount = account != null;
        recorder.setOnCheckedChangeListener(null);
        recorder.setEnabled(hasAccount);
        recorder.setChecked(hasAccount && XsCaptureBridge.isRecordingEnabled());
        recorder.setOnCheckedChangeListener((view, enabled) -> {
            if (!XsCaptureBridge.setRecordingEnabled(this, enabled)) {
                refreshRecordingState();
                return;
            }
            status.setText(enabled ?
                tr("已为当前账号开启本地记录", "Local recording enabled") :
                tr("已关闭记录；原有历史仍保留", "Recording off; existing history kept"));
        });
        status.setText(hasAccount ?
            tr("当前账号已连接 · 数据仅在本机",
               "Active account linked · on-device only") :
            tr("尚未连接 X 账号（等待客户端适配器）",
               "X account not linked (client adapter pending)"));
    }

    private void setMode(Mode next) {
        mode = next;
        query.setVisibility(next == Mode.SEARCH ? View.VISIBLE : View.GONE);
        folderFilter.setVisibility(next == Mode.SEARCH ? View.VISIBLE : View.GONE);
        typeFilter.setVisibility(next == Mode.SEARCH ? View.VISIBLE : View.GONE);
        dateFilter.setVisibility(next == Mode.FOLDERS ? View.GONE : View.VISIBLE);
        refresh();
    }

    /** Never run database queries on the UI thread. */
    private void refresh() {
        int request = requested.incrementAndGet();
        final String owner = account;
        final Mode selectedMode = mode;
        final String searchText = query.getText().toString();
        final Long after = minTime;
        final String folder = selectedFolder;
        final String type = actionType;
        if (owner == null) {
            showRows(new ArrayList<>());
            return;
        }
        worker.execute(() -> {
            List<Row> rows = new ArrayList<>();
            try {
                if (selectedMode == Mode.FOLDERS) {
                    try (Cursor cursor = archive.categories(owner)) {
                        while (cursor.moveToNext()) {
                            rows.add(new Row(cursor.getString(0), cursor.getString(1),
                                tr("长按重命名或删除", "Long press to rename or delete"), true));
                        }
                    }
                } else if (selectedMode == Mode.HISTORY) {
                    try (Cursor cursor = archive.timeline(owner, after, 200)) {
                        while (cursor.moveToNext()) {
                            String postId = cursor.getString(1);
                            long openedAt = cursor.getLong(2);
                            rows.add(new Row(postId,
                                headline(cursor.getString(4), cursor.getString(5), postId),
                                date(openedAt) + "  ·  #" + postId, false));
                        }
                    }
                } else {
                    ArchiveSearch.Filter filter = new ArchiveSearch.Filter();
                    filter.keyword = searchText;
                    filter.categoryId = folder;
                    filter.actionType = type;
                    filter.fromMs = after;
                    filter.limit = 200;
                    try (Cursor cursor = archive.search(owner, filter)) {
                        while (cursor.moveToNext()) {
                            String postId = cursor.getString(1);
                            long openedAt = cursor.getLong(6);
                            int opens = cursor.getInt(7);
                            rows.add(new Row(postId,
                                headline(cursor.getString(2), cursor.getString(3), postId),
                                date(openedAt) + "  ·  " + tr("打开", "Opens") +
                                    ": " + opens, false));
                        }
                    }
                }
            } catch (RuntimeException error) {
                runOnUiThread(() -> {
                    if (requested.get() == request) {
                        Toast.makeText(this, tr("本地查询失败", "Local search failed"),
                            Toast.LENGTH_SHORT).show();
                    }
                });
                return;
            }
            runOnUiThread(() -> {
                if (requested.get() == request && selectedMode == mode && !isFinishing()) {
                    showRows(rows);
                }
            });
        });
    }

    private void showRows(List<Row> rows) {
        visible.clear();
        visible.addAll(rows);
        List<Map<String, String>> data = new ArrayList<>();
        for (Row row : rows) {
            Map<String, String> values = new HashMap<>();
            values.put("title", row.headline);
            values.put("detail", row.detail);
            data.add(values);
        }
        SimpleAdapter adapter = new SimpleAdapter(this, data,
            android.R.layout.simple_list_item_2,
            new String[]{"title", "detail"},
            new int[]{android.R.id.text1, android.R.id.text2});
        results.setAdapter(adapter);
        results.setEmptyView(null);
        if (rows.isEmpty()) {
            status.setText(account == null ?
                tr("等待接入当前 X 账号", "Waiting for X account integration") :
                tr("本地暂时没有匹配的记录", "No matching local records"));
        } else if (account != null) {
            status.setText(tr("当前账号 · 仅本机 · ", "Active account · on-device · ") +
                rows.size() + tr(" 条结果", " results"));
        }
    }

    private void chooseDate() {
        String[] names = zh ?
            new String[]{"全部时间", "最近 24 小时", "最近 7 天", "最近 30 天"} :
            new String[]{"All time", "Last 24 hours", "Last 7 days", "Last 30 days"};
        new AlertDialog.Builder(this)
            .setTitle(tr("阅读时间", "Date range"))
            .setSingleChoiceItems(names, dateChoice, (dialog, which) -> {
                dateChoice = which;
                long now = System.currentTimeMillis();
                long day = 86400000L;
                minTime = which == 0 ? null :
                    now - (which == 1 ? day : which == 2 ? day * 7 : day * 30);
                dateFilter.setText(names[which]);
                dialog.dismiss();
                refresh();
            })
            .setNegativeButton(tr("取消", "Cancel"), null)
            .show();
    }

    private void chooseActionType() {
        String[] types = {null, "ORIGINAL", "REPLY", "QUOTE", "REPOST", "BOOKMARK"};
        String[] labels = zh ?
            new String[]{"全部类型", "原创", "回复", "引用", "转帖", "收藏"} :
            new String[]{"All types", "Original", "Reply", "Quote", "Repost", "Bookmark"};
        new AlertDialog.Builder(this)
            .setTitle(tr("内容类型", "Content type"))
            .setItems(labels, (dialog, selected) -> {
                actionType = types[selected];
                typeFilter.setText(labels[selected]);
                refresh();
            }).show();
    }

    private void chooseFolder() {
        if (account == null) return;
        final String owner = account;
        worker.execute(() -> {
            List<String> ids = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            ids.add(null);
            labels.add(tr("全部分类", "All folders"));
            try (Cursor cursor = archive.categories(owner)) {
                while (cursor.moveToNext()) {
                    ids.add(cursor.getString(0));
                    labels.add(cursor.getString(1));
                }
            }
            runOnUiThread(() -> {
                if (!owner.equals(account) || isFinishing()) return;
                new AlertDialog.Builder(this)
                    .setTitle(tr("筛选分类", "Filter folder"))
                    .setItems(labels.toArray(new String[0]), (dialog, choice) -> {
                        selectedFolder = ids.get(choice);
                        folderFilter.setText(labels.get(choice));
                        refresh();
                    }).show();
            });
        });
    }

    private void organizePost(String postId) {
        if (account == null) return;
        final String owner = account;
        worker.execute(() -> {
            List<String> ids = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            try (Cursor c = archive.categories(owner)) {
                while (c.moveToNext()) {
                    ids.add(c.getString(0));
                    labels.add(c.getString(1));
                }
            }
            runOnUiThread(() -> {
                if (!owner.equals(account) || isFinishing()) return;
                if (ids.isEmpty()) {
                    Toast.makeText(this,
                        tr("请先创建分类", "Create a folder first"),
                        Toast.LENGTH_SHORT).show();
                    return;
                }
                new AlertDialog.Builder(this)
                    .setTitle(tr("加入本地分类", "Add to local folder"))
                    .setItems(labels.toArray(new String[0]), (dialog, choice) -> {
                        worker.execute(() -> {
                            try {
                                archive.assignCategory(owner, postId, ids.get(choice));
                                runOnUiThread(() -> Toast.makeText(this,
                                    tr("已加入分类", "Added to folder"), Toast.LENGTH_SHORT).show());
                            } catch (RuntimeException ignored) {
                                runOnUiThread(() -> Toast.makeText(this,
                                    tr("添加分类失败", "Unable to assign folder"),
                                    Toast.LENGTH_SHORT).show());
                            }
                        });
                    }).show();
            });
        });
    }

    private void editFolder(Row row) {
        if (account == null) return;
        new AlertDialog.Builder(this)
            .setTitle(row.headline)
            .setItems(new String[]{
                tr("重命名", "Rename"), tr("删除分类", "Delete folder")
            }, (dialog, index) -> {
                if (index == 0) showFolderEditor(row.id, row.headline);
                else new AlertDialog.Builder(this)
                    .setMessage(tr("仅删除本地分类，不删除帖子。", 
                        "Only the local folder is deleted, not its posts."))
                    .setNegativeButton(tr("取消", "Cancel"), null)
                    .setPositiveButton(tr("删除", "Delete"), (d, which) -> {
                        String owner = account;
                        worker.execute(() -> {
                            archive.deleteCategory(owner, row.id);
                            runOnUiThread(() -> {
                                if (row.id.equals(selectedFolder)) selectedFolder = null;
                                refresh();
                            });
                        });
                    }).show();
            }).show();
    }

    private void showFolderEditor(String existingId, String initialName) {
        if (account == null) {
            Toast.makeText(this, tr("等待接入账号", "Account not bound"),
                Toast.LENGTH_SHORT).show();
            return;
        }
        EditText entry = new EditText(this);
        entry.setSingleLine(true);
        entry.setHint(tr("输入分类名称", "Folder name"));
        if (initialName != null) entry.setText(initialName);
        String owner = account;
        new AlertDialog.Builder(this)
            .setTitle(existingId == null ?
                tr("新建本地分类", "Create local folder") :
                tr("重命名分类", "Rename folder"))
            .setView(entry)
            .setNegativeButton(tr("取消", "Cancel"), null)
            .setPositiveButton(tr("保存", "Save"), (dialog, choice) -> {
                String name = entry.getText().toString().trim();
                if (name.isEmpty() || name.length() > 120) {
                    Toast.makeText(this,
                        tr("分类名不能为空，最多 120 字", "Folder name must be 1–120 chars"),
                        Toast.LENGTH_SHORT).show();
                    return;
                }
                String folderId = existingId == null ? UUID.randomUUID().toString() : existingId;
                worker.execute(() -> {
                    try {
                        archive.putCategory(owner, folderId, name);
                        runOnUiThread(this::refresh);
                    } catch (RuntimeException ignored) {
                        runOnUiThread(() -> Toast.makeText(this,
                            tr("保存失败", "Unable to save"), Toast.LENGTH_SHORT).show());
                    }
                });
            }).show();
    }

    private void confirmErase() {
        if (account == null) return;
        String owner = account;
        new AlertDialog.Builder(this)
            .setTitle(tr("清空本地归档？", "Erase local archive?"))
            .setMessage(tr("仅删除当前账号在本机的历史、分类和搜索索引，不影响 X 服务器数据。",
                "Deletes only this account's on-device Xs history, folders and indexes. X data is untouched."))
            .setNegativeButton(tr("取消", "Cancel"), null)
            .setPositiveButton(tr("清空", "Erase"), (d, n) -> worker.execute(() -> {
                try {
                    archive.clearAccount(owner);
                    runOnUiThread(() -> {
                        if (owner.equals(account)) {
                            selectedFolder = null;
                            refresh();
                        }
                    });
                } catch (RuntimeException ignored) {
                    runOnUiThread(() -> Toast.makeText(this,
                        tr("清空失败", "Unable to erase"), Toast.LENGTH_SHORT).show());
                }
            })).show();
    }

    private void openPost(String postId) {
        Uri uri = Uri.parse("https://x.com/i/web/status/" + postId);
        Intent local = new Intent(Intent.ACTION_VIEW, uri);
        local.setPackage(getPackageName());
        try {
            startActivity(local);
        } catch (ActivityNotFoundException notAvailable) {
            Toast.makeText(this, tr("当前客户端无法打开此帖子", 
                "This client cannot open the post"), Toast.LENGTH_SHORT).show();
        }
    }

    private String headline(String author, String body, String id) {
        String prefix = author == null || author.isEmpty() ? "" : "@" + author + "  ";
        String content = body == null || body.trim().isEmpty() ?
            tr("帖子 #", "Post #") + id : body.replace('\n', ' ').trim();
        String result = prefix + content;
        return result.length() > 110 ? result.substring(0, 110) + "…" : result;
    }

    private String date(long when) {
        return DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
            .format(new Date(when));
    }

    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setAllCaps(false);
        return b;
    }

    private TextView text(String value) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setPadding(0, px(3), 0, px(3));
        return tv;
    }

    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    private int px(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String tr(String chinese, String english) {
        return zh ? chinese : english;
    }

    @Override
    protected void onDestroy() {
        requested.incrementAndGet();
        worker.execute(() -> archive.close());
        worker.shutdown();
        super.onDestroy();
    }
}

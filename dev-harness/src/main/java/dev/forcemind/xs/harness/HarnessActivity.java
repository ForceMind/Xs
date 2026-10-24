package dev.forcemind.xs.harness;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import dev.forcemind.xs.android.XsArchiveDatabase;
import dev.forcemind.xs.android.XsCaptureBridge;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Offline, fake-data developer harness. NOT a Twitter/X client or patched APK.
 * No network access, authentication, or permission requirements.
 */
public final class HarnessActivity extends Activity {
    private static final String[] EXAMPLES = {
        "基于本地数据做快速搜索，而不是反复请求网络。",
        "Local-first means your personal archive remains searchable offline.",
        "Working with timelines: read time is not the original post time.",
        "产品设计笔记：分类、标签和可回溯的阅读路径。",
        "A separate visit record is created for each deliberate open.",
        "Test indexing: Kotlin, SQLite, Android, UI and source code.",
        "Build an independent extension, not a second social platform.",
        "阅读历史测试：按日期查找帖子和转帖。"
    };

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private String currentAccount = "test-account-a";
    private TextView status;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 42, 28, 24);
        setContentView(root);

        TextView title = new TextView(this);
        title.setText("Xs · Dev Harness");
        title.setTextSize(24);
        root.addView(title);

        TextView disclaimer = new TextView(this);
        disclaimer.setText("Developer test fixture only.\n" +
            "Synthetic posts, offline SQLite, NO X login or patching.\n" +
            "仅用于开发测试，非 X 增强安装包。");
        root.addView(disclaimer);

        status = new TextView(this);
        root.addView(status);

        Button switchAccount = new Button(this);
        switchAccount.setText("Switch fake account A / B · 模拟账号切换");
        root.addView(switchAccount);
        switchAccount.setOnClickListener(v -> {
            currentAccount = "test-account-a".equals(currentAccount) ?
                "test-account-b" : "test-account-a";
            XsCaptureBridge.bindAuthenticatedAccount(this, currentAccount);
            updateStatus();
        });

        Button seed = new Button(this);
        seed.setText("Seed offline example posts · 写入模拟数据");
        root.addView(seed);
        seed.setOnClickListener(v -> seedSampleData());

        Button simulateOpen = new Button(this);
        simulateOpen.setText("Simulate real post OPEN · 模拟主动打开");
        root.addView(simulateOpen);
        simulateOpen.setOnClickListener(v -> {
            if (!XsCaptureBridge.isRecordingEnabled()) {
                Toast.makeText(this, "Enable opened-post recording in Xs archive first",
                    Toast.LENGTH_LONG).show();
                return;
            }
            String id = Long.toString(2112345678901234999L);
            XsCaptureBridge.onPostOpened("https://x.com/example/status/" + id,
                "xs_fixture", "Bridge event: deliberate post open / 主动打开",
                System.currentTimeMillis() - 300000L, System.currentTimeMillis());
            Toast.makeText(this, "Enqueued one local open event", Toast.LENGTH_SHORT).show();
        });

        Button simulateFeed = new Button(this);
        simulateFeed.setText("Simulate confirmed FEED visibility · 模拟时间线浏览");
        root.addView(simulateFeed);
        simulateFeed.setOnClickListener(v -> {
            if (!XsCaptureBridge.isFeedRecordingEnabled()) {
                Toast.makeText(this, "Enable feed recording in Xs archive first",
                    Toast.LENGTH_LONG).show();
                return;
            }
            XsCaptureBridge.onFeedPostVisible(
                "https://x.com/example/status/2112345678901234998",
                "home_following", "xs_fixture",
                "Bridge event: visible feed post / 实际看到的信息流",
                System.currentTimeMillis() - 3600000L,
                true, 0.95, 1400L, System.currentTimeMillis());
            Toast.makeText(this, "Sent confirmed visible feed event",
                Toast.LENGTH_SHORT).show();
        });

        Button archive = new Button(this);
        archive.setText("Open native archive · 查看归档界面");
        root.addView(archive);
        archive.setOnClickListener(v -> XsCaptureBridge.openLocalArchive(this));

        TextView note = new TextView(this);
        note.setText("Enable each recording switch inside Archive, return here to " +
            "simulate events, then reopen Archive. These are synthetic bridge events. " +
            "Long press a post to organize it; search works offline.");
        root.addView(note, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        XsCaptureBridge.bindAuthenticatedAccount(this, currentAccount);
        updateStatus();
    }

    private void seedSampleData() {
        final String owner = currentAccount;
        final long now = System.currentTimeMillis();
        status.setText("Seeding offline sample data...");
        io.execute(() -> {
            try (XsArchiveDatabase db = new XsArchiveDatabase(getApplicationContext())) {
                db.putCategory(owner, "dev-reading", "Reading / 阅读");
                db.putCategory(owner, "dev-product", "Product Research / 产品研究");
                for (int i = 0; i < EXAMPLES.length; i++) {
                    String postId = Long.toString(2112345678901234500L + i);
                    String url = "https://x.com/example/status/" + postId;
                    long published = now - (i + 20L) * 86400000L;
                    long opened = now - i * 3600000L;
                    db.recordOpened(owner, url, "xs_fixture", EXAMPLES[i],
                        published, opened);
                    db.assignCategory(owner, postId,
                        i % 2 == 0 ? "dev-reading" : "dev-product");
                }
                // Feed-only records demonstrate that feed views must not
                // inflate deliberate post open counts.
                for (int i = 0; i < 4; i++) {
                    String id = Long.toString(2112345678901234600L + i);
                    db.recordFeedVisible(owner, "https://x.com/example/status/" + id,
                        i % 2 == 0 ? "home_for_you" : "home_following",
                        "xs_fixture", "信息流测试 / Feed visibility #" + i,
                        now - i * 86400000L, now - i * 1800000L);
                }
                String first = "https://x.com/example/status/2112345678901234500";
                db.recordOpened(owner, first, "xs_fixture", EXAMPLES[0],
                    now - 20L * 86400000L, now);
                db.recordPersonalAction(owner, first, "REPOST", "xs_fixture",
                    EXAMPLES[0], null, null, now);
                runOnUiThread(() -> {
                    updateStatus();
                    Toast.makeText(this, "Local synthetic posts seeded", Toast.LENGTH_SHORT).show();
                });
            } catch (RuntimeException failed) {
                runOnUiThread(() -> Toast.makeText(this,
                    "Sample data failed; inspect Android logs", Toast.LENGTH_LONG).show());
            }
        });
    }

    private void updateStatus() {
        status.setText("Active fixture account: " + currentAccount +
            "\nCapture by default: " +
            (XsCaptureBridge.isRecordingEnabled() ? "ON" : "OFF"));
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }
}

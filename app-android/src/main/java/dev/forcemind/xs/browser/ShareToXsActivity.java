package dev.forcemind.xs.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import dev.forcemind.xs.core.SharedPostParser;
import dev.forcemind.xs.android.XsArchiveDatabase;
import dev.forcemind.xs.android.XsLocalSpaces;
import dev.forcemind.xs.android.XsLibraryWrites;


/**
 * Native Android share target. Never opens WebView, cookies, or any URL itself.
 * Shares are saved only after explicit user confirmation to a local space.
 */
public final class ShareToXsActivity extends Activity {
    private String canonical;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(32, 48, 32, 24);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(this);
        label.setText(R.string.share_to_xs);
        label.setTextSize(20);
        box.addView(label, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(box);

        Intent intent = getIntent();
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction()) ||
            !"text/plain".equals(intent.getType())) {
            unsupported();
            return;
        }
        CharSequence shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        canonical = SharedPostParser.fromSharedText(shared == null ? null : shared.toString());
        if (canonical == null) { unsupported(); return; }

        new AlertDialog.Builder(this)
            .setTitle(R.string.share_confirm_title)
            .setMessage(getString(R.string.share_confirm_body, canonical))
            .setNegativeButton(R.string.cancel, (d, which) -> finish())
            .setOnCancelListener(d -> finish())
            .setPositiveButton(R.string.share_save, (d, which) -> checkLocalSpace())
            .show();
    }

    private void unsupported() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.share_to_xs)
            .setMessage(R.string.share_invalid)
            .setPositiveButton(android.R.string.ok, (d, which) -> finish())
            .setOnCancelListener(d -> finish()).show();
    }

    private void checkLocalSpace() {
        if (XsLocalSpaces.current(this) != null) { save(); return; }
        new AlertDialog.Builder(this)
            .setTitle(R.string.local_scope_title)
            .setMessage(R.string.local_scope_notice)
            .setNegativeButton(R.string.cancel, (d, which) -> finish())
            .setOnCancelListener(d -> finish())
            .setPositiveButton(R.string.create_local_scope, (d, which) -> {
                try {
                    XsLocalSpaces.createOnExplicitUserAction(this);
                    save();
                } catch (RuntimeException error) {
                    toast(R.string.local_scope_error);
                    finish();
                }
            }).show();
    }

    private void save() {
        String scope = XsLocalSpaces.current(this);
        if (scope == null || canonical == null) { toast(R.string.local_save_failed); finish(); return; }
        final String url = canonical;
        boolean queued=XsLibraryWrites.submit(scope,() -> {
            boolean success = false, fresh = false;
            try (XsArchiveDatabase db = new XsArchiveDatabase(getApplicationContext())) {
                fresh = db.saveManualLink(scope, url, System.currentTimeMillis());
                success = true;
            } catch (RuntimeException ignored) { }
            final boolean okay = success, newEntry = fresh;
            runOnUiThread(() -> {
                if (!isFinishing()) toast(!okay ? R.string.local_save_failed :
                    newEntry ? R.string.local_save_done : R.string.local_save_exists);
                finish();
            });
        });
        if (!queued) { toast(R.string.local_save_failed); finish(); }
    }

    private void toast(int id) {
        Toast.makeText(this, id, Toast.LENGTH_SHORT).show();
    }

}

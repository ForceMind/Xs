package dev.forcemind.xs.android;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Only parses the exported posts JS file, as text/JSON. Never executes scripts,
 * contacts X, or reads account tokens, messages or media.
 */
public final class XsArchiveImporter {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int MAX_POSTS = 50_000;
    private static final Pattern ID = Pattern.compile("[0-9]{1,20}");
    private static final Pattern PREFIX = Pattern.compile(
        "^\\s*(?:\\uFEFF)?window\\.YTD\\.(?:tweet|tweets)\\.part[0-9]+\\s*=\\s*\\[",
        Pattern.DOTALL);

    public static final class Record {
        public final String postId;
        public final String body;
        public final String actionType;
        public final String relatedPostId;
        public final Long publishedAtMs;
        public final Long actionAtMs;

        Record(String id, String body, String type, String relatedPostId, Long published, Long action) {
            postId = id;
            this.body = body;
            actionType = type;
            this.relatedPostId = relatedPostId;
            publishedAtMs = published;
            actionAtMs = action;
        }
    }

    public static final class Result {
        public final int parsed;
        public final int inserted;
        public final int skipped;

        Result(int parsed, int inserted, int skipped) {
            this.parsed = parsed;
            this.inserted = inserted;
            this.skipped = skipped;
        }
    }

    private XsArchiveImporter() {}

    /**
     * Call on a worker thread after the user explicitly confirms the exported
     * posts file is their own archive. The local library is not an X actor ID;
     * actor identity remains unknown without separately verified archive metadata.
     */
    public static Result importJs(InputStream file, String accountId,
                                  XsArchiveDatabase db, long importedAtMs)
                                  throws IOException {
        if (file == null || db == null || accountId == null ||
            accountId.trim().isEmpty() || importedAtMs <= 0) {
            throw new IllegalArgumentException("Import input, local library and time required");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = file.read(chunk)) != -1) {
            if (bytes.size() + n > MAX_BYTES) {
                throw new IOException("Posts file exceeds 16 MiB; import smaller parts");
            }
            bytes.write(chunk, 0, n);
        }
        String raw = new String(bytes.toByteArray(), StandardCharsets.UTF_8).trim();
        if (!raw.startsWith("[") && !PREFIX.matcher(raw).find()) {
            throw new IOException("Not a tweets.js/tweet.js export");
        }
        int left = raw.indexOf('[');
        int right = raw.lastIndexOf(']');
        if (left < 0 || right <= left) throw new IOException("Missing posts array");
        JSONArray items;
        try {
            items = new JSONArray(raw.substring(left, right + 1));
        } catch (JSONException invalid) {
            throw new IOException("Malformed posts JSON", invalid);
        }
        if (items.length() > MAX_POSTS) {
            throw new IOException("More than 50,000 posts; split the exported file");
        }
        int skipped = 0;
        List<Record> rows = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject root = items.optJSONObject(i);
            if (root == null) { skipped++; continue; }
            JSONObject item = root.optJSONObject("tweet");
            if (item == null) item = root;
            String id = item.optString("id_str", "");
            if (!ID.matcher(id).matches()) id = item.optString("id", "");
            if (!ID.matcher(id).matches()) { skipped++; continue; }
            String body = item.optString("full_text", item.optString("text", ""));
            if (body.length() > 16000) body = body.substring(0, 16000);
            Long createdAt = parseDate(item.optString("created_at", ""));

            JSONObject reposted=item.optJSONObject("retweeted_status");
            String repostTarget=reposted == null ? null : numericIdentity(reposted,"id");
            if (repostTarget == null) repostTarget=numericIdentity(item,"retweeted_status_id");
            boolean repost=repostTarget != null;
            boolean reply = numericIdentity(item,"in_reply_to_status_id") != null ||
                            numericIdentity(item,"in_reply_to_user_id") != null;
            boolean quote = item.optBoolean("is_quote_status", false) ||
                            numericIdentity(item,"quoted_status_id") != null;
            // A hand-written "RT @" is not evidence of a platform repost. Keep
            // ambiguous export text searchable without inventing its action type.
            boolean ambiguous=body.startsWith("RT @") && !repost && !reply && !quote;
            String type = repost ? "REPOST" : reply ? "REPLY" : quote ? "QUOTE" : ambiguous ? null : "ORIGINAL";
            String related="REPLY".equals(type) ? numericIdentity(item,"in_reply_to_status_id") :
                "QUOTE".equals(type) ? numericIdentity(item,"quoted_status_id") :
                "REPOST".equals(type) ? repostTarget : null;

            // Retweet metadata cannot safely establish original publication
            // time or exact repost action time: both remain unknown.
            rows.add(new Record(id, body, type,related,
                repost || ambiguous ? null : createdAt, repost || ambiguous ? null : createdAt));
        }
        int stored = db.importArchivedOwnPosts(accountId, rows, importedAtMs);
        return new Result(rows.size(), stored, skipped);
    }

    private static String numericIdentity(JSONObject item,String key) {
        String value=item.optString(key + "_str",item.optString(key,""));
        return ID.matcher(value).matches() && !"0".equals(value) ? value : null;
    }

    private static Long parseDate(String value) {
        if (value == null || value.isEmpty()) return null;
        SimpleDateFormat parser =
            new SimpleDateFormat("EEE MMM dd HH:mm:ss Z yyyy", Locale.US);
        parser.setLenient(false);
        ParsePosition position=new ParsePosition(0);
        Date date=parser.parse(value,position);
        return date != null && date.getTime() > 0 && position.getIndex() == value.length() ? date.getTime() : null;
    }
}

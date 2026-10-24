package dev.forcemind.xs.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Builds parameterized SQLite search statements. Caller owns the returned Cursor. */
public final class ArchiveSearch {
    private static final Pattern LATIN_WORDS =
        Pattern.compile("[A-Za-z0-9]+(?:[ \\t]+[A-Za-z0-9]+)*");
    private static final int MAX_QUERY_CHARS = 256;

    public enum DateField { LAST_SEEN, LAST_OPENED, FEED_SEEN, PUBLISHED, ACTION }

    public static final class Filter {
        public String keyword;
        public String categoryId;
        public String actionType;
        public Long fromMs;
        public Long toMs;
        public DateField dateField = DateField.LAST_SEEN;
        public boolean openedOnly = false;
        public int limit = 50;
        public int offset = 0;
    }

    public static final class Query {
        public final String sql;
        public final String[] args;

        private Query(String sql, List<String> args) {
            this.sql = sql;
            this.args = args.toArray(new String[0]);
        }
    }

    private ArchiveSearch() {}

    private static boolean validActionType(String type) {
        return "ORIGINAL".equals(type) || "REPLY".equals(type) ||
               "QUOTE".equals(type) || "REPOST".equals(type) ||
               "BOOKMARK".equals(type);
    }

    public static Query build(String accountId, Filter filter) {
        if (accountId == null || accountId.trim().isEmpty()) {
            throw new IllegalArgumentException("account ID required");
        }
        if (filter == null) filter = new Filter();
        if (filter.fromMs != null && filter.toMs != null &&
                filter.fromMs > filter.toMs) {
            throw new IllegalArgumentException("Invalid date range");
        }
        if (filter.offset < 0) throw new IllegalArgumentException("Negative offset");
        if (filter.dateField == null) throw new IllegalArgumentException("Date basis required");
        if (filter.actionType != null && !filter.actionType.isEmpty() &&
            !validActionType(filter.actionType)) {
            throw new IllegalArgumentException("Unsupported personal action type");
        }

        String dateColumn;
        switch (filter.dateField) {
            case LAST_OPENED: dateColumn = "p.last_opened_at_ms"; break;
            case FEED_SEEN: dateColumn = "p.last_feed_seen_at_ms"; break;
            case PUBLISHED: dateColumn = "p.published_at_ms"; break;
            case ACTION:
                // A strict allowlist ensures an action type cannot inject SQL.
                String onlyAction = filter.actionType == null ||
                    filter.actionType.isEmpty() ? "" :
                    " AND a.action_type='" + filter.actionType + "'";
                dateColumn = "(SELECT MAX(a.action_at_ms) FROM user_actions a " +
                    "WHERE a.account_id=p.account_id AND a.post_id=p.post_id" +
                    onlyAction + ")";
                break;
            default: dateColumn = "p.last_seen_at_ms"; break;
        }
        StringBuilder sql = new StringBuilder(
            "SELECT p.account_id, p.post_id, p.author_handle, p.body, " +
            "p.published_at_ms, p.first_seen_at_ms, p.last_seen_at_ms, " +
            "p.opened_count, " + dateColumn + " AS date_sort_ms " +
            "FROM posts p WHERE p.account_id = ?");
        List<String> args = new ArrayList<>();
        args.add(accountId);

        if (filter.openedOnly) sql.append(" AND p.opened_count > 0");
        if (filter.fromMs != null) {
            sql.append(" AND ").append(dateColumn).append(" >= ?");
            args.add(Long.toString(filter.fromMs));
        }
        if (filter.toMs != null) {
            sql.append(" AND ").append(dateColumn).append(" <= ?");
            args.add(Long.toString(filter.toMs));
        }
        if (filter.categoryId != null && !filter.categoryId.isEmpty()) {
            sql.append(" AND EXISTS (SELECT 1 FROM post_categories c WHERE " +
                       "c.account_id=p.account_id AND c.post_id=p.post_id AND c.category_id=?)");
            args.add(filter.categoryId);
        }
        if (filter.actionType != null && !filter.actionType.isEmpty()) {
            sql.append(" AND EXISTS (SELECT 1 FROM user_actions a WHERE " +
                       "a.account_id=p.account_id AND a.post_id=p.post_id AND a.action_type=?)");
            args.add(filter.actionType);
        }
        String keyword = filter.keyword == null ? "" : filter.keyword.trim();
        if (keyword.length() > MAX_QUERY_CHARS) {
            throw new IllegalArgumentException("Query too long");
        }
        if (!keyword.isEmpty()) {
            if (LATIN_WORDS.matcher(keyword).matches()) {
                // Whitelist tokens: never pass user-written operators to MATCH.
                String[] tokens = keyword.toLowerCase(Locale.ROOT).split("[ \\t]+");
                List<String> safe = new ArrayList<>();
                for (String token : tokens) safe.add(token + "*");
                sql.append(" AND EXISTS (SELECT 1 FROM posts_fts WHERE " +
                           "posts_fts.account_id=p.account_id AND " +
                           "posts_fts.post_id=p.post_id AND posts_fts MATCH ?)");
                args.add(String.join(" AND ", safe));
            } else {
                String needle = "%" + keyword.replace("!", "!!")
                    .replace("%", "!%").replace("_", "!_") + "%";
                sql.append(" AND (p.body LIKE ? ESCAPE '!' OR " +
                           "p.author_handle LIKE ? ESCAPE '!')");
                args.add(needle);
                args.add(needle);
            }
        }
        int limit = Math.max(1, Math.min(200, filter.limit));
        sql.append(" ORDER BY ").append(dateColumn)
           .append(" DESC, p.post_id DESC LIMIT ? OFFSET ?");
        args.add(Integer.toString(limit));
        args.add(Integer.toString(filter.offset));
        return new Query(sql.toString(), args);
    }
}

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

    public enum DateField { LAST_SEEN, LAST_OPENED, FEED_SEEN, PUBLISHED, ACTION, FAVORITED }

    public static final class Filter {
        public String keyword;
        public String categoryId;
        public String actionType;
        public boolean includeSnapshotVersions = false;
        public boolean ownOnly = false;
        public String actionState;
        public String actorId;
        public String actionSource;
        public Long fromMs;
        public Long toMs;
        public DateField dateField = DateField.LAST_SEEN;
        public boolean openedOnly = false;
        public boolean neverOpened = false;
        public boolean favoriteOnly = false;
        public boolean sortByDwell = false;
        public Long minDwellMs;
        public String source;
        public String author;
        public String tag;
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
               "BOOKMARK".equals(type) || "LOCAL_SAVE".equals(type);
    }

    private static boolean has(String value) { return value != null && !value.isEmpty(); }

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

        if (filter.actionState != null && !filter.actionState.isEmpty() &&
                !"ACTIVE".equals(filter.actionState) && !"REVERSED".equals(filter.actionState) &&
                !"UNKNOWN".equals(filter.actionState)) throw new IllegalArgumentException("Unsupported action state");
        if ((filter.actorId != null && filter.actorId.length() > 128) ||
                (filter.actionSource != null && filter.actionSource.length() > 256)) {
            throw new IllegalArgumentException("Action filter too long");
        }
        if (filter.minDwellMs != null && filter.minDwellMs < 0) {
            throw new IllegalArgumentException("Negative dwell duration");
        }
        if (filter.openedOnly && filter.neverOpened) {
            throw new IllegalArgumentException("Conflicting open filters");
        }
        if (filter.source != null && !filter.source.isEmpty() &&
                !FeedCapturePolicy.supportedSource(filter.source) &&
                !"post_detail".equals(filter.source)) {
            throw new IllegalArgumentException("Unsupported observation source");
        }
        if ((filter.author != null && filter.author.length() > 128) ||
                (filter.tag != null && filter.tag.length() > 64)) {
            throw new IllegalArgumentException("Filter too long");
        }
        // Duration, source and exposure-date filters must be satisfied by one event.
        // Aggregate only matching exposures, so sorting cannot borrow an older/other-feed duration.
        boolean hasSource = filter.source != null && !filter.source.isEmpty();
        boolean feedDate = filter.dateField == DateField.FEED_SEEN;
        boolean feedSource = hasSource && (FeedCapturePolicy.supportedSource(filter.source) ||
            filter.minDwellMs != null || feedDate);
        List<String> args = new ArrayList<>();
        StringBuilder matchedFeed = new StringBuilder(
            "SELECT e.account_id,e.post_id,MAX(e.duration_ms) AS max_dwell_ms, " +
            "MAX(e.event_at_ms) AS feed_time, CASE WHEN MAX(e.duration_ms)>=600000 " +
            "THEN 'CAPPED_LOWER_BOUND' WHEN MAX(e.duration_ms) IS NOT NULL " +
            "THEN 'SAMPLED_LOWER_BOUND' END AS dwell_quality FROM history_events e " +
            "WHERE e.account_id=? AND e.event_type='FEED_VISIBLE'");
        args.add(accountId);
        if (filter.minDwellMs != null) {
            matchedFeed.append(" AND e.duration_ms>=CAST(? AS INTEGER)");
            args.add(Long.toString(filter.minDwellMs));
        }
        if (feedSource) {
            matchedFeed.append(" AND e.source=?");
            args.add(filter.source);
        }
        if (feedDate && filter.fromMs != null) {
            matchedFeed.append(" AND e.event_at_ms>=CAST(? AS INTEGER)");
            args.add(Long.toString(filter.fromMs));
        }
        if (feedDate && filter.toMs != null) {
            matchedFeed.append(" AND e.event_at_ms<=CAST(? AS INTEGER)");
            args.add(Long.toString(filter.toMs));
        }
        matchedFeed.append(" GROUP BY e.account_id,e.post_id");
        boolean ownType="ORIGINAL".equals(filter.actionType) || "REPLY".equals(filter.actionType) ||
            "QUOTE".equals(filter.actionType) || "REPOST".equals(filter.actionType);
        boolean legacyType="BOOKMARK".equals(filter.actionType) || "LOCAL_SAVE".equals(filter.actionType);
        boolean ownRequired=filter.ownOnly || ownType || has(filter.actionState) || has(filter.actorId) || has(filter.actionSource);
        StringBuilder matchedAction=new StringBuilder("SELECT a.account_id,a.post_id,MAX(a.action_at_ms) AS action_time," +
            "GROUP_CONCAT(a.action_type||' / '||a.state||' / '||COALESCE(a.actor_id,'?')||' / '||a.actor_provenance, '; ') " +
            "AS action_summary FROM own_actions a WHERE a.account_id=?");
        args.add(accountId);
        if (ownType) { matchedAction.append(" AND a.action_type=?"); args.add(filter.actionType); }
        if (has(filter.actionState)) { matchedAction.append(" AND a.state=?"); args.add(filter.actionState); }
        if (has(filter.actorId)) { matchedAction.append(" AND a.actor_id=?"); args.add(filter.actorId); }
        if (has(filter.actionSource)) { matchedAction.append(" AND a.source=?"); args.add(filter.actionSource); }
        if (filter.dateField == DateField.ACTION && !legacyType) {
            if (filter.fromMs != null) { matchedAction.append(" AND a.action_at_ms>=CAST(? AS INTEGER)"); args.add(Long.toString(filter.fromMs)); }
            if (filter.toMs != null) { matchedAction.append(" AND a.action_at_ms<=CAST(? AS INTEGER)"); args.add(Long.toString(filter.toMs)); }
        }
        matchedAction.append(" GROUP BY a.account_id,a.post_id");
        String dwellColumn = "matched_feed.max_dwell_ms";
        String favoriteTime = "(SELECT f.favorited_at_ms FROM favorites f WHERE " +
            "f.account_id=p.account_id AND f.post_id=p.post_id)";
        String dateColumn;
        switch (filter.dateField) {
            case LAST_OPENED: dateColumn = "p.last_opened_at_ms"; break;
            case FEED_SEEN: dateColumn = "matched_feed.feed_time"; break;
            case PUBLISHED: dateColumn = "p.published_at_ms"; break;
            case FAVORITED: dateColumn = favoriteTime; break;
            case ACTION:
                if (legacyType) {
                    dateColumn = "(SELECT MAX(a.action_at_ms) FROM user_actions a " +
                        "WHERE a.account_id=p.account_id AND a.post_id=p.post_id AND a.action_type='" +
                        filter.actionType + "')";
                } else if (ownRequired) dateColumn="matched_action.action_time";
                else {
                    // Preserve manual-link/bookmark date searches alongside the own-action index.
                    String localTime="(SELECT MAX(l.action_at_ms) FROM user_actions l WHERE " +
                        "l.account_id=p.account_id AND l.post_id=p.post_id AND l.action_type IN ('LOCAL_SAVE','BOOKMARK'))";
                    dateColumn="CASE WHEN matched_action.action_time IS NULL THEN " + localTime +
                        " WHEN " + localTime + " IS NULL THEN matched_action.action_time ELSE MAX(" +
                        localTime + ",matched_action.action_time) END";
                }
                break;
            default: dateColumn = "p.last_seen_at_ms"; break;
        }
        StringBuilder sql = new StringBuilder(
            "SELECT p.account_id, p.post_id, p.author_handle, p.body, " +
            "p.published_at_ms, p.first_seen_at_ms, p.last_seen_at_ms, " +
            "p.opened_count, " + dateColumn + " AS date_sort_ms, " +
            dwellColumn + " AS max_dwell_ms, " + favoriteTime + " AS favorited_at_ms, " +
            "(SELECT f.note FROM favorites f WHERE f.account_id=p.account_id AND " +
            "f.post_id=p.post_id) AS note, " +
            "(SELECT GROUP_CONCAT(t.tag, ', ') FROM post_tags t WHERE t.account_id=p.account_id " +
            "AND t.post_id=p.post_id) AS tags, " +
            "matched_feed.dwell_quality AS dwell_quality, matched_action.action_summary AS own_action_summary, " +
            "p.current_snapshot_key AS current_snapshot_key, " +
            "(SELECT v.quality FROM snapshot_versions v WHERE v.account_id=p.account_id AND v.post_id=p.post_id AND v.snapshot_key=p.current_snapshot_key) AS snapshot_quality, " +
            "(SELECT v.captured_at_ms FROM snapshot_versions v WHERE v.account_id=p.account_id AND v.post_id=p.post_id AND v.snapshot_key=p.current_snapshot_key) AS snapshot_captured_at_ms, " +
            "(SELECT COUNT(*) FROM snapshot_versions v WHERE v.account_id=p.account_id AND v.post_id=p.post_id) AS snapshot_count " +
            "FROM posts p LEFT JOIN (" + matchedFeed + ") matched_feed ON " +
            "matched_feed.account_id=p.account_id AND matched_feed.post_id=p.post_id " +
            "LEFT JOIN (" + matchedAction + ") matched_action ON matched_action.account_id=p.account_id " +
            "AND matched_action.post_id=p.post_id " +
            "WHERE p.account_id = ?");
        args.add(accountId);

        if (ownRequired) sql.append(" AND matched_action.post_id IS NOT NULL");
        if (filter.openedOnly) sql.append(" AND p.opened_count > 0");
        if (filter.neverOpened) sql.append(" AND p.opened_count = 0 AND NOT EXISTS " +
            "(SELECT 1 FROM history_events o WHERE o.account_id=p.account_id " +
            "AND o.post_id=p.post_id AND o.event_type='OPEN_POST')");
        if (filter.favoriteOnly) sql.append(" AND EXISTS (SELECT 1 FROM favorites f WHERE " +
            "f.account_id=p.account_id AND f.post_id=p.post_id)");
        if (filter.minDwellMs != null || feedSource || feedDate) {
            sql.append(" AND matched_feed.post_id IS NOT NULL");
        }
        if (hasSource && !feedSource) {
            sql.append(" AND EXISTS (SELECT 1 FROM history_events e WHERE " +
                "e.account_id=p.account_id AND e.post_id=p.post_id AND e.source=?)");
            args.add(filter.source);
        }
        if (filter.author != null && !filter.author.trim().isEmpty()) {
            sql.append(" AND p.author_handle=? COLLATE NOCASE");
            args.add(filter.author.trim().replaceFirst("^@", ""));
        }
        if (filter.tag != null && !filter.tag.trim().isEmpty()) {
            sql.append(" AND EXISTS (SELECT 1 FROM post_tags t WHERE " +
                "t.account_id=p.account_id AND t.post_id=p.post_id AND t.tag=?)");
            args.add(filter.tag.trim());
        }
        if (!feedDate && filter.fromMs != null) {
            sql.append(" AND ").append(dateColumn).append(" >= CAST(? AS INTEGER)");
            args.add(Long.toString(filter.fromMs));
        }
        if (!feedDate && filter.toMs != null) {
            sql.append(" AND ").append(dateColumn).append(" <= CAST(? AS INTEGER)");
            args.add(Long.toString(filter.toMs));
        }
        if (filter.categoryId != null && !filter.categoryId.isEmpty()) {
            sql.append(" AND EXISTS (SELECT 1 FROM post_categories c WHERE " +
                       "c.account_id=p.account_id AND c.post_id=p.post_id AND c.category_id=?)");
            args.add(filter.categoryId);
        }
        if (legacyType) {
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
                // Whitelist tokens: never pass user-written operators to MATCH. Adjacent
                // terms are implicit AND in both standard and enhanced FTS3/FTS4 syntax;
                // explicit AND is a literal token on Android builds without enhanced syntax.
                String[] tokens = keyword.toLowerCase(Locale.ROOT).split("[ \\t]+");
                List<String> safe = new ArrayList<>();
                for (String token : tokens) safe.add(token + "*");
                // FTS rowids are bound to posts by our insert/update/delete triggers.
                // Materialize MATCH once; correlated MATCH reran the full hit list for every post.
                // The outer p.account_id predicate still owns all library isolation.
                sql.append(" AND (p.rowid IN (SELECT rowid FROM posts_fts WHERE posts_fts MATCH ?)");
                args.add(String.join(" ", safe));
            } else {
                String needle = "%" + keyword.replace("!", "!!")
                    .replace("%", "!%").replace("_", "!_") + "%";
                sql.append(" AND ((p.body LIKE ? ESCAPE '!' OR " +
                           "p.author_handle LIKE ? ESCAPE '!')");
                args.add(needle);
                args.add(needle);
            }
            if(filter.includeSnapshotVersions) {
                if(LATIN_WORDS.matcher(keyword).matches()) {
                    sql.append(" OR p.post_id IN (SELECT post_id FROM snapshot_versions_fts WHERE account_id=? " +
                        "AND snapshot_versions_fts MATCH ?)");
                    args.add(accountId);args.add(ftsTerm(keyword));
                } else {
                    sql.append(" OR EXISTS (SELECT 1 FROM snapshot_versions v WHERE v.account_id=p.account_id " +
                        "AND v.post_id=p.post_id AND (v.body LIKE ? ESCAPE '!' OR v.author_handle LIKE ? ESCAPE '!'))");
                    args.add(likeTerm(keyword));args.add(likeTerm(keyword));
                }
            }
            String extraNeedle = "%" + keyword.replace("!", "!!")
                .replace("%", "!%").replace("_", "!_") + "%";
            sql.append(" OR EXISTS (SELECT 1 FROM favorites f WHERE f.account_id=p.account_id " +
                "AND f.post_id=p.post_id AND f.note LIKE ? ESCAPE '!') " +
                "OR EXISTS (SELECT 1 FROM post_tags t WHERE t.account_id=p.account_id " +
                "AND t.post_id=p.post_id AND t.tag LIKE ? ESCAPE '!'))");
            args.add(extraNeedle);
            args.add(extraNeedle);
        }
        int limit = Math.max(1, Math.min(200, filter.limit));
        sql.append(" ORDER BY ").append(filter.sortByDwell ? dwellColumn : dateColumn)
           .append(" DESC, p.post_id DESC LIMIT ? OFFSET ?");
        args.add(Integer.toString(limit));
        args.add(Integer.toString(filter.offset));
        return new Query(sql.toString(), args);
    }
    /** The same nine columns power version lists, individual details and version-text search. */
    public static final String SNAPSHOT_COLUMNS="SELECT v.snapshot_key,v.author_handle,v.body,v.published_at_ms," +
        "v.captured_at_ms,v.source,v.quality,v.content_hash,CASE WHEN p.current_snapshot_key=v.snapshot_key THEN 1 ELSE 0 END AS is_current " +
        "FROM snapshot_versions v JOIN posts p ON p.account_id=v.account_id AND p.post_id=v.post_id";
    public static Query buildSnapshots(String libraryId,String postId,String keyword,int limit,int offset) {
        if(libraryId==null||libraryId.trim().isEmpty()||postId==null||!postId.matches("[0-9]{1,20}")||offset<0)throw new IllegalArgumentException("Snapshot context required");
        String value=keyword==null?"":keyword.trim();if(value.length()>MAX_QUERY_CHARS)throw new IllegalArgumentException("Query too long");
        List<String> args=new ArrayList<>();args.add(libraryId);args.add(postId);
        StringBuilder sql=new StringBuilder(SNAPSHOT_COLUMNS+" WHERE v.account_id=? AND v.post_id=?");
        if(!value.isEmpty()) {
            if(LATIN_WORDS.matcher(value).matches()) {
                sql.append(" AND v.rowid IN (SELECT rowid FROM snapshot_versions_fts WHERE snapshot_versions_fts MATCH ?)");
                args.add(ftsTerm(value));
            } else {
                sql.append(" AND (v.body LIKE ? ESCAPE '!' OR v.author_handle LIKE ? ESCAPE '!')");
                args.add(likeTerm(value));args.add(likeTerm(value));
            }
        }
        sql.append(" ORDER BY v.captured_at_ms DESC,v.rowid DESC LIMIT ? OFFSET ?");
        args.add(Integer.toString(Math.max(1,Math.min(200,limit))));args.add(Integer.toString(offset));return new Query(sql.toString(),args);
    }
    private static String ftsTerm(String value) {
        List<String> words=new ArrayList<>();for(String word:value.toLowerCase(Locale.ROOT).split("[ \\t]+"))words.add(word+"*");return String.join(" ",words);
    }
    private static String likeTerm(String value){return "%"+value.replace("!","!!").replace("%","!%").replace("_","!_")+"%";}

}

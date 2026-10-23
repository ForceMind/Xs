package dev.forcemind.xs.tests;

import dev.forcemind.xs.core.ArchiveSchema;
import dev.forcemind.xs.core.ArchiveSearch;
import dev.forcemind.xs.core.PostUrls;

/** Dependency-free JVM checks; Android SQLite integration needs device testing. */
public final class CoreSmokeTest {
    private static int checks;

    public static void main(String[] args) {
        eq("123456789", PostUrls.extractPostId("https://x.com/name/status/123456789?s=20"));
        eq("42", PostUrls.extractPostId("twitter.com/name/status/42"));
        eq("42", PostUrls.extractPostId("https://mobile.twitter.com/name/status/42/photo/1"));
        eq("42", PostUrls.extractPostId("https://x.com/i/web/status/42"));
        eq("https://x.com/i/web/status/42", PostUrls.canonicalLink("https://x.com/i/status/42"));
        eq(null, PostUrls.extractPostId("https://x.com.evil.example/u/status/42"));
        eq(null, PostUrls.extractPostId("https://x.com@evil.example/u/status/42"));
        eq(null, PostUrls.extractPostId("https://evil.example/u/status/42"));
        eq(null, PostUrls.extractPostId("javascript:alert(1)"));
        eq(null, PostUrls.extractPostId("https://x.com/u/status/not-a-number"));
        eq(null, PostUrls.extractPostId("https://x.com/u/status/42/other/1"));
        eq(null, PostUrls.extractPostId("https://x.com/u/status/42%2Fanything"));
        eq(null, PostUrls.extractPostId("https://x.com/u/status/42?x=1\nbad"));

        ArchiveSearch.Filter f = new ArchiveSearch.Filter();
        f.keyword = "AI agent";
        f.fromMs = 1000L;
        f.toMs = 9000L;
        f.openedOnly = true;
        f.categoryId = "research";
        f.limit = 999;
        ArchiveSearch.Query q = ArchiveSearch.build("user-1", f);
        yes(q.sql.contains("p.account_id = ?"));
        yes(q.sql.contains("posts_fts MATCH ?"));
        yes(q.sql.contains("p.opened_count > 0"));
        eq("user-1", q.args[0]);
        eq("ai* AND agent*", q.args[q.args.length - 3]);
        eq("200", q.args[q.args.length - 2]);

        f.keyword = "人工智能";
        q = ArchiveSearch.build("user-1", f);
        yes(q.sql.contains("LIKE ? ESCAPE '!'"));
        yes(!q.sql.contains("posts_fts MATCH ?"));
        yes(contains(q.args, "%人工智能%"));

        f.keyword = "' OR 1=1 -- %_!";
        q = ArchiveSearch.build("user-1", f);
        yes(!q.sql.contains("1=1"));
        yes(contains(q.args, "%' OR 1=1 -- !%!_!!%"));

        f.keyword = null;
        f.dateField = ArchiveSearch.DateField.PUBLISHED;
        f.actionType = "REPOST";
        q = ArchiveSearch.build("user-2", f);
        yes(q.sql.contains("p.published_at_ms DESC"));
        yes(q.sql.contains("user_actions"));
        eq("user-2", q.args[0]);

        f.fromMs = 5L;
        f.toMs = 1L;
        expectInvalid(() -> ArchiveSearch.build("user-2", f));
        f.fromMs = null;
        f.toMs = null;
        f.offset = -1;
        expectInvalid(() -> ArchiveSearch.build("user-2", f));
        expectInvalid(() -> ArchiveSearch.build("", new ArchiveSearch.Filter()));
        yes(ArchiveSchema.CREATE.length >= 10);
        boolean fts = false, cascade = false;
        for (String ddl : ArchiveSchema.CREATE) {
            if (ddl.contains("VIRTUAL TABLE") && ddl.contains("fts4")) fts = true;
            if (ddl.contains("ON DELETE CASCADE")) cascade = true;
        }
        yes(fts && cascade);
        System.out.println("PASS " + checks + " core assertions");
    }

    private static boolean contains(String[] values, String expected) {
        for (String value : values) if (value.equals(expected)) return true;
        return false;
    }

    private static void expectInvalid(Runnable runnable) {
        try {
            runnable.run();
            throw new AssertionError("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            checks++;
        }
    }

    private static void yes(boolean truth) {
        if (!truth) throw new AssertionError("Assertion failed");
        checks++;
    }

    private static void eq(Object expected, Object actual) {
        if (!(expected == null ? actual == null : expected.equals(actual))) {
            throw new AssertionError("Expected [" + expected + "] got [" + actual + "]");
        }
        checks++;
    }
}

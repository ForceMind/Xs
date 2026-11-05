package dev.forcemind.xs.tests;

import dev.forcemind.xs.core.SharedPostParser;

public final class SharedPostParserTest {
    private static int assertions;
    private static void eq(String expected,String actual) {
        if (!(expected==null ? actual==null : expected.equals(actual))) {
            throw new AssertionError(expected+" != "+actual);
        }
        assertions++;
    }
    public static void main(String[] args) {
        eq("https://x.com/i/web/status/12345",
           SharedPostParser.fromSharedText("Here's the post: https://x.com/user/status/12345?s=20"));
        eq("https://x.com/i/web/status/56789",
           SharedPostParser.fromSharedText("转发地址（https://twitter.com/user/status/56789）。"));
        eq("https://x.com/i/web/status/42",
           SharedPostParser.fromSharedText("https://mobile.twitter.com/i/status/42"));
        eq("https://x.com/i/web/status/43",
           SharedPostParser.fromSharedText("https://www.X.COM/User/status/43"));
        eq(null, SharedPostParser.fromSharedText("https://x.com.evil.test/user/status/42"));
        eq(null, SharedPostParser.fromSharedText("https://evil.test/?q=https://x.com/user/status/42"));
        eq(null, SharedPostParser.fromSharedText("ftp://x.com/user/status/42"));
        eq(null, SharedPostParser.fromSharedText("http://x.com/user/status/42"));
        eq(null, SharedPostParser.fromSharedText("https://x.com/home"));
        eq(null, SharedPostParser.fromSharedText("https://x.com/messages"));
        eq(null, SharedPostParser.fromSharedText(null));
        eq(null, SharedPostParser.fromSharedText(new String(new char[9000])));
        eq(null, SharedPostParser.fromSharedText("https://x.com/user/status/notanumber"));
        eq(null, SharedPostParser.fromSharedText("https://x.com@evil.test/user/status/42"));
        System.out.println("PASS "+assertions+" shared-post parsing assertions");
    }
}

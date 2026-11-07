package dev.forcemind.xs.tests;

import dev.forcemind.xs.core.BrowserUploadPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Request constraints must survive both system-picker filtering and untrusted results. */
public final class BrowserUploadPolicyTest {
    private static int assertions;
    private static void check(boolean result) {
        if (!result) throw new AssertionError("Browser upload assertion " + (assertions + 1));
        assertions++;
    }
    private static BrowserUploadPolicy single(String... types) { return BrowserUploadPolicy.create(0, types); }
    private static void rejects(int mode, String... types) {
        try { BrowserUploadPolicy.create(mode, types); }
        catch (IllegalArgumentException expected) { assertions++; return; }
        throw new AssertionError("Unsupported chooser request accepted");
    }
    private static String repeat(char value, int count) {
        char[] chars = new char[count]; Arrays.fill(chars, value); return new String(chars);
    }
    private static boolean result(BrowserUploadPolicy policy, String[] uris, String... types) {
        return policy.acceptsResult(uris == null ? null : Arrays.asList(uris), Arrays.asList(types));
    }
    public static void main(String[] args) {
        for (String[] blank : new String[][]{null, {}, {null}, {""}, {" ", "", null}, {",,"}}) {
            BrowserUploadPolicy policy = BrowserUploadPolicy.create(0, blank);
            check(!policy.allowsMultiple());
            check(policy.mimeTypes().equals(Arrays.asList("image/*", "video/*")));
            check(policy.acceptsMime("image/png"));
            check(policy.acceptsMime("VIDEO/MP4"));
            check(!policy.acceptsMime("text/html"));
        }
        for (int mode : new int[]{-1, 2, 3, 4, Integer.MIN_VALUE, Integer.MAX_VALUE}) rejects(mode, "image/png");
        BrowserUploadPolicy png = single(" IMAGE/PNG ");
        check(png.mimeTypes().equals(Collections.singletonList("image/png")));
        check(png.acceptsMime("IMAGE/PNG"));
        check(!png.acceptsMime("video/mp4"));
        check(!png.acceptsMime("image/jpeg"));
        for (String invalid : new String[]{null, "", "image/*", "*/*", "image/png; charset=binary", "image/png\r\n", "image/p ng", "image/png/extra", "image/", "video/", "application/octet-stream", "video/*+xml"})
            check(!png.acceptsMime(invalid));
        for (String unsupported : new String[]{"audio/*", "text/html", "application/pdf", ".txt", ".svgz", "image/", "image/**", "image/png;foo=bar", "image/p ng", "\n", "image/png\u0000", "*", "*/png", "video/*+xml", "image/png/extra"}) rejects(0, unsupported);
        BrowserUploadPolicy mixed = single("application/pdf, image/png", ".MP4", "audio/*", "image/png", ".unknown");
        check(mixed.mimeTypes().equals(Arrays.asList("image/png", "video/mp4")));
        check(mixed.acceptsMime("image/png")); check(mixed.acceptsMime("video/mp4"));
        check(!mixed.acceptsMime("video/webm")); check(!mixed.acceptsMime("application/pdf"));
        BrowserUploadPolicy families = single("image/png", "image/*", "image/jpeg", "video/mp4", "*/*");
        check(families.mimeTypes().equals(Arrays.asList("image/*", "video/*")));
        check(families.acceptsMime("image/vnd.example+binary"));
        check(families.acceptsMime("video/quicktime"));
        check(!families.acceptsMime("audio/mp4"));
        for (String[] pair : new String[][]{{".jpg","image/jpeg"},{".jpeg","image/jpeg"},{".jpe","image/jpeg"},{".png","image/png"},{".gif","image/gif"},{".webp","image/webp"},{".avif","image/avif"},{".bmp","image/bmp"},{".heic","image/heic"},{".heif","image/heif"},{".mp4","video/mp4"},{".m4v","video/mp4"},{".webm","video/webm"},{".mov","video/quicktime"},{".3gp","video/3gpp"}}) {
            check(single(pair[0]).mimeTypes().equals(Collections.singletonList(pair[1])));
            check(single(pair[0]).acceptsMime(pair[1]));
        }
        // A later mutation of the platform's array or a returned list cannot broaden the request.
        String[] source = {"image/png"}; BrowserUploadPolicy immutable = BrowserUploadPolicy.create(0, source);
        source[0] = "video/*"; check(!immutable.acceptsMime("video/mp4"));
        try { immutable.mimeTypes().add("video/*"); throw new AssertionError("Mutable MIME policy"); }
        catch (UnsupportedOperationException expected) { assertions++; }
        rejects(0, new String[17]);
        rejects(0, "image/" + repeat('a', 123)); // 129 characters exceeds one-token budget.
        rejects(0, repeat(' ', 1025));
        rejects(0, repeat(',', 16)); // 17 empty tokens must still consume the token budget.
        rejects(0, "image/png", repeat(' ', 1016)); // Total accept budget is independent of per-token bounds.
        String[] sixteen = new String[16]; Arrays.fill(sixteen, "image/png");
        check(single(sixteen).acceptsMime("image/png"));
        Arrays.fill(sixteen, repeat(' ', 55) + "image/png"); // Exactly 1,024 input characters.
        check(single(sixteen).acceptsMime("image/png"));
        check(families.acceptsMime("image/" + repeat('a', 122)));
        check(!families.acceptsMime("image/" + repeat('a', 123)));
        check(result(png, new String[]{"content://fixed/one"}, "image/png"));
        check(!result(png, new String[]{"content://fixed/one"}, "video/mp4"));
        check(!result(png, new String[]{"content://fixed/one", "content://fixed/two"}, "image/png", "image/png"));
        BrowserUploadPolicy multi = BrowserUploadPolicy.create(1, new String[]{"image/png,video/mp4"});
        check(multi.allowsMultiple());
        check(result(multi, new String[]{"content://fixed/one", "content://fixed/two"}, "image/png", "video/mp4"));
        check(!result(multi, new String[]{"content://fixed/one", "content://fixed/one"}, "image/png", "image/png"));
        check(!result(multi, new String[]{"content://fixed/one", null}, "image/png", "image/png"));
        check(!result(multi, new String[]{""}, "image/png"));
        check(!result(multi, new String[]{"content://fixed/one"}, "video/webm"));
        check(!result(multi, null, "image/png"));
        check(!result(multi, new String[0]));
        check(!result(multi, new String[]{"content://fixed/one"}));
        check(!multi.acceptsResult(Collections.singletonList("content://fixed/one"), null));
        List<String> eight = new ArrayList<>(), types = new ArrayList<>();
        for (int i = 0; i < 8; i++) { eight.add("content://fixed/" + i); types.add("image/png"); }
        check(multi.acceptsResult(eight, types));
        eight.add("content://fixed/8"); types.add("image/png");
        check(!multi.acceptsResult(eight, types));
        System.out.println("PASS " + assertions + " browser upload policy assertions");
    }
}

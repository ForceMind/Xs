package dev.forcemind.xs.tests;

import dev.forcemind.xs.core.MediaDownloadPolicy;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

public final class MediaDownloadPolicyTest {
    private static int assertions;
    private static final String POST = "https://x.com/i/web/status/12345";
    private static final byte[] PNG = {
        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        0, 0, 0, 0, 0x49, 0x45, 0x4e, 0x44, (byte) 0xae, 0x42, 0x60, (byte) 0x82
    };

    private static void check(boolean valid) {
        if (!valid) throw new AssertionError("Policy assertion " + (assertions + 1));
        assertions++;
    }

    private static void rejects(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException expected) {
            check(!expected.getMessage().contains("http"));
            return;
        }
        throw new AssertionError("Unsupported resource accepted");
    }

    private static void envelope(byte[] bytes, String extension, boolean complete) throws Exception {
        File file = File.createTempFile("xs-envelope-", ".test");
        try {
            Files.write(file.toPath(), bytes);
            check(MediaDownloadPolicy.hasCompleteEnvelope(file, extension) == complete);
        } finally {
            check(file.delete());
        }
    }

    public static void main(String[] args) throws Exception {
        String base = "https://pbs.twimg.com/media/AbC_123-456";
        for (String suffix : new String[]{"", ".jpg", ".png", ".gif", ".webp", ".jpeg:orig",
                "?format=jpg&name=orig", "?name=4096x4096&format=png", ".jpg:large"}) {
            String input = base + suffix;
            check(input.equals(MediaDownloadPolicy.validateImage(POST, input)));
        }
        for (String invalid : new String[]{
                "http://pbs.twimg.com/media/A.jpg", "file:///media/A.jpg", "content://pbs.twimg.com/media/A",
                "blob:https://pbs.twimg.com/A", "https://127.0.0.1/media/A.jpg", "https://[::1]/media/A.jpg",
                "https://192.168.1.1/media/A.jpg", "https://localhost/media/A.jpg", "https://pbs.twimg.com.evil.test/media/A",
                "https://video.twimg.com/media/A.mp4", "https://evil.test/?url=https://pbs.twimg.com/media/A.jpg",
                "https://pbs.twimg.com@evil.test/media/A.jpg", "https://user:pass@pbs.twimg.com/media/A.jpg",
                "https://pbs.twimg.com:443/media/A.jpg", "https://pbs.twimg.com/media/A.jpg#fragment",
                "https://pbs.twimg.com/media/../A.jpg", "https://pbs.twimg.com/media/%2e%2e/A.jpg",
                "https://pbs.twimg.com/media/A%2fB.jpg", "https://pbs.twimg.com/media/A%252fB.jpg",
                "https://pbs.twimg.com/media/A/extra.jpg", "https://pbs.twimg.com/media//A.jpg",
                "https://pbs.twimg.com/profile_images/A.jpg", "https://pbs.twimg.com/media/A.m3u8",
                "https://pbs.twimg.com/media/A.mp4", "https://pbs.twimg.com/media/A.mpd",
                "https://pbs.twimg.com/media/A.jpg?redirect=https://evil.test", "https://pbs.twimg.com/media/A.jpg?format=html",
                "https://pbs.twimg.com/media/A.jpg?format=jpg&format=png", "https://pbs.twimg.com/media/A.jpg?name=orig&",
                "https://pbs.twimg.com/media/A.jpg?format=%6apg", "https://pbs.twimg.com/media/A.jpg?name=orig=evil",
                "https://pbs.twimg.com/media/A.jpg?name=0x0", "https://pbs.twimg.com/media/A.jpg?", "//pbs.twimg.com/media/A.jpg",
                "https://pbs.twimg.com/media/A.jpg\n", "", null}) {
            rejects(() -> MediaDownloadPolicy.validateImage(POST, invalid));
        }
        for (String invalidPost : new String[]{"https://x.com/home", "https://x.com/messages", "https://evil.test/a/status/1",
                "http://x.com/a/status/1", "https://x.com:443/a/status/1", "https://x.com/a/status/1#fragment",
                "https://user:pass@x.com/a/status/1", "x.com/a/status/1", null}) {
            rejects(() -> MediaDownloadPolicy.validateImage(invalidPost, base));
        }
        rejects(() -> MediaDownloadPolicy.validateImage(POST, base + new String(new char[2100]).replace('\0', 'a')));

        check("jpg".equals(MediaDownloadPolicy.imageExtension(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff}, "image/jpeg")));
        check("png".equals(MediaDownloadPolicy.imageExtension(PNG, "IMAGE/PNG; charset=binary")));
        byte[] gif87 = "GIF87a;".getBytes(StandardCharsets.US_ASCII);
        byte[] gif89 = "GIF89a;".getBytes(StandardCharsets.US_ASCII);
        check("gif".equals(MediaDownloadPolicy.imageExtension(gif87, "image/gif")));
        check("gif".equals(MediaDownloadPolicy.imageExtension(gif89, "application/octet-stream")));
        byte[] webp = {0x52, 0x49, 0x46, 0x46, 4, 0, 0, 0, 0x57, 0x45, 0x42, 0x50};
        check("webp".equals(MediaDownloadPolicy.imageExtension(webp, "image/webp")));
        rejects(() -> MediaDownloadPolicy.imageExtension(PNG, "image/jpeg"));
        rejects(() -> MediaDownloadPolicy.imageExtension(PNG, "text/html"));
        rejects(() -> MediaDownloadPolicy.imageExtension(PNG, null));
        rejects(() -> MediaDownloadPolicy.imageExtension("<html><body>error".getBytes(StandardCharsets.UTF_8), "image/png"));
        rejects(() -> MediaDownloadPolicy.imageExtension("#EXTM3U".getBytes(StandardCharsets.UTF_8), "application/octet-stream"));
        rejects(() -> MediaDownloadPolicy.imageExtension(new byte[0], "image/jpeg"));
        rejects(() -> MediaDownloadPolicy.imageExtension(null, "image/jpeg"));
        rejects(() -> MediaDownloadPolicy.imageExtension(Arrays.copyOf(PNG, 7), "image/png"));

        check(MediaDownloadPolicy.completeLength(-1, 12));
        check(MediaDownloadPolicy.completeLength(12, 12));
        check(MediaDownloadPolicy.completeLength(MediaDownloadPolicy.MAX_BYTES, MediaDownloadPolicy.MAX_BYTES));
        check(!MediaDownloadPolicy.completeLength(12, 11));
        check(!MediaDownloadPolicy.completeLength(11, 12));
        check(!MediaDownloadPolicy.completeLength(-2, 12));
        check(!MediaDownloadPolicy.completeLength(-1, 0));
        check(!MediaDownloadPolicy.completeLength(-1, MediaDownloadPolicy.MAX_BYTES + 1));

        envelope(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9}, "jpg", true);
        envelope(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0}, "jpg", false);
        envelope(new byte[]{(byte) 0xff, (byte) 0xd8}, "jpg", false);
        envelope(PNG, "png", true);
        envelope(Arrays.copyOf(PNG, PNG.length - 1), "png", false);
        byte[] badCrc = PNG.clone();
        badCrc[badCrc.length - 1] ^= 1;
        envelope(badCrc, "png", false);
        envelope(PNG, "jpg", false);
        envelope(gif87, "gif", true);
        envelope(gif89, "gif", true);
        envelope(Arrays.copyOf(gif89, gif89.length - 1), "gif", false);
        envelope(webp, "webp", true);
        envelope(Arrays.copyOf(webp, webp.length - 1), "webp", false);
        byte[] tooLargeRiff = webp.clone();
        tooLargeRiff[4] = 8;
        envelope(tooLargeRiff, "webp", false);
        byte[] unsignedRiff = webp.clone();
        unsignedRiff[7] = (byte) 0xff;
        envelope(unsignedRiff, "webp", false);
        check(!MediaDownloadPolicy.hasCompleteEnvelope(null, "jpg"));
        check("image/jpeg".equals(MediaDownloadPolicy.mimeFor("jpg")));
        check("image/png".equals(MediaDownloadPolicy.mimeFor("png")));
        check("image/gif".equals(MediaDownloadPolicy.mimeFor("gif")));
        check("image/webp".equals(MediaDownloadPolicy.mimeFor("webp")));
        rejects(() -> MediaDownloadPolicy.mimeFor("mp4"));
        System.out.println("PASS " + assertions + " media download policy assertions");
    }
}

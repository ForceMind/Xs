package dev.forcemind.xs.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Narrow public-image download policy. This is not a generic URL downloader:
 * callers must obtain the candidate from the explicitly selected public post.
 * Validation errors deliberately never include the supplied URL.
 */
public final class MediaDownloadPolicy {
    public static final long MAX_BYTES = 20L * 1024 * 1024;
    public static final int MAX_QUEUE = 16;
    public static final int MAX_REDIRECTS = 3;
    private static final Pattern IMAGE_PATH = Pattern.compile(
        "/media/[A-Za-z0-9_-]{1,180}(?:\\.(?:jpg|jpeg|png|webp|gif))?" +
        "(?::(?:orig|large|medium|small|thumb))?");

    private MediaDownloadPolicy() {}

    /** Reapply on every redirect; never send browser cookies or credentials. */
    public static String validateImage(String postUrl, String mediaUrl) {
        if (postUrl == null || postUrl.length() > 4096 ||
                PostUrls.extractPostId(postUrl) == null) {
            throw invalid();
        }
        URI post = parse(postUrl);
        if (!"https".equalsIgnoreCase(post.getScheme()) ||
                post.getRawUserInfo() != null || post.getPort() != -1 ||
                post.getRawFragment() != null) throw invalid();
        if (mediaUrl == null || mediaUrl.length() > 2048) throw invalid();
        URI uri = parse(mediaUrl);
        if (!"https".equalsIgnoreCase(uri.getScheme()) ||
                !"pbs.twimg.com".equalsIgnoreCase(uri.getHost()) ||
                uri.getRawUserInfo() != null || uri.getPort() != -1 ||
                uri.getRawFragment() != null || uri.isOpaque() ||
                uri.getRawPath() == null ||
                !IMAGE_PATH.matcher(uri.getRawPath()).matches()) throw invalid();
        String query = uri.getRawQuery();
        if (query != null) {
            Set<String> seen = new HashSet<>();
            for (String part : query.split("&", -1)) {
                String[] pair = part.split("=", -1);
                if (pair.length != 2 || !seen.add(pair[0])) throw invalid();
                if ("format".equals(pair[0])) {
                    if (!pair[1].matches("jpg|jpeg|png|webp|gif")) throw invalid();
                } else if ("name".equals(pair[0])) {
                    if (!pair[1].matches("orig|large|medium|small|thumb|[1-9][0-9]{0,4}x[1-9][0-9]{0,4}")) {
                        throw invalid();
                    }
                } else throw invalid();
            }
        }
        return uri.toASCIIString();
    }

    /** Detect actual image format and require an honest HTTP media type. */
    public static String imageExtension(byte[] prefix, String contentType) {
        String extension = sniff(prefix);
        if (extension == null || contentType == null) throw invalid();
        String mime = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!"application/octet-stream".equals(mime) && !mimeFor(extension).equals(mime)) {
            throw invalid();
        }
        return extension;
    }

    /** Unknown length is allowed, but every streamed byte remains bounded. */
    public static boolean completeLength(long declared, long actual) {
        return actual > 0 && actual <= MAX_BYTES && declared >= -1 &&
            (declared == -1 || declared == actual);
    }

    /**
     * Basic truncation detection in addition to the platform's full decode.
     * A matching envelope is not, on its own, proof that a file is decodable.
     */
    public static boolean hasCompleteEnvelope(File file, String extension) throws IOException {
        if (file == null || !file.isFile() || file.length() < 4 || file.length() > MAX_BYTES) {
            return false;
        }
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long size = input.length();
            byte[] header = new byte[(int) Math.min(size, 16)];
            input.readFully(header);
            if (!extensionEquals(extension, sniff(header))) return false;
            if ("jpg".equals(extension)) {
                input.seek(size - 2);
                return input.readUnsignedByte() == 0xff && input.readUnsignedByte() == 0xd9;
            }
            if ("png".equals(extension)) {
                if (size < 20) return false;
                byte[] end = {0, 0, 0, 0, 0x49, 0x45, 0x4e, 0x44,
                              (byte) 0xae, 0x42, 0x60, (byte) 0x82};
                input.seek(size - end.length);
                for (byte b : end) if (input.readUnsignedByte() != (b & 0xff)) return false;
                return true;
            }
            if ("gif".equals(extension)) {
                input.seek(size - 1);
                return input.readUnsignedByte() == 0x3b;
            }
            if ("webp".equals(extension)) {
                long riffSize = (header[4] & 0xffL) | ((header[5] & 0xffL) << 8) |
                    ((header[6] & 0xffL) << 16) | ((header[7] & 0xffL) << 24);
                return riffSize + 8 == size;
            }
            return false;
        }
    }

    public static String mimeFor(String extension) {
        if ("jpg".equals(extension)) return "image/jpeg";
        if ("png".equals(extension)) return "image/png";
        if ("gif".equals(extension)) return "image/gif";
        if ("webp".equals(extension)) return "image/webp";
        throw invalid();
    }

    private static boolean extensionEquals(String a, String b) {
        return a != null && a.equals(b);
    }

    private static String sniff(byte[] bytes) {
        if (bytes == null) return null;
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff &&
                (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff) return "jpg";
        if (matches(bytes, 0, new int[]{0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})) return "png";
        if (matches(bytes, 0, new int[]{0x47, 0x49, 0x46, 0x38, 0x37, 0x61}) ||
            matches(bytes, 0, new int[]{0x47, 0x49, 0x46, 0x38, 0x39, 0x61})) return "gif";
        if (matches(bytes, 0, new int[]{0x52, 0x49, 0x46, 0x46}) &&
            matches(bytes, 8, new int[]{0x57, 0x45, 0x42, 0x50})) return "webp";
        return null;
    }

    private static boolean matches(byte[] bytes, int offset, int[] value) {
        if (bytes.length < offset + value.length) return false;
        for (int i = 0; i < value.length; i++) {
            if ((bytes[offset + i] & 0xff) != value[i]) return false;
        }
        return true;
    }

    private static URI parse(String raw) {
        try { return URI.create(raw); }
        catch (IllegalArgumentException exception) { throw invalid(); }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Unsupported public image resource");
    }
}

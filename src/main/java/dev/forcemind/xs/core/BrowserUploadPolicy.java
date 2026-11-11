package dev.forcemind.xs.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Immutable, media-only interpretation of one native WebView file request.
 * MIME declarations filter selection; they do not authenticate file contents or an upload.
 */
public final class BrowserUploadPolicy {
    public static final int MODE_OPEN = 0;
    public static final int MODE_OPEN_MULTIPLE = 1;
    public static final int MAX_FILES = 8;
    private static final int MAX_TOKENS = 16;
    private static final int MAX_ACCEPT_CHARS = 1024;
    private static final int MAX_TOKEN_CHARS = 128;
    private final boolean multiple;
    private final List<String> mimeTypes;

    private BrowserUploadPolicy(boolean multiple, List<String> types) {
        this.multiple = multiple;
        this.mimeTypes = Collections.unmodifiableList(new ArrayList<>(types));
    }

    /** Unsupported entries are omitted; an entirely unsupported request is rejected. */
    public static BrowserUploadPolicy create(int mode, String[] acceptTypes) {
        if (mode != MODE_OPEN && mode != MODE_OPEN_MULTIPLE) throw invalid();
        List<String> types = new ArrayList<>();
        int chars = 0, tokens = 0;
        boolean specified = false;
        if (acceptTypes != null) {
            if (acceptTypes.length > MAX_TOKENS) throw invalid();
            for (String entry : acceptTypes) {
                if (entry == null) continue;
                if (entry.length() > MAX_ACCEPT_CHARS - chars) throw invalid();
                chars += entry.length();
                // Preserve empty fields for the budget: commas cannot bypass token limits.
                String[] parts = entry.split(",", -1);
                if (parts.length > MAX_TOKENS - tokens) throw invalid();
                tokens += parts.length;
                for (String part : parts) {
                    if (part.length() > MAX_TOKEN_CHARS) throw invalid();
                    if (hasControls(part)) { specified = true; continue; }
                    String token = part.trim().toLowerCase(Locale.ROOT);
                    if (token.isEmpty()) continue;
                    specified = true;
                    if ("*/*".equals(token)) {
                        add(types, "image/*");
                        add(types, "video/*");
                    } else {
                        String type = token.startsWith(".") ? extension(token) : token;
                        if (mediaType(type, true)) add(types, type);
                    }
                }
            }
        }
        if (!specified) {
            add(types, "image/*");
            add(types, "video/*");
        }
        if (types.isEmpty()) throw invalid();
        return new BrowserUploadPolicy(mode == MODE_OPEN_MULTIPLE, types);
    }

    public boolean allowsMultiple() { return multiple; }
    public List<String> mimeTypes() { return mimeTypes; }

    public boolean acceptsMime(String value) {
        if (value == null || value.length() > MAX_TOKEN_CHARS || hasControls(value)) return false;
        String type = value.trim().toLowerCase(Locale.ROOT);
        if (!mediaType(type, false)) return false;
        return mimeTypes.contains(type) || mimeTypes.contains(type.substring(0, type.indexOf('/')) + "/*");
    }

    /** URI ownership, content scheme and current-page checks remain native responsibilities. */
    public boolean acceptsResult(List<String> uris, List<String> types) {
        if (uris == null || types == null || uris.isEmpty() || uris.size() != types.size() ||
                uris.size() > (multiple ? MAX_FILES : 1)) return false;
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < uris.size(); i++) {
            String uri = uris.get(i);
            if (uri == null || uri.isEmpty() || !seen.add(uri) || !acceptsMime(types.get(i))) return false;
        }
        return true;
    }

    private static void add(List<String> types, String type) {
        String family = type.substring(0, type.indexOf('/')) + "/";
        if (types.contains(family + "*")) return;
        if (type.endsWith("/*")) {
            for (int i = types.size() - 1; i >= 0; i--) if (types.get(i).startsWith(family)) types.remove(i);
        }
        if (!types.contains(type)) types.add(type);
    }

    private static boolean mediaType(String type, boolean wildcard) {
        if (type == null || type.length() > MAX_TOKEN_CHARS) return false;
        int slash = type.indexOf('/');
        if (slash < 0 || !(type.startsWith("image/") || type.startsWith("video/"))) return false;
        String subtype = type.substring(slash + 1);
        if ("*".equals(subtype)) return wildcard;
        // A conservative MIME-token subset, including vendor and structured suffixes.
        return subtype.matches("[a-z0-9][a-z0-9.+_-]*");
    }

    private static boolean hasControls(String value) {
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return true;
        return false;
    }

    private static String extension(String value) {
        switch (value) {
            case ".jpg": case ".jpeg": case ".jpe": return "image/jpeg";
            case ".png": return "image/png";
            case ".gif": return "image/gif";
            case ".webp": return "image/webp";
            case ".avif": return "image/avif";
            case ".bmp": return "image/bmp";
            case ".heic": return "image/heic";
            case ".heif": return "image/heif";
            case ".mp4": case ".m4v": return "video/mp4";
            case ".webm": return "video/webm";
            case ".mov": return "video/quicktime";
            case ".3gp": return "video/3gpp";
            default: return null;
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Unsupported or oversized browser media request");
    }
}

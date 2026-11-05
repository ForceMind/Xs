package dev.forcemind.xs.android;

import dev.forcemind.xs.core.ObservationGate;
import dev.forcemind.xs.core.PostUrls;
import dev.forcemind.xs.core.FeedCapturePolicy;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Offline parser for a future origin-scoped WebView observation listener.
 * This class is deliberately NOT attached to a WebView and has no storage,
 * network, credential, consent, file, or native-operation capability.
 *
 * Every field from the website is untrusted. Native scope, epoch validation,
 * foreground/route/gesture checks happen separately in ObservationGate.
 */
public final class WebObservationParser {
    private static final int MAX_CHARS = ObservationGate.MAX_BYTES;
    private static final Set<String> KEYS = new HashSet<>(Arrays.asList(
        "v", "type", "epoch", "seq", "id", "postUrl", "author",
        "body", "source", "visibleFraction", "dwellMs"
    ));

    private WebObservationParser() {}

    public static ObservationGate.Message parse(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > MAX_CHARS) {
            throw invalid();
        }
        int bytes = raw.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > ObservationGate.MAX_BYTES) throw invalid();
        try {
            JSONObject object = new JSONObject(raw);
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                if (!KEYS.contains(keys.next())) throw invalid();
            }
            int version = (int) exactLong(object, "v", 1, 1);
            if (version != ObservationGate.VERSION) throw invalid();
            String type = requiredString(object, "type", 32);
            ObservationGate.Type kind;
            if ("POST_OPENED".equals(type)) {
                kind = ObservationGate.Type.POST_OPENED;
            } else if ("FEED_VISIBLE".equals(type)) {
                kind = ObservationGate.Type.FEED_VISIBLE;
            } else throw invalid();
            long epoch = exactLong(object, "epoch", 0, Long.MAX_VALUE);
            long seq = exactLong(object, "seq", 1, Long.MAX_VALUE);
            String id = requiredString(object, "id", 20);
            if (!id.matches("[0-9]{1,20}")) throw invalid();
            String url = requiredString(object, "postUrl", 8192);
            if (!id.equals(PostUrls.extractPostId(url))) throw invalid();
            String author = optionalString(object, "author", 100);
            String body = optionalString(object, "body", 16000);

            String source = null;
            double fraction = 0;
            long dwell = 0;
            if (kind == ObservationGate.Type.FEED_VISIBLE) {
                source = requiredString(object, "source", 32);
                if (!FeedCapturePolicy.supportedSource(source)) throw invalid();
                fraction = exactDouble(object, "visibleFraction");
                dwell = exactLong(object, "dwellMs", 0, 600_000);
            } else if (object.has("source") || object.has("visibleFraction") ||
                       object.has("dwellMs")) {
                throw invalid();
            }
            return new ObservationGate.Message(kind, epoch, seq, bytes, id, url,
                author, body, source, fraction, dwell);
        } catch (JSONException | ArithmeticException invalid) {
            throw invalid();
        }
    }

    private static long exactLong(JSONObject o, String key, long min, long max)
            throws JSONException {
        Object raw = o.get(key);
        if (!(raw instanceof Number) || raw instanceof Double || raw instanceof Float) {
            throw invalid();
        }
        String text = raw.toString();
        if (!text.matches("[0-9]{1,19}")) throw invalid();
        long value;
        try { value = Long.parseLong(text); }
        catch (NumberFormatException bad) { throw invalid(); }
        if (value < min || value > max) throw invalid();
        return value;
    }

    private static double exactDouble(JSONObject o, String key) throws JSONException {
        Object raw = o.get(key);
        if (!(raw instanceof Number)) throw invalid();
        double value = ((Number) raw).doubleValue();
        if (Double.isNaN(value) || Double.isInfinite(value) ||
            value < 0 || value > 1) throw invalid();
        return value;
    }

    private static String requiredString(JSONObject o, String key, int max)
            throws JSONException {
        Object value = o.get(key);
        if (!(value instanceof String)) throw invalid();
        String s = (String) value;
        if (s.isEmpty() || s.length() > max) throw invalid();
        return s;
    }

    private static String optionalString(JSONObject o, String key, int max)
            throws JSONException {
        if (!o.has(key)) return null;
        Object value = o.get(key);
        if (!(value instanceof String)) throw invalid();
        String s = (String) value;
        if (s.length() > max) throw invalid();
        return s;
    }

    private static IllegalArgumentException invalid() {
        // Deliberately avoid including private/raw payload content in errors.
        return new IllegalArgumentException("Invalid observation envelope");
    }
}

package dev.forcemind.xs.browser;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import dev.forcemind.xs.android.WebObservationParser;
import dev.forcemind.xs.core.ObservationGate;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ObservationParserTest {
    private static final String OPEN =
        "{\"v\":1,\"type\":\"POST_OPENED\",\"epoch\":2,\"seq\":1," +
        "\"id\":\"12345\",\"postUrl\":\"https://x.com/alice/status/12345\"," +
        "\"author\":\"alice\",\"body\":\"public test\"}";

    @Test public void onlyAllowedTypedPublicObservationsAreParsed() {
        ObservationGate.Message event = WebObservationParser.parse(OPEN);
        assertEquals(ObservationGate.Type.POST_OPENED, event.type);
        assertEquals(2L, event.epoch);
        assertEquals(1L, event.sequence);
        assertEquals("12345", event.id);
        assertEquals("public test", event.body);
        assertTrue(event.encodedBytes <= ObservationGate.MAX_BYTES);

        ObservationGate.Message feed = WebObservationParser.parse(
            "{\"v\":1,\"type\":\"FEED_VISIBLE\",\"epoch\":2,\"seq\":2," +
            "\"id\":\"12345\",\"postUrl\":\"https://x.com/alice/status/12345\"," +
            "\"source\":\"home_following\",\"visibleFraction\":0.61,\"dwellMs\":1200}");
        assertEquals(ObservationGate.Type.FEED_VISIBLE, feed.type);
        assertEquals(0.61, feed.visibleFraction, 0.00001);
        assertEquals(1200L, feed.dwellMs);
    }

    @Test public void rejectAccountControlAndUnauthorizedInstructions() {
        String[] forbidden = {
            OPEN.replace("\"author\"", "\"scope\":\"local:spoof\",\"author\""),
            OPEN.replace("\"author\"", "\"accountId\":\"someone\",\"author\""),
            OPEN.replace("\"author\"", "\"cookie\":\"secret\",\"author\""),
            OPEN.replace("\"type\":\"POST_OPENED\"", "\"type\":\"DELETE_ARCHIVE\""),
            OPEN.replace("\"v\":1", "\"v\":2"),
            OPEN.replace("\"id\":\"12345\"", "\"id\":\"99999\""),
            OPEN.replace("\"seq\":1", "\"seq\":1.3"),
            OPEN.replace("\"epoch\":2", "\"epoch\":-2"),
            OPEN.replace("\"body\":\"public test\"", "\"body\":null"),
            OPEN.replace("\"postUrl\":\"https://x.com/alice/status/12345\"",
                "\"postUrl\":\"https://evil.test/user/status/12345\""),
            OPEN.replace("\"author\"", "\"visibleFraction\":0.9,\"author\"")
        };
        for (String message : forbidden) expectInvalid(message);
        expectInvalid(null);
        expectInvalid("");
        expectInvalid(OPEN + new String(new char[65536]).replace('\0', 'x'));
        expectInvalid("{broken");
    }

    private static void expectInvalid(String s) {
        try {
            WebObservationParser.parse(s);
            fail("Invalid envelope must be rejected");
        } catch (IllegalArgumentException expected) {
            assertEquals("Invalid observation envelope", expected.getMessage());
        }
    }
}

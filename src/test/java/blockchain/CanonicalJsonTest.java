package blockchain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CanonicalJsonTest {
    @Test
    void canonicalizesObjectKeysAndWhitespace() {
        String input = """
                {
                  "type": "HARVESTED",
                  "payload": { "quantity": "1200.000", "batchCode": "MANGO-2026-0001" },
                  "networkId": "agritrace-test",
                  "eventId": "vector-1"
                }
                """;

        assertEquals(
                "{\"eventId\":\"vector-1\",\"networkId\":\"agritrace-test\","
                        + "\"payload\":{\"batchCode\":\"MANGO-2026-0001\",\"quantity\":\"1200.000\"},"
                        + "\"type\":\"HARVESTED\"}",
                CanonicalJson.canonicalize(input));
    }

    @Test
    void rejectsNullOrBlankInput() {
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.canonicalize(null));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.canonicalize("  "));
    }

    @Test
    void rejectsMalformedJson() {
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.canonicalize("{invalid"));
    }

    @Test
    void rejectsDuplicateObjectProperties() {
        assertThrows(IllegalArgumentException.class,
                () -> CanonicalJson.canonicalize("{\"eventId\":\"first\",\"eventId\":\"second\"}"));
    }
}

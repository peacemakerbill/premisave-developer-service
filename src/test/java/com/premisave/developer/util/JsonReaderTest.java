package com.premisave.developer.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonReaderTest {

    @Test
    void readsNestedObjectsAndArraysWithEveryKindOfValue() {
        Map<String, Object> m = JsonReader.readObject(
                " {\"a\":1,\"b\":-2.5,\"c\":true,\"d\":null,\"e\":\"x\\ny\\u00e9\",\"f\":[1,{\"g\":[]}],\"h\":{},\"i\":1e3} ");
        assertEquals(1L, m.get("a"));
        assertEquals(-2.5, m.get("b"));
        assertEquals(Boolean.TRUE, m.get("c"));
        assertNull(m.get("d"));
        assertTrue(m.containsKey("d"));
        assertEquals("x\ny\u00e9", m.get("e"));
        List<?> f = (List<?>) m.get("f");
        assertEquals(1L, f.get(0));
        assertEquals(List.of(), ((Map<?, ?>) f.get(1)).get("g"));
        assertEquals(Map.of(), m.get("h"));
        assertEquals(1000.0, m.get("i"));
    }

    @Test
    void readsAListOfObjectsLikeGitHubsEmailAddresses() {
        List<Object> l = JsonReader.readArray("[{\"email\":\"a@b.com\",\"primary\":true,\"verified\":true},{\"email\":\"c@d.com\",\"primary\":false,\"verified\":false}]");
        assertEquals(2, l.size());
        assertEquals("a@b.com", ((Map<?, ?>) l.get(0)).get("email"));
    }

    @Test
    void bigWholeNumbersAreKeptAsLongsAndHugeOnesAsDoubles() {
        assertEquals(9007199254740993L, JsonReader.read("9007199254740993"));
        assertEquals(1.0E30, JsonReader.read("1000000000000000000000000000000"));
    }

    @Test
    void refusesAnythingThatIsNotValidJson() {
        for (String bad : new String[] {"", "{", "{\"a\"}", "{\"a\":}", "[1,]", "{\"a\":1,}", "{\"a\":1} x", "nul", "'x'", "{\"a\":01x}",
                "\"unterminated", "\"bad\\q\"", "{\"a\":\"\u0001\"}", "-", "1.", "1e"}) {
            assertThrows(IllegalArgumentException.class, () -> JsonReader.read(bad), bad);
        }
    }

    @Test
    void refusesTheWrongTopLevelShape() {
        assertThrows(IllegalArgumentException.class, () -> JsonReader.readObject("[1]"));
        assertThrows(IllegalArgumentException.class, () -> JsonReader.readArray("{}"));
    }

    @Test
    void refusesAbsurdNesting() {
        String deep = "[".repeat(100) + "]".repeat(100);
        assertThrows(IllegalArgumentException.class, () -> JsonReader.read(deep));
    }
}

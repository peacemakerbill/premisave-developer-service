package com.premisave.developer.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlatJsonTest {

    @Test
    void roundTripsStringsNumbersBooleansAndNull() {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("a", "text with \"quotes\", a \\ and a\nnewline");
        in.put("b", 42L);
        in.put("c", -7);
        in.put("d", true);
        in.put("e", null);
        Map<String, Object> out = FlatJson.read(FlatJson.write(in));
        assertEquals(in.get("a"), out.get("a"));
        assertEquals(42L, out.get("b"));
        assertEquals(-7L, out.get("c"));
        assertEquals(true, out.get("d"));
        assertNull(out.get("e"));
        assertEquals(5, out.size());
    }

    @Test
    void readsUnicodeEscapesAndWhitespace() {
        assertEquals("Aé", FlatJson.read(" { \"k\" : \"A\\u00e9\" } ").get("k"));
        assertEquals(0, FlatJson.read("{}").size());
    }

    @Test
    void rejectsAnythingThatIsNotAFlatObject() {
        for (String bad : new String[] {"", "[]", "{\"a\":{}}", "{\"a\":[1]}", "{\"a\":1.5}", "{\"a\"}", "{\"a\":1,}", "{\"a\":1} x",
                "{\"a\":\"unterminated}", "nope", "{\"a\":-}"}) {
            assertThrows(IllegalArgumentException.class, () -> FlatJson.read(bad), bad);
        }
    }
}

package com.premisave.developer.developer;

import com.premisave.developer.common.DeveloperException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsernamesTest {

    @Test
    void goodNamesPass() {
        for (String ok : new String[] {"abc", "jane.doe", "jane_doe", "jane-doe", "j4ne", "a1.b2_c3-d4", "a".repeat(30)}) {
            Usernames.check(ok);
            assertTrue(Usernames.isValid(ok), ok);
        }
    }

    @Test
    void badNamesFail() {
        for (String bad : new String[] {"", "ab", "a".repeat(31), "1abc", ".abc", "abc.", "a..b", "a._b", "ABC", "a b", "a@b", "caf\u00e9"}) {
            assertThrows(DeveloperException.class, () -> Usernames.check(bad), bad);
        }
    }

    @Test
    void reservedWordsAndBrandsAreCaughtThroughSeparators() {
        for (String r : new String[] {"admin", "ad.min", "a_d-m.i_n", "support", "premisave", "my.premisave.app", "pre-misave", "safaricom.dev", "mpesa", "dev"}) {
            assertTrue(Usernames.isReserved(r), r);
        }
        for (String ok : new String[] {"jane.doe", "devin", "administrate", "supporter1", "rooted.fan"}) {
            assertFalse(Usernames.isReserved(ok), ok);
        }
    }

    @Test
    void normaliseTrimsAndLowerCases() {
        assertEquals("jane.doe", Usernames.normalise("  Jane.DOE "));
        assertEquals("", Usernames.normalise(null));
    }

    @Test
    void theBaseComesFromTheNamesThenTheEmail() {
        assertEquals("jane.doe", Usernames.base("Jane", "Doe", "x@y.com"));
        assertEquals("jose.muller", Usernames.base("Jos\u00e9", "M\u00fcller", "x@y.com"));
        assertEquals("jane", Usernames.base("Jane", null, "x@y.com"));
        assertEquals("fallback", Usernames.base("\u0416", "\u0418", "fallback@y.com"));
        assertEquals("dev123", Usernames.base(null, null, "123@y.com"));
        assertEquals("dev", Usernames.base(null, null, "@@@"));
        assertEquals("a.dev", Usernames.base("A", null, "x@y.com"));
        assertTrue(Usernames.base("Averyveryveryverylongfirstname", "Andlonglastnametoo", "x@y.com").length() <= 22);
        assertFalse(Usernames.base("Averyveryveryverylongfirst", "x", "x@y.com").endsWith("."));
    }

    @Test
    void candidatesStartWithTheBaseThenAddDigits() {
        assertEquals("jane.doe", Usernames.candidate("jane.doe", 0));
        assertTrue(Usernames.candidate("jane.doe", 1).matches("jane\\.doe[0-9]{4}"));
        assertTrue(Usernames.candidate("jane.doe", 21).matches("jane\\.doe[0-9]{8}"));
        assertTrue(Usernames.isValid(Usernames.candidate(Usernames.base("x".repeat(40), null, "a@b.co"), 21)));
        assertNull(null);
    }
}

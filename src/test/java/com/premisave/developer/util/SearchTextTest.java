package com.premisave.developer.util;

import com.premisave.developer.exception.DeveloperException;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchTextTest {

    @Test
    void blankSearchTextMeansNoSearch() {
        assertNull(SearchText.clean(null));
        assertNull(SearchText.clean(""));
        assertNull(SearchText.clean("   "));
    }

    @Test
    void surroundingSpacesAreTrimmed() {
        assertEquals("jane@example.com", SearchText.clean("  jane@example.com "));
    }

    @Test
    void textOverTheLimitIsRefused() {
        assertEquals(SearchText.MAX_LENGTH, SearchText.clean("a".repeat(SearchText.MAX_LENGTH)).length());
        assertThrows(DeveloperException.class, () -> SearchText.clean("a".repeat(SearchText.MAX_LENGTH + 1)));
    }

    @Test
    void everyPatternCharacterIsEscaped() {
        assertEquals("a\\.b\\+c\\*\\(d\\)\\[e\\]\\{f\\}\\|g\\^h\\$i\\?j\\\\k", SearchText.escape("a.b+c*(d)[e]{f}|g^h$i?j\\k"));
    }

    @Test
    void escapedTextMatchesItselfAndNothingElse() {
        Pattern p = Pattern.compile(SearchText.escape("j.doe+test@example.com"), Pattern.CASE_INSENSITIVE);
        assertTrue(p.matcher("J.Doe+test@example.com").find());
        assertFalse(p.matcher("jXdoeeetest@example.com").find());
        assertTrue(Pattern.compile(SearchText.escape(".*"), Pattern.CASE_INSENSITIVE).matcher("a.*b").find());
        assertFalse(Pattern.compile(SearchText.escape(".*"), Pattern.CASE_INSENSITIVE).matcher("anything").find());
    }

    @Test
    void ordinaryTextIsLeftAlone() {
        assertEquals("jane_doe-2@example", SearchText.escape("jane_doe-2@example"));
    }

    @Test
    void exactMatchesTheWholeValueOnly() {
        Pattern p = Pattern.compile(SearchText.exact(" app_1 "));
        assertTrue(p.matcher("app_1").matches());
        assertFalse(p.matcher("app_10").matches());
        assertFalse(p.matcher("xapp_1").matches());
        assertFalse(Pattern.compile(SearchText.exact(".*")).matcher("anything").matches());
    }

    @Test
    void containsMatchesAnywhereAndTakesNoPatternFromTheUser() {
        assertTrue(Pattern.compile(SearchText.contains("_DELETED")).matcher("APP_DELETED").find());
        assertFalse(Pattern.compile(SearchText.contains("a.c")).matcher("abc").find());
    }

    @Test
    void noTextMeansEverythingMatches() {
        assertEquals(SearchText.ANY, SearchText.exact(null));
        assertEquals(SearchText.ANY, SearchText.exact("   "));
        assertEquals(SearchText.ANY, SearchText.contains(null));
        assertEquals(SearchText.ANY, SearchText.contains(""));
        assertTrue("whatever".matches(SearchText.ANY));
    }
}

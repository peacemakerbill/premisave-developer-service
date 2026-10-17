package com.premisave.developer.util;

import com.premisave.developer.exception.DeveloperException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PersonNamesTest {

    @Test
    void requiredAndOptionalTidyTheValue() {
        assertEquals("Anne Marie", PersonNames.required("firstName", "  Anne   Marie ", 60));
        assertEquals("O'Brien", PersonNames.required("lastName", "O'Brien", 60));
        assertNull(PersonNames.optional("middleName", "   ", 60));
        assertNull(PersonNames.optional("middleName", null, 60));
        assertThrows(DeveloperException.class, () -> PersonNames.required("firstName", " ", 60));
        assertThrows(DeveloperException.class, () -> PersonNames.required("firstName", null, 60));
    }

    @Test
    void symbolsDigitsMarkupAndOverlongNamesAreRefused() {
        for (String bad : new String[] {"J4ne", "Jane!", "<b>", "-Jane", "1", "Jane_Doe", "Jane@", "x".repeat(61)}) {
            assertThrows(DeveloperException.class, () -> PersonNames.required("firstName", bad, 60), bad);
        }
    }

    @Test
    void fullJoinsWhatIsThere() {
        assertEquals("Jane Mary Doe", PersonNames.full("Jane", "Mary", "Doe"));
        assertEquals("Jane Doe", PersonNames.full("Jane", null, "Doe"));
        assertEquals("Jane", PersonNames.full("Jane", " ", null));
        assertEquals("", PersonNames.full(null, null, null));
    }

    @Test
    void splitGivesFirstMiddleAndLast() {
        PersonNames.Parts p = PersonNames.split("Jane Mary Ann Doe");
        assertEquals("Jane", p.first());
        assertEquals("Mary Ann", p.middle());
        assertEquals("Doe", p.last());
        PersonNames.Parts two = PersonNames.split("Jane Doe");
        assertEquals("Jane", two.first());
        assertNull(two.middle());
        assertEquals("Doe", two.last());
        PersonNames.Parts one = PersonNames.split("Jane");
        assertEquals("Jane", one.first());
        assertNull(one.last());
    }

    @Test
    void splitDropsCharactersThatAreNotAllowedAndCopesWithJunk() {
        PersonNames.Parts p = PersonNames.split("Jane Doe 123");
        assertEquals("Jane", p.first());
        assertEquals("Doe", p.last());
        PersonNames.Parts markup = PersonNames.split("<script>alert(1)</script>");
        assertEquals("script", markup.first());
        assertEquals("script", markup.last());
        assertNull(PersonNames.split(null).first());
        assertNull(PersonNames.split("   ").first());
        assertNull(PersonNames.split("12345 !!!").first());
    }
}

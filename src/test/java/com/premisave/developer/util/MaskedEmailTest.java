package com.premisave.developer.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** An address with most of its name hidden. */
class MaskedEmailTest {

    @Test
    void keepsTheFirstLetterAndTheDomain() {
        assertEquals("j*******@example.com", MaskedEmail.of("jane.doe@example.com"));
        assertEquals("n***@dev.test", MaskedEmail.of("new@dev.test"));
    }

    @Test
    void neverShowsMoreStarsThanEightOrFewerThanThree() {
        assertEquals("a***@x.test", MaskedEmail.of("a@x.test"));
        assertEquals("v********@x.test", MaskedEmail.of("very.long.name.indeed@x.test"));
    }

    @Test
    void trimsAndHidesTheRestOfTheName() {
        String masked = MaskedEmail.of("  secret.person@corp.test ");
        assertEquals('s', masked.charAt(0));
        assertFalse(masked.contains("ecret"));
        assertEquals("s********@corp.test", masked);
    }

    @Test
    void anythingThatIsNotAnAddressBecomesStars() {
        assertEquals("***", MaskedEmail.of(null));
        assertEquals("***", MaskedEmail.of(""));
        assertEquals("***", MaskedEmail.of("plain"));
        assertEquals("***", MaskedEmail.of("@x.test"));
        assertEquals("***", MaskedEmail.of("name@"));
    }
}

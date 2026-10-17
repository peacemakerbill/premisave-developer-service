package com.premisave.developer.util;

import com.premisave.developer.config.MailCredentialsCleaner;

import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class MailCredentialsTest {

    @Test
    void theGoogleAppPasswordAsPastedWithQuotesAndSpacesBecomesSixteenLetters() {
        assertEquals("yyyynnnnbbbbkkkk", MailCredentials.password("' yyyy nnnn bbbb kkkk'"));
        assertEquals("yyyynnnnbbbbkkkk", MailCredentials.password("\"yyyy nnnn bbbb kkkk\""));
        assertEquals("yyyynnnnbbbbkkkk", MailCredentials.password("yyyy nnnn bbbb kkkk"));
        assertEquals("yyyynnnnbbbbkkkk", MailCredentials.password("  yyyy  nnnn bbbb kkkk  "));
        assertEquals("yyyynnnnbbbbkkkk", MailCredentials.password("yyyy\u00a0nnnn\u00a0bbbb\u00a0kkkk"));
        assertEquals("yyyynnnnbbbbkkkk", MailCredentials.password("yyyynnnnbbbbkkkk"));
    }

    @Test
    void aMatchingPairOfQuotesIsRemovedOnceAndNothingElse() {
        assertEquals("secret", MailCredentials.password("'secret'"));
        assertEquals("secret", MailCredentials.password("\" secret \""));
        assertEquals("'secret'", MailCredentials.password("''secret''"));
        assertEquals("'secret\"", MailCredentials.password("'secret\""));
        assertEquals("sec'ret", MailCredentials.password("sec'ret"));
        assertEquals("'", MailCredentials.password("'"));
        assertEquals("", MailCredentials.password("''"));
    }

    @Test
    void anyOtherPasswordKeepsItsSpacesAndCharactersExactly() {
        assertEquals("my pass word 123", MailCredentials.password("my pass word 123"));
        assertEquals("Yyyy nnnn bbbb kkkk", MailCredentials.password("Yyyy nnnn bbbb kkkk"));
        assertEquals("yyyy nnnn bbbb kkkk llll", MailCredentials.password("yyyy nnnn bbbb kkkk llll"));
        assertEquals("p@ss w0rd!#$%^&*()", MailCredentials.password("p@ss w0rd!#$%^&*()"));
        assertEquals("pa\u00e9ss", MailCredentials.password("pa\u00e9ss"));
    }

    @Test
    void nullStaysNullAndTheUserNameLosesQuotesAndSpaces() {
        assertNull(MailCredentials.password(null));
        assertNull(MailCredentials.username(null));
        assertEquals("me@gmail.com", MailCredentials.username(" 'me@gmail.com' "));
        assertEquals("my name@example.com", MailCredentials.username("my name@example.com"));
    }

    @Test
    void theMailSenderIsCleanedAndOtherBeansAreLeftAlone() {
        MailCredentialsCleaner cleaner = new MailCredentialsCleaner();
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setUsername("'me@gmail.com'");
        sender.setPassword("' yyyy nnnn bbbb kkkk'");
        assertSame(sender, cleaner.postProcessBeforeInitialization(sender, "mailSender"));
        assertEquals("me@gmail.com", sender.getUsername());
        assertEquals("yyyynnnnbbbbkkkk", sender.getPassword());

        JavaMailSenderImpl clean = new JavaMailSenderImpl();
        clean.setUsername("me@gmail.com");
        clean.setPassword("plainpassword");
        cleaner.postProcessBeforeInitialization(clean, "other");
        assertEquals("plainpassword", clean.getPassword());

        JavaMailSenderImpl none = new JavaMailSenderImpl();
        cleaner.postProcessBeforeInitialization(none, "nocredentials");
        assertNull(none.getPassword());

        Object other = "not a mail sender";
        assertSame(other, cleaner.postProcessBeforeInitialization(other, "x"));
    }
}

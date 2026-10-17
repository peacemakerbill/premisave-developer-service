package com.premisave.developer.util;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailTemplatesTest {

    private static final String PORTAL = "https://portal.example.com";
    private static final String LINK = "https://portal.example.com/verify-email?token=abc_DEF-123";

    private static List<EmailTemplates.Message> all() {
        return List.of(
                EmailTemplates.verify("Jane", LINK, 24, PORTAL),
                EmailTemplates.welcome("Jane", PORTAL),
                EmailTemplates.signIn("Jane", "Email and password", "Chrome on Windows", "203.0.113.9",
                        Instant.parse("2026-10-16T08:05:00Z"), PORTAL),
                EmailTemplates.reset("Jane", LINK, 60, PORTAL),
                EmailTemplates.passwordChanged("Jane", PORTAL),
                EmailTemplates.transferCode("Jane", "123456", "move the production app \"Shop\"", "Acme Ltd", "PRM-0000000001",
                        "Olivia Tester", 15, PORTAL),
                EmailTemplates.appReceived("Jane", "Olivia", "Shop", "Acme Ltd", "PRM-0000000001", PORTAL),
                EmailTemplates.appHandedOver("Jane", "Shop", "Acme Ltd", "PRM-0000000001", "n***@dev.test", PORTAL));
    }

    @Test
    void theTransferCodeEmailShowsTheCodeWhatItIsForAndHowLongItLasts() {
        String html = all().get(5).html();
        assertTrue(html.contains(">123456<"));
        assertTrue(html.contains("move the production app &quot;Shop&quot;"));
        assertTrue(html.contains("Acme Ltd"));
        assertTrue(html.contains("PRM-0000000001"));
        assertTrue(html.contains("Olivia Tester"));
        assertTrue(html.contains("15 minutes"));
        assertTrue(all().get(5).subject().contains("123456"));
        assertFalse(html.contains("token="));
    }

    @Test
    void theTransferCodeEmailGreetsAnUnnamedOwnerGenerally() {
        String html = EmailTemplates.transferCode(null, "123456", "x", "Acme", "PRM-1", "O", 1, PORTAL).html();
        assertTrue(html.contains("Hello there"));
        assertTrue(html.contains("1 minute"));
    }

    @Test
    void theReceivedEmailSaysKeysMustBeMadeAgain() {
        String html = all().get(6).html();
        assertTrue(html.contains("Olivia"));
        assertTrue(html.contains("revoked"));
        assertTrue(html.contains("href=\"" + PORTAL + "\""));
    }

    @Test
    void theHandedOverEmailOffersToSecureTheAccount() {
        String html = all().get(7).html();
        assertTrue(html.contains("n***@dev.test"));
        assertTrue(html.contains("href=\"" + PORTAL + "/forgot-password\""));
    }

    @Test
    void everyEmailIsAFullHtmlPageWithNothingLeftToFillIn() {
        for (EmailTemplates.Message m : all()) {
            assertTrue(m.html().startsWith("<!DOCTYPE html>"), m.subject());
            assertTrue(m.html().contains("Premisave"), m.subject());
            assertTrue(m.html().contains("Hello Jane"), m.subject());
            assertFalse(m.html().contains("{{"), m.subject() + " still has a placeholder");
            assertFalse(m.html().contains("}}"), m.subject());
            assertTrue(m.html().contains("<title>" + m.subject() + "</title>"), m.subject());
            assertFalse(m.subject().isBlank());
        }
    }

    @Test
    void theConfirmationEmailCarriesTheLinkInTheButtonAndAsText() {
        String html = EmailTemplates.verify("Jane", LINK, 24, PORTAL).html();
        assertTrue(html.contains("href=\"" + LINK + "\""));
        assertEquals(2, html.split(java.util.regex.Pattern.quote(LINK), -1).length - 1);
        assertTrue(html.contains("expires in 24 hours"));
        assertTrue(EmailTemplates.verify("Jane", LINK, 1, PORTAL).html().contains("expires in 1 hour."));
    }

    @Test
    void theResetEmailSaysHowLongTheLinkLasts() {
        String html = EmailTemplates.reset("Jane", "https://portal.example.com/reset-password?token=t", 45, PORTAL).html();
        assertTrue(html.contains("expires in 45 minutes"));
        assertTrue(html.contains("href=\"https://portal.example.com/reset-password?token=t\""));
    }

    @Test
    void theSignInNoticeShowsTimeMethodDeviceAndAddressButNoSignInLink() {
        String html = all().get(2).html();
        assertTrue(html.contains("16 Oct 2026, 08:05 UTC"));
        assertTrue(html.contains("Email and password"));
        assertTrue(html.contains("Chrome on Windows"));
        assertTrue(html.contains("203.0.113.9"));
        assertTrue(html.contains("href=\"" + PORTAL + "/forgot-password\""));
        assertFalse(html.contains("token="));
    }

    @Test
    void thePasswordNoticeLinksToTheResetPage() {
        assertTrue(all().get(4).html().contains("href=\"" + PORTAL + "/forgot-password\""));
    }

    @Test
    void namesAndOtherValuesAreEscaped() {
        String html = EmailTemplates.signIn("<script>alert(1)</script> & \"Co\"", "<b>x</b>", "<i>d</i>", "<1>",
                Instant.parse("2026-10-16T08:05:00Z"), PORTAL).html();
        assertFalse(html.contains("<script>"));
        assertFalse(html.contains("<b>x</b>"));
        assertFalse(html.contains("<i>d</i>"));
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;Co&quot;"));
    }

    @Test
    void aValueThatLooksLikeAPlaceholderIsNeverExpanded() {
        String html = EmailTemplates.verify("{{link}} {{contentHtml}}", LINK, 24, PORTAL).html();
        assertTrue(html.contains("{{link}} {{contentHtml}}"));
        assertEquals(2, html.split(java.util.regex.Pattern.quote(LINK), -1).length - 1);
    }

    @Test
    void aMissingNameReadsAsThere() {
        assertTrue(EmailTemplates.welcome(null, PORTAL).html().contains("Hello there"));
        assertTrue(EmailTemplates.welcome("  ", PORTAL).html().contains("Hello there"));
    }

    @Test
    void fillLeavesUnknownPlaceholdersEmptyAndOnlyTrustsHtmlSuffixedNames() {
        assertEquals("a--b", EmailTemplates.fill("a{{nothing}}--b", Map.of()));
        assertEquals("<b>&lt;i&gt;", EmailTemplates.fill("{{boldHtml}}{{plain}}", Map.of("boldHtml", "<b>", "plain", "<i>")));
        assertEquals("$1\\2", EmailTemplates.fill("{{v}}", Map.of("v", "$1\\2")));
    }
}

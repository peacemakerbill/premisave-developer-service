package com.premisave.developer.service;

import com.premisave.developer.TestProps;
import com.premisave.developer.TestRepos;
import com.premisave.developer.enums.DeveloperStatus;
import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.exception.DeveloperException;
import com.premisave.developer.model.Developer;
import com.premisave.developer.model.EmailToken;
import com.premisave.developer.security.PasswordHasher;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountEmailServiceTest {

    private static final String PREFIX_VERIFY = "https://portal.example.com/verify-email?token=";
    private static final String PREFIX_RESET = "https://portal.example.com/reset-password?token=";

    private Instant now = Instant.parse("2026-10-16T08:00:00Z");
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };
    private final TestRepos.Developers devs = new TestRepos.Developers();
    private final TestRepos.EmailTokens tokenRepo = new TestRepos.EmailTokens();
    private final TestRepos.RecordingMailer mailer = new TestRepos.RecordingMailer();
    private final PasswordHasher hasher = new PasswordHasher(10_000);
    private final EmailTokenService tokens = new EmailTokenService(tokenRepo.repo, TestProps.mail(), TestProps.PEPPER, clock);
    private final AccountEmailService service = new AccountEmailService(devs.repo, tokens,
            new MailService(mailer, Runnable::run, false), hasher, TestProps.mail(), 10, clock);

    private Developer developer() {
        Developer d = new Developer();
        d.setDeveloperId("dev_1");
        d.setEmail("jane@example.com");
        d.setFullName("Jane <Doe>");
        d.setPasswordHash(hasher.hash("an old password 1"));
        d.setStatus(DeveloperStatus.ACTIVE);
        d.setCreatedAt(now);
        devs.store.put(d.getDeveloperId(), d);
        return d;
    }

    private static String tokenIn(String link, String prefix) {
        return link.substring(prefix.length());
    }

    // ---- welcome and sign in notice

    @Test
    void confirmingAnAddressSendsTheWelcomeEmailOnce() {
        Developer d = developer();
        service.sendVerification(d);
        String token = tokenIn(mailer.lastLink(PREFIX_VERIFY), PREFIX_VERIFY);
        mailer.sent.clear();
        service.verifyEmail(token);
        assertEquals(1, mailer.sent.size());
        assertEquals("Welcome to Premisave", mailer.sent.get(0).subject());
        assertEquals("jane@example.com", mailer.sent.get(0).to());
        assertTrue(mailer.sent.get(0).html().contains("Jane &lt;Doe&gt;"));
    }

    @Test
    void theSignInNoticeGoesToTheOwnerAndNeverFailsTheCaller() {
        Developer d = developer();
        service.sendSignInAlert(d, new com.premisave.developer.dto.ClientInfo("198.51.100.4", "curl/8.5.0"), "Google", now);
        assertEquals(1, mailer.sent.size());
        assertTrue(mailer.sent.get(0).html().contains("curl"));
        assertTrue(mailer.sent.get(0).html().contains("Google"));
        assertTrue(mailer.sent.get(0).html().contains("198.51.100.4"));
        mailer.fail = true;
        service.sendSignInAlert(d, null, "Email and password", now);
        service.sendWelcome(d);
        assertEquals(1, mailer.sent.size());
    }

    @Test
    void emailsGreetTheFirstNameWhenThereIsOne() {
        Developer d = developer();
        d.setFirstName("Janet");
        d.setLastName("Doe");
        service.sendWelcome(d);
        assertTrue(mailer.sent.get(0).html().contains("Hello Janet,"));
        assertFalse(mailer.sent.get(0).html().contains("Doe"));
    }

    // ---- confirming an address

    @Test
    void theConfirmationLinkConfirmsTheAddressOnceAndOnlyOnce() {
        Developer d = developer();
        service.sendVerification(d);
        assertEquals(1, mailer.sent.size());
        String token = tokenIn(mailer.lastLink(PREFIX_VERIFY), PREFIX_VERIFY);

        Map<String, Object> out = service.verifyEmail(token);
        assertEquals(true, out.get("emailVerified"));
        assertTrue(d.isEmailVerified());
        assertEquals(now, d.getEmailVerifiedAt());

        DeveloperException again = assertThrows(DeveloperException.class, () -> service.verifyEmail(token));
        assertEquals(ErrorCode.INVALID_TOKEN, again.code());
    }

    @Test
    void onlyAHashOfTheTokenIsStored() {
        Developer d = developer();
        service.sendVerification(d);
        String token = tokenIn(mailer.lastLink(PREFIX_VERIFY), PREFIX_VERIFY);
        EmailToken row = tokenRepo.store.values().iterator().next();
        assertFalse(row.getTokenHash().contains(token));
        assertFalse(row.getId().contains(token));
        assertEquals(now.plusSeconds(24 * 3600), row.getExpiresAt());
    }

    @Test
    void anExpiredOrUnknownOrWrongKindOfLinkIsRefused() {
        Developer d = developer();
        service.sendVerification(d);
        String token = tokenIn(mailer.lastLink(PREFIX_VERIFY), PREFIX_VERIFY);

        assertEquals(ErrorCode.INVALID_TOKEN, assertThrows(DeveloperException.class, () -> service.verifyEmail("nonsense")).code());
        assertEquals(ErrorCode.INVALID_TOKEN, assertThrows(DeveloperException.class, () -> service.verifyEmail("")).code());
        assertEquals(ErrorCode.INVALID_TOKEN, assertThrows(DeveloperException.class, () -> service.verifyEmail(null)).code());
        // A confirmation link cannot be used as a reset link.
        assertEquals(ErrorCode.INVALID_TOKEN,
                assertThrows(DeveloperException.class, () -> service.resetPassword(token, "a brand new password 2")).code());
        assertEquals(1, tokenRepo.store.size());

        now = now.plusSeconds(24 * 3600 + 1);
        assertEquals(ErrorCode.INVALID_TOKEN, assertThrows(DeveloperException.class, () -> service.verifyEmail(token)).code());
        assertFalse(d.isEmailVerified());
    }

    @Test
    void aLinkSentToAnOldAddressDoesNotConfirmANewOne() {
        Developer d = developer();
        service.sendVerification(d);
        String token = tokenIn(mailer.lastLink(PREFIX_VERIFY), PREFIX_VERIFY);
        d.setEmail("someone.else@example.com");
        assertEquals(ErrorCode.INVALID_TOKEN, assertThrows(DeveloperException.class, () -> service.verifyEmail(token)).code());
        assertFalse(d.isEmailVerified());
    }

    @Test
    void nothingIsSentToAnAddressThatIsAlreadyConfirmed() {
        Developer d = developer();
        d.setEmailVerified(true);
        service.sendVerification(d);
        assertTrue(mailer.sent.isEmpty());
    }

    // ---- asking for another link

    @Test
    void aSignedInDeveloperCanAskForAnotherLinkAfterTheCooldown() {
        Developer d = developer();
        service.sendVerification(d);
        DeveloperException soon = assertThrows(DeveloperException.class, () -> service.resendVerification(d));
        assertEquals(ErrorCode.RATE_LIMITED, soon.code());
        assertEquals(60L, soon.extra().get("retryAfterSeconds"));
        assertEquals(1, mailer.sent.size());

        now = now.plusSeconds(61);
        assertEquals(true, service.resendVerification(d).get("sent"));
        assertEquals(2, mailer.sent.size());
    }

    @Test
    void aConfirmedDeveloperIsToldThereIsNothingToDo() {
        Developer d = developer();
        d.setEmailVerified(true);
        assertEquals(ErrorCode.INVALID_STATE, assertThrows(DeveloperException.class, () -> service.resendVerification(d)).code());
    }

    @Test
    void theHourlyCapStopsALongRunOfEmails() {
        Developer d = developer();
        for (int i = 0; i < 5; i++) {
            service.sendVerification(d);
            now = now.plusSeconds(61);
        }
        assertEquals(5, mailer.sent.size());
        DeveloperException e = assertThrows(DeveloperException.class, () -> service.resendVerification(d));
        assertEquals(ErrorCode.RATE_LIMITED, e.code());
        assertEquals(5, mailer.sent.size());
        now = now.plusSeconds(3600);
        service.resendVerification(d);
        assertEquals(6, mailer.sent.size());
    }

    @Test
    void theOpenResendEntryGivesNothingAwayAboutWhoHasAnAccount() {
        Developer d = developer();
        service.resendVerification("nobody@example.com");
        service.resendVerification("  ");
        service.resendVerification((String) null);
        assertTrue(mailer.sent.isEmpty());
        service.resendVerification("JANE@example.com");
        assertEquals(1, mailer.sent.size());
        service.resendVerification("jane@example.com");
        assertEquals(1, mailer.sent.size());
        d.setStatus(DeveloperStatus.DISABLED);
        now = now.plusSeconds(120);
        service.resendVerification("jane@example.com");
        assertEquals(1, mailer.sent.size());
    }

    // ---- forgotten password

    @Test
    void aResetLinkIsSentOnlyToAnActiveAccountAndTheNameIsEscaped() {
        Developer d = developer();
        service.forgotPassword("nobody@example.com");
        assertTrue(mailer.sent.isEmpty());
        service.forgotPassword(" Jane@Example.com ");
        assertEquals(1, mailer.sent.size());
        assertTrue(mailer.sent.get(0).html().contains("Jane &lt;Doe&gt;"));
        assertFalse(mailer.sent.get(0).html().contains("Jane <Doe>"));
        assertNotNull(mailer.lastLink(PREFIX_RESET));

        now = now.plusSeconds(61);
        d.setStatus(DeveloperStatus.DISABLED);
        service.forgotPassword("jane@example.com");
        assertEquals(1, mailer.sent.size());
    }

    @Test
    void askingTwiceInAMinuteSendsOneEmailAndStillAnswersTheSame() {
        developer();
        service.forgotPassword("jane@example.com");
        service.forgotPassword("jane@example.com");
        assertEquals(1, mailer.sent.size());
    }

    @Test
    void aFailingMailServerDoesNotRevealOrBreakAnything() {
        developer();
        mailer.fail = true;
        service.forgotPassword("jane@example.com");
        service.forgotPassword("nobody@example.com");
        assertTrue(mailer.sent.isEmpty());
    }

    @Test
    void resettingThePasswordWorksOnceAndEndsEverythingElse() {
        Developer d = developer();
        d.setFailedLogins(3);
        d.setLockedUntil(now.plusSeconds(600));
        int version = d.getTokenVersion();
        service.forgotPassword("jane@example.com");
        String token = tokenIn(mailer.lastLink(PREFIX_RESET), PREFIX_RESET);
        mailer.sent.clear();

        service.resetPassword(token, "a brand new password 2");

        assertTrue(hasher.verify("a brand new password 2", d.getPasswordHash()));
        assertFalse(hasher.verify("an old password 1", d.getPasswordHash()));
        assertEquals(version + 1, d.getTokenVersion());
        assertEquals(0, d.getFailedLogins());
        assertNull(d.getLockedUntil());
        assertTrue(d.isEmailVerified());
        assertEquals(1, mailer.sent.size());
        assertTrue(mailer.sent.get(0).subject().contains("password was changed"));
        assertEquals(ErrorCode.INVALID_TOKEN,
                assertThrows(DeveloperException.class, () -> service.resetPassword(token, "yet another password 3")).code());
    }

    @Test
    void confirmingTheEmailActivatesAPendingAccountButNeverALockedOutOne() {
        Developer pending = developer();
        pending.setStatus(DeveloperStatus.PENDING);
        service.sendVerification(pending);
        service.verifyEmail(tokenIn(mailer.lastLink(PREFIX_VERIFY), PREFIX_VERIFY));
        assertEquals(DeveloperStatus.ACTIVE, pending.getStatus());
        assertTrue(pending.isEmailVerified());

        Developer disabled = new Developer();
        disabled.setStatus(DeveloperStatus.DISABLED);
        disabled.confirmEmail(now);
        assertEquals(DeveloperStatus.DISABLED, disabled.getStatus());
        assertTrue(disabled.isEmailVerified());
    }

    @Test
    void resettingThePasswordFromTheEmailLinkActivatesAPendingAccount() {
        Developer d = developer();
        d.setStatus(DeveloperStatus.PENDING);
        service.forgotPassword("jane@example.com");
        service.resetPassword(tokenIn(mailer.lastLink(PREFIX_RESET), PREFIX_RESET), "a brand new password 2");
        assertEquals(DeveloperStatus.ACTIVE, d.getStatus());
        assertTrue(d.isEmailVerified());
    }

    @Test
    void aWeakPasswordDoesNotUseUpTheLink() {
        developer();
        service.forgotPassword("jane@example.com");
        String token = tokenIn(mailer.lastLink(PREFIX_RESET), PREFIX_RESET);
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(DeveloperException.class, () -> service.resetPassword(token, "short")).code());
        service.resetPassword(token, "a brand new password 2");
    }

    @Test
    void aNewResetLinkEndsTheOlderOnesWhenAPasswordIsSet() {
        Developer d = developer();
        service.forgotPassword("jane@example.com");
        String first = tokenIn(mailer.lastLink(PREFIX_RESET), PREFIX_RESET);
        now = now.plusSeconds(61);
        service.forgotPassword("jane@example.com");
        String second = tokenIn(mailer.lastLink(PREFIX_RESET), PREFIX_RESET);
        service.resetPassword(second, "a brand new password 2");
        assertEquals(ErrorCode.INVALID_TOKEN,
                assertThrows(DeveloperException.class, () -> service.resetPassword(first, "one more password 4")).code());
        assertTrue(hasher.verify("a brand new password 2", d.getPasswordHash()));
    }

    @Test
    void anExpiredResetLinkIsRefused() {
        developer();
        service.forgotPassword("jane@example.com");
        String token = tokenIn(mailer.lastLink(PREFIX_RESET), PREFIX_RESET);
        now = now.plusSeconds(3600 + 1);
        assertEquals(ErrorCode.INVALID_TOKEN,
                assertThrows(DeveloperException.class, () -> service.resetPassword(token, "a brand new password 2")).code());
    }

    @Test
    void aResetCannotReviveADisabledAccount() {
        Developer d = developer();
        service.forgotPassword("jane@example.com");
        String token = tokenIn(mailer.lastLink(PREFIX_RESET), PREFIX_RESET);
        d.setStatus(DeveloperStatus.DISABLED);
        assertEquals(ErrorCode.ACCOUNT_DISABLED,
                assertThrows(DeveloperException.class, () -> service.resetPassword(token, "a brand new password 2")).code());
    }

    @Test
    void anAccountThatNeverHadAPasswordCanGetOneThroughAReset() {
        Developer d = developer();
        d.setPasswordHash(null);
        service.forgotPassword("jane@example.com");
        String token = tokenIn(mailer.lastLink(PREFIX_RESET), PREFIX_RESET);
        service.resetPassword(token, "a brand new password 2");
        assertTrue(hasher.verify("a brand new password 2", d.getPasswordHash()));
    }

    @Test
    void theMailServiceWritesTheLinkToTheLogOnlyInLocalMode() {
        MailService quiet = new MailService(null, Runnable::run, false);
        MailService local = new MailService(null, Runnable::run, true);
        assertFalse(quiet.canSend());
        quiet.send("a@b.com", "s", "<p>x</p>", "https://portal.example.com/x");
        local.send("a@b.com", "s", "<p>x</p>", "https://portal.example.com/x");
    }
}

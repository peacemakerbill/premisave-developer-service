package com.premisave.developer.service;

import com.premisave.developer.TestProps;
import com.premisave.developer.client.Mailer;

import jakarta.mail.MessagingException;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MailStartupCheckTest {

    /** A mail sender that records what was sent and can be told to refuse the login or the send. */
    static class FakeSender extends JavaMailSenderImpl {
        boolean refuseLogin;
        RuntimeException sendFailure;
        int logins;
        /** One email handed to the mail server. */
        record Sent(String from, String to, String subject, String html) {}

        final List<Sent> sent = new ArrayList<>();

        FakeSender() {
            setHost("smtp.gmail.com");
            setPort(587);
            setUsername("me@gmail.com");
            setPassword("yyyynnnnbbbbkkkk");
        }

        @Override
        public void testConnection() throws MessagingException {
            logins++;
            if (refuseLogin) throw new MessagingException("535-5.7.8 Username and Password not accepted");
        }

        /** The Mailer the check uses, recording instead of sending. */
        Mailer mailerFor(String from) {
            return (to, subject, html) -> {
                if (sendFailure != null) throw sendFailure;
                sent.add(new Sent(from, to, subject, html));
            };
        }
    }

    private static MailStartupCheck check(FakeSender sender, String sendTo) {
        return new MailStartupCheck(Optional.ofNullable(sender), TestProps.with("http://localhost:8084", null), sendTo,
                sender == null ? null : sender::mailerFor);
    }

    @Test
    void withNoMailServerItSaysMailIsOffAndSendsNothing() {
        MailStartupCheck.Result r = check(null, "me@gmail.com").run();
        assertEquals(MailStartupCheck.Outcome.OFF, r.outcome());
        assertTrue(r.message().contains("MAIL_USERNAME"));
    }

    @Test
    void aMailSenderWithNoUsernameCountsAsNoMailServer() {
        FakeSender noUser = new FakeSender();
        noUser.setUsername("");
        MailStartupCheck.Result r = check(noUser, "me@gmail.com").run();
        assertEquals(MailStartupCheck.Outcome.OFF, r.outcome());
        assertTrue(noUser.sent.isEmpty());
        assertEquals(0, noUser.logins);
    }

    @Test
    void aMailSenderWithABlankHostCountsAsNoMailServer() {
        FakeSender blank = new FakeSender();
        blank.setHost("  ");
        MailStartupCheck.Result r = check(blank, "me@gmail.com").run();
        assertEquals(MailStartupCheck.Outcome.OFF, r.outcome());
        assertTrue(blank.sent.isEmpty());
        assertEquals(0, blank.logins);
    }

    @Test
    void aWorkingLoginWithNoAddressIsReadyAndSendsNothing() {
        FakeSender sender = new FakeSender();
        MailStartupCheck.Result r = check(sender, "").run();
        assertEquals(MailStartupCheck.Outcome.READY, r.outcome());
        assertEquals(1, sender.logins);
        assertTrue(sender.sent.isEmpty());
    }

    @Test
    void aBlankAddressCountsAsNoAddress() {
        FakeSender sender = new FakeSender();
        assertEquals(MailStartupCheck.Outcome.READY, check(sender, "   ").run().outcome());
        assertEquals(MailStartupCheck.Outcome.READY, check(sender, null).run().outcome());
        assertTrue(sender.sent.isEmpty());
    }

    @Test
    void anAddressSendsOneTestEmailFromTheMailUser() {
        FakeSender sender = new FakeSender();
        MailStartupCheck.Result r = check(sender, " tester@example.com ").run();
        assertEquals(MailStartupCheck.Outcome.TEST_SENT, r.outcome());
        assertEquals(1, sender.sent.size());
        FakeSender.Sent m = sender.sent.get(0);
        assertEquals("tester@example.com", m.to());
        assertEquals("me@gmail.com", m.from());
        assertEquals("Premisave developer service: mail check", m.subject());
        assertTrue(r.message().contains("tester@example.com"));
    }

    @Test
    void theTestEmailIsADesignedHtmlPageThatShowsWhereItWentAndNeverThePassword() {
        FakeSender sender = new FakeSender();
        check(sender, "tester@example.com").run();
        String html = sender.sent.get(0).html();
        assertTrue(html.startsWith("<!DOCTYPE html") || html.startsWith("<!doctype html") || html.contains("<html"));
        assertTrue(html.contains("Email is working"));
        assertTrue(html.contains("tester@example.com"));
        assertTrue(html.contains("me@gmail.com"));
        assertTrue(html.contains("smtp.gmail.com:587"));
        assertTrue(!html.contains("yyyynnnnbbbbkkkk"));
        assertTrue(!html.contains("{{"));
    }

    @Test
    void aRefusedLoginIsReportedWithAGmailHintAndNothingIsSent() {
        FakeSender sender = new FakeSender();
        sender.refuseLogin = true;
        MailStartupCheck.Result r = check(sender, "me@gmail.com").run();
        assertEquals(MailStartupCheck.Outcome.LOGIN_REFUSED, r.outcome());
        assertTrue(r.message().contains("App Password"));
        assertTrue(sender.sent.isEmpty());
    }

    @Test
    void aFailedSendIsReportedAndDoesNotThrow() {
        FakeSender sender = new FakeSender();
        sender.sendFailure = new IllegalStateException("Mail server connection failed");
        MailStartupCheck.Result r = check(sender, "me@gmail.com").run();
        assertEquals(MailStartupCheck.Outcome.TEST_FAILED, r.outcome());
        assertTrue(r.message().contains("Mail server connection failed"));
    }

    @Test
    void somethingThatIsNotAnEmailAddressIsIgnored() {
        FakeSender sender = new FakeSender();
        MailStartupCheck.Result r = check(sender, "not-an-address").run();
        assertEquals(MailStartupCheck.Outcome.READY, r.outcome());
        assertTrue(r.message().contains("not an email address"));
        assertTrue(sender.sent.isEmpty());
    }

    @Test
    void thePasswordIsNeverInTheReport() {
        FakeSender sender = new FakeSender();
        assertTrue(!check(sender, "me@gmail.com").run().message().contains("yyyynnnnbbbbkkkk"));
        sender.refuseLogin = true;
        assertTrue(!check(sender, "me@gmail.com").run().message().contains("yyyynnnnbbbbkkkk"));
    }

    @Test
    void whenTheMailFromAddressIsSetItIsUsedAsTheSender() {
        FakeSender sender = new FakeSender();
        com.premisave.developer.config.DeveloperProperties base = TestProps.with("http://localhost:8084", null);
        com.premisave.developer.config.DeveloperProperties.Mail mail = new com.premisave.developer.config.DeveloperProperties.Mail(
                "noreply@premisave.com", "https://portal.example.com", null, null, 60, 5, true, false, true);
        com.premisave.developer.config.DeveloperProperties props = new com.premisave.developer.config.DeveloperProperties(
                base.jwt(), base.keys(), base.wallet(), base.proxy(), base.apps(), base.businesses(), base.security(), base.internal(), base.cors(),
                mail, base.oauth(), base.cloudinary());
        new MailStartupCheck(Optional.of(sender), props, "me@gmail.com", sender::mailerFor).run();
        assertEquals("noreply@premisave.com", sender.sent.get(0).from());
    }

    // ---- what is wrong with .env when mail is off

    private static String diagnoseFile(String content) throws Exception {
        Path file = Files.createTempFile("env-check", ".env");
        try {
            Files.writeString(file, content);
            return MailStartupCheck.diagnose(file);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void aMissingEnvFileIsNamedWithItsFullPath() {
        Path missing = Path.of("definitely-not-here", ".env");
        String d = MailStartupCheck.diagnose(missing);
        assertTrue(d.contains("No .env file was found"));
        assertTrue(d.contains(missing.toAbsolutePath().toString()));
    }

    @Test
    void anEnvFileWithoutAHostLineSaysSo() throws Exception {
        assertTrue(diagnoseFile("SERVER_PORT=8091\nMAIL_HOST=smtp.gmail.com\n").contains("has no MAIL_USERNAME line"));
    }

    @Test
    void aCommentedHostLineIsFound() throws Exception {
        assertTrue(diagnoseFile("# MAIL_USERNAME=me@gmail.com\n").contains("MAIL_USERNAME line in .env starts with #"));
        assertTrue(diagnoseFile("#spring.mail.username=me@gmail.com\n").contains("starts with #"));
    }

    @Test
    void everySpellingOfTheUsernameCountsAsAUsernameLine() throws Exception {
        for (String line : new String[] {"MAIL_USERNAME=me@gmail.com", "spring.mail.username=me@gmail.com"}) {
            assertTrue(diagnoseFile(line + "\n").contains("has a mail username line with a value"), line);
        }
    }

    @Test
    void theOldSpringNameIsToldToRename() throws Exception {
        String d = diagnoseFile("SPRING_MAIL_HOST=smtp.gmail.com\nSPRING_MAIL_USERNAME=me@gmail.com\n");
        assertTrue(d.contains("SPRING_MAIL_USERNAME is not read. Rename it to MAIL_USERNAME"));
        assertTrue(!d.contains("me@gmail.com"));
    }

    @Test
    void anEmptyHostValueIsFound() throws Exception {
        assertTrue(diagnoseFile("MAIL_USERNAME=\n").contains("MAIL_USERNAME in .env has no value"));
        assertTrue(diagnoseFile("MAIL_USERNAME=''\n").contains("has no value"));
        assertTrue(diagnoseFile("spring.mail.username=\n").contains("has no value"));
    }

    @Test
    void aGoodHostLineIsNotBlamedAndNoValueIsReported() throws Exception {
        String d = diagnoseFile("MAIL_USERNAME=secret.user@example.com\nMAIL_PASSWORD=topsecret\n");
        assertTrue(d.contains("has a mail username line with a value"));
        assertTrue(!d.contains("secret.user@example.com"));
        assertTrue(!d.contains("topsecret"));
    }
}
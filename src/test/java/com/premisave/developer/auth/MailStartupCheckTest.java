package com.premisave.developer.auth;

import com.premisave.developer.TestProps;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

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
        final List<SimpleMailMessage> sent = new ArrayList<>();

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

        @Override
        public void send(SimpleMailMessage message) {
            if (sendFailure != null) throw sendFailure;
            sent.add(message);
        }
    }

    private static MailStartupCheck check(FakeSender sender, String sendTo) {
        return new MailStartupCheck(Optional.ofNullable(sender), TestProps.with("http://localhost:8084", null), sendTo);
    }

    @Test
    void withNoMailServerItSaysMailIsOffAndSendsNothing() {
        MailStartupCheck.Result r = check(null, "me@gmail.com").run();
        assertEquals(MailStartupCheck.Outcome.OFF, r.outcome());
        assertTrue(r.message().contains("spring.mail.host"));
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
        MailStartupCheck.Result r = check(sender, " grahambill011@gmail.com ").run();
        assertEquals(MailStartupCheck.Outcome.TEST_SENT, r.outcome());
        assertEquals(1, sender.sent.size());
        SimpleMailMessage m = sender.sent.get(0);
        assertEquals("grahambill011@gmail.com", m.getTo()[0]);
        assertEquals("me@gmail.com", m.getFrom());
        assertEquals("Premisave developer service: mail check", m.getSubject());
        assertTrue(r.message().contains("grahambill011@gmail.com"));
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
                base.jwt(), base.keys(), base.wallet(), base.proxy(), base.apps(), base.security(), base.internal(), base.cors(),
                mail, base.oauth());
        new MailStartupCheck(Optional.of(sender), props, "me@gmail.com").run();
        assertEquals("noreply@premisave.com", sender.sent.get(0).getFrom());
    }
}
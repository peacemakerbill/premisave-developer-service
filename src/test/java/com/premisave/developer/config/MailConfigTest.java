package com.premisave.developer.config;

import com.premisave.developer.TestProps;
import com.premisave.developer.service.MailService;

import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MailConfigTest {

    private static MailService serviceFor(JavaMailSenderImpl sender) {
        return new MailConfig().mailService(Optional.ofNullable(sender), TestProps.with("http://localhost:8084", null));
    }

    private static JavaMailSenderImpl sender(String host, String username) {
        JavaMailSenderImpl s = new JavaMailSenderImpl();
        s.setHost(host);
        s.setUsername(username);
        return s;
    }

    @Test
    void noMailSenderMeansNothingIsSent() {
        assertFalse(serviceFor(null).canSend());
    }

    @Test
    void theGmailDefaultHostWithoutAUsernameIsNotAConfiguredServer() {
        assertFalse(serviceFor(sender("smtp.gmail.com", null)).canSend());
        assertFalse(serviceFor(sender("smtp.gmail.com", "")).canSend());
        assertFalse(serviceFor(sender("smtp.gmail.com", "   ")).canSend());
    }

    @Test
    void aBlankHostIsNotAConfiguredServer() {
        assertFalse(serviceFor(sender("", "me@gmail.com")).canSend());
        assertFalse(serviceFor(sender("  ", "me@gmail.com")).canSend());
        assertFalse(serviceFor(sender(null, "me@gmail.com")).canSend());
    }

    @Test
    void aHostAndAUsernameSendMail() {
        assertTrue(serviceFor(sender("smtp.gmail.com", "me@gmail.com")).canSend());
    }
}

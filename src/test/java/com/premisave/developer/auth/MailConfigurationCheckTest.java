package com.premisave.developer.auth;

import com.premisave.developer.config.DeveloperProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks, against the real configuration, that email is switched on in this service and that the mail server accepts
 * the login. It starts the whole application, so it reads your .env exactly as the service does, and it needs MongoDB
 * running as for a normal start. It sends nothing unless you ask it to.
 *
 * It is skipped in a normal build. Run it on purpose:
 *
 *   mvn test -Dtest=MailConfigurationCheckTest -Dmail.check=true
 *   mvn test -Dtest=MailConfigurationCheckTest -Dmail.check=true -Dmail.check.send-to=you@example.com
 *
 * In IntelliJ, run the class with the VM option -Dmail.check=true (add -Dmail.check.send-to=... to send a message).
 * Run it from the project folder, because that is where .env is read from.
 *
 * Nothing secret is printed: the password is reported only as a length.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfSystemProperty(named = "mail.check", matches = "true")
class MailConfigurationCheckTest {

    private static final String NOT_CONFIGURED = "Mail is OFF: no mail username is set. Put MAIL_USERNAME=you@gmail.com "
            + "and MAIL_PASSWORD=your App Password (and MAIL_HOST if it is not Gmail) in the .env file in the project "
            + "folder, with no # in front, then run again.";

    @Autowired(required = false)
    private JavaMailSender sender;

    @Autowired
    private MailService mailService;

    @Autowired
    private DeveloperProperties props;

    @Test
    void mailIsConfiguredAndActive() {
        JavaMailSenderImpl mail = withHost();
        assertTrue(mailService.canSend(), "A mail host is set but the mail service did not pick it up");
        assertTrue(mail.getPort() > 0, "The mail port is not set (Gmail uses 587)");
                assertTrue(notBlank(mail.getPassword()), "The mail password is empty. Use the 16 character Google App Password in MAIL_PASSWORD");

        String from = props.mail().from();
        System.out.println("Mail is ACTIVE");
        System.out.println("  host:      " + mail.getHost() + ":" + mail.getPort());
        System.out.println("  username:  " + mail.getUsername());
        System.out.println("  password:  " + mail.getPassword().length() + " characters");
        System.out.println("  from:      " + (from == null ? "(not set, the mail server decides)" : from));
        System.out.println("  portal:    " + props.mail().portalBaseUrl());
        if (from != null && !from.equalsIgnoreCase(mail.getUsername())) {
            System.out.println("  note:      Gmail sends as the signed in account, not as " + from);
        }
    }

    /** Opens a connection and logs in, without sending anything. A wrong App Password fails here. */
    @Test
    void theMailServerAcceptsTheLogin() throws Exception {
        withHost().testConnection();
        System.out.println("The mail server accepted the login");
    }

    /** Sends one real message to the address in -Dmail.check.send-to. */
    @Test
    @EnabledIfSystemProperty(named = "mail.check.send-to", matches = ".+@.+")
    void sendsATestEmail() {
        JavaMailSenderImpl mail = withHost();
        String to = System.getProperty("mail.check.send-to").trim();
        String from = props.mail().from() == null ? mail.getUsername() : props.mail().from();

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject("Premisave developer service: mail check");
        message.setText("If you can read this, email is working in the Premisave developer service.");
        mail.send(message);
        System.out.println("Test email sent to " + to + ". Check the inbox and the spam folder.");
    }

    /** The standard mail sender, after checking that it has a host and a username. Spring builds one even when they are empty. */
    private JavaMailSenderImpl withHost() {
        assertNotNull(sender, NOT_CONFIGURED);
        assertTrue(sender instanceof JavaMailSenderImpl, "The mail sender is not the standard Spring one");
        JavaMailSenderImpl mail = (JavaMailSenderImpl) sender;
        assertTrue(notBlank(mail.getHost()) && notBlank(mail.getUsername()), NOT_CONFIGURED);
        return mail;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
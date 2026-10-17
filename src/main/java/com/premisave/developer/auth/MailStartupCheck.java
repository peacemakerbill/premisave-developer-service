package com.premisave.developer.auth;

import com.premisave.developer.config.DeveloperProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Checks the mail settings once the service has started and writes the result to the log, so a wrong setting shows
 * up at startup and not when the first developer signs up. It is off unless developer.mail.startup-check.enabled is
 * true. It reports whether mail is on, logs in to the mail server without sending anything, and, when
 * developer.mail.startup-check.send-test-to holds an address, sends one test email there.
 *
 * It runs on its own thread, so a slow or unreachable mail server never delays startup, and nothing it finds stops
 * the service from starting. The password is never logged, only its length.
 */
@Component("developerMailStartupCheck")
@ConditionalOnProperty(name = "developer.mail.startup-check.enabled", havingValue = "true")
public class MailStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(MailStartupCheck.class);

    public enum Outcome { OFF, LOGIN_REFUSED, READY, TEST_SENT, TEST_FAILED }

    public record Result(Outcome outcome, String message) {}

    private final JavaMailSender sender;
    private final DeveloperProperties props;
    private final String sendTestTo;

    public MailStartupCheck(Optional<JavaMailSender> sender, DeveloperProperties props,
                            @Value("${developer.mail.startup-check.send-test-to:}") String sendTestTo) {
        this.sender = sender.orElse(null);
        this.props = props;
        this.sendTestTo = sendTestTo == null ? "" : sendTestTo.trim();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        Thread thread = new Thread(() -> {
            try {
                run();
            } catch (RuntimeException e) {
                log.error("Mail check: the check itself failed: {}", e.toString());
            }
        }, "developer-mail-check");
        thread.setDaemon(true);
        thread.start();
    }

    /** Runs the check, logs what it finds and returns it. */
    public Result run() {
        if (sender == null) {
            return report(Outcome.OFF, "Mail check: mail is OFF. Spring has no spring.mail.host, so no email will be sent. "
                    + "Put spring.mail.host=smtp.gmail.com in the .env file (or SPRING_MAIL_HOST in the environment) and restart.");
        }
        String username = null;
        if (sender instanceof JavaMailSenderImpl mail) {
            username = mail.getUsername();
            log.info("Mail check: mail is ON. Server {}:{}, user {}, password {} characters", mail.getHost(), mail.getPort(),
                    username, mail.getPassword() == null ? 0 : mail.getPassword().length());
            try {
                mail.testConnection();
                log.info("Mail check: the mail server accepted the login");
            } catch (Exception e) {
                return report(Outcome.LOGIN_REFUSED, "Mail check: the mail server did not accept the login: " + e + hint(e));
            }
        } else {
            log.info("Mail check: mail is ON with a custom mail sender, so the login was not tested");
        }

        if (sendTestTo.isEmpty()) {
            return report(Outcome.READY, "Mail check: mail is ready. No test email was asked for "
                    + "(developer.mail.startup-check.send-test-to is empty)");
        }
        if (!sendTestTo.contains("@")) {
            return report(Outcome.READY, "Mail check: mail is ready, but '" + sendTestTo
                    + "' is not an email address, so no test email was sent");
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            String from = props.mail().from() == null ? username : props.mail().from();
            if (from != null) message.setFrom(from);
            message.setTo(sendTestTo);
            message.setSubject("Premisave developer service: mail check");
            message.setText("If you can read this, email is working in the Premisave developer service.\n\n"
                    + "This message is sent each time the service starts while developer.mail.startup-check.send-test-to is set. "
                    + "Clear that setting to stop it.");
            sender.send(message);
            return report(Outcome.TEST_SENT, "Mail check: test email sent to " + sendTestTo + ". Check the inbox and the spam folder");
        } catch (RuntimeException e) {
            return report(Outcome.TEST_FAILED, "Mail check: the test email to " + sendTestTo + " could not be sent: " + e);
        }
    }

    private static String hint(Exception e) {
        String text = String.valueOf(e);
        if (text.contains("535") || text.contains("Username and Password not accepted") || text.contains("AuthenticationFailed")) {
            return ". For Gmail use the full address as spring.mail.username and a 16 character App Password "
                    + "(2 step verification must be on) as spring.mail.password, not the account password";
        }
        return "";
    }

    private Result report(Outcome outcome, String message) {
        switch (outcome) {
            case READY, TEST_SENT -> log.info(message);
            case OFF -> log.warn(message);
            default -> log.error(message);
        }
        return new Result(outcome, message);
    }
}
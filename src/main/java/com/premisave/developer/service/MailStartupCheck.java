package com.premisave.developer.service;

import com.premisave.developer.client.Mailer;
import com.premisave.developer.client.SmtpMailer;
import com.premisave.developer.config.DeveloperProperties;
import com.premisave.developer.config.MailConfig;
import com.premisave.developer.util.EmailTemplates;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

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

    private final Function<String, Mailer> mailers;

    @Autowired
    public MailStartupCheck(Optional<JavaMailSender> sender, DeveloperProperties props,
                            @Value("${developer.mail.startup-check.send-test-to:}") String sendTestTo) {
        this(sender, props, sendTestTo, null);
    }

    /** mailers makes the Mailer for a given sender address; null means the real mail server. Tests pass their own. */
    MailStartupCheck(Optional<JavaMailSender> sender, DeveloperProperties props, String sendTestTo,
                     Function<String, Mailer> mailers) {
        this.sender = sender.filter(MailConfig::isConfigured).orElse(null);
        this.props = props;
        this.sendTestTo = sendTestTo == null ? "" : sendTestTo.trim();
        this.mailers = mailers != null ? mailers : from -> new SmtpMailer(this.sender, from);
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
            return report(Outcome.OFF, "Mail check: mail is OFF. No mail username (or host) is set, so no email will be sent. "
                    + "Put MAIL_USERNAME, MAIL_PASSWORD and, if not Gmail, MAIL_HOST in the .env file and restart. "
                    + diagnose(Path.of(".env")));
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
            String from = props.mail().from() == null || props.mail().from().isBlank() ? username : props.mail().from();
            String server = sender instanceof JavaMailSenderImpl mail ? mail.getHost() + ":" + mail.getPort() : "a custom mail sender";
            EmailTemplates.Message message = EmailTemplates.mailCheck(sendTestTo, from, server, Instant.now(), props.mail().portalBaseUrl());
            mailers.apply(from).send(sendTestTo, message.subject(), message.html());
            return report(Outcome.TEST_SENT, "Mail check: test email sent to " + sendTestTo + ". Check the inbox and the spam folder");
        } catch (Exception e) {
            return report(Outcome.TEST_FAILED, "Mail check: the test email to " + sendTestTo + " could not be sent: " + e);
        }
    }

    /** The spellings of the mail username setting that the service reads, lower case. */
    private static final List<String> USER_KEYS = List.of("mail_username", "spring.mail.username");

    /**
     * Says what is wrong with the .env file when mail is off: no such file in the folder the service started from, no
     * mail username line (MAIL_USERNAME or spring.mail.username), a line commented out with #, an empty value, or the old
     * SPRING_MAIL_USERNAME name, which is no longer read.
     * Only key names are looked at, no value is ever reported.
     */
    static String diagnose(Path file) {
        Path shown = file.toAbsolutePath();
        if (!Files.isRegularFile(file)) {
            return "No .env file was found at " + shown + ". Start the service from the folder that holds .env.";
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException | RuntimeException e) {
            return "The .env file at " + shown + " could not be read: " + e.getClass().getSimpleName();
        }
        String commented = null;
        String emptyValue = null;
        boolean good = false;
        boolean oldName = false;
        for (String raw : lines) {
            String line = raw.trim();
            boolean hash = line.startsWith("#");
            String body = hash ? line.substring(1).trim() : line;
            int eq = body.indexOf('=');
            if (eq < 0) continue;
            String key = body.substring(0, eq).trim();
            String value = body.substring(eq + 1).trim();
            if (!hash && key.equalsIgnoreCase("SPRING_MAIL_USERNAME")) oldName = true;
            if (!USER_KEYS.contains(key.toLowerCase(Locale.ROOT))) continue;
            if (hash) commented = "the " + key + " line in .env starts with #, remove the #";
            else if (value.isEmpty() || value.equals("''") || value.equals("\"\"")) emptyValue = key + " in .env has no value";
            else good = true;
        }
        if (good) {
            return "The .env file at " + shown + " has a mail username line with a value, so check the key name for a typo "
                    + "or a stray character, and that this is the .env of the folder the service started from.";
        }
        if (oldName) {
            return "In " + shown + ": SPRING_MAIL_USERNAME is not read. Rename it to MAIL_USERNAME, and SPRING_MAIL_PASSWORD, "
                    + "SPRING_MAIL_HOST and SPRING_MAIL_PORT to MAIL_PASSWORD, MAIL_HOST and MAIL_PORT.";
        }
        String why = emptyValue != null ? emptyValue : commented != null ? commented : "the .env file has no MAIL_USERNAME line";
        return "In " + shown + ": " + why + ".";
    }

    private static String hint(Exception e) {
        String text = String.valueOf(e);
        if (text.contains("535") || text.contains("Username and Password not accepted") || text.contains("AuthenticationFailed")) {
            return ". For Gmail use the full address as the mail username and a 16 character App Password "
                    + "(2 step verification must be on) as the mail password, not the account password";
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
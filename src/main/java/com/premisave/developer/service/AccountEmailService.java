package com.premisave.developer.service;

import com.premisave.developer.config.DeveloperProperties;
import com.premisave.developer.dto.ClientInfo;
import com.premisave.developer.enums.DeveloperStatus;
import com.premisave.developer.enums.EmailTokenType;
import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.exception.DeveloperException;
import com.premisave.developer.model.Developer;
import com.premisave.developer.model.EmailToken;
import com.premisave.developer.repository.DeveloperRepository;
import com.premisave.developer.security.PasswordHasher;
import com.premisave.developer.util.EmailTemplates;
import com.premisave.developer.util.PasswordPolicy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Everything that travels by email: confirming an address, asking for another confirmation link, and resetting a
 * forgotten password. The public entry points (the ones that take an email address from someone who is not signed in)
 * answer the same way whether or not the address has an account, so they cannot be used to find out who is registered.
 */
@Service("developerAccountEmailService")
public class AccountEmailService {

    private static final Logger log = LoggerFactory.getLogger(AccountEmailService.class);

    private final DeveloperRepository developers;
    private final EmailTokenService tokens;
    private final MailService mail;
    private final PasswordHasher hasher;
    private final DeveloperProperties.Mail config;
    private final int passwordMinLength;
    private final Clock clock;

    @Autowired
    public AccountEmailService(DeveloperRepository developers, EmailTokenService tokens, MailService mail,
                               PasswordHasher hasher, DeveloperProperties props) {
        this(developers, tokens, mail, hasher, props.mail(), props.security().passwordMinLength(), Clock.systemUTC());
    }

    public AccountEmailService(DeveloperRepository developers, EmailTokenService tokens, MailService mail,
                               PasswordHasher hasher, DeveloperProperties.Mail config, int passwordMinLength, Clock clock) {
        this.developers = developers;
        this.tokens = tokens;
        this.mail = mail;
        this.hasher = hasher;
        this.config = config;
        this.passwordMinLength = passwordMinLength;
        this.clock = clock;
    }

    /** Whether this service has a mail server to send through. False means no email leaves, whatever else happens. */
    public boolean canSendEmail() {
        return mail.canSend();
    }

    /**
     * Sends the confirmation link for a new account. Returns at once: the link is created and sent on the mail threads,
     * so sign up does not wait for it. Never throws: sign up must not fail because email did.
     */
    public void sendVerification(Developer d) {
        mail.later("confirmation link", () -> deliverVerification(d));
    }

    private void deliverVerification(Developer d) {
        try {
            if (d.isEmailVerified() || tokens.waitSeconds(d, EmailTokenType.VERIFY_EMAIL).isPresent()) return;
            EmailTokenService.Issued issued = tokens.issue(d, EmailTokenType.VERIFY_EMAIL);
            String link = config.portalBaseUrl() + "/verify-email?token=" + issued.token();
            EmailTemplates.Message m = EmailTemplates.verify(greeting(d), link, config.verificationTtlHours(), config.portalBaseUrl());
            mail.send(d.getEmail(), m.subject(), m.html(), link);
        } catch (RuntimeException e) {
            log.warn("Could not send a confirmation link to developer {}: {}", d.getDeveloperId(), e.toString());
        }
    }

    /** For a signed in developer. Says plainly when it is not possible. */
    public Map<String, Object> resendVerification(Developer d) {
        if (d.isEmailVerified()) throw DeveloperException.invalidState("Your email address is already confirmed");
        Optional<Long> wait = tokens.waitSeconds(d, EmailTokenType.VERIFY_EMAIL);
        if (wait.isPresent()) {
            throw new DeveloperException(ErrorCode.RATE_LIMITED, "A link was sent a moment ago. Try again in " + wait.get() + " seconds.")
                    .with("retryAfterSeconds", wait.get());
        }
        sendVerification(d);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sent", true);
        m.put("email", d.getEmail());
        return m;
    }

    /** For someone who is not signed in. The answer never says whether the address has an account. */
    public void resendVerification(String email) {
        mail.later("confirmation link", () -> findActive(email).ifPresent(this::deliverVerification));
    }

    /** Redeems a confirmation link. */
    public Map<String, Object> verifyEmail(String token) {
        EmailToken row = tokens.redeem(token, EmailTokenType.VERIFY_EMAIL);
        Developer d = developers.findById(row.getDeveloperId())
                .orElseThrow(() -> new DeveloperException(ErrorCode.INVALID_TOKEN, "This link is invalid or has expired. Ask for a new one."));
        if (!d.getEmail().equals(row.getEmail())) {
            throw new DeveloperException(ErrorCode.INVALID_TOKEN, "This link is invalid or has expired. Ask for a new one.");
        }
        if (!d.isEmailVerified()) {
            Instant now = clock.instant();
            d.confirmEmail(now);
            d.setUpdatedAt(now);
            developers.save(d);
            sendWelcome(d);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("emailVerified", true);
        m.put("email", d.getEmail());
        return m;
    }

    /**
     * Sends a password reset link when the address belongs to an active account. Says nothing either way. The lookup and
     * the link are done on the mail threads, so the request takes the same time for every address.
     */
    public void forgotPassword(String email) {
        mail.later("password reset link", () -> deliverReset(email));
    }

    private void deliverReset(String email) {
        try {
            Optional<Developer> found = findActive(email);
            if (found.isEmpty()) return;
            Developer d = found.get();
            if (tokens.waitSeconds(d, EmailTokenType.RESET_PASSWORD).isPresent()) return;
            EmailTokenService.Issued issued = tokens.issue(d, EmailTokenType.RESET_PASSWORD);
            String link = config.portalBaseUrl() + "/reset-password?token=" + issued.token();
            EmailTemplates.Message m = EmailTemplates.reset(greeting(d), link, config.resetTtlMinutes(), config.portalBaseUrl());
            mail.send(d.getEmail(), m.subject(), m.html(), link);
        } catch (RuntimeException e) {
            log.warn("Could not send a password reset link: {}", e.toString());
        }
    }

    /**
     * Sets a new password from a reset link. The link proves the person controls the mailbox, so the address counts as
     * confirmed. Every session ends, a lockout is cleared, and any other reset link stops working.
     */
    public void resetPassword(String token, String newPassword) {
        PasswordPolicy.check(passwordMinLength, newPassword, null);
        EmailToken row = tokens.redeem(token, EmailTokenType.RESET_PASSWORD);
        Developer d = developers.findById(row.getDeveloperId())
                .orElseThrow(() -> new DeveloperException(ErrorCode.INVALID_TOKEN, "This link is invalid or has expired. Ask for a new one."));
        if (d.getStatus() == DeveloperStatus.DISABLED) {
            throw new DeveloperException(ErrorCode.ACCOUNT_DISABLED, "This account has been disabled");
        }
        PasswordPolicy.check(passwordMinLength, newPassword, d.getEmail());
        Instant now = clock.instant();
        d.setPasswordHash(hasher.hash(newPassword));
        d.setTokenVersion(d.getTokenVersion() + 1);
        d.setFailedLogins(0);
        d.setLockedUntil(null);
        if (!d.isEmailVerified()) d.confirmEmail(now);
        d.setUpdatedAt(now);
        developers.save(d);
        tokens.clear(d.getDeveloperId(), EmailTokenType.RESET_PASSWORD);
        sendPasswordChanged(d);
    }

    /** Tells the owner that the password changed. Never throws. */
    public void sendPasswordChanged(Developer d) {
        mail.later("password change notice", () -> deliverPasswordChanged(d));
    }

    private void deliverPasswordChanged(Developer d) {
        try {
            EmailTemplates.Message m = EmailTemplates.passwordChanged(greeting(d), config.portalBaseUrl());
            mail.send(d.getEmail(), m.subject(), m.html(), null);
        } catch (RuntimeException e) {
            log.warn("Could not send the password change notice to developer {}: {}", d.getDeveloperId(), e.toString());
        }
    }

    /** Tells an administrator account created at startup that it exists. Holds no password. Never throws. */
    public void sendAdminCreated(Developer d) {
        mail.later("administrator account email", () -> {
            try {
                EmailTemplates.Message m = EmailTemplates.adminCreated(greeting(d), d.getEmail(), config.portalBaseUrl());
                mail.send(d.getEmail(), m.subject(), m.html(), null);
            } catch (RuntimeException e) {
                log.warn("Could not send the administrator email to developer {}: {}", d.getDeveloperId(), e.toString());
            }
        });
    }

    /** Sent once the address is confirmed: what to do next. Never throws. */
    public void sendWelcome(Developer d) {
        mail.later("welcome email", () -> deliverWelcome(d));
    }

    private void deliverWelcome(Developer d) {
        try {
            EmailTemplates.Message m = EmailTemplates.welcome(greeting(d), config.portalBaseUrl());
            mail.send(d.getEmail(), m.subject(), m.html(), null);
        } catch (RuntimeException e) {
            log.warn("Could not send the welcome email to developer {}: {}", d.getDeveloperId(), e.toString());
        }
    }

    /**
     * Tells the owner about a successful sign in, with the time, method, device and network address, so a sign in they
     * did not make stands out. Off when developer.mail.notify-sign-in is false. Never throws.
     */
    public void sendSignInAlert(Developer d, ClientInfo client, String method, Instant at) {
        if (!config.notifySignIn()) return;
        mail.later("sign in notice", () -> deliverSignInAlert(d, client, method, at));
    }

    private void deliverSignInAlert(Developer d, ClientInfo client, String method, Instant at) {
        try {
            ClientInfo c = client == null ? ClientInfo.UNKNOWN : client;
            EmailTemplates.Message m = EmailTemplates.signIn(greeting(d), method, c.device(), c.ipText(), at,
                    config.portalBaseUrl());
            mail.send(d.getEmail(), m.subject(), m.html(), null);
        } catch (RuntimeException e) {
            log.warn("Could not send the sign in notice to developer {}: {}", d.getDeveloperId(), e.toString());
        }
    }

    /** The name the emails greet: the first name, or the full name for accounts that have none. */
    private static String greeting(Developer d) {
        return d.getFirstName() != null && !d.getFirstName().isBlank() ? d.getFirstName() : d.getFullName();
    }

    private Optional<Developer> findActive(String email) {
        String e = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (e.isEmpty() || e.length() > 160) return Optional.empty();
        return developers.findByEmail(e).filter(d -> d.getStatus() != DeveloperStatus.DISABLED);
    }
}
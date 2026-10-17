package com.premisave.developer.service;

import com.premisave.developer.client.Mailer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Sends email without making the request wait for it. {@link #later} moves all of the work off the request thread:
 * looking the account up, creating the link, filling the template and talking to the mail server. So sign up, sign in
 * and a reset request answer as fast as they would with no email at all, and asking for a reset link takes as long for a
 * known address as for an unknown one. A failure is logged and never reaches the caller: an account must not fail to be
 * created because a mail server is down. When no mailer is configured nothing is sent. With logLinks on, the full link
 * is written to the log instead, which is for local work only because a link is a credential.
 */
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final Mailer mailer;
    private final Executor executor;
    private final boolean logLinks;

    /** mailer may be null when no mail server is configured. */
    public MailService(Mailer mailer, Executor executor, boolean logLinks) {
        this.mailer = mailer;
        this.executor = executor;
        this.logLinks = logLinks;
    }

    public boolean canSend() {
        return mailer != null;
    }

    /**
     * Runs the work that prepares and sends an email on the mail threads, and returns at once. Never throws: when the
     * queue is full the email is dropped and logged, and the caller carries on.
     */
    public void later(String what, Runnable work) {
        try {
            executor.execute(() -> {
                try {
                    work.run();
                } catch (RuntimeException e) {
                    log.warn("Could not prepare the email '{}': {}", what, e.toString());
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("The mail queue is full; the email '{}' was dropped", what);
        }
    }

    /** link is only used for the log line in local mode. */
    public void send(String to, String subject, String html, String link) {
        if (mailer == null) {
            if (logLinks && link != null) {
                log.warn("Mail is not configured. Email to {} ({}): {}", to, subject, link);
            } else {
                log.warn("Mail is not configured, so the email '{}' to {} was not sent", subject, to);
            }
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    mailer.send(to, subject, html);
                } catch (Exception e) {
                    log.warn("Could not send the email '{}' to {}: {}", subject, to, e.toString());
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("The mail queue is full; the email '{}' to {} was dropped", subject, to);
        }
    }
}

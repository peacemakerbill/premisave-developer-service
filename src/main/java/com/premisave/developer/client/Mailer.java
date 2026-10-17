package com.premisave.developer.client;

/** Sends one email. Implementations may throw; the caller logs and carries on. */
public interface Mailer {
    void send(String to, String subject, String html) throws Exception;
}

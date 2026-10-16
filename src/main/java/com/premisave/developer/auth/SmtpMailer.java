package com.premisave.developer.auth;

import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/** Sends through the application's mail server (spring.mail.*). */
public class SmtpMailer implements Mailer {

    private final JavaMailSender sender;
    private final String from;

    public SmtpMailer(JavaMailSender sender, String from) {
        this.sender = sender;
        this.from = from;
    }

    @Override
    public void send(String to, String subject, String html) throws Exception {
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
        if (from != null) helper.setFrom(from);
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(html, true);
        sender.send(message);
    }
}

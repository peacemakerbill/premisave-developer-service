package com.premisave.developer.auth;

import com.premisave.developer.config.DeveloperProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Wires the mail service. The mail server comes from spring.mail.*; without spring.mail.host nothing is sent. */
@Configuration("developerMailConfig")
public class MailConfig {

    @Bean("developerMailService")
    public MailService mailService(Optional<JavaMailSender> sender, DeveloperProperties props) {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(500),
                r -> {
                    Thread t = new Thread(r, "developer-mail");
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
        Mailer mailer = sender.map(s -> (Mailer) new SmtpMailer(s, props.mail().from())).orElse(null);
        return new MailService(mailer, pool, props.mail().logLinks());
    }
}

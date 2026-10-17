package com.premisave.developer.config;

import com.premisave.developer.client.Mailer;
import com.premisave.developer.client.SmtpMailer;
import com.premisave.developer.service.MailService;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Wires the mail service. The mail server comes from spring.mail.*, which application.yml fills from the MAIL_*
 * names in .env or the environment. The host defaults to smtp.gmail.com, so Spring always builds a mail sender. A sender with no host
 * or no username is not a configured mail server: nothing is sent.
 */
@Configuration("developerMailConfig")
public class MailConfig {

    /** Removes pasted quotes and App Password spaces from the mail login before the mail sender is used. */
    @Bean
    static BeanPostProcessor developerMailCredentialsCleaner() {
        return new MailCredentialsCleaner();
    }

    /** False for a standard Spring mail sender with no host or no username, which is what an unset MAIL_USERNAME leaves behind. */
    public static boolean isConfigured(JavaMailSender sender) {
        return !(sender instanceof JavaMailSenderImpl impl) || (notBlank(impl.getHost()) && notBlank(impl.getUsername()));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    @Bean("developerMailService")
    MailService mailService(Optional<JavaMailSender> sender, DeveloperProperties props) {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(500),
                r -> {
                    Thread t = new Thread(r, "developer-mail");
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
        Mailer mailer = sender.filter(MailConfig::isConfigured).map(s -> (Mailer) new SmtpMailer(s, props.mail().from())).orElse(null);
        return new MailService(mailer, pool, props.mail().logLinks());
    }
}

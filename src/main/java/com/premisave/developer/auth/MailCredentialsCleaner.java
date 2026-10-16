package com.premisave.developer.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/** Cleans the mail user name and password (see {@link MailCredentials}) on the mail sender Spring Boot builds. */
public class MailCredentialsCleaner implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(MailCredentialsCleaner.class);

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof JavaMailSenderImpl sender) {
            String username = MailCredentials.username(sender.getUsername());
            String password = MailCredentials.password(sender.getPassword());
            boolean usernameChanged = username != null && !username.equals(sender.getUsername());
            boolean passwordChanged = password != null && !password.equals(sender.getPassword());
            if (usernameChanged) sender.setUsername(username);
            if (passwordChanged) sender.setPassword(password);
            if (usernameChanged || passwordChanged) {
                log.info("The mail {} had surrounding quotes or spaces, which were removed before logging in",
                        usernameChanged && passwordChanged ? "user name and password" : usernameChanged ? "user name" : "password");
            }
        }
        return bean;
    }
}

package com.premisave.developer.config;

import com.premisave.developer.util.EmailTemplates;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/** Hands the support address to the email templates, which are plain static code with no Spring wiring. */
@Configuration
class EmailSupportConfig {

    @Value("${email.support:thepeacemakerske@gmail.com}")
    private String supportEmail;

    @PostConstruct
    void apply() {
        EmailTemplates.setSupportEmail(supportEmail);
    }
}

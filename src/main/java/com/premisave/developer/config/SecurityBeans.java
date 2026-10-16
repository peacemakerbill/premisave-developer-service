package com.premisave.developer.config;

import com.premisave.developer.security.JwtService;
import com.premisave.developer.security.PasswordHasher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The password hasher and the token signer. They live apart from WebConfig on purpose: WebConfig needs the
 * authentication interceptor, which needs these, and one class cannot both depend on and provide them.
 */
@Configuration("developerSecurityBeans")
public class SecurityBeans {

    @Bean("developerPasswordHasher")
    public PasswordHasher passwordHasher(DeveloperProperties props) {
        return new PasswordHasher(props.security().pbkdf2Iterations());
    }

    @Bean("developerJwtService")
    public JwtService jwtService(DeveloperProperties props) {
        return new JwtService(props.jwt().secret(), props.jwt().issuer(), props.jwt().accessTtlMinutes());
    }
}

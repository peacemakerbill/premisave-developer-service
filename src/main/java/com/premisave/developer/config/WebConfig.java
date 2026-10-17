package com.premisave.developer.config;

import com.premisave.developer.security.AdminRoleInterceptor;
import com.premisave.developer.security.DeveloperAuthInterceptor;
import com.premisave.developer.security.InternalKeyInterceptor;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Settings, the guards (sign in, administrator role, internal key), and browser access for the developer portal only. */
@Configuration
@EnableConfigurationProperties(DeveloperProperties.class)
public class WebConfig implements WebMvcConfigurer {

    private final DeveloperAuthInterceptor developerAuth;
    private final AdminRoleInterceptor adminRole;
    private final InternalKeyInterceptor internalKey;
    private final DeveloperProperties props;

    public WebConfig(DeveloperAuthInterceptor developerAuth, AdminRoleInterceptor adminRole, InternalKeyInterceptor internalKey,
                     DeveloperProperties props) {
        this.developerAuth = developerAuth;
        this.adminRole = adminRole;
        this.internalKey = internalKey;
        this.props = props;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(developerAuth).addPathPatterns("/api/v1/**").excludePathPatterns("/api/v1/auth/**");
        // Registered after the sign in check, which puts the developer on the request. Administrators only.
        registry.addInterceptor(adminRole).addPathPatterns("/api/v1/admin/**");
        registry.addInterceptor(internalKey).addPathPatterns("/internal/**");
    }

    /** Only the portal API is opened to browsers, and only for the configured origins. The /v1 API never is. */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (props.cors().allowedOrigins().isEmpty()) return;
        registry.addMapping("/api/v1/**")
                .allowedOrigins(props.cors().allowedOrigins().toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type", "Accept")
                .maxAge(3600);
    }
}

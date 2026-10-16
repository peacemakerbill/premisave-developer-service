package com.premisave.developer.security;

import com.premisave.developer.common.DeveloperException;
import com.premisave.developer.common.Hashing;
import com.premisave.developer.config.DeveloperProperties;
import com.premisave.developer.enums.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Guards /internal/** with the internal API key. With no key configured every call is refused. */
@Component("developerInternalKeyInterceptor")
public class InternalKeyInterceptor implements HandlerInterceptor {

    private final DeveloperProperties.Internal config;

    public InternalKeyInterceptor(DeveloperProperties props) {
        this.config = props.internal();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String presented = request.getHeader("X-API-Key");
        if (!config.configured() || presented == null || !Hashing.constantTimeEquals(presented, config.apiKey())) {
            throw new DeveloperException(ErrorCode.UNAUTHENTICATED, "A valid internal API key is required");
        }
        return true;
    }
}

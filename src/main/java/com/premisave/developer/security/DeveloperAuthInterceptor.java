package com.premisave.developer.security;

import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.exception.DeveloperException;
import com.premisave.developer.service.DeveloperService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Requires a valid sign in token on the portal API and makes the developer available to the controller. */
@Component("developerAuthInterceptor")
public class DeveloperAuthInterceptor implements HandlerInterceptor {

    public static final String ATTR_DEVELOPER = "developer";
    public static final String ATTR_CLAIMS = "tokenClaims";

    private final DeveloperService developers;

    public DeveloperAuthInterceptor(DeveloperService developers) {
        this.developers = developers;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equals(request.getMethod())) return true;
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new DeveloperException(ErrorCode.UNAUTHENTICATED, "Send your sign in token as Authorization: Bearer <token>");
        }
        DeveloperService.Authenticated who = developers.authenticateToken(auth.substring(7).trim());
        request.setAttribute(ATTR_DEVELOPER, who.developer());
        request.setAttribute(ATTR_CLAIMS, who.claims());
        return true;
    }
}

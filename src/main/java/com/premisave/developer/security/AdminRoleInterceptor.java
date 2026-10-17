package com.premisave.developer.security;

import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.exception.DeveloperException;
import com.premisave.developer.model.Developer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Lets only administrators into /api/v1/admin. It runs after the sign in check, which has already put the developer on the
 * request. The role is the one stored on the account, so removing it locks the person out of the admin API at once.
 */
@Component("developerAdminRoleInterceptor")
public class AdminRoleInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equals(request.getMethod())) return true;
        require((Developer) request.getAttribute(DeveloperAuthInterceptor.ATTR_DEVELOPER));
        return true;
    }

    /** Throws FORBIDDEN unless the account is an administrator. */
    public static void require(Developer developer) {
        if (developer == null || !developer.isAdmin()) {
            throw new DeveloperException(ErrorCode.FORBIDDEN, "This area is for Premisave administrators");
        }
    }
}

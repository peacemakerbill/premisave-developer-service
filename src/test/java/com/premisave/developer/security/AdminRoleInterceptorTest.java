package com.premisave.developer.security;

import com.premisave.developer.enums.DeveloperRole;
import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.exception.DeveloperException;
import com.premisave.developer.model.Developer;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Only administrators get into /api/v1/admin. */
class AdminRoleInterceptorTest {

    private final AdminRoleInterceptor interceptor = new AdminRoleInterceptor();

    private static HttpServletRequest request(String method, Developer developer) {
        return (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class}, (proxy, m, args) -> switch (m.getName()) {
                    case "getMethod" -> method;
                    case "getAttribute" -> DeveloperAuthInterceptor.ATTR_DEVELOPER.equals(args[0]) ? developer : null;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    private static Developer withRole(DeveloperRole role) {
        Developer d = new Developer();
        d.setDeveloperId("dev_1");
        d.setRole(role);
        return d;
    }

    @Test
    void anAdministratorGetsIn() {
        assertTrue(interceptor.preHandle(request("GET", withRole(DeveloperRole.ADMIN)), null, null));
    }

    @Test
    void anOrdinaryDeveloperIsRefused() {
        DeveloperException e = assertThrows(DeveloperException.class,
                () -> interceptor.preHandle(request("GET", withRole(DeveloperRole.DEVELOPER)), null, null));
        assertEquals(ErrorCode.FORBIDDEN, e.code());
        assertEquals(403, e.code().httpStatus());
    }

    @Test
    void anAccountFromBeforeRolesExistedCountsAsAnOrdinaryDeveloper() {
        Developer old = new Developer();
        old.setRole(null);
        assertThrows(DeveloperException.class, () -> AdminRoleInterceptor.require(old));
    }

    @Test
    void noDeveloperOnTheRequestIsRefused() {
        assertThrows(DeveloperException.class, () -> interceptor.preHandle(request("POST", null), null, null));
    }

    @Test
    void thePreflightRequestPassesWithoutASignIn() {
        assertTrue(interceptor.preHandle(request("OPTIONS", null), null, null));
    }
}

package com.premisave.developer.filter;

import com.premisave.developer.util.Ids;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Gives every request an id (the caller's X-Request-Id when it is sensible) and echoes it back. */
@Component("developerRequestIdFilter")
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String ATTR = "developer.requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = request.getHeader("X-Request-Id");
        if (id == null || id.isBlank() || id.length() > 64 || !id.matches("[A-Za-z0-9_.:-]+")) {
            id = Ids.requestId();
        }
        request.setAttribute(ATTR, id);
        response.setHeader("X-Request-Id", id);
        chain.doFilter(request, response);
    }

    public static String of(HttpServletRequest request) {
        Object id = request.getAttribute(ATTR);
        return id instanceof String s ? s : Ids.requestId();
    }
}

package com.premisave.developer.controller;

import com.premisave.developer.filter.RequestIdFilter;
import com.premisave.developer.service.ProxyService;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/** /v1/live/** and /v1/sandbox/**: the API merchants call with their key and secret. */
@RestController("developerPublicApiController")
public class PublicApiController {

    private final ProxyService proxy;

    public PublicApiController(ProxyService proxy) {
        this.proxy = proxy;
    }

    @RequestMapping(value = "/v1/**",
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
    public ResponseEntity<byte[]> handle(HttpServletRequest request) throws IOException {
        byte[] body = ProxyService.readBody(request.getInputStream(), proxy.maxBodyBytes());
        return proxy.handlePublic(request.getMethod(), request.getRequestURI(), request.getQueryString(),
                request::getHeader, body, RequestIdFilter.of(request));
    }
}

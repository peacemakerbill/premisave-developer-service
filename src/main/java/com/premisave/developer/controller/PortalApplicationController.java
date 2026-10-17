package com.premisave.developer.controller;

import com.premisave.developer.filter.RequestIdFilter;
import com.premisave.developer.model.Developer;
import com.premisave.developer.service.MerchantLinkService;
import com.premisave.developer.service.ProxyService;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * The onboarding application of the signed in developer's business: the form, documents, submit and timeline. The calls go
 * to the wallet service, which owns the application; this service only fixes the merchant to the developer's own.
 */
@RestController("developerPortalApplicationController")
public class PortalApplicationController {

    private static final String PREFIX = "/api/v1/merchant/application";

    private final ProxyService proxy;

    public PortalApplicationController(ProxyService proxy) {
        this.proxy = proxy;
    }

    @RequestMapping(value = "/api/v1/merchant/application/**",
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
    public ResponseEntity<byte[]> handle(@RequestAttribute Developer developer, HttpServletRequest request)
            throws IOException {
        String merchantId = MerchantLinkService.requireMerchantId(developer);
        String uri = request.getRequestURI();
        String rest = uri.length() > PREFIX.length() ? uri.substring(PREFIX.length()) : "";
        byte[] body = ProxyService.readBody(request.getInputStream(), proxy.maxUploadBytes());
        return proxy.forwardApplication(request.getMethod(), merchantId, developer.getDeveloperId(),
                rest.isEmpty() ? "/" : rest, request.getQueryString(), request::getHeader, body, RequestIdFilter.of(request));
    }
}

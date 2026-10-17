package com.premisave.developer.controller;

import com.premisave.developer.exception.DeveloperException;
import com.premisave.developer.filter.RequestIdFilter;
import com.premisave.developer.model.Developer;
import com.premisave.developer.service.BusinessService;
import com.premisave.developer.service.ProxyService;
import com.premisave.developer.util.DocumentUploads;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * The onboarding application of one business: the form, documents, submit and timeline. The wallet service owns the
 * application; this service checks the business belongs to the signed in developer and passes the call on. A document is
 * sent as an ordinary file upload and converted to what the wallet service takes.
 */
@RestController("developerBusinessApplicationController")
public class BusinessApplicationController {

    private static final String PREFIX = "/api/v1/businesses/";

    private final BusinessService businesses;
    private final ProxyService proxy;

    public BusinessApplicationController(BusinessService businesses, ProxyService proxy) {
        this.businesses = businesses;
        this.proxy = proxy;
    }

    /** Form field "file" is the document. Form field "type" says what it is, for example business registration. */
    @PostMapping(value = "/api/v1/businesses/{businessId}/application/documents", consumes = "multipart/form-data")
    public ResponseEntity<byte[]> upload(@RequestAttribute Developer developer, @PathVariable String businessId,
                                         @RequestParam(required = false) MultipartFile file,
                                         @RequestParam(required = false) String type,
                                         HttpServletRequest request) throws IOException {
        String id = businesses.requireApplicant(developer, businessId);
        if (file == null || file.isEmpty()) {
            throw DeveloperException.validation("file", "is required. Attach a PDF, PNG or JPEG");
        }
        byte[] body = DocumentUploads.walletBody(type, file.getOriginalFilename(), file.getContentType(), file.getBytes());
        return proxy.forwardApplication("POST", id, developer.getDeveloperId(), "/documents", null,
                name -> name.equals("content-type") ? "application/json" : request.getHeader(name), body,
                RequestIdFilter.of(request));
    }

    @RequestMapping(value = "/api/v1/businesses/{businessId}/application/**",
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
    public ResponseEntity<byte[]> handle(@RequestAttribute Developer developer, @PathVariable String businessId,
                                         HttpServletRequest request) throws IOException {
        String id = businesses.requireApplicant(developer, businessId);
        String marker = PREFIX + businessId + "/application";
        String uri = request.getRequestURI();
        String rest = uri.length() > marker.length() ? uri.substring(marker.length()) : "";
        byte[] body = ProxyService.readBody(request.getInputStream(), proxy.maxUploadBytes());
        return proxy.forwardApplication(request.getMethod(), id, developer.getDeveloperId(),
                rest.isEmpty() ? "/" : rest, request.getQueryString(), request::getHeader, body, RequestIdFilter.of(request));
    }
}

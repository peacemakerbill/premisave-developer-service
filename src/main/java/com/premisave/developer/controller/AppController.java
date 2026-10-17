package com.premisave.developer.controller;

import com.premisave.developer.dto.ApiResponse;
import com.premisave.developer.dto.CreateAppRequest;
import com.premisave.developer.dto.ReasonRequest;
import com.premisave.developer.dto.UpdateAppRequest;
import com.premisave.developer.enums.AppStatus;
import com.premisave.developer.exception.DeveloperException;
import com.premisave.developer.model.Developer;
import com.premisave.developer.service.AppService;
import com.premisave.developer.util.Responses;
import com.premisave.developer.view.AppViews;
import com.premisave.developer.view.PageViews;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.Map;

/** Sandbox and production apps of the signed in developer. */
@RestController("developerAppController")
@RequestMapping("/api/v1/apps")
public class AppController {

    private final AppService apps;

    public AppController(AppService apps) {
        this.apps = apps;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@RequestAttribute Developer developer,
                                                                   @Valid @RequestBody CreateAppRequest req) {
        AppService.Created created = apps.create(developer, req);
        return Responses.created("App created", AppService.withKey(created.app(), created.key()));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(
            @RequestAttribute Developer developer,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        AppStatus appStatus = null;
        if (status != null && !status.isBlank()) {
            try {
                appStatus = AppStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw DeveloperException.validation("status", "must be ACTIVE or SUSPENDED");
            }
        }
        return Responses.page("Apps retrieved",
                apps.list(developer, environment, appStatus, PageViews.pageable(page, size)), AppViews::app);
    }

    @GetMapping("/{appId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(@RequestAttribute Developer developer,
                                                                @PathVariable String appId) {
        return Responses.ok("App retrieved", AppViews.app(apps.get(developer, appId)));
    }

    @PutMapping("/{appId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(@RequestAttribute Developer developer,
                                                                   @PathVariable String appId,
                                                                   @Valid @RequestBody UpdateAppRequest req) {
        return Responses.ok("App updated", AppViews.app(apps.update(developer, appId, req)));
    }

    @PostMapping("/{appId}/suspend")
    public ResponseEntity<ApiResponse<Map<String, Object>>> suspend(@RequestAttribute Developer developer,
                                                                    @PathVariable String appId,
                                                                    @Valid @RequestBody(required = false) ReasonRequest req) {
        return Responses.ok("App suspended", AppViews.app(apps.suspend(developer, appId, req == null ? null : req.reason())));
    }

    @PostMapping("/{appId}/reactivate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reactivate(@RequestAttribute Developer developer,
                                                                       @PathVariable String appId) {
        return Responses.ok("App reactivated", AppViews.app(apps.reactivate(developer, appId)));
    }
}

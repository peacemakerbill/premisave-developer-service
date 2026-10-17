package com.premisave.developer.controller;

import com.premisave.developer.dto.AdminOwnerRequest;
import com.premisave.developer.dto.AdminStatusRequest;
import com.premisave.developer.dto.ApiResponse;
import com.premisave.developer.dto.CreateKeyRequest;
import com.premisave.developer.dto.UpdateAppRequest;
import com.premisave.developer.enums.AppStatus;
import com.premisave.developer.exception.DeveloperException;
import com.premisave.developer.model.Developer;
import com.premisave.developer.service.AdminActor;
import com.premisave.developer.service.AdminAppService;
import com.premisave.developer.util.Responses;
import com.premisave.developer.view.AppViews;
import com.premisave.developer.view.KeyViews;
import com.premisave.developer.view.PageViews;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Premisave staff: any app and its keys. Same two entrances as the rest of the admin API. */
@RestController("developerAdminAppController")
@RequestMapping({"/api/v1/admin", "/internal/admin"})
public class AdminAppController {

    private final AdminAppService apps;

    public AdminAppController(AdminAppService apps) {
        this.apps = apps;
    }

    /** Newest first. Every filter is optional: developerId, businessId, environment (production or sandbox), status and name. */
    @GetMapping("/apps")
    public ResponseEntity<ApiResponse<Map<String, Object>>> search(@RequestParam(required = false) String developerId,
                                                                    @RequestParam(required = false) String businessId,
                                                                    @RequestParam(required = false) String environment,
                                                                    @RequestParam(required = false) String status,
                                                                    @RequestParam(required = false) String name,
                                                                    @RequestParam(required = false) Integer page,
                                                                    @RequestParam(required = false) Integer size) {
        AppStatus wanted = status == null || status.isBlank() ? null : appStatus(status);
        return Responses.page("Apps retrieved",
                apps.search(developerId, businessId, environment, wanted, name, PageViews.pageable(page, size)), AppViews::app);
    }

    @GetMapping("/apps/{appId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> app(@PathVariable String appId) {
        return Responses.ok("App retrieved", AppViews.app(apps.get(appId)));
    }

    @PutMapping("/apps/{appId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(
            @RequestAttribute(name = "developer", required = false) Developer admin, @PathVariable String appId,
            @Valid @RequestBody UpdateAppRequest req) {
        return Responses.ok("App updated", AppViews.app(apps.update(AdminActor.of(admin), appId, req)));
    }

    @DeleteMapping("/apps/{appId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> delete(
            @RequestAttribute(name = "developer", required = false) Developer admin, @PathVariable String appId) {
        apps.delete(AdminActor.of(admin), appId);
        return Responses.ok("App deleted", AdminController.deleted("appId", appId));
    }

    @PutMapping("/apps/{appId}/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status(
            @RequestAttribute(name = "developer", required = false) Developer admin, @PathVariable String appId,
            @Valid @RequestBody AdminStatusRequest req) {
        AppStatus status = appStatus(req.status());
        if (status == AppStatus.SUSPENDED && (req.reason() == null || req.reason().isBlank())) {
            throw DeveloperException.validation("reason", "is required when suspending");
        }
        return Responses.ok("App status updated", AppViews.app(apps.setStatus(AdminActor.of(admin), appId, status, req.reason())));
    }

    /** Hands the app to another active developer account and revokes its keys. */
    @PutMapping("/apps/{appId}/owner")
    public ResponseEntity<ApiResponse<Map<String, Object>>> owner(
            @RequestAttribute(name = "developer", required = false) Developer admin, @PathVariable String appId,
            @Valid @RequestBody AdminOwnerRequest req) {
        return Responses.ok("App owner changed", AppViews.app(apps.changeOwner(AdminActor.of(admin), appId, req.developerId())));
    }

    // Keys

    @GetMapping("/apps/{appId}/keys")
    public ResponseEntity<ApiResponse<Map<String, Object>>> keys(@PathVariable String appId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("keys", apps.keys(appId).stream().map(KeyViews::key).toList());
        return Responses.ok("Keys retrieved", m);
    }

    @PostMapping("/apps/{appId}/keys")
    public ResponseEntity<ApiResponse<Map<String, Object>>> issueKey(
            @RequestAttribute(name = "developer", required = false) Developer admin, @PathVariable String appId,
            @Valid @RequestBody(required = false) CreateKeyRequest req) {
        return Responses.created("Key created",
                KeyViews.issued(apps.issueKey(AdminActor.of(admin), appId, req == null ? null : req.label())));
    }

    @DeleteMapping("/apps/{appId}/keys")
    public ResponseEntity<ApiResponse<Map<String, Object>>> revokeAll(
            @RequestAttribute(name = "developer", required = false) Developer admin, @PathVariable String appId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("appId", appId);
        m.put("revoked", apps.revokeAllKeys(AdminActor.of(admin), appId));
        return Responses.ok("Keys revoked", m);
    }

    @PostMapping("/apps/{appId}/keys/{key}/rotate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> rotateKey(
            @RequestAttribute(name = "developer", required = false) Developer admin, @PathVariable String appId,
            @PathVariable String key) {
        return Responses.created("Key rotated", KeyViews.issued(apps.rotateKey(AdminActor.of(admin), appId, key)));
    }

    @DeleteMapping("/apps/{appId}/keys/{key}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> revokeKey(
            @RequestAttribute(name = "developer", required = false) Developer admin, @PathVariable String appId,
            @PathVariable String key) {
        return Responses.ok("Key revoked", KeyViews.key(apps.revokeKey(AdminActor.of(admin), appId, key)));
    }

    private static AppStatus appStatus(String value) {
        try {
            return AppStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw DeveloperException.validation("status", "must be ACTIVE or SUSPENDED");
        }
    }
}

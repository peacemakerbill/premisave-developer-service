package com.premisave.developer.controller;

import com.premisave.developer.dto.AdminCreateDeveloperRequest;
import com.premisave.developer.dto.AdminEmailRequest;
import com.premisave.developer.dto.AdminRoleRequest;
import com.premisave.developer.dto.AdminStatusRequest;
import com.premisave.developer.dto.ApiResponse;
import com.premisave.developer.dto.UpdateProfileRequest;
import com.premisave.developer.enums.DeveloperStatus;
import com.premisave.developer.exception.DeveloperException;
import com.premisave.developer.model.Developer;
import com.premisave.developer.service.AdminActor;
import com.premisave.developer.service.AdminAppService;
import com.premisave.developer.service.AdminAudit;
import com.premisave.developer.service.AdminDeveloperService;
import com.premisave.developer.service.AdminOverviewService;
import com.premisave.developer.util.Responses;
import com.premisave.developer.view.AdminViews;
import com.premisave.developer.view.AppViews;
import com.premisave.developer.view.DeveloperViews;
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

/**
 * Premisave staff: developer accounts, platform numbers and the audit trail. Reachable two ways, with the same behaviour:
 * /api/v1/admin with the sign in token of an administrator, and /internal/admin with the internal API key.
 */
@RestController("developerAdminController")
@RequestMapping({"/api/v1/admin", "/internal/admin"})
public class AdminController {

    private final AdminDeveloperService accounts;
    private final AdminAppService apps;
    private final AdminOverviewService overview;
    private final AdminAudit audit;

    public AdminController(AdminDeveloperService accounts, AdminAppService apps, AdminOverviewService overview, AdminAudit audit) {
        this.accounts = accounts;
        this.apps = apps;
        this.overview = overview;
        this.audit = audit;
    }

    // Overview

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> stats() {
        return Responses.ok("Statistics retrieved", overview.stats());
    }

    /** Newest first. actorId and targetId match exactly, action matches anywhere in the name (for example APP_ or _DELETED). */
    @GetMapping("/audit")
    public ResponseEntity<ApiResponse<Map<String, Object>>> audit(@RequestParam(required = false) String actorId,
                                                                   @RequestParam(required = false) String targetId,
                                                                   @RequestParam(required = false) String action,
                                                                   @RequestParam(required = false) Integer page,
                                                                   @RequestParam(required = false) Integer size) {
        return Responses.page("Audit trail retrieved", audit.search(actorId, targetId, action, PageViews.pageable(page, size)),
                AdminViews::action);
    }

    // Developers

    /**
     * Searches developer accounts, newest first. q is matched anywhere in the email, username, full name or company name,
     * or against the developer id exactly; status narrows the list to PENDING, ACTIVE or DISABLED accounts.
     */
    @GetMapping("/developers")
    public ResponseEntity<ApiResponse<Map<String, Object>>> searchDevelopers(@RequestParam(required = false) String q,
                                                                              @RequestParam(required = false) String status,
                                                                              @RequestParam(required = false) Integer page,
                                                                              @RequestParam(required = false) Integer size) {
        DeveloperStatus wanted = status == null || status.isBlank() ? null : status(status, "PENDING, ACTIVE or DISABLED");
        return Responses.page("Developers retrieved", accounts.search(q, wanted, PageViews.pageable(page, size)),
                DeveloperViews::summary);
    }

    @GetMapping("/administrators")
    public ResponseEntity<ApiResponse<Map<String, Object>>> administrators(@RequestParam(required = false) Integer page,
                                                                            @RequestParam(required = false) Integer size) {
        return Responses.page("Administrators retrieved", accounts.administrators(PageViews.pageable(page, size)),
                DeveloperViews::summary);
    }

    @PostMapping("/developers")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createDeveloper(
            @RequestAttribute(name = "developer", required = false) Developer admin,
            @Valid @RequestBody AdminCreateDeveloperRequest req) {
        return Responses.created("Developer created", accounts.create(AdminActor.of(admin), req));
    }

    @GetMapping("/developers/{developerId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> developer(@PathVariable String developerId) {
        return Responses.ok("Developer retrieved", AdminViews.developer(accounts.get(developerId)));
    }

    @PutMapping("/developers/{developerId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateDeveloper(
            @RequestAttribute(name = "developer", required = false) Developer admin, @PathVariable String developerId,
            @Valid @RequestBody UpdateProfileRequest req) {
        return Responses.ok("Developer updated", AdminViews.developer(accounts.updateProfile(AdminActor.of(admin), developerId, req)));
    }

    @DeleteMapping("/developers/{developerId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteDeveloper(
            @RequestAttribute(name = "developer", required = false) Developer admin, @PathVariable String developerId) {
        accounts.delete(AdminActor.of(admin), developerId);
        return Responses.ok("Developer deleted", deleted("developerId", developerId));
    }

    @PutMapping("/developers/{developerId}/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> developerStatus(
            @RequestAttribute(name = "developer", required = false) Developer admin, @PathVariable String developerId,
            @Valid @RequestBody AdminStatusRequest req) {
        DeveloperStatus status = status(req.status(), "ACTIVE or DISABLED");
        return Responses.ok("Developer status updated",
                AdminViews.developer(accounts.setStatus(AdminActor.of(admin), developerId, status)));
    }

    @PutMapping("/developers/{developerId}/role")
    public ResponseEntity<ApiResponse<Map<String, Object>>> developerRole(
            @RequestAttribute(name = "developer", required = false) Developer admin, @PathVariable String developerId,
            @Valid @RequestBody AdminRoleRequest req) {
        return Responses.ok("Developer role updated",
                AdminViews.developer(accounts.setRole(AdminActor.of(admin), developerId, req.role())));
    }

    @PutMapping("/developers/{developerId}/email")
    public ResponseEntity<ApiResponse<Map<String, Object>>> developerEmail(
            @RequestAttribute(name = "developer", required = false) Developer admin, @PathVariable String developerId,
            @Valid @RequestBody AdminEmailRequest req) {
        return Responses.ok("Developer email updated",
                AdminViews.developer(accounts.setEmail(AdminActor.of(admin), developerId, req.email(), req.verified())));
    }

    @PostMapping("/developers/{developerId}/verify-email")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyEmail(
            @RequestAttribute(name = "developer", required = false) Developer admin, @PathVariable String developerId) {
        return Responses.ok("Email counted as confirmed", AdminViews.developer(accounts.verifyEmail(AdminActor.of(admin), developerId)));
    }

    @PostMapping("/developers/{developerId}/unlock")
    public ResponseEntity<ApiResponse<Map<String, Object>>> unlock(
            @RequestAttribute(name = "developer", required = false) Developer admin, @PathVariable String developerId) {
        return Responses.ok("Account unlocked", AdminViews.developer(accounts.unlock(AdminActor.of(admin), developerId)));
    }

    @PostMapping("/developers/{developerId}/sign-out")
    public ResponseEntity<ApiResponse<Map<String, Object>>> signOut(
            @RequestAttribute(name = "developer", required = false) Developer admin, @PathVariable String developerId) {
        return Responses.ok("All sessions ended", AdminViews.developer(accounts.signOut(AdminActor.of(admin), developerId)));
    }

    @PostMapping("/developers/{developerId}/password-reset")
    public ResponseEntity<ApiResponse<Map<String, Object>>> passwordReset(
            @RequestAttribute(name = "developer", required = false) Developer admin, @PathVariable String developerId) {
        return Responses.ok("Password reset requested", accounts.sendPasswordReset(AdminActor.of(admin), developerId));
    }

    // Apps and businesses of one developer

    @GetMapping("/developers/{developerId}/apps")
    public ResponseEntity<ApiResponse<Map<String, Object>>> developerApps(@PathVariable String developerId,
                                                                           @RequestParam(required = false) String environment,
                                                                           @RequestParam(required = false) Integer page,
                                                                           @RequestParam(required = false) Integer size) {
        accounts.get(developerId);
        return Responses.page("Apps retrieved",
                apps.search(developerId, null, environment, null, null, PageViews.pageable(page, size)), AppViews::app);
    }

    @GetMapping("/developers/{developerId}/businesses")
    public ResponseEntity<ApiResponse<Map<String, Object>>> developerBusinesses(@PathVariable String developerId) {
        return Responses.ok("Businesses retrieved", accounts.businessesOf(developerId));
    }

    @PostMapping("/developers/{developerId}/businesses/{businessId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> shareBusiness(
            @RequestAttribute(name = "developer", required = false) Developer admin, @PathVariable String developerId,
            @PathVariable String businessId) {
        return Responses.ok("Business shared", accounts.shareBusiness(AdminActor.of(admin), developerId, businessId));
    }

    @DeleteMapping("/developers/{developerId}/businesses/{businessId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> unshareBusiness(
            @RequestAttribute(name = "developer", required = false) Developer admin, @PathVariable String developerId,
            @PathVariable String businessId) {
        accounts.unshareBusiness(AdminActor.of(admin), developerId, businessId);
        return Responses.ok("Business no longer shared", deleted("businessId", businessId));
    }

    private static DeveloperStatus status(String value, String allowed) {
        try {
            return DeveloperStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw DeveloperException.validation("status", "must be " + allowed);
        }
    }

    static Map<String, Object> deleted(String key, String id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, id);
        m.put("deleted", true);
        return m;
    }
}

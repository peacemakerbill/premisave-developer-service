package com.premisave.developer.controller;

import com.premisave.developer.dto.AdminStatusRequest;
import com.premisave.developer.dto.ApiResponse;
import com.premisave.developer.enums.AppStatus;
import com.premisave.developer.enums.DeveloperStatus;
import com.premisave.developer.exception.DeveloperException;
import com.premisave.developer.service.AppService;
import com.premisave.developer.service.DeveloperService;
import com.premisave.developer.util.Responses;
import com.premisave.developer.view.AppViews;
import com.premisave.developer.view.DeveloperViews;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.Map;

/** Premisave staff tooling, behind the internal API key. */
@RestController("developerAdminController")
@RequestMapping("/internal/admin")
public class AdminController {

    private final AppService apps;
    private final DeveloperService developers;

    public AdminController(AppService apps, DeveloperService developers) {
        this.apps = apps;
        this.developers = developers;
    }

    @GetMapping("/apps/{appId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> app(@PathVariable String appId) {
        return Responses.ok("App retrieved", AppViews.app(apps.adminGet(appId)));
    }

    @PutMapping("/apps/{appId}/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> appStatus(@PathVariable String appId,
                                                                      @Valid @RequestBody AdminStatusRequest req) {
        AppStatus status = parse(AppStatus.class, req.status(), "ACTIVE or SUSPENDED");
        if (status == AppStatus.SUSPENDED && (req.reason() == null || req.reason().isBlank())) {
            throw DeveloperException.validation("reason", "is required when suspending");
        }
        return Responses.ok("App status updated", AppViews.app(apps.adminSetStatus(appId, status, req.reason())));
    }

    @PutMapping("/developers/{developerId}/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> developerStatus(@PathVariable String developerId,
                                                                            @Valid @RequestBody AdminStatusRequest req) {
        DeveloperStatus status = parse(DeveloperStatus.class, req.status(), "ACTIVE or DISABLED");
        return Responses.ok("Developer status updated", DeveloperViews.developer(developers.setStatus(developerId, status)));
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String allowed) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw DeveloperException.validation("status", "must be " + allowed);
        }
    }
}

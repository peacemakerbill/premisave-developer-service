package com.premisave.developer.controller;

import com.premisave.developer.dto.ApiResponse;
import com.premisave.developer.enums.TransferKind;
import com.premisave.developer.enums.TransferStatus;
import com.premisave.developer.exception.DeveloperException;
import com.premisave.developer.model.Developer;
import com.premisave.developer.service.AdminActor;
import com.premisave.developer.service.AdminOverviewService;
import com.premisave.developer.util.Responses;
import com.premisave.developer.view.AdminViews;
import com.premisave.developer.view.PageViews;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/** Premisave staff: app transfers and business claims. Same two entrances as the rest of the admin API. */
@RestController("developerAdminTransferController")
@RequestMapping({"/api/v1/admin", "/internal/admin"})
public class AdminTransferController {

    private final AdminOverviewService overview;
    private final Clock clock = Clock.systemUTC();

    public AdminTransferController(AdminOverviewService overview) {
        this.overview = overview;
    }

    /** Newest first. Filters: status (PENDING, COMPLETED, CANCELLED or REJECTED), type (APP_TRANSFER or BUSINESS_CLAIM) and developerId. */
    @GetMapping("/transfers")
    public ResponseEntity<ApiResponse<Map<String, Object>>> search(@RequestParam(required = false) String status,
                                                                    @RequestParam(required = false) String type,
                                                                    @RequestParam(required = false) String developerId,
                                                                    @RequestParam(required = false) Integer page,
                                                                    @RequestParam(required = false) Integer size) {
        TransferStatus s = status == null || status.isBlank() ? null : parse(TransferStatus.class, status, "status",
                "PENDING, COMPLETED, CANCELLED or REJECTED");
        TransferKind k = type == null || type.isBlank() ? null : parse(TransferKind.class, type, "type", "APP_TRANSFER or BUSINESS_CLAIM");
        Instant now = clock.instant();
        return Responses.page("Transfers retrieved", overview.searchTransfers(s, k, developerId, PageViews.pageable(page, size)),
                t -> AdminViews.transfer(t, now));
    }

    @GetMapping("/transfers/{transferId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> transfer(@PathVariable String transferId) {
        return Responses.ok("Transfer retrieved", AdminViews.transfer(overview.getTransfer(transferId), clock.instant()));
    }

    /** Closes a request that is still waiting for its code. */
    @DeleteMapping("/transfers/{transferId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cancel(
            @RequestAttribute(name = "developer", required = false) Developer admin, @PathVariable String transferId) {
        return Responses.ok("Transfer cancelled",
                AdminViews.transfer(overview.cancelTransfer(AdminActor.of(admin), transferId), clock.instant()));
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String field, String allowed) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw DeveloperException.validation(field, "must be " + allowed);
        }
    }
}

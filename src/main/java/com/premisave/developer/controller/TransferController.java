package com.premisave.developer.controller;

import com.premisave.developer.dto.ApiResponse;
import com.premisave.developer.dto.ClaimBusinessRequest;
import com.premisave.developer.dto.ConfirmTransferRequest;
import com.premisave.developer.dto.TransferAppRequest;
import com.premisave.developer.model.Developer;
import com.premisave.developer.service.TransferService;
import com.premisave.developer.util.Responses;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Moving a production app to another developer account, and taking up an approved business by its merchant number. Both
 * are confirmed with a code emailed to the address linked to the business.
 */
@RestController("developerTransferController")
public class TransferController {

    private final TransferService transfers;

    public TransferController(TransferService transfers) {
        this.transfers = transfers;
    }

    @PostMapping("/api/v1/apps/{appId}/transfer")
    public ResponseEntity<ApiResponse<Map<String, Object>>> transferApp(@RequestAttribute Developer developer,
                                                                        @PathVariable String appId,
                                                                        @Valid @RequestBody TransferAppRequest req) {
        return Responses.created("A code was emailed to the address linked to the business", transfers.startAppTransfer(developer, appId, req.toEmail()));
    }

    @PostMapping("/api/v1/transfers/claim")
    public ResponseEntity<ApiResponse<Map<String, Object>>> claim(@RequestAttribute Developer developer,
                                                                  @Valid @RequestBody ClaimBusinessRequest req) {
        return Responses.created("A code was emailed to the address linked to the business", transfers.startClaim(developer, req.merchantNumber()));
    }

    @GetMapping("/api/v1/transfers")
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(@RequestAttribute Developer developer) {
        return Responses.ok("Transfers retrieved", transfers.list(developer));
    }

    @GetMapping("/api/v1/transfers/{transferId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(@RequestAttribute Developer developer,
                                                                @PathVariable String transferId) {
        return Responses.ok("Transfer retrieved", transfers.get(developer, transferId));
    }

    @PostMapping("/api/v1/transfers/{transferId}/confirm")
    public ResponseEntity<ApiResponse<Map<String, Object>>> confirm(@RequestAttribute Developer developer,
                                                                    @PathVariable String transferId,
                                                                    @Valid @RequestBody ConfirmTransferRequest req) {
        return Responses.ok("Confirmed", transfers.confirm(developer, transferId, req.otp()));
    }

    @PostMapping("/api/v1/transfers/{transferId}/resend")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resend(@RequestAttribute Developer developer,
                                                                   @PathVariable String transferId) {
        return Responses.ok("A new code was emailed", transfers.resend(developer, transferId));
    }

    @DeleteMapping("/api/v1/transfers/{transferId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cancel(@RequestAttribute Developer developer,
                                                                   @PathVariable String transferId) {
        return Responses.ok("Cancelled", transfers.cancel(developer, transferId));
    }
}

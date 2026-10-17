package com.premisave.developer.controller;

import com.premisave.developer.dto.ApiResponse;
import com.premisave.developer.dto.ApplyBusinessRequest;
import com.premisave.developer.model.Developer;
import com.premisave.developer.service.BusinessService;
import com.premisave.developer.util.Responses;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** The businesses a developer account has applied to onboard. One account can apply for many. */
@RestController("developerBusinessController")
@RequestMapping("/api/v1/businesses")
public class BusinessController {

    private final BusinessService businesses;

    public BusinessController(BusinessService businesses) {
        this.businesses = businesses;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> apply(@RequestAttribute Developer developer,
                                                                  @Valid @RequestBody ApplyBusinessRequest req) {
        return Responses.created("Business added. Complete its application next", businesses.apply(developer, req));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(@RequestAttribute Developer developer) {
        return Responses.ok("Businesses retrieved", businesses.list(developer));
    }

    @GetMapping("/{businessId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(@RequestAttribute Developer developer,
                                                                @PathVariable String businessId) {
        return Responses.ok("Business retrieved", businesses.view(developer, businessId));
    }
}

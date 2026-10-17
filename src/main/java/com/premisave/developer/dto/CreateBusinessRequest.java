package com.premisave.developer.dto;

import com.premisave.developer.enums.MethodType;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Set;

/** The business behind a developer account. Becomes a merchant in the wallet service. */
public record CreateBusinessRequest(
        @NotBlank @Size(max = 160) String businessName,
        @Size(max = 160) String displayName,
        @NotBlank @Size(min = 2, max = 2, message = "must be a 2 letter country code") String country,
        @Email @Size(max = 160) String contactEmail,
        @Size(max = 20) String contactPhone,
        @Size(min = 3, max = 3) String defaultCurrency,
        Set<MethodType> requestedMethods) {
}

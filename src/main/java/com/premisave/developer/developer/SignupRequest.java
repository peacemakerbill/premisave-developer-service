package com.premisave.developer.developer;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * What a new developer gives at sign up. country is the 2 letter code of where they or their company is based.
 * acceptTerms must be true. phoneNumber and companyName are optional, and marketingOptIn defaults to false.
 */
public record SignupRequest(
        @NotBlank @Email @Size(max = 160) String email,
        @NotBlank @Size(min = 2, max = 120) String fullName,
        @NotBlank @Size(max = 128) String password,
        @NotBlank @Size(min = 2, max = 2, message = "must be a 2 letter country code") String country,
        @Size(max = 24) String phoneNumber,
        @Size(max = 160) String companyName,
        @NotNull Boolean acceptTerms,
        Boolean marketingOptIn) {
}

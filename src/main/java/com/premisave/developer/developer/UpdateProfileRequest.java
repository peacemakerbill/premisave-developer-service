package com.premisave.developer.developer;

import jakarta.validation.constraints.Size;

/**
 * Changes to the profile. A field left out stays as it is. phoneNumber and companyName can be cleared by sending an
 * empty string. The email address cannot be changed here.
 */
public record UpdateProfileRequest(
        @Size(min = 2, max = 120) String fullName,
        @Size(min = 2, max = 2, message = "must be a 2 letter country code") String country,
        @Size(max = 24) String phoneNumber,
        @Size(max = 160) String companyName,
        Boolean marketingOptIn) {
}

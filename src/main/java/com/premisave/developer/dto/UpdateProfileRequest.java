package com.premisave.developer.dto;

import jakarta.validation.constraints.Size;

/**
 * Changes to the profile. A field left out stays as it is. The optional ones (middleName, phoneNumber, companyName, the
 * address lines, city, stateOrRegion, postalCode and timezone) are cleared by sending an empty string. firstName,
 * lastName, username and country can be changed but not cleared. The email address cannot be changed here.
 */
public record UpdateProfileRequest(
        @Size(max = 60) String firstName,
        @Size(max = 60) String middleName,
        @Size(max = 60) String lastName,
        @Size(max = 40) String username,
        @Size(min = 2, max = 2, message = "must be a 2 letter country code") String country,
        @Size(max = 24) String phoneNumber,
        @Size(max = 160) String companyName,
        @Size(max = 120) String addressLine1,
        @Size(max = 120) String addressLine2,
        @Size(max = 80) String city,
        @Size(max = 80) String stateOrRegion,
        @Size(max = 16) String postalCode,
        @Size(max = 64) String timezone,
        Boolean marketingOptIn) {
}

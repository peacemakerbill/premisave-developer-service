package com.premisave.developer.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A developer account made by staff. The account is active and its email counts as confirmed, because staff vouch for it.
 * It has no password: with sendInvite (on unless set to false) a link is emailed for the person to choose one. role is
 * DEVELOPER (the default) or ADMIN.
 */
public record AdminCreateDeveloperRequest(
        @NotBlank @Email @Size(max = 160) String email,
        @NotBlank @Size(max = 60) String firstName,
        @Size(max = 60) String middleName,
        @NotBlank @Size(max = 60) String lastName,
        @Size(min = 2, max = 2, message = "must be a 2 letter country code") String country,
        @Size(max = 24) String phoneNumber,
        @Size(max = 160) String companyName,
        @Size(max = 20) String role,
        Boolean sendInvite) {
}

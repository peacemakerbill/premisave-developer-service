package com.premisave.developer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** currentPassword may be left out only by an account that has no password yet (created through a sign in provider). */
public record ChangePasswordRequest(
        @Size(max = 128) String currentPassword,
        @NotBlank @Size(max = 128) String newPassword) {
}

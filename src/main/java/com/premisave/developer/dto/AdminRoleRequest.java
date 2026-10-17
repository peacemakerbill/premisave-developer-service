package com.premisave.developer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** role is DEVELOPER or ADMIN. */
public record AdminRoleRequest(@NotBlank @Size(max = 20) String role) {
}

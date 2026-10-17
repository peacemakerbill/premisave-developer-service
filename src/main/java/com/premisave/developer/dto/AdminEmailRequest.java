package com.premisave.developer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A new email address for an account. verified says whether staff vouch for it: true (the default) counts it as confirmed,
 * false sends a confirmation link to the new address.
 */
public record AdminEmailRequest(@NotBlank @Size(max = 160) String email, Boolean verified) {
}

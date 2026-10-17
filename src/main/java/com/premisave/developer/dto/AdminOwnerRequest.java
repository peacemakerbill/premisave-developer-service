package com.premisave.developer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The developer account an app is handed to. */
public record AdminOwnerRequest(@NotBlank @Size(max = 64) String developerId) {
}

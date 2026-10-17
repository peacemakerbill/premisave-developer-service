package com.premisave.developer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** What the portal sends after the provider returns the person to it. */
public record OAuthCallbackRequest(
        @NotBlank @Size(max = 2048) String code,
        @NotBlank @Size(max = 2048) String state,
        @NotBlank @Size(max = 512) String redirectUri) {
}

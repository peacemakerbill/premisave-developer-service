package com.premisave.developer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What the portal sends to a social sign in endpoint. token is Google's ID token, Facebook's access token or GitHub's
 * one time code. redirectUri is only for GitHub, and only when the GitHub login was started with one.
 */
public record SocialLoginRequest(
        @NotBlank @Size(max = 4096) String token,
        @Size(max = 512) String redirectUri) {
}
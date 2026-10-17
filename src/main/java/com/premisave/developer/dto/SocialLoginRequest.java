package com.premisave.developer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What the portal sends to a social sign in endpoint. token is Google's ID token, Facebook's access token or GitHub's
 * one time code.
 */
public record SocialLoginRequest(@NotBlank @Size(max = 4096) String token) {
}

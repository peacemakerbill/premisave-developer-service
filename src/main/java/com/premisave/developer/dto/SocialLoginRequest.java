package com.premisave.developer.dto;

import jakarta.validation.constraints.Size;

/**
 * What the portal sends to a social sign in endpoint. Send either token or code.
 *
 * <ul>
 *   <li>token is Google's ID token, Facebook's access token or GitHub's one time code, from a browser or phone.</li>
 *   <li>code is an authorization code, for the Linux and Windows apps that only receive a code on a local address.
 *       Google and Facebook also need the redirectUri the code was issued for, and this service trades the code with
 *       its client secret.</li>
 * </ul>
 */
public record SocialLoginRequest(
        @Size(max = 4096) String token,
        @Size(max = 4096) String code,
        @Size(max = 512) String redirectUri) {
}

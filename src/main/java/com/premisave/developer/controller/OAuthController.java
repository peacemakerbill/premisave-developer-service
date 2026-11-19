package com.premisave.developer.controller;

import com.premisave.developer.dto.ApiResponse;
import com.premisave.developer.dto.ClientInfo;
import com.premisave.developer.dto.SocialLoginRequest;
import com.premisave.developer.dto.VerifyEmailRequest;
import com.premisave.developer.service.OAuthService;
import com.premisave.developer.util.Responses;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Sign up and sign in with Google, Facebook and GitHub, for the developer portal. Each provider has its own endpoint.
 * The portal gets the provider's token in the browser and posts it as { "token": "..." }: Google's ID token,
 * Facebook's access token, or GitHub's one time code. The Linux and Windows apps post { "code": "...", "redirectUri": "..." }
 * instead and this service trades the code. First sign in with a new email creates the account.
 *
 * When a provider that does not vouch for its addresses (Facebook) brings an address that already has an account, the
 * answer is 409 ACCOUNT_EXISTS with next CONFIRM_LINK, and the account's mailbox gets a link. The portal posts that
 * link's token to /link/confirm, and after that the provider signs in like any other.
 */
@RestController("developerOAuthController")
@RequestMapping("/api/v1/auth/social")
public class OAuthController {

    private final OAuthService oauth;

    public OAuthController(OAuthService oauth) {
        this.oauth = oauth;
    }

    @GetMapping("/providers")
    public ResponseEntity<ApiResponse<Map<String, Object>>> providers() {
        return Responses.ok("Sign in providers retrieved", oauth.providers());
    }

    @PostMapping("/google")
    public ResponseEntity<ApiResponse<Map<String, Object>>> google(@Valid @RequestBody SocialLoginRequest req,
                                                                   HttpServletRequest http) {
        return signedIn("google", req, http);
    }

    @PostMapping("/facebook")
    public ResponseEntity<ApiResponse<Map<String, Object>>> facebook(@Valid @RequestBody SocialLoginRequest req,
                                                                     HttpServletRequest http) {
        return signedIn("facebook", req, http);
    }

    @PostMapping("/github")
    public ResponseEntity<ApiResponse<Map<String, Object>>> github(@Valid @RequestBody SocialLoginRequest req,
                                                                   HttpServletRequest http) {
        return signedIn("github", req, http);
    }

    /** Public, because the link is opened from an email on a device that may not be signed in. It signs nobody in. */
    @PostMapping("/link/confirm")
    public ResponseEntity<ApiResponse<Map<String, Object>>> confirmLink(@Valid @RequestBody VerifyEmailRequest req) {
        return Responses.ok("Account linked", oauth.confirmLink(req.token()));
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> signedIn(String provider, SocialLoginRequest req,
                                                                      HttpServletRequest http) {
        return Responses.ok("Signed in", oauth.signIn(provider, req.token(), req.code(), req.redirectUri(), ClientInfo.of(http)));
    }
}

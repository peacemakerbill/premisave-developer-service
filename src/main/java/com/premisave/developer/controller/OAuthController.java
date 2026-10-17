package com.premisave.developer.controller;

import com.premisave.developer.dto.ApiResponse;
import com.premisave.developer.dto.ClientInfo;
import com.premisave.developer.dto.SocialLoginRequest;
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
 * Facebook's access token, or GitHub's one time code. First sign in with a new email creates the account.
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

    private ResponseEntity<ApiResponse<Map<String, Object>>> signedIn(String provider, SocialLoginRequest req,
                                                                      HttpServletRequest http) {
        return Responses.ok("Signed in", oauth.signIn(provider, req.token(), req.redirectUri(), ClientInfo.of(http)));
    }
}
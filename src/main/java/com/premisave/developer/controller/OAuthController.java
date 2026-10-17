package com.premisave.developer.controller;

import com.premisave.developer.dto.ApiResponse;
import com.premisave.developer.dto.ClientInfo;
import com.premisave.developer.dto.OAuthCallbackRequest;
import com.premisave.developer.service.OAuthService;
import com.premisave.developer.util.Responses;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Sign up and sign in with Google, Facebook and GitHub, for the developer portal. The portal asks for the provider's
 * address, sends the person there, and when the provider returns them to the portal it posts the code and state back.
 */
@RestController("developerOAuthController")
@RequestMapping("/api/v1/auth/oauth")
public class OAuthController {

    private final OAuthService oauth;

    public OAuthController(OAuthService oauth) {
        this.oauth = oauth;
    }

    @GetMapping("/providers")
    public ResponseEntity<ApiResponse<Map<String, Object>>> providers() {
        return Responses.ok("Sign in providers retrieved", oauth.providers());
    }

    @GetMapping("/{provider}/authorize")
    public ResponseEntity<ApiResponse<Map<String, Object>>> authorize(@PathVariable String provider,
                                                                      @RequestParam String redirectUri) {
        return Responses.ok("Authorization address created", oauth.authorize(provider, redirectUri));
    }

    @PostMapping("/{provider}/callback")
    public ResponseEntity<ApiResponse<Map<String, Object>>> callback(@PathVariable String provider,
                                                                     @Valid @RequestBody OAuthCallbackRequest req,
                                                                     HttpServletRequest http) {
        return Responses.ok("Signed in", oauth.callback(provider, req.code(), req.state(), req.redirectUri(),
                ClientInfo.of(http)));
    }
}

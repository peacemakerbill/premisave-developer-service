package com.premisave.developer.controller;

import com.premisave.developer.dto.ApiResponse;
import com.premisave.developer.dto.SocialLoginRequest;
import com.premisave.developer.model.Developer;
import com.premisave.developer.service.OAuthService;
import com.premisave.developer.util.Responses;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The sign in providers connected to the signed in developer's account. Connecting takes the same body as a social sign
 * in (a token, or a code and redirectUri). The developer is already signed in, so no email link is needed.
 */
@RestController("developerConnectionsController")
@RequestMapping("/api/v1/me/connections")
public class ConnectionsController {

    private final OAuthService oauth;

    public ConnectionsController(OAuthService oauth) {
        this.oauth = oauth;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(@RequestAttribute Developer developer) {
        return Responses.ok("Connections retrieved", oauth.connections(developer));
    }

    @PostMapping("/{provider}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> connect(@RequestAttribute Developer developer,
                                                                    @PathVariable String provider,
                                                                    @Valid @RequestBody SocialLoginRequest req) {
        return Responses.ok("Connected", oauth.connect(developer, provider, req.token(), req.code(), req.redirectUri()));
    }

    @DeleteMapping("/{provider}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> disconnect(@RequestAttribute Developer developer,
                                                                       @PathVariable String provider) {
        return Responses.ok("Disconnected", oauth.disconnect(developer, provider));
    }
}

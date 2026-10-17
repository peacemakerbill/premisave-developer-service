package com.premisave.developer.controller;

import com.premisave.developer.dto.ApiResponse;
import com.premisave.developer.dto.ClientInfo;
import com.premisave.developer.dto.EmailRequest;
import com.premisave.developer.dto.LoginRequest;
import com.premisave.developer.dto.ResetPasswordRequest;
import com.premisave.developer.dto.SignupRequest;
import com.premisave.developer.dto.VerifyEmailRequest;
import com.premisave.developer.service.AccountEmailService;
import com.premisave.developer.service.DeveloperService;
import com.premisave.developer.util.Responses;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Open to anyone: create an account, sign in, confirm an address, and recover a forgotten password. */
@RestController("developerAuthController")
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final DeveloperService developers;
    private final AccountEmailService accountEmail;

    public AuthController(DeveloperService developers, AccountEmailService accountEmail) {
        this.developers = developers;
        this.accountEmail = accountEmail;
    }

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<Map<String, Object>>> signup(@Valid @RequestBody SignupRequest req) {
        Map<String, Object> result = developers.signup(req);
        return Responses.created((String) result.get("message"), result);
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<Map<String, Object>>> login(@Valid @RequestBody LoginRequest req,
                                                                  HttpServletRequest http) {
        return Responses.ok("Signed in", developers.login(req, ClientInfo.of(http)));
    }

    @PostMapping("/verify-email")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyEmail(@Valid @RequestBody VerifyEmailRequest req) {
        return Responses.ok("Email address confirmed", accountEmail.verifyEmail(req.token()));
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resendVerification(@Valid @RequestBody EmailRequest req) {
        accountEmail.resendVerification(req.email());
        return Responses.ok("If this address has an account that is not confirmed yet, a new link is on its way", Map.of());
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Map<String, Object>>> forgotPassword(@Valid @RequestBody EmailRequest req) {
        accountEmail.forgotPassword(req.email());
        return Responses.ok("If this address has an account, a reset link is on its way", Map.of());
    }

    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        accountEmail.resetPassword(req.token(), req.newPassword());
        return Responses.ok("Password changed. Sign in with the new password", Map.of());
    }
}

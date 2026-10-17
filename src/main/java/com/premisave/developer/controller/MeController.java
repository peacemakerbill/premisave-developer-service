package com.premisave.developer.controller;

import com.premisave.developer.config.DeveloperProperties;
import com.premisave.developer.dto.ApiResponse;
import com.premisave.developer.dto.ChangePasswordRequest;
import com.premisave.developer.dto.CreateBusinessRequest;
import com.premisave.developer.dto.UpdateProfileRequest;
import com.premisave.developer.model.Developer;
import com.premisave.developer.service.AccountEmailService;
import com.premisave.developer.service.DeveloperService;
import com.premisave.developer.service.MerchantLinkService;
import com.premisave.developer.service.ProfilePictureService;
import com.premisave.developer.util.Responses;
import com.premisave.developer.view.DeveloperViews;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** The signed in developer, their business, and the test merchant numbers. */
@RestController("developerMeController")
@RequestMapping("/api/v1")
public class MeController {

    private final DeveloperService developers;
    private final MerchantLinkService merchants;
    private final AccountEmailService accountEmail;
    private final ProfilePictureService pictures;
    private final DeveloperProperties props;

    public MeController(DeveloperService developers, MerchantLinkService merchants, AccountEmailService accountEmail,
                        ProfilePictureService pictures, DeveloperProperties props) {
        this.developers = developers;
        this.merchants = merchants;
        this.accountEmail = accountEmail;
        this.pictures = pictures;
        this.props = props;
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<Map<String, Object>>> me(@RequestAttribute Developer developer) {
        return Responses.ok("Account retrieved", DeveloperViews.developer(developer));
    }

    @PutMapping("/me")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateProfile(@RequestAttribute Developer developer,
                                                                          @Valid @RequestBody UpdateProfileRequest req) {
        return Responses.ok("Profile updated", developers.updateProfile(developer, req));
    }

    /** The body is the image itself (JPEG, PNG or WebP), not form data. */
    @PutMapping("/me/profile-picture")
    public ResponseEntity<ApiResponse<Map<String, Object>>> uploadPicture(@RequestAttribute Developer developer,
                                                                          HttpServletRequest request) throws IOException {
        return Responses.ok("Profile picture updated", pictures.upload(developer, request.getInputStream()));
    }

    @DeleteMapping("/me/profile-picture")
    public ResponseEntity<ApiResponse<Map<String, Object>>> removePicture(@RequestAttribute Developer developer) {
        return Responses.ok("Profile picture removed", pictures.remove(developer));
    }

    @GetMapping("/me/username-available")
    public ResponseEntity<ApiResponse<Map<String, Object>>> usernameAvailable(@RequestAttribute Developer developer,
                                                                              @RequestParam String username) {
        return Responses.ok("Username checked", developers.checkUsername(developer, username));
    }

    @PutMapping("/me/password")
    public ResponseEntity<ApiResponse<Map<String, Object>>> password(@RequestAttribute Developer developer,
                                                                      @Valid @RequestBody ChangePasswordRequest req) {
        return Responses.ok("Password changed", developers.changePassword(developer, req));
    }

    @PostMapping("/me/logout")
    public ResponseEntity<ApiResponse<Map<String, Object>>> logout(
            @RequestAttribute Developer developer,
            @RequestAttribute com.premisave.developer.security.JwtService.Claims tokenClaims) {
        developers.logout(developer, tokenClaims);
        return Responses.ok("Signed out", Map.of());
    }

    @PostMapping("/me/logout-all")
    public ResponseEntity<ApiResponse<Map<String, Object>>> logoutAll(@RequestAttribute Developer developer) {
        developers.logoutEverywhere(developer);
        return Responses.ok("Signed out everywhere", Map.of());
    }

    @PostMapping("/me/resend-verification")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resendVerification(
            @RequestAttribute Developer developer) {
        return Responses.ok("Confirmation link sent", accountEmail.resendVerification(developer));
    }

    @PostMapping("/merchant")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createBusiness(@RequestAttribute Developer developer,
                                                                           @Valid @RequestBody CreateBusinessRequest req) {
        return Responses.created("Business created", merchants.create(developer, req));
    }

    @GetMapping("/merchant")
    public ResponseEntity<ApiResponse<Map<String, Object>>> business(@RequestAttribute Developer developer) {
        return Responses.ok("Business retrieved", merchants.view(developer));
    }

    @GetMapping("/meta/test-merchant-numbers")
    public ResponseEntity<ApiResponse<Map<String, Object>>> testNumbers() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("testMerchantNumbers", props.apps().testMerchantNumbers());
        m.put("default", props.apps().defaultTestNumber());
        return Responses.ok("Test merchant numbers retrieved", m);
    }
}

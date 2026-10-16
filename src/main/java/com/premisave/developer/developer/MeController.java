package com.premisave.developer.developer;

import com.premisave.developer.auth.AccountEmailService;
import com.premisave.developer.common.ApiResponse;
import com.premisave.developer.common.Responses;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** The signed in developer, their business, and the test merchant numbers. */
@RestController("developerMeController")
@RequestMapping("/api/v1")
public class MeController {

    private final DeveloperService developers;
    private final MerchantLinkService merchants;
    private final AccountEmailService accountEmail;
    private final com.premisave.developer.config.DeveloperProperties props;

    public MeController(DeveloperService developers, MerchantLinkService merchants, AccountEmailService accountEmail,
                        com.premisave.developer.config.DeveloperProperties props) {
        this.developers = developers;
        this.merchants = merchants;
        this.accountEmail = accountEmail;
        this.props = props;
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<Map<String, Object>>> me(@RequestAttribute("developer") Developer developer) {
        return Responses.ok("Account retrieved", DeveloperViews.developer(developer));
    }

    @PutMapping("/me/password")
    public ResponseEntity<ApiResponse<Map<String, Object>>> password(@RequestAttribute("developer") Developer developer,
                                                                      @Valid @RequestBody ChangePasswordRequest req) {
        return Responses.ok("Password changed", developers.changePassword(developer, req));
    }

    @PostMapping("/me/logout")
    public ResponseEntity<ApiResponse<Map<String, Object>>> logout(
            @RequestAttribute("developer") Developer developer,
            @RequestAttribute("tokenClaims") com.premisave.developer.security.JwtService.Claims claims) {
        developers.logout(developer, claims);
        return Responses.ok("Signed out", Map.of());
    }

    @PostMapping("/me/logout-all")
    public ResponseEntity<ApiResponse<Map<String, Object>>> logoutAll(@RequestAttribute("developer") Developer developer) {
        developers.logoutEverywhere(developer);
        return Responses.ok("Signed out everywhere", Map.of());
    }

    @PostMapping("/me/resend-verification")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resendVerification(
            @RequestAttribute("developer") Developer developer) {
        return Responses.ok("Confirmation link sent", accountEmail.resendVerification(developer));
    }

    @PostMapping("/merchant")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createBusiness(@RequestAttribute("developer") Developer developer,
                                                                           @Valid @RequestBody CreateBusinessRequest req) {
        return Responses.created("Business created", merchants.create(developer, req));
    }

    @GetMapping("/merchant")
    public ResponseEntity<ApiResponse<Map<String, Object>>> business(@RequestAttribute("developer") Developer developer) {
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

package com.premisave.developer.key;

import com.premisave.developer.app.AppService;
import com.premisave.developer.app.DeveloperApp;
import com.premisave.developer.common.ApiResponse;
import com.premisave.developer.common.Responses;
import com.premisave.developer.developer.Developer;
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

import java.util.LinkedHashMap;
import java.util.Map;

/** API keys of one app. A secret appears in the response that creates or rotates a key, and never again. */
@RestController("developerKeyController")
@RequestMapping("/api/v1/apps/{appId}/keys")
public class KeyController {

    private final AppService apps;
    private final ApiKeyService keys;

    public KeyController(AppService apps, ApiKeyService keys) {
        this.apps = apps;
        this.keys = keys;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(@RequestAttribute("developer") Developer developer,
                                                                 @PathVariable String appId) {
        DeveloperApp app = apps.get(developer, appId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("keys", keys.list(app.getAppId()).stream().map(KeyViews::key).toList());
        return Responses.ok("Keys retrieved", m);
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@RequestAttribute("developer") Developer developer,
                                                                   @PathVariable String appId,
                                                                   @Valid @RequestBody(required = false) CreateKeyRequest req) {
        DeveloperApp app = apps.get(developer, appId);
        return Responses.created("Key created", KeyViews.issued(keys.issue(app, req == null ? null : req.label())));
    }

    @PostMapping("/{key}/rotate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> rotate(@RequestAttribute("developer") Developer developer,
                                                                   @PathVariable String appId, @PathVariable String key) {
        DeveloperApp app = apps.get(developer, appId);
        return Responses.created("Key rotated", KeyViews.issued(keys.rotate(app, key)));
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> revoke(@RequestAttribute("developer") Developer developer,
                                                                   @PathVariable String appId, @PathVariable String key) {
        DeveloperApp app = apps.get(developer, appId);
        return Responses.ok("Key revoked", KeyViews.key(keys.revoke(app, key)));
    }
}

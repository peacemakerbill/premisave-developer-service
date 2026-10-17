package com.premisave.developer.client;

import com.premisave.developer.dto.MerchantInfo;
import com.premisave.developer.dto.WalletInfo;
import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.exception.DeveloperException;
import com.premisave.developer.model.DeveloperApp;
import com.premisave.developer.model.Limits;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The provisioning calls the developer service makes to the wallet service. */
@Component("developerWalletApi")
public class WalletApi {

    private static final String BASE = "/internal/commercial/v1";

    private final WalletClient client;
    private final JsonMapper json;

    public WalletApi(WalletClient client, JsonMapper json) {
        this.client = client;
        this.json = json;
    }

    public Map<String, Object> createMerchant(Map<String, Object> body) {
        return call("POST", BASE + "/merchants", body);
    }

    /** Every business of a developer account, oldest first, as { "businesses": [ merchant views ] }. */
    public Map<String, Object> listMerchants(String developerAccountId) {
        String q = URLEncoder.encode(developerAccountId, StandardCharsets.UTF_8);
        return call("GET", BASE + "/merchants?developerAccountId=" + q, null);
    }

    /** One business by its PRM merchant number, as the wallet service shows it. Unknown numbers are MERCHANT_NOT_FOUND. */
    public Map<String, Object> getMerchantViewByNumber(String merchantNumber) {
        String n = URLEncoder.encode(merchantNumber, StandardCharsets.UTF_8);
        return call("GET", BASE + "/merchants/by-number/" + n, null);
    }

    public MerchantInfo getMerchant(String merchantId) {
        return MerchantInfo.from(call("GET", BASE + "/merchants/" + merchantId, null));
    }

    public Map<String, Object> getMerchantView(String merchantId) {
        return call("GET", BASE + "/merchants/" + merchantId, null);
    }

    public WalletInfo resolveWallet(String reference) {
        String q = URLEncoder.encode(reference, StandardCharsets.UTF_8);
        return WalletInfo.from(call("GET", BASE + "/wallets/resolve?reference=" + q, null));
    }

    /** Tells the wallet service the current settings of an app. */
    public void pushAppContext(DeveloperApp app) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchantId", app.getMerchantId());
        body.put("environment", app.getEnvironment().headerValue());
        body.put("status", app.getStatus().name());
        body.put("enabledMethods", app.getEnabledMethods() == null ? null
                : app.getEnabledMethods().stream().map(Enum::name).sorted().toList());
        body.put("limits", limitsBody(app.getLimits()));
        body.put("version", app.getVersion());
        call("PUT", BASE + "/apps/" + app.getAppId() + "/context", body);
    }

    private static Map<String, Object> limitsBody(Limits l) {
        if (l == null || l.isEmpty()) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("singleTransactionUsd", l.getSingleTransactionUsd());
        m.put("dailyVolumeUsd", l.getDailyVolumeUsd());
        m.put("monthlyVolumeUsd", l.getMonthlyVolumeUsd());
        m.put("dailyPayoutCount", l.getDailyPayoutCount());
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(String method, String path, Map<String, Object> body) {
        byte[] payload = body == null ? null : json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = body == null ? Map.of() : Map.of("Content-Type", "application/json");
        WalletClient.Reply reply = client.send(client.config().baseUrl(), method, path, headers, payload, null);
        Map<String, Object> envelope;
        try {
            envelope = json.readValue(reply.text(), Map.class);
        } catch (RuntimeException e) {
            throw new DeveloperException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "The wallet service answered " + reply.status() + " with something this service cannot read");
        }
        Object data = envelope == null ? null : envelope.get("data");
        if (reply.status() / 100 == 2) {
            return data instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        }
        throw translate(reply.status(), envelope);
    }

    /** Turns the wallet service's error envelope into this service's exception, keeping its code and extra fields. */
    @SuppressWarnings("unchecked")
    static DeveloperException translate(int status, Map<String, Object> envelope) {
        String message = envelope != null && envelope.get("message") != null ? String.valueOf(envelope.get("message"))
                : "The wallet service refused the request";
        Map<String, Object> data = envelope != null && envelope.get("data") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        ErrorCode code = codeOf(String.valueOf(data.get("code")), status);
        DeveloperException ex = new DeveloperException(code, message);
        data.forEach((k, v) -> {
            if (k.equals("details") && v instanceof List<?> d) {
                ex.withDetails((List<Map<String, String>>) d);
            } else if (!k.equals("code") && !k.equals("requestId")) {
                ex.with(k, v);
            }
        });
        return ex;
    }

    private static ErrorCode codeOf(String walletCode, int status) {
        try {
            return ErrorCode.valueOf(walletCode);
        } catch (IllegalArgumentException e) {
            if (status == 404) return ErrorCode.NOT_FOUND;
            if (status == 409) return ErrorCode.INVALID_STATE;
            if (status >= 500) return ErrorCode.UPSTREAM_UNAVAILABLE;
            return ErrorCode.VALIDATION_FAILED;
        }
    }
}

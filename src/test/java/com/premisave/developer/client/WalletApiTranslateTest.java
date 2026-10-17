package com.premisave.developer.client;

import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.exception.DeveloperException;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class WalletApiTranslateTest {

    private static Map<String, Object> envelope(String message, Map<String, Object> data) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("success", false);
        e.put("message", message);
        e.put("data", data);
        return e;
    }

    @Test
    void aKnownWalletCodeKeepsItsNameMessageAndExtras() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", "DUPLICATE_REQUEST");
        data.put("requestId", "req_1");
        data.put("merchantId", "mer_9");
        DeveloperException e = WalletApi.translate(409, envelope("A merchant already exists", data));
        assertEquals(ErrorCode.DUPLICATE_REQUEST, e.code());
        assertEquals("A merchant already exists", e.getMessage());
        assertEquals("mer_9", e.extra().get("merchantId"));
        assertFalse(e.extra().containsKey("code"));
        assertFalse(e.extra().containsKey("requestId"));
    }

    @Test
    void validationDetailsAreKept() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", "VALIDATION_FAILED");
        data.put("details", List.of(Map.of("field", "country", "issue", "bad")));
        DeveloperException e = WalletApi.translate(400, envelope("Request validation failed", data));
        assertEquals(ErrorCode.VALIDATION_FAILED, e.code());
        assertEquals("country", e.details().get(0).get("field"));
    }

    @Test
    void walletOnlyCodesFallBackByStatus() {
        Map<String, Object> d = Map.of("code", "SOME_WALLET_ONLY_CODE");
        assertEquals(ErrorCode.NOT_FOUND, WalletApi.translate(404, envelope("m", d)).code());
        assertEquals(ErrorCode.INVALID_STATE, WalletApi.translate(409, envelope("m", d)).code());
        assertEquals(ErrorCode.UPSTREAM_UNAVAILABLE, WalletApi.translate(500, envelope("m", d)).code());
        assertEquals(ErrorCode.VALIDATION_FAILED, WalletApi.translate(422, envelope("m", d)).code());
    }

    @Test
    void anUnreadableEnvelopeStillGivesAnError() {
        DeveloperException e = WalletApi.translate(502, null);
        assertEquals(ErrorCode.UPSTREAM_UNAVAILABLE, e.code());
        assertNull(e.details());
    }
}

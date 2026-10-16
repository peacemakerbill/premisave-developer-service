package com.premisave.developer.wallet;

import com.premisave.developer.common.DeveloperException;
import com.premisave.developer.enums.Environment;
import com.premisave.developer.enums.MethodType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MerchantInfoTest {

    private static Map<String, Object> view() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("merchantId", "mer_1");
        m.put("merchantNumber", "PRM-0000000001");
        m.put("sandbox", Map.of("status", "ACTIVE", "walletId", "w1"));
        m.put("live", Map.of("status", "PENDING_APPROVAL", "walletId", "w2"));
        m.put("enabledMethods", List.of("CARD", "MOBILE_MONEY", "SOMETHING_NEW"));
        Map<String, Object> live = new LinkedHashMap<>();
        live.put("singleTransactionUsd", "500.00");
        live.put("dailyVolumeUsd", null);
        live.put("monthlyVolumeUsd", "20000");
        live.put("dailyPayoutCount", 10);
        Map<String, Object> sandbox = new LinkedHashMap<>();
        sandbox.put("singleTransactionUsd", null);
        m.put("limits", Map.of("sandbox", sandbox, "live", live));
        return m;
    }

    @Test
    void readsIdentityStatusMethodsAndLimits() {
        MerchantInfo i = MerchantInfo.from(view());
        assertEquals("mer_1", i.merchantId());
        assertEquals("PRM-0000000001", i.merchantNumber());
        assertEquals("ACTIVE", i.statusFor(Environment.SANDBOX));
        assertEquals("PENDING_APPROVAL", i.statusFor(Environment.LIVE));
        assertEquals(2, i.enabledMethods().size());
        assertTrue(i.enabledMethods().contains(MethodType.CARD));
        assertEquals(new BigDecimal("500.00"), i.limitsFor(Environment.LIVE).getSingleTransactionUsd());
        assertNull(i.limitsFor(Environment.LIVE).getDailyVolumeUsd());
        assertEquals(10, i.limitsFor(Environment.LIVE).getDailyPayoutCount());
        assertTrue(i.limitsFor(Environment.SANDBOX).isEmpty());
    }

    @Test
    void missingPartsDegradeInsteadOfFailing() {
        MerchantInfo i = MerchantInfo.from(Map.of("merchantId", "mer_2"));
        assertEquals("UNKNOWN", i.statusFor(Environment.LIVE));
        assertTrue(i.enabledMethods().isEmpty());
        assertTrue(i.limitsFor(Environment.LIVE).isEmpty());
    }

    @Test
    void aReplyWithoutAMerchantIdIsRejected() {
        assertThrows(DeveloperException.class, () -> MerchantInfo.from(Map.of()));
        assertThrows(DeveloperException.class, () -> MerchantInfo.from(null));
    }

    @Test
    void walletInfoReadsTheFrozenFlag() {
        assertEquals(true, WalletInfo.from(Map.of("walletId", "w1", "accountNumber", "a@b.c", "frozen", true)).frozen());
        assertEquals(false, WalletInfo.from(Map.of("walletId", "w1")).frozen());
    }
}

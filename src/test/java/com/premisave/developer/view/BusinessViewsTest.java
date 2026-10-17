package com.premisave.developer.view;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The business as a developer sees it, and the next step it points to. */
class BusinessViewsTest {

    private static Map<String, Object> merchant(String applicationStatus, boolean ready, int missing, String live) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("merchantId", "mer_1");
        m.put("merchantNumber", "PRM-0000000001");
        m.put("businessName", "Acme Ltd");
        m.put("displayName", "Acme");
        m.put("country", "KE");
        m.put("defaultCurrency", "KES");
        m.put("enabledMethods", List.of("CARD"));
        m.put("walletId", "internal");
        m.put("sandbox", Map.of("status", "ACTIVE"));
        m.put("live", Map.of("status", live));
        m.put("limits", Map.of("sandbox", Map.of("perTransaction", 1), "live", Map.of("perTransaction", 2)));
        if (applicationStatus != null) {
            m.put("onboarding", Map.of("applicationId", "a1", "status", applicationStatus, "readyToSubmit", ready,
                    "missing", missing == 0 ? List.of() : java.util.Collections.nCopies(missing, "x")));
        }
        m.put("createdAt", "2026-10-17T08:00:00Z");
        return m;
    }

    private static String step(Map<String, Object> business) {
        return String.valueOf(((Map<?, ?>) business.get("nextStep")).get("code"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void theBusinessUsesDeveloperWordsAndHidesInternals() {
        Map<String, Object> b = BusinessViews.business(merchant("DRAFT", false, 2, "NOT_ENABLED"));
        assertEquals("mer_1", b.get("businessId"));
        assertEquals("Acme Ltd", b.get("name"));
        assertEquals("Acme", b.get("tradingName"));
        assertEquals("KES", b.get("currency"));
        assertEquals(List.of("CARD"), b.get("paymentMethods"));
        assertEquals(Map.of("sandbox", "ACTIVE", "production", "NOT_ENABLED"), b.get("status"));
        assertEquals(2, ((Map<String, Object>) ((Map<String, Object>) b.get("limits")).get("production")).get("perTransaction"));
        assertFalse(b.containsKey("walletId"));
        assertFalse(b.containsKey("merchantId"));
        assertFalse(b.containsKey("live"));
    }

    @Test
    void nextStepFollowsTheApplication() {
        assertEquals("START_APPLICATION", step(BusinessViews.business(merchant(null, false, 0, "NOT_ENABLED"))));
        assertEquals("COMPLETE_APPLICATION", step(BusinessViews.business(merchant("DRAFT", false, 3, "NOT_ENABLED"))));
        assertEquals("SUBMIT_APPLICATION", step(BusinessViews.business(merchant("DRAFT", true, 0, "NOT_ENABLED"))));
        assertEquals("FIX_APPLICATION", step(BusinessViews.business(merchant("RETURNED", false, 1, "NOT_ENABLED"))));
        assertEquals("RESUBMIT_APPLICATION", step(BusinessViews.business(merchant("RETURNED", true, 0, "NOT_ENABLED"))));
        assertEquals("WAIT_FOR_REVIEW", step(BusinessViews.business(merchant("SUBMITTED", true, 0, "NOT_ENABLED"))));
        assertEquals("WAIT_FOR_REVIEW", step(BusinessViews.business(merchant("IN_REVIEW", true, 0, "NOT_ENABLED"))));
        assertEquals("CREATE_APP", step(BusinessViews.business(merchant("APPROVED", true, 0, "ACTIVE"))));
        assertEquals("CONTACT_SUPPORT", step(BusinessViews.business(merchant("REJECTED", false, 0, "NOT_ENABLED"))));
        assertEquals("CHECK_APPLICATION", step(BusinessViews.business(merchant("WEIRD", false, 0, "NOT_ENABLED"))));
    }

    @Test
    void aSuspendedOrClosedProductionOverridesTheApplication() {
        assertEquals("PRODUCTION_SUSPENDED", step(BusinessViews.business(merchant("APPROVED", true, 0, "SUSPENDED"))));
        assertEquals("PRODUCTION_CLOSED", step(BusinessViews.business(merchant("APPROVED", true, 0, "CLOSED"))));
    }

    @Test
    void theMessageCountsWhatIsMissing() {
        Map<String, Object> one = BusinessViews.business(merchant("DRAFT", false, 1, "NOT_ENABLED"));
        Map<String, Object> many = BusinessViews.business(merchant("DRAFT", false, 4, "NOT_ENABLED"));
        assertTrue(String.valueOf(((Map<?, ?>) one.get("nextStep")).get("message")).contains("1 more item "));
        assertTrue(String.valueOf(((Map<?, ?>) many.get("nextStep")).get("message")).contains("4 more items"));
    }

    @Test
    void aMerchantWithoutLimitsOrApplicationStillRenders() {
        Map<String, Object> m = merchant(null, false, 0, "NOT_ENABLED");
        m.remove("limits");
        Map<String, Object> b = BusinessViews.business(m);
        assertFalse(b.containsKey("limits"));
        assertNull(b.get("application"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void anOwnerSeesTheApplicationButNotTheAddressesOfTheWalletOwner() {
        Map<String, Object> m = merchant("SUBMITTED", true, 0, "NOT_ENABLED");
        Map<String, Object> onboarding = new LinkedHashMap<>((Map<String, Object>) m.get("onboarding"));
        onboarding.put("walletAccount", "owner@acme.test");
        onboarding.put("contactEmail", "contact@acme.test");
        m.put("onboarding", onboarding);
        Map<String, Object> b = BusinessViews.business(m);
        assertEquals("OWNER", b.get("access"));
        Map<String, Object> application = (Map<String, Object>) b.get("application");
        assertEquals("SUBMITTED", application.get("status"));
        assertFalse(application.containsKey("walletAccount"));
        assertFalse(application.containsKey("contactEmail"));
        assertEquals("owner@acme.test", onboarding.get("walletAccount"));
    }

    @Test
    void aSharedBusinessHidesTheApplicationAndPointsToAnApp() {
        Map<String, Object> b = BusinessViews.business(merchant("SUBMITTED", true, 0, "ACTIVE"), true);
        assertEquals("SHARED", b.get("access"));
        assertNull(b.get("application"));
        assertEquals("CREATE_APP", step(b));
        assertEquals("PRM-0000000001", b.get("merchantNumber"));
    }

    @Test
    void aSharedBusinessWhoseProductionIsSuspendedSaysSo() {
        assertEquals("PRODUCTION_SUSPENDED", step(BusinessViews.business(merchant("APPROVED", true, 0, "SUSPENDED"), true)));
    }
}

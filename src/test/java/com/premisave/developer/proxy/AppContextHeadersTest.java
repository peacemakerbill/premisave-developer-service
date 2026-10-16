package com.premisave.developer.proxy;

import com.premisave.developer.app.DeveloperApp;
import com.premisave.developer.enums.AppStatus;
import com.premisave.developer.enums.Environment;
import com.premisave.developer.enums.MethodType;
import com.premisave.developer.model.Limits;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The exact header values are a contract with the wallet service. The same literals are asserted in the wallet service's
 * AppContextHeadersTest, so a change on one side fails a test on the other.
 */
class AppContextHeadersTest {

    private static DeveloperApp app() {
        DeveloperApp a = new DeveloperApp();
        a.setAppId("app_abc");
        a.setMerchantId("mer_xyz");
        a.setEnvironment(Environment.LIVE);
        a.setStatus(AppStatus.ACTIVE);
        a.setVersion(1760601600000L);
        return a;
    }

    @Test
    void anAppThatNarrowsNothingSendsOnlyIdentityStatusAndVersion() {
        Map<String, String> h = AppContextHeaders.of(app());
        assertEquals("mer_xyz", h.get("X-Merchant-Id"));
        assertEquals("app_abc", h.get("X-App-Id"));
        assertEquals("production", h.get("X-App-Environment"));
        assertEquals("ACTIVE", h.get("X-App-Status"));
        assertEquals("1760601600000", h.get("X-App-Version"));
        assertFalse(h.containsKey("X-App-Methods"));
        assertFalse(h.containsKey("X-App-Limits"));
    }

    @Test
    void sandboxIsNamedSandbox() {
        DeveloperApp a = app();
        a.setEnvironment(Environment.SANDBOX);
        assertEquals("sandbox", AppContextHeaders.of(a).get("X-App-Environment"));
    }

    @Test
    void methodsAreSortedAndAnEmptySetIsNone() {
        DeveloperApp a = app();
        a.setEnabledMethods(EnumSet.of(MethodType.MOBILE_MONEY, MethodType.CARD));
        assertEquals("CARD,MOBILE_MONEY", AppContextHeaders.of(a).get("X-App-Methods"));
        a.setEnabledMethods(EnumSet.noneOf(MethodType.class));
        assertEquals("none", AppContextHeaders.of(a).get("X-App-Methods"));
    }

    @Test
    void limitsListOnlyWhatIsSet() {
        DeveloperApp a = app();
        a.setLimits(new Limits(new BigDecimal("100.00"), null, new BigDecimal("20000"), 5));
        assertEquals("singleTransactionUsd=100.00,monthlyVolumeUsd=20000,dailyPayoutCount=5", AppContextHeaders.of(a).get("X-App-Limits"));
        a.setLimits(new Limits());
        assertFalse(AppContextHeaders.of(a).containsKey("X-App-Limits"));
    }

    @Test
    void suspendedIsSent() {
        DeveloperApp a = app();
        a.setStatus(AppStatus.SUSPENDED);
        assertEquals("SUSPENDED", AppContextHeaders.of(a).get("X-App-Status"));
    }
}

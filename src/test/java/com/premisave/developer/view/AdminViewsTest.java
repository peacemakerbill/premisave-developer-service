package com.premisave.developer.view;

import com.premisave.developer.enums.DeveloperRole;
import com.premisave.developer.enums.TransferKind;
import com.premisave.developer.enums.TransferStatus;
import com.premisave.developer.model.AdminAction;
import com.premisave.developer.model.Developer;
import com.premisave.developer.model.TransferRequest;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** What staff see: whole addresses and every id, never a secret. */
class AdminViewsTest {

    private static final Instant NOW = Instant.parse("2026-10-18T08:00:00Z");

    @Test
    void anAccountShowsRoleLockoutAndHowEachBusinessCameToIt() {
        Developer d = new Developer();
        d.setDeveloperId("dev_1");
        d.setEmail("jane@acme.test");
        d.setPasswordHash("pbkdf2-sha256$secret");
        d.setRole(DeveloperRole.ADMIN);
        d.addBusiness("mer_1");
        d.addSharedBusiness("mer_2");
        d.setFailedLogins(2);

        Map<String, Object> v = AdminViews.developer(d);
        assertEquals("jane@acme.test", v.get("email"));
        assertEquals("ADMIN", v.get("role"));
        assertEquals(List.of("mer_1"), v.get("appliedBusinessIds"));
        assertEquals(List.of("mer_2"), v.get("sharedBusinessIds"));
        assertEquals(2, v.get("failedLogins"));
        assertNull(v.get("lockedUntil"));
        assertFalse(v.containsKey("passwordHash"));
        assertFalse(v.toString().contains("secret"));
    }

    @Test
    void anAccountWithNoSharedBusinessesShowsAnEmptyList() {
        Developer d = new Developer();
        d.setSharedMerchantIds(null);
        assertEquals(List.of(), AdminViews.developer(d).get("sharedBusinessIds"));
    }

    @Test
    void aTransferShowsBothPartiesAndNeverTheCode() {
        TransferRequest t = new TransferRequest();
        t.setId("trf_1");
        t.setKind(TransferKind.APP_TRANSFER);
        t.setStatus(TransferStatus.PENDING);
        t.setRequesterId("dev_1");
        t.setFromEmail("jane@acme.test");
        t.setToEmail("john@acme.test");
        t.setAppId("app_1");
        t.setOtpHash("hash-value");
        t.setExpiresAt(NOW.plusSeconds(900));
        t.setCreatedAt(NOW);

        Map<String, Object> v = AdminViews.transfer(t, NOW);
        assertEquals("trf_1", v.get("transferId"));
        assertEquals("APP_TRANSFER", v.get("type"));
        assertEquals("jane@acme.test", v.get("fromEmail"));
        assertEquals("john@acme.test", v.get("toEmail"));
        assertEquals("app_1", v.get("appId"));
        assertFalse(v.toString().contains("hash-value"));
        assertFalse(v.containsKey("otpHash"));
    }

    @Test
    void anAuditEntryShowsWhoDidWhat() {
        AdminAction a = new AdminAction();
        a.setId("act_1");
        a.setActorId("adm_1");
        a.setActorEmail("ann@premisave.test");
        a.setAction("APP_DELETED");
        a.setTargetType("APP");
        a.setTargetId("app_1");
        a.setDetail("x");
        a.setCreatedAt(NOW);

        Map<String, Object> v = AdminViews.action(a);
        assertEquals("act_1", v.get("id"));
        assertEquals("adm_1", v.get("actorId"));
        assertEquals("ann@premisave.test", v.get("actorEmail"));
        assertEquals("APP_DELETED", v.get("action"));
        assertEquals("APP", v.get("targetType"));
        assertEquals("app_1", v.get("targetId"));
        assertEquals("x", v.get("detail"));
        assertEquals("2026-10-18T08:00:00Z", v.get("createdAt"));
    }
}

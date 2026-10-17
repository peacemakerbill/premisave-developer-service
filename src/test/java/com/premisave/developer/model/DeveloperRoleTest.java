package com.premisave.developer.model;

import com.premisave.developer.config.DeveloperProperties.Security;
import com.premisave.developer.enums.DeveloperRole;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Roles on the account, and the list of addresses that become administrators. */
class DeveloperRoleTest {

    @Test
    void anAccountWithNoRoleIsAnOrdinaryDeveloper() {
        Developer d = new Developer();
        assertEquals(DeveloperRole.DEVELOPER, d.getRole());
        assertFalse(d.isAdmin());
        d.setRole(null);
        assertEquals(DeveloperRole.DEVELOPER, d.getRole());
    }

    @Test
    void anAdministratorIsRecognised() {
        Developer d = new Developer();
        d.setRole(DeveloperRole.ADMIN);
        assertTrue(d.isAdmin());
        d.setRole(DeveloperRole.DEVELOPER);
        assertFalse(d.isAdmin());
    }

    @Test
    void aSharedBusinessCanBeTakenBackButAnAppliedOneCannot() {
        Developer d = new Developer();
        d.addBusiness("mer_own");
        d.addSharedBusiness("mer_shared");
        assertFalse(d.removeSharedBusiness("mer_own"));
        assertTrue(d.ownsBusiness("mer_own"));
        assertTrue(d.removeSharedBusiness("mer_shared"));
        assertFalse(d.ownsBusiness("mer_shared"));
        assertFalse(d.removeSharedBusiness("mer_shared"));
        assertFalse(new Developer().removeSharedBusiness("mer_x"));
    }

    @Test
    void theAddressListIsTrimmedLowerCasedAndFreeOfBlanksAndRepeats() {
        Security s = new Security(5, 15, 10, 210_000, Arrays.asList(" Boss@Premisave.test ", "", null, "boss@premisave.test", "ann@premisave.test"));
        assertEquals(List.of("boss@premisave.test", "ann@premisave.test"), s.adminEmails());
    }

    @Test
    void anAddressIsMatchedIgnoringCaseAndSpaces() {
        Security s = new Security(5, 15, 10, 210_000, List.of("boss@premisave.test"));
        assertTrue(s.isAdminEmail("BOSS@premisave.test"));
        assertTrue(s.isAdminEmail("  boss@premisave.test "));
        assertFalse(s.isAdminEmail("boss@premisave.test.evil.com"));
        assertFalse(s.isAdminEmail("xboss@premisave.test"));
        assertFalse(s.isAdminEmail(null));
    }

    @Test
    void noListMeansNoOneIsPromoted() {
        assertTrue(new Security(null, null, null, null, null).adminEmails().isEmpty());
        assertFalse(new Security(null, null, null, null, null).isAdminEmail("boss@premisave.test"));
    }
}

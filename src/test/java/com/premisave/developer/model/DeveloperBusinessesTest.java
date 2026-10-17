package com.premisave.developer.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The businesses of an account, including the single business that older accounts stored in merchantId. */
class DeveloperBusinessesTest {

    @Test
    void aNewAccountHasNone() {
        Developer d = new Developer();
        assertTrue(d.businessIds().isEmpty());
        assertFalse(d.ownsBusiness("mer_1"));
    }

    @Test
    void businessesKeepTheOrderTheyWereAddedInWithoutDuplicates() {
        Developer d = new Developer();
        d.addBusiness("mer_2");
        d.addBusiness("mer_1");
        d.addBusiness("mer_2");
        assertEquals(List.of("mer_2", "mer_1"), d.businessIds());
        assertTrue(d.ownsBusiness("mer_1"));
    }

    @Test
    void theOldSingleBusinessCountsAndComesFirst() {
        Developer d = new Developer();
        d.setMerchantId("mer_old");
        d.addBusiness("mer_new");
        d.addBusiness("mer_old");
        assertEquals(List.of("mer_old", "mer_new"), d.businessIds());
        assertTrue(d.ownsBusiness("mer_old"));
    }

    @Test
    void theReturnedListCannotBeUsedToChangeTheAccount() {
        Developer d = new Developer();
        d.addBusiness("mer_1");
        List<String> ids = d.businessIds();
        assertThrows(UnsupportedOperationException.class, () -> ids.add("mer_x"));
        assertEquals(List.of("mer_1"), d.businessIds());
    }

    @Test
    void aBlankIdIsNotABusiness() {
        Developer d = new Developer();
        assertFalse(d.ownsBusiness(null));
        assertFalse(d.ownsBusiness(""));
    }

    @Test
    void aSharedBusinessCountsAsUseButNotAsApplied() {
        Developer d = new Developer();
        d.addBusiness("mer_1");
        d.addSharedBusiness("mer_9");
        assertTrue(d.ownsBusiness("mer_9"));
        assertTrue(d.appliedFor("mer_1"));
        assertFalse(d.appliedFor("mer_9"));
        assertEquals(List.of("mer_1"), d.appliedBusinessIds());
        assertEquals(List.of("mer_1", "mer_9"), d.businessIds());
    }

    @Test
    void sharingABusinessTheAccountAlreadyHasChangesNothing() {
        Developer d = new Developer();
        d.addBusiness("mer_1");
        d.addSharedBusiness("mer_1");
        d.addSharedBusiness("mer_9");
        d.addSharedBusiness("mer_9");
        assertEquals(List.of("mer_9"), d.getSharedMerchantIds());
        assertEquals(List.of("mer_1", "mer_9"), d.businessIds());
    }

    @Test
    void theOldSingleBusinessIsAppliedFor() {
        Developer d = new Developer();
        d.setMerchantId("mer_old");
        assertTrue(d.appliedFor("mer_old"));
        assertFalse(d.appliedFor(null));
    }
}

package com.premisave.developer.service;

import com.premisave.developer.TestRepos;
import com.premisave.developer.model.AdminAction;
import com.premisave.developer.view.PageViews;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The trail of admin changes: what is written, how it is searched, and that a failure to write never blocks the change. */
class AdminAuditTest {

    private final TestRepos.AdminActions repo = new TestRepos.AdminActions();
    private Instant now = Instant.parse("2026-10-18T08:00:00Z");
    private final Clock clock = new Clock() {
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };
    private final AdminAudit audit = new AdminAudit(repo.repo, clock);
    private final AdminActor ann = new AdminActor("adm_1", "ann@premisave.test");
    private final AdminActor bob = new AdminActor("adm_2", "bob@premisave.test");

    private List<String> ids(String actor, String target, String action) {
        return audit.search(actor, target, action, PageViews.pageable(0, 20)).getContent().stream().map(AdminAction::getAction).toList();
    }

    @Test
    void anEntryHoldsWhoDidWhatToWhat() {
        audit.record(ann, "APP_DELETED", "APP", "app_1", "Shop of dev_1");
        AdminAction a = repo.store.get(0);
        assertNotNull(a.getId());
        assertEquals("adm_1", a.getActorId());
        assertEquals("ann@premisave.test", a.getActorEmail());
        assertEquals("APP_DELETED", a.getAction());
        assertEquals("APP", a.getTargetType());
        assertEquals("app_1", a.getTargetId());
        assertEquals("Shop of dev_1", a.getDetail());
        assertEquals(now, a.getCreatedAt());
    }

    @Test
    void theInternalKeyIsRecordedAsItsOwnActor() {
        audit.record(AdminActor.INTERNAL, "DEVELOPER_DISABLED", "DEVELOPER", "dev_1", "status is now DISABLED");
        assertEquals("internal-key", repo.store.get(0).getActorId());
        assertNull(repo.store.get(0).getActorEmail());
    }

    @Test
    void entriesAreFoundByActorTargetAndPartOfTheAction() {
        audit.record(ann, "APP_DELETED", "APP", "app_1", "x");
        now = now.plusSeconds(60);
        audit.record(bob, "APP_SUSPENDED", "APP", "app_2", "x");
        now = now.plusSeconds(60);
        audit.record(bob, "DEVELOPER_DISABLED", "DEVELOPER", "dev_1", "x");

        assertEquals(List.of("DEVELOPER_DISABLED", "APP_SUSPENDED", "APP_DELETED"), ids(null, null, null));
        assertEquals(List.of("DEVELOPER_DISABLED", "APP_SUSPENDED"), ids("adm_2", null, null));
        assertEquals(List.of("APP_SUSPENDED"), ids(null, "app_2", null));
        assertEquals(List.of("APP_SUSPENDED", "APP_DELETED"), ids(null, null, "APP_"));
        assertEquals(List.of("APP_DELETED"), ids(null, null, "_DELETED"));
        assertEquals(List.of(), ids("adm_", null, null));
        assertEquals(List.of(), ids(null, null, ".*X"));
    }

    @Test
    void aFailureToWriteIsSwallowedSoTheChangeStandsAndIsLoggedInstead() {
        repo.failToWrite = true;
        audit.record(ann, "APP_DELETED", "APP", "app_1", "x");
        assertEquals(0, repo.store.size());
    }
}

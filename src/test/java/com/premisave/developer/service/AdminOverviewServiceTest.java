package com.premisave.developer.service;

import com.premisave.developer.enums.AppStatus;
import com.premisave.developer.enums.Environment;
import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.enums.TransferKind;
import com.premisave.developer.enums.TransferStatus;
import com.premisave.developer.exception.DeveloperException;
import com.premisave.developer.model.Developer;
import com.premisave.developer.model.TransferRequest;
import com.premisave.developer.enums.DeveloperStatus;
import com.premisave.developer.view.PageViews;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Platform numbers, and staff control of app transfers and business claims. */
class AdminOverviewServiceTest {

    private final AdminFixture f = new AdminFixture();
    private final Developer boss = f.admin("adm_1", "boss@premisave.test");
    private final AdminActor bossActor = AdminActor.of(boss);

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> stats, String name) {
        return (Map<String, Object>) stats.get(name);
    }

    @Test
    void theNumbersCountDevelopersAppsAndTransfers() {
        f.developer("dev_1", "jane@acme.test");
        f.developer("dev_2", "john@acme.test").setStatus(DeveloperStatus.DISABLED);
        f.developer("dev_3", "mary@acme.test").setStatus(DeveloperStatus.PENDING);
        f.app("app_1", "dev_1", "mer_1", Environment.SANDBOX, "A", 1);
        f.app("app_2", "dev_1", "mer_1", Environment.LIVE, "B", 2);
        f.app("app_3", "dev_1", "mer_1", Environment.LIVE, "C", 3).setStatus(AppStatus.SUSPENDED);
        f.transfer("trf_1", TransferKind.APP_TRANSFER, TransferStatus.PENDING, "dev_1", "dev_2", 1);
        f.transfer("trf_2", TransferKind.BUSINESS_CLAIM, TransferStatus.COMPLETED, "dev_2", null, 2);

        Map<String, Object> stats = f.overview.stats();
        Map<String, Object> devs = section(stats, "developers");
        assertEquals(4L, devs.get("total"));
        assertEquals(2L, devs.get("active"));
        assertEquals(1L, devs.get("pending"));
        assertEquals(1L, devs.get("disabled"));
        assertEquals(1L, devs.get("administrators"));
        Map<String, Object> apps = section(stats, "apps");
        assertEquals(3L, apps.get("total"));
        assertEquals(1L, apps.get("sandbox"));
        assertEquals(2L, apps.get("production"));
        assertEquals(2L, apps.get("active"));
        assertEquals(1L, apps.get("suspended"));
        Map<String, Object> transfers = section(stats, "transfers");
        assertEquals(1L, transfers.get("pending"));
        assertEquals(1L, transfers.get("completed"));
        assertNotNull(stats.get("generatedAt"));
    }

    private List<String> found(TransferStatus status, TransferKind kind, String developerId) {
        return f.overview.searchTransfers(status, kind, developerId, PageViews.pageable(0, 20)).getContent().stream()
                .map(TransferRequest::getId).toList();
    }

    private void seedTransfers() {
        f.transfer("trf_1", TransferKind.APP_TRANSFER, TransferStatus.PENDING, "dev_1", "dev_2", 1);
        f.transfer("trf_2", TransferKind.BUSINESS_CLAIM, TransferStatus.COMPLETED, "dev_2", null, 2);
        f.transfer("trf_3", TransferKind.APP_TRANSFER, TransferStatus.CANCELLED, "dev_3", "dev_1", 3);
    }

    @Test
    void transfersAreSearchedNewestFirstByStatusKindAndEitherParty() {
        seedTransfers();
        assertEquals(List.of("trf_3", "trf_2", "trf_1"), found(null, null, null));
        assertEquals(List.of("trf_1"), found(TransferStatus.PENDING, null, null));
        assertEquals(List.of("trf_3", "trf_1"), found(null, TransferKind.APP_TRANSFER, null));
        assertEquals(List.of("trf_3", "trf_1"), found(null, null, "dev_1"));
        assertEquals(List.of("trf_2"), found(null, null, "dev_2").stream().filter(id -> !id.equals("trf_1")).toList());
        assertEquals(List.of(), found(TransferStatus.REJECTED, null, null));
    }

    @Test
    void aTransferCanBeReadAndAnUnknownOneIsNotFound() {
        seedTransfers();
        assertEquals("trf_1", f.overview.getTransfer("trf_1").getId());
        assertEquals(ErrorCode.TRANSFER_NOT_FOUND, assertThrows(DeveloperException.class, () -> f.overview.getTransfer("trf_none")).code());
        assertEquals(ErrorCode.TRANSFER_NOT_FOUND, assertThrows(DeveloperException.class, () -> f.overview.getTransfer(null)).code());
    }

    @Test
    void aWaitingRequestCanBeCancelled() {
        seedTransfers();
        TransferRequest t = f.overview.cancelTransfer(bossActor, "trf_1");
        assertEquals(TransferStatus.CANCELLED, t.getStatus());
        assertNotNull(t.getCompletedAt());
        assertNotNull(t.getPurgeAt());
        assertEquals(List.of("TRANSFER_CANCELLED trf_1"), f.actions.summary());
    }

    @Test
    void aFinishedRequestIsNotCancelledAgainAndADoneOneCannotBe() {
        seedTransfers();
        f.overview.cancelTransfer(bossActor, "trf_3");
        assertEquals(TransferStatus.CANCELLED, f.transfers.store.get("trf_3").getStatus());
        assertEquals(0, f.actions.store.size());
        assertEquals(ErrorCode.INVALID_STATE,
                assertThrows(DeveloperException.class, () -> f.overview.cancelTransfer(bossActor, "trf_2")).code());
        assertNull(f.transfers.store.get("trf_2").getPurgeAt());
    }
}

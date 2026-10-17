package com.premisave.developer.service;

import com.premisave.developer.enums.AppStatus;
import com.premisave.developer.enums.DeveloperRole;
import com.premisave.developer.enums.DeveloperStatus;
import com.premisave.developer.enums.Environment;
import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.enums.TransferKind;
import com.premisave.developer.enums.TransferStatus;
import com.premisave.developer.exception.DeveloperException;
import com.premisave.developer.model.TransferRequest;
import com.premisave.developer.repository.DeveloperAppRepository;
import com.premisave.developer.repository.DeveloperRepository;
import com.premisave.developer.repository.TransferRequestRepository;
import com.premisave.developer.util.SearchText;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Platform wide numbers for staff, and staff control of app transfers and business claims. */
@Service("developerAdminOverviewService")
public class AdminOverviewService {

    /** How long a finished request is kept after staff cancel it, matching what the developer side keeps. */
    private static final Duration KEEP_CANCELLED = Duration.ofDays(7);

    private final DeveloperRepository developers;
    private final DeveloperAppRepository apps;
    private final TransferRequestRepository transfers;
    private final AdminAudit audit;
    private final Clock clock;

    @Autowired
    public AdminOverviewService(DeveloperRepository developers, DeveloperAppRepository apps, TransferRequestRepository transfers,
                                AdminAudit audit) {
        this(developers, apps, transfers, audit, Clock.systemUTC());
    }

    public AdminOverviewService(DeveloperRepository developers, DeveloperAppRepository apps, TransferRequestRepository transfers,
                                AdminAudit audit, Clock clock) {
        this.developers = developers;
        this.apps = apps;
        this.transfers = transfers;
        this.audit = audit;
        this.clock = clock;
    }

    /** Counts of developers, apps and open transfers. */
    public Map<String, Object> stats() {
        Map<String, Object> dev = new LinkedHashMap<>();
        dev.put("total", developers.count());
        for (DeveloperStatus s : DeveloperStatus.values()) dev.put(s.name().toLowerCase(java.util.Locale.ROOT), developers.countByStatus(s));
        dev.put("administrators", developers.countByRole(DeveloperRole.ADMIN));

        Map<String, Object> app = new LinkedHashMap<>();
        app.put("total", apps.count());
        app.put("sandbox", apps.countByEnvironment(Environment.SANDBOX));
        app.put("production", apps.countByEnvironment(Environment.LIVE));
        app.put("active", apps.countByStatus(AppStatus.ACTIVE));
        app.put("suspended", apps.countByStatus(AppStatus.SUSPENDED));

        Map<String, Object> tr = new LinkedHashMap<>();
        tr.put("pending", transfers.countByStatus(TransferStatus.PENDING));
        tr.put("completed", transfers.countByStatus(TransferStatus.COMPLETED));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("developers", dev);
        out.put("apps", app);
        out.put("transfers", tr);
        out.put("generatedAt", clock.instant().toString());
        return out;
    }

    /** Newest first. Each filter is optional; developerId matches either side of the request. */
    public Page<TransferRequest> searchTransfers(TransferStatus status, TransferKind kind, String developerId, Pageable pageable) {
        return transfers.adminSearch(status == null ? SearchText.ANY : SearchText.exact(status.name()),
                kind == null ? SearchText.ANY : SearchText.exact(kind.name()), SearchText.exact(developerId), pageable);
    }

    public TransferRequest getTransfer(String transferId) {
        return transfers.findById(transferId == null ? "" : transferId)
                .orElseThrow(() -> new DeveloperException(ErrorCode.TRANSFER_NOT_FOUND, "Transfer not found"));
    }

    /** Closes a request that is still waiting for its code. A request that is already done stays as it is. */
    public TransferRequest cancelTransfer(AdminActor actor, String transferId) {
        TransferRequest t = getTransfer(transferId);
        if (t.getStatus() == TransferStatus.COMPLETED) {
            throw DeveloperException.invalidState("This request is already done and cannot be cancelled");
        }
        if (t.getStatus() == TransferStatus.PENDING) {
            Instant now = clock.instant();
            t.setStatus(TransferStatus.CANCELLED);
            t.setCompletedAt(now);
            t.setPurgeAt(now.plus(KEEP_CANCELLED));
            transfers.save(t);
            audit.record(actor, "TRANSFER_CANCELLED", "TRANSFER", transferId, "cancelled by staff");
        }
        return t;
    }
}

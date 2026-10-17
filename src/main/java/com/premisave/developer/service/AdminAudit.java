package com.premisave.developer.service;

import com.premisave.developer.model.AdminAction;
import com.premisave.developer.repository.AdminActionRepository;
import com.premisave.developer.util.Ids;
import com.premisave.developer.util.SearchText;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Clock;

/** The trail of changes made through the admin API. Recording never blocks the change it describes. */
@Service("developerAdminAudit")
public class AdminAudit {

    private final AdminActionRepository actions;
    private final Clock clock;

    @Autowired
    public AdminAudit(AdminActionRepository actions) {
        this(actions, Clock.systemUTC());
    }

    public AdminAudit(AdminActionRepository actions, Clock clock) {
        this.actions = actions;
        this.clock = clock;
    }

    /** Writes one entry. The detail must be plain words about the change and never a secret. */
    public void record(AdminActor actor, String action, String targetType, String targetId, String detail) {
        AdminAction a = new AdminAction();
        a.setId(Ids.next("act"));
        a.setActorId(actor.id());
        a.setActorEmail(actor.email());
        a.setAction(action);
        a.setTargetType(targetType);
        a.setTargetId(targetId);
        a.setDetail(detail);
        a.setCreatedAt(clock.instant());
        try {
            actions.insert(a);
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(AdminAudit.class).warn("Could not write the audit entry {} for {}: {}", action, targetId, e.toString());
        }
    }

    /** Newest first. actor and target must match exactly; action matches anywhere in the name, for example APP_ or _DELETED. */
    public Page<AdminAction> search(String actorId, String targetId, String action, Pageable pageable) {
        return actions.search(SearchText.exact(actorId), SearchText.exact(targetId), SearchText.contains(action), pageable);
    }
}

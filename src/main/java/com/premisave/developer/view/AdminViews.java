package com.premisave.developer.view;

import com.premisave.developer.enums.TransferKind;
import com.premisave.developer.model.AdminAction;
import com.premisave.developer.model.Developer;
import com.premisave.developer.model.TransferRequest;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** What the admin API shows. Staff see whole email addresses and every id; no secret is ever included. */
public final class AdminViews {

    private AdminViews() {}

    /** The account as its owner sees it, plus what staff need: lockout, session version and which businesses are shared. */
    public static Map<String, Object> developer(Developer d) {
        Map<String, Object> m = new LinkedHashMap<>(DeveloperViews.developer(d));
        m.put("appliedBusinessIds", d.appliedBusinessIds());
        m.put("sharedBusinessIds", d.getSharedMerchantIds() == null ? java.util.List.of() : java.util.List.copyOf(d.getSharedMerchantIds()));
        m.put("failedLogins", d.getFailedLogins());
        m.put("lockedUntil", iso(d.getLockedUntil()));
        m.put("updatedAt", iso(d.getUpdatedAt()));
        return m;
    }

    public static Map<String, Object> transfer(TransferRequest t, Instant now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("transferId", t.getId());
        m.put("type", t.getKind() == TransferKind.APP_TRANSFER ? "APP_TRANSFER" : "BUSINESS_CLAIM");
        m.put("status", TransferViews.statusOf(t, now));
        m.put("requesterId", t.getRequesterId());
        m.put("fromEmail", t.getFromEmail());
        m.put("toDeveloperId", t.getToDeveloperId());
        m.put("toEmail", t.getToEmail());
        m.put("appId", t.getAppId());
        m.put("appName", t.getAppName());
        m.put("businessId", t.getMerchantId());
        m.put("businessName", t.getBusinessName());
        m.put("merchantNumber", t.getMerchantNumber());
        m.put("codeSentTo", t.getOtpSentTo());
        m.put("attempts", t.getAttempts());
        m.put("expiresAt", iso(t.getExpiresAt()));
        m.put("createdAt", iso(t.getCreatedAt()));
        m.put("completedAt", iso(t.getCompletedAt()));
        return m;
    }

    public static Map<String, Object> action(AdminAction a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("actorId", a.getActorId());
        m.put("actorEmail", a.getActorEmail());
        m.put("action", a.getAction());
        m.put("targetType", a.getTargetType());
        m.put("targetId", a.getTargetId());
        m.put("detail", a.getDetail());
        m.put("createdAt", iso(a.getCreatedAt()));
        return m;
    }

    private static String iso(Instant t) {
        return t == null ? null : t.toString();
    }
}

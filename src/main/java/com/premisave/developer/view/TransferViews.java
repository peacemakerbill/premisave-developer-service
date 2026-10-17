package com.premisave.developer.view;

import com.premisave.developer.enums.TransferKind;
import com.premisave.developer.enums.TransferStatus;
import com.premisave.developer.model.TransferRequest;
import com.premisave.developer.util.MaskedEmail;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How a transfer or claim looks to a developer. The account that started it sees where the code went and how many tries
 * are left. The account an app is moving to sees only that it is on its way, with the sender's address hidden in part.
 */
public final class TransferViews {

    /** How many wrong codes close a request. */
    public static final int MAX_ATTEMPTS = 5;

    private TransferViews() {}

    public static Map<String, Object> view(TransferRequest t, String viewerId, Instant now) {
        boolean requester = t.getRequesterId().equals(viewerId);
        String status = statusOf(t, now);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("transferId", t.getId());
        m.put("type", t.getKind() == TransferKind.APP_TRANSFER ? "APP_TRANSFER" : "BUSINESS_CLAIM");
        m.put("direction", requester ? "OUTGOING" : "INCOMING");
        m.put("status", status);
        if (t.getAppId() != null) {
            Map<String, Object> app = new LinkedHashMap<>();
            app.put("appId", t.getAppId());
            app.put("name", t.getAppName());
            m.put("app", app);
        }
        Map<String, Object> business = new LinkedHashMap<>();
        business.put("businessId", t.getMerchantId());
        business.put("name", t.getBusinessName());
        business.put("merchantNumber", t.getMerchantNumber());
        m.put("business", business);
        if (t.getKind() == TransferKind.APP_TRANSFER) {
            if (requester) {
                m.put("to", MaskedEmail.of(t.getToEmail()));
            } else {
                m.put("from", MaskedEmail.of(t.getFromEmail()));
            }
        }
        if (requester) {
            m.put("codeSentTo", t.getOtpSentTo());
            if ("PENDING".equals(status)) m.put("attemptsLeft", Math.max(0, MAX_ATTEMPTS - t.getAttempts()));
        }
        m.put("expiresAt", iso(t.getExpiresAt()));
        m.put("createdAt", iso(t.getCreatedAt()));
        m.put("completedAt", iso(t.getCompletedAt()));
        return m;
    }

    /** PENDING turns into EXPIRED once the time is up, without the row having to change. */
    public static String statusOf(TransferRequest t, Instant now) {
        if (t.getStatus() == TransferStatus.PENDING && t.getExpiresAt() != null && !now.isBefore(t.getExpiresAt())) {
            return "EXPIRED";
        }
        return t.getStatus().name();
    }

    private static String iso(Instant t) {
        return t == null ? null : t.toString();
    }
}

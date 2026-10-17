package com.premisave.developer.view;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The developer facing shape of a business. The wallet service calls it a merchant and says "live"; here it is a
 * business and "production", like apps. Internal ids (wallets, fee plan) are left out, and a nextStep says what the
 * owner has to do now, so a client does not have to work that out from the application status.
 */
public final class BusinessViews {

    private BusinessViews() {}

    /** A business the caller applied for. */
    public static Map<String, Object> business(Map<String, Object> merchant) {
        return business(merchant, false);
    }

    /**
     * shared is true for a business the caller was given use of instead of applying for it. Its application is not shown,
     * because it belongs to the account that applied, and it is approved by definition, so the next step is an app.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> business(Map<String, Object> merchant, boolean shared) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("businessId", merchant.get("merchantId"));
        m.put("access", shared ? "SHARED" : "OWNER");
        m.put("merchantNumber", merchant.get("merchantNumber"));
        m.put("name", merchant.get("businessName"));
        m.put("tradingName", merchant.get("displayName"));
        m.put("country", merchant.get("country"));
        m.put("currency", merchant.get("defaultCurrency"));
        m.put("paymentMethods", merchant.get("enabledMethods"));

        String sandbox = statusOf(merchant.get("sandbox"));
        String production = statusOf(merchant.get("live"));
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("sandbox", sandbox);
        status.put("production", production);
        m.put("status", status);

        Object limits = merchant.get("limits");
        if (limits instanceof Map<?, ?> l) {
            Map<String, Object> shown = new LinkedHashMap<>();
            shown.put("sandbox", ((Map<String, Object>) l).get("sandbox"));
            shown.put("production", ((Map<String, Object>) l).get("live"));
            m.put("limits", shown);
        }

        Map<String, Object> application = shared ? null
                : merchant.get("onboarding") instanceof Map<?, ?> o ? withoutInternals((Map<String, Object>) o) : null;
        m.put("application", application);
        m.put("nextStep", nextStep(shared ? Map.of("status", "APPROVED") : application, production));
        m.put("createdAt", merchant.get("createdAt"));
        return m;
    }

    /** The application summary without the addresses the wallet service uses to reach the business's owner. */
    private static Map<String, Object> withoutInternals(Map<String, Object> summary) {
        Map<String, Object> out = new LinkedHashMap<>(summary);
        out.remove("walletAccount");
        out.remove("contactEmail");
        return out;
    }

    /** What the owner should do next, as a stable code and a sentence a person can read. */
    static Map<String, Object> nextStep(Map<String, Object> application, String production) {
        if ("SUSPENDED".equals(production) || "CLOSED".equals(production)) {
            return step("PRODUCTION_" + production, production.equals("CLOSED")
                    ? "Production is closed for this business. Contact support."
                    : "Production is suspended for this business. Contact support.");
        }
        if (application == null) {
            return step("START_APPLICATION", "Start the application for this business to go live.");
        }
        String state = String.valueOf(application.get("status"));
        boolean ready = Boolean.TRUE.equals(application.get("readyToSubmit"));
        int missing = application.get("missing") instanceof List<?> l ? l.size() : 0;
        return switch (state) {
            case "DRAFT" -> ready
                    ? step("SUBMIT_APPLICATION", "The application is complete. Submit it for review.")
                    : step("COMPLETE_APPLICATION", missing > 0
                            ? "Fill in " + missing + " more " + (missing == 1 ? "item" : "items") + " on the application, then submit it."
                            : "Complete the application, then submit it.");
            case "RETURNED" -> ready
                    ? step("RESUBMIT_APPLICATION", "Your corrections are in. Submit the application again.")
                    : step("FIX_APPLICATION", "We sent the application back. Fix the items we listed, then submit it again.");
            case "SUBMITTED" -> step("WAIT_FOR_REVIEW", "The application is waiting for a reviewer.");
            case "IN_REVIEW" -> step("WAIT_FOR_REVIEW", "A reviewer is looking at the application.");
            case "APPROVED" -> step("CREATE_APP", "Approved. You can create production apps for this business.");
            case "REJECTED" -> step("CONTACT_SUPPORT", "The application was declined. Contact support to have it looked at again.");
            default -> step("CHECK_APPLICATION", "Open the application to see where it stands.");
        };
    }

    private static Map<String, Object> step(String code, String message) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("code", code);
        s.put("message", message);
        return s;
    }

    private static String statusOf(Object environment) {
        return environment instanceof Map<?, ?> m && m.get("status") != null ? String.valueOf(m.get("status")) : "UNKNOWN";
    }
}

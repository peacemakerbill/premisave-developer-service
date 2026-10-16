package com.premisave.developer.proxy;

import com.premisave.developer.app.DeveloperApp;
import com.premisave.developer.model.Limits;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The headers that tell the wallet service who is calling and what the app is allowed to do. The wallet service
 * trusts them because only this service holds the internal API key.
 *
 *   X-Merchant-Id, X-App-Id            identity
 *   X-App-Environment                  production or sandbox
 *   X-App-Status                       ACTIVE or SUSPENDED
 *   X-App-Methods                      comma separated method names, or "none"; absent means same as the merchant
 *   X-App-Limits                       name=value pairs separated by commas; absent means no app limits
 *   X-App-Version                      the app's change counter
 */
public final class AppContextHeaders {

    private AppContextHeaders() {}

    public static Map<String, String> of(DeveloperApp app) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-Merchant-Id", app.getMerchantId());
        h.put("X-App-Id", app.getAppId());
        h.put("X-App-Environment", app.getEnvironment().headerValue());
        h.put("X-App-Status", app.getStatus().name());
        String methods = methods(app);
        if (methods != null) h.put("X-App-Methods", methods);
        String limits = limits(app.getLimits());
        if (limits != null) h.put("X-App-Limits", limits);
        h.put("X-App-Version", String.valueOf(app.getVersion()));
        return h;
    }

    static String methods(DeveloperApp app) {
        if (app.getEnabledMethods() == null) return null;
        if (app.getEnabledMethods().isEmpty()) return "none";
        return app.getEnabledMethods().stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    static String limits(Limits l) {
        if (l == null || l.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        append(sb, "singleTransactionUsd", l.getSingleTransactionUsd() == null ? null : l.getSingleTransactionUsd().toPlainString());
        append(sb, "dailyVolumeUsd", l.getDailyVolumeUsd() == null ? null : l.getDailyVolumeUsd().toPlainString());
        append(sb, "monthlyVolumeUsd", l.getMonthlyVolumeUsd() == null ? null : l.getMonthlyVolumeUsd().toPlainString());
        append(sb, "dailyPayoutCount", l.getDailyPayoutCount() == null ? null : l.getDailyPayoutCount().toString());
        return sb.toString();
    }

    private static void append(StringBuilder sb, String name, String value) {
        if (value == null) return;
        if (sb.length() > 0) sb.append(',');
        sb.append(name).append('=').append(value);
    }
}

package com.premisave.developer.app;

import com.premisave.developer.model.Limits;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AppViews {

    private AppViews() {}

    public static Map<String, Object> app(DeveloperApp a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("appId", a.getAppId());
        m.put("merchantId", a.getMerchantId());
        m.put("environment", a.getEnvironment().headerValue());
        m.put("name", a.getName());
        m.put("description", a.getDescription());
        m.put("merchantNumber", a.getMerchantNumber());
        if (a.getPremisaveWalletId() != null) {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("walletId", a.getPremisaveWalletId());
            w.put("accountNumber", a.getPremisaveWalletAccount());
            m.put("premisaveWallet", w);
        }
        m.put("status", a.getStatus().name());
        if (a.getSuspendedBy() != null) {
            m.put("suspendedBy", a.getSuspendedBy().name());
            m.put("suspensionReason", a.getSuspensionReason());
        }
        m.put("enabledMethods", a.getEnabledMethods() == null ? null : a.getEnabledMethods().stream().map(Enum::name).sorted().toList());
        m.put("limits", a.getLimits() == null ? null : limits(a.getLimits()));
        m.put("callbackUrl", a.getCallbackUrl());
        m.put("createdAt", iso(a.getCreatedAt()));
        m.put("updatedAt", iso(a.getUpdatedAt()));
        return m;
    }

    static Map<String, Object> limits(Limits l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("singleTransactionUsd", l.getSingleTransactionUsd() == null ? null : l.getSingleTransactionUsd().toPlainString());
        m.put("dailyVolumeUsd", l.getDailyVolumeUsd() == null ? null : l.getDailyVolumeUsd().toPlainString());
        m.put("monthlyVolumeUsd", l.getMonthlyVolumeUsd() == null ? null : l.getMonthlyVolumeUsd().toPlainString());
        m.put("dailyPayoutCount", l.getDailyPayoutCount());
        return m;
    }

    static String iso(Instant t) {
        return t == null ? null : t.toString();
    }
}

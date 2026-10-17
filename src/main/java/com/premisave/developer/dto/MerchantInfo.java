package com.premisave.developer.dto;

import com.premisave.developer.enums.Environment;
import com.premisave.developer.enums.ErrorCode;
import com.premisave.developer.enums.MethodType;
import com.premisave.developer.exception.DeveloperException;
import com.premisave.developer.model.Limits;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** The parts of a wallet service merchant that the app rules need. */
public record MerchantInfo(String merchantId, String merchantNumber, String sandboxStatus, String liveStatus,
                           Set<MethodType> enabledMethods, Limits sandboxLimits, Limits liveLimits,
                           Map<String, Object> raw) {

    public String statusFor(Environment env) {
        return env == Environment.LIVE ? liveStatus : sandboxStatus;
    }

    public Limits limitsFor(Environment env) {
        return env == Environment.LIVE ? liveLimits : sandboxLimits;
    }

    @SuppressWarnings("unchecked")
    public static MerchantInfo from(Map<String, Object> data) {
        if (data == null || data.get("merchantId") == null) {
            throw new DeveloperException(ErrorCode.UPSTREAM_UNAVAILABLE, "The wallet service returned an unexpected merchant");
        }
        Set<MethodType> methods = EnumSet.noneOf(MethodType.class);
        if (data.get("enabledMethods") instanceof Iterable<?> it) {
            for (Object o : it) {
                try {
                    methods.add(MethodType.valueOf(String.valueOf(o)));
                } catch (IllegalArgumentException ignored) {
                    // A method this service does not know yet cannot be narrowed to, so it is left out.
                }
            }
        }
        Map<String, Object> limits = data.get("limits") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        return new MerchantInfo(
                String.valueOf(data.get("merchantId")),
                data.get("merchantNumber") == null ? null : String.valueOf(data.get("merchantNumber")),
                statusOf(data.get("sandbox")),
                statusOf(data.get("live")),
                methods,
                limitsOf(limits.get("sandbox")),
                limitsOf(limits.get("live")),
                data);
    }

    private static String statusOf(Object env) {
        return env instanceof Map<?, ?> m && m.get("status") != null ? String.valueOf(m.get("status")) : "UNKNOWN";
    }

    private static Limits limitsOf(Object o) {
        Limits l = new Limits();
        if (o instanceof Map<?, ?> m) {
            l.setSingleTransactionUsd(decimal(m.get("singleTransactionUsd")));
            l.setDailyVolumeUsd(decimal(m.get("dailyVolumeUsd")));
            l.setMonthlyVolumeUsd(decimal(m.get("monthlyVolumeUsd")));
            if (m.get("dailyPayoutCount") instanceof Number n) l.setDailyPayoutCount(n.intValue());
        }
        return l;
    }

    private static BigDecimal decimal(Object o) {
        if (o == null) return null;
        try {
            return new BigDecimal(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

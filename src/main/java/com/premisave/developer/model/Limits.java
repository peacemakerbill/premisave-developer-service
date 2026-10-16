package com.premisave.developer.model;

import java.math.BigDecimal;

/** Optional ceilings in USD. A null value means "no limit set at this level". */
public class Limits {

    private BigDecimal singleTransactionUsd;
    private BigDecimal dailyVolumeUsd;
    private BigDecimal monthlyVolumeUsd;
    private Integer dailyPayoutCount;

    public Limits() {}

    public Limits(BigDecimal single, BigDecimal daily, BigDecimal monthly, Integer payouts) {
        this.singleTransactionUsd = single;
        this.dailyVolumeUsd = daily;
        this.monthlyVolumeUsd = monthly;
        this.dailyPayoutCount = payouts;
    }

    public BigDecimal getSingleTransactionUsd() { return singleTransactionUsd; }
    public void setSingleTransactionUsd(BigDecimal v) { this.singleTransactionUsd = v; }
    public BigDecimal getDailyVolumeUsd() { return dailyVolumeUsd; }
    public void setDailyVolumeUsd(BigDecimal v) { this.dailyVolumeUsd = v; }
    public BigDecimal getMonthlyVolumeUsd() { return monthlyVolumeUsd; }
    public void setMonthlyVolumeUsd(BigDecimal v) { this.monthlyVolumeUsd = v; }
    public Integer getDailyPayoutCount() { return dailyPayoutCount; }
    public void setDailyPayoutCount(Integer v) { this.dailyPayoutCount = v; }

    public boolean isEmpty() {
        return singleTransactionUsd == null && dailyVolumeUsd == null
                && monthlyVolumeUsd == null && dailyPayoutCount == null;
    }

    /** True when every value set here is within the ceiling set by {@code parent} (unset parent values impose nothing). */
    public boolean fitsWithin(Limits parent) {
        if (parent == null) return true;
        return within(singleTransactionUsd, parent.singleTransactionUsd)
                && within(dailyVolumeUsd, parent.dailyVolumeUsd)
                && within(monthlyVolumeUsd, parent.monthlyVolumeUsd)
                && (dailyPayoutCount == null || parent.dailyPayoutCount == null
                    || dailyPayoutCount <= parent.dailyPayoutCount);
    }

    private static boolean within(BigDecimal mine, BigDecimal ceiling) {
        return mine == null || ceiling == null || mine.compareTo(ceiling) <= 0;
    }
}

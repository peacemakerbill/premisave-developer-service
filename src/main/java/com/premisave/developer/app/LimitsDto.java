package com.premisave.developer.app;

import java.math.BigDecimal;

public record LimitsDto(BigDecimal singleTransactionUsd, BigDecimal dailyVolumeUsd, BigDecimal monthlyVolumeUsd,
                        Integer dailyPayoutCount) {
}

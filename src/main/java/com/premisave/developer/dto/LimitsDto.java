package com.premisave.developer.dto;

import java.math.BigDecimal;

public record LimitsDto(BigDecimal singleTransactionUsd, BigDecimal dailyVolumeUsd, BigDecimal monthlyVolumeUsd,
                        Integer dailyPayoutCount) {
}

package com.premisave.developer.app;

import com.premisave.developer.enums.MethodType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record CreateAppRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 500) String description,
        @NotBlank String environment,
        @Size(max = 24) String merchantNumber,
        @Size(max = 160) String premisaveWalletAccount,
        Set<MethodType> enabledMethods,
        @Valid LimitsDto limits,
        @Size(max = 500) String callbackUrl) {
}

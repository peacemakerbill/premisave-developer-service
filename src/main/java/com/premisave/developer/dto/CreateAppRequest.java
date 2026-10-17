package com.premisave.developer.dto;

import com.premisave.developer.enums.MethodType;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Set;

/**
 * businessId says which of the account's businesses the app is for. It can be left out when the account has just one.
 * merchantNumber is only checked for a production app, where it can be left out too and is then the business's own.
 */
public record CreateAppRequest(
        @Size(max = 64) String businessId,
        @NotBlank @Size(max = 120) String name,
        @Size(max = 500) String description,
        @NotBlank String environment,
        @Size(max = 24) String merchantNumber,
        @Size(max = 160) String premisaveWalletAccount,
        Set<MethodType> enabledMethods,
        @Valid LimitsDto limits,
        @Size(max = 500) String callbackUrl) {

    /** For an account with one business, so no business has to be named. */
    public CreateAppRequest(String name, String description, String environment, String merchantNumber,
                            String premisaveWalletAccount, Set<MethodType> enabledMethods, LimitsDto limits,
                            String callbackUrl) {
        this(null, name, description, environment, merchantNumber, premisaveWalletAccount, enabledMethods, limits, callbackUrl);
    }
}

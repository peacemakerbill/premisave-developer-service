package com.premisave.developer.app;

import com.premisave.developer.enums.MethodType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record UpdateAppRequest(
        @Size(max = 120) String name,
        @Size(max = 500) String description,
        Set<MethodType> enabledMethods,
        @Valid LimitsDto limits,
        @Size(max = 500) String callbackUrl) {
}

package com.premisave.developer.developer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VerifyEmailRequest(
        @NotBlank @Size(max = 200) String token) {
}

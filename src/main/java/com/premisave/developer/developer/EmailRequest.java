package com.premisave.developer.developer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EmailRequest(
        @NotBlank @Size(max = 160) String email) {
}

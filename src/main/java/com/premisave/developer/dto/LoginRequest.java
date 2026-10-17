package com.premisave.developer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(max = 160) String email,
        @NotBlank @Size(max = 128) String password) {
}

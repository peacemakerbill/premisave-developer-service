package com.premisave.developer.developer;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank @Email @Size(max = 160) String email,
        @NotBlank @Size(min = 2, max = 120) String fullName,
        @NotBlank @Size(max = 128) String password) {
}

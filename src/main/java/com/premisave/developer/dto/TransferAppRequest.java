package com.premisave.developer.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The developer account that should receive the app, named by the email address it signs in with. */
public record TransferAppRequest(@NotBlank @Email @Size(max = 160) String toEmail) {
}

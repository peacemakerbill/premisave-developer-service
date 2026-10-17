package com.premisave.developer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** The six digit code that was emailed to the address linked to the business. */
public record ConfirmTransferRequest(@NotBlank @Pattern(regexp = "\\d{6}", message = "must be the 6 digit code from the email") String otp) {
}

package com.premisave.developer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The PRM merchant number of an approved business, as the owner sees it in the wallet. */
public record ClaimBusinessRequest(@NotBlank @Size(max = 40) String merchantNumber) {
}

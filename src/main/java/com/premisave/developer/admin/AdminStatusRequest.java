package com.premisave.developer.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminStatusRequest(@NotBlank String status, @Size(max = 300) String reason) {
}

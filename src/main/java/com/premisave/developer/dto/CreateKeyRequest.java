package com.premisave.developer.dto;

import jakarta.validation.constraints.Size;

public record CreateKeyRequest(@Size(max = 60) String label) {
}

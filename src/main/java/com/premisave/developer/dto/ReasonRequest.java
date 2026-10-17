package com.premisave.developer.dto;

import jakarta.validation.constraints.Size;

public record ReasonRequest(@Size(max = 300) String reason) {
}

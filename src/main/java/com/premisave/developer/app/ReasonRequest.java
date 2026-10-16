package com.premisave.developer.app;

import jakarta.validation.constraints.Size;

public record ReasonRequest(@Size(max = 300) String reason) {
}

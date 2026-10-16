package com.premisave.developer.key;

import jakarta.validation.constraints.Size;

public record CreateKeyRequest(@Size(max = 60) String label) {
}

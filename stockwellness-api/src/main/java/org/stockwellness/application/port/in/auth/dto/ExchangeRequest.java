package org.stockwellness.application.port.in.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ExchangeRequest(
        @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9_-]{43}$")
        String code
) {
}

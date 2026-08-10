package org.stockwellness.adapter.in.web.testsupport.dto;

import jakarta.validation.constraints.NotBlank;

public record E2eAttestationRequest(@NotBlank String expectedDatabaseId) {
}

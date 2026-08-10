package org.stockwellness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.test-support.attestation")
public record E2eAttestationProperties(
        Long allowedMemberId,
        String databaseId
) {
}

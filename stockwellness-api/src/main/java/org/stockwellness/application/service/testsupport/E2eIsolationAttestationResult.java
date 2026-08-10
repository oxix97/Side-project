package org.stockwellness.application.service.testsupport;

public record E2eIsolationAttestationResult(
        Long memberId,
        String databaseId,
        boolean isolated
) {
}

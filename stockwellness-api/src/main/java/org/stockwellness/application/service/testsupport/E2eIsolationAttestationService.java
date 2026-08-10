package org.stockwellness.application.service.testsupport;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.stockwellness.config.E2eAttestationProperties;
import org.stockwellness.global.error.ErrorCode;
import org.stockwellness.global.error.exception.GlobalException;

@Service
@Profile("!prod & (test | e2e)")
@RequiredArgsConstructor
public class E2eIsolationAttestationService {

    private final E2eAttestationProperties properties;

    public E2eIsolationAttestationResult attest(Long memberId, String expectedDatabaseId) {
        if (properties.allowedMemberId() == null
                || properties.allowedMemberId() <= 0
                || !StringUtils.hasText(properties.databaseId())
                || !properties.allowedMemberId().equals(memberId)
                || !properties.databaseId().equals(expectedDatabaseId)) {
            throw new GlobalException(ErrorCode.ACCESS_DENIED);
        }

        return new E2eIsolationAttestationResult(memberId, properties.databaseId(), true);
    }
}

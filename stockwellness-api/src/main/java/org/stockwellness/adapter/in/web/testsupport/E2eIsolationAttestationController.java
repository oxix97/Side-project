package org.stockwellness.adapter.in.web.testsupport;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.stockwellness.adapter.in.web.testsupport.dto.E2eAttestationRequest;
import org.stockwellness.application.service.testsupport.E2eIsolationAttestationResult;
import org.stockwellness.application.service.testsupport.E2eIsolationAttestationService;
import org.stockwellness.global.common.response.ApiResponse;
import org.stockwellness.global.security.MemberPrincipal;

@RestController
@RequestMapping("/api/v1/test-support")
@Profile("!prod & (test | e2e)")
@RequiredArgsConstructor
public class E2eIsolationAttestationController {

    private final E2eIsolationAttestationService attestationService;

    @PostMapping("/attestation")
    public ApiResponse<E2eIsolationAttestationResult> attest(
            @AuthenticationPrincipal MemberPrincipal memberPrincipal,
            @Valid @RequestBody E2eAttestationRequest request
    ) {
        return ApiResponse.success(attestationService.attest(memberPrincipal.id(), request.expectedDatabaseId()));
    }
}

package org.stockwellness.application.service.testsupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stockwellness.config.E2eAttestationProperties;
import org.stockwellness.global.error.ErrorCode;
import org.stockwellness.global.error.exception.BusinessException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Real E2E 격리 증명 서비스")
class E2eIsolationAttestationServiceTest {

    @Test
    @DisplayName("서버 allowlist 회원과 격리 DB가 모두 일치할 때만 isolated true를 반환한다")
    void attest_success_for_allowlisted_member_and_database() {
        E2eIsolationAttestationService service = new E2eIsolationAttestationService(
                new E2eAttestationProperties(7L, "isolated-e2e")
        );

        E2eIsolationAttestationResult result = service.attest(7L, "isolated-e2e");

        assertThat(result.memberId()).isEqualTo(7L);
        assertThat(result.databaseId()).isEqualTo("isolated-e2e");
        assertThat(result.isolated()).isTrue();
    }

    @Test
    @DisplayName("인증 회원이 allowlist와 다르면 A002로 거부한다")
    void reject_member_mismatch() {
        E2eIsolationAttestationService service = new E2eIsolationAttestationService(
                new E2eAttestationProperties(7L, "isolated-e2e")
        );

        assertThatThrownBy(() -> service.attest(8L, "isolated-e2e"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ACCESS_DENIED);
    }

    @Test
    @DisplayName("요청한 databaseId가 서버 설정과 다르면 A002로 거부한다")
    void reject_database_mismatch() {
        E2eIsolationAttestationService service = new E2eIsolationAttestationService(
                new E2eAttestationProperties(7L, "isolated-e2e")
        );

        assertThatThrownBy(() -> service.attest(7L, "production"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ACCESS_DENIED);
    }

    @Test
    @DisplayName("서버 allowlist 설정이 없으면 fail closed 한다")
    void reject_when_server_configuration_is_missing() {
        E2eIsolationAttestationService service = new E2eIsolationAttestationService(
                new E2eAttestationProperties(null, null)
        );

        assertThatThrownBy(() -> service.attest(7L, "isolated-e2e"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ACCESS_DENIED);
    }

    @Test
    @DisplayName("databaseId만 설정되고 allowlist 회원이 없으면 fail closed 한다")
    void reject_when_only_database_is_configured() {
        E2eIsolationAttestationService service = new E2eIsolationAttestationService(
                new E2eAttestationProperties(null, "isolated-e2e")
        );

        assertThatThrownBy(() -> service.attest(7L, "isolated-e2e"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ACCESS_DENIED);
    }

    @Test
    @DisplayName("allowlist 회원 ID가 0 이하이면 일치하더라도 fail closed 한다")
    void reject_non_positive_allowlisted_member_id() {
        E2eIsolationAttestationService zeroMemberService = new E2eIsolationAttestationService(
                new E2eAttestationProperties(0L, "isolated-e2e")
        );
        E2eIsolationAttestationService negativeMemberService = new E2eIsolationAttestationService(
                new E2eAttestationProperties(-1L, "isolated-e2e")
        );

        assertThatThrownBy(() -> zeroMemberService.attest(0L, "isolated-e2e"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ACCESS_DENIED);
        assertThatThrownBy(() -> negativeMemberService.attest(-1L, "isolated-e2e"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ACCESS_DENIED);
    }
}

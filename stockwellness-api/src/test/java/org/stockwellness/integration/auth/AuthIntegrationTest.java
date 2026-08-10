package org.stockwellness.integration.auth;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.stockwellness.adapter.out.persistence.member.MemberRepository;
import org.stockwellness.application.port.in.auth.command.LoginCommand;
import org.stockwellness.application.port.in.auth.dto.ReissueRequest;
import org.stockwellness.application.port.in.auth.result.LoginResult;
import org.stockwellness.domain.auth.RefreshToken;
import org.stockwellness.domain.member.LoginType;
import org.stockwellness.domain.shared.Email;
import org.stockwellness.fixture.AuthFixture;
import org.stockwellness.global.util.DateUtil;
import org.stockwellness.integration.common.BaseIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Auth API E2E 통합 테스트")
class AuthIntegrationTest extends BaseIntegrationTest {

    private static final String VALID_EXCHANGE_CODE = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    @Autowired
    private MemberRepository memberRepository;

    @BeforeEach
    void setUp() {
        memberRepository.deleteAll();
    }

    @Test
    @DisplayName("OAuth 교환 코드는 한 번만 LoginResponse로 교환된다")
    void oauth_exchange_code_is_single_use() throws Exception {
        String email = "newuser@example.com";
        LoginResult loginResult = authUseCase.login(new LoginCommand(email, "New User", LoginType.KAKAO));
        given(oAuthExchangeCodePort.consume(VALID_EXCHANGE_CODE))
                .willReturn(Optional.of(loginResult), Optional.empty());

        mockMvc.perform(post("/api/v1/auth/exchange")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"" + VALID_EXCHANGE_CODE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").exists())
                .andExpect(jsonPath("$.data.refreshToken").exists())
                .andExpect(jsonPath("$.data.email").value(email));

        mockMvc.perform(post("/api/v1/auth/exchange")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"" + VALID_EXCHANGE_CODE + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A009"));

        assertThat(memberRepository.findByEmail(new Email(email))).isPresent();
    }

    @Test
    @DisplayName("유효한 리프레시 토큰으로 액세스 토큰을 재발급받는다")
    void reissue_token_success() throws Exception {
        LoginResult loginResult = authUseCase.login(AuthFixture.createLoginCommand());
        String refreshToken = loginResult.refreshToken();
        Long memberId = loginResult.memberId();
        ReissueRequest reissueRequest = new ReissueRequest(refreshToken);

        given(refreshTokenPort.findByMemberId(ArgumentMatchers.any())).willReturn(
                RefreshToken.create(memberId, refreshToken, DateUtil.now().plusDays(1))
        );

        // when & then
        mockMvc.perform(post("/api/v1/auth/reissue")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reissueRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").exists())
                .andExpect(jsonPath("$.data.refreshToken").exists());
    }

    @Test
    @DisplayName("Bearer 로그아웃 후 기존 RefreshToken 재발급은 A005를 반환한다")
    void logout_revokes_refresh_token() throws Exception {
        LoginResult loginResult = authUseCase.login(AuthFixture.createLoginCommand());
        ReissueRequest reissueRequest = new ReissueRequest(loginResult.refreshToken());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .with(csrf())
                        .header("Authorization", "Bearer " + loginResult.accessToken()))
                .andExpect(status().isOk());

        given(refreshTokenPort.findByMemberId(loginResult.memberId())).willReturn(null);

        mockMvc.perform(post("/api/v1/auth/reissue")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reissueRequest)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A005"));
    }
}

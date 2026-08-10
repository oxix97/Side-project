package org.stockwellness.global.security.handler;

import java.io.IOException;
import java.util.Collections;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.stockwellness.application.port.in.auth.AuthUseCase;
import org.stockwellness.domain.member.LoginType;
import org.stockwellness.domain.member.MemberRole;
import org.stockwellness.global.security.MemberPrincipal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class OAuth2LoginSuccessHandlerTest {

    private final AuthUseCase authUseCase = mock(AuthUseCase.class);
    private final OAuth2LoginSuccessHandler handler = new OAuth2LoginSuccessHandler(authUseCase);

    @Test
    @DisplayName("OAuth2 로그인 성공 시 토큰 없이 일회용 code로 callback 리다이렉트한다")
    void onAuthenticationSuccess_redirects_with_one_time_code_only() throws IOException, ServletException {
        ReflectionTestUtils.setField(handler, "frontendRedirectUrl", "http://localhost:5173/auth/callback");

        given(authUseCase.issueOAuthExchangeCode(any())).willReturn("opaque-code");

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, authentication());

        assertThat(response.getRedirectedUrl())
                .isEqualTo("http://localhost:5173/auth/callback?code=opaque-code")
                .doesNotContain("accessToken")
                .doesNotContain("refreshToken")
                .doesNotContain("access-token")
                .doesNotContain("refresh-token");
    }

    @Test
    @DisplayName("설정된 callback의 기존 query를 제거하고 code 하나만 전달한다")
    void onAuthenticationSuccess_replaces_existing_query_with_code_only() throws IOException, ServletException {
        ReflectionTestUtils.setField(
                handler,
                "frontendRedirectUrl",
                "https://front.example/auth/callback?campaign=legacy&accessToken=stale"
        );
        given(authUseCase.issueOAuthExchangeCode(any())).willReturn("opaque-code");

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, authentication());

        assertThat(response.getRedirectedUrl())
                .isEqualTo("https://front.example/auth/callback?code=opaque-code")
                .doesNotContain("campaign")
                .doesNotContain("accessToken");
    }

    private UsernamePasswordAuthenticationToken authentication() {
        MemberPrincipal principal = new MemberPrincipal(
                1L,
                "user@example.com",
                "tester",
                LoginType.GOOGLE,
                MemberRole.USER,
                Collections.emptyList(),
                Collections.emptyMap()
        );
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}

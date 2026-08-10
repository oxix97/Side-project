package org.stockwellness.global.security.handler;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import org.stockwellness.application.port.in.auth.AuthUseCase;
import org.stockwellness.application.port.in.auth.command.LoginCommand;
import org.stockwellness.global.security.MemberPrincipal;

@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2LoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final AuthUseCase authUseCase;

    @Value("${app.frontend-redirect-url:http://localhost:5173/auth/callback}")
    private String frontendRedirectUrl;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException {
        MemberPrincipal principal = (MemberPrincipal) authentication.getPrincipal();

        // 1. LoginCommand를 생성하여 AuthService 호출 (가입/로그인 통합 처리)
        LoginCommand command = new LoginCommand(
                principal.email(),
                principal.nickname(),
                principal.loginType()
        );
        String exchangeCode = authUseCase.issueOAuthExchangeCode(command);

        // 2. 프론트엔드 리다이렉트 (60초·1회 사용 교환 코드만 전달)
        String targetUrl = UriComponentsBuilder.fromUriString(frontendRedirectUrl)
                .replaceQuery(null)
                .queryParam("code", exchangeCode)
                .build().toUriString();

        log.info("OAuth2 로그인 성공 - 일회용 교환 코드 redirect 발급 완료");
        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }
}

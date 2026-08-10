package org.stockwellness.global.security.jwt;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.stockwellness.adapter.out.security.jwt.JwtProvider;
import org.stockwellness.global.security.CustomUserDetailsService;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;
    private final CustomUserDetailsService userDetailService;
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final List<String> EXCLUDE_URLS = List.of(
            "/api/v1/auth/exchange",
            "/api/v1/auth/reissue",
            "/api/v1/auth/test",
            "/api/v1/stocks/popular-search",
            "/oauth2/**",
            "/login/**"
    );
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return EXCLUDE_URLS.stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        try {
            String jwt = extractJwtFromRequest(request);

            if (StringUtils.hasText(jwt)) {
                // 토큰 검증과 ID 추출을 한 번에 수행 (성능 최적화)
                Long memberId = jwtProvider.validateAndGetId(jwt);

                UserDetails userDetails = userDetailService.loadUserByUsername(memberId.toString());

                setAuthenticationToContext(userDetails, request);
                log.debug("JWT 인증 성공 - memberId: {}", memberId);
            }
        } catch (Exception e) {
            // 인증 실패 로그는 WARN 레벨로 낮춤 (정상적인 실패 케이스 포함)
            log.warn("JWT 인증 실패 - URI: {}, Message: {}", request.getRequestURI(), e.getMessage());
            // 예외를 다시 던져서 JwtExceptionFilter가 처리하도록 함 (중요)
            throw e;
        }

        filterChain.doFilter(request, response);
    }

    private void setAuthenticationToContext(UserDetails userDetails, HttpServletRequest request) {
        // UserDetails를 기반으로 Authentication 생성
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());

        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private String extractJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(BEARER_PREFIX)) {
            return bearerToken.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}

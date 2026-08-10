package org.stockwellness.application.service.auth;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.stockwellness.adapter.out.security.jwt.JwtProperties;
import org.stockwellness.adapter.out.security.jwt.JwtProvider;
import org.stockwellness.application.port.in.auth.command.LoginCommand;
import org.stockwellness.application.port.in.auth.result.LoginResult;
import org.stockwellness.application.port.in.auth.result.ReissueResult;
import org.stockwellness.application.port.out.auth.OAuthExchangeCodePort;
import org.stockwellness.application.port.out.auth.RefreshTokenPort;
import org.stockwellness.application.port.out.member.LoadMemberPort;
import org.stockwellness.application.port.out.member.SaveMemberPort;
import org.stockwellness.domain.auth.RefreshToken;
import org.stockwellness.domain.member.Member;
import org.stockwellness.fixture.AuthFixture;
import org.stockwellness.global.error.ErrorCode;
import org.stockwellness.global.error.exception.BusinessException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("Auth 서비스 단위 테스트")
class AuthServiceTest {

    @InjectMocks
    private AuthService authService;

    @Mock
    private LoadMemberPort loadMemberPort;
    @Mock
    private SaveMemberPort saveMemberPort;
    @Mock
    private JwtProvider jwtProvider;
    @Mock
    private RefreshTokenPort refreshTokenPort;
    @Mock
    private OAuthExchangeCodePort oAuthExchangeCodePort;
    @Mock
    private JwtProperties jwtProperties;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        lenient().when(jwtProperties.refreshTokenExpiryMs()).thenReturn(2592000000L);
    }

    @Nested
    @DisplayName("로그인 (Login)")
    class Login {

        @Test
        @DisplayName("기존 회원이 로그인하면 토큰을 발급한다")
        void login_existing_member() {
            // given
            LoginCommand command = AuthFixture.createLoginCommand();
            Member member = AuthFixture.createMember();
            ReflectionTestUtils.setField(member, "createdAt", LocalDateTime.now());
            
            // Mocking: 회원 조회 성공
            given(loadMemberPort.loadMemberByEmailAndLoginType(any(), any()))
                    .willReturn(Optional.of(member));
            
            // Mocking: 토큰 발급
            given(jwtProvider.generateAccessToken(member)).willReturn(AuthFixture.ACCESS_TOKEN);
            given(jwtProvider.generateRefreshToken(member)).willReturn(AuthFixture.REFRESH_TOKEN);

            // when
            LoginResult result = authService.login(command);

            // then
            assertThat(result.accessToken()).isEqualTo(AuthFixture.ACCESS_TOKEN);
            assertThat(result.refreshToken()).isEqualTo(AuthFixture.REFRESH_TOKEN);
            verify(refreshTokenPort).save(any(RefreshToken.class));
        }

        @Test
        @DisplayName("신규 회원이면 회원가입 후 토큰을 발급한다")
        void login_new_member() {
            // given
            LoginCommand command = AuthFixture.createLoginCommand();
            Member newMember = AuthFixture.createMember();
            ReflectionTestUtils.setField(newMember, "createdAt", LocalDateTime.now());

            // Mocking: 회원 없음 -> 저장
            given(loadMemberPort.loadMemberByEmailAndLoginType(any(), any()))
                    .willReturn(Optional.empty());
            given(saveMemberPort.saveMember(any(Member.class))).willReturn(newMember);

            given(jwtProvider.generateAccessToken(newMember)).willReturn(AuthFixture.ACCESS_TOKEN);
            given(jwtProvider.generateRefreshToken(newMember)).willReturn(AuthFixture.REFRESH_TOKEN);

            // when
            LoginResult result = authService.login(command);

            // then
            assertThat(result.accessToken()).isEqualTo(AuthFixture.ACCESS_TOKEN);
            verify(saveMemberPort).saveMember(any(Member.class));
        }

        @Test
        @DisplayName("탈퇴한 회원이 로그인하면 예외가 발생한다")
        void login_deactivated_member() {
            // given
            LoginCommand command = AuthFixture.createLoginCommand();
            Member member = AuthFixture.createMember();
            member.deactivate();

            given(loadMemberPort.loadMemberByEmailAndLoginType(any(), any()))
                    .willReturn(Optional.of(member));

            // when & then
            assertThatThrownBy(() -> authService.login(command))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNAUTHORIZED);
        }

        @Test
        @DisplayName("OAuth 로그인은 토큰 응답을 60초 일회용 교환 코드로 저장한다")
        void issue_oauth_exchange_code_with_sixty_second_ttl() {
            LoginCommand command = AuthFixture.createLoginCommand();
            Member member = AuthFixture.createMember();
            ReflectionTestUtils.setField(member, "createdAt", LocalDateTime.now());
            given(loadMemberPort.loadMemberByEmailAndLoginType(any(), any())).willReturn(Optional.of(member));
            given(jwtProvider.generateAccessToken(member)).willReturn(AuthFixture.ACCESS_TOKEN);
            given(jwtProvider.generateRefreshToken(member)).willReturn(AuthFixture.REFRESH_TOKEN);
            given(oAuthExchangeCodePort.issue(any(LoginResult.class), any(Duration.class)))
                    .willReturn("opaque-code");

            String code = authService.issueOAuthExchangeCode(command);

            assertThat(code).isEqualTo("opaque-code");
            verify(oAuthExchangeCodePort).issue(any(LoginResult.class), org.mockito.ArgumentMatchers.eq(Duration.ofSeconds(60)));
        }
    }

    @Nested
    @DisplayName("OAuth 교환 코드 (Exchange)")
    class Exchange {
        @Test
        @DisplayName("같은 교환 코드는 한 번만 사용할 수 있다")
        void exchange_code_is_single_use() {
            LoginResult loginResult = new LoginResult(
                    AuthFixture.ACCESS_TOKEN,
                    AuthFixture.REFRESH_TOKEN,
                    AuthFixture.MEMBER_ID,
                    AuthFixture.EMAIL,
                    AuthFixture.NICKNAME,
                    LocalDate.of(2026, 8, 10)
            );
            given(oAuthExchangeCodePort.consume("opaque-code"))
                    .willReturn(Optional.of(loginResult), Optional.empty());

            assertThat(authService.exchange("opaque-code")).isEqualTo(loginResult);
            assertThatThrownBy(() -> authService.exchange("opaque-code"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OAUTH_EXCHANGE_CODE_INVALID);
        }

        @Test
        @DisplayName("만료되거나 존재하지 않는 교환 코드는 A009를 반환한다")
        void invalid_exchange_code_returns_a009() {
            given(oAuthExchangeCodePort.consume("expired-code")).willReturn(Optional.empty());

            assertThatThrownBy(() -> authService.exchange("expired-code"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OAUTH_EXCHANGE_CODE_INVALID);
        }
    }

    @Nested
    @DisplayName("토큰 재발급 (Reissue)")
    class Reissue {

        @Test
        @DisplayName("유효한 RefreshToken으로 요청 시 AccessToken을 재발급한다")
        void reissue_success() {
            // given
            String oldRefreshToken = AuthFixture.REFRESH_TOKEN;
            RefreshToken storedToken = AuthFixture.createRefreshToken();
            Member member = AuthFixture.createMember();

            // [변경] validateAndGetId 호출 시 ID 반환
            given(jwtProvider.validateAndGetId(oldRefreshToken)).willReturn(AuthFixture.MEMBER_ID);
            
            given(refreshTokenPort.findByMemberId(AuthFixture.MEMBER_ID)).willReturn(storedToken);
            given(loadMemberPort.loadMember(AuthFixture.MEMBER_ID)).willReturn(Optional.of(member));

            given(jwtProvider.generateAccessToken(member)).willReturn("new.access.token");
            given(jwtProvider.generateRefreshToken(member)).willReturn("new.refresh.token");

            // when
            ReissueResult result = authService.reissue(oldRefreshToken);

            // then
            assertThat(result.accessToken()).isEqualTo("new.access.token");
            assertThat(result.refreshToken()).isEqualTo("new.refresh.token");
            
            // 기존 토큰 삭제 및 새 토큰 저장 확인 (Rotation)
            verify(refreshTokenPort).deleteByMemberId(AuthFixture.MEMBER_ID);
            verify(refreshTokenPort).save(any(RefreshToken.class));
        }

        @Test
        @DisplayName("RefreshToken이 유효하지 않으면 예외가 발생한다")
        void reissue_fail_invalid_token() {
            // given
            // [변경] 검증 실패 시 예외 발생
            given(jwtProvider.validateAndGetId(AuthFixture.REFRESH_TOKEN))
                    .willThrow(new JwtException("Invalid Token"));

            // when & then
            assertThatThrownBy(() -> authService.reissue(AuthFixture.REFRESH_TOKEN))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EXPIRED_JWT);
        }
    }

    @Nested
    @DisplayName("로그아웃 (Logout)")
    class Logout {
        @Test
        @DisplayName("로그아웃 시 RefreshToken을 삭제한다")
        void logout_success() {
            // when
            authService.logout(AuthFixture.MEMBER_ID);

            // then
            verify(refreshTokenPort).deleteByMemberId(AuthFixture.MEMBER_ID);
        }

        @Test
        @DisplayName("로그아웃 후 기존 RefreshToken 재발급은 A005를 반환한다")
        void reissue_after_logout_returns_a005() {
            given(jwtProvider.validateAndGetId(AuthFixture.REFRESH_TOKEN)).willReturn(AuthFixture.MEMBER_ID);
            given(refreshTokenPort.findByMemberId(AuthFixture.MEMBER_ID)).willReturn(null);

            authService.logout(AuthFixture.MEMBER_ID);

            assertThatThrownBy(() -> authService.reissue(AuthFixture.REFRESH_TOKEN))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REFRESH_TOKEN);
        }
    }
}

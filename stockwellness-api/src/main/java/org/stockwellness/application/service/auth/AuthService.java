package org.stockwellness.application.service.auth;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.stockwellness.adapter.out.security.jwt.JwtProperties;
import org.stockwellness.adapter.out.security.jwt.JwtProvider;
import org.stockwellness.application.port.in.auth.AuthUseCase;
import org.stockwellness.application.port.in.auth.command.LoginCommand;
import org.stockwellness.application.port.in.auth.result.LoginResult;
import org.stockwellness.application.port.in.auth.result.ReissueResult;
import org.stockwellness.application.port.out.auth.OAuthExchangeCodePort;
import org.stockwellness.application.port.out.auth.RefreshTokenPort;
import org.stockwellness.application.port.out.member.LoadMemberPort;
import org.stockwellness.application.port.out.member.SaveMemberPort;
import org.stockwellness.domain.auth.RefreshToken;
import org.stockwellness.domain.member.Member;
import org.stockwellness.domain.member.event.MemberCreatedEvent;
import org.stockwellness.domain.member.exception.MemberNotFoundException;
import org.stockwellness.domain.shared.Email;
import org.stockwellness.global.error.ErrorCode;
import org.stockwellness.global.error.exception.GlobalException;
import org.stockwellness.global.util.DateUtil;

@Slf4j
@RequiredArgsConstructor
@Transactional
@Service
public class AuthService implements AuthUseCase {
    private static final Duration OAUTH_EXCHANGE_CODE_TTL = Duration.ofSeconds(60);

    private final LoadMemberPort loadMemberPort;
    private final SaveMemberPort saveMemberPort;
    private final JwtProvider jwtProvider;
    private final RefreshTokenPort refreshTokenPort;
    private final OAuthExchangeCodePort oAuthExchangeCodePort;
    private final JwtProperties jwtProperties;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public LoginResult login(LoginCommand command) {
        Member member = loadMemberPort.loadMemberByEmailAndLoginType(new Email(command.email()), command.loginType())
                .orElseGet(() -> {
                    Member newMember = Member.register(
                            command.email(),
                            command.nickname(),
                            command.loginType()
                    );
                    Member savedMember = saveMemberPort.saveMember(newMember);
                    eventPublisher.publishEvent(new MemberCreatedEvent(savedMember));
                    return savedMember;
                });

        if (!member.isActive()) {
            throw new GlobalException(ErrorCode.UNAUTHORIZED);
        }

        String accessToken = jwtProvider.generateAccessToken(member);
        String refreshToken = jwtProvider.generateRefreshToken(member);

        LocalDateTime expiredAt = DateUtil.plus(DateUtil.now(), jwtProperties.refreshTokenExpiryMs(), ChronoUnit.MILLIS);
        RefreshToken rt = RefreshToken.create(member.getId(), refreshToken, expiredAt);
        refreshTokenPort.save(rt);

        return new LoginResult(
                accessToken,
                refreshToken,
                member.getId(),
                member.getEmail().getAddress(),
                member.getNickname(),
                member.getCreatedAt().toLocalDate()
        );
    }

    @Override
    public String issueOAuthExchangeCode(LoginCommand command) {
        LoginResult loginResult = login(command);
        return oAuthExchangeCodePort.issue(loginResult, OAUTH_EXCHANGE_CODE_TTL);
    }

    @Override
    public LoginResult exchange(String code) {
        return oAuthExchangeCodePort.consume(code)
                .orElseThrow(() -> new GlobalException(ErrorCode.OAUTH_EXCHANGE_CODE_INVALID));
    }

    @Override
    public ReissueResult reissue(String oldRefreshToken) {
        // 1. 토큰 유효성 및 ID 추출 (한 번에 수행)
        Long memberId;
        try {
            memberId = jwtProvider.validateAndGetId(oldRefreshToken);
        } catch (Exception e) {
            throw new GlobalException(ErrorCode.EXPIRED_JWT); // 또는 INVALID_TOKEN
        }

        RefreshToken stored = refreshTokenPort.findByMemberId(memberId);
        if (stored == null || !stored.tokenValue().equals(oldRefreshToken) || stored.isExpired()) {
            throw new GlobalException(ErrorCode.INVALID_REFRESH_TOKEN);
        }

        Member member = loadMemberPort.loadMember(memberId)
                .orElseThrow(MemberNotFoundException::new);

        if (!member.isActive()) {
            throw new GlobalException(ErrorCode.UNAUTHORIZED);
        }

        // 2. Rotation
        refreshTokenPort.deleteByMemberId(memberId);

        String newAccessToken = jwtProvider.generateAccessToken(member);
        String newRefreshToken = jwtProvider.generateRefreshToken(member);
        LocalDateTime newExpiredAt = DateUtil.plus(DateUtil.now(), 30, ChronoUnit.DAYS);

        refreshTokenPort.save(RefreshToken.create(memberId, newRefreshToken, newExpiredAt));

        return new ReissueResult(newAccessToken, newRefreshToken);
    }

    @Override
    @CacheEvict(value = "member", key = "#memberId")
    public void logout(Long memberId) {
        refreshTokenPort.deleteByMemberId(memberId);
    }
}

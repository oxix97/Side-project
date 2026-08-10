package org.stockwellness.integration.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.stockwellness.adapter.out.persistence.stock.StockPriceCacheAdapter;
import org.stockwellness.application.port.in.auth.AuthUseCase;
import org.stockwellness.application.port.in.auth.command.LoginCommand;
import org.stockwellness.application.port.in.auth.result.LoginResult;
import org.stockwellness.application.port.out.auth.OAuthExchangeCodePort;
import org.stockwellness.application.port.out.auth.RefreshTokenPort;
import org.stockwellness.application.port.out.stock.PopularSearchPort;
import org.stockwellness.domain.member.LoginType;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 모든 API 통합 테스트의 공통 설정을 관리하는 베이스 클래스.
 * 인프라(Redis, Kafka) 연결을 차단하고 비즈니스 흐름을 검증합니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public abstract class BaseIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    // --- 인프라 레벨 Mock (실제 연결 차단) ---
    @MockitoBean
    protected LettuceConnectionFactory redisConnectionFactory;

    @MockitoBean
    protected KafkaTemplate<String, Object> kafkaTemplate;

    // --- 비즈니스/어댑터 레벨 Mock (Redis 의존성 제거) ---
    @MockitoBean
    protected StringRedisTemplate redisTemplate;

    @MockitoBean
    protected RefreshTokenPort refreshTokenPort;

    @MockitoBean
    protected OAuthExchangeCodePort oAuthExchangeCodePort;

    @Autowired
    protected AuthUseCase authUseCase;

    @MockitoBean
    protected StockPriceCacheAdapter stockPriceCacheAdapter;

    @MockitoBean
    protected PopularSearchPort popularSearchPort;

    @MockitoBean
    protected CacheManager cacheManager;

    @BeforeEach
    void setCommonMock() {
        // Redis NPE 방지를 위한 기본 Mock 설정
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        ZSetOperations<String, String> zSetOperations = mock(ZSetOperations.class);
        given(redisTemplate.opsForZSet()).willReturn(zSetOperations);

        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        given(redisTemplate.opsForHash()).willReturn(hashOperations);

        ListOperations<String, String> listOperations = mock(ListOperations.class);
        given(redisTemplate.opsForList()).willReturn(listOperations);

        SetOperations<String, String> setOperations = mock(SetOperations.class);
        given(redisTemplate.opsForSet()).willReturn(setOperations);

        // Cache NPE 방지
        Cache cache = mock(Cache.class);
        given(cacheManager.getCache(ArgumentMatchers.anyString())).willReturn(cache);
    }

    /**
     * 테스트용 사용자 로그인을 수행하고 Access Token을 반환합니다.
     */
    protected String loginAndGetToken(String email, String nickname) {
        LoginResult result = authUseCase.login(new LoginCommand(email, nickname, LoginType.KAKAO));
        return result.accessToken();
    }
}

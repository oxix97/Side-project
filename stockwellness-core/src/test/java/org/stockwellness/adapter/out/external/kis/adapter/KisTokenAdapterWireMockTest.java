package org.stockwellness.adapter.out.external.kis.adapter;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.wiremock.AutoConfigureWireMock;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.stockwellness.adapter.out.external.kis.exception.KisAuthenticationException;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "kis.base-url=http://localhost:${wiremock.server.port}",
        "kis.app-key=test-key",
        "kis.app-secret=test-secret"
})
@ActiveProfiles("test")
@AutoConfigureWireMock(port = 0)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class KisTokenAdapterWireMockTest {

    @Autowired
    private KisTokenAdapter kisTokenAdapter;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private LettuceConnectionFactory redisConnectionFactory;

    @Test
    @DisplayName("캐시된 토큰이 없으면 KIS API를 호출하여 새 토큰을 발급받고 Redis에 저장한다")
    void getAccessToken_WhenNoCache_ShouldFetchFromApi() {
        // 준비
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(anyString())).willReturn(null);

        stubFor(post(urlEqualTo("/oauth2/tokenP"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "access_token": "new-test-token",
                                  "expires_in": 3600,
                                  "token_type": "Bearer"
                                }
                                """)));

        // 실행
        String token = kisTokenAdapter.getAccessToken();

        // 검증
        assertThat(token).isEqualTo("new-test-token");
        verify(valueOperations).set(eq("stockwellness:kis:token"), eq("new-test-token"), any(Duration.class));
    }

    @Test
    @DisplayName("Redis에 캐시된 토큰이 있으면 API를 호출하지 않고 캐시된 값을 반환한다")
    void getAccessToken_WhenCacheExists_ShouldReturnCachedToken() {
        // 준비
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("stockwellness:kis:token")).willReturn("cached-token");

        // 실행
        String token = kisTokenAdapter.getAccessToken();

        // 검증
        assertThat(token).isEqualTo("cached-token");
        verify(valueOperations).get("stockwellness:kis:token");
        // WireMock에 요청이 가지 않았어야 함 (stubFor를 사용하지 않았으므로 요청이 가면 에러가 나거나 검증 가능)
    }

    @Test
    @DisplayName("토큰 무효화 호출 시 Redis 캐시 키를 삭제한다")
    void invalidateAccessToken_ShouldDeleteRedisKey() {
        kisTokenAdapter.invalidateAccessToken();

        verify(redisTemplate).delete("stockwellness:kis:token");
    }

    @Test
    @DisplayName("토큰 발급 응답의 TTL이 safety margin 이하이면 예외를 던진다")
    void getAccessToken_WhenTtlInvalid_ShouldThrowException() {
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(anyString())).willReturn(null);

        stubFor(post(urlEqualTo("/oauth2/tokenP"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "access_token": "short-lived-token",
                                  "expires_in": 300,
                                  "token_type": "Bearer"
                                }
                                """)));

        assertThatThrownBy(() -> kisTokenAdapter.getAccessToken())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("TTL");

        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("토큰 발급 응답이 KIS 업무 오류이면 인증 예외를 던진다")
    void getAccessToken_WhenBusinessError_ShouldThrowAuthenticationException() {
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(anyString())).willReturn(null);

        stubFor(post(urlEqualTo("/oauth2/tokenP"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "rt_cd": "1",
                                  "msg_cd": "TOKEN_EXPIRED",
                                  "msg1": "기간이 만료된 token 입니다."
                                }
                                """)));

        assertThatThrownBy(() -> kisTokenAdapter.getAccessToken())
                .isInstanceOf(KisAuthenticationException.class)
                .hasMessageContaining("기간이 만료된 token");
    }
}

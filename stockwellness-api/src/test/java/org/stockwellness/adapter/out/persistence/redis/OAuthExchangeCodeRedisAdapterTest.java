package org.stockwellness.adapter.out.persistence.redis;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.stockwellness.application.port.in.auth.result.LoginResult;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("OAuth 교환 코드 Redis 어댑터")
class OAuthExchangeCodeRedisAdapterTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private OAuthExchangeCodeRedisAdapter adapter;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        adapter = new OAuthExchangeCodeRedisAdapter(redisTemplate, objectMapper);
    }

    @Test
    @DisplayName("opaque code를 지정한 TTL로 저장한다")
    void issue_saves_opaque_code_with_ttl() {
        LoginResult result = loginResult();

        String code = adapter.issue(result, Duration.ofSeconds(60));

        assertThat(code).isNotBlank();
        verify(valueOperations).set(eq("oauth_exchange_code:" + code), anyString(), eq(Duration.ofSeconds(60)));
    }

    @Test
    @DisplayName("교환 코드는 Redis GETDEL로 원자적으로 한 번 소비한다")
    void consume_uses_atomic_get_and_delete() throws Exception {
        LoginResult expected = loginResult();
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        given(valueOperations.getAndDelete("oauth_exchange_code:opaque-code"))
                .willReturn(objectMapper.writeValueAsString(expected));

        Optional<LoginResult> actual = adapter.consume("opaque-code");

        assertThat(actual).contains(expected);
        verify(valueOperations).getAndDelete("oauth_exchange_code:opaque-code");
    }

    @Test
    @DisplayName("직렬화 실패 예외에는 토큰을 포함한 원인 예외를 연결하지 않는다")
    void serialization_failure_does_not_expose_token_in_exception() throws Exception {
        ObjectMapper failingObjectMapper = mock(ObjectMapper.class);
        given(failingObjectMapper.writeValueAsString(loginResult()))
                .willThrow(new JsonProcessingException("access-token") { });
        OAuthExchangeCodeRedisAdapter failingAdapter =
                new OAuthExchangeCodeRedisAdapter(redisTemplate, failingObjectMapper);

        assertThatThrownBy(() -> failingAdapter.issue(loginResult(), Duration.ofSeconds(60)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OAuth exchange code serialization failed")
                .hasNoCause();
    }

    @Test
    @DisplayName("역직렬화 실패 예외에는 Redis payload를 포함한 원인 예외를 연결하지 않는다")
    void deserialization_failure_does_not_expose_payload_in_exception() throws Exception {
        ObjectMapper failingObjectMapper = mock(ObjectMapper.class);
        given(valueOperations.getAndDelete("oauth_exchange_code:opaque-code"))
                .willReturn("serialized-token-payload");
        given(failingObjectMapper.readValue("serialized-token-payload", LoginResult.class))
                .willThrow(new JsonProcessingException("serialized-token-payload") { });
        OAuthExchangeCodeRedisAdapter failingAdapter =
                new OAuthExchangeCodeRedisAdapter(redisTemplate, failingObjectMapper);

        assertThatThrownBy(() -> failingAdapter.consume("opaque-code"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OAuth exchange code deserialization failed")
                .hasNoCause();
    }

    private LoginResult loginResult() {
        return new LoginResult(
                "access-token",
                "refresh-token",
                1L,
                "user@example.com",
                "tester",
                LocalDate.of(2026, 8, 10)
        );
    }
}

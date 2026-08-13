package org.stockwellness.adapter.out.persistence.redis;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.stockwellness.application.port.in.auth.result.LoginResult;
import org.stockwellness.application.port.out.auth.OAuthExchangeCodePort;

@Component
@RequiredArgsConstructor
public class OAuthExchangeCodeRedisAdapter implements OAuthExchangeCodePort {

    private static final String KEY_PREFIX = "oauth_exchange_code:";
    private static final int CODE_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public String issue(LoginResult loginResult, Duration ttl) {
        byte[] randomBytes = new byte[CODE_BYTES];
        SECURE_RANDOM.nextBytes(randomBytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        redisTemplate.opsForValue().set(KEY_PREFIX + code, serialize(loginResult), ttl);
        return code;
    }

    @Override
    public Optional<LoginResult> consume(String code) {
        String serialized = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + code);
        if (serialized == null) {
            return Optional.empty();
        }
        return Optional.of(deserialize(serialized));
    }

    private String serialize(LoginResult loginResult) {
        try {
            return objectMapper.writeValueAsString(loginResult);
        } catch (JsonProcessingException ignored) {
            throw new IllegalStateException("OAuth exchange code serialization failed");
        }
    }

    private LoginResult deserialize(String serialized) {
        try {
            return objectMapper.readValue(serialized, LoginResult.class);
        } catch (JsonProcessingException ignored) {
            throw new IllegalStateException("OAuth exchange code deserialization failed");
        }
    }
}

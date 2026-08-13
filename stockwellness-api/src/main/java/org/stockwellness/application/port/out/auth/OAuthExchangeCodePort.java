package org.stockwellness.application.port.out.auth;

import java.time.Duration;
import java.util.Optional;

import org.stockwellness.application.port.in.auth.result.LoginResult;

public interface OAuthExchangeCodePort {
    String issue(LoginResult loginResult, Duration ttl);

    Optional<LoginResult> consume(String code);
}

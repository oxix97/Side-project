package org.stockwellness.application.port.in.auth;

import org.stockwellness.application.port.in.auth.command.LoginCommand;
import org.stockwellness.application.port.in.auth.result.LoginResult;
import org.stockwellness.application.port.in.auth.result.ReissueResult;

public interface AuthUseCase {
    LoginResult login(LoginCommand command);
    String issueOAuthExchangeCode(LoginCommand command);
    LoginResult exchange(String code);
    ReissueResult reissue(String refreshToken);
    void logout(Long memberId);
}

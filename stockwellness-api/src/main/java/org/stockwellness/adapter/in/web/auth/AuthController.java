package org.stockwellness.adapter.in.web.auth;

import java.util.Map;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.stockwellness.adapter.in.web.auth.dto.LoginResponse;
import org.stockwellness.adapter.in.web.auth.dto.ReissueResponse;
import org.stockwellness.application.port.in.auth.AuthUseCase;
import org.stockwellness.application.port.in.auth.dto.ExchangeRequest;
import org.stockwellness.application.port.in.auth.dto.ReissueRequest;
import org.stockwellness.application.port.in.auth.result.LoginResult;
import org.stockwellness.application.port.in.auth.result.ReissueResult;
import org.stockwellness.global.common.response.ApiResponse;
import org.stockwellness.global.security.MemberPrincipal;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthUseCase authUseCase;

    @PostMapping("/exchange")
    public ApiResponse<LoginResponse> exchange(@Valid @RequestBody ExchangeRequest request) {
        return ApiResponse.success(toLoginResponse(authUseCase.exchange(request.code())));
    }

    @PostMapping("/reissue")
    public ApiResponse<ReissueResponse> reissue(@Valid @RequestBody ReissueRequest request) {
        ReissueResult result = authUseCase.reissue(request.refreshToken());
        
        ReissueResponse response = new ReissueResponse(
            result.accessToken(),
            result.refreshToken()
        );
        return ApiResponse.success(response);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@AuthenticationPrincipal MemberPrincipal memberPrincipal) {
        authUseCase.logout(memberPrincipal.id());
        return ApiResponse.success();
    }

    @GetMapping("/test")
    public ApiResponse<Map<String, String>> test() {
        return ApiResponse.success(Map.of("status", "ok", "service", "stockwellness"));
    }

    private LoginResponse toLoginResponse(LoginResult result) {
        return new LoginResponse(
                result.accessToken(),
                result.refreshToken(),
                result.memberId(),
                result.email(),
                result.nickname(),
                result.joinedDate()
        );
    }
}

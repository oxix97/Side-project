package org.stockwellness.adapter.in.web.auth;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.epages.restdocs.apispec.ResourceSnippetParameters;
import com.epages.restdocs.apispec.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.stockwellness.application.port.in.auth.AuthUseCase;
import org.stockwellness.application.port.in.auth.dto.ExchangeRequest;
import org.stockwellness.application.port.in.auth.dto.LoginRequest;
import org.stockwellness.application.port.in.auth.dto.ReissueRequest;
import org.stockwellness.application.port.in.auth.result.LoginResult;
import org.stockwellness.application.port.in.auth.result.ReissueResult;
import org.stockwellness.fixture.AuthFixture;
import org.stockwellness.global.error.ErrorCode;
import org.stockwellness.global.error.exception.GlobalException;
import org.stockwellness.support.RestDocsSupport;
import org.stockwellness.support.annotation.MockMember;
import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.ResourceDocumentation.headerWithName;
import static com.epages.restdocs.apispec.ResourceDocumentation.resource;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.snippet.Attributes.key;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Auth 컨트롤러 통합 테스트 (RestDocs)")
class AuthControllerTest extends RestDocsSupport {

    private static final String VALID_EXCHANGE_CODE = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String EXCHANGE_CODE_PATTERN = "^[A-Za-z0-9_-]{43}$";

    @MockitoBean
    private AuthUseCase authUseCase;

    @Nested
    @DisplayName("Raw 로그인 API")
    class RawLogin {
        @Test
        @DisplayName("이메일 정보만 받는 production 로그인 경로는 지원하지 않는다")
        void raw_login_is_not_supported() throws Exception {
            // given
            LoginRequest request = AuthFixture.createLoginRequest();

            // when & then
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(csrf())
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("OAuth 교환 API")
    class Exchange {
        @Test
        @DisplayName("유효한 일회용 code를 기존 LoginResponse로 교환한다")
        void exchange_success() throws Exception {
            ExchangeRequest request = new ExchangeRequest(VALID_EXCHANGE_CODE);
            LoginResult result = new LoginResult(
                    AuthFixture.ACCESS_TOKEN,
                    AuthFixture.REFRESH_TOKEN,
                    1L,
                    AuthFixture.EMAIL,
                    AuthFixture.NICKNAME,
                    LocalDate.of(2026, 8, 10)
            );
            given(authUseCase.exchange(VALID_EXCHANGE_CODE)).willReturn(result);

            List<FieldDescriptor> responseFields = new ArrayList<>(commonResponseFields());
            responseFields.addAll(List.of(
                    fieldWithPath("data.accessToken").description("액세스 토큰"),
                    fieldWithPath("data.refreshToken").description("리프레시 토큰"),
                    fieldWithPath("data.memberId").description("회원 ID"),
                    fieldWithPath("data.email").description("이메일"),
                    fieldWithPath("data.nickname").description("닉네임"),
                    fieldWithPath("data.joinedDate").description("가입 일자")
            ));

            mockMvc.perform(post("/api/v1/auth/exchange")
                            .with(csrf())
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andDo(document("auth-exchange",
                            resource(ResourceSnippetParameters.builder()
                                    .tag("Auth")
                                    .summary("OAuth 교환 코드로 토큰 발급")
                                    .description("43자리 Base64URL code를 60초 내 한 번만 교환하며, 만료·재사용·미등록 code는 A009로 거부합니다.")
                                    .requestSchema(Schema.schema("ExchangeRequest"))
                                    .responseSchema(Schema.schema("LoginResponse"))
                                    .requestFields(exchangeCodeField())
                                    .responseFields(responseFields)
                                    .build())
                    ));
        }

        @Test
        @DisplayName("서버 생성 형식이 아닌 code는 G001로 거부한다")
        void exchange_rejects_invalid_code_shape() throws Exception {
            mockMvc.perform(post("/api/v1/auth/exchange")
                            .with(csrf())
                            .contentType(APPLICATION_JSON)
                            .content("{\"code\":\"opaque-code\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("G001"));

            mockMvc.perform(post("/api/v1/auth/exchange")
                            .with(csrf())
                            .contentType(APPLICATION_JSON)
                            .content("{\"code\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("G001"));

            verify(authUseCase, never()).exchange(any());
        }

        @Test
        @DisplayName("code가 누락되거나 비어 있으면 G001로 거부한다")
        void exchange_rejects_missing_or_blank_code() throws Exception {
            mockMvc.perform(post("/api/v1/auth/exchange")
                            .with(csrf())
                            .contentType(APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("G001"));

            mockMvc.perform(post("/api/v1/auth/exchange")
                            .with(csrf())
                            .contentType(APPLICATION_JSON)
                            .content("{\"code\":\" \"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("G001"));

            verify(authUseCase, never()).exchange(any());
        }

        @Test
        @DisplayName("만료·재사용·알 수 없는 유효 형식 code는 A009로 문서화한다")
        void exchange_invalid_or_reused_code_is_documented() throws Exception {
            given(authUseCase.exchange(VALID_EXCHANGE_CODE))
                    .willThrow(new GlobalException(ErrorCode.OAUTH_EXCHANGE_CODE_INVALID));

            mockMvc.perform(post("/api/v1/auth/exchange")
                            .with(csrf())
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new ExchangeRequest(VALID_EXCHANGE_CODE))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("A009"))
                    .andDo(document("auth-exchange-error-a009",
                            resource(ResourceSnippetParameters.builder()
                                    .tag("Auth")
                                    .summary("OAuth 교환 코드로 토큰 발급")
                                    .description("43자리 Base64URL code를 60초 내 한 번만 교환하며, 만료·재사용·미등록 code는 A009로 거부합니다.")
                                    .requestSchema(Schema.schema("ExchangeRequest"))
                                    .responseSchema(Schema.schema("ErrorResponse"))
                                    .requestFields(exchangeCodeField())
                                    .responseFields(commonResponseFieldsWithNoData())
                                    .build())
                    ));
        }
    }

    @Nested
    @DisplayName("토큰 재발급 API")
    class Reissue {
        @Test
        @DisplayName("유효한 리프레시 토큰으로 액세스 토큰을 재발급한다")
        void reissue_success() throws Exception {
            // given
            ReissueRequest request = AuthFixture.createReissueRequest();
            ReissueResult result = new ReissueResult("new.access.token", "new.refresh.token");

            given(authUseCase.reissue(any())).willReturn(result);

            List<FieldDescriptor> responseFields = new ArrayList<>(commonResponseFields());
            responseFields.addAll(List.of(
                    fieldWithPath("data.accessToken").description("새로운 액세스 토큰"),
                    fieldWithPath("data.refreshToken").description("새로운 리프레시 토큰")
            ));

            // when & then
            mockMvc.perform(post("/api/v1/auth/reissue")
                            .with(csrf())
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andDo(document("auth-reissue",
                            resource(ResourceSnippetParameters.builder()
                                    .tag("Auth")
                                    .summary("토큰 재발급")
                                    .description("유효한 리프레시 토큰을 회전하며, 로그아웃 등으로 폐기된 토큰은 A005로 거부합니다.")
                                    .requestSchema(Schema.schema("ReissueRequest"))
                                    .responseSchema(Schema.schema("ReissueResponse"))
                                    .requestFields(
                                            fieldWithPath("refreshToken").description("리프레시 토큰")
                                    )
                                    .responseFields(responseFields)
                                    .build())
                    ));
        }

        @Test
        @DisplayName("폐기된 리프레시 토큰은 A005로 문서화한다")
        void invalid_refresh_token_is_documented() throws Exception {
            ReissueRequest request = AuthFixture.createReissueRequest();
            given(authUseCase.reissue(any()))
                    .willThrow(new GlobalException(ErrorCode.INVALID_REFRESH_TOKEN));

            mockMvc.perform(post("/api/v1/auth/reissue")
                            .with(csrf())
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("A005"))
                    .andDo(document("auth-reissue-error-a005",
                            resource(ResourceSnippetParameters.builder()
                                    .tag("Auth")
                                    .summary("토큰 재발급")
                                    .description("유효한 리프레시 토큰을 회전하며, 로그아웃 등으로 폐기된 토큰은 A005로 거부합니다.")
                                    .requestSchema(Schema.schema("ReissueRequest"))
                                    .responseSchema(Schema.schema("ErrorResponse"))
                                    .requestFields(fieldWithPath("refreshToken").description("리프레시 토큰"))
                                    .responseFields(commonResponseFieldsWithNoData())
                                    .build())
                    ));
        }
    }

    @Nested
    @DisplayName("로그아웃 API")
    class Logout {
        @Test
        @MockMember(id = 1L)
        @DisplayName("로그아웃 성공 시 200 OK를 반환한다")
        void logout_success() throws Exception {
            // when & then
            mockMvc.perform(post("/api/v1/auth/logout")
                            .with(csrf())
                            .header("Authorization", "Bearer {ACCESS_TOKEN}")
                            .contentType(APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andDo(document("auth-logout",
                            resource(ResourceSnippetParameters.builder()
                                    .tag("Auth")
                                    .summary("로그아웃")
                                    .description("Bearer 인증이 필수이며 서버에서 리프레시 토큰을 삭제합니다. 무인증은 A001로 거부합니다.")
                                    .requestHeaders(
                                            headerWithName("Authorization").description("Bearer Access Token")
                                    )
                                    .responseSchema(Schema.schema("EmptyDataResponse"))
                                    .responseFields(commonResponseFieldsWithNoData())
                                    .build())
                    ));
            
            verify(authUseCase).logout(1L);
        }
    }

    private FieldDescriptor exchangeCodeField() {
        return fieldWithPath("code")
                .description("43자리 Base64URL OAuth 일회용 교환 코드")
                .attributes(key("validationConstraints").value(List.of(
                        Map.of(
                                "name", "javax.validation.constraints.Pattern",
                                "configuration", Map.of("regexp", EXCHANGE_CODE_PATTERN)
                        )
                )));
    }
}

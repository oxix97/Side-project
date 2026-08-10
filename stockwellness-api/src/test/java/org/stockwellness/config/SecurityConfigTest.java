package org.stockwellness.config;

import java.util.List;

import com.epages.restdocs.apispec.ResourceSnippetParameters;
import com.epages.restdocs.apispec.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.stockwellness.application.port.in.stock.StockPriceUseCase;
import org.stockwellness.application.port.in.stock.StockSearchUseCase;
import org.stockwellness.application.port.in.stock.StockUseCase;
import org.stockwellness.application.port.in.stock.result.StockDetailResult;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.ResourceDocumentation.resource;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureRestDocs
@ActiveProfiles("test")
@DisplayName("Security 인가 설정 테스트")
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StockUseCase stockUseCase;

    @MockitoBean
    private StockPriceUseCase stockPriceUseCase;

    @MockitoBean
    private StockSearchUseCase stockSearchUseCase;

    @Test
    @DisplayName("비로그인 상태에서 포트폴리오 접근 시 A008 코드를 반환한다")
    void portfolios_unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/portfolios"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A008"));
    }

    @Test
    @DisplayName("비로그인 상태에서 관심 그룹 접근 시 A008 코드를 반환한다")
    void watchlist_unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/watchlist"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A008"));
    }

    @Test
    @DisplayName("비로그인 상태에서 회원 정보 접근 시 A008 코드를 반환한다")
    void members_unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/members/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A008"));
    }

    @Test
    @DisplayName("비로그인 상태에서 로그아웃 요청 시 A001을 반환한다")
    void logout_unauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A001"))
                .andDo(document("auth-logout-error-a001",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Auth")
                                .summary("로그아웃")
                                .description("Bearer 인증이 필수이며 서버에서 리프레시 토큰을 삭제합니다. 무인증은 A001로 거부합니다.")
                                .responseSchema(Schema.schema("ErrorResponse"))
                                .responseFields(errorResponseFields())
                                .build())
                ));
    }

    @Test
    @DisplayName("비로그인 상태에서 E2E 격리 증명 요청 시 A001을 반환한다")
    void e2e_attestation_unauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/test-support/attestation")
                        .contentType("application/json")
                        .content("{\"expectedDatabaseId\":\"isolated-e2e\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A001"))
                .andDo(document("test-support-e2e-attestation-error-a001",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Test Support")
                                .summary("Real E2E 격리 환경 증명")
                                .description("Bearer 인증 후 전용 회원과 서버 격리 DB를 검증합니다. 무인증은 A001, 불일치는 A002이며 test/e2e profile 전용입니다.")
                                .requestSchema(Schema.schema("E2eAttestationRequest"))
                                .requestFields(fieldWithPath("expectedDatabaseId").description("프론트 Real lane이 기대하는 격리 DB 식별자"))
                                .responseSchema(Schema.schema("ErrorResponse"))
                                .responseFields(errorResponseFields())
                                .build())
                ));
    }

    @Test
    @DisplayName("비로그인 상태에서 종목 상세 접근 시 200을 반환한다")
    void stocks_permitted() throws Exception {
        // given
        given(stockUseCase.getStockDetail(anyString()))
                .willReturn(null);

        // when & then
        mockMvc.perform(get("/api/v1/stocks/AAPL"))
                .andExpect(status().isOk());
    }

    private List<FieldDescriptor> errorResponseFields() {
        return List.of(
                fieldWithPath("success").type(JsonFieldType.BOOLEAN).description("성공 여부"),
                fieldWithPath("status").type(JsonFieldType.NUMBER).description("HTTP 상태 코드"),
                fieldWithPath("code").type(JsonFieldType.STRING).description("비즈니스 상세 코드"),
                fieldWithPath("message").type(JsonFieldType.STRING).description("결과 메시지"),
                fieldWithPath("timestamp").type(JsonFieldType.STRING).description("응답 시간"),
                fieldWithPath("traceId").type(JsonFieldType.STRING).description("에러 추적용 ID").optional(),
                fieldWithPath("errors").type(JsonFieldType.ARRAY).description("상세 필드 에러 목록")
        );
    }
}

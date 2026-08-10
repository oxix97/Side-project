package org.stockwellness.adapter.in.web.testsupport;

import java.util.ArrayList;
import java.util.List;

import com.epages.restdocs.apispec.ResourceSnippetParameters;
import com.epages.restdocs.apispec.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.stockwellness.application.service.testsupport.E2eIsolationAttestationResult;
import org.stockwellness.application.service.testsupport.E2eIsolationAttestationService;
import org.stockwellness.global.error.ErrorCode;
import org.stockwellness.global.error.exception.GlobalException;
import org.stockwellness.support.RestDocsSupport;
import org.stockwellness.support.annotation.MockMember;
import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.ResourceDocumentation.headerWithName;
import static com.epages.restdocs.apispec.ResourceDocumentation.resource;
import static org.mockito.BDDMockito.given;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Real E2E 격리 증명 컨트롤러")
class E2eIsolationAttestationControllerTest extends RestDocsSupport {

    @MockitoBean
    private E2eIsolationAttestationService attestationService;

    @Test
    @MockMember(id = 7L)
    @DisplayName("인증 회원과 격리 DB 증명 결과를 REST 계약으로 반환한다")
    void attest_success() throws Exception {
        given(attestationService.attest(7L, "isolated-e2e"))
                .willReturn(new E2eIsolationAttestationResult(7L, "isolated-e2e", true));

        List<FieldDescriptor> responseFields = new ArrayList<>(commonResponseFields());
        responseFields.addAll(List.of(
                fieldWithPath("data.memberId").description("전용 E2E 회원 ID"),
                fieldWithPath("data.databaseId").description("서버가 설정한 격리 데이터베이스 식별자"),
                fieldWithPath("data.isolated").description("회원과 격리 DB 검증 성공 여부")
        ));

        mockMvc.perform(post("/api/v1/test-support/attestation")
                        .with(csrf())
                        .header("Authorization", "Bearer {ACCESS_TOKEN}")
                        .contentType(APPLICATION_JSON)
                        .content("{\"expectedDatabaseId\":\"isolated-e2e\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberId").value(7L))
                .andExpect(jsonPath("$.data.databaseId").value("isolated-e2e"))
                .andExpect(jsonPath("$.data.isolated").value(true))
                .andExpect(jsonPath("$.data.accessToken").doesNotExist())
                .andExpect(jsonPath("$.data.connectionString").doesNotExist())
                .andDo(document("test-support-e2e-attestation",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Test Support")
                                .summary("Real E2E 격리 환경 증명")
                                .description("Bearer 인증 후 전용 회원과 서버 격리 DB를 검증합니다. 무인증은 A001, 불일치는 A002이며 test/e2e profile 전용입니다.")
                                .requestHeaders(headerWithName("Authorization").description("전용 E2E 계정 Bearer Access Token"))
                                .requestSchema(Schema.schema("E2eAttestationRequest"))
                                .responseSchema(Schema.schema("E2eAttestationResponse"))
                                .requestFields(fieldWithPath("expectedDatabaseId").description("프론트 Real lane이 기대하는 격리 DB 식별자"))
                                .responseFields(responseFields)
                                .build())
                ));
    }

    @Test
    @MockMember(id = 7L)
    @DisplayName("회원 또는 DB 불일치는 A002로 문서화한다")
    void attest_access_denied_is_documented() throws Exception {
        given(attestationService.attest(7L, "isolated-e2e"))
                .willThrow(new GlobalException(ErrorCode.ACCESS_DENIED));

        mockMvc.perform(post("/api/v1/test-support/attestation")
                        .with(csrf())
                        .header("Authorization", "Bearer {ACCESS_TOKEN}")
                        .contentType(APPLICATION_JSON)
                        .content("{\"expectedDatabaseId\":\"isolated-e2e\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A002"))
                .andDo(document("test-support-e2e-attestation-error-a002",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Test Support")
                                .summary("Real E2E 격리 환경 증명")
                                .description("Bearer 인증 후 전용 회원과 서버 격리 DB를 검증합니다. 무인증은 A001, 불일치는 A002이며 test/e2e profile 전용입니다.")
                                .requestHeaders(headerWithName("Authorization").description("전용 E2E 계정 Bearer Access Token"))
                                .requestSchema(Schema.schema("E2eAttestationRequest"))
                                .responseSchema(Schema.schema("ErrorResponse"))
                                .requestFields(fieldWithPath("expectedDatabaseId").description("프론트 Real lane이 기대하는 격리 DB 식별자"))
                                .responseFields(commonResponseFieldsWithNoData())
                                .build())
                ));
    }
}

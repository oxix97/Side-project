package org.stockwellness.adapter.in.web.error;

import java.util.List;
import java.util.Set;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.stockwellness.global.alert.SlackAlertService;
import org.stockwellness.global.error.ErrorCode;
import org.stockwellness.global.error.GlobalExceptionHandler;
import org.stockwellness.global.error.exception.GlobalException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private static final String SENSITIVE_TEST_MARKER = "do-not-log-validation-marker";

    private final SlackAlertService slackAlertService = mock(SlackAlertService.class);

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
            .setControllerAdvice(new GlobalExceptionHandler(slackAlertService))
            .build();

    private ListAppender<ILoggingEvent> listAppender;

    @BeforeEach
    void setUpLogCapture() {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);
    }

    @AfterEach
    void tearDownLogCapture() {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        logger.detachAppender(listAppender);
    }

    @Test
    @DisplayName("GlobalException 발생 시 표준 ApiResponse를 반환한다")
    void handleGlobalException_test() throws Exception {
        mockMvc.perform(get("/test/exception")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("M001"))
                .andExpect(jsonPath("$.message").value("회원을 찾을 수 없습니다."))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    @DisplayName("입력값 검증 실패 시 FieldError 목록을 포함한 ApiResponse를 반환한다")
    void handleBindingException_test() throws Exception {
        mockMvc.perform(post("/test/validation")
                        .content("{\"name\":\"\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("G001"))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].reason").exists());
    }

    @Test
    @DisplayName("민감한 validation 거부값은 응답 상세와 로그에 노출하지 않는다")
    void handleBindingException_doesNotExposeSensitiveRejectedValue() throws Exception {
        MvcResult result = mockMvc.perform(post("/test/sensitive-validation")
                        .content("{\"code\":\"" + SENSITIVE_TEST_MARKER + "\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("G001"))
                .andExpect(jsonPath("$.errors[0].field").value("code"))
                .andExpect(jsonPath("$.errors[0].value").value(""))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain(SENSITIVE_TEST_MARKER);

        List<String> validationLogs = listAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertThat(validationLogs)
                .anyMatch(message -> message.contains("field=code") && message.contains("constraint=Pattern"))
                .noneMatch(message -> message.contains(SENSITIVE_TEST_MARKER));
    }

    @Test
    @DisplayName("중첩·복합 민감 필드의 validation 거부값을 마스킹한다")
    void handleBindingException_masksNestedAndCompositeSensitiveFields() throws Exception {
        MvcResult result = mockMvc.perform(post("/test/nested-sensitive-validation")
                        .content("""
                                {
                                  "credentials": {"password": "%s"},
                                  "clientSecret": "%s",
                                  "apiToken": "%s",
                                  "authorizationCode": "%s"
                                }
                                """.formatted(
                                SENSITIVE_TEST_MARKER,
                                SENSITIVE_TEST_MARKER,
                                SENSITIVE_TEST_MARKER,
                                SENSITIVE_TEST_MARKER
                        ))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("G001"))
                .andReturn();

        assertSensitiveValueIsNotExposed(result);
        assertValidationMetadataLogged("credentials.password", "Pattern");
        assertValidationMetadataLogged("clientSecret", "Pattern");
        assertValidationMetadataLogged("apiToken", "Pattern");
        assertValidationMetadataLogged("authorizationCode", "Pattern");
    }

    @Test
    @DisplayName("method-level 민감 파라미터의 validation 거부값을 마스킹한다")
    void handleMethodValidationException_masksSensitiveParameter() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/method-validation")
                        .param("authorizationCode", SENSITIVE_TEST_MARKER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("G001"))
                .andReturn();

        assertSensitiveValueIsNotExposed(result);
        assertValidationMetadataLogged("authorizationCode", "Pattern");
    }

    @Test
    @DisplayName("ConstraintViolation 민감 필드의 validation 거부값을 마스킹한다")
    void handleConstraintViolationException_masksSensitiveField() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/constraint-validation"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("G001"))
                .andReturn();

        assertSensitiveValueIsNotExposed(result);
        assertValidationMetadataLogged("apiToken", "Pattern");
    }

    @Test
    @DisplayName("예상치 못한 500 에러 발생 시 Slack 알림을 트리거한다")
    void handleUnexpectedException_sendsSlackAlert() throws Exception {
        mockMvc.perform(get("/test/unexpected")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("G004"));

        verify(slackAlertService).sendErrorAlert(any(Exception.class), anyString(), any());
    }

    @Test
    @DisplayName("4xx BusinessException 발생 시 Slack 알림을 전송하지 않는다")
    void handleBusinessException_doesNotSendSlackAlert() throws Exception {
        mockMvc.perform(get("/test/exception")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());

        verify(slackAlertService, never()).sendErrorAlert(any(), anyString(), any());
        verify(slackAlertService, never()).sendInternalServerErrorAlert(anyString(), any());
    }

    @Test
    @DisplayName("지원하지 않는 메서드 호출 시 405 응답을 반환하고 Slack 알림을 전송하지 않는다")
    void handleMethodNotSupported_test() throws Exception {
        mockMvc.perform(post("/test/exception")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("G002"));

        verify(slackAlertService, never()).sendErrorAlert(any(), anyString(), any());
    }

    @RestController
    static class TestController {
        @GetMapping("/test/exception")
        public void throwBusinessException() {
            throw new GlobalException(ErrorCode.MEMBER_NOT_FOUND);
        }

        @GetMapping("/test/unexpected")
        public void throwUnexpectedException() {
            throw new RuntimeException("예상치 못한 오류");
        }

        @PostMapping("/test/validation")
        public void testValidation(@RequestBody @Valid TestDto dto) {
        }

        @PostMapping("/test/sensitive-validation")
        public void testSensitiveValidation(@RequestBody @Valid SensitiveTestDto dto) {
        }

        @PostMapping("/test/nested-sensitive-validation")
        public void testNestedSensitiveValidation(@RequestBody @Valid NestedSensitiveTestDto dto) {
        }

        @GetMapping("/test/method-validation")
        public void testMethodValidation(
                @RequestParam @Pattern(regexp = "^[A-Za-z0-9_-]{43}$") String authorizationCode
        ) {
        }

        @GetMapping("/test/constraint-validation")
        public void throwConstraintViolation() {
            Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
            Set<ConstraintViolation<ConstraintSensitiveTestDto>> violations = validator.validate(
                    new ConstraintSensitiveTestDto(SENSITIVE_TEST_MARKER)
            );
            throw new jakarta.validation.ConstraintViolationException(violations);
        }
    }

    record TestDto(@NotBlank String name) {}

    record SensitiveTestDto(@Pattern(regexp = "^[A-Za-z0-9_-]{43}$") String code) {}

    record NestedSensitiveTestDto(
            @Valid Credentials credentials,
            @Pattern(regexp = "^[A-Za-z0-9_-]{43}$") String clientSecret,
            @Pattern(regexp = "^[A-Za-z0-9_-]{43}$") String apiToken,
            @Pattern(regexp = "^[A-Za-z0-9_-]{43}$") String authorizationCode
    ) {}

    record Credentials(@Pattern(regexp = "^[A-Za-z0-9_-]{43}$") String password) {}

    record ConstraintSensitiveTestDto(@Pattern(regexp = "^[A-Za-z0-9_-]{43}$") String apiToken) {}

    private void assertSensitiveValueIsNotExposed(MvcResult result) throws Exception {
        assertThat(result.getResponse().getContentAsString()).doesNotContain(SENSITIVE_TEST_MARKER);
        assertThat(validationLogMessages()).noneMatch(message -> message.contains(SENSITIVE_TEST_MARKER));
    }

    private void assertValidationMetadataLogged(String field, String constraint) {
        assertThat(validationLogMessages())
                .anyMatch(message -> message.contains("field=" + field)
                        && message.contains("constraint=" + constraint));
    }

    private List<String> validationLogMessages() {
        return listAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}

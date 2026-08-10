package org.stockwellness.global.error;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.stockwellness.global.alert.SlackAlertService;
import org.stockwellness.global.common.response.ApiResponse;
import org.stockwellness.global.error.exception.BusinessException;

@Slf4j
@RequiredArgsConstructor
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Set<String> SENSITIVE_FIELD_MARKERS = Set.of(
            "code",
            "password",
            "token",
            "secret"
    );

    private final SlackAlertService slackAlertService;

    /**
     * 표준 예외 처리 통합 핸들러
     */
    @ExceptionHandler({
            BusinessException.class,
            MethodArgumentNotValidException.class,
            HandlerMethodValidationException.class,
            ConstraintViolationException.class,
            ExpiredJwtException.class,
            JwtException.class,
            NoResourceFoundException.class,
            HttpRequestMethodNotSupportedException.class,
            HttpMessageNotReadableException.class,
            Exception.class
    })
    public ResponseEntity<ApiResponse<Void>> handleAllExceptions(Exception e, HttpServletRequest request) {
        String traceId = generateTraceId();

        return switch (e) {
            case BusinessException be -> handleBusinessException(be, traceId);
            case MethodArgumentNotValidException me -> handleBindingException(me, traceId);
            case HandlerMethodValidationException hve -> handleHandlerMethodValidationException(hve, traceId);
            case ConstraintViolationException cve -> handleConstraintViolationException(cve, traceId);
            case ExpiredJwtException ignored -> createErrorResponse(ErrorCode.EXPIRED_JWT, traceId);
            case JwtException ignored -> createErrorResponse(ErrorCode.INVALID_JWT, traceId);
            case NoResourceFoundException ignored -> createErrorResponse(ErrorCode.RESOURCE_NOT_FOUND, traceId);
            case HttpRequestMethodNotSupportedException ignored -> createErrorResponse(ErrorCode.METHOD_NOT_ALLOWED, traceId);
            case HttpMessageNotReadableException ignored -> createErrorResponse(ErrorCode.INVALID_INPUT_VALUE, traceId);
            default -> handleUnexpectedException(e, traceId, request);
        };
    }

    private ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e, String traceId) {
        log.warn("[{}] BusinessException: {}", traceId, e.getMessage());
        return createErrorResponse(e.getErrorCode(), traceId);
    }

    private ResponseEntity<ApiResponse<Void>> handleBindingException(MethodArgumentNotValidException e, String traceId) {
        List<String> validationMetadata = e.getBindingResult().getFieldErrors().stream()
                .map(error -> "field=%s,constraint=%s".formatted(error.getField(), error.getCode()))
                .toList();
        log.warn("[{}] MethodArgumentNotValidException: {}", traceId, validationMetadata);
        List<ApiResponse.FieldError> fieldErrors = getFieldErrors(e.getBindingResult());
        return createErrorResponse(ErrorCode.INVALID_INPUT_VALUE, traceId, fieldErrors);
    }

    private ResponseEntity<ApiResponse<Void>> handleHandlerMethodValidationException(
            HandlerMethodValidationException e,
            String traceId
    ) {
        List<String> validationMetadata = e.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> "field=%s,constraint=%s".formatted(
                                parameterName(result),
                                constraintName(error)
                        )))
                .toList();
        log.warn("[{}] HandlerMethodValidationException: {}", traceId, validationMetadata);
        List<ApiResponse.FieldError> fieldErrors = e.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> toFieldError(result, error)))
                .toList();
        return createErrorResponse(ErrorCode.INVALID_INPUT_VALUE, traceId, fieldErrors);
    }

    private ResponseEntity<ApiResponse<Void>> handleConstraintViolationException(
            ConstraintViolationException e,
            String traceId
    ) {
        List<String> validationMetadata = e.getConstraintViolations().stream()
                .map(violation -> "field=%s,constraint=%s".formatted(
                        extractLeafNode(violation.getPropertyPath().toString()),
                        violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName()
                ))
                .toList();
        log.warn("[{}] ConstraintViolationException: {}", traceId, validationMetadata);
        List<ApiResponse.FieldError> fieldErrors = e.getConstraintViolations().stream()
                .map(violation -> new ApiResponse.FieldError(
                        extractLeafNode(violation.getPropertyPath().toString()),
                        sanitizedRejectedValue(
                                extractLeafNode(violation.getPropertyPath().toString()),
                                violation.getInvalidValue()
                        ),
                        violation.getMessage()
                ))
                .toList();
        return createErrorResponse(ErrorCode.INVALID_INPUT_VALUE, traceId, fieldErrors);
    }

    private ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception e, String traceId, HttpServletRequest request) {
        log.error("[{}] Unexpected Exception: ", traceId, e);
        slackAlertService.sendErrorAlert(e, traceId, request);
        return createErrorResponse(ErrorCode.INTERNAL_SERVER_ERROR, traceId);
    }

    private ResponseEntity<ApiResponse<Void>> createErrorResponse(ErrorCode errorCode, String traceId) {
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ApiResponse.error(errorCode, traceId));
    }

    private ResponseEntity<ApiResponse<Void>> createErrorResponse(ErrorCode errorCode, String traceId, List<ApiResponse.FieldError> fieldErrors) {
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ApiResponse.error(errorCode, traceId, fieldErrors));
    }

    private List<ApiResponse.FieldError> getFieldErrors(BindingResult bindingResult) {
        return bindingResult.getFieldErrors().stream()
                .map(error -> new ApiResponse.FieldError(
                        error.getField(),
                        sanitizedRejectedValue(error.getField(), error.getRejectedValue()),
                        error.getDefaultMessage()))
                .toList();
    }

    private String sanitizedRejectedValue(String field, Object rejectedValue) {
        if (rejectedValue == null || isSensitiveValidationField(field)) {
            return "";
        }
        return rejectedValue.toString();
    }

    private ApiResponse.FieldError toFieldError(ParameterValidationResult result, MessageSourceResolvable error) {
        String parameterName = parameterName(result);
        Object argument = result.getArgument();

        return new ApiResponse.FieldError(
                parameterName,
                sanitizedRejectedValue(parameterName, argument),
                error.getDefaultMessage()
        );
    }

    private String parameterName(ParameterValidationResult result) {
        String parameterName = result.getMethodParameter().getParameterName();
        return parameterName == null ? "unknown" : parameterName;
    }

    private String constraintName(MessageSourceResolvable error) {
        String[] codes = error.getCodes();
        if (codes == null || codes.length == 0) {
            return "unknown";
        }
        return codes[codes.length - 1];
    }

    private boolean isSensitiveValidationField(String field) {
        if (field == null || field.equals("unknown")) {
            return true;
        }
        String normalizedLeafName = extractLeafNode(field)
                .replaceAll("[^A-Za-z0-9]", "")
                .toLowerCase(Locale.ROOT);
        return SENSITIVE_FIELD_MARKERS.stream().anyMatch(normalizedLeafName::contains);
    }

    private String extractLeafNode(String propertyPath) {
        int separatorIndex = propertyPath.lastIndexOf('.');
        return separatorIndex >= 0 ? propertyPath.substring(separatorIndex + 1) : propertyPath;
    }

    private String generateTraceId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}

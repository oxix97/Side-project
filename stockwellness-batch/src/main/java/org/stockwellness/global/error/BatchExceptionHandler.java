package org.stockwellness.global.error;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.stockwellness.global.common.response.ApiResponse;
import org.stockwellness.global.error.exception.BusinessException;

@RestControllerAdvice
public class BatchExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ApiResponse.error(errorCode, UUID.randomUUID().toString().substring(0, 8)));
    }
}

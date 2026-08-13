package org.stockwellness.adapter.in.web.batch.dto;

/**
 * 배치 실행 API 요청에 대한 표준 응답 DTO
 */
public record BatchExecutionResponse(
        Long executionId,
        String jobName,
        String statusUrl,
        String message
) {
}

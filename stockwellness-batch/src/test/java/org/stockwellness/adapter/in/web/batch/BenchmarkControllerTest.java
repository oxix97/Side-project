package org.stockwellness.adapter.in.web.batch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.stockwellness.application.port.in.batch.BatchControlUseCase;
import org.stockwellness.global.error.ErrorCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BenchmarkController.class)
@AutoConfigureMockMvc(addFilters = false) // Security 필터 비활성화 (테스트 편의성)
class BenchmarkControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BatchControlUseCase batchControlUseCase;

    @Test
    @DisplayName("배치 수동 실행 API 성공 테스트")
    void syncBenchmarkPrice_success() throws Exception {
        when(batchControlUseCase.launchAsync(any())).thenReturn(
                new BatchControlUseCase.BatchExecutionResult(
                        101L,
                        "benchmarkPriceSyncJob",
                        "STARTING",
                        "/api/v1/admin/batch/status/benchmarkPriceSyncJob",
                        "배치 잡 [benchmarkPriceSyncJob]이 시작되었습니다. (ExecutionId: 101)"
                )
        );

        mockMvc.perform(post("/api/v1/admin/batch/benchmark-sync")
                        .param("startDate", "20260301"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.executionId").value(101))
                .andExpect(jsonPath("$.data.jobName").value("benchmarkPriceSyncJob"))
                .andExpect(jsonPath("$.data.statusUrl").value("/api/v1/admin/batch/status/benchmarkPriceSyncJob"))
                .andExpect(jsonPath("$.data.message").exists());

        verify(batchControlUseCase).launchAsync(any());
    }

    @Test
    @DisplayName("배치 수동 실행 API 실패 테스트 - 잘못된 날짜 형식")
    void syncBenchmarkPrice_invalidDate() throws Exception {
        mockMvc.perform(post("/api/v1/admin/batch/benchmark-sync")
                        .param("startDate", "2026-03-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT_VALUE.getCode()));
    }
}

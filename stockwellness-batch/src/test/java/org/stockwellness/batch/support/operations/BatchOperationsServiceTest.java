package org.stockwellness.batch.support.operations;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.test.util.ReflectionTestUtils;
import org.stockwellness.adapter.out.persistence.stock.repository.StockPriceRepository;
import org.stockwellness.application.port.in.batch.BatchControlUseCase;
import org.stockwellness.application.port.out.stock.StockPort;
import org.stockwellness.application.service.batch.MarketIndexSyncService;
import org.stockwellness.batch.support.exception.BatchException;
import org.stockwellness.global.error.ErrorCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class BatchOperationsServiceTest {

    @Mock
    private JobExplorer jobExplorer;

    @Mock
    private JobLauncher jobLauncher;

    @Mock
    private JobLauncher asyncJobLauncher;

    @Mock
    private JobOperator jobOperator;

    @Mock
    private JobRepository jobRepository;

    @Mock
    private Job stockMasterSyncJob;

    @Mock
    private Job stockPriceBatchJob;

    @Mock
    private Job sectorEodJob;

    @Mock
    private Job portfolioStatsJob;

    @Mock
    private Job benchmarkPriceSyncJob;

    @Mock
    private Job stockInvestorTradeDetailJob;

    @Mock
    private Job backfillMarketWeatherJob;

    @Mock
    private StockPort stockPort;

    @Mock
    private MarketIndexSyncService marketIndexSyncService;

    @Mock
    private StockPriceRepository stockPriceRepository;

    @InjectMocks
    private BatchOperationsService batchOperationsService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(batchOperationsService, "asyncJobLauncher", asyncJobLauncher);
        ReflectionTestUtils.setField(batchOperationsService, "jobLauncher", jobLauncher);
        ReflectionTestUtils.setField(batchOperationsService, "stockPriceBatchJob", stockPriceBatchJob);
        ReflectionTestUtils.setField(batchOperationsService, "benchmarkPriceSyncJob", benchmarkPriceSyncJob);
    }

    @Test
    @DisplayName("종목 마스터 동기화는 null startDate와 endDate를 JobParameters에 추가하지 않는다")
    void buildParameters_stockMasterSync_omitsNullDates() {
        JobParameters parameters = ReflectionTestUtils.invokeMethod(batchOperationsService, "buildParameters",
                new BatchControlUseCase.BatchLaunchCommand(
                BatchControlUseCase.BatchJobType.STOCK_MASTER_SYNC,
                null,
                null,
                null,
                null,
                false
        ));

        assertThat(parameters.getString("startDate")).isNull();
        assertThat(parameters.getString("endDate")).isNull();
        assertThat(parameters.getLong("time")).isNotNull();
    }

    @Test
    @DisplayName("투자주체 보정은 baseDate가 null이면 해당 파라미터를 JobParameters에 추가하지 않는다")
    void buildParameters_stockForeignInstitution_omitsNullBaseDate() {
        JobParameters parameters = ReflectionTestUtils.invokeMethod(batchOperationsService, "buildParameters",
                new BatchControlUseCase.BatchLaunchCommand(
                BatchControlUseCase.BatchJobType.STOCK_FOREIGN_INSTITUTION,
                null,
                null,
                null,
                null,
                false
        ));

        assertThat(parameters.getString("targetDate")).isNull();
        assertThat(parameters.getLong("time")).isNotNull();
    }

    @Test
    @DisplayName("벤치마크 동기화는 명시 날짜가 있을 때만 startDate와 endDate를 JobParameters에 추가한다")
    void buildParameters_benchmarkPriceSync_addsNormalizedDatesWhenPresent() {
        JobParameters parameters = ReflectionTestUtils.invokeMethod(batchOperationsService, "buildParameters",
                new BatchControlUseCase.BatchLaunchCommand(
                BatchControlUseCase.BatchJobType.BENCHMARK_PRICE_SYNC,
                null,
                "2026-04-01",
                "20260408",
                null,
                false
        ));

        assertThat(parameters.getString("startDate")).isEqualTo("20260401");
        assertThat(parameters.getString("endDate")).isEqualTo("20260408");
        assertThat(parameters.getLong("timestamp")).isNotNull();
    }

    @Test
    @DisplayName("시장 날씨 소급 배치는 고유 실행 파라미터만 추가한다")
    void buildParameters_marketWeatherBackfill_addsOnlyRunId() {
        JobParameters parameters = ReflectionTestUtils.invokeMethod(batchOperationsService, "buildParameters",
                new BatchControlUseCase.BatchLaunchCommand(
                        BatchControlUseCase.BatchJobType.MARKET_WEATHER_BACKFILL,
                        null,
                        null,
                        null,
                        null,
                        false
                ));

        assertThat(UUID.fromString(parameters.getString("runId"))).isNotNull();
        assertThat(parameters.getLong("time")).isNull();
        assertThat(parameters.getParameters()).hasSize(1);
    }

    @Test
    @DisplayName("비동기 배치의 중복 검사와 실행 요청은 하나의 임계 구역에서 처리한다")
    void launchAsync_serializesConcurrentChecksAndLaunches() throws Exception {
        int modifiers = BatchOperationsService.class
                .getMethod("launchAsync", BatchControlUseCase.BatchLaunchCommand.class)
                .getModifiers();

        assertThat(Modifier.isSynchronized(modifiers)).isTrue();
    }

    @Test
    @DisplayName("시세 동기화 배치가 이미 실행 중이면 중복 실행을 차단한다")
    void launchAsync_stockPriceSync_blocksWhenJobAlreadyRunning() throws Exception {
        given(jobExplorer.findRunningJobExecutions("stockPriceBatchJob"))
                .willReturn(Set.of(Mockito.mock(JobExecution.class)));

        BatchControlUseCase.BatchLaunchCommand command = new BatchControlUseCase.BatchLaunchCommand(
                BatchControlUseCase.BatchJobType.STOCK_PRICE_SYNC,
                null,
                null,
                null,
                null,
                false
        );

        assertThatThrownBy(() -> batchOperationsService.launchAsync(command))
                .isInstanceOfSatisfying(BatchException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BATCH_JOB_ALREADY_RUNNING));

        verify(asyncJobLauncher, never()).run(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    @DisplayName("KIS 호출 배치는 이미 실행 중이면 중복 실행을 차단한다")
    void launchAsync_benchmarkSync_blocksWhenJobAlreadyRunning() throws Exception {
        given(jobExplorer.findRunningJobExecutions("benchmarkPriceSyncJob"))
                .willReturn(Set.of(Mockito.mock(JobExecution.class)));

        BatchControlUseCase.BatchLaunchCommand command = new BatchControlUseCase.BatchLaunchCommand(
                BatchControlUseCase.BatchJobType.BENCHMARK_PRICE_SYNC,
                null,
                "20260401",
                "20260408",
                null,
                false
        );

        assertThatThrownBy(() -> batchOperationsService.launchAsync(command))
                .isInstanceOfSatisfying(BatchException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BATCH_JOB_ALREADY_RUNNING));

        verify(asyncJobLauncher, never()).run(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    @DisplayName("시장 날씨 소급 배치가 이미 실행 중이면 중복 실행을 차단한다")
    void launchAsync_marketWeatherBackfill_blocksWhenJobAlreadyRunning() throws Exception {
        given(jobExplorer.findRunningJobExecutions("backfillMarketWeatherJob"))
                .willReturn(Set.of(Mockito.mock(JobExecution.class)));

        BatchControlUseCase.BatchLaunchCommand command = new BatchControlUseCase.BatchLaunchCommand(
                BatchControlUseCase.BatchJobType.MARKET_WEATHER_BACKFILL,
                null,
                null,
                null,
                null,
                false
        );

        assertThatThrownBy(() -> batchOperationsService.launchAsync(command))
                .isInstanceOfSatisfying(BatchException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BATCH_JOB_ALREADY_RUNNING));

        verify(asyncJobLauncher, never()).run(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    @DisplayName("DB 보정 배치는 다른 실행이 있어도 중복 실행 차단 대상이 아니다")
    void launchAsync_prevCloseSync_doesNotBlockWhenJobAlreadyRunning() throws Exception {
        JobExecution jobExecution = Mockito.mock(JobExecution.class);
        given(jobExecution.getId()).willReturn(99L);

        JobInstance jobInstance = Mockito.mock(JobInstance.class);
        given(jobExecution.getJobInstance()).willReturn(jobInstance);
        given(jobInstance.getJobName()).willReturn("stockPriceBatchJob");
        given(jobExecution.getStatus()).willReturn(BatchStatus.COMPLETED);
        given(asyncJobLauncher.run(ArgumentMatchers.eq(stockPriceBatchJob), ArgumentMatchers.any()))
                .willReturn(jobExecution);

        BatchControlUseCase.BatchLaunchCommand command = new BatchControlUseCase.BatchLaunchCommand(
                BatchControlUseCase.BatchJobType.STOCK_PRICE_PREV_CLOSE_SYNC,
                null,
                "20260401",
                "20260408",
                null,
                false
        );

        BatchControlUseCase.BatchExecutionResult result = batchOperationsService.launchAsync(command);

        assertThat(result.jobName()).isEqualTo("stockPriceBatchJob");
        verifyNoInteractions(jobExplorer);
        verify(asyncJobLauncher).run(ArgumentMatchers.eq(stockPriceBatchJob), ArgumentMatchers.any());
    }

    @Test
    @DisplayName("abandon은 실행 중인 배치를 FAILED 상태로 변경한다")
    void abandon_marksRunningJobAsFailed() {
        Long executionId = 1L;
        JobExecution jobExecution = mock(JobExecution.class);
        StepExecution stepExecution = mock(StepExecution.class);

        given(jobExplorer.getJobExecution(executionId)).willReturn(jobExecution);
        given(jobExecution.getStatus()).willReturn(BatchStatus.STARTED);
        given(jobExecution.getStepExecutions()).willReturn(List.of(stepExecution));
        given(stepExecution.getStatus()).willReturn(BatchStatus.STARTED);

        String result = batchOperationsService.abandon(executionId);

        assertThat(result).contains("강제 종료 처리했습니다");
        verify(jobExecution).setStatus(BatchStatus.FAILED);
        verify(stepExecution).setStatus(BatchStatus.FAILED);
        verify(jobRepository).update(jobExecution);
        verify(jobRepository).update(stepExecution);
    }

    @Test
    @DisplayName("cleanupStuckJobs는 모든 실행 중인 배치를 FAILED 상태로 변경한다")
    void cleanupStuckJobs_marksAllRunningJobsAsFailed() {
        String jobName = "testJob";
        JobExecution jobExecution = mock(JobExecution.class);
        given(jobExplorer.getJobNames()).willReturn(List.of(jobName));
        given(jobExplorer.findRunningJobExecutions(jobName)).willReturn(Set.of(jobExecution));
        given(jobExecution.getStepExecutions()).willReturn(List.of());

        batchOperationsService.cleanupStuckJobs();

        verify(jobExecution).setStatus(BatchStatus.FAILED);
        verify(jobRepository).update(jobExecution);
    }
}

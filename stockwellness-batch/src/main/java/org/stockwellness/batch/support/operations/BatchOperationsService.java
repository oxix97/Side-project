package org.stockwellness.batch.support.operations;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.stockwellness.adapter.out.persistence.stock.repository.StockPriceRepository;
import org.stockwellness.application.port.in.batch.BatchControlUseCase;
import org.stockwellness.application.port.in.batch.BatchMonitoringUseCase;
import org.stockwellness.application.port.out.stock.StockPort;
import org.stockwellness.application.service.batch.MarketIndexSyncService;
import org.stockwellness.batch.support.exception.BatchException;
import org.stockwellness.domain.stock.price.PriceIssueType;
import org.stockwellness.global.error.ErrorCode;
import org.stockwellness.global.util.DateUtil;

@Slf4j
@Service
public class BatchOperationsService implements BatchControlUseCase, BatchMonitoringUseCase {

    private static final LocalDate MIN_PRICE_SYNC_DATE = LocalDate.of(2022, 1, 1);
    private static final Set<BatchJobType> KIS_BOUND_JOB_TYPES = EnumSet.of(
            BatchJobType.STOCK_MASTER_SYNC,
            BatchJobType.STOCK_PRICE_SYNC,
            BatchJobType.SECTOR_EOD_SYNC,
            BatchJobType.BENCHMARK_PRICE_SYNC,
            BatchJobType.STOCK_FOREIGN_INSTITUTION
    );

    private final JobLauncher jobLauncher;
    private final JobLauncher asyncJobLauncher;
    private final JobExplorer jobExplorer;
    private final JobOperator jobOperator;
    private final JobRepository jobRepository;
    private final Job stockMasterSyncJob;
    private final Job stockPriceBatchJob;
    private final Job sectorEodJob;
    private final Job portfolioStatsJob;
    private final Job benchmarkPriceSyncJob;
    private final Job stockInvestorTradeDetailJob;
    private final StockPort stockPort;
    private final MarketIndexSyncService marketIndexSyncService;
    private final StockPriceRepository stockPriceRepository;

    public BatchOperationsService(
            @Qualifier("jobLauncher") JobLauncher jobLauncher,
            @Qualifier("asyncJobLauncher") JobLauncher asyncJobLauncher,
            JobExplorer jobExplorer,
            JobOperator jobOperator,
            JobRepository jobRepository,
            @Qualifier("stockMasterSyncJob") Job stockMasterSyncJob,
            @Qualifier("dailyStockPriceBatchJob") Job stockPriceBatchJob,
            @Qualifier("sectorEodJob") Job sectorEodJob,
            @Qualifier("portfolioStatsJob") Job portfolioStatsJob,
            @Qualifier("benchmarkPriceSyncJob") Job benchmarkPriceSyncJob,
            @Qualifier("stockInvestorTradeDetailJob") Job stockInvestorTradeDetailJob,
            StockPort stockPort,
            MarketIndexSyncService marketIndexSyncService,
            StockPriceRepository stockPriceRepository
    ) {
        this.jobLauncher = jobLauncher;
        this.asyncJobLauncher = asyncJobLauncher;
        this.jobExplorer = jobExplorer;
        this.jobOperator = jobOperator;
        this.jobRepository = jobRepository;
        this.stockMasterSyncJob = stockMasterSyncJob;
        this.stockPriceBatchJob = stockPriceBatchJob;
        this.sectorEodJob = sectorEodJob;
        this.portfolioStatsJob = portfolioStatsJob;
        this.benchmarkPriceSyncJob = benchmarkPriceSyncJob;
        this.stockInvestorTradeDetailJob = stockInvestorTradeDetailJob;
        this.stockPort = stockPort;
        this.marketIndexSyncService = marketIndexSyncService;
        this.stockPriceRepository = stockPriceRepository;
    }

    @Override
    public BatchExecutionResult launchAsync(BatchLaunchCommand command) {
        Job job = resolveJob(command.jobType());
        ensureNoRunningPriceSync(command, job);
        JobParameters parameters = buildParameters(command);
        return toResult(runJob(asyncJobLauncher, job, parameters));
    }

    @Override
    public BatchExecutionResult launchSync(BatchLaunchCommand command) {
        Job job = resolveJob(command.jobType());
        ensureNoRunningPriceSync(command, job);
        JobParameters parameters = buildParameters(command);
        return toResult(runJob(jobLauncher, job, parameters));
    }

    @Override
    public BatchExecutionResult syncIndices(MarketIndexSyncCommand command) {
        try {
            marketIndexSyncService.syncIndices(command.fileName());
            return new BatchExecutionResult(
                    0L,
                    BatchJobType.MARKET_INDEX_SYNC.jobName(),
                    "COMPLETED",
                    null,
                    "업종 마스터 동기화 성공"
            );
        } catch (Exception e) {
            log.error("업종 마스터 동기화 실패", e);
            throw new IllegalStateException("업종 마스터 동기화 실패", e);
        }
    }

    @Override
    public String stop(BatchStopCommand command) {
        try {
            boolean result = jobOperator.stop(command.executionId());
            return result ? "중단 요청 성공" : "중단 실패 (이미 종료되었을 수 있음)";
        } catch (Exception e) {
            return "오류 발생: " + e.getMessage();
        }
    }

    @Override
    public String abandon(Long executionId) {
        JobExecution jobExecution = jobExplorer.getJobExecution(executionId);
        if (jobExecution == null) {
            return "존재하지 않는 executionId입니다: " + executionId;
        }

        if (jobExecution.getStatus().isRunning()) {
            markExecutionAsFailed(jobExecution);
            return "배치 실행을 강제 종료 처리했습니다. (FAILED)";
        }
        return "이미 종료된 배치입니다. (Status: " + jobExecution.getStatus() + ")";
    }

    public void cleanupStuckJobs() {
        log.info("[배치] 시작 시 멈춰있는 배치 정리 시작...");
        for (String jobName : jobExplorer.getJobNames()) {
            Set<JobExecution> runningExecutions = jobExplorer.findRunningJobExecutions(jobName);
            for (JobExecution execution : runningExecutions) {
                log.warn("[배치] 멈춰있는 배치 발견: id={}, jobName={}, status={}. FAILED로 마킹합니다.",
                        execution.getId(), jobName, execution.getStatus());
                markExecutionAsFailed(execution);
            }
        }
        log.info("[배치] 시작 시 멈춰있는 배치 정리 완료");
    }

    private void markExecutionAsFailed(JobExecution jobExecution) {
        LocalDateTime now = LocalDateTime.now();
        jobExecution.setStatus(BatchStatus.FAILED);
        jobExecution.setExitStatus(ExitStatus.FAILED);
        jobExecution.setEndTime(now);

        for (StepExecution stepExecution : jobExecution.getStepExecutions()) {
            if (stepExecution.getStatus().isRunning()) {
                stepExecution.setStatus(BatchStatus.FAILED);
                stepExecution.setExitStatus(ExitStatus.FAILED);
                stepExecution.setEndTime(now);
                jobRepository.update(stepExecution);
            }
        }
        jobRepository.update(jobExecution);
    }

    @Override
    public List<BatchJobStatusResult> getRecentJobStatuses(String jobName, int limit) {
        return jobExplorer.getJobInstances(jobName, 0, limit).stream()
                .flatMap(instance -> jobExplorer.getJobExecutions(instance).stream())
                .map(execution -> new BatchJobStatusResult(
                        execution.getId(),
                        execution.getJobInstance().getJobName(),
                        execution.getStatus().name(),
                        execution.getStartTime(),
                        execution.getEndTime(),
                        execution.getExitStatus().getExitCode()
                ))
                .sorted((left, right) -> right.executionId().compareTo(left.executionId()))
                .toList();
    }

    @Override
    public DataIntegrityResult checkDataIntegrity(LocalDate startDate, LocalDate endDate) {
        var invalidPrices = stockPriceRepository.findInvalidPrices(startDate, endDate);
        List<InvalidPriceIssue> issues = invalidPrices.stream()
                .map(price -> new InvalidPriceIssue(
                        price.getStock().getTicker(),
                        price.getStock().getName(),
                        price.getId().getBaseDate(),
                        price.getClosePrice() == null ? PriceIssueType.NULL_PRICE.name() : PriceIssueType.ZERO_PRICE.name()
                ))
                .toList();
        return new DataIntegrityResult(issues.size(), issues);
    }

    private Job resolveJob(BatchJobType jobType) {
        return switch (jobType) {
            case STOCK_MASTER_SYNC -> stockMasterSyncJob;
            case STOCK_PRICE_SYNC, STOCK_PRICE_PREV_CLOSE_SYNC -> stockPriceBatchJob;
            case SECTOR_EOD_SYNC -> sectorEodJob;
            case PORTFOLIO_STATS_SYNC -> portfolioStatsJob;
            case BENCHMARK_PRICE_SYNC -> benchmarkPriceSyncJob;
            case STOCK_FOREIGN_INSTITUTION -> stockInvestorTradeDetailJob;
            case MARKET_INDEX_SYNC -> throw new IllegalArgumentException("MARKET_INDEX_SYNC는 Job이 아닌 직접 동기화입니다.");
        };
    }

    private JobExecution runJob(JobLauncher launcher, Job job, JobParameters parameters) {
        try {
            return launcher.run(job, parameters);
        } catch (Exception e) {
            log.error("잡 실행 실패: {}", job.getName(), e);
            throw new IllegalStateException("배치 실행 실패: " + job.getName(), e);
        }
    }

    private void ensureNoRunningPriceSync(BatchLaunchCommand command, Job job) {
        if (!KIS_BOUND_JOB_TYPES.contains(command.jobType())) {
            return;
        }

        String jobName = command.jobType().jobName();
        var runningExecutions = jobExplorer.findRunningJobExecutions(jobName);
        if (runningExecutions == null || runningExecutions.isEmpty()) {
            return;
        }

        log.warn("[배치] KIS 연동 배치 중복 실행 차단 jobType={}, jobName={}, runningExecutionCount={}",
                command.jobType(), jobName, runningExecutions.size());
        throw new BatchException(ErrorCode.BATCH_JOB_ALREADY_RUNNING);
    }

    private JobParameters buildParameters(BatchLaunchCommand command) {
        validateTargetTicker(command);

        JobParametersBuilder builder = new JobParametersBuilder();
        switch (command.jobType()) {
            case STOCK_MASTER_SYNC, SECTOR_EOD_SYNC, STOCK_PRICE_SYNC, STOCK_PRICE_PREV_CLOSE_SYNC, PORTFOLIO_STATS_SYNC, STOCK_FOREIGN_INSTITUTION -> builder.addLong("time", System.currentTimeMillis());
            case BENCHMARK_PRICE_SYNC -> builder.addLong("timestamp", System.currentTimeMillis());
            case MARKET_INDEX_SYNC -> {
            }
        }

        if (command.jobType() == BatchJobType.STOCK_PRICE_SYNC || command.jobType() == BatchJobType.STOCK_PRICE_PREV_CLOSE_SYNC) {
            String normalizedStartDate = normalizePriceDate(command.startDate(), true);
            String normalizedEndDate = normalizePriceDate(command.endDate(), false);
            addStringIfPresent(builder, "startDate", normalizedStartDate);
            addStringIfPresent(builder, "endDate", normalizedEndDate);
            addStringIfPresent(builder, "targetDate", normalizePriceDate(command.targetDate() != null ? command.targetDate() : normalizedEndDate, false));
        } else if (command.jobType() == BatchJobType.BENCHMARK_PRICE_SYNC) {
            addStringIfPresent(builder, "startDate", normalizeDate(command.startDate()));
            addStringIfPresent(builder, "endDate", normalizeDate(command.endDate()));
        } else if (command.jobType() == BatchJobType.STOCK_FOREIGN_INSTITUTION) {
            addStringIfPresent(builder, "targetDate", normalizeDate(command.targetDate()));
        }

        if (command.targetTicker() != null && command.jobType() != BatchJobType.STOCK_FOREIGN_INSTITUTION) {
            builder.addString("targetTicker", command.targetTicker());
        }
        if (command.jobType() == BatchJobType.STOCK_PRICE_SYNC) {
            builder.addString("publishEvent", String.valueOf(command.publishEvent()));
        }
        return builder.toJobParameters();
    }

    private void addStringIfPresent(JobParametersBuilder builder, String key, String value) {
        if (value != null) {
            builder.addString(key, value);
        }
    }

    private BatchExecutionResult toResult(JobExecution execution) {
        String jobName = execution.getJobInstance().getJobName();
        return new BatchExecutionResult(
                execution.getId(),
                jobName,
                execution.getStatus().name(),
                "/api/v1/admin/batch/status/" + jobName,
                String.format("배치 잡 [%s]이 시작되었습니다. (ExecutionId: %d)", jobName, execution.getId())
        );
    }

    private void validateTargetTicker(BatchLaunchCommand command) {
        if (command.targetTicker() == null || command.targetTicker().isBlank()) {
            return;
        }
        if (command.jobType() == BatchJobType.STOCK_PRICE_PREV_CLOSE_SYNC
                && !stockPort.existsByTicker(command.targetTicker())) {
            throw new IllegalArgumentException("존재하지 않는 종목입니다: " + command.targetTicker());
        }
    }

    private String normalizePriceDate(String rawDate, boolean applyLowerBound) {
        LocalDate date = DateUtil.parseFlexible(rawDate);
        if (date == null) {
            return null;
        }
        if (applyLowerBound && date.isBefore(MIN_PRICE_SYNC_DATE)) {
            date = MIN_PRICE_SYNC_DATE;
        }
        return DateUtil.format(date);
    }

    private String normalizeDate(String rawDate) {
        LocalDate date = DateUtil.parseFlexible(rawDate);
        return date != null ? DateUtil.format(date) : null;
    }
}

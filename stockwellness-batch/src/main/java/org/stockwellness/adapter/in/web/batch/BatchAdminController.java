package org.stockwellness.adapter.in.web.batch;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.stockwellness.adapter.batch.stockprice.support.StockPriceSyncRequest;
import org.stockwellness.adapter.in.scheduler.DailyBatchOrchestrationService;
import org.stockwellness.adapter.in.scheduler.DailyBatchOrchestrator;
import org.stockwellness.adapter.in.web.batch.dto.BatchExecutionResponse;
import org.stockwellness.adapter.in.web.batch.dto.BatchJobStatusResponse;
import org.stockwellness.adapter.in.web.batch.dto.DailyFullSyncRequest;
import org.stockwellness.adapter.in.web.batch.dto.DataIntegrityResponse;
import org.stockwellness.adapter.out.external.kis.adapter.KisDailyPriceAdapter;
import org.stockwellness.adapter.out.external.kis.dto.KisMultiStockPriceDetail;
import org.stockwellness.application.port.in.batch.BatchControlUseCase;
import org.stockwellness.application.port.in.batch.BatchMonitoringUseCase;
import org.stockwellness.batch.support.exception.BatchException;
import org.stockwellness.global.common.response.ApiResponse;
import org.stockwellness.global.error.ErrorCode;
import org.stockwellness.global.util.DateUtil;

@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/batch")
@RestController
public class BatchAdminController {

    private final BatchControlUseCase batchControlUseCase;
    private final BatchMonitoringUseCase batchMonitoringUseCase;
    private final DailyBatchOrchestrationService dailyBatchOrchestrationService;
    private final KisDailyPriceAdapter kisDailyPriceAdapter;
    private final DailyBatchOrchestrator dailyBatchOrchestrator;

    /**
     * KIS 어댑터를 직접 호출하여 여러 종목의 현재가/시세를 즉시 조회 (테스트/관리용)
     */
    @GetMapping("/fetch-multi-prices")
    public ApiResponse<List<KisMultiStockPriceDetail>> fetchMultiPrices(@RequestParam List<String> tickers) {
        return ApiResponse.success(kisDailyPriceAdapter.fetchMultiStockPrices(tickers));
    }

    /**
     * 업종/지수 마스터(idxcode.mst) 동기화
     */
    @PostMapping("/sync-indices")
    public ApiResponse<BatchExecutionResponse> runIndicesSync() {
        var result = batchControlUseCase.syncIndices(new BatchControlUseCase.MarketIndexSyncCommand("idxcode.mst"));
        return ApiResponse.success(new BatchExecutionResponse(result.executionId(), result.jobName(), result.statusUrl(), result.message()));
    }

    /**
     * 특정 Job의 최근 실행 상태 조회
     */
    @GetMapping("/status/{jobName}")
    public ApiResponse<List<BatchJobStatusResponse>> getJobStatus(@PathVariable String jobName) {
        List<BatchJobStatusResponse> responses = batchMonitoringUseCase.getRecentJobStatuses(jobName, 10).stream()
                .map(result -> new BatchJobStatusResponse(
                        result.executionId(),
                        result.jobName(),
                        result.status(),
                        result.startTime(),
                        result.endTime(),
                        result.exitCode()
                ))
                .toList();
        return ApiResponse.success(responses);
    }

    /**
     * 시세 데이터 정합성 체크 (누락 또는 0원 시세)
     */
    @GetMapping("/integrity-check")
    public ApiResponse<DataIntegrityResponse> checkDataIntegrity(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) {
        var result = batchMonitoringUseCase.checkDataIntegrity(startDate, endDate);
        List<DataIntegrityResponse.InvalidPriceDetail> details = result.issues().stream()
                .map(issue -> new DataIntegrityResponse.InvalidPriceDetail(
                        issue.ticker(),
                        issue.name(),
                        issue.baseDate(),
                        issue.issueType()
                ))
                .toList();
        return ApiResponse.success(new DataIntegrityResponse(result.totalCount(), details));
    }

    /**
     * 종목 마스터(KOSPI/KOSDAQ) 동기화 배치 실행
     */
    @PostMapping("/sync-master")
    public ApiResponse<BatchExecutionResponse> runMasterSync() {
        return ApiResponse.success(toExecutionResponse(batchControlUseCase.launchAsync(
                new BatchControlUseCase.BatchLaunchCommand(
                        BatchControlUseCase.BatchJobType.STOCK_MASTER_SYNC,
                        null,
                        null,
                        null,
                        null,
                        false
                )
        )));
    }

    /**
     * 스케줄러와 동일한 6단계 일일 오케스트레이션 수동 실행
     */
    @PostMapping("/run-daily-full-sync")
    public ApiResponse<Void> runDailyFullSync(@RequestBody(required = false) DailyFullSyncRequest request) {
        LocalDate businessDate = request != null && StringUtils.hasText(request.endDate())
                ? DateUtil.parse(request.endDate())
                : null;
        dailyBatchOrchestrationService.runDailyFullSync(businessDate);
        return ApiResponse.success();
    }

    @PostMapping("/run-daily-sync")
    public ApiResponse<Map<String, String>> runDailySync() {
        long totalStartTime = System.currentTimeMillis();

        // 1. 지수 동기화 (KOSPI, S&P500 등)
        long marketStart = System.currentTimeMillis();
        dailyBatchOrchestrator.runDailyMarketSync();
        long marketDuration = System.currentTimeMillis() - marketStart;

        // 2. 주식 데이터 동기화 (마스터, 수급, 시세) - 섹터 분석의 선행 작업
        long stockStart = System.currentTimeMillis();
        dailyBatchOrchestrator.runDailyStockSync();
        long stockDuration = System.currentTimeMillis() - stockStart;

        // 3. 섹터 인사이트 동기화 (주식 시세를 기반으로 계산)
        long sectorStart = System.currentTimeMillis();
        dailyBatchOrchestrator.runDailySectorInsightSync();
        long sectorDuration = System.currentTimeMillis() - sectorStart;

        long totalDuration = System.currentTimeMillis() - totalStartTime;

        // 밀리초(ms)를 초(s) 단위로 가독성 있게 변환하여 응답
        Map<String, String> timings = Map.of(
                "marketSync", (marketDuration / 1000.0) + "s",
                "stockSync", (stockDuration / 1000.0) + "s",
                "sectorSync", (sectorDuration / 1000.0) + "s",
                "totalTime", (totalDuration / 1000.0) + "s"
        );

        return ApiResponse.success(timings);
    }

    /**
     * 섹터 인사이트 배치 실행
     */
    @PostMapping("/sync-sector")
    public ApiResponse<BatchExecutionResponse> runSectorSync(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate
    ) {
        return ApiResponse.success(toExecutionResponse(batchControlUseCase.launchAsync(
                new BatchControlUseCase.BatchLaunchCommand(
                        BatchControlUseCase.BatchJobType.SECTOR_EOD_SYNC,
                        null,
                        startDate,
                        endDate,
                        null,
                        false
                )
        )));
    }

    /**
     * 시세 수집 배치 실행 (전체 종목)
     */
    @PostMapping("/fetch-prices")
    public ApiResponse<BatchExecutionResponse> runPriceFetch(
            @RequestBody(required = false) StockPriceSyncRequest request,
            @RequestParam(defaultValue = "false") boolean publishEvent
    ) {
        return ApiResponse.success(toExecutionResponse(batchControlUseCase.launchAsync(
                new BatchControlUseCase.BatchLaunchCommand(
                        BatchControlUseCase.BatchJobType.STOCK_PRICE_SYNC,
                        null,
                        request != null ? request.startDate() : null,
                        request != null ? request.endDate() : null,
                        request != null ? request.endDate() : null,
                        publishEvent
                )
        )));
    }

    /**
     * 시세 수집 배치 실행 (단건 종목)
     */
    @PostMapping("/fetch-prices/single")
    public ApiResponse<BatchExecutionResponse> runSinglePriceFetch(
            @RequestBody StockPriceSyncRequest request,
            @RequestParam(defaultValue = "false") boolean publishEvent
    ) {
        if (request == null || !StringUtils.hasText(request.targetTicker())) {
            throw new BatchException(ErrorCode.INVALID_INPUT_VALUE);
        }
        return ApiResponse.success(toExecutionResponse(batchControlUseCase.launchAsync(
                new BatchControlUseCase.BatchLaunchCommand(
                        BatchControlUseCase.BatchJobType.STOCK_PRICE_SYNC,
                        request.targetTicker(),
                        request.startDate(),
                        request.endDate(),
                        request.endDate(),
                        publishEvent
                )
        )));
    }

    /**
     * 전일 종가 데이터 소급 보정 실행
     */
    @PostMapping("/sync-prev-close")
    public ApiResponse<BatchExecutionResponse> runPrevCloseSync(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate
    ) {
        return ApiResponse.success(toExecutionResponse(batchControlUseCase.launchAsync(
                new BatchControlUseCase.BatchLaunchCommand(
                        BatchControlUseCase.BatchJobType.STOCK_PRICE_PREV_CLOSE_SYNC,
                        null,
                        startDate,
                        endDate,
                        endDate,
                        false
                )
        )));
    }

    /**
     * 투자주체 순매수 금액 보정 배치 실행 (랭킹 보정용)
     */
    @PostMapping("/sync-investor-trade-detail")
    public ApiResponse<BatchExecutionResponse> runInvestorTradeDetailSync(
            @RequestParam(required = false) String targetDate
    ) {
        return ApiResponse.success(toExecutionResponse(batchControlUseCase.launchAsync(
                new BatchControlUseCase.BatchLaunchCommand(
                        BatchControlUseCase.BatchJobType.STOCK_FOREIGN_INSTITUTION,
                        null,
                        null,
                        null,
                        targetDate,
                        false
                )
        )));
    }

    /**
     * 특정 종목 단건 즉시 동기화 (보정 배치 활용)
     */
    @PostMapping("/sync-stock/{ticker}")
    public ApiResponse<BatchExecutionResponse> syncSingleStock(
            @PathVariable String ticker,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate
    ) {
        return ApiResponse.success(toExecutionResponse(batchControlUseCase.launchAsync(
                new BatchControlUseCase.BatchLaunchCommand(
                        BatchControlUseCase.BatchJobType.STOCK_PRICE_PREV_CLOSE_SYNC,
                        ticker,
                        startDate,
                        endDate,
                        endDate,
                        false
                )
        )));
    }

    /**
     * 포트폴리오 통계 지표(MDD, Sharpe, Beta) 갱신 배치 실행
     */
    @PostMapping("/sync-portfolio-stats")
    public ApiResponse<BatchExecutionResponse> runPortfolioStatsSync() {
        return ApiResponse.success(toExecutionResponse(batchControlUseCase.launchAsync(
                new BatchControlUseCase.BatchLaunchCommand(
                        BatchControlUseCase.BatchJobType.PORTFOLIO_STATS_SYNC,
                        null,
                        null,
                        null,
                        null,
                        false
                )
        )));
    }

    /**
     * 실행 중인 배치 중단
     */
    @PostMapping("/stop/{executionId}")
    public ApiResponse<String> stopJob(@PathVariable Long executionId) {
        return ApiResponse.success(batchControlUseCase.stop(new BatchControlUseCase.BatchStopCommand(executionId)));
    }

    /**
     * 멈춰있는 배치 강제 종료 (Status를 FAILED로 변경)
     */
    @PostMapping("/abandon/{executionId}")
    public ApiResponse<String> abandonJob(@PathVariable Long executionId) {
        return ApiResponse.success(batchControlUseCase.abandon(executionId));
    }

    private BatchExecutionResponse toExecutionResponse(BatchControlUseCase.BatchExecutionResult result) {
        return new BatchExecutionResponse(result.executionId(), result.jobName(), result.statusUrl(), result.message());
    }
}

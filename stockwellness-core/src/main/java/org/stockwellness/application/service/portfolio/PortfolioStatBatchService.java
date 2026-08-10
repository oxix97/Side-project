package org.stockwellness.application.service.portfolio;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.stockwellness.adapter.out.persistence.portfolio.PortfolioStatsRepository;
import org.stockwellness.application.port.in.stock.result.StockPriceResult;
import org.stockwellness.application.port.out.outbox.OutboxPort;
import org.stockwellness.application.port.out.portfolio.PortfolioPort;
import org.stockwellness.application.service.portfolio.internal.*;
import org.stockwellness.config.KafkaTopicConfig;
import org.stockwellness.domain.outbox.OutboxEvent;
import org.stockwellness.domain.portfolio.AssetType;
import org.stockwellness.domain.portfolio.Portfolio;
import org.stockwellness.domain.portfolio.PortfolioItem;
import org.stockwellness.domain.portfolio.PortfolioStats;
import org.stockwellness.domain.portfolio.RebalancingPeriod;
import org.stockwellness.domain.portfolio.event.PortfolioAnalysisCompletedEvent;
import org.stockwellness.domain.portfolio.indicator.BenchmarkCode;
import org.stockwellness.domain.stock.BenchmarkType;
import org.stockwellness.global.util.JsonUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PortfolioStatBatchService {

    private final PortfolioStatsRepository portfolioStatsRepository;
    private final SimulationDataProvider simulationDataProvider;
    private final BacktestEngine backtestEngine;
    private final OutboxPort outboxPort;
    private final JsonUtil jsonUtil;
    private final PortfolioAnalysisService portfolioAnalysisService; // 벤치마크 계산 로직 공유

    private final PortfolioPort portfolioPort;

    @Autowired
    @Lazy
    private PortfolioStatBatchService self;

    private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private static final int MAX_SYMBOLS_PER_LOAD = 50; // 메모리 보호를 위한 임계치

    @PreDestroy
    public void shutdown() {
        log.info("[배치] PortfolioStatBatchService Executor 종료 중...");
        virtualThreadExecutor.shutdown();
    }

    public void updatePortfolioStatsBatch(List<Long> portfolioIds) {
        if (portfolioIds.isEmpty()) return;

        List<Portfolio> portfolios = portfolioPort.loadAllWithItems(portfolioIds);
        if (portfolios.isEmpty()) return;

        long startTime = System.currentTimeMillis();
        int totalCount = portfolios.size();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        Set<String> allSymbols = portfolios.stream()
                .flatMap(p -> p.getItems().stream())
                .filter(this::isPositiveStock)
                .map(PortfolioItem::getSymbol)
                .collect(Collectors.toSet());

        if (allSymbols.isEmpty()) return;

        LocalDate end = LocalDate.now();
        LocalDate start = end.minusYears(2);
        SimulationData chunkSharedData = loadPartitionedData(allSymbols, start, end);

        // Virtual Thread 기반 병렬 처리
        List<CompletableFuture<Void>> futures = portfolios.stream()
                .map(portfolio -> CompletableFuture.runAsync(() -> {
                    try {
                        self.updateIndividualPortfolioStats(portfolio, chunkSharedData, end);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        log.error("[배치] 포트폴리오 {} 통계 업데이트 실패: {}", portfolio.getId(), e.getMessage());
                        failureCount.incrementAndGet();
                    }
                }, virtualThreadExecutor))
                .toList();

        // 모든 병렬 작업 완료 대기
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        long duration = System.currentTimeMillis() - startTime;
        log.info("[배치 성능 모니터링] PortfolioStatsBatchJob Chunk 완료. 소요시간: {}ms, 성공: {}, 실패: {}, 총계: {}, 가상스레드사용: {}",
                duration, successCount.get(), failureCount.get(), totalCount, true);
    }

    public void updateIndividualPortfolioStats(Portfolio portfolio, SimulationData sharedData, LocalDate baseDate) {
        Map<String, BigDecimal> weights = buildBacktestWeights(portfolio);

        if (weights.isEmpty()) return;

        SimulationData filteredData = filterDataForPortfolio(sharedData, weights.keySet());

        // 1. 무거운 연산 (백테스트 실행) - 트랜잭션 외부에서 수행
        BacktestResult result = backtestEngine.runLumpSum(
                filteredData,
                weights,
                BigDecimal.valueOf(1000000),
                RebalancingPeriod.NONE,
                BenchmarkType.KOSPI.getTicker(),
                BigDecimal.valueOf(3.0),
                true
        );

        // 2. DB 업데이트 및 이벤트 저장을 위한 트랜잭션 메서드 호출
        self.saveStatsAndEvent(portfolio, result, filteredData, baseDate);
    }

    private SimulationData loadPartitionedData(Set<String> allSymbols, LocalDate start, LocalDate end) {
        List<String> symbolList = new ArrayList<>(allSymbols);
        Map<String, List<StockPriceResult>> allStockPrices = new HashMap<>();
        Map<String, List<StockPriceResult>> benchmarkPrices = null;

        for (int i = 0; i < symbolList.size(); i += MAX_SYMBOLS_PER_LOAD) {
            List<String> partition = symbolList.subList(i, Math.min(i + MAX_SYMBOLS_PER_LOAD, symbolList.size()));
            // 배치 통계도 필수 primary 벤치마크를 명시적으로 로드한다.
            SimulationData partData = simulationDataProvider.loadData(
                    partition,
                    List.of(BenchmarkCode.KOSPI.getTicker()),
                    start,
                    end
            );
            allStockPrices.putAll(partData.stockPrices());
            if (benchmarkPrices == null) {
                benchmarkPrices = partData.benchmarkPrices();
            }
        }

        return new SimulationData(allStockPrices, benchmarkPrices != null ? benchmarkPrices : Map.of());
    }

    @Transactional
    public void saveStatsAndEvent(Portfolio portfolio, BacktestResult result, SimulationData filteredData, LocalDate baseDate) {
        BigDecimal mdd = result.mdd();
        BigDecimal sharpe = result.sharpeRatio();
        BigDecimal beta = result.beta();

        // 리팩토링된 엔티티 메서드 활용을 위한 시세 맵 구성
        Map<String, BigDecimal> currentPrices = portfolio.getItems().stream()
                .collect(Collectors.toMap(PortfolioItem::getSymbol, i -> {
                    List<StockPriceResult> prices = filteredData.stockPrices().get(i.getSymbol());
                    return (prices != null && !prices.isEmpty()) ? prices.get(prices.size() - 1).closePrice() : i.getPurchasePrice();
                }));

        BigDecimal inceptionReturn = portfolio.calculateTotalReturnRate(currentPrices);
        BigDecimal benchmarkReturn = portfolioAnalysisService.calculateBenchmarkReturn(BenchmarkType.KOSPI.getTicker(), baseDate.minusYears(2), baseDate);

        portfolioStatsRepository.findByPortfolioId(portfolio.getId())
                .ifPresentOrElse(
                        stats -> stats.update(baseDate, mdd, sharpe, beta, inceptionReturn, benchmarkReturn),
                        () -> portfolioStatsRepository.save(PortfolioStats.create(portfolio, baseDate, mdd, sharpe, beta, inceptionReturn, benchmarkReturn))
                );

        String payload = jsonUtil.toJson(new PortfolioAnalysisCompletedEvent(
                portfolio.getId(), baseDate, mdd, sharpe, beta));
        outboxPort.save(OutboxEvent.create(
                KafkaTopicConfig.PORTFOLIO_ANALYSIS_COMPLETED_TOPIC, payload));
    }

    private SimulationData filterDataForPortfolio(SimulationData sharedData, Set<String> symbols) {
        Map<String, List<StockPriceResult>> filteredStockPrices = new HashMap<>();
        symbols.forEach(s -> {
            List<StockPriceResult> prices = sharedData.stockPrices().get(s);
            if (prices != null) filteredStockPrices.put(s, prices);
        });
        return new SimulationData(filteredStockPrices, sharedData.benchmarkPrices());
    }

    /**
     * 백테스트가 지원하는 주식 자산만 추출하고, 현금·0% 항목으로 인해
     * 투자 비중 합계가 100% 미만이 되는 포트폴리오는 주식 비중을 다시
     * 100%로 정규화한다. 배치와 사용자 요청의 기본 비중 정책을 동일하게
     * 유지하기 위한 경계 변환이다.
     */
    private Map<String, BigDecimal> buildBacktestWeights(Portfolio portfolio) {
        Map<String, BigDecimal> stockWeights = portfolio.getItems().stream()
                .filter(this::isPositiveStock)
                .collect(Collectors.toMap(
                        PortfolioItem::getSymbol,
                        PortfolioItem::getTargetWeight,
                        BigDecimal::add,
                        LinkedHashMap::new
                ));

        BigDecimal total = stockWeights.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            return stockWeights;
        }
        if (total.compareTo(BigDecimal.valueOf(100)) == 0) {
            return stockWeights;
        }

        Map<String, BigDecimal> normalized = new LinkedHashMap<>();
        stockWeights.forEach((symbol, weight) -> normalized.put(
                symbol,
                weight.multiply(BigDecimal.valueOf(100))
                        .divide(total, 8, RoundingMode.HALF_UP)
        ));
        return normalized;
    }

    private boolean isPositiveStock(PortfolioItem item) {
        return item != null
                && item.getAssetType() == AssetType.STOCK
                && item.getSymbol() != null
                && !item.getSymbol().isBlank()
                && item.getTargetWeight() != null
                && item.getTargetWeight().compareTo(BigDecimal.ZERO) > 0;
    }

}

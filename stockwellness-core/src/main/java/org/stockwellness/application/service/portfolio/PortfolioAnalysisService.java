package org.stockwellness.application.service.portfolio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.stockwellness.application.port.in.portfolio.AiAdvisorUseCase;
import org.stockwellness.application.port.in.portfolio.PortfolioAnalysisUseCase;
import org.stockwellness.application.port.in.portfolio.command.BacktestPortfolioCommand;
import org.stockwellness.application.port.in.portfolio.result.*;
import org.stockwellness.application.port.in.stock.result.StockPriceResult;
import org.stockwellness.application.port.out.portfolio.PortfolioPort;
import org.stockwellness.application.port.out.stock.BenchmarkPricePort;
import org.stockwellness.application.port.out.stock.LoadBenchmarkPort;
import org.stockwellness.application.port.out.stock.StockPort;
import org.stockwellness.application.port.out.stock.StockPricePort;
import org.stockwellness.application.service.portfolio.internal.*;
import org.stockwellness.domain.portfolio.*;
import org.stockwellness.domain.portfolio.exception.PortfolioAccessDeniedException;
import org.stockwellness.domain.portfolio.exception.PortfolioNotFoundException;
import org.stockwellness.domain.stock.BenchmarkType;
import org.stockwellness.domain.stock.Country;
import org.stockwellness.domain.stock.Stock;
import org.stockwellness.domain.stock.price.ChartPeriod;
import org.stockwellness.domain.stock.price.BenchmarkPrice;
import org.stockwellness.domain.stock.price.StockPrice;
import org.stockwellness.global.error.ErrorCode;
import org.stockwellness.global.error.exception.GlobalException;
import org.stockwellness.global.util.FinanceCalculationUtil;
import org.stockwellness.global.util.PortfolioMapperUtil;

/**
 * 포트폴리오 분석 서비스
 * 실시간 가치 평가, 자산 분산도 분석, 목표 비중 기반 리밸런싱 가이드, 
 * 과거 수익률 백테스팅 및 종목 간 상관관계 분석 로직을 통합 관리합니다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PortfolioAnalysisService implements PortfolioAnalysisUseCase {

    private final PortfolioAnalysisDataLoader dataLoader;
    private final SimulationDataProvider simulationDataProvider;
    private final PortfolioPort portfolioPort;
    private final StockPort stockPort;
    private final StockPricePort stockPricePort;
    private final BenchmarkPricePort benchmarkPricePort;
    private final LoadBenchmarkPort loadBenchmarkPort;
    private final BacktestEngine backtestEngine;
    private final PortfolioCorrelationCalculator correlationCalculator;
    private final AiAdvisorUseCase aiAdvisorUseCase;

    /**
     * 포트폴리오의 실시간 평가 가치 및 수익률 정보를 조회합니다.
     * 현재가 기준으로 총 매수 금액 대비 평가 손익과 전일 대비 수익률을 계산합니다.
     */
    @Override
    public PortfolioValuationResult getValuation(Long memberId, Long portfolioId) {
        return calculateValuation(dataLoader.loadContext(portfolioId, memberId), null);
    }

    /**
     * 포트폴리오의 자산군(주식/현금), 업종, 국가별 분산 비중을 조회합니다.
     */
    @Override
    public PortfolioDiversificationResult getDiversification(Long memberId, Long portfolioId) {
        return calculateDiversification(dataLoader.loadContext(portfolioId, memberId));
    }

    /**
     * 설정된 목표 비중과 실시간 현재 비중을 비교하여 리밸런싱을 위해 필요한 매매 수량 가이드를 제공합니다.
     */
    @Override
    public PortfolioRebalancingResult getRebalancingGuide(Long memberId, Long portfolioId) {
        return calculateRebalancing(dataLoader.loadContext(portfolioId, memberId));
    }

    /**
     * 포트폴리오 분석의 핵심 지표(가치, 분산, 리밸런싱)를 통합 요약하여 조회합니다.
     * 최근 1년 성과 지표(CAGR, 변동성 등)와 종목별 수익 기여도를 함께 산출합니다.
     */
    @Override
    public PortfolioAnalysisSummaryResult getAnalysisSummary(Long memberId, Long portfolioId, LocalDate startDate, LocalDate endDate) {
        AnalysisContext context = dataLoader.loadContext(portfolioId, memberId);
        BacktestResult performanceResult = calculateSummaryPerformance(context, startDate, endDate);

        return new PortfolioAnalysisSummaryResult(
                calculateValuation(context, performanceResult),
                calculateDiversification(context),
                calculateRebalancing(context),
                calculateItemContributions(context)
        );
    }

    /**
     * 개별 종목이 전체 포트폴리오 성과(수익률)에 얼마나 기여했는지 계산합니다.
     */
    private Map<String, BigDecimal> calculateItemContributions(AnalysisContext context) {
        BigDecimal totalInvestment = context.portfolio().calculateTotalPurchaseAmount();
        if (totalInvestment.compareTo(BigDecimal.ZERO) == 0) return Map.of();

        Map<String, BigDecimal> contributions = new HashMap<>();
        for (PortfolioItem item : context.portfolio().getItems()) {
            BigDecimal currentPrice = getCurrentPrice(item, context.priceMap());
            // 기여도 = (개별 종목 손익 / 전체 포트폴리오 총 투자액) * 100
            contributions.put(item.getSymbol(), item.calculateContribution(currentPrice, totalInvestment));
        }
        return contributions;
    }

    /**
     * 과거 시세 데이터를 기반으로 선택한 전략(거치식/적립식)에 따른 투자 성과를 시뮬레이션합니다.
     * 시뮬레이션 결과에 대해 AI 어드바이저의 분석 리포트도 함께 생성합니다.
     */
    @Override
    public BacktestResult runBacktest(BacktestPortfolioCommand command) {
        validateBacktestCommand(command);
        AnalysisContext context = dataLoader.loadContext(command.portfolioId(), command.memberId());

        // 종목별 목표 비중 추출 (사용자 입력 가중치가 있으면 우선 사용, 없으면 포트폴리오 기본 비중 사용)
        boolean usePortfolioWeights = command.weights() == null || command.weights().isEmpty();
        Map<String, BigDecimal> weights = !usePortfolioWeights ?
                new LinkedHashMap<>(command.weights()) :
                context.portfolio().getItems().stream()
                // 백테스트 시세가 없는 CASH와 0% 보유 자산은 가격 입력에서 제외합니다.
                .filter(item -> item.getAssetType() == AssetType.STOCK
                        && item.getTargetWeight() != null
                        && item.getTargetWeight().compareTo(BigDecimal.ZERO) > 0)
                .collect(Collectors.toMap(
                        PortfolioItem::getSymbol,
                        PortfolioItem::getTargetWeight,
                        (first, ignored) -> first,
                        LinkedHashMap::new
                ));
        if (usePortfolioWeights) {
            weights = normalizeStockWeights(weights);
        }
        validateBacktestWeights(weights);

        List<String> symbols = new ArrayList<>(weights.keySet());

        // 선택된 기간에 따른 시세 데이터 로딩 및 시뮬레이션 실행
        LocalDate end = LocalDate.now();
        ChartPeriod period = command.period() == null ? ChartPeriod.ONE_YEAR : command.period();
        LocalDate start = period.calculateStartDate(end);
        List<String> benchmarkTickers = command.benchmarkTickers();
        if (benchmarkTickers == null || benchmarkTickers.isEmpty()) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE);
        }
        String primaryTicker = benchmarkTickers.getFirst();
        SimulationData data = simulationDataProvider.loadData(symbols, benchmarkTickers, start, end);

        BacktestStrategy strategy = parseBacktestStrategy(command.strategy());
        RebalancingPeriod rebalancingPeriod = command.rebalancingPeriod() == null
                ? RebalancingPeriod.NONE
                : command.rebalancingPeriod();
        boolean dividendReinvested = command.dividendReinvested() == null || command.dividendReinvested();

        // 투자 방식에 따른 시뮬레이션 엔진 실행 (LumpSum: 거치식, DCA: 적립식)
        BacktestResult result = (strategy == BacktestStrategy.DCA) ?
                backtestEngine.runDCA(data, weights, command.amount(), rebalancingPeriod, primaryTicker, BigDecimal.valueOf(3.0), dividendReinvested) :
                backtestEngine.runLumpSum(data, weights, command.amount(), rebalancingPeriod, primaryTicker, BigDecimal.valueOf(3.0), dividendReinvested);
        // AI 어드바이저가 백테스트 결과를 분석하여 조언 생성
        String aiComment = aiAdvisorUseCase.generateBacktestAdvice(result, command.strategy(), primaryTicker);
        
        return new BacktestResult(
                result.dailyResults(), result.cagr(), result.mdd(), result.relativeMdd(), result.sharpeRatio(),
                result.totalReturnRate(), result.volatility(), result.alpha(), result.beta(),
                result.bestYearRate(), result.worstYearRate(), result.itemReturns(), result.comparisons(), aiComment,
                result.xirr(), result.timeWeightedReturnRate(), result.calculationMethod(), result.sortinoRatio(), result.recoveryPeriod()
        );
    }

    private void validateBacktestCommand(BacktestPortfolioCommand command) {
        if (command == null || command.amount() == null || command.amount().compareTo(BigDecimal.ZERO) <= 0
                || command.portfolioId() == null || command.memberId() == null) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE);
        }
        parseBacktestStrategy(command.strategy());
        if (command.benchmarkTickers() == null || command.benchmarkTickers().isEmpty()
                || command.benchmarkTickers().stream().anyMatch(ticker -> ticker == null || ticker.isBlank())) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }

    private Map<String, BigDecimal> normalizeStockWeights(Map<String, BigDecimal> weights) {
        BigDecimal total = weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            return weights;
        }
        Map<String, BigDecimal> normalized = new LinkedHashMap<>();
        weights.forEach((symbol, weight) -> normalized.put(
                symbol,
                weight.multiply(BigDecimal.valueOf(100)).divide(total, 8, RoundingMode.HALF_UP)
        ));
        return normalized;
    }

    private BacktestStrategy parseBacktestStrategy(String strategy) {
        if (strategy == null || strategy.isBlank()) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE);
        }
        try {
            return BacktestStrategy.valueOf(strategy.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }

    private void validateBacktestWeights(Map<String, BigDecimal> weights) {
        if (weights == null || weights.isEmpty()
                || weights.entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getKey().isBlank()
                || entry.getValue() == null || entry.getValue().compareTo(BigDecimal.ZERO) <= 0)) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE);
        }
        BigDecimal total = weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.subtract(BigDecimal.valueOf(100)).abs().compareTo(new BigDecimal("0.000001")) > 0) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }

    /**
     * 포트폴리오 구성 종목 간의 가격 변동 상관관계 행렬을 산출합니다.
     * 최근 2년간의 일별 수익률 데이터를 기반으로 계산합니다.
     */
    @Override
    public PortfolioInceptionPerformanceResult getPerformanceSinceInception(Long memberId, Long portfolioId) {
        Portfolio portfolio = portfolioPort.findById(portfolioId).orElseThrow(PortfolioNotFoundException::new);
        if (!portfolio.getMemberId().equals(memberId)) throw new PortfolioAccessDeniedException();

        List<PortfolioItem> items = portfolio.getItems();
        if (items.isEmpty()) return new PortfolioInceptionPerformanceResult(BigDecimal.ZERO, BigDecimal.ZERO, List.of());

        List<String> symbols = items.stream().filter(i -> i.getAssetType() == AssetType.STOCK).map(PortfolioItem::getSymbol).toList();
        Map<String, BigDecimal> latestPriceMap = stockPricePort.findAllLatestByTickers(symbols);
        Map<String, Stock> stockMap = stockPort.loadStocksByTickers(symbols).stream()
                .collect(Collectors.toMap(Stock::getTicker, s -> s));

        BigDecimal portfolioTotalReturn = portfolio.calculateTotalReturnRate(latestPriceMap);
        BigDecimal totalInvestment = portfolio.calculateTotalPurchaseAmount();

        List<PortfolioInceptionPerformanceResult.StockInceptionPerformance> stockPerformances = items.stream()
                .map(item -> {
                    BigDecimal currentPrice = (item.getAssetType() == AssetType.CASH) ? BigDecimal.ONE : latestPriceMap.get(item.getSymbol());
                    if (currentPrice == null) currentPrice = item.getPurchasePrice();

                    String name = (item.getAssetType() == AssetType.CASH) ? "현금" : 
                                 (stockMap.containsKey(item.getSymbol()) ? stockMap.get(item.getSymbol()).getName() : item.getSymbol());
                    
                    BigDecimal individualReturn = item.calculateReturnRate(currentPrice);
                    return new PortfolioInceptionPerformanceResult.StockInceptionPerformance(
                            item.getSymbol(), name, individualReturn,
                            item.calculateContribution(currentPrice, totalInvestment),
                            individualReturn.subtract(portfolioTotalReturn)
                    );
                }).toList();

        BigDecimal benchmarkReturn = calculateBenchmarkReturn(BenchmarkType.KOSPI.getTicker(), portfolio.getInceptionDate(), LocalDate.now());
        return new PortfolioInceptionPerformanceResult(portfolioTotalReturn, benchmarkReturn, stockPerformances);
    }

    @Override
    public PortfolioInceptionChartResult getInceptionChart(Long memberId, Long portfolioId) {
        Portfolio portfolio = portfolioPort.loadPortfolio(portfolioId, memberId)
                .orElseThrow(() -> {
                    if (portfolioPort.findById(portfolioId).isPresent()) {
                        throw new PortfolioAccessDeniedException();
                    }
                    return new PortfolioNotFoundException();
                });

        List<PortfolioItem> items = portfolio.getItems();
        List<PortfolioItem> stockItems = items.stream()
                .filter(item -> item.getAssetType() == AssetType.STOCK)
                .toList();

        if (stockItems.isEmpty()) {
            return new PortfolioInceptionChartResult(portfolio.getInceptionDate(), 0, List.of(), List.of());
        }

        LocalDate inceptionDate = portfolio.getInceptionDate();
        LocalDate endDate = LocalDate.now();
        List<String> symbols = stockItems.stream().map(PortfolioItem::getSymbol).distinct().toList();

        Map<String, List<StockPriceResult>> stockPriceMap = stockPricePort.loadPricesByTickers(symbols, inceptionDate, endDate);
        List<LocalDate> dates = stockPriceMap.values().stream()
                .flatMap(List::stream)
                .map(StockPriceResult::baseDate)
                .distinct()
                .sorted()
                .toList();

        if (dates.isEmpty()) {
            return new PortfolioInceptionChartResult(inceptionDate, 0, List.of(), List.of());
        }

        Map<String, Map<LocalDate, BigDecimal>> stockCloseMap = stockPriceMap.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().stream()
                                .collect(Collectors.toMap(StockPriceResult::baseDate, StockPriceResult::closePrice))
                ));

        List<BenchmarkType> benchmarks = BenchmarkType.defaultSimulationBenchmarks();
        Map<String, List<StockPriceResult>> benchmarkSeries = benchmarks.stream()
                .collect(Collectors.toMap(
                        BenchmarkType::getTicker,
                        benchmark -> loadBenchmarkPort.loadBenchmarkPrices(benchmark.getTicker(), inceptionDate, endDate)
                ));
        Map<String, Map<LocalDate, BigDecimal>> benchmarkCloseMap = benchmarkSeries.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().stream()
                                .collect(Collectors.toMap(StockPriceResult::baseDate, StockPriceResult::closePrice))
                ));

        Map<String, BigDecimal> latestStockPrices = new HashMap<>();
        Map<String, BigDecimal> firstBenchmarkPrices = new HashMap<>();
        Map<String, BigDecimal> latestBenchmarkPrices = new HashMap<>();
        List<PortfolioInceptionChartResult.DailyResult> dailyResults = new ArrayList<>();

        for (LocalDate date : dates) {
            BigDecimal investedAmount = BigDecimal.ZERO;
            BigDecimal currentValue = BigDecimal.ZERO;

            for (PortfolioItem item : items) {
                if (item.getPurchaseDate().isAfter(date)) {
                    continue;
                }

                investedAmount = investedAmount.add(item.calculatePurchaseAmount());
                if (item.getAssetType() == AssetType.CASH) {
                    currentValue = currentValue.add(item.getQuantity());
                    continue;
                }

                BigDecimal closePrice = stockCloseMap.getOrDefault(item.getSymbol(), Map.of()).get(date);
                if (closePrice != null) {
                    latestStockPrices.put(item.getSymbol(), closePrice);
                }

                BigDecimal effectivePrice = latestStockPrices.getOrDefault(item.getSymbol(), item.getPurchasePrice());
                currentValue = currentValue.add(item.getQuantity().multiply(effectivePrice));
            }

            if (investedAmount.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            BigDecimal portfolioReturnRate = FinanceCalculationUtil.calculateRate(
                    currentValue.subtract(investedAmount),
                    investedAmount
            );

            Map<String, BigDecimal> benchmarkReturnRates = new HashMap<>();
            for (BenchmarkType benchmark : benchmarks) {
                BigDecimal closePrice = benchmarkCloseMap.getOrDefault(benchmark.getTicker(), Map.of()).get(date);
                if (closePrice != null) {
                    latestBenchmarkPrices.put(benchmark.getTicker(), closePrice);
                    firstBenchmarkPrices.putIfAbsent(benchmark.getTicker(), closePrice);
                }

                BigDecimal startPrice = firstBenchmarkPrices.get(benchmark.getTicker());
                BigDecimal currentBenchmarkPrice = latestBenchmarkPrices.get(benchmark.getTicker());
                if (startPrice == null || currentBenchmarkPrice == null || startPrice.compareTo(BigDecimal.ZERO) <= 0) {
                    benchmarkReturnRates.put(benchmark.getTicker(), BigDecimal.ZERO);
                    continue;
                }

                benchmarkReturnRates.put(
                        benchmark.getTicker(),
                        FinanceCalculationUtil.calculateRate(currentBenchmarkPrice.subtract(startPrice), startPrice)
                );
            }

            dailyResults.add(new PortfolioInceptionChartResult.DailyResult(date, portfolioReturnRate, Map.copyOf(benchmarkReturnRates)));
        }

        if (dailyResults.isEmpty()) {
            return new PortfolioInceptionChartResult(inceptionDate, 0, List.of(), List.of());
        }

        long daysElapsed = ChronoUnit.DAYS.between(inceptionDate, LocalDate.now());

        Map<String, BigDecimal> finalBenchmarkReturns = dailyResults.get(dailyResults.size() - 1).benchmarkReturnRates();
        List<PortfolioInceptionChartResult.IndexComparison> comparisons = benchmarks.stream()
                .map(benchmark -> new PortfolioInceptionChartResult.IndexComparison(
                        benchmark.getName(),
                        benchmark.getTicker(),
                        finalBenchmarkReturns.getOrDefault(benchmark.getTicker(), BigDecimal.ZERO)
                ))
                .toList();

        return new PortfolioInceptionChartResult(inceptionDate, daysElapsed, dailyResults, comparisons);
    }

    public BigDecimal calculateBenchmarkReturn(String ticker, LocalDate startDate, LocalDate endDate) {
        Optional<BenchmarkPrice> startPrice = benchmarkPricePort.findLatestBefore(ticker, startDate.plusDays(1));
        Optional<BenchmarkPrice> endPrice = benchmarkPricePort.findLatestBefore(ticker, endDate.plusDays(1));

        if (startPrice.isPresent() && endPrice.isPresent()) {
            BigDecimal startVal = startPrice.get().getClosePrice();
            BigDecimal endVal = endPrice.get().getClosePrice();
            if (startVal.compareTo(BigDecimal.ZERO) > 0) {
                return FinanceCalculationUtil.calculateRate(endVal.subtract(startVal), startVal);
            }
        }
        return BigDecimal.ZERO;
    }

    @Override
    public Map<String, Map<String, BigDecimal>> getCorrelationMatrix(Long memberId, Long portfolioId) {
        AnalysisContext context = dataLoader.loadContext(portfolioId, memberId);
        List<String> symbols = context.getStockSymbols();
        if (symbols.size() < 2) return Map.of();

        // 최근 2년치 가격 정보 로드
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusYears(2);
        SimulationData data = simulationDataProvider.loadData(symbols, null, start, end);

        Map<String, List<BigDecimal>> returnsMap = new HashMap<>();
        for (String symbol : symbols) {
            // 가격 데이터를 일별 수익률 리스트로 변환
            List<BigDecimal> returns = FinanceCalculationUtil.calculateDailyReturns(data.stockPrices().get(symbol));
            if (!returns.isEmpty()) returnsMap.put(symbol, returns);
        }
        // 상관관계 계산 엔진 호출
        return correlationCalculator.calculateMatrix(returnsMap);
    }

    // ── 내부 계산 로직 (Private Helpers) ──────────────────────────────────────────

    private BacktestResult calculateSummaryPerformance(AnalysisContext context, LocalDate startDate, LocalDate endDate) {
        List<PortfolioItem> stockItems = context.portfolio().getItems().stream()
                .filter(item -> item.getAssetType() == AssetType.STOCK)
                .toList();

        if (stockItems.isEmpty()) {
            return null;
        }

        Map<String, BigDecimal> weights = normalizeWeights(stockItems.stream()
                .collect(Collectors.toMap(PortfolioItem::getSymbol, PortfolioItem::getTargetWeight)));
        List<String> symbols = new ArrayList<>(weights.keySet());
        List<String> benchmarkTickers = BenchmarkType.defaultSimulationBenchmarkTickers();
        SimulationData data = simulationDataProvider.loadData(symbols, benchmarkTickers, startDate, endDate);

        return backtestEngine.runLumpSum(
                data,
                weights,
                context.portfolio().calculateTotalPurchaseAmount(),
                RebalancingPeriod.NONE,
                benchmarkTickers.getFirst(),
                BigDecimal.valueOf(3.0),
                false
        );
    }

    /**
     * 마지막 완료 EOD 종가를 반영한 총 평가 금액 및 손익 지표(누적/일별) 계산 로직.
     * 가격이 누락된 경우 매수가나 0을 합성하지 않고 상태와 nullable 합계를 반환한다.
     */
    private PortfolioValuationResult calculateValuation(AnalysisContext context, BacktestResult performanceResult) {
        Portfolio portfolio = context.portfolio();
        BigDecimal totalPurchaseAmount = portfolio.calculateTotalPurchaseAmount();
        LocalDate asOfDate = latestCompletedEodDate(context.priceMap());
        List<String> missingSymbols = portfolio.getItems().stream()
                .filter(item -> item.getAssetType() == AssetType.STOCK)
                .filter(item -> latestAvailablePrice(item.getSymbol(), context.priceMap()) == null)
                .map(PortfolioItem::getSymbol)
                .toList();
        long stockCount = portfolio.getItems().stream()
                .filter(item -> item.getAssetType() == AssetType.STOCK)
                .count();
        ValuationStatus valuationStatus = stockCount == 0 || missingSymbols.isEmpty()
                ? ValuationStatus.COMPLETE
                : missingSymbols.size() == stockCount ? ValuationStatus.UNAVAILABLE : ValuationStatus.PARTIAL;

        // 수급 데이터 계산 (StockPrice에서 분리됨에 따라 0으로 초기화)
        BigDecimal totalInstitutionalNetBuying = BigDecimal.ZERO;
        BigDecimal totalForeignNetBuying = BigDecimal.ZERO;
        BigDecimal totalPersonNetBuying = BigDecimal.ZERO;

        BigDecimal currentTotalValue = null;
        BigDecimal totalProfitLoss = null;
        BigDecimal totalReturnRate = null;
        BigDecimal dailyProfitLoss = null;
        BigDecimal dailyReturnRate = null;
        if (valuationStatus == ValuationStatus.COMPLETE) {
            Map<String, BigDecimal> currentPrices = portfolio.getItems().stream()
                    .collect(Collectors.toMap(PortfolioItem::getSymbol, item -> currentPriceForValuation(item, context.priceMap())));
            currentTotalValue = portfolio.calculateTotalCurrentValue(currentPrices);
            totalProfitLoss = currentTotalValue.subtract(totalPurchaseAmount);
            totalReturnRate = totalPurchaseAmount.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO
                    : FinanceCalculationUtil.calculateRate(totalProfitLoss, totalPurchaseAmount);

            Map<String, BigDecimal> previousPrices = portfolio.getItems().stream()
                    .collect(Collectors.toMap(PortfolioItem::getSymbol, item -> previousPriceForValuation(item, context.priceMap())));
            if (previousPrices.values().stream().allMatch(Objects::nonNull)) {
                BigDecimal previousTotalValue = portfolio.calculateTotalCurrentValue(previousPrices);
                dailyProfitLoss = currentTotalValue.subtract(previousTotalValue);
                dailyReturnRate = FinanceCalculationUtil.calculateRate(dailyProfitLoss, previousTotalValue);
            }
        }

        return new PortfolioValuationResult(
                totalPurchaseAmount, currentTotalValue, totalProfitLoss, totalReturnRate,
                dailyProfitLoss, dailyReturnRate,
                performanceResult != null ? performanceResult.cagr() : BigDecimal.ZERO,
                performanceResult != null ? performanceResult.volatility() : BigDecimal.ZERO,
                performanceResult != null ? performanceResult.alpha() : BigDecimal.ZERO,
                context.stats() != null ? context.stats().getMdd() : BigDecimal.ZERO,
                context.stats() != null ? context.stats().getSharpeRatio() : BigDecimal.ZERO,
                context.stats() != null ? context.stats().getBeta() : BigDecimal.ZERO,
                totalInstitutionalNetBuying, totalForeignNetBuying, totalPersonNetBuying,
                valuationStatus, asOfDate, missingSymbols
        );
    }

    /**
     * 자산군/업종/국가별 그룹핑을 통한 분산 비중 산출 로직
     */
    private PortfolioDiversificationResult calculateDiversification(AnalysisContext context) {
        BigDecimal totalValue = BigDecimal.ZERO;
        
        Map<AssetType, BigDecimal> assetValueMap = new EnumMap<>(AssetType.class);
        Map<String, BigDecimal> sectorValueMap = new HashMap<>();
        Map<Country, BigDecimal> countryValueMap = new EnumMap<>(Country.class);

        for (PortfolioItem item : context.portfolio().getItems()) {
            BigDecimal currentValue = calculateCurrentValue(item, context.priceMap());
            totalValue = totalValue.add(currentValue);

            // 1. 자산군별(주식/현금) 금액 합산
            AssetType assetType = item.getAssetType();
            assetValueMap.put(assetType, assetValueMap.getOrDefault(assetType, BigDecimal.ZERO).add(currentValue));

            if (assetType == AssetType.STOCK) {
                Stock stock = context.stockMap().get(item.getSymbol());
                if (stock != null) {
                    // 2. 주식 종목의 업종별 금액 합산
                    String sector = stock.getSector().getSectorName();
                    sectorValueMap.put(sector, sectorValueMap.getOrDefault(sector, BigDecimal.ZERO).add(currentValue));
                    // 3. 주식 종목의 상장 국가별 금액 합산
                    Country country = PortfolioMapperUtil.resolveCountry(stock.getMarketType());
                    countryValueMap.put(country, countryValueMap.getOrDefault(country, BigDecimal.ZERO).add(currentValue));
                }
            } else {
                // 현금 자산의 국가 분류 (통화 기준: KRW->KOREA, USD->USA)
                Country country = PortfolioMapperUtil.resolveCountryFromCurrency(item.getCurrency());
                countryValueMap.put(country, countryValueMap.getOrDefault(country, BigDecimal.ZERO).add(currentValue));
            }
        }

        // 금액 데이터를 백분율(%) 비중 데이터로 변환하여 반환
        return new PortfolioDiversificationResult(totalValue, 
                convertAssetRatios(assetValueMap, totalValue),
                PortfolioMapperUtil.calculateRatios(sectorValueMap, totalValue), 
                convertCountryRatios(countryValueMap, totalValue));
    }

    private Map<String, BigDecimal> convertAssetRatios(Map<AssetType, BigDecimal> assetValueMap, BigDecimal total) {
        Map<String, BigDecimal> ratios = new HashMap<>();
        assetValueMap.forEach((k, v) -> ratios.put(k.name(), FinanceCalculationUtil.calculateRate(v, total)));
        return ratios;
    }

    private Map<String, BigDecimal> convertCountryRatios(Map<Country, BigDecimal> countryValueMap, BigDecimal total) {
        Map<String, BigDecimal> ratios = new HashMap<>();
        countryValueMap.forEach((k, v) -> ratios.put(k.name(), FinanceCalculationUtil.calculateRate(v, total)));
        return ratios;
    }

    /**
     * 목표 비중과 실시간 현재 비중을 비교하여 리밸런싱을 위해 매매해야 할 수량을 산출합니다.
     */
    private PortfolioRebalancingResult calculateRebalancing(AnalysisContext context) {
        BigDecimal totalValue = context.portfolio().calculateTotalCurrentValue(
                context.portfolio().getItems().stream().collect(Collectors.toMap(PortfolioItem::getSymbol, i -> getCurrentPrice(i, context.priceMap())))
        );

        List<PortfolioRebalancingResult.RebalancingItem> items = new ArrayList<>();
        for (PortfolioItem item : context.portfolio().getItems()) {
            BigDecimal currentPrice = getCurrentPrice(item, context.priceMap());
            BigDecimal currentValue = calculateCurrentValue(item, context.priceMap());
            
            // 1. 실시간 현재 비중 (%) 계산
            BigDecimal currentWeight = totalValue.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO :
                    FinanceCalculationUtil.calculateRate(currentValue, totalValue);
            
            // 2. 설정된 목표 비중 (%)
            BigDecimal targetWeight = item.getTargetWeight();
            
            // 3. 목표 가치 도달을 위해 필요한 금액 차이 계산
            BigDecimal targetValue = totalValue.multiply(targetWeight).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
            BigDecimal diffValue = targetValue.subtract(currentValue);
            
            // 4. 차이 금액을 현재가로 나누어 매매 추천 수량(Quantity) 산출
            BigDecimal recommendedQuantity = currentPrice.compareTo(BigDecimal.ZERO) > 0 ?
                    diffValue.divide(currentPrice, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;

            items.add(new PortfolioRebalancingResult.RebalancingItem(
                    item.getSymbol(),
                    resolveDisplayName(item, context.stockMap()),
                    currentWeight,
                    targetWeight,
                    targetWeight.subtract(currentWeight),
                    item.getQuantity(),
                    recommendedQuantity,
                    currentPrice
            ));
        }
        return new PortfolioRebalancingResult(totalValue, items);
    }

    /**
     * 입력된 가중치의 합계가 100%가 아닐 경우, 각 가중치의 상대적 비율을 유지하면서 합이 100%가 되도록 정규화합니다.
     * 예: A: 10, B: 10 입력 시 -> A: 50%, B: 50%로 변환
     */
    private Map<String, BigDecimal> normalizeWeights(Map<String, BigDecimal> weights) {
        BigDecimal totalWeight = weights.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 합계가 0이거나 이미 100%인 경우 그대로 반환
        if (totalWeight.compareTo(BigDecimal.ZERO) == 0 || 
            totalWeight.setScale(2, RoundingMode.HALF_UP).compareTo(BigDecimal.valueOf(100).setScale(2, RoundingMode.HALF_UP)) == 0) {
            return weights;
        }

        Map<String, BigDecimal> normalized = new HashMap<>();
        for (Map.Entry<String, BigDecimal> entry : weights.entrySet()) {
            // 개별 비중 / 전체 합계 비율로 재계산
            BigDecimal ratio = entry.getValue().divide(totalWeight, 8, RoundingMode.HALF_UP);
            normalized.put(entry.getKey(), ratio.multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP));
        }
        return normalized;
    }

    // ── 기초 조회 유틸리티 메서드 ──

    /**
     * 특정 종목의 가장 최신 시세 정보를 조회합니다.
     */
    private StockPrice getLatestPrice(String symbol, Map<String, List<StockPrice>> priceMap) {
        return priceMap.getOrDefault(symbol, List.of()).stream().findFirst().orElse(null);
    }

    private StockPrice latestAvailablePrice(String symbol, Map<String, List<StockPrice>> priceMap) {
        return priceMap.getOrDefault(symbol, List.of()).stream()
                .filter(price -> price != null && price.getClosePrice() != null)
                .filter(price -> price.getId() == null || price.getId().getBaseDate() == null
                        || !price.getId().getBaseDate().isAfter(LocalDate.now()))
                .findFirst()
                .orElse(null);
    }

    private LocalDate latestCompletedEodDate(Map<String, List<StockPrice>> priceMap) {
        return priceMap.values().stream()
                .flatMap(List::stream)
                .filter(price -> price != null && price.getClosePrice() != null && price.getId() != null)
                .map(price -> price.getId().getBaseDate())
                .filter(Objects::nonNull)
                .filter(date -> !date.isAfter(LocalDate.now()))
                .max(LocalDate::compareTo)
                .orElse(null);
    }

    private BigDecimal currentPriceForValuation(PortfolioItem item, Map<String, List<StockPrice>> priceMap) {
        if (item.getAssetType() == AssetType.CASH) return BigDecimal.ONE;
        StockPrice price = latestAvailablePrice(item.getSymbol(), priceMap);
        return price == null ? null : price.getClosePrice();
    }

    private BigDecimal previousPriceForValuation(PortfolioItem item, Map<String, List<StockPrice>> priceMap) {
        if (item.getAssetType() == AssetType.CASH) return BigDecimal.ONE;
        List<StockPrice> histories = priceMap.getOrDefault(item.getSymbol(), List.of());
        StockPrice latest = latestAvailablePrice(item.getSymbol(), priceMap);
        if (latest == null) return null;
        if (latest.getPreviousClosePrice() != null) return latest.getPreviousClosePrice();
        return histories.stream()
                .filter(price -> price != null && price != latest && price.getClosePrice() != null)
                .filter(price -> price.getId() == null || latest.getId() == null
                        || price.getId().getBaseDate().isBefore(latest.getId().getBaseDate()))
                .findFirst()
                .map(StockPrice::getClosePrice)
                .orElse(null);
    }

    /**
     * 자산 항목(주식/현금)의 현재가를 결정합니다.
     */
    private BigDecimal getCurrentPrice(PortfolioItem item, Map<String, List<StockPrice>> priceMap) {
        if (item.getAssetType() == AssetType.CASH) return BigDecimal.ONE;
        StockPrice price = getLatestPrice(item.getSymbol(), priceMap);
        return (price != null) ? price.getClosePrice() : item.getPurchasePrice();
    }

    /**
     * 자산 항목의 현재 시점 평가 가치를 계산합니다.
     */
    private BigDecimal calculateCurrentValue(PortfolioItem item, Map<String, List<StockPrice>> priceMap) {
        if (item.getAssetType() == AssetType.CASH) return item.getQuantity();
        return item.getQuantity().multiply(getCurrentPrice(item, priceMap));
    }

    private String resolveDisplayName(PortfolioItem item, Map<String, Stock> stockMap) {
        if (item.getAssetType() == AssetType.CASH) {
            return "현금";
        }

        Stock stock = stockMap.get(item.getSymbol());
        if (stock != null && stock.getName() != null && !stock.getName().isBlank()) {
            return stock.getName();
        }
        return item.getSymbol();
    }
}

package org.stockwellness.application.service.portfolio.internal;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.DoubleUnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;
import org.stockwellness.application.port.in.stock.result.StockPriceResult;
import org.stockwellness.domain.portfolio.BacktestStrategy;
import org.stockwellness.domain.portfolio.RebalancingPeriod;
import org.stockwellness.domain.portfolio.indicator.BacktestAggregator;
import org.stockwellness.domain.portfolio.indicator.IndicatorCalculator;
import org.stockwellness.domain.portfolio.math.FinancialMath;
import org.stockwellness.domain.portfolio.strategy.CashFlowModel;
import org.stockwellness.domain.portfolio.strategy.DCAModel;
import org.stockwellness.domain.portfolio.strategy.LumpSumModel;
import org.stockwellness.domain.portfolio.strategy.RebalancingStrategy;
import org.stockwellness.domain.portfolio.vo.ReturnSeries;
import org.stockwellness.global.error.ErrorCode;
import org.stockwellness.global.error.exception.GlobalException;

/**
 * EOD 가격으로 거치식·적립식 백테스트를 수행하는 금융 계산 엔진입니다.
 *
 * <p>모든 위험 지표는 외부 현금흐름을 제거한 NAV의 일간 TWR을 사용합니다.
 * 따라서 DCA 납입일에 원금이 증가해도 수익률이 급등하지 않습니다.</p>
 */
@Component
public class BacktestEngine {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal NAV_BASE = BigDecimal.valueOf(100);
    private static final BigDecimal MIN_PRICE = BigDecimal.ZERO;
    private static final double DAYS_PER_YEAR = 365.0;
    private static final int SCALE = 16;

    private final RebalancingStrategy rebalancingStrategy = new RebalancingStrategy();
    private final BacktestAggregator aggregator = new BacktestAggregator();

    public BacktestResult runLumpSum(
            SimulationData data,
            Map<String, BigDecimal> weights,
            BigDecimal initialAmount,
            RebalancingPeriod rebalancingPeriod,
            String primaryBenchmarkTicker,
            BigDecimal riskFreeRate,
            boolean dividendReinvested
    ) {
        return runSimulation(
                data,
                weights,
                new LumpSumModel(initialAmount),
                BacktestStrategy.LUMP_SUM,
                rebalancingPeriod,
                primaryBenchmarkTicker,
                riskFreeRate,
                dividendReinvested
        );
    }

    public BacktestResult runDCA(
            SimulationData data,
            Map<String, BigDecimal> weights,
            BigDecimal monthlyAmount,
            RebalancingPeriod rebalancingPeriod,
            String primaryBenchmarkTicker,
            BigDecimal riskFreeRate,
            boolean dividendReinvested
    ) {
        return runSimulation(
                data,
                weights,
                new DCAModel(monthlyAmount),
                BacktestStrategy.DCA,
                rebalancingPeriod,
                primaryBenchmarkTicker,
                riskFreeRate,
                dividendReinvested
        );
    }

    private BacktestResult runSimulation(
            SimulationData data,
            Map<String, BigDecimal> weights,
            CashFlowModel cashFlowModel,
            BacktestStrategy strategy,
            RebalancingPeriod rebalancingPeriod,
            String primaryBenchmarkTicker,
            BigDecimal riskFreeRate,
            boolean dividendReinvested
    ) {
        validateInputs(data, weights, cashFlowModel, primaryBenchmarkTicker);

        List<LocalDate> allDates = extractSortedDates(data);
        if (allDates.isEmpty()) {
            throw new GlobalException(ErrorCode.PRICE_DATA_NOT_FOUND);
        }

        String resolvedPrimaryTicker = resolvePrimaryTicker(data.benchmarkPrices(), primaryBenchmarkTicker);
        if (resolvedPrimaryTicker == null) {
            throw new GlobalException(ErrorCode.PRICE_DATA_NOT_FOUND);
        }

        Map<String, BigDecimal[]> priceArrays = createAlignedPriceArrays(
                data.stockPrices(), allDates, dividendReinvested);
        Map<String, BigDecimal[]> benchmarkArrays = createAlignedPriceArrays(
                data.benchmarkPrices(), allDates, false);

        // A benchmark may have an observation before the first asset observation
        // (e.g. different market holidays).  Do not synthesize an asset price or
        // invest at that date; begin the simulation at the first date where every
        // weighted asset has a valid price while retaining the benchmark union
        // from that point onward.
        int simulationStartIndex = findSimulationStartIndex(allDates, priceArrays, weights);
        if (simulationStartIndex < 0) {
            throw new GlobalException(ErrorCode.PRICE_DATA_NOT_FOUND);
        }
        List<LocalDate> simulationDates = allDates.subList(simulationStartIndex, allDates.size());

        List<BacktestResult.DailyBacktestResult> dailyResults = new ArrayList<>(simulationDates.size());
        Map<LocalDate, BigDecimal> dailyReturns = new LinkedHashMap<>();
        List<BigDecimal> navValues = new ArrayList<>(simulationDates.size());
        Map<String, BigDecimal> totalShares = new HashMap<>();
        List<CashFlow> cashFlows = new ArrayList<>();

        BigDecimal totalInvested = BigDecimal.ZERO;
        BigDecimal previousValue = BigDecimal.ZERO;
        BigDecimal nav = NAV_BASE;
        LocalDate lastRebalanceDate = simulationDates.getFirst();
        YearMonth lastInvestmentMonth = null;

        for (int i = simulationStartIndex; i < allDates.size(); i++) {
            LocalDate date = allDates.get(i);
            Map<String, BigDecimal> currentPrices = extractCurrentPrices(priceArrays, i);

            BigDecimal deposit = BigDecimal.ZERO;
            if (strategy == BacktestStrategy.LUMP_SUM && i == simulationStartIndex) {
                deposit = cashFlowModel.getInitialAmount();
            } else if (strategy == BacktestStrategy.DCA) {
                YearMonth currentMonth = YearMonth.from(date);
                if (lastInvestmentMonth == null || !currentMonth.equals(lastInvestmentMonth)) {
                    deposit = ((DCAModel) cashFlowModel).monthlyAmount();
                    lastInvestmentMonth = currentMonth;
                }
            }

            if (deposit.compareTo(BigDecimal.ZERO) > 0) {
                totalInvested = totalInvested.add(deposit);
                cashFlows.add(new CashFlow(date, deposit.negate()));
                invest(totalShares, deposit, weights, currentPrices);
            }

            BigDecimal valueBeforeRebalance = calculateDailyValue(totalShares, priceArrays, i);
            if (rebalancingStrategy.shouldRebalance(date, lastRebalanceDate, rebalancingPeriod)) {
                rebalancingStrategy.rebalance(totalShares, valueBeforeRebalance, weights, currentPrices);
                lastRebalanceDate = date;
            }

            BigDecimal dailyValue = calculateDailyValue(totalShares, priceArrays, i);
            BigDecimal dailyTwr = i == simulationStartIndex
                    ? BigDecimal.ZERO
                    : calculateDailyTwr(previousValue, dailyValue, deposit);

            // The simulation may start after a leading benchmark-only date. The
            // first simulated asset date is the NAV baseline regardless of its
            // absolute index in the union date axis, so do not emit a return or
            // advance NAV until the next simulated date.
            if (i > simulationStartIndex) {
                dailyReturns.put(date, dailyTwr);
                nav = nav.multiply(
                        BigDecimal.ONE.add(dailyTwr.divide(HUNDRED, SCALE, RoundingMode.HALF_UP)),
                        new MathContext(SCALE, RoundingMode.HALF_UP)
                );
            }
            navValues.add(nav);

            dailyResults.add(new BacktestResult.DailyBacktestResult(
                    date,
                    dailyValue,
                    totalInvested,
                    dailyTwr,
                    calculateBenchmarkReturns(benchmarkArrays, i, simulationStartIndex)
            ));

            previousValue = dailyValue;
        }

        if (previousValue.compareTo(BigDecimal.ZERO) > 0) {
            cashFlows.add(new CashFlow(simulationDates.getLast(), previousValue));
        }

        double years = calculateYears(simulationDates.getFirst(), simulationDates.getLast());
        BigDecimal portfolioCagr = FinancialMath.calculateCAGR(NAV_BASE, nav, years);
        BacktestResult aggregated = aggregateResults(
                dailyResults,
                dailyReturns,
                navValues,
                NAV_BASE,
                nav,
                years,
                benchmarkArrays,
                resolvedPrimaryTicker,
                simulationStartIndex,
                calculateItemReturns(weights, totalShares, priceArrays, allDates.size() - 1, totalInvested),
                riskFreeRate,
                portfolioCagr
        );

        BigDecimal xirr = strategy == BacktestStrategy.DCA ? calculateXirr(cashFlows) : null;
        String calculationMethod = strategy == BacktestStrategy.DCA ? "DCA_XIRR_TWR" : "LUMP_SUM_CAGR_TWR";
        return withStrategyMetrics(aggregated, strategy, xirr, nav, portfolioCagr, calculationMethod, dailyReturns, simulationDates);
    }

    private BacktestResult withStrategyMetrics(
            BacktestResult result,
            BacktestStrategy strategy,
            BigDecimal xirr,
            BigDecimal nav,
            BigDecimal portfolioCagr,
            String calculationMethod,
            Map<LocalDate, BigDecimal> dailyReturns,
            List<LocalDate> dates
    ) {
        BigDecimal cagr = strategy == BacktestStrategy.LUMP_SUM ? portfolioCagr : null;
        BigDecimal twr = nav.subtract(NAV_BASE).divide(NAV_BASE, SCALE, RoundingMode.HALF_UP).multiply(HUNDRED);
        BigDecimal sortino = calculateSortino(dailyReturns.values().stream().toList(), portfolioCagr);
        Long recoveryPeriod = calculateRecoveryPeriod(dates, result, dailyReturns);
        return new BacktestResult(
                result.dailyResults(),
                cagr,
                result.mdd(),
                result.relativeMdd(),
                result.sharpeRatio(),
                twr,
                result.volatility(),
                result.alpha(),
                result.beta(),
                result.bestYearRate(),
                result.worstYearRate(),
                result.itemReturns(),
                result.comparisons(),
                result.aiComment(),
                xirr,
                twr,
                calculationMethod,
                sortino,
                recoveryPeriod
        );
    }

    private BacktestResult aggregateResults(
            List<BacktestResult.DailyBacktestResult> dailyResults,
            Map<LocalDate, BigDecimal> dailyReturns,
            List<BigDecimal> navValues,
            BigDecimal initialNav,
            BigDecimal finalNav,
            double years,
            Map<String, BigDecimal[]> benchmarkArrays,
            String primaryBenchmarkTicker,
            int simulationStartIndex,
            Map<String, BigDecimal> itemReturns,
            BigDecimal riskFreeRate,
            BigDecimal portfolioCagr
    ) {
        Map<String, ReturnSeries> benchmarkSeries = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal[]> entry : benchmarkArrays.entrySet()) {
            Map<LocalDate, BigDecimal> returns = new LinkedHashMap<>();
            BigDecimal[] prices = entry.getValue();
            for (int i = 1; i < dailyResults.size(); i++) {
                int previousPriceIndex = simulationStartIndex + i - 1;
                int currentPriceIndex = simulationStartIndex + i;
                BigDecimal previousPrice = prices[previousPriceIndex];
                BigDecimal currentPrice = prices[currentPriceIndex];
                if (previousPrice == null || currentPrice == null) {
                    continue;
                }
                returns.put(
                        dailyResults.get(i).date(),
                        FinancialMath.calculateReturnRate(previousPrice, currentPrice)
                );
            }
            benchmarkSeries.put(entry.getKey(), new ReturnSeries(returns));
        }

        IndicatorCalculator.IndicatorContext context = new IndicatorCalculator.IndicatorContext(
                new ReturnSeries(dailyReturns),
                navValues,
                initialNav,
                finalNav,
                years,
                benchmarkSeries,
                primaryBenchmarkTicker,
                riskFreeRate == null ? BigDecimal.ZERO : riskFreeRate,
                portfolioCagr
        );

        return aggregator.aggregate(dailyResults, context, itemReturns, null);
    }

    private int findSimulationStartIndex(
            List<LocalDate> allDates,
            Map<String, BigDecimal[]> priceArrays,
        Map<String, BigDecimal> weights
    ) {
        for (int i = 0; i < allDates.size(); i++) {
            int index = i;
            boolean allAssetsReady = weights.keySet().stream()
                    .map(priceArrays::get)
                    .allMatch(array -> array != null
                            && index < array.length
                            && array[index] != null
                            && array[index].compareTo(MIN_PRICE) > 0);
            if (allAssetsReady) {
                return i;
            }
        }
        return -1;
    }

    private void validateInputs(
            SimulationData data,
            Map<String, BigDecimal> weights,
            CashFlowModel cashFlowModel,
            String primaryBenchmarkTicker
    ) {
        if (data == null || data.stockPrices() == null || data.benchmarkPrices() == null
                || primaryBenchmarkTicker == null || primaryBenchmarkTicker.isBlank()) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE);
        }
        if (weights == null || weights.isEmpty()
                || weights.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getKey().isBlank()
                || entry.getValue() == null
                || entry.getValue().compareTo(BigDecimal.ZERO) <= 0)) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE);
        }
        BigDecimal weightTotal = weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (weightTotal.subtract(HUNDRED).abs().compareTo(new BigDecimal("0.000001")) > 0) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE);
        }
        if (cashFlowModel == null || cashFlowModel.getInitialAmount() == null
                || cashFlowModel.getInitialAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }

    private List<LocalDate> extractSortedDates(SimulationData data) {
        Stream<LocalDate> stockDates = data.stockPrices().values().stream()
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .filter(Objects::nonNull)
                .map(StockPriceResult::baseDate)
                .filter(Objects::nonNull);
        Stream<LocalDate> benchmarkDates = data.benchmarkPrices().values().stream()
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .filter(Objects::nonNull)
                .map(StockPriceResult::baseDate)
                .filter(Objects::nonNull);
        return Stream.concat(stockDates, benchmarkDates).distinct().sorted().toList();
    }

    private Map<String, BigDecimal[]> createAlignedPriceArrays(
            Map<String, List<StockPriceResult>> sourceData,
            List<LocalDate> allDates,
            boolean dividendReinvested
    ) {
        Map<String, BigDecimal[]> alignedPrices = new LinkedHashMap<>();
        sourceData.forEach((symbol, prices) -> {
            if (prices == null || prices.isEmpty()) {
                throw new GlobalException(ErrorCode.PRICE_DATA_NOT_FOUND);
            }
            NavigableMap<LocalDate, BigDecimal> priceMap = prices.stream()
                    .filter(price -> price != null && price.baseDate() != null)
                    .collect(Collectors.toMap(
                            StockPriceResult::baseDate,
                            price -> validatedPrice(price, dividendReinvested),
                            (first, ignored) -> first,
                            TreeMap::new
                    ));
            if (priceMap.isEmpty()) {
                throw new GlobalException(ErrorCode.PRICE_DATA_NOT_FOUND);
            }

            BigDecimal[] array = new BigDecimal[allDates.size()];
            BigDecimal lastPrice = null;
            for (int i = 0; i < allDates.size(); i++) {
                Map.Entry<LocalDate, BigDecimal> entry = priceMap.floorEntry(allDates.get(i));
                if (entry != null) {
                    lastPrice = entry.getValue();
                }
                // A leading null is expected when another market's benchmark
                // starts before this series.  The simulation start index filters
                // these dates before valuation/deposit; gaps after the first
                // observation continue to use the last known trading price.
                if (lastPrice == null) {
                    array[i] = null;
                    continue;
                }
                if (lastPrice.compareTo(MIN_PRICE) <= 0) {
                    throw new GlobalException(ErrorCode.PRICE_DATA_NOT_FOUND);
                }
                array[i] = lastPrice;
            }
            alignedPrices.put(symbol, array);
        });
        return alignedPrices;
    }

    private BigDecimal validatedPrice(StockPriceResult price, boolean dividendReinvested) {
        BigDecimal close = dividendReinvested && price.adjClosePrice() != null
                ? price.adjClosePrice()
                : price.closePrice();
        if (close == null || close.compareTo(MIN_PRICE) <= 0) {
            throw new GlobalException(ErrorCode.PRICE_DATA_NOT_FOUND);
        }
        return close;
    }

    private String resolvePrimaryTicker(Map<String, List<StockPriceResult>> benchmarkPrices, String requested) {
        if (benchmarkPrices.containsKey(requested)) {
            return requested;
        }
        String normalized = switch (requested.toUpperCase()) {
            case "SP500", "S&P500", "S&P 500" -> "SPX";
            case "KOSPI" -> "0001";
            case "KOSDAQ" -> "1001";
            default -> requested;
        };
        if (benchmarkPrices.containsKey(normalized)) {
            return normalized;
        }
        return benchmarkPrices.keySet().stream()
                .filter(key -> key.equalsIgnoreCase(requested) || key.equalsIgnoreCase(normalized))
                .findFirst()
                .orElse(null);
    }

    private Map<String, BigDecimal> extractCurrentPrices(Map<String, BigDecimal[]> priceArrays, int index) {
        Map<String, BigDecimal> prices = new HashMap<>();
        priceArrays.forEach((symbol, array) -> prices.put(symbol, array[index]));
        return prices;
    }

    private Map<String, BigDecimal> calculateBenchmarkReturns(
            Map<String, BigDecimal[]> benchmarkArrays,
            int currentIndex,
            int simulationStartIndex
    ) {
        Map<String, BigDecimal> returns = new LinkedHashMap<>();
        benchmarkArrays.forEach((ticker, array) -> {
            BigDecimal baseline = null;
            for (int i = simulationStartIndex; i < array.length; i++) {
                if (array[i] != null) {
                    baseline = array[i];
                    break;
                }
            }
            BigDecimal current = currentIndex < array.length ? array[currentIndex] : null;
            returns.put(ticker, baseline == null || current == null
                    ? BigDecimal.ZERO
                    : FinancialMath.calculateReturnRate(baseline, current));
        });
        return returns;
    }

    private void invest(
            Map<String, BigDecimal> totalShares,
            BigDecimal amount,
            Map<String, BigDecimal> weights,
            Map<String, BigDecimal> currentPrices
    ) {
        for (Map.Entry<String, BigDecimal> entry : weights.entrySet()) {
            BigDecimal price = currentPrices.get(entry.getKey());
            if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
                throw new GlobalException(ErrorCode.PRICE_DATA_NOT_FOUND);
            }
            BigDecimal allocated = amount.multiply(entry.getValue()).divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
            BigDecimal shares = allocated.divide(price, SCALE, RoundingMode.HALF_UP);
            totalShares.merge(entry.getKey(), shares, BigDecimal::add);
        }
    }

    private BigDecimal calculateDailyValue(
            Map<String, BigDecimal> shares,
            Map<String, BigDecimal[]> priceArrays,
            int index
    ) {
        BigDecimal value = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> entry : shares.entrySet()) {
            BigDecimal[] prices = priceArrays.get(entry.getKey());
            if (prices == null || prices[index].compareTo(BigDecimal.ZERO) <= 0) {
                throw new GlobalException(ErrorCode.PRICE_DATA_NOT_FOUND);
            }
            value = value.add(entry.getValue().multiply(prices[index]));
        }
        return value;
    }

    private BigDecimal calculateDailyTwr(BigDecimal previousValue, BigDecimal endValue, BigDecimal externalCashFlow) {
        if (previousValue == null || previousValue.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal endValueBeforeFlow = endValue.subtract(externalCashFlow == null ? BigDecimal.ZERO : externalCashFlow);
        if (endValueBeforeFlow.compareTo(BigDecimal.ZERO) < 0) {
            throw new GlobalException(ErrorCode.PRICE_DATA_NOT_FOUND);
        }
        return endValueBeforeFlow.subtract(previousValue)
                .divide(previousValue, SCALE, RoundingMode.HALF_UP)
                .multiply(HUNDRED);
    }

    private Map<String, BigDecimal> calculateItemReturns(
            Map<String, BigDecimal> weights,
            Map<String, BigDecimal> shares,
            Map<String, BigDecimal[]> priceArrays,
            int lastIndex,
            BigDecimal totalInvested
    ) {
        if (totalInvested == null || totalInvested.compareTo(BigDecimal.ZERO) <= 0) {
            return Map.of();
        }
        Map<String, BigDecimal> itemReturns = new LinkedHashMap<>();
        for (String symbol : weights.keySet()) {
            BigDecimal share = shares.getOrDefault(symbol, BigDecimal.ZERO);
            BigDecimal currentPrice = priceArrays.get(symbol)[lastIndex];
            BigDecimal currentValue = share.multiply(currentPrice);
            BigDecimal initialAllocated = totalInvested.multiply(weights.get(symbol)).divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
            BigDecimal contribution = currentValue.subtract(initialAllocated)
                    .divide(totalInvested, SCALE, RoundingMode.HALF_UP)
                    .multiply(HUNDRED);
            itemReturns.put(symbol, contribution);
        }
        return itemReturns;
    }

    private double calculateYears(LocalDate start, LocalDate end) {
        return Math.max(1.0 / DAYS_PER_YEAR, ChronoUnit.DAYS.between(start, end) / DAYS_PER_YEAR);
    }

    private BigDecimal calculateXirr(List<CashFlow> cashFlows) {
        if (cashFlows == null || cashFlows.isEmpty()
                || cashFlows.stream().noneMatch(flow -> flow.amount().compareTo(BigDecimal.ZERO) < 0)
                || cashFlows.stream().noneMatch(flow -> flow.amount().compareTo(BigDecimal.ZERO) > 0)) {
            return null;
        }
        // A single valuation date has no elapsed holding period, so an XIRR
        // root is undefined even when the deposit and liquidation happen on
        // that same date. Do not report the solver's initial guess (10%) as a
        // fallback for this non-applicable case.
        if (cashFlows.stream().map(CashFlow::date).distinct().count() < 2) {
            return null;
        }
        LocalDate baseDate = cashFlows.getFirst().date();
        DoubleUnaryOperator npv = rate -> cashFlows.stream()
                .mapToDouble(flow -> flow.amount().doubleValue()
                        / Math.pow(1.0 + rate, ChronoUnit.DAYS.between(baseDate, flow.date()) / DAYS_PER_YEAR))
                .sum();
        DoubleUnaryOperator derivative = rate -> cashFlows.stream()
                .mapToDouble(flow -> {
                    double years = ChronoUnit.DAYS.between(baseDate, flow.date()) / DAYS_PER_YEAR;
                    return -years * flow.amount().doubleValue()
                            / Math.pow(1.0 + rate, years + 1.0);
                })
                .sum();

        double rate = 0.1;
        for (int i = 0; i < 100; i++) {
            double value = npv.applyAsDouble(rate);
            if (Math.abs(value) < 1e-8) {
                if (Math.abs(rate) < 1e-9) {
                    return BigDecimal.ZERO;
                }
                return BigDecimal.valueOf(rate * 100).setScale(8, RoundingMode.HALF_UP);
            }
            double slope = derivative.applyAsDouble(rate);
            if (!Double.isFinite(slope) || Math.abs(slope) < 1e-12) {
                break;
            }
            double next = rate - value / slope;
            if (!Double.isFinite(next) || next <= -0.999999999) {
                break;
            }
            rate = next;
        }

        double low = -0.999999;
        double high = 1.0;
        double lowValue = npv.applyAsDouble(low);
        double highValue = npv.applyAsDouble(high);
        int expansions = 0;
        while (lowValue * highValue > 0 && expansions++ < 16) {
            high = high * 2.0 + 1.0;
            highValue = npv.applyAsDouble(high);
        }
        if (!Double.isFinite(lowValue) || !Double.isFinite(highValue) || lowValue * highValue > 0) {
            return null;
        }
        for (int i = 0; i < 160; i++) {
            double middle = (low + high) / 2.0;
            double middleValue = npv.applyAsDouble(middle);
            if (Math.abs(middleValue) < 1e-8) {
                if (Math.abs(middle) < 1e-9) {
                    return BigDecimal.ZERO;
                }
                return BigDecimal.valueOf(middle * 100).setScale(8, RoundingMode.HALF_UP);
            }
            if (lowValue * middleValue <= 0) {
                high = middle;
                highValue = middleValue;
            } else {
                low = middle;
                lowValue = middleValue;
            }
        }
        return null;
    }

    private BigDecimal calculateSortino(List<BigDecimal> returns, BigDecimal annualizedReturn) {
        if (returns == null || returns.size() < 2 || annualizedReturn == null) {
            return null;
        }
        List<BigDecimal> downside = returns.stream()
                .filter(value -> value.compareTo(BigDecimal.ZERO) < 0)
                .toList();
        if (downside.isEmpty()) {
            return null;
        }
        BigDecimal squared = downside.stream()
                .map(value -> value.multiply(value))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(returns.size()), SCALE, RoundingMode.HALF_UP);
        BigDecimal downsideAnnualized = FinancialMath.sqrt(squared)
                .multiply(FinancialMath.sqrt(BigDecimal.valueOf(252)));
        if (downsideAnnualized.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return annualizedReturn.divide(downsideAnnualized, SCALE, RoundingMode.HALF_UP);
    }

    private Long calculateRecoveryPeriod(
            List<LocalDate> dates,
            BacktestResult result,
            Map<LocalDate, BigDecimal> dailyReturns
    ) {
        if (dates == null || dates.size() < 2 || dailyReturns.isEmpty()) {
            return null;
        }
        BigDecimal peak = NAV_BASE;
        LocalDate peakDate = dates.getFirst();
        long maxDays = 0;
        BigDecimal nav = NAV_BASE;
        for (int i = 1; i < dates.size(); i++) {
            BigDecimal daily = dailyReturns.getOrDefault(dates.get(i), BigDecimal.ZERO);
            nav = nav.multiply(BigDecimal.ONE.add(daily.divide(HUNDRED, SCALE, RoundingMode.HALF_UP)));
            if (nav.compareTo(peak) >= 0) {
                maxDays = Math.max(maxDays, ChronoUnit.DAYS.between(peakDate, dates.get(i)));
                peak = nav;
                peakDate = dates.get(i);
            }
        }
        if (result.mdd().compareTo(BigDecimal.ZERO) == 0) {
            return 0L;
        }
        maxDays = Math.max(maxDays, ChronoUnit.DAYS.between(peakDate, dates.getLast()));
        return maxDays;
    }

    private record CashFlow(LocalDate date, BigDecimal amount) {}
}

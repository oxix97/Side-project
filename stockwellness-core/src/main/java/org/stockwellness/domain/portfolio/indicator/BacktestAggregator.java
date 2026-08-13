package org.stockwellness.domain.portfolio.indicator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.stockwellness.application.service.portfolio.internal.BacktestResult;
import org.stockwellness.domain.portfolio.vo.ReturnSeries;

/**
 * 분리된 지표 계산기들을 사용하여 최종 BacktestResult를 조합하는 애그리게이터
 */
public class BacktestAggregator {

    private final PerformanceCalculator performanceCalculator = new PerformanceCalculator();
    private final RiskCalculator riskCalculator = new RiskCalculator();
    private final EfficiencyCalculator efficiencyCalculator = new EfficiencyCalculator();
    private final MarketCorrelationCalculator correlationCalculator = new MarketCorrelationCalculator();

    public BacktestResult aggregate(
            List<BacktestResult.DailyBacktestResult> dailyResults,
            IndicatorCalculator.IndicatorContext context,
            Map<String, BigDecimal> itemReturns,
            String aiComment
    ) {
        PerformanceCalculator.PerformanceMetrics perf = performanceCalculator.calculate(context);
        RiskCalculator.RiskMetrics risk = riskCalculator.calculate(context);
        EfficiencyCalculator.EfficiencyMetrics efficiency = efficiencyCalculator.calculate(context);
        Map<String, MarketCorrelationCalculator.CorrelationMetrics> correlations = correlationCalculator.calculate(context);

        List<BacktestResult.IndexComparison> comparisons = new ArrayList<>();
        for (Map.Entry<String, MarketCorrelationCalculator.CorrelationMetrics> entry : correlations.entrySet()) {
            String ticker = entry.getKey();
            MarketCorrelationCalculator.CorrelationMetrics metrics = entry.getValue();
            
            // 벤치마크별 MDD 및 상대 MDD 계산
            ReturnSeries benchmarkSeries = context.benchmarkReturns().get(ticker);
            BigDecimal benchmarkMdd = (benchmarkSeries != null) ? benchmarkSeries.calculateMDDFromReturns() : BigDecimal.ZERO;
            BigDecimal relativeMdd = risk.mdd().subtract(benchmarkMdd);

            comparisons.add(new BacktestResult.IndexComparison(
                benchmarkDisplayName(ticker),
                ticker,
                metrics.indexReturn(),
                metrics.alpha(),
                metrics.beta(),
                benchmarkMdd,
                relativeMdd
            ));
        }

        // 스칼라 지표는 요청된 primary benchmark와 같은 comparison을 사용한다.
        BacktestResult.IndexComparison primary = comparisons.stream()
                .filter(comparison -> comparison.ticker().equalsIgnoreCase(context.primaryBenchmarkTicker()))
                .findFirst()
                .orElse(comparisons.isEmpty() ? null : comparisons.getFirst());
        BigDecimal primaryAlpha = primary == null ? BigDecimal.ZERO : primary.alpha();
        BigDecimal primaryBeta = primary == null ? BigDecimal.ONE : primary.beta();
        BigDecimal primaryRelativeMdd = primary == null ? BigDecimal.ZERO : primary.relativeMdd();

        return new BacktestResult(
                dailyResults,
                perf.cagr(),
                risk.mdd(),
                primaryRelativeMdd,
                efficiency.sharpeRatio(),
                perf.totalReturnRate(),
                risk.annualizedVolatility(),
                primaryAlpha,
                primaryBeta,
                perf.bestYearRate(),
                perf.worstYearRate(),
                itemReturns,
                comparisons,
                aiComment
        );
    }

    private String benchmarkDisplayName(String ticker) {
        try {
            return BenchmarkCode.fromCode(ticker).getDisplayName();
        } catch (IllegalArgumentException ignored) {
            return ticker;
        }
    }
}

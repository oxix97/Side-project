package org.stockwellness.adapter.in.web.portfolio.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.stockwellness.application.service.portfolio.internal.BacktestResult;
import org.stockwellness.domain.portfolio.indicator.BenchmarkCode;

public record BacktestResponse(
    List<DailyResult> dailyResults,
    String primaryBenchmark,
    BigDecimal cagr,
    BigDecimal xirr,
    BigDecimal timeWeightedReturnRate,
    String calculationMethod,
    BigDecimal mdd,
    BigDecimal relativeMdd,
    BigDecimal sharpeRatio,
    BigDecimal sortinoRatio,
    Long recoveryPeriod,
    BigDecimal totalReturnRate,
    BigDecimal volatility,
    BigDecimal alpha,
    BigDecimal beta,
    BigDecimal bestYearRate,
    BigDecimal worstYearRate,
    Map<String, BigDecimal> itemReturns,
    List<IndexComparisonResponse> comparisons,
    String aiComment
) {
    public record DailyResult(
        LocalDate date,
        BigDecimal totalValue,
        BigDecimal totalInvested,
        BigDecimal returnRate,
        BigDecimal benchmarkReturnRate,
        Map<String, BigDecimal> benchmarkReturnRates
    ) {}

    public record IndexComparisonResponse(
        String indexName,
        String ticker,
        BigDecimal totalReturn,
        BigDecimal alpha,
        BigDecimal beta,
        BigDecimal mdd,
        BigDecimal relativeMdd
    ) {}

    public static BacktestResponse from(BacktestResult result, String primaryBenchmarkTicker) {
        String primaryCode = toExternalCode(primaryBenchmarkTicker);
        return new BacktestResponse(
                result.dailyResults().stream()
                        .map(daily -> toDailyResult(daily, primaryBenchmarkTicker))
                        .toList(),
                primaryCode,
                scaleNullable(result.cagr()),
                scaleNullable(result.xirr()),
                scaleNullable(result.timeWeightedReturnRate()),
                result.calculationMethod(),
                scaleNullable(result.mdd()),
                scaleNullable(result.relativeMdd()),
                scaleNullable(result.sharpeRatio()),
                scaleNullable(result.sortinoRatio()),
                result.recoveryPeriod(),
                scaleNullable(result.totalReturnRate()),
                scaleNullable(result.volatility()),
                scaleNullable(result.alpha()),
                scaleNullable(result.beta()),
                scaleNullable(result.bestYearRate()),
                scaleNullable(result.worstYearRate()),
                scaleMap(result.itemReturns()),
                result.comparisons().stream()
                        .map(comparison -> new IndexComparisonResponse(
                                comparison.indexName(),
                                toExternalCode(comparison.ticker()),
                                scaleNullable(comparison.totalReturn()),
                                scaleNullable(comparison.alpha()),
                                scaleNullable(comparison.beta()),
                                scaleNullable(comparison.mdd()),
                                scaleNullable(comparison.relativeMdd())
                        ))
                        .toList(),
                result.aiComment()
        );
    }

    private static DailyResult toDailyResult(BacktestResult.DailyBacktestResult daily, String primaryTicker) {
        Map<String, BigDecimal> benchmarkRates = new LinkedHashMap<>();
        daily.benchmarkReturnRates().forEach((ticker, value) -> benchmarkRates.put(toExternalCode(ticker), scaleNullable(value)));
        BigDecimal primaryRate = daily.benchmarkReturnRates().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(primaryTicker)
                        || toExternalCode(entry.getKey()).equalsIgnoreCase(toExternalCode(primaryTicker)))
                .map(Map.Entry::getValue)
                .findFirst()
                .map(BacktestResponse::scaleNullable)
                .orElse(null);
        return new DailyResult(
                daily.date(),
                scaleNullable(daily.totalValue(), 0),
                scaleNullable(daily.totalInvested(), 0),
                scaleNullable(daily.returnRate()),
                primaryRate,
                benchmarkRates
        );
    }

    private static Map<String, BigDecimal> scaleMap(Map<String, BigDecimal> values) {
        Map<String, BigDecimal> scaled = new LinkedHashMap<>();
        if (values != null) {
            values.forEach((key, value) -> scaled.put(key, scaleNullable(value)));
        }
        return scaled;
    }

    private static BigDecimal scaleNullable(BigDecimal value) {
        return scaleNullable(value, 4);
    }

    private static BigDecimal scaleNullable(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP);
    }

    private static String toExternalCode(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return ticker;
        }
        try {
            return BenchmarkCode.fromCode(ticker).getCode();
        } catch (IllegalArgumentException ignored) {
            return ticker;
        }
    }
}

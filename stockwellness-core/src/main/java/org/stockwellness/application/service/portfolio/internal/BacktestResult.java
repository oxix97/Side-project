package org.stockwellness.application.service.portfolio.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public record BacktestResult(
    List<DailyBacktestResult> dailyResults,
    BigDecimal cagr,              // 연평균 수익률
    BigDecimal mdd,               // 최대 낙폭
    BigDecimal relativeMdd,       // 벤치마크 대비 상대 낙폭 (Portfolio MDD - Benchmark MDD)
    BigDecimal sharpeRatio,       // 샤프 지수
    BigDecimal totalReturnRate,   // 총 수익률
    BigDecimal volatility,        // 변동성 (표준편차)
    BigDecimal alpha,             // 초과 수익률 (vs Primary Benchmark)
    BigDecimal beta,              // 시장 민감도
    BigDecimal bestYearRate,      // 최고의 해 수익률
    BigDecimal worstYearRate,     // 최악의 해 수익률
    Map<String, BigDecimal> itemReturns, // 종목별 수익률 (기여도 산출용)
    List<IndexComparison> comparisons, // 다중 지수 비교 결과 추가
    String aiComment,
    BigDecimal xirr,               // DCA 현금흐름 기준 연환산 수익률
    BigDecimal timeWeightedReturnRate, // 외부 현금흐름을 제거한 누적 TWR
    String calculationMethod,      // DCA_XIRR_TWR 또는 LUMP_SUM_CAGR_TWR
    BigDecimal sortinoRatio,
    Long recoveryPeriod
) {
    /** 이전 내부 호출부와의 호환을 위한 오버로드입니다. */
    public BacktestResult(
            List<DailyBacktestResult> dailyResults,
            BigDecimal cagr,
            BigDecimal mdd,
            BigDecimal relativeMdd,
            BigDecimal sharpeRatio,
            BigDecimal totalReturnRate,
            BigDecimal volatility,
            BigDecimal alpha,
            BigDecimal beta,
            BigDecimal bestYearRate,
            BigDecimal worstYearRate,
            Map<String, BigDecimal> itemReturns,
            List<IndexComparison> comparisons,
            String aiComment
    ) {
        this(
                dailyResults, cagr, mdd, relativeMdd, sharpeRatio, totalReturnRate,
                volatility, alpha, beta, bestYearRate, worstYearRate, itemReturns,
                comparisons, aiComment, null, totalReturnRate, null, null, null
        );
    }

    public static BacktestResult empty() {
        return new BacktestResult(
            Collections.emptyList(),
            BigDecimal.ZERO, // cagr
            BigDecimal.ZERO, // mdd
            BigDecimal.ZERO, // relativeMdd
            BigDecimal.ZERO, // sharpeRatio
            BigDecimal.ZERO, // totalReturnRate
            BigDecimal.ZERO, // volatility
            BigDecimal.ZERO, // alpha
            BigDecimal.ZERO, // beta
            BigDecimal.ZERO, // bestYearRate
            BigDecimal.ZERO, // worstYearRate
            Collections.emptyMap(),
            Collections.emptyList(),
            null,
            null,
            BigDecimal.ZERO,
            null,
            null,
            null
        );
    }

    public record DailyBacktestResult(
        LocalDate date,
        BigDecimal totalValue,
        BigDecimal totalInvested,
        BigDecimal returnRate,
        Map<String, BigDecimal> benchmarkReturnRates // Ticker -> 누적 수익률 (다중 지수)
    ) {
        /** 외부 현금흐름을 제거한 일간 시간가중수익률(%)입니다. */
        public BigDecimal dailyTwr() {
            return returnRate;
        }
    }

    public record IndexComparison(
        String indexName,
        String ticker,
        BigDecimal totalReturn,
        BigDecimal alpha,
        BigDecimal beta,
        BigDecimal mdd,
        BigDecimal relativeMdd
    ) {}
}

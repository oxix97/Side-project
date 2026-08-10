package org.stockwellness.application.port.in.portfolio.result;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PortfolioValuationResult(
    BigDecimal totalPurchaseAmount,
    BigDecimal currentTotalValue,
    BigDecimal totalProfitLoss,
    BigDecimal totalReturnRate,
    BigDecimal dailyProfitLoss,
    BigDecimal dailyReturnRate,
    BigDecimal cagr,
    BigDecimal volatility,
    BigDecimal alpha,
    BigDecimal mdd,
    BigDecimal sharpeRatio,
    BigDecimal beta,
    BigDecimal totalInstitutionalNetBuying,
    BigDecimal totalForeignNetBuying,
    BigDecimal totalPersonNetBuying,
    ValuationStatus valuationStatus,
    LocalDate asOfDate,
    List<String> missingSymbols
) {
    /**
     * 이전 호출자와의 소스 호환성을 유지하되, 새 상태 계약은 완전 평가로 명시한다.
     */
    public PortfolioValuationResult(
            BigDecimal totalPurchaseAmount,
            BigDecimal currentTotalValue,
            BigDecimal totalProfitLoss,
            BigDecimal totalReturnRate,
            BigDecimal dailyProfitLoss,
            BigDecimal dailyReturnRate,
            BigDecimal cagr,
            BigDecimal volatility,
            BigDecimal alpha,
            BigDecimal mdd,
            BigDecimal sharpeRatio,
            BigDecimal beta,
            BigDecimal totalInstitutionalNetBuying,
            BigDecimal totalForeignNetBuying,
            BigDecimal totalPersonNetBuying
    ) {
        this(totalPurchaseAmount, currentTotalValue, totalProfitLoss, totalReturnRate,
                dailyProfitLoss, dailyReturnRate, cagr, volatility, alpha, mdd,
                sharpeRatio, beta, totalInstitutionalNetBuying, totalForeignNetBuying,
                totalPersonNetBuying, ValuationStatus.COMPLETE, null, List.of());
    }

    public PortfolioValuationResult {
        valuationStatus = valuationStatus == null ? ValuationStatus.UNAVAILABLE : valuationStatus;
        missingSymbols = missingSymbols == null ? List.of() : List.copyOf(missingSymbols);
    }
}

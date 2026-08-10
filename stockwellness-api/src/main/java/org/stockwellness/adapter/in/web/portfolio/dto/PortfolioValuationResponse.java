package org.stockwellness.adapter.in.web.portfolio.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import org.stockwellness.application.port.in.portfolio.result.PortfolioValuationResult;
import org.stockwellness.application.port.in.portfolio.result.ValuationStatus;

public record PortfolioValuationResponse(
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
    public PortfolioValuationResponse(
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

    public static PortfolioValuationResponse from(PortfolioValuationResult result) {
        return new PortfolioValuationResponse(
            scale(result.totalPurchaseAmount(), 0),
            scale(result.currentTotalValue(), 0),
            scale(result.totalProfitLoss(), 0),
            scale(result.totalReturnRate(), 4),
            scale(result.dailyProfitLoss(), 0),
            scale(result.dailyReturnRate(), 4),
            scale(result.cagr(), 4),
            scale(result.volatility(), 4),
            scale(result.alpha(), 4),
            scale(result.mdd(), 4),
            scale(result.sharpeRatio(), 4),
            scale(result.beta(), 4),
            scale(result.totalInstitutionalNetBuying(), 0),
            scale(result.totalForeignNetBuying(), 0),
            scale(result.totalPersonNetBuying(), 0),
            result.valuationStatus(),
            result.asOfDate(),
            result.missingSymbols()
        );
    }

    private static BigDecimal scale(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP);
    }
}

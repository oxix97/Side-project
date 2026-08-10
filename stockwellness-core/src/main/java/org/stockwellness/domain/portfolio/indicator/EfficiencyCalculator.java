package org.stockwellness.domain.portfolio.indicator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.stockwellness.domain.portfolio.math.FinancialMath;

/**
 * 효율성 지표 계산기 (Sharpe Ratio, Sortino Ratio 등)
 */
public class EfficiencyCalculator implements IndicatorCalculator<EfficiencyCalculator.EfficiencyMetrics> {

    public record EfficiencyMetrics(
        BigDecimal sharpeRatio,
        BigDecimal sortinoRatio
    ) {}

    private static final BigDecimal VOLATILITY_THRESHOLD = new BigDecimal("0.00000001");

    @Override
    public EfficiencyMetrics calculate(IndicatorContext context) {
        BigDecimal riskFreeRate = context.riskFreeRate();
        
        // Sharpe의 초과 수익률 기준은 선택한 비교지수가 아니라 연환산 무위험 수익률이다.
        BigDecimal baseReturn = riskFreeRate == null ? BigDecimal.ZERO : riskFreeRate;

        // 포트폴리오 연환산 TWR - 무위험 수익률
        BigDecimal cagr = context.portfolioCagr() != null ? context.portfolioCagr() : 
                FinancialMath.calculateCAGR(context.initialAmount(), context.finalAmount(), context.years());
        BigDecimal excessReturn = cagr.subtract(baseReturn);

        // 2. Sharpe Ratio (고도화: 벤치마크 대비 상대 Sharpe 지표 성격 포함)
        BigDecimal annualizedVolatility = context.portfolioReturns().calculateAnnualizedVolatility();
        BigDecimal sharpeRatio = BigDecimal.ZERO;
        if (annualizedVolatility.compareTo(VOLATILITY_THRESHOLD) > 0) {
            sharpeRatio = excessReturn.divide(annualizedVolatility, 16, RoundingMode.HALF_UP);
        }

        // 3. Sortino Ratio
        BigDecimal downsideVolatility = calculateDownsideVolatility(context.portfolioReturns().getReturnsOnly());
        BigDecimal annualizedDownsideVolatility = FinancialMath.annualizeVolatility(downsideVolatility);
        BigDecimal sortinoRatio = BigDecimal.ZERO;
        if (annualizedDownsideVolatility.compareTo(VOLATILITY_THRESHOLD) > 0) {
            sortinoRatio = excessReturn.divide(annualizedDownsideVolatility, 16, RoundingMode.HALF_UP);
        }

        return new EfficiencyMetrics(sharpeRatio, sortinoRatio);
    }

    private BigDecimal calculateDownsideVolatility(List<BigDecimal> returns) {
        if (returns == null || returns.isEmpty()) return BigDecimal.ZERO;
        
        // Downside returns only (< 0)
        List<BigDecimal> negativeReturns = returns.stream()
                .filter(r -> r.compareTo(BigDecimal.ZERO) < 0)
                .toList();

        if (negativeReturns.isEmpty()) return BigDecimal.ZERO;

        // Variance of negative returns (assuming MAR = 0)
        BigDecimal sumSquared = BigDecimal.ZERO;
        for (BigDecimal r : negativeReturns) {
            sumSquared = sumSquared.add(r.multiply(r));
        }
        
        // Sum / TOTAL returns count (not just negative returns count)
        BigDecimal downsideVariance = sumSquared.divide(BigDecimal.valueOf(returns.size()), 16, RoundingMode.HALF_UP);
        return FinancialMath.sqrt(downsideVariance);
    }
}

package org.stockwellness.domain.portfolio.indicator;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

import org.stockwellness.domain.portfolio.math.FinancialMath;
import org.stockwellness.domain.portfolio.vo.ReturnSeries;

/**
 * 시장 상관성 지표 계산기 (Alpha, Beta 등)
 */
public class MarketCorrelationCalculator implements IndicatorCalculator<Map<String, MarketCorrelationCalculator.CorrelationMetrics>> {

    public record CorrelationMetrics(
        BigDecimal indexReturn,
        BigDecimal alpha,
        BigDecimal beta
    ) {}

    @Override
    public Map<String, CorrelationMetrics> calculate(IndicatorContext context) {
        Map<String, CorrelationMetrics> results = new LinkedHashMap<>();
        
        ReturnSeries portfolio = context.portfolioReturns();
        BigDecimal portfolioTotalReturn = calculateCumulativeReturn(portfolio);

        for (Map.Entry<String, ReturnSeries> entry : context.benchmarkReturns().entrySet()) {
            String ticker = entry.getKey();
            ReturnSeries benchmark = entry.getValue();

            // Index Return calculation (last value vs initial benchmark value might be needed, 
            // but usually it's cumulative return of benchmark during the period)
            // For simplicity, we calculate from the return series if available.
            BigDecimal indexReturn = calculateCumulativeReturn(benchmark);
            
            BigDecimal beta = FinancialMath.calculateBeta(portfolio.getReturnsOnly(), benchmark.getReturnsOnly());
            BigDecimal alpha = portfolioTotalReturn.subtract(indexReturn); // Simple Alpha

            results.put(ticker, new CorrelationMetrics(indexReturn, alpha, beta));
        }
        
        return results;
    }

    private BigDecimal calculateCumulativeReturn(ReturnSeries series) {
        if (series == null || series.isEmpty()) return BigDecimal.ZERO;
        
        // (1 + r1) * (1 + r2) * ... - 1
        BigDecimal cumulative = BigDecimal.ONE;
        for (BigDecimal r : series.getReturnsOnly()) {
            BigDecimal multiplier = BigDecimal.ONE.add(r.divide(BigDecimal.valueOf(100), 16, RoundingMode.HALF_UP));
            cumulative = cumulative.multiply(multiplier, new MathContext(16));
        }
        
        return cumulative.subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100));
    }
}

package org.stockwellness.domain.stock.insight;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record MarketWeatherPolicy(
    BigDecimal trendWeight,
    BigDecimal breadthWeight,
    BigDecimal momentumWeight,
    int rollingWindowDays
) {
    public static final MarketWeatherPolicy DEFAULT = new MarketWeatherPolicy(
        new BigDecimal("0.4"),
        new BigDecimal("0.4"),
        new BigDecimal("0.2"),
        252
    );

    public int calculateIntegratedScore(int trendScore, int breadthScore, int momentumScore) {
        return BigDecimal.valueOf(trendScore).multiply(trendWeight)
                .add(BigDecimal.valueOf(breadthScore).multiply(breadthWeight))
                .add(BigDecimal.valueOf(momentumScore).multiply(momentumWeight))
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();
    }
}

package org.stockwellness.application.service.insight;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Component;
import org.stockwellness.adapter.out.persistence.insight.SectorIndicator;
import org.stockwellness.domain.stock.insight.MarketWeatherPolicy;
import org.stockwellness.domain.stock.insight.RollingPercentileCalculator;

@Component
public class WeatherScoreCalculator {

    public int calculate(SectorIndicator current, List<SectorIndicator> history) {
        List<BigDecimal> trendHistory = history.stream().map(SectorIndicator::getMa20Disparity).toList();
        List<BigDecimal> breadthHistory = history.stream().map(SectorIndicator::getAdr).toList();
        List<BigDecimal> momentumHistory = history.stream().map(SectorIndicator::getRsi14).toList();

        int trendScore = RollingPercentileCalculator.calculate(current.getMa20Disparity(), trendHistory);
        int breadthScore = RollingPercentileCalculator.calculate(current.getAdr(), breadthHistory);
        int momentumScore = RollingPercentileCalculator.calculate(current.getRsi14(), momentumHistory);

        return MarketWeatherPolicy.DEFAULT.calculateIntegratedScore(trendScore, breadthScore, momentumScore);
    }
}

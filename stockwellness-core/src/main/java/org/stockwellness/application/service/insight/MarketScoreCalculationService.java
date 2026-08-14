package org.stockwellness.application.service.insight;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.stockwellness.adapter.out.persistence.insight.SectorIndicator;
import org.stockwellness.adapter.out.persistence.insight.repository.SectorIndicatorRepository;
import org.stockwellness.adapter.out.persistence.stock.repository.SectorInsightRepository;
import org.stockwellness.application.port.out.messaging.MarketScoreCalculatedEvent;
import org.stockwellness.domain.stock.MarketType;
import org.stockwellness.domain.stock.insight.MarketWeatherPolicy;
import org.stockwellness.domain.stock.insight.SectorInsight;

@Service
@RequiredArgsConstructor
public class MarketScoreCalculationService {

    private final SectorIndicatorRepository sectorIndicatorRepository;
    private final SectorInsightRepository sectorInsightRepository;
    private final WeatherScoreCalculator weatherScoreCalculator;

    public List<MarketScoreCalculatedEvent> calculate(LocalDate targetDate) {
        Map<MarketType, List<SectorInsight>> insightsByMarket = sectorInsightRepository.findAllByBaseDate(targetDate)
                .stream()
                .collect(Collectors.groupingBy(SectorInsight::getMarketType));

        return insightsByMarket.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> calculate(targetDate, entry.getKey(), entry.getValue()))
                .toList();
    }

    private MarketScoreCalculatedEvent calculate(
            LocalDate targetDate,
            MarketType marketType,
            List<SectorInsight> insights
    ) {
        List<MarketScoreCalculatedEvent.SectorScore> sectorScores = insights.stream()
                .sorted(Comparator.comparing(SectorInsight::getSectorCode))
                .map(insight -> calculateSectorScore(targetDate, insight))
                .toList();

        int overallScore = BigDecimal.valueOf(sectorScores.stream()
                        .mapToInt(MarketScoreCalculatedEvent.SectorScore::score)
                        .average()
                        .orElse(50.0))
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();

        return new MarketScoreCalculatedEvent(
                targetDate,
                marketType.name(),
                overallScore,
                sectorScores
        );
    }

    private MarketScoreCalculatedEvent.SectorScore calculateSectorScore(
            LocalDate targetDate,
            SectorInsight insight
    ) {
        SectorIndicator current = sectorIndicatorRepository
                .findByBaseDateAndSectorCode(targetDate, insight.getSectorCode())
                .orElseThrow(() -> new IllegalStateException(
                        "당일 섹터 지표가 없습니다: " + insight.getSectorCode()
                ));
        if (current.getMa20Disparity() == null
                || current.getMa20Disparity().compareTo(BigDecimal.ZERO) <= 0
                || current.getAdr() == null
                || current.getRsi14() == null) {
            throw new IllegalStateException(
                    "당일 섹터 지표가 불완전합니다: " + targetDate + ", " + insight.getSectorCode()
            );
        }
        List<SectorIndicator> history = sectorIndicatorRepository
                .findAllBySectorCodeAndBaseDateLessThanEqualOrderByBaseDateDesc(
                        insight.getSectorCode(),
                        targetDate,
                        PageRequest.of(0, MarketWeatherPolicy.DEFAULT.rollingWindowDays())
                );

        return new MarketScoreCalculatedEvent.SectorScore(
                insight.getSectorCode(),
                insight.getSectorName(),
                weatherScoreCalculator.calculate(current, history)
        );
    }
}

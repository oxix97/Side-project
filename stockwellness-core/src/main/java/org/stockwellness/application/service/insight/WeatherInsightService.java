package org.stockwellness.application.service.insight;

import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.stockwellness.adapter.out.persistence.insight.MarketWeather;
import org.stockwellness.adapter.out.persistence.insight.MarketWeather.SectorSummary;
import org.stockwellness.adapter.out.persistence.insight.SectorWeather;
import org.stockwellness.adapter.out.persistence.insight.repository.MarketWeatherRepository;
import org.stockwellness.adapter.out.persistence.insight.repository.SectorWeatherRepository;
import org.stockwellness.application.port.out.external.SearchApiPort;
import org.stockwellness.application.port.out.messaging.MarketScoreCalculatedEvent;
import org.stockwellness.application.port.out.sector.WeatherInsightPort;
import org.stockwellness.domain.stock.insight.WeatherState;

@Slf4j
@Service
@RequiredArgsConstructor
public class WeatherInsightService {

    private final SearchApiPort searchApiPort;
    private final WeatherInsightPort weatherInsightPort;
    private final MarketWeatherRepository marketWeatherRepository;
    private final SectorWeatherRepository sectorWeatherRepository;

    @Transactional
    public void generateInsights(MarketScoreCalculatedEvent event) {
        log.info("🤖 Generating AI Insights for market: {} on {}", event.marketType(), event.baseDate());

        // 1. Search News via Tavily
        String newsContext = searchApiPort.searchFinancialNews(event.marketType() + " 시장 현황 및 주요 경제 지표");

        // 2. Generate Market Summary via OpenAI
        String summary = weatherInsightPort.generateMarketWeatherSummary(event.overallScore(), event.marketType(), newsContext);

        // 3. Prepare Top/Bottom Sectors for JSONB
        List<SectorSummary> topSectorSummaries = event.sectorScores().stream()
                .sorted((a, b) -> Integer.compare(b.score(), a.score()))
                .limit(3)
                .map(s -> new SectorSummary(s.code(), s.name(), s.score(), WeatherState.fromScore(s.score()).getIconEmoji()))
                .toList();

        List<SectorSummary> bottomSectorSummaries = event.sectorScores().stream()
                .sorted((a, b) -> Integer.compare(a.score(), b.score()))
                .limit(3)
                .map(s -> new SectorSummary(s.code(), s.name(), s.score(), WeatherState.fromScore(s.score()).getIconEmoji()))
                .toList();

        // 4. Save Market Weather
        MarketWeather calculatedMarketWeather = MarketWeather.builder()
                .baseDate(event.baseDate())
                .marketType(event.marketType())
                .weatherScore(event.overallScore())
                .weatherState(WeatherState.fromScore(event.overallScore()).getStateName())
                .aiSummary(summary)
                .topSectors(topSectorSummaries)
                .bottomSectors(bottomSectorSummaries)
                .build();
        MarketWeather marketWeather = marketWeatherRepository
                .findByBaseDateAndMarketType(event.baseDate(), event.marketType())
                .map(existing -> {
                    existing.updateCalculation(
                            calculatedMarketWeather.getWeatherScore(),
                            calculatedMarketWeather.getWeatherState(),
                            calculatedMarketWeather.getTopSectors(),
                            calculatedMarketWeather.getBottomSectors()
                    );
                    existing.updateInsight(summary);
                    return existing;
                })
                .orElse(calculatedMarketWeather);
        marketWeatherRepository.save(marketWeather);

        // 5. Generate Top Sector Insights (Detailed)
        for (SectorSummary sector : topSectorSummaries) {
            String sectorNews = searchApiPort.searchFinancialNews(sector.name() + " 업종 최근 동향");
            var insight = weatherInsightPort.generateSectorWeatherInsight(sector.name(), sector.score(), sectorNews);

            SectorWeather calculatedSectorWeather = SectorWeather.builder()
                    .baseDate(event.baseDate())
                    .sectorCode(sector.code())
                    .weatherScore(sector.score())
                    .weatherState(WeatherState.fromScore(sector.score()).getStateName())
                    .aiTitle(insight.title())
                    .aiInsight(insight.insight())
                    .build();
            SectorWeather sectorWeather = sectorWeatherRepository
                    .findByBaseDateAndSectorCode(event.baseDate(), sector.code())
                    .map(existing -> {
                        existing.updateScore(
                                calculatedSectorWeather.getWeatherScore(),
                                calculatedSectorWeather.getWeatherState()
                        );
                        existing.updateInsight(insight.title(), insight.insight());
                        return existing;
                    })
                    .orElse(calculatedSectorWeather);
            sectorWeatherRepository.save(sectorWeather);
        }
        
        log.info("✅ Finished generating AI Insights");
    }
}

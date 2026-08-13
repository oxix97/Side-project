package org.stockwellness.application.service.insight;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.stockwellness.adapter.out.persistence.insight.MarketWeather;
import org.stockwellness.adapter.out.persistence.insight.SectorWeather;
import org.stockwellness.adapter.out.persistence.insight.repository.MarketWeatherRepository;
import org.stockwellness.adapter.out.persistence.insight.repository.SectorWeatherRepository;
import org.stockwellness.application.port.out.external.SearchApiPort;
import org.stockwellness.application.port.out.messaging.MarketScoreCalculatedEvent;
import org.stockwellness.application.port.out.sector.WeatherInsightPort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WeatherInsightServiceTest {

    @Mock
    private SearchApiPort searchApiPort;
    @Mock
    private WeatherInsightPort weatherInsightPort;
    @Mock
    private MarketWeatherRepository marketWeatherRepository;
    @Mock
    private SectorWeatherRepository sectorWeatherRepository;

    private WeatherInsightService service;

    @BeforeEach
    void setUp() {
        service = new WeatherInsightService(
                searchApiPort,
                weatherInsightPort,
                marketWeatherRepository,
                sectorWeatherRepository
        );
    }

    @Test
    @DisplayName("같은 시장·기준일 이벤트를 재처리하면 기존 시장 및 섹터 행을 갱신한다")
    void generateInsights_UpsertsByEventIdentity() {
        LocalDate baseDate = LocalDate.of(2026, 8, 12);
        MarketWeather existingMarket = MarketWeather.builder()
                .baseDate(baseDate)
                .marketType("KOSPI")
                .weatherScore(40)
                .weatherState("CLOUDY")
                .aiSummary("기존 해설")
                .topSectors(List.of())
                .bottomSectors(List.of())
                .build();
        SectorWeather existingSector = SectorWeather.builder()
                .baseDate(baseDate)
                .sectorCode("001")
                .weatherScore(40)
                .weatherState("CLOUDY")
                .aiTitle("기존 제목")
                .aiInsight("기존 인사이트")
                .build();
        when(marketWeatherRepository.findByBaseDateAndMarketType(baseDate, "KOSPI"))
                .thenReturn(Optional.of(existingMarket));
        when(sectorWeatherRepository.findByBaseDateAndSectorCode(baseDate, "001"))
                .thenReturn(Optional.of(existingSector));
        when(searchApiPort.searchFinancialNews(any())).thenReturn("");
        when(weatherInsightPort.generateMarketWeatherSummary(80, "KOSPI", ""))
                .thenReturn("새 시장 해설");
        when(weatherInsightPort.generateSectorWeatherInsight("전기전자", 90, ""))
                .thenReturn(new WeatherInsightPort.SectorWeatherInsight("새 제목", "새 인사이트"));
        MarketScoreCalculatedEvent event = new MarketScoreCalculatedEvent(
                baseDate,
                "KOSPI",
                80,
                List.of(new MarketScoreCalculatedEvent.SectorScore("001", "전기전자", 90))
        );

        service.generateInsights(event);

        assertThat(existingMarket.getWeatherScore()).isEqualTo(80);
        assertThat(existingMarket.getAiSummary()).isEqualTo("새 시장 해설");
        assertThat(existingSector.getWeatherScore()).isEqualTo(90);
        assertThat(existingSector.getAiTitle()).isEqualTo("새 제목");
        assertThat(existingSector.getAiInsight()).isEqualTo("새 인사이트");
    }
}

package org.stockwellness.application.service.insight;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.stockwellness.adapter.out.external.ai.OpenAiAdapter;
import org.stockwellness.adapter.out.persistence.insight.MarketWeather;
import org.stockwellness.adapter.out.persistence.insight.SectorWeather;
import org.stockwellness.adapter.out.persistence.insight.repository.MarketWeatherRepository;
import org.stockwellness.adapter.out.persistence.insight.repository.SectorWeatherRepository;
import org.stockwellness.application.port.out.external.SearchApiPort;
import org.stockwellness.application.port.out.messaging.MarketScoreCalculatedEvent;
import org.stockwellness.application.port.out.sector.WeatherInsightPort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class WeatherInsightServiceTransactionTest {

    private static final LocalDate PREVIOUS_DATE = LocalDate.of(2026, 8, 13);
    private static final LocalDate FAILED_DATE = LocalDate.of(2026, 8, 14);

    @Autowired
    private WeatherInsightService service;

    @Autowired
    private MarketWeatherRepository marketWeatherRepository;

    @Autowired
    private SectorWeatherRepository sectorWeatherRepository;

    @MockitoBean
    private SearchApiPort searchApiPort;

    @MockitoBean
    private OpenAiAdapter openAiAdapter;

    @BeforeEach
    void setUp() {
        sectorWeatherRepository.deleteAll();
        marketWeatherRepository.deleteAll();
        marketWeatherRepository.saveAndFlush(MarketWeather.builder()
                .baseDate(PREVIOUS_DATE)
                .marketType("KOSPI")
                .weatherScore(65)
                .weatherState("맑음")
                .aiSummary("이전 정상 시장 해설")
                .topSectors(List.of())
                .bottomSectors(List.of())
                .build());
        sectorWeatherRepository.saveAndFlush(SectorWeather.builder()
                .baseDate(PREVIOUS_DATE)
                .sectorCode("001")
                .weatherScore(70)
                .weatherState("맑음")
                .aiTitle("이전 정상 제목")
                .aiInsight("이전 정상 섹터 해설")
                .build());
    }

    @Test
    void sectorAiFailureRollsBackCurrentDateAndPreservesPreviousEod() {
        when(searchApiPort.searchFinancialNews(anyString())).thenReturn("");
        when(openAiAdapter.generateMarketWeatherSummary(70, "KOSPI", ""))
                .thenReturn("당일 시장 해설");
        when(openAiAdapter.generateSectorWeatherInsight("전기전자", 90, ""))
                .thenReturn(new WeatherInsightPort.SectorWeatherInsight("당일 제목", "당일 해설"));
        when(openAiAdapter.generateSectorWeatherInsight("건설", 80, ""))
                .thenThrow(new IllegalStateException("provider unavailable"));
        MarketScoreCalculatedEvent event = new MarketScoreCalculatedEvent(
                FAILED_DATE,
                "KOSPI",
                70,
                List.of(
                        new MarketScoreCalculatedEvent.SectorScore("001", "전기전자", 90),
                        new MarketScoreCalculatedEvent.SectorScore("002", "건설", 80)
                )
        );

        assertThatThrownBy(() -> service.generateInsights(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("provider unavailable");

        assertThat(marketWeatherRepository.findByBaseDateAndMarketType(FAILED_DATE, "KOSPI")).isEmpty();
        assertThat(sectorWeatherRepository.findAllByBaseDate(FAILED_DATE)).isEmpty();
        assertThat(marketWeatherRepository.findByBaseDateAndMarketType(PREVIOUS_DATE, "KOSPI"))
                .get()
                .extracting(MarketWeather::getAiSummary)
                .isEqualTo("이전 정상 시장 해설");
        assertThat(sectorWeatherRepository.findByBaseDateAndSectorCode(PREVIOUS_DATE, "001"))
                .get()
                .extracting(SectorWeather::getAiInsight)
                .isEqualTo("이전 정상 섹터 해설");
    }

    @Test
    void successfulEventReprocessingKeepsOneRowPerNaturalKey() {
        when(searchApiPort.searchFinancialNews(anyString())).thenReturn("");
        when(openAiAdapter.generateMarketWeatherSummary(70, "KOSPI", ""))
                .thenReturn("당일 시장 해설");
        when(openAiAdapter.generateSectorWeatherInsight("전기전자", 90, ""))
                .thenReturn(new WeatherInsightPort.SectorWeatherInsight("당일 제목", "당일 해설"));
        MarketScoreCalculatedEvent event = new MarketScoreCalculatedEvent(
                FAILED_DATE,
                "KOSPI",
                70,
                List.of(new MarketScoreCalculatedEvent.SectorScore("001", "전기전자", 90))
        );

        service.generateInsights(event);
        service.generateInsights(event);

        assertThat(marketWeatherRepository.count()).isEqualTo(2);
        assertThat(sectorWeatherRepository.count()).isEqualTo(2);
        assertThat(marketWeatherRepository.findByBaseDateAndMarketType(FAILED_DATE, "KOSPI")).isPresent();
        assertThat(sectorWeatherRepository.findAllByBaseDate(FAILED_DATE))
                .singleElement()
                .extracting(SectorWeather::getSectorCode)
                .isEqualTo("001");
    }
}

package org.stockwellness.adapter.batch.insight;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.item.Chunk;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.stockwellness.adapter.out.persistence.insight.MarketWeather;
import org.stockwellness.adapter.out.persistence.insight.SectorIndicator;
import org.stockwellness.adapter.out.persistence.insight.SectorWeather;
import org.stockwellness.adapter.out.persistence.insight.repository.MarketWeatherRepository;
import org.stockwellness.adapter.out.persistence.insight.repository.SectorIndicatorRepository;
import org.stockwellness.adapter.out.persistence.insight.repository.SectorWeatherRepository;
import org.stockwellness.adapter.out.persistence.stock.repository.SectorInsightRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketWeatherBackfillConfigTest {

    @Mock
    private JobRepository jobRepository;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private EntityManagerFactory entityManagerFactory;
    @Mock
    private SectorIndicatorRepository sectorIndicatorRepository;
    @Mock
    private SectorWeatherRepository sectorWeatherRepository;
    @Mock
    private MarketWeatherRepository marketWeatherRepository;
    @Mock
    private SectorInsightRepository sectorInsightRepository;

    @InjectMocks
    private MarketWeatherBackfillConfig config;

    @Test
    @DisplayName("시작일이 없으면 최근 252거래일 중 가장 오래된 기준일을 사용한다")
    void resolveStartDate_UsesOldestDateInRecentTradingWindow() {
        LocalDate oldest = LocalDate.of(2025, 8, 1);
        when(sectorInsightRepository.findRecentDistinctBaseDates(PageRequest.of(0, 252)))
                .thenReturn(List.of(LocalDate.of(2026, 8, 13), oldest));

        LocalDate resolved = config.resolveStartDate(null);

        assertThat(resolved).isEqualTo(oldest);
    }

    @Test
    @DisplayName("명시한 시작일은 데이터 기반 기본 창보다 우선한다")
    void resolveStartDate_PrefersRequestedDate() {
        LocalDate resolved = config.resolveStartDate("2026-01-05");

        assertThat(resolved).isEqualTo(LocalDate.of(2026, 1, 5));
    }

    @Test
    @DisplayName("지표 소급 배치를 재실행하면 같은 기준일과 섹터의 기존 행을 갱신한다")
    void backfillWriter_UpdatesExistingIndicator() throws Exception {
        LocalDate baseDate = LocalDate.of(2026, 8, 12);
        SectorIndicator existing = SectorIndicator.builder()
                .baseDate(baseDate)
                .sectorCode("001")
                .ma20Disparity(BigDecimal.valueOf(95))
                .adr(BigDecimal.valueOf(90))
                .rsi14(BigDecimal.valueOf(40))
                .build();
        SectorIndicator recalculated = SectorIndicator.builder()
                .baseDate(baseDate)
                .sectorCode("001")
                .ma20Disparity(BigDecimal.valueOf(105))
                .adr(BigDecimal.valueOf(110))
                .rsi14(BigDecimal.valueOf(60))
                .isOverheated(true)
                .build();
        when(sectorIndicatorRepository.findByBaseDateAndSectorCode(baseDate, "001"))
                .thenReturn(Optional.of(existing));

        config.backfillWriter().write(new Chunk<>(List.of(recalculated)));

        assertThat(existing.getMa20Disparity()).isEqualByComparingTo("105");
        assertThat(existing.getAdr()).isEqualByComparingTo("110");
        assertThat(existing.getRsi14()).isEqualByComparingTo("60");
        assertThat(existing.isOverheated()).isTrue();
    }

    @Test
    @DisplayName("섹터 날씨 소급 배치를 재실행하면 같은 기준일과 섹터의 기존 행을 갱신한다")
    void sectorWeatherWriter_UpdatesExistingWeather() throws Exception {
        LocalDate baseDate = LocalDate.of(2026, 8, 12);
        SectorWeather existing = SectorWeather.builder()
                .baseDate(baseDate)
                .sectorCode("001")
                .weatherScore(40)
                .weatherState("CLOUDY")
                .aiTitle("기존 제목")
                .aiInsight("기존 해설")
                .build();
        SectorWeather recalculated = SectorWeather.builder()
                .baseDate(baseDate)
                .sectorCode("001")
                .weatherScore(75)
                .weatherState("SUNNY")
                .build();
        when(sectorWeatherRepository.findByBaseDateAndSectorCode(baseDate, "001"))
                .thenReturn(Optional.of(existing));

        config.sectorWeatherWriter().write(new Chunk<>(List.of(recalculated)));

        assertThat(existing.getWeatherScore()).isEqualTo(75);
        assertThat(existing.getWeatherState()).isEqualTo("SUNNY");
        assertThat(existing.getAiTitle()).isEqualTo("기존 제목");
        assertThat(existing.getAiInsight()).isEqualTo("기존 해설");
    }

    @Test
    @DisplayName("시장 날씨 소급 배치를 재실행하면 AI 해설을 보존하고 계산값을 갱신한다")
    void saveMarketWeather_UpdatesCalculationAndPreservesInsight() {
        LocalDate baseDate = LocalDate.of(2026, 8, 12);
        MarketWeather existing = MarketWeather.builder()
                .baseDate(baseDate)
                .marketType("KOSPI")
                .weatherScore(40)
                .weatherState("CLOUDY")
                .aiSummary("기존 AI 해설")
                .topSectors(List.of())
                .bottomSectors(List.of())
                .build();
        MarketWeather recalculated = MarketWeather.builder()
                .baseDate(baseDate)
                .marketType("KOSPI")
                .weatherScore(75)
                .weatherState("SUNNY")
                .topSectors(List.of(new MarketWeather.SectorSummary("001", "전기전자", 90, "☀️")))
                .bottomSectors(List.of(new MarketWeather.SectorSummary("002", "건설", 20, "🌧️")))
                .build();
        when(marketWeatherRepository.findByBaseDateAndMarketType(baseDate, "KOSPI"))
                .thenReturn(Optional.of(existing));

        config.saveMarketWeather(recalculated);

        assertThat(existing.getWeatherScore()).isEqualTo(75);
        assertThat(existing.getWeatherState()).isEqualTo("SUNNY");
        assertThat(existing.getAiSummary()).isEqualTo("기존 AI 해설");
        assertThat(existing.getTopSectors()).extracting(MarketWeather.SectorSummary::code)
                .containsExactly("001");
        assertThat(existing.getBottomSectors()).extracting(MarketWeather.SectorSummary::code)
                .containsExactly("002");
    }
}

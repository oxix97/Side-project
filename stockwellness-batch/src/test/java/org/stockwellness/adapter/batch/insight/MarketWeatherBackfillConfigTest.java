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
import org.mockito.ArgumentCaptor;
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
import org.stockwellness.adapter.out.persistence.stock.repository.SectorDailyDetailRepository;
import org.stockwellness.adapter.out.persistence.stock.repository.SectorInsightRepository;
import org.stockwellness.application.port.out.messaging.MarketScoreCalculatedEvent;
import org.stockwellness.application.port.out.stock.SectorDailyDetailSnapshot;
import org.stockwellness.application.service.insight.MarketScoreCalculationService;
import org.stockwellness.domain.stock.MarketType;
import org.stockwellness.domain.stock.insight.SectorDailyDetail;
import org.stockwellness.domain.stock.insight.SectorIndicators;
import org.stockwellness.domain.stock.insight.SectorInsight;
import org.stockwellness.domain.stock.insight.SectorTechnicalIndicators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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
    private SectorDailyDetailRepository sectorDailyDetailRepository;
    @Mock
    private SectorInsightRepository sectorInsightRepository;
    @Mock
    private MarketScoreCalculationService marketScoreCalculationService;

    @InjectMocks
    private MarketWeatherBackfillConfig config;

    @Test
    @DisplayName("원천 거래일이 252개보다 적으면 소급 배치를 거부한다")
    void resolveTradingDates_RejectsIncompleteSourceWindow() {
        LocalDate latest = LocalDate.of(2026, 8, 12);
        List<LocalDate> incompleteDates = java.util.stream.IntStream.range(0, 251)
                .mapToObj(latest::minusDays)
                .toList();
        when(sectorInsightRepository.findRecentDistinctBaseDates(PageRequest.of(0, 252)))
                .thenReturn(incompleteDates);

        assertThatThrownBy(() -> config.resolveTradingDates())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("252")
                .hasMessageContaining("251");
    }

    @Test
    @DisplayName("소급 배치는 공통 계산 결과를 시장별 정량 날씨로 저장한다")
    void backfillWeather_PersistsCalculatedMarketsWithoutAiCalls() {
        LocalDate baseDate = LocalDate.of(2026, 8, 12);
        when(marketScoreCalculationService.calculate(baseDate)).thenReturn(List.of(
                event(baseDate, "KOSPI", 80, "001", "전기전자", 90),
                event(baseDate, "KOSDAQ", 40, "101", "반도체", 30)
        ));
        when(sectorWeatherRepository.findByBaseDateAndSectorCode(baseDate, "001"))
                .thenReturn(Optional.empty());
        when(sectorWeatherRepository.findByBaseDateAndSectorCode(baseDate, "101"))
                .thenReturn(Optional.empty());
        when(marketWeatherRepository.findByBaseDateAndMarketType(baseDate, "KOSPI"))
                .thenReturn(Optional.empty());
        when(marketWeatherRepository.findByBaseDateAndMarketType(baseDate, "KOSDAQ"))
                .thenReturn(Optional.empty());

        config.backfillWeather(baseDate);

        ArgumentCaptor<SectorWeather> sectorCaptor = ArgumentCaptor.forClass(SectorWeather.class);
        verify(sectorWeatherRepository, times(2)).save(sectorCaptor.capture());
        assertThat(sectorCaptor.getAllValues())
                .extracting(SectorWeather::getSectorCode, SectorWeather::getWeatherScore)
                .containsExactly(tuple("001", 90), tuple("101", 30));

        ArgumentCaptor<MarketWeather> marketCaptor = ArgumentCaptor.forClass(MarketWeather.class);
        verify(marketWeatherRepository, times(2)).save(marketCaptor.capture());
        assertThat(marketCaptor.getAllValues())
                .extracting(MarketWeather::getMarketType, MarketWeather::getWeatherScore)
                .containsExactly(tuple("KOSPI", 80), tuple("KOSDAQ", 40));
    }

    @Test
    @DisplayName("특정 거래일의 KOSDAQ 계산 결과가 없으면 소급 배치를 실패시킨다")
    void backfillWeather_RejectsMissingRequiredMarket() {
        LocalDate baseDate = LocalDate.of(2026, 8, 12);
        when(marketScoreCalculationService.calculate(baseDate)).thenReturn(List.of(
                event(baseDate, "KOSPI", 80, "001", "전기전자", 90)
        ));

        assertThatThrownBy(() -> config.backfillWeather(baseDate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("KOSDAQ")
                .hasMessageContaining(baseDate.toString());
        verifyNoInteractions(sectorWeatherRepository, marketWeatherRepository);
    }

    @Test
    @DisplayName("원천 가격·MA20·ADR·RSI 중 하나라도 누락되면 지표 백필을 실패시킨다")
    void backfillProcessor_RejectsMissingRequiredMetric() {
        LocalDate baseDate = LocalDate.of(2026, 8, 12);
        SectorInsight incomplete = SectorInsight.of(
                "전기전자",
                "001",
                MarketType.KOSPI,
                baseDate,
                SectorIndicators.of(BigDecimal.ONE, BigDecimal.ZERO, null, 0L, 0L, 0, 0),
                null,
                false
        );
        when(sectorDailyDetailRepository.findBySectorCodeAndBaseDate("001", baseDate))
                .thenReturn(Optional.of(detail("001", baseDate, 5, 0, 5, 0)));

        assertThatThrownBy(() -> config.backfillProcessor().process(incomplete))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("001")
                .hasMessageContaining(baseDate.toString());
    }

    @Test
    @DisplayName("지표 백필은 과거 SectorInsight의 placeholder 대신 원천 상세 ADR을 사용한다")
    void backfillProcessor_UsesAdvanceDeclineRatioFromDailyDetail() throws Exception {
        LocalDate baseDate = LocalDate.of(2026, 8, 12);
        SectorInsight placeholder = SectorInsight.of(
                "전기전자",
                "001",
                MarketType.KOSPI,
                baseDate,
                SectorIndicators.of(BigDecimal.valueOf(100), BigDecimal.ZERO, BigDecimal.ZERO, 0L, 0L, 0, 0),
                SectorTechnicalIndicators.of(
                        BigDecimal.valueOf(100),
                        BigDecimal.valueOf(100),
                        BigDecimal.valueOf(50),
                        null,
                        null,
                        null,
                        false,
                        false
                ),
                false
        );
        when(sectorDailyDetailRepository.findBySectorCodeAndBaseDate("001", baseDate))
                .thenReturn(Optional.of(detail("001", baseDate, 4, 1, 9, 1)));

        SectorIndicator result = config.backfillProcessor().process(placeholder);

        assertThat(result.getAdr()).isEqualByComparingTo("50.0000");
    }

    @Test
    @DisplayName("과거 ADR 원천 상세가 없으면 지표 백필을 실패시킨다")
    void backfillProcessor_RejectsMissingDailyDetail() {
        LocalDate baseDate = LocalDate.of(2026, 8, 12);
        SectorInsight insight = SectorInsight.of(
                "전기전자",
                "001",
                MarketType.KOSPI,
                baseDate,
                SectorIndicators.of(BigDecimal.valueOf(100), BigDecimal.ZERO, BigDecimal.ZERO, 0L, 0L, 0, 0),
                null,
                false
        );

        assertThatThrownBy(() -> config.backfillProcessor().process(insight))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADR 원천 상세")
                .hasMessageContaining(baseDate.toString())
                .hasMessageContaining("001");
    }

    @Test
    @DisplayName("최근 252거래일은 가장 오래된 원천일에서 최신 원천일 순서로 반환한다")
    void resolveTradingDates_ReturnsAscendingSourceDates() {
        LocalDate latest = LocalDate.of(2026, 8, 12);
        List<LocalDate> descendingDates = java.util.stream.IntStream.range(0, 252)
                .mapToObj(latest::minusDays)
                .toList();
        when(sectorInsightRepository.findRecentDistinctBaseDates(PageRequest.of(0, 252)))
                .thenReturn(descendingDates);

        List<LocalDate> resolved = config.resolveTradingDates();

        assertThat(resolved).hasSize(252);
        assertThat(resolved.getFirst()).isEqualTo(latest.minusDays(251));
        assertThat(resolved.getLast()).isEqualTo(latest);
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

        config.saveSectorWeather(recalculated);

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

    private MarketScoreCalculatedEvent event(
            LocalDate baseDate,
            String marketType,
            int overallScore,
            String sectorCode,
            String sectorName,
            int sectorScore
    ) {
        return new MarketScoreCalculatedEvent(
                baseDate,
                marketType,
                overallScore,
                List.of(new MarketScoreCalculatedEvent.SectorScore(sectorCode, sectorName, sectorScore))
        );
    }

    private SectorDailyDetail detail(
            String sectorCode,
            LocalDate baseDate,
            int rising,
            int upperLimit,
            int falling,
            int lowerLimit
    ) {
        return SectorDailyDetail.of(
                sectorCode,
                "테스트 섹터",
                new SectorDailyDetailSnapshot(
                        sectorCode,
                        baseDate,
                        BigDecimal.valueOf(100),
                        BigDecimal.ZERO,
                        "3",
                        BigDecimal.ZERO,
                        0L,
                        0L,
                        0L,
                        0L,
                        BigDecimal.valueOf(100),
                        BigDecimal.valueOf(100),
                        BigDecimal.valueOf(100),
                        rising,
                        upperLimit,
                        0,
                        falling,
                        lowerLimit,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        0L,
                        0L,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        0L,
                        0L,
                        0L
                )
        );
    }
}

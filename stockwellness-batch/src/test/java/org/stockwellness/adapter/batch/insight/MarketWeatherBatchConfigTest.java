package org.stockwellness.adapter.batch.insight;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.item.Chunk;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.stockwellness.adapter.out.persistence.insight.SectorIndicator;
import org.stockwellness.adapter.out.persistence.insight.repository.SectorIndicatorRepository;
import org.stockwellness.adapter.out.persistence.stock.repository.SectorInsightRepository;
import org.stockwellness.application.port.out.messaging.MarketScoreCalculatedEvent;
import org.stockwellness.application.service.insight.MarketScoreCalculationService;
import org.stockwellness.application.service.insight.WeatherScoreCalculator;
import org.stockwellness.domain.stock.MarketType;
import org.stockwellness.domain.stock.insight.SectorIndicators;
import org.stockwellness.domain.stock.insight.SectorInsight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketWeatherBatchConfigTest {

    @Mock
    private JobRepository jobRepository;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private EntityManagerFactory entityManagerFactory;
    @Mock
    private SectorIndicatorRepository sectorIndicatorRepository;
    @Mock
    private SectorInsightRepository sectorInsightRepository;
    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private MarketWeatherBatchConfig config;

    @BeforeEach
    void setUp() {
        config = new MarketWeatherBatchConfig(
                jobRepository,
                transactionManager,
                entityManagerFactory,
                sectorIndicatorRepository,
                new MarketScoreCalculationService(
                        sectorIndicatorRepository,
                        sectorInsightRepository,
                        new WeatherScoreCalculator()
                ),
                kafkaTemplate
        );
    }

    @Test
    @DisplayName("대상일의 실제 섹터 지표로 시장별 점수 이벤트를 만든다")
    void buildMarketScoreEvents_UsesCalculatedSectorScores() {
        LocalDate targetDate = LocalDate.of(2026, 8, 12);
        SectorInsight electronics = insight("001", "전기전자", MarketType.KOSPI, targetDate);
        SectorInsight finance = insight("002", "금융", MarketType.KOSPI, targetDate);
        SectorIndicator electronicsCurrent = indicator("001", targetDate, 50);
        SectorIndicator financeCurrent = indicator("002", targetDate, 30);
        when(sectorInsightRepository.findAllByBaseDate(targetDate))
                .thenReturn(List.of(electronics, finance));
        when(sectorIndicatorRepository.findByBaseDateAndSectorCode(targetDate, "001"))
                .thenReturn(Optional.of(electronicsCurrent));
        when(sectorIndicatorRepository.findByBaseDateAndSectorCode(targetDate, "002"))
                .thenReturn(Optional.of(financeCurrent));
        when(sectorIndicatorRepository.findAllBySectorCodeAndBaseDateLessThanEqualOrderByBaseDateDesc(
                "001", targetDate, PageRequest.of(0, 252)))
                .thenReturn(history("001", targetDate, 10, 20, 30, 40, 50));
        when(sectorIndicatorRepository.findAllBySectorCodeAndBaseDateLessThanEqualOrderByBaseDateDesc(
                "002", targetDate, PageRequest.of(0, 252)))
                .thenReturn(history("002", targetDate, 10, 20, 30, 40, 50));

        List<MarketScoreCalculatedEvent> events = config.buildMarketScoreEvents(targetDate);

        assertThat(events).hasSize(1);
        MarketScoreCalculatedEvent event = events.getFirst();
        assertThat(event.baseDate()).isEqualTo(targetDate);
        assertThat(event.marketType()).isEqualTo("KOSPI");
        assertThat(event.sectorScores())
                .extracting(MarketScoreCalculatedEvent.SectorScore::name)
                .containsExactly("전기전자", "금융");
        assertThat(event.sectorScores())
                .extracting(MarketScoreCalculatedEvent.SectorScore::score)
                .containsExactly(100, 50);
        assertThat(event.overallScore()).isEqualTo(75);
    }

    @Test
    @DisplayName("대상 섹터의 당일 지표가 누락되면 불완전한 시장 점수를 발행하지 않는다")
    void buildMarketScoreEvents_RejectsMissingCurrentIndicator() {
        LocalDate targetDate = LocalDate.of(2026, 8, 12);
        SectorInsight electronics = insight("001", "전기전자", MarketType.KOSPI, targetDate);
        SectorInsight finance = insight("002", "금융", MarketType.KOSPI, targetDate);
        when(sectorInsightRepository.findAllByBaseDate(targetDate))
                .thenReturn(List.of(electronics, finance));
        when(sectorIndicatorRepository.findByBaseDateAndSectorCode(targetDate, "001"))
                .thenReturn(Optional.of(indicator("001", targetDate, 50)));
        when(sectorIndicatorRepository.findAllBySectorCodeAndBaseDateLessThanEqualOrderByBaseDateDesc(
                "001", targetDate, PageRequest.of(0, 252)))
                .thenReturn(history("001", targetDate, 10, 20, 30, 40, 50));
        when(sectorIndicatorRepository.findByBaseDateAndSectorCode(targetDate, "002"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> config.buildMarketScoreEvents(targetDate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("002");
    }

    @Test
    @DisplayName("일일 배치도 원천 가격·MA20·ADR·RSI 누락을 지표로 변환하지 않는다")
    void sectorIndicatorProcessor_RejectsMissingRequiredMetric() {
        LocalDate targetDate = LocalDate.of(2026, 8, 12);
        SectorInsight incomplete = SectorInsight.of(
                "전기전자",
                "001",
                MarketType.KOSPI,
                targetDate,
                SectorIndicators.of(BigDecimal.ONE, BigDecimal.ZERO, null, 0L, 0L, 0, 0),
                null,
                false
        );

        assertThatThrownBy(() -> config.sectorIndicatorProcessor().process(incomplete))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("001")
                .hasMessageContaining(targetDate.toString());
    }

    @Test
    @DisplayName("일일 지표 배치를 재실행하면 같은 기준일과 섹터의 기존 행을 갱신한다")
    void sectorIndicatorWriter_UpdatesExistingIndicator() throws Exception {
        LocalDate targetDate = LocalDate.of(2026, 8, 12);
        SectorIndicator existing = indicator("001", targetDate, 20);
        SectorIndicator recalculated = indicator("001", targetDate, 80);
        when(sectorIndicatorRepository.findByBaseDateAndSectorCode(targetDate, "001"))
                .thenReturn(Optional.of(existing));

        config.sectorIndicatorWriter().write(new Chunk<>(List.of(recalculated)));

        assertThat(existing.getMa20Disparity()).isEqualByComparingTo("80");
        assertThat(existing.getAdr()).isEqualByComparingTo("80");
        assertThat(existing.getRsi14()).isEqualByComparingTo("80");
    }

    private SectorInsight insight(String code, String name, MarketType marketType, LocalDate baseDate) {
        return SectorInsight.of(
                name,
                code,
                marketType,
                baseDate,
                SectorIndicators.of(BigDecimal.ONE, BigDecimal.ZERO, 0L, 0L, 0, 0),
                null,
                false
        );
    }

    private List<SectorIndicator> history(String code, LocalDate targetDate, int... values) {
        return java.util.stream.IntStream.range(0, values.length)
                .mapToObj(index -> indicator(code, targetDate.minusDays(values.length - 1L - index), values[index]))
                .sorted((left, right) -> right.getBaseDate().compareTo(left.getBaseDate()))
                .toList();
    }

    private SectorIndicator indicator(String code, LocalDate baseDate, int value) {
        return SectorIndicator.builder()
                .baseDate(baseDate)
                .sectorCode(code)
                .ma20Disparity(BigDecimal.valueOf(value))
                .adr(BigDecimal.valueOf(value))
                .rsi14(BigDecimal.valueOf(value))
                .build();
    }
}

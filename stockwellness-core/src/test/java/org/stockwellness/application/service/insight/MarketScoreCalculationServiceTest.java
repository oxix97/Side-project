package org.stockwellness.application.service.insight;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.stockwellness.adapter.out.persistence.insight.SectorIndicator;
import org.stockwellness.adapter.out.persistence.insight.repository.SectorIndicatorRepository;
import org.stockwellness.adapter.out.persistence.stock.repository.SectorInsightRepository;
import org.stockwellness.application.port.out.messaging.MarketScoreCalculatedEvent;
import org.stockwellness.domain.stock.MarketType;
import org.stockwellness.domain.stock.insight.SectorIndicators;
import org.stockwellness.domain.stock.insight.SectorInsight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketScoreCalculationServiceTest {

    @Mock
    private SectorIndicatorRepository sectorIndicatorRepository;
    @Mock
    private SectorInsightRepository sectorInsightRepository;

    private MarketScoreCalculationService service;

    @BeforeEach
    void setUp() {
        service = new MarketScoreCalculationService(
                sectorIndicatorRepository,
                sectorInsightRepository,
                new WeatherScoreCalculator()
        );
    }

    @Test
    @DisplayName("대상일 섹터를 시장별로 나누고 최근 252거래일 이력으로 점수를 계산한다")
    void calculate_GroupsByMarketAndUsesRollingWindow() {
        LocalDate targetDate = LocalDate.of(2026, 8, 12);
        SectorInsight kospi = insight("001", "전기전자", MarketType.KOSPI, targetDate);
        SectorInsight kosdaq = insight("101", "반도체", MarketType.KOSDAQ, targetDate);
        SectorIndicator kospiCurrent = indicator("001", targetDate, 50);
        SectorIndicator kosdaqCurrent = indicator("101", targetDate, 30);
        when(sectorInsightRepository.findAllByBaseDate(targetDate)).thenReturn(List.of(kosdaq, kospi));
        when(sectorIndicatorRepository.findByBaseDateAndSectorCode(targetDate, "001"))
                .thenReturn(Optional.of(kospiCurrent));
        when(sectorIndicatorRepository.findByBaseDateAndSectorCode(targetDate, "101"))
                .thenReturn(Optional.of(kosdaqCurrent));
        when(sectorIndicatorRepository.findAllBySectorCodeAndBaseDateLessThanEqualOrderByBaseDateDesc(
                "001", targetDate, PageRequest.of(0, 252)))
                .thenReturn(history("001", targetDate));
        when(sectorIndicatorRepository.findAllBySectorCodeAndBaseDateLessThanEqualOrderByBaseDateDesc(
                "101", targetDate, PageRequest.of(0, 252)))
                .thenReturn(history("101", targetDate));

        List<MarketScoreCalculatedEvent> events = service.calculate(targetDate);

        assertThat(events).extracting(MarketScoreCalculatedEvent::marketType)
                .containsExactly("KOSPI", "KOSDAQ");
        assertThat(events).extracting(MarketScoreCalculatedEvent::overallScore)
                .containsExactly(100, 50);
    }

    @Test
    @DisplayName("대상일 섹터 지표가 누락되면 계산을 실패시킨다")
    void calculate_RejectsMissingCurrentIndicator() {
        LocalDate targetDate = LocalDate.of(2026, 8, 12);
        when(sectorInsightRepository.findAllByBaseDate(targetDate))
                .thenReturn(List.of(insight("001", "전기전자", MarketType.KOSPI, targetDate)));
        when(sectorIndicatorRepository.findByBaseDateAndSectorCode(targetDate, "001"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.calculate(targetDate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("001");
    }

    @Test
    @DisplayName("저장된 당일 지표의 필수 정량값이 누락되면 계산을 실패시킨다")
    void calculate_RejectsIncompleteCurrentIndicator() {
        LocalDate targetDate = LocalDate.of(2026, 8, 12);
        SectorIndicator incomplete = SectorIndicator.builder()
                .baseDate(targetDate)
                .sectorCode("001")
                .ma20Disparity(BigDecimal.valueOf(100))
                .adr(BigDecimal.valueOf(50))
                .rsi14(null)
                .build();
        when(sectorInsightRepository.findAllByBaseDate(targetDate))
                .thenReturn(List.of(insight("001", "전기전자", MarketType.KOSPI, targetDate)));
        when(sectorIndicatorRepository.findByBaseDateAndSectorCode(targetDate, "001"))
                .thenReturn(Optional.of(incomplete));

        assertThatThrownBy(() -> service.calculate(targetDate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("001")
                .hasMessageContaining(targetDate.toString());
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

    private List<SectorIndicator> history(String code, LocalDate baseDate) {
        return List.of(
                indicator(code, baseDate.minusDays(4), 10),
                indicator(code, baseDate.minusDays(3), 20),
                indicator(code, baseDate.minusDays(2), 30),
                indicator(code, baseDate.minusDays(1), 40),
                indicator(code, baseDate, 50)
        );
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

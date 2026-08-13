package org.stockwellness.application.service.insight;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stockwellness.adapter.out.persistence.insight.SectorIndicator;

import static org.assertj.core.api.Assertions.assertThat;

class WeatherScoreCalculatorTest {

    private final WeatherScoreCalculator calculator = new WeatherScoreCalculator();

    @Test
    @DisplayName("섹터 점수는 추세 40%, 시장 폭 40%, 모멘텀 20% 백분위를 합산한다")
    void calculate_UsesRollingPercentilesAndPolicyWeights() {
        LocalDate baseDate = LocalDate.of(2026, 8, 12);
        List<SectorIndicator> history = List.of(
                indicator(baseDate.minusDays(4), 10, 10, 10),
                indicator(baseDate.minusDays(3), 20, 20, 20),
                indicator(baseDate.minusDays(2), 30, 30, 30),
                indicator(baseDate.minusDays(1), 40, 40, 40),
                indicator(baseDate, 50, 50, 50)
        );
        SectorIndicator current = indicator(baseDate, 50, 30, 10);

        int score = calculator.calculate(current, history);

        assertThat(score).isEqualTo(60);
    }

    private SectorIndicator indicator(LocalDate baseDate, int trend, int breadth, int momentum) {
        return SectorIndicator.builder()
                .baseDate(baseDate)
                .sectorCode("001")
                .ma20Disparity(BigDecimal.valueOf(trend))
                .adr(BigDecimal.valueOf(breadth))
                .rsi14(BigDecimal.valueOf(momentum))
                .build();
    }
}

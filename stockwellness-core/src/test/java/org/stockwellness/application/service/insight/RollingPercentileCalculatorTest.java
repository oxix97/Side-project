package org.stockwellness.application.service.insight;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.stockwellness.domain.stock.insight.RollingPercentileCalculator;
import static org.assertj.core.api.Assertions.assertThat;

class RollingPercentileCalculatorTest {

    @Test
    @DisplayName("데이터가 적을 때는 50점을 반환한다")
    void shouldReturnNeutralScoreWhenHistoryIsShort() {
        int score = RollingPercentileCalculator.calculate(BigDecimal.valueOf(105), List.of(BigDecimal.valueOf(105)));
        assertThat(score).isEqualTo(50);
    }

    @Test
    @DisplayName("누락값을 제외한 유효 이력이 5개 미만이면 중립 점수를 반환한다")
    void shouldReturnNeutralScoreWhenValidHistoryIsShort() {
        List<BigDecimal> history = Arrays.asList(
                BigDecimal.valueOf(105),
                null,
                null,
                null,
                null
        );

        int score = RollingPercentileCalculator.calculate(BigDecimal.valueOf(105), history);

        assertThat(score).isEqualTo(50);
    }

    @Test
    @DisplayName("중간값일 때는 50점 근처를 반환한다")
    void shouldReturnMedianScore() {
        List<BigDecimal> history = List.of(
                BigDecimal.valueOf(90),
                BigDecimal.valueOf(95),
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(105),
                BigDecimal.valueOf(110)
        );
        int score = RollingPercentileCalculator.calculate(BigDecimal.valueOf(100), history);
        assertThat(score).isBetween(40, 60);
    }

    @Test
    @DisplayName("최고값일 때는 100점을 반환한다")
    void shouldReturnMaxScore() {
        List<BigDecimal> history = List.of(
                BigDecimal.valueOf(90),
                BigDecimal.valueOf(95),
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(105),
                BigDecimal.valueOf(110)
        );
        int score = RollingPercentileCalculator.calculate(BigDecimal.valueOf(110), history);
        assertThat(score).isEqualTo(100);
    }

    @Test
    @DisplayName("최저값일 때는 0점을 반환한다")
    void shouldReturnMinScore() {
        List<BigDecimal> history = List.of(
                BigDecimal.valueOf(90),
                BigDecimal.valueOf(95),
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(105),
                BigDecimal.valueOf(110)
        );
        int score = RollingPercentileCalculator.calculate(BigDecimal.valueOf(90), history);
        assertThat(score).isEqualTo(0);
    }
}

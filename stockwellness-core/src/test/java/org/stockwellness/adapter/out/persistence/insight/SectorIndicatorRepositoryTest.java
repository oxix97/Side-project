package org.stockwellness.adapter.out.persistence.insight;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.stockwellness.adapter.out.persistence.insight.repository.SectorIndicatorRepository;
import org.stockwellness.config.JpaConfig;
import org.stockwellness.config.QueryDslConfig;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "spring.flyway.enabled=false")
@Import({QueryDslConfig.class, JpaConfig.class})
@ActiveProfiles("test")
class SectorIndicatorRepositoryTest {

    @Autowired
    private SectorIndicatorRepository repository;

    @Test
    @DisplayName("롤링 지표 이력은 기준일을 포함한 최근 252거래일만 반환한다")
    void findRecentHistory_LimitsToPolicyWindow() {
        LocalDate firstTradingDate = LocalDate.of(2025, 1, 2);
        List<SectorIndicator> indicators = IntStream.range(0, 253)
                .mapToObj(day -> SectorIndicator.builder()
                        .baseDate(firstTradingDate.plusDays(day))
                        .sectorCode("0001")
                        .ma20Disparity(BigDecimal.valueOf(day))
                        .adr(BigDecimal.valueOf(day))
                        .rsi14(BigDecimal.valueOf(day))
                        .build())
                .toList();
        repository.saveAllAndFlush(indicators);

        List<SectorIndicator> history = repository
                .findAllBySectorCodeAndBaseDateLessThanEqualOrderByBaseDateDesc(
                        "0001",
                        firstTradingDate.plusDays(252),
                        PageRequest.of(0, 252)
                );

        assertThat(history).hasSize(252);
        assertThat(history.getFirst().getBaseDate()).isEqualTo(firstTradingDate.plusDays(252));
        assertThat(history.getLast().getBaseDate()).isEqualTo(firstTradingDate.plusDays(1));
    }
}

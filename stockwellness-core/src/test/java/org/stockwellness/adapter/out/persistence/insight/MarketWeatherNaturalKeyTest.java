package org.stockwellness.adapter.out.persistence.insight;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.stockwellness.adapter.out.persistence.insight.repository.MarketWeatherRepository;
import org.stockwellness.adapter.out.persistence.insight.repository.SectorIndicatorRepository;
import org.stockwellness.adapter.out.persistence.insight.repository.SectorWeatherRepository;
import org.stockwellness.config.JpaConfig;
import org.stockwellness.config.QueryDslConfig;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = "spring.flyway.enabled=false")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QueryDslConfig.class, JpaConfig.class})
@ActiveProfiles("test")
class MarketWeatherNaturalKeyTest {

    private static final LocalDate BASE_DATE = LocalDate.of(2026, 8, 14);

    @Autowired
    private SectorIndicatorRepository sectorIndicatorRepository;

    @Autowired
    private SectorWeatherRepository sectorWeatherRepository;

    @Autowired
    private MarketWeatherRepository marketWeatherRepository;

    @Test
    void sectorIndicatorRejectsDuplicateBaseDateAndSectorCode() {
        sectorIndicatorRepository.saveAndFlush(indicator());

        assertThatThrownBy(() -> sectorIndicatorRepository.saveAndFlush(indicator()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sectorWeatherRejectsDuplicateBaseDateAndSectorCode() {
        sectorWeatherRepository.saveAndFlush(sectorWeather());

        assertThatThrownBy(() -> sectorWeatherRepository.saveAndFlush(sectorWeather()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void marketWeatherRejectsDuplicateBaseDateAndMarketType() {
        marketWeatherRepository.saveAndFlush(marketWeather());

        assertThatThrownBy(() -> marketWeatherRepository.saveAndFlush(marketWeather()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private SectorIndicator indicator() {
        return SectorIndicator.builder()
                .baseDate(BASE_DATE)
                .sectorCode("0001")
                .ma20Disparity(BigDecimal.ONE)
                .rsi14(BigDecimal.TEN)
                .adr(BigDecimal.ONE)
                .build();
    }

    private SectorWeather sectorWeather() {
        return SectorWeather.builder()
                .baseDate(BASE_DATE)
                .sectorCode("0001")
                .weatherScore(50)
                .weatherState("CLOUDY")
                .build();
    }

    private MarketWeather marketWeather() {
        return MarketWeather.builder()
                .baseDate(BASE_DATE)
                .marketType("KOSPI")
                .weatherScore(50)
                .weatherState("CLOUDY")
                .topSectors(List.of())
                .bottomSectors(List.of())
                .build();
    }
}

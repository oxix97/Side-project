package org.stockwellness.adapter.out.persistence.insight.repository;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.stockwellness.adapter.out.persistence.insight.MarketWeather;

public interface MarketWeatherRepository extends JpaRepository<MarketWeather, Long> {
    Optional<MarketWeather> findFirstByMarketTypeOrderByBaseDateDesc(String marketType);

    Optional<MarketWeather> findByBaseDateAndMarketType(LocalDate baseDate, String marketType);
}

package org.stockwellness.adapter.out.persistence.insight.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.stockwellness.adapter.out.persistence.insight.SectorWeather;

public interface SectorWeatherRepository extends JpaRepository<SectorWeather, Long> {
    List<SectorWeather> findAllByBaseDate(LocalDate baseDate);

    Optional<SectorWeather> findByBaseDateAndSectorCode(LocalDate baseDate, String sectorCode);
}

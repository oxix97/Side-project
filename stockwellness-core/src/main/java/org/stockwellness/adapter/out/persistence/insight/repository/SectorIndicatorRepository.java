package org.stockwellness.adapter.out.persistence.insight.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.stockwellness.adapter.out.persistence.insight.SectorIndicator;

public interface SectorIndicatorRepository extends JpaRepository<SectorIndicator, Long> {
    Optional<SectorIndicator> findByBaseDateAndSectorCode(LocalDate baseDate, String sectorCode);

    List<SectorIndicator> findAllBySectorCodeAndBaseDateLessThanEqualOrderByBaseDateDesc(
            String sectorCode,
            LocalDate baseDate,
            Pageable pageable
    );
}

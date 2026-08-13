package org.stockwellness.adapter.out.persistence.stock.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.stockwellness.domain.stock.MarketType;
import org.stockwellness.domain.stock.insight.SectorInsight;

public interface SectorInsightRepository extends JpaRepository<SectorInsight, Long>, SectorInsightRepositoryCustom {
    Optional<SectorInsight> findBySectorCodeAndBaseDate(String sectorCode, LocalDate baseDate);

    Optional<SectorInsight> findFirstBySectorCodeAndBaseDateBeforeOrderByBaseDateDesc(String sectorCode, LocalDate baseDate);

    @Query("""
        SELECT s FROM SectorInsight s 
        WHERE s.baseDate = :date 
          AND (:marketType IS NULL OR s.marketType = :marketType)
        ORDER BY s.indicators.avgFluctuationRate DESC
    """)
    List<SectorInsight> findTopByDate(@Param("date") LocalDate date, @Param("marketType") MarketType marketType, Pageable pageable);

    @Query("""
        SELECT s FROM SectorInsight s 
        WHERE s.baseDate = :date 
          AND (:marketType IS NULL OR s.marketType = :marketType)
        ORDER BY (COALESCE(s.indicators.netForeignBuyAmount, 0) + COALESCE(s.indicators.netInstBuyAmount, 0)) DESC,
                 s.indicators.netForeignBuyAmount DESC,
                 s.indicators.netInstBuyAmount DESC
    """)
    List<SectorInsight> findTopBySupply(@Param("date") LocalDate date, @Param("marketType") MarketType marketType, Pageable pageable);

    List<SectorInsight> findAllByBaseDate(LocalDate date);

    @Query("SELECT s.indicators.sectorIndexCurrentPrice FROM SectorInsight s WHERE s.sectorCode = :sectorCode AND s.baseDate < :date ORDER BY s.baseDate DESC")
    List<BigDecimal> findPastPrices(String sectorCode, LocalDate date, Pageable pageable);

    List<SectorInsight> findBySectorCodeInAndBaseDate(List<String> sectorCodes, LocalDate baseDate);

    List<SectorInsight> findBySectorCodeAndBaseDateLessThanEqualOrderByBaseDateDesc(String sectorCode, LocalDate endDate, Pageable pageable);

    @Query("SELECT MAX(s.baseDate) FROM SectorInsight s")
    Optional<LocalDate> findMaxBaseDate();

    @Query("SELECT DISTINCT s.baseDate FROM SectorInsight s ORDER BY s.baseDate DESC")
    List<LocalDate> findRecentDistinctBaseDates(Pageable pageable);

    Optional<SectorInsight> findFirstBySectorCodeOrderByBaseDateDesc(String sectorCode);
}

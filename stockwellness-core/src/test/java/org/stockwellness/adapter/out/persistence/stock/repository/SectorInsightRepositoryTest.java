package org.stockwellness.adapter.out.persistence.stock.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.stockwellness.config.JpaConfig;
import org.stockwellness.config.QueryDslConfig;
import org.stockwellness.domain.stock.MarketType;
import org.stockwellness.domain.stock.insight.SectorIndicators;
import org.stockwellness.domain.stock.insight.SectorInsight;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import({QueryDslConfig.class, JpaConfig.class})
@ActiveProfiles("test")
@DisplayName("SectorInsightRepository 통합 테스트")
class SectorInsightRepositoryTest {

    @Autowired
    private SectorInsightRepository sectorInsightRepository;

    @Test
    @DisplayName("업종 코드와 기준일로 업종 인사이트를 조회한다")
    void findBySectorCodeAndBaseDate_Success() {
        // given
        LocalDate today = LocalDate.now();
        SectorIndicators indicators = SectorIndicators.of(BigDecimal.valueOf(1000), BigDecimal.valueOf(1.5), 100L, 50L, 3, 2);
        SectorInsight insight = SectorInsight.of("전기전자", "001", MarketType.KOSPI, today, indicators, null, false);
        sectorInsightRepository.save(insight);
        sectorInsightRepository.flush();

        // when
        Optional<SectorInsight> found = sectorInsightRepository.findBySectorCodeAndBaseDate("001", today);

        // then
        assertThat(found).isPresent();
        assertThat(found.get().getSectorName()).isEqualTo("전기전자");
    }

    @Test
    @DisplayName("특정 날짜의 등락률 상위 업종을 조회한다")
    void findTopByDate_Success() {
        // given
        LocalDate today = LocalDate.now();
        SectorInsight s1 = SectorInsight.of("IT", "001", MarketType.KOSPI, today, 
                SectorIndicators.of(BigDecimal.valueOf(100), BigDecimal.valueOf(5.0), 0L, 0L, 0, 0), null, false);
        SectorInsight s2 = SectorInsight.of("Finance", "002", MarketType.KOSPI, today, 
                SectorIndicators.of(BigDecimal.valueOf(100), BigDecimal.valueOf(2.0), 0L, 0L, 0, 0), null, false);
        SectorInsight s3 = SectorInsight.of("Bio", "003", MarketType.KOSPI, today, 
                SectorIndicators.of(BigDecimal.valueOf(100), BigDecimal.valueOf(10.0), 0L, 0L, 0, 0), null, false);
        
        sectorInsightRepository.saveAll(List.of(s1, s2, s3));
        sectorInsightRepository.flush();

        // when: 등락률 내림차순 상위 2개
        List<SectorInsight> topSectors = sectorInsightRepository.findTopByDate(today, MarketType.KOSPI, PageRequest.of(0, 2));

        // then
        assertThat(topSectors).hasSize(2);
        assertThat(topSectors.get(0).getSectorName()).isEqualTo("Bio");
        assertThat(topSectors.get(1).getSectorName()).isEqualTo("IT");
    }

    @Test
    @DisplayName("특정 날짜의 수급(외국인+기관 합산) 상위 업종을 조회한다")
    void findTopBySupply_CombinedWeight_Success() {
        // given
        LocalDate today = LocalDate.now();
        
        // Sector A: Foreign 500, Inst 500 (Total 1000)
        SectorInsight sA = SectorInsight.of("SectorA", "001", MarketType.KOSPI, today, 
                SectorIndicators.of(BigDecimal.valueOf(100), BigDecimal.ZERO, 500L, 500L, 1, 1), null, false);
        
        // Sector B: Foreign 800, Inst 100 (Total 900)
        SectorInsight sB = SectorInsight.of("SectorB", "002", MarketType.KOSPI, today, 
                SectorIndicators.of(BigDecimal.valueOf(100), BigDecimal.ZERO, 800L, 100L, 5, 1), null, false);
        
        sectorInsightRepository.saveAll(List.of(sA, sB));
        sectorInsightRepository.flush();

        // when: 합산 금액 기준 정렬 (A: 1000 > B: 900)
        List<SectorInsight> topSupply = sectorInsightRepository.findTopBySupply(today, MarketType.KOSPI, PageRequest.of(0, 10));

        // then: A가 1위여야 함 (기존 로직에서는 외국인 매수세가 강한 B가 1위였음)
        assertThat(topSupply).hasSize(2);
        assertThat(topSupply.get(0).getSectorName()).isEqualTo("SectorA");
        assertThat(topSupply.get(1).getSectorName()).isEqualTo("SectorB");
    }

    @Test
    @DisplayName("가장 최신 기준일을 조회한다")
    void findMaxBaseDate_Success() {
        // given
        LocalDate d1 = LocalDate.of(2026, 1, 1);
        LocalDate d2 = LocalDate.of(2026, 2, 1);
        
        SectorInsight s1 = SectorInsight.of("T1", "001", MarketType.KOSPI, d1, 
                SectorIndicators.of(BigDecimal.ZERO, BigDecimal.ZERO, 0L, 0L, 0, 0), null, false);
        SectorInsight s2 = SectorInsight.of("T2", "002", MarketType.KOSPI, d2, 
                SectorIndicators.of(BigDecimal.ZERO, BigDecimal.ZERO, 0L, 0L, 0, 0), null, false);
        
        sectorInsightRepository.saveAll(List.of(s1, s2));
        sectorInsightRepository.flush();

        // when
        Optional<LocalDate> maxDate = sectorInsightRepository.findMaxBaseDate();

        // then
        assertThat(maxDate).isPresent();
        assertThat(maxDate.get()).isEqualTo(d2);
    }

    @Test
    @DisplayName("소급 기준일은 중복 섹터를 제외한 최근 252거래일만 조회한다")
    void findRecentDistinctBaseDates_LimitsToTradingDays() {
        LocalDate firstTradingDate = LocalDate.of(2025, 1, 2);
        List<SectorInsight> insights = java.util.stream.IntStream.range(0, 253)
                .mapToObj(day -> SectorInsight.of(
                        "전기전자",
                        "001",
                        MarketType.KOSPI,
                        firstTradingDate.plusDays(day),
                        SectorIndicators.of(BigDecimal.ONE, BigDecimal.ZERO, 0L, 0L, 0, 0),
                        null,
                        false
                ))
                .toList();
        sectorInsightRepository.saveAll(insights);
        sectorInsightRepository.flush();

        List<LocalDate> dates = sectorInsightRepository.findRecentDistinctBaseDates(PageRequest.of(0, 252));

        assertThat(dates).hasSize(252);
        assertThat(dates.getFirst()).isEqualTo(firstTradingDate.plusDays(252));
        assertThat(dates.getLast()).isEqualTo(firstTradingDate.plusDays(1));
    }

    @Test
    @DisplayName("여러 섹터 코드의 최신 이전 인사이트를 한 번에 조회한다")
    void findLatestBeforeByCodes_Success() {
        LocalDate 기준일 = LocalDate.of(2026, 4, 9);
        SectorInsight oldKospi = SectorInsight.of("전기전자", "001", MarketType.KOSPI, 기준일.minusDays(3),
                SectorIndicators.of(BigDecimal.valueOf(1000), BigDecimal.ONE, 10L, 10L, 1, 1), null, false);
        SectorInsight latestKospi = SectorInsight.of("전기전자", "001", MarketType.KOSPI, 기준일.minusDays(1),
                SectorIndicators.of(BigDecimal.valueOf(1100), BigDecimal.ONE, 20L, 20L, 2, 2), null, false);
        SectorInsight latestKosdaq = SectorInsight.of("반도체", "101", MarketType.KOSDAQ, 기준일.minusDays(2),
                SectorIndicators.of(BigDecimal.valueOf(900), BigDecimal.ONE, 30L, 30L, 3, 3), null, false);
        SectorInsight current = SectorInsight.of("전기전자", "001", MarketType.KOSPI, 기준일,
                SectorIndicators.of(BigDecimal.valueOf(1200), BigDecimal.ONE, 40L, 40L, 4, 4), null, false);

        sectorInsightRepository.saveAll(List.of(oldKospi, latestKospi, latestKosdaq, current));
        sectorInsightRepository.flush();

        Map<String, SectorInsight> result = sectorInsightRepository.findLatestBeforeByCodes(List.of("001", "101"), 기준일);

        assertThat(result).hasSize(2);
        assertThat(result.get("001").getBaseDate()).isEqualTo(기준일.minusDays(1));
        assertThat(result.get("101").getBaseDate()).isEqualTo(기준일.minusDays(2));
    }

    @Test
    @DisplayName("여러 섹터 코드의 과거 가격을 코드별로 그룹핑하고 limit 만큼만 반환한다")
    void findPastPricesByCodes_Success() {
        LocalDate 기준일 = LocalDate.of(2026, 4, 9);
        sectorInsightRepository.saveAll(List.of(
                SectorInsight.of("전기전자", "001", MarketType.KOSPI, 기준일.minusDays(1),
                        SectorIndicators.of(BigDecimal.valueOf(1100), BigDecimal.ZERO, 0L, 0L, 0, 0), null, false),
                SectorInsight.of("전기전자", "001", MarketType.KOSPI, 기준일.minusDays(2),
                        SectorIndicators.of(BigDecimal.valueOf(1000), BigDecimal.ZERO, 0L, 0L, 0, 0), null, false),
                SectorInsight.of("전기전자", "001", MarketType.KOSPI, 기준일.minusDays(3),
                        SectorIndicators.of(BigDecimal.valueOf(900), BigDecimal.ZERO, 0L, 0L, 0, 0), null, false),
                SectorInsight.of("반도체", "101", MarketType.KOSDAQ, 기준일.minusDays(1),
                        SectorIndicators.of(BigDecimal.valueOf(700), BigDecimal.ZERO, 0L, 0L, 0, 0), null, false)
        ));
        sectorInsightRepository.flush();

        Map<String, List<BigDecimal>> result = sectorInsightRepository.findPastPricesByCodes(List.of("001", "101"), 기준일, 2);

        assertThat(result.get("001")).hasSize(2);
        assertThat(result.get("001").get(0)).isEqualByComparingTo("1100");
        assertThat(result.get("001").get(1)).isEqualByComparingTo("1000");
        assertThat(result.get("101")).hasSize(1);
        assertThat(result.get("101").get(0)).isEqualByComparingTo("700");
    }
}

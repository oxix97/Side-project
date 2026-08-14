package org.stockwellness.batch.job.insight;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.stockwellness.adapter.out.persistence.insight.MarketWeather;
import org.stockwellness.adapter.out.persistence.insight.SectorIndicator;
import org.stockwellness.adapter.out.persistence.insight.SectorWeather;
import org.stockwellness.adapter.out.persistence.insight.repository.MarketWeatherRepository;
import org.stockwellness.adapter.out.persistence.insight.repository.SectorIndicatorRepository;
import org.stockwellness.adapter.out.persistence.insight.repository.SectorWeatherRepository;
import org.stockwellness.adapter.out.persistence.stock.repository.SectorDailyDetailRepository;
import org.stockwellness.adapter.out.persistence.stock.repository.SectorInsightRepository;
import org.stockwellness.application.port.out.stock.SectorDailyDetailSnapshot;
import org.stockwellness.domain.stock.MarketType;
import org.stockwellness.domain.stock.insight.SectorDailyDetail;
import org.stockwellness.domain.stock.insight.SectorIndicators;
import org.stockwellness.domain.stock.insight.SectorInsight;
import org.stockwellness.domain.stock.insight.SectorTechnicalIndicators;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.kafka.consumer.group-id=batch-test-group")
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"market-score-calculated"})
class MarketWeatherJobIntegrationTest {

    @Autowired
    private Job dailyMarketWeatherJob;

    @Autowired
    private Job backfillMarketWeatherJob;

    @Autowired
    @Qualifier("jobLauncher")
    private JobLauncher jobLauncher;

    @Autowired
    private SectorInsightRepository sectorInsightRepository;

    @Autowired
    private SectorDailyDetailRepository sectorDailyDetailRepository;

    @Autowired
    private SectorIndicatorRepository sectorIndicatorRepository;

    @Autowired
    private SectorWeatherRepository sectorWeatherRepository;

    @Autowired
    private MarketWeatherRepository marketWeatherRepository;

    @BeforeEach
    void cleanWeatherData() {
        marketWeatherRepository.deleteAll();
        sectorWeatherRepository.deleteAll();
        sectorIndicatorRepository.deleteAll();
        sectorInsightRepository.deleteAll();
        sectorDailyDetailRepository.deleteAll();
    }

    @Test
    @DisplayName("시장 기상 배치 Job이 빈으로 등록되어 있어야 한다")
    void jobBeans_shouldBeRegistered() {
        assertThat(dailyMarketWeatherJob).isNotNull();
        assertThat(backfillMarketWeatherJob).isNotNull();
    }

    @Test
    @DisplayName("252거래일 백필을 두 번 실행해도 점수와 AI 필드가 동일하다")
    void backfillIsIdempotentAcrossTwoRuns() throws Exception {
        seedTradingDays();

        JobExecution firstExecution = runBackfill();
        assertThat(firstExecution.getStatus())
                .withFailMessage("첫 실행 실패: %s", firstExecution.getAllFailureExceptions())
                .isEqualTo(BatchStatus.COMPLETED);

        LocalDate latest = LocalDate.of(2026, 8, 13);
        SectorWeather sectorWeather = sectorWeatherRepository
                .findByBaseDateAndSectorCode(latest, "001")
                .orElseThrow();
        sectorWeather.updateInsight("기존 AI 제목", "기존 AI 해설");
        sectorWeatherRepository.saveAndFlush(sectorWeather);
        MarketWeather marketWeather = marketWeatherRepository
                .findByBaseDateAndMarketType(latest, "KOSPI")
                .orElseThrow();
        marketWeather.updateInsight("기존 시장 AI 해설");
        marketWeatherRepository.saveAndFlush(marketWeather);

        List<String> firstIndicators = indicatorSnapshot();
        List<String> firstSectorWeather = sectorWeatherSnapshot();
        List<String> firstMarketWeather = marketWeatherSnapshot();

        JobExecution secondExecution = runBackfill();
        assertThat(secondExecution.getStatus())
                .withFailMessage("두 번째 실행 실패: %s", secondExecution.getAllFailureExceptions())
                .isEqualTo(BatchStatus.COMPLETED);

        assertThat(sectorIndicatorRepository.count()).isEqualTo(504);
        assertThat(sectorWeatherRepository.count()).isEqualTo(504);
        assertThat(marketWeatherRepository.count()).isEqualTo(504);
        assertThat(indicatorSnapshot()).isEqualTo(firstIndicators);
        assertThat(sectorWeatherSnapshot()).isEqualTo(firstSectorWeather);
        assertThat(marketWeatherSnapshot()).isEqualTo(firstMarketWeather);
        assertThat(sectorWeatherRepository.findAll())
                .allSatisfy(weather -> assertThat(weather.getWeatherScore()).isBetween(0, 100));
        assertThat(marketWeatherRepository.findAll())
                .allSatisfy(weather -> assertThat(weather.getWeatherScore()).isBetween(0, 100));
    }

    private void seedTradingDays() {
        LocalDate first = LocalDate.of(2025, 12, 5);
        List<SectorInsight> insights = IntStream.range(0, 252)
                .boxed()
                .flatMap(day -> List.of(
                        insight("코스피 섹터", "001", MarketType.KOSPI, first.plusDays(day), day),
                        insight("코스닥 섹터", "101", MarketType.KOSDAQ, first.plusDays(day), day)
                ).stream())
                .toList();
        sectorInsightRepository.saveAllAndFlush(insights);
        List<SectorDailyDetail> details = IntStream.range(0, 252)
                .boxed()
                .flatMap(day -> List.of(
                        detail("코스피 섹터", "001", first.plusDays(day), day),
                        detail("코스닥 섹터", "101", first.plusDays(day), day)
                ).stream())
                .toList();
        sectorDailyDetailRepository.saveAllAndFlush(details);
    }

    private SectorInsight insight(
            String name,
            String code,
            MarketType marketType,
            LocalDate baseDate,
            int day
    ) {
        BigDecimal price = BigDecimal.valueOf(100 + day);
        return SectorInsight.of(
                name,
                code,
                marketType,
                baseDate,
                SectorIndicators.of(price, BigDecimal.ZERO, BigDecimal.valueOf(50), 0L, 0L, 0, 0),
                SectorTechnicalIndicators.of(
                        price,
                        BigDecimal.valueOf(100),
                        BigDecimal.valueOf(50),
                        null,
                        null,
                        null,
                        false,
                        false
                ),
                false
        );
    }

    private SectorDailyDetail detail(String name, String code, LocalDate baseDate, int day) {
        BigDecimal price = BigDecimal.valueOf(100 + day);
        return SectorDailyDetail.of(
                code,
                name,
                new SectorDailyDetailSnapshot(
                        code,
                        baseDate,
                        price,
                        BigDecimal.ZERO,
                        "3",
                        BigDecimal.ZERO,
                        0L,
                        0L,
                        0L,
                        0L,
                        price,
                        price,
                        price,
                        10 + day % 5,
                        0,
                        0,
                        5 + (day + 1) % 5,
                        0,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        0L,
                        0L,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        0L,
                        0L,
                        0L
                )
        );
    }

    private JobExecution runBackfill() throws Exception {
        return jobLauncher.run(
                backfillMarketWeatherJob,
                new JobParametersBuilder().addLong("time", System.nanoTime()).toJobParameters()
        );
    }

    private List<String> indicatorSnapshot() {
        return sectorIndicatorRepository.findAll().stream()
                .sorted(Comparator.comparing(SectorIndicator::getBaseDate)
                        .thenComparing(SectorIndicator::getSectorCode))
                .map(value -> String.join("|",
                        value.getBaseDate().toString(),
                        value.getSectorCode(),
                        Objects.toString(value.getMa20Disparity()),
                        Objects.toString(value.getRsi14()),
                        Objects.toString(value.getAdr()),
                        Boolean.toString(value.isOverheated())))
                .toList();
    }

    private List<String> sectorWeatherSnapshot() {
        return sectorWeatherRepository.findAll().stream()
                .sorted(Comparator.comparing(SectorWeather::getBaseDate)
                        .thenComparing(SectorWeather::getSectorCode))
                .map(value -> String.join("|",
                        value.getBaseDate().toString(),
                        value.getSectorCode(),
                        Integer.toString(value.getWeatherScore()),
                        value.getWeatherState(),
                        Objects.toString(value.getAiTitle(), ""),
                        Objects.toString(value.getAiInsight(), "")))
                .toList();
    }

    private List<String> marketWeatherSnapshot() {
        return marketWeatherRepository.findAll().stream()
                .sorted(Comparator.comparing(MarketWeather::getBaseDate)
                        .thenComparing(MarketWeather::getMarketType))
                .map(value -> String.join("|",
                        value.getBaseDate().toString(),
                        value.getMarketType(),
                        Integer.toString(value.getWeatherScore()),
                        value.getWeatherState(),
                        Objects.toString(value.getAiSummary(), ""),
                        Objects.toString(value.getTopSectors()),
                        Objects.toString(value.getBottomSectors())))
                .toList();
    }
}

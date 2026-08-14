package org.stockwellness.adapter.batch.insight;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JpaPagingItemReader;
import org.springframework.batch.item.database.builder.JpaPagingItemReaderBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.stockwellness.adapter.out.persistence.insight.MarketWeather;
import org.stockwellness.adapter.out.persistence.insight.SectorIndicator;
import org.stockwellness.adapter.out.persistence.insight.SectorWeather;
import org.stockwellness.adapter.out.persistence.insight.repository.MarketWeatherRepository;
import org.stockwellness.adapter.out.persistence.insight.repository.SectorIndicatorRepository;
import org.stockwellness.adapter.out.persistence.insight.repository.SectorWeatherRepository;
import org.stockwellness.adapter.out.persistence.stock.repository.SectorDailyDetailRepository;
import org.stockwellness.adapter.out.persistence.stock.repository.SectorInsightRepository;
import org.stockwellness.application.port.out.messaging.MarketScoreCalculatedEvent;
import org.stockwellness.application.service.insight.MarketScoreCalculationService;
import org.stockwellness.domain.stock.MarketType;
import org.stockwellness.domain.stock.insight.SectorInsight;
import org.stockwellness.domain.stock.insight.MarketWeatherPolicy;
import org.stockwellness.domain.stock.insight.WeatherState;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class MarketWeatherBackfillConfig {

    private static final Set<String> REQUIRED_MARKET_TYPES = Set.of(
            MarketType.KOSPI.name(),
            MarketType.KOSDAQ.name()
    );

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final EntityManagerFactory entityManagerFactory;
    private final SectorIndicatorRepository sectorIndicatorRepository;
    private final SectorWeatherRepository sectorWeatherRepository;
    private final MarketWeatherRepository marketWeatherRepository;
    private final SectorDailyDetailRepository sectorDailyDetailRepository;
    private final SectorInsightRepository sectorInsightRepository;
    private final MarketScoreCalculationService marketScoreCalculationService;

    @Bean
    public Job backfillMarketWeatherJob(
            Step backfillSectorIndicatorStep,
            Step backfillMarketWeatherStep
    ) {
        return new JobBuilder("backfillMarketWeatherJob", jobRepository)
                .start(backfillSectorIndicatorStep)
                .next(backfillMarketWeatherStep)
                .build();
    }

    @Bean
    public Step backfillSectorIndicatorStep(
            JpaPagingItemReader<SectorInsight> backfillReader,
            ItemProcessor<SectorInsight, SectorIndicator> backfillProcessor,
            ItemWriter<SectorIndicator> backfillWriter
    ) {
        return new StepBuilder("backfillSectorIndicatorStep", jobRepository)
                .<SectorInsight, SectorIndicator>chunk(50, transactionManager)
                .reader(backfillReader)
                .processor(backfillProcessor)
                .writer(backfillWriter)
                .build();
    }

    @Bean
    public Step backfillMarketWeatherStep() {
        return new StepBuilder("backfillMarketWeatherStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    resolveTradingDates().forEach(this::backfillWeather);
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    @Bean
    @StepScope
    public JpaPagingItemReader<SectorInsight> backfillReader() {
        LocalDate startDate = resolveTradingDates().getFirst();

        log.info("🚀 Starting Market Weather Backfill from {}", startDate);

        return new JpaPagingItemReaderBuilder<SectorInsight>()
                .name("backfillReader")
                .entityManagerFactory(entityManagerFactory)
                .queryString("SELECT s FROM SectorInsight s WHERE s.baseDate >= :startDate ORDER BY s.baseDate ASC, s.id ASC")
                .parameterValues(Map.of("startDate", startDate))
                .pageSize(50)
                .build();
    }

    @Bean
    public ItemProcessor<SectorInsight, SectorIndicator> backfillProcessor() {
        return insight -> {
            var source = sectorDailyDetailRepository
                    .findBySectorCodeAndBaseDate(insight.getSectorCode(), insight.getBaseDate())
                    .orElseThrow(() -> new IllegalStateException(
                            "ADR 원천 상세가 없습니다: " + insight.getBaseDate() + ", " + insight.getSectorCode()
                    ));
            return SectorIndicatorMapper.from(insight, source.calculateAdvanceDeclineRatio());
        };
    }

    @Bean
    public ItemWriter<SectorIndicator> backfillWriter() {
        return items -> {
            List<SectorIndicator> upserts = items.getItems().stream()
                    .map(item -> sectorIndicatorRepository
                            .findByBaseDateAndSectorCode(item.getBaseDate(), item.getSectorCode())
                            .map(existing -> {
                                existing.update(
                                        item.getMa20Disparity(),
                                        item.getRsi14(),
                                        item.getAdr(),
                                        item.isOverheated()
                                );
                                return existing;
                            })
                            .orElse(item))
                    .toList();
            sectorIndicatorRepository.saveAll(upserts);
        };
    }

    List<LocalDate> resolveTradingDates() {
        int requiredDays = MarketWeatherPolicy.DEFAULT.rollingWindowDays();
        List<LocalDate> recentTradingDates = sectorInsightRepository.findRecentDistinctBaseDates(
                PageRequest.of(0, requiredDays)
        );
        if (recentTradingDates.size() < requiredDays) {
            throw new IllegalStateException(
                    "시장 날씨 소급 배치에는 252거래일이 필요합니다: " + recentTradingDates.size()
            );
        }
        return recentTradingDates.reversed();
    }

    void backfillWeather(LocalDate baseDate) {
        List<MarketScoreCalculatedEvent> events = marketScoreCalculationService.calculate(baseDate);
        Set<String> calculatedMarkets = events.stream()
                .filter(event -> !event.sectorScores().isEmpty())
                .map(MarketScoreCalculatedEvent::marketType)
                .collect(java.util.stream.Collectors.toSet());
        if (!calculatedMarkets.containsAll(REQUIRED_MARKET_TYPES)) {
            Set<String> missingMarkets = new java.util.HashSet<>(REQUIRED_MARKET_TYPES);
            missingMarkets.removeAll(calculatedMarkets);
            throw new IllegalStateException(
                    "시장 점수 계산 결과가 누락되었습니다: " + baseDate + ", " + missingMarkets
            );
        }

        for (MarketScoreCalculatedEvent event : events) {
            event.sectorScores().stream()
                    .map(score -> SectorWeather.builder()
                            .baseDate(baseDate)
                            .sectorCode(score.code())
                            .weatherScore(score.score())
                            .weatherState(WeatherState.fromScore(score.score()).getStateName())
                            .build())
                    .forEach(this::saveSectorWeather);

            List<MarketWeather.SectorSummary> topSectors = event.sectorScores().stream()
                    .sorted(Comparator.comparingInt(MarketScoreCalculatedEvent.SectorScore::score).reversed())
                    .limit(3)
                    .map(this::toSummary)
                    .toList();
            List<MarketWeather.SectorSummary> bottomSectors = event.sectorScores().stream()
                    .sorted(Comparator.comparingInt(MarketScoreCalculatedEvent.SectorScore::score))
                    .limit(3)
                    .map(this::toSummary)
                    .toList();

            saveMarketWeather(MarketWeather.builder()
                    .baseDate(baseDate)
                    .marketType(event.marketType())
                    .weatherScore(event.overallScore())
                    .weatherState(WeatherState.fromScore(event.overallScore()).getStateName())
                    .topSectors(topSectors)
                    .bottomSectors(bottomSectors)
                    .build());
        }
    }

    private MarketWeather.SectorSummary toSummary(MarketScoreCalculatedEvent.SectorScore score) {
        return new MarketWeather.SectorSummary(
                score.code(),
                score.name(),
                score.score(),
                WeatherState.fromScore(score.score()).getIconEmoji()
        );
    }

    void saveSectorWeather(SectorWeather calculated) {
        SectorWeather target = sectorWeatherRepository
                .findByBaseDateAndSectorCode(calculated.getBaseDate(), calculated.getSectorCode())
                .map(existing -> {
                    existing.updateScore(calculated.getWeatherScore(), calculated.getWeatherState());
                    return existing;
                })
                .orElse(calculated);
        sectorWeatherRepository.save(target);
    }

    void saveMarketWeather(MarketWeather calculated) {
        MarketWeather target = marketWeatherRepository
                .findByBaseDateAndMarketType(calculated.getBaseDate(), calculated.getMarketType())
                .map(existing -> {
                    existing.updateCalculation(
                            calculated.getWeatherScore(),
                            calculated.getWeatherState(),
                            calculated.getTopSectors(),
                            calculated.getBottomSectors()
                    );
                    return existing;
                })
                .orElse(calculated);
        marketWeatherRepository.save(target);
    }
}

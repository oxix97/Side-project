package org.stockwellness.adapter.batch.insight;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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
import org.springframework.beans.factory.annotation.Value;
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
import org.stockwellness.adapter.out.persistence.stock.repository.SectorInsightRepository;
import org.stockwellness.domain.stock.insight.SectorInsight;
import org.stockwellness.domain.stock.insight.MarketWeatherPolicy;
import org.stockwellness.domain.stock.insight.WeatherState;
import org.stockwellness.global.util.DateUtil;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class MarketWeatherBackfillConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final EntityManagerFactory entityManagerFactory;
    private final SectorIndicatorRepository sectorIndicatorRepository;
    private final SectorWeatherRepository sectorWeatherRepository;
    private final MarketWeatherRepository marketWeatherRepository;
    private final SectorInsightRepository sectorInsightRepository;

    @Bean
    public Job backfillMarketWeatherJob(
            Step backfillSectorIndicatorStep,
            Step backfillSectorWeatherStep,
            Step backfillMarketWeatherStep
    ) {
        return new JobBuilder("backfillMarketWeatherJob", jobRepository)
                .start(backfillSectorIndicatorStep)
                .next(backfillSectorWeatherStep)
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
    public Step backfillSectorWeatherStep(
            JpaPagingItemReader<SectorIndicator> sectorIndicatorReader,
            ItemProcessor<SectorIndicator, SectorWeather> sectorWeatherProcessor,
            ItemWriter<SectorWeather> sectorWeatherWriter
    ) {
        return new StepBuilder("backfillSectorWeatherStep", jobRepository)
                .<SectorIndicator, SectorWeather>chunk(50, transactionManager)
                .reader(sectorIndicatorReader)
                .processor(sectorWeatherProcessor)
                .writer(sectorWeatherWriter)
                .build();
    }

    @Bean
    public Step backfillMarketWeatherStep() {
        return new StepBuilder("backfillMarketWeatherStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    String startDateStr = (String) chunkContext.getStepContext().getJobParameters().get("startDate");
                    LocalDate startDate = resolveStartDate(startDateStr);

                    // 일별로 섹터별 점수를 가져와서 시장 전체 기상도 생성
                    LocalDate current = startDate;
                    LocalDate end = DateUtil.today();

                    while (!current.isAfter(end)) {
                        List<SectorWeather> dailyWeathers = sectorWeatherRepository.findAllByBaseDate(current);
                        if (!dailyWeathers.isEmpty()) {
                            double avgScore = dailyWeathers.stream()
                                    .mapToInt(SectorWeather::getWeatherScore)
                                    .average()
                                    .orElse(50.0);

                            // 상위/하위 섹터 요약 (간소화)
                            var sorted = dailyWeathers.stream()
                                    .sorted((a, b) -> Integer.compare(b.getWeatherScore(), a.getWeatherScore()))
                                    .toList();

                            var topSectors = sorted.stream().limit(3)
                                    .map(s -> new MarketWeather.SectorSummary(s.getSectorCode(), getSectorName(s.getSectorCode()), s.getWeatherScore(), WeatherState.fromScore(s.getWeatherScore()).getIconEmoji()))
                                    .toList();

                            var bottomSectors = sorted.stream().skip(Math.max(0, sorted.size() - 3))
                                    .map(s -> new MarketWeather.SectorSummary(s.getSectorCode(), getSectorName(s.getSectorCode()), s.getWeatherScore(), WeatherState.fromScore(s.getWeatherScore()).getIconEmoji()))
                                    .toList();

                            MarketWeather marketWeather = MarketWeather.builder()
                                    .baseDate(current)
                                    .marketType("KOSPI") // 기본값
                                    .weatherScore((int) avgScore)
                                    .weatherState(WeatherState.fromScore((int) avgScore).getStateName())
                                    .topSectors(topSectors)
                                    .bottomSectors(bottomSectors)
                                    .build();

                            saveMarketWeather(marketWeather);
                        }
                        current = current.plusDays(1);
                    }
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    private String getSectorName(String code) {
        return sectorInsightRepository.findFirstBySectorCodeOrderByBaseDateDesc(code)
                .map(si -> si.getSectorName())
                .orElse(code);
    }

    @Bean
    @StepScope
    public JpaPagingItemReader<SectorIndicator> sectorIndicatorReader(
            @Value("#{jobParameters['startDate']}") String startDateStr
    ) {
        LocalDate startDate = resolveStartDate(startDateStr);

        return new JpaPagingItemReaderBuilder<SectorIndicator>()
                .name("sectorIndicatorReader")
                .entityManagerFactory(entityManagerFactory)
                .queryString("SELECT s FROM SectorIndicator s WHERE s.baseDate >= :startDate")
                .parameterValues(Map.of("startDate", startDate))
                .pageSize(50)
                .build();
    }

    @Bean
    public ItemProcessor<SectorIndicator, SectorWeather> sectorWeatherProcessor() {
        return indicator -> {
            // 점수 산출 로직: (이격도 점수 * 0.4) + (ADR 점수 * 0.3) + (RSI 점수 * 0.3)
            int score = calculateScore(indicator);

            return SectorWeather.builder()
                    .baseDate(indicator.getBaseDate())
                    .sectorCode(indicator.getSectorCode())
                    .weatherScore(score)
                    .weatherState(WeatherState.fromScore(score).getStateName())
                    .build();
        };
    }

    private int calculateScore(SectorIndicator indicator) {
        double score = 50.0;

        // 1. 이격도 (100 기준 +- 10% 범위)
        if (indicator.getMa20Disparity() != null) {
            double disp = indicator.getMa20Disparity().doubleValue();
            double dispScore = Math.max(0, Math.min(100, (disp - 90) * 5)); // 90일때 0, 110일때 100
            score = score * 0.6 + dispScore * 0.4;
        }

        // 2. RSI (30~70 기준)
        if (indicator.getRsi14() != null) {
            double rsi = indicator.getRsi14().doubleValue();
            double rsiScore = rsi; // RSI 자체가 0~100 지표
            score = score * 0.7 + rsiScore * 0.3;
        }

        // 3. ADR (100 기준 +- 20% 범위)
        if (indicator.getAdr() != null) {
            double adr = indicator.getAdr().doubleValue();
            double adrScore = Math.max(0, Math.min(100, (adr - 80) * 2.5)); // 80일때 0, 120일때 100
            score = score * 0.7 + adrScore * 0.3;
        }

        return (int) score;
    }

    @Bean
    public ItemWriter<SectorWeather> sectorWeatherWriter() {
        return items -> {
            List<SectorWeather> upserts = items.getItems().stream()
                    .map(item -> sectorWeatherRepository
                            .findByBaseDateAndSectorCode(item.getBaseDate(), item.getSectorCode())
                            .map(existing -> {
                                existing.updateScore(item.getWeatherScore(), item.getWeatherState());
                                return existing;
                            })
                            .orElse(item))
                    .toList();
            sectorWeatherRepository.saveAll(upserts);
        };
    }

    @Bean
    @StepScope
    public JpaPagingItemReader<SectorInsight> backfillReader(
            @Value("#{jobParameters['startDate']}") String startDateStr
    ) {
        LocalDate startDate = resolveStartDate(startDateStr);

        log.info("🚀 Starting Market Weather Backfill from {}", startDate);

        return new JpaPagingItemReaderBuilder<SectorInsight>()
                .name("backfillReader")
                .entityManagerFactory(entityManagerFactory)
                .queryString("SELECT s FROM SectorInsight s WHERE s.baseDate >= :startDate ORDER BY s.baseDate ASC")
                .parameterValues(Map.of("startDate", startDate))
                .pageSize(50)
                .build();
    }

    @Bean
    public ItemProcessor<SectorInsight, SectorIndicator> backfillProcessor() {
        return insight -> {
            BigDecimal disparity = BigDecimal.ZERO;
            if (insight.getIndicators() != null && insight.getIndicators().getSectorIndexCurrentPrice() != null 
                && insight.getTechnicalIndicators() != null && insight.getTechnicalIndicators().getMa20() != null 
                && insight.getTechnicalIndicators().getMa20().compareTo(BigDecimal.ZERO) > 0) {
                
                disparity = insight.getIndicators().getSectorIndexCurrentPrice()
                        .divide(insight.getTechnicalIndicators().getMa20(), 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));
            }

            BigDecimal adr = (insight.getIndicators() != null) ? insight.getIndicators().getAdvanceRatio() : BigDecimal.ZERO;
            BigDecimal rsi = (insight.getTechnicalIndicators() != null) ? insight.getTechnicalIndicators().getRsi14() : null;

            return SectorIndicator.builder()
                    .baseDate(insight.getBaseDate())
                    .sectorCode(insight.getSectorCode())
                    .ma20Disparity(disparity)
                    .adr(adr)
                    .rsi14(rsi)
                    .isOverheated(insight.isOverheated())
                    .build();
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

    LocalDate resolveStartDate(String requestedStartDate) {
        LocalDate parsed = DateUtil.parseFlexible(requestedStartDate);
        if (parsed != null) {
            return parsed;
        }

        List<LocalDate> recentTradingDates = sectorInsightRepository.findRecentDistinctBaseDates(
                PageRequest.of(0, MarketWeatherPolicy.DEFAULT.rollingWindowDays())
        );
        return recentTradingDates.isEmpty()
                ? DateUtil.today()
                : recentTradingDates.getLast();
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

package org.stockwellness.adapter.batch.insight;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.stockwellness.adapter.out.persistence.insight.SectorIndicator;
import org.stockwellness.adapter.out.persistence.insight.repository.SectorIndicatorRepository;
import org.stockwellness.adapter.out.persistence.stock.repository.SectorInsightRepository;
import org.stockwellness.application.port.out.messaging.MarketScoreCalculatedEvent;
import org.stockwellness.application.service.insight.WeatherScoreCalculator;
import org.stockwellness.domain.stock.MarketType;
import org.stockwellness.domain.stock.insight.MarketWeatherPolicy;
import org.stockwellness.domain.stock.insight.SectorInsight;
import org.stockwellness.global.util.DateUtil;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class MarketWeatherBatchConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final EntityManagerFactory entityManagerFactory;
    private final SectorIndicatorRepository sectorIndicatorRepository;
    private final SectorInsightRepository sectorInsightRepository;
    private final WeatherScoreCalculator weatherScoreCalculator;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Bean
    public Job dailyMarketWeatherJob(
            Step calculateSectorIndicatorStep,
            Step publishMarketScoreEventStep
    ) {
        return new JobBuilder("dailyMarketWeatherJob", jobRepository)
                .start(calculateSectorIndicatorStep)
                .next(publishMarketScoreEventStep)
                .build();
    }

    @Bean
    public Step calculateSectorIndicatorStep(
            JpaPagingItemReader<SectorInsight> sectorInsightReader,
            ItemProcessor<SectorInsight, SectorIndicator> sectorIndicatorProcessor,
            ItemWriter<SectorIndicator> sectorIndicatorWriter
    ) {
        return new StepBuilder("calculateSectorIndicatorStep", jobRepository)
                .<SectorInsight, SectorIndicator>chunk(20, transactionManager)
                .reader(sectorInsightReader)
                .processor(sectorIndicatorProcessor)
                .writer(sectorIndicatorWriter)
                .build();
    }

    @Bean
    @StepScope
    public JpaPagingItemReader<SectorInsight> sectorInsightReader(
            @Value("#{jobParameters['targetDate']}") String targetDateStr
    ) {
        LocalDate targetDate = DateUtil.parseFlexible(targetDateStr);
        if (targetDate == null) targetDate = DateUtil.today();

        return new JpaPagingItemReaderBuilder<SectorInsight>()
                .name("sectorInsightReader")
                .entityManagerFactory(entityManagerFactory)
                .queryString("SELECT s FROM SectorInsight s WHERE s.baseDate = :targetDate")
                .parameterValues(Map.of("targetDate", targetDate))
                .pageSize(20)
                .build();
    }

    @Bean
    public ItemProcessor<SectorInsight, SectorIndicator> sectorIndicatorProcessor() {
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
    public ItemWriter<SectorIndicator> sectorIndicatorWriter() {
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

    @Bean
    public Step publishMarketScoreEventStep() {
        return new StepBuilder("publishMarketScoreEventStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    String targetDateStr = (String) chunkContext.getStepContext().getJobParameters().get("targetDate");
                    LocalDate targetDate = DateUtil.parseFlexible(targetDateStr);
                    if (targetDate == null) {
                        targetDate = DateUtil.today();
                    }

                    List<MarketScoreCalculatedEvent> events = buildMarketScoreEvents(targetDate);
                    if (events.isEmpty()) {
                        throw new IllegalStateException("시장 점수 이벤트를 생성할 섹터 지표가 없습니다: " + targetDate);
                    }

                    for (MarketScoreCalculatedEvent event : events) {
                        String eventKey = event.marketType() + ":" + event.baseDate();
                        kafkaTemplate.send("market-score-calculated", eventKey, event)
                                .get(10, TimeUnit.SECONDS);
                        log.info("🚀 Published MarketScoreCalculatedEvent for {} on {}", event.marketType(), targetDate);
                    }

                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    List<MarketScoreCalculatedEvent> buildMarketScoreEvents(LocalDate targetDate) {
        Map<MarketType, List<SectorInsight>> insightsByMarket = sectorInsightRepository.findAllByBaseDate(targetDate)
                .stream()
                .collect(Collectors.groupingBy(SectorInsight::getMarketType));

        return insightsByMarket.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> buildMarketScoreEvent(targetDate, entry.getKey(), entry.getValue()))
                .toList();
    }

    private MarketScoreCalculatedEvent buildMarketScoreEvent(
            LocalDate targetDate,
            MarketType marketType,
            List<SectorInsight> insights
    ) {
        List<MarketScoreCalculatedEvent.SectorScore> sectorScores = insights.stream()
                .sorted(Comparator.comparing(SectorInsight::getSectorCode))
                .map(insight -> {
                    SectorIndicator current = sectorIndicatorRepository
                            .findByBaseDateAndSectorCode(targetDate, insight.getSectorCode())
                            .orElseThrow(() -> new IllegalStateException(
                                    "당일 섹터 지표가 없습니다: " + insight.getSectorCode()
                            ));
                    List<SectorIndicator> history = sectorIndicatorRepository
                            .findAllBySectorCodeAndBaseDateLessThanEqualOrderByBaseDateDesc(
                                    insight.getSectorCode(),
                                    targetDate,
                                    PageRequest.of(0, MarketWeatherPolicy.DEFAULT.rollingWindowDays())
                            );
                    int score = weatherScoreCalculator.calculate(current, history);
                    return new MarketScoreCalculatedEvent.SectorScore(
                            insight.getSectorCode(),
                            insight.getSectorName(),
                            score
                    );
                })
                .toList();

        int overallScore = BigDecimal.valueOf(sectorScores.stream()
                        .mapToInt(MarketScoreCalculatedEvent.SectorScore::score)
                        .average()
                        .orElse(50.0))
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();

        return new MarketScoreCalculatedEvent(
                targetDate,
                marketType.name(),
                overallScore,
                sectorScores
        );
    }
}

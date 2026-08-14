package org.stockwellness.adapter.batch.insight;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.stockwellness.adapter.out.persistence.insight.SectorIndicator;
import org.stockwellness.adapter.out.persistence.insight.repository.SectorIndicatorRepository;
import org.stockwellness.application.port.out.messaging.MarketScoreCalculatedEvent;
import org.stockwellness.application.service.insight.MarketScoreCalculationService;
import org.stockwellness.config.KafkaTopicConfig;
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
    private final MarketScoreCalculationService marketScoreCalculationService;
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
        return SectorIndicatorMapper::from;
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
                        kafkaTemplate.send(KafkaTopicConfig.MARKET_SCORE_CALCULATED_TOPIC, eventKey, event)
                                .get(10, TimeUnit.SECONDS);
                        log.info("🚀 Published MarketScoreCalculatedEvent for {} on {}", event.marketType(), targetDate);
                    }

                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    List<MarketScoreCalculatedEvent> buildMarketScoreEvents(LocalDate targetDate) {
        return marketScoreCalculationService.calculate(targetDate);
    }
}

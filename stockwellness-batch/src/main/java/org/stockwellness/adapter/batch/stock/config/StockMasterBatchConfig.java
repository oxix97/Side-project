package org.stockwellness.adapter.batch.stock.config;

import java.util.ArrayList;
import java.util.Objects;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.stockwellness.adapter.batch.stock.step.reader.KosdaqMasterItemReader;
import org.stockwellness.adapter.batch.stock.step.reader.KospiMasterItemReader;
import org.stockwellness.adapter.batch.stock.step.tasklet.StockDelistTasklet;
import org.stockwellness.application.port.in.batch.StockMasterSyncUseCase;
import org.stockwellness.application.port.out.stock.StockPort;
import org.stockwellness.batch.support.BatchMdcListener;
import org.stockwellness.batch.support.logging.CommonBatchJobLoggingListener;
import org.stockwellness.batch.support.logging.CommonBatchStepLoggingListener;
import org.stockwellness.domain.stock.KosdaqItem;
import org.stockwellness.domain.stock.KospiItem;
import org.stockwellness.domain.stock.MarketType;
import org.stockwellness.domain.stock.Stock;

/**
 * 종목 마스터 동기화 Spring Batch Job 설정
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class StockMasterBatchConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager txManager;
    private final StockMasterSyncUseCase stockMasterSyncUseCase;
    private final StockPort stockPort;
    private final BatchMdcListener mdcListener;
    private final JobExecutionListener commonJobListener;
    private final CommonBatchJobLoggingListener commonBatchJobLoggingListener;
    private final CommonBatchStepLoggingListener commonBatchStepLoggingListener;

    private static final int CHUNK_SIZE = 500;

    @Bean
    public Job stockMasterSyncJob(
            Step kospiUpsertStep,
            Step kospiDelistStep,
            Step kosdaqUpsertStep,
            Step kosdaqDelistStep
    ) {
        return new JobBuilder("stockMasterSyncJob", jobRepository)
                .listener(commonJobListener)
                .listener(commonBatchJobLoggingListener)
                .start(kospiUpsertStep)
                .next(kospiDelistStep)
                .next(kosdaqUpsertStep)
                .next(kosdaqDelistStep)
                .build();
    }

    @Bean
    public Step kospiUpsertStep(
            KospiMasterItemReader kospiItemReader,
            ItemProcessor<KospiItem, Stock> kospiItemProcessor,
            ItemWriter<Stock> stockItemWriter
    ) {
        return new StepBuilder("kospiUpsertStep", jobRepository)
                .<KospiItem, Stock>chunk(CHUNK_SIZE, txManager)
                .reader(kospiItemReader)
                .processor(kospiItemProcessor)
                .writer(stockItemWriter)
                .listener(mdcListener)
                .listener(commonBatchStepLoggingListener)
                .faultTolerant()
                .retryLimit(3)
                .retry(TransientDataAccessException.class)
                .build();
    }

    @Bean
    public Step kospiDelistStep() {
        return new StepBuilder("kospiDelistStep", jobRepository)
                .tasklet(new StockDelistTasklet(stockMasterSyncUseCase, MarketType.KOSPI), txManager)
                .listener(mdcListener)
                .listener(commonBatchStepLoggingListener)
                .build();
    }

    @Bean
    public Step kosdaqUpsertStep(
            KosdaqMasterItemReader kosdaqItemReader,
            ItemProcessor<KosdaqItem, Stock> kosdaqItemProcessor,
            ItemWriter<Stock> stockItemWriter
    ) {
        return new StepBuilder("kosdaqUpsertStep", jobRepository)
                .<KosdaqItem, Stock>chunk(CHUNK_SIZE, txManager)
                .reader(kosdaqItemReader)
                .processor(kosdaqItemProcessor)
                .writer(stockItemWriter)
                .listener(mdcListener)
                .listener(commonBatchStepLoggingListener)
                .faultTolerant()
                .retryLimit(3)
                .retry(TransientDataAccessException.class)
                .build();
    }

    @Bean
    public Step kosdaqDelistStep() {
        return new StepBuilder("kosdaqDelistStep", jobRepository)
                .tasklet(new StockDelistTasklet(stockMasterSyncUseCase, MarketType.KOSDAQ), txManager)
                .listener(mdcListener)
                .listener(commonBatchStepLoggingListener)
                .build();
    }

    @Bean
    @StepScope
    public KospiMasterItemReader kospiItemReader() {
        return new KospiMasterItemReader(stockMasterSyncUseCase.loadKospiItems());
    }

    @Bean
    @StepScope
    public KosdaqMasterItemReader kosdaqItemReader() {
        return new KosdaqMasterItemReader(stockMasterSyncUseCase.loadKosdaqItems());
    }

    @Bean
    @StepScope
    public ItemProcessor<KospiItem, Stock> kospiItemProcessor() {
        return item -> stockMasterSyncUseCase
                .upsertKospi(new StockMasterSyncUseCase.KospiMasterSyncCommand(item))
                .stock();
    }

    @Bean
    @StepScope
    public ItemProcessor<KosdaqItem, Stock> kosdaqItemProcessor() {
        return item -> stockMasterSyncUseCase
                .upsertKosdaq(new StockMasterSyncUseCase.KosdaqMasterSyncCommand(item))
                .stock();
    }

    @Bean
    public ItemWriter<Stock> stockItemWriter() {
        return chunk -> stockPort.saveAll(new ArrayList<>(chunk.getItems().stream()
                .filter(Objects::nonNull)
                .toList()));
    }
}

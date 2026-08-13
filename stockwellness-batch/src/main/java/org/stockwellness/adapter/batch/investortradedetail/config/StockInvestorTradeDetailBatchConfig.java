package org.stockwellness.adapter.batch.investortradedetail.config;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.support.ListItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.stockwellness.adapter.batch.investortradedetail.model.InvestorTradeDetailUpdateCommand;
import org.stockwellness.adapter.batch.investortradedetail.step.processor.StockInvestorTradeDetailProcessor;
import org.stockwellness.adapter.batch.investortradedetail.step.tasklet.StockInvestorTradeDetailValidationTasklet;
import org.stockwellness.adapter.batch.investortradedetail.step.writer.StockInvestorTradeDetailWriter;
import org.stockwellness.adapter.out.external.kis.dto.InvestorTradeDetail;
import org.stockwellness.adapter.out.persistence.stock.repository.StockRepository;
import org.stockwellness.application.service.batch.StockInvestorTradeDetailBatchService;
import org.stockwellness.batch.support.BatchMdcListener;
import org.stockwellness.global.util.DateUtil;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class StockInvestorTradeDetailBatchConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final BatchMdcListener mdcListener;
    private final JobExecutionListener commonJobListener;
    private final StockInvestorTradeDetailBatchService batchService;
    private final StockRepository stockRepository;
    private final JdbcTemplate jdbcTemplate;

    @Bean
    public Job stockInvestorTradeDetailJob(
            Step stockInvestorTradeDetailValidationStep,
            Step stockInvestorTradeDetailStep
    ) {
        return new JobBuilder("stockInvestorTradeDetailJob", jobRepository)
                .start(stockInvestorTradeDetailValidationStep)
                .next(stockInvestorTradeDetailStep)
                .listener(mdcListener)
                .listener(commonJobListener)
                .build();
    }

    @Bean
    public Step stockInvestorTradeDetailValidationStep(
            StockInvestorTradeDetailValidationTasklet stockInvestorTradeDetailValidationTasklet
    ) {
        return new StepBuilder("stockInvestorTradeDetailValidationStep", jobRepository)
                .tasklet(stockInvestorTradeDetailValidationTasklet, transactionManager)
                .listener(mdcListener)
                .build();
    }

    @Bean
    public Step stockInvestorTradeDetailStep(
            ListItemReader<InvestorTradeDetail> stockInvestorTradeDetailReader,
            StockInvestorTradeDetailProcessor stockInvestorTradeDetailProcessor,
            StockInvestorTradeDetailWriter stockInvestorTradeDetailWriter,
            StockInvestorTradeDetailStepLoggingListener stockInvestorTradeDetailStepLoggingListener
    ) {
        return new StepBuilder("stockInvestorTradeDetailStep", jobRepository)
                .<InvestorTradeDetail, InvestorTradeDetailUpdateCommand>chunk(50, transactionManager)
                .reader(stockInvestorTradeDetailReader)
                .processor(stockInvestorTradeDetailProcessor)
                .writer(stockInvestorTradeDetailWriter)
                .listener(stockInvestorTradeDetailStepLoggingListener)
                .listener(mdcListener)
                .build();
    }

    @Bean
    @StepScope
    public ListItemReader<InvestorTradeDetail> stockInvestorTradeDetailReader() {
        return new ListItemReader<>(batchService.fetchMergedDetails());
    }

    @Bean
    @StepScope
    public StockInvestorTradeDetailProcessor stockInvestorTradeDetailProcessor(
            @Value("#{jobExecutionContext['resolvedBaseDate']}") String resolvedBaseDateStr
    ) {
        return new StockInvestorTradeDetailProcessor(stockRepository, parseTargetDate(resolvedBaseDateStr));
    }

    @Bean
    public StockInvestorTradeDetailStepLoggingListener stockInvestorTradeDetailStepLoggingListener() {
        return new StockInvestorTradeDetailStepLoggingListener();
    }

    @Bean
    @StepScope
    public StockInvestorTradeDetailValidationTasklet stockInvestorTradeDetailValidationTasklet(
            @Value("#{jobParameters['targetDate']}") String targetDateStr
    ) {
        LocalDate requestedDate = parseTargetDate(targetDateStr);
        return new StockInvestorTradeDetailValidationTasklet(batchService, requestedDate);
    }

    @Bean
    public StockInvestorTradeDetailWriter stockInvestorTradeDetailWriter() {
        return new StockInvestorTradeDetailWriter(jdbcTemplate);
    }

    private LocalDate parseTargetDate(String targetDateStr) {
        if (targetDateStr == null || targetDateStr.isBlank()) {
            return DateUtil.today();
        }
        try {
            return DateUtil.parseFlexible(targetDateStr);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("잘못된 targetDate 형식입니다: " + targetDateStr, exception);
        }
    }
}

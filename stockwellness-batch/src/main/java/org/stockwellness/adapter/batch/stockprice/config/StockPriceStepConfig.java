package org.stockwellness.adapter.batch.stockprice.config;

import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.JpaPagingItemReader;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.item.database.builder.JpaPagingItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.stockwellness.adapter.batch.stockprice.step.processor.DailyStockPriceProcessor;
import org.stockwellness.adapter.batch.stockprice.step.processor.TechnicalIndicatorProcessor;
import org.stockwellness.adapter.batch.stockprice.step.reader.StockListReader;
import org.stockwellness.adapter.batch.stockprice.step.writer.StockPriceListWriter;
import org.stockwellness.adapter.batch.stockprice.support.StockPriceBatchTargetQuery;
import org.stockwellness.adapter.batch.stockprice.support.StockPriceSql;
import org.stockwellness.application.port.out.external.kis.KisDailyPricePort;
import org.stockwellness.application.port.out.stock.StockPricePort;
import org.stockwellness.domain.stock.Stock;
import org.stockwellness.domain.stock.StockStatus;
import org.stockwellness.domain.stock.price.StockPrice;
import org.stockwellness.global.util.DateUtil;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class StockPriceStepConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager txManager;
    private final EntityManagerFactory entityManagerFactory;
    private final DataSource dataSource;

    /**
     * [Step 1] 시세 수집 단계: [KIS] 멀티종목시세 조회를 통해 원시 가격 데이터를 저장
     */
    @Bean
    public Step dailyStockPriceStep(
            StockListReader dailyStockPriceReader,
            DailyStockPriceProcessor dailyStockPriceProcessor,
            StockPriceListWriter stockPriceListWriter
    ) {
        return new StepBuilder("dailyStockPriceStep", jobRepository)
                .<List<Stock>, List<StockPrice>>chunk(1, txManager)
                .reader(dailyStockPriceReader)
                .processor(dailyStockPriceProcessor)
                .writer(stockPriceListWriter)
                .build();
    }

    /**
     * [Step 2] 기술 지표 계산
     */
    @Bean
    public Step technicalIndicatorCalculateStep(
            JpaPagingItemReader<Stock> technicalIndicatorStockReader,
            TechnicalIndicatorProcessor technicalIndicatorProcessor,
            JdbcBatchItemWriter<StockPrice> stockPriceJdbcWriter
    ) {
        return new StepBuilder("technicalIndicatorCalculateStep", jobRepository)
                .<Stock, StockPrice>chunk(30, txManager)
                .reader(technicalIndicatorStockReader)
                .processor(technicalIndicatorProcessor)
                .writer(stockPriceJdbcWriter)
                .build();
    }

    @Bean
    @StepScope
    public DailyStockPriceProcessor dailyStockPriceProcessor(
            KisDailyPricePort kisDailyPricePort,
            @Value("#{jobParameters['targetDate']}") String targetDateStr,
            @Value("#{jobParameters['endDate']}") String endDateStr
    ) {
        LocalDate targetDate = parseTargetDate(targetDateStr, endDateStr);
        return new DailyStockPriceProcessor(kisDailyPricePort, targetDate);
    }

    private LocalDate parseTargetDate(String targetDateStr, String endDateStr) {
        LocalDate targetDate = DateUtil.parse(targetDateStr);
        if (targetDate == null) {
            targetDate = DateUtil.parse(endDateStr);
        }
        if (targetDate == null) {
            targetDate = DateUtil.today();
        }
        return targetDate;
    }

    @Bean
    @StepScope
    public TechnicalIndicatorProcessor technicalIndicatorProcessor(StockPricePort stockPricePort) {
        return new TechnicalIndicatorProcessor(stockPricePort);
    }

    @Bean
    @StepScope
    public StockListReader dailyStockPriceReader(JpaPagingItemReader<Stock> stockReader) {
        return new StockListReader(stockReader, 30);
    }

    @Bean
    @StepScope
    public JpaPagingItemReader<Stock> stockReader(
            @Value("#{jobParameters['targetTicker']}") String targetTicker
    ) {
        String query = StockPriceBatchTargetQuery.selectQuery(targetTicker);
        Map<String, Object> parameters = StockPriceBatchTargetQuery.parameters(targetTicker);

        return new JpaPagingItemReaderBuilder<Stock>()
                .name("stockReader")
                .entityManagerFactory(entityManagerFactory)
                .queryString(query)
                .parameterValues(parameters)
                .pageSize(300)
                .saveState(false)
                .build();
    }

    @Bean
    @StepScope
    public JpaPagingItemReader<Stock> technicalIndicatorStockReader() {
        return new JpaPagingItemReaderBuilder<Stock>()
                .name("technicalIndicatorStockReader")
                .entityManagerFactory(entityManagerFactory)
                .pageSize(300)
                .queryString("select s from Stock s where s.status = StockStatus.ACTIVE order by s.id asc")
                .saveState(false)
                .build();
    }

    @Bean
    public StockPriceListWriter stockPriceListWriter(JdbcBatchItemWriter<StockPrice> stockPriceJdbcWriter) {
        return new StockPriceListWriter(stockPriceJdbcWriter);
    }

    @Bean
    public JdbcBatchItemWriter<StockPrice> stockPriceJdbcWriter() {
        return new JdbcBatchItemWriterBuilder<StockPrice>()
                .dataSource(dataSource)
                .sql(StockPriceSql.UPSERT_STOCK_PRICE)
                .itemPreparedStatementSetter((item, ps) -> {
                    int idx = 1;
                    ps.setDate(idx++, DateUtil.toSqlDate(item.getId().getBaseDate()));
                    ps.setLong(idx++, item.getId().getStockId());
                    ps.setBigDecimal(idx++, item.getOpenPrice());
                    ps.setBigDecimal(idx++, item.getHighPrice());
                    ps.setBigDecimal(idx++, item.getLowPrice());
                    ps.setBigDecimal(idx++, item.getClosePrice());
                    ps.setBigDecimal(idx++, item.getAdjClosePrice());
                    ps.setBigDecimal(idx++, item.getPreviousClosePrice());
                    ps.setLong(idx++, item.getVolume());
                    ps.setBigDecimal(idx++, item.getTransactionAmt());

                    var indicators = item.getIndicators();

                    if (indicators != null) {
                        ps.setBigDecimal(idx++, indicators.getMa5());
                        ps.setBigDecimal(idx++, indicators.getMa20());
                        ps.setBigDecimal(idx++, indicators.getMa60());
                        ps.setBigDecimal(idx++, indicators.getMa120());
                        ps.setBigDecimal(idx++, indicators.getRsi14());
                        ps.setBigDecimal(idx++, indicators.getMacd());
                        ps.setBigDecimal(idx++, indicators.getMacdSignal());
                        ps.setBigDecimal(idx++, indicators.getBollingerUpper());
                        ps.setBigDecimal(idx++, indicators.getBollingerMid());
                        ps.setBigDecimal(idx++, indicators.getBollingerLower());
                        ps.setBigDecimal(idx++, indicators.getAdx());
                        ps.setBigDecimal(idx++, indicators.getPlusDi());
                        ps.setBigDecimal(idx++, indicators.getMinusDi());
                        ps.setString(idx++, indicators.getAlignmentStatus() != null ? indicators.getAlignmentStatus().name() : null);
                        ps.setObject(idx++, indicators.getIsGoldenCross());
                        ps.setObject(idx++, indicators.getIsDeadCross());
                        ps.setObject(idx++, indicators.getIsMacdCross());
                    } else {
                        for (int i = 0; i < 17; i++) {
                            ps.setNull(idx++, Types.NULL);
                        }
                    }
                })
                .assertUpdates(false)
                .build();
    }
}

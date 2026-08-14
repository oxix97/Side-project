package org.stockwellness.application.port.in.batch;

public interface BatchControlUseCase {

    BatchExecutionResult launchAsync(BatchLaunchCommand command);

    BatchExecutionResult launchSync(BatchLaunchCommand command);

    BatchExecutionResult syncIndices(MarketIndexSyncCommand command);

    String stop(BatchStopCommand command);

    String abandon(Long executionId);

    enum BatchJobType {
        STOCK_MASTER_SYNC("stockMasterSyncJob"),
        STOCK_PRICE_SYNC("stockPriceBatchJob"),
        SECTOR_EOD_SYNC("sectorEodJob"),
        STOCK_PRICE_PREV_CLOSE_SYNC("stockPricePrevCloseSyncJob"),
        PORTFOLIO_STATS_SYNC("portfolioStatsJob"),
        BENCHMARK_PRICE_SYNC("benchmarkPriceSyncJob"),
        STOCK_FOREIGN_INSTITUTION("stockInvestorTradeDetailJob"),
        MARKET_WEATHER_BACKFILL("backfillMarketWeatherJob"),
        MARKET_INDEX_SYNC("MarketIndexSync");

        private final String jobName;

        BatchJobType(String jobName) {
            this.jobName = jobName;
        }

        public String jobName() {
            return jobName;
        }
    }

    record BatchLaunchCommand(
            BatchJobType jobType,
            String targetTicker,
            String startDate,
            String endDate,
            String targetDate,
            boolean publishEvent
    ) {
    }

    record MarketIndexSyncCommand(String fileName) {
    }

    record BatchStopCommand(Long executionId) {
    }

    record BatchExecutionResult(
            Long executionId,
            String jobName,
            String status,
            String statusUrl,
            String message
    ) {
    }
}

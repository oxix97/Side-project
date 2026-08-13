package org.stockwellness.application.service.stock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.stockwellness.application.port.in.stock.StockPriceUseCase;
import org.stockwellness.application.port.in.stock.result.*;
import org.stockwellness.application.port.in.stock.result.ChartDataResponse.BenchmarkPoint;
import org.stockwellness.application.port.in.stock.result.ChartDataResponse.ChartPoint;
import org.stockwellness.application.port.out.stock.LoadBenchmarkPort;
import org.stockwellness.application.port.out.stock.StockPort;
import org.stockwellness.application.port.out.stock.StockPricePort;
import org.stockwellness.domain.stock.MarketType;
import org.stockwellness.domain.stock.Stock;
import org.stockwellness.domain.stock.exception.StockPriceException;
import org.stockwellness.domain.stock.price.ChartPeriod;
import org.stockwellness.domain.stock.price.TradeDirection;
import org.stockwellness.global.error.ErrorCode;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StockChartService implements StockPriceUseCase {

    private static final int CALC_SCALE = 8;
    private static final int DISPLAY_SCALE = 4;

    private final StockPricePort stockPricePort;
    private final LoadBenchmarkPort loadBenchmarkPort;
    private final StockPort stockPort;

    @Override
    @Cacheable(cacheNames = "stockSupplyRanking:v1", key = "#direction + '_' + #limit", unless = "#result == null")
    public StockSupplyRankingResponse getTopStocksBySupply(
            TradeDirection direction,
            int limit
    ) {
        Optional<LocalDate> effectiveDate = stockPricePort.findLatestInvestorTradeDate();
        log.info("[수급 랭킹 조회] direction={}, limit={}, effectiveDate={}", direction, limit, effectiveDate.orElse(null));

        if (effectiveDate.isEmpty()) {
            return new StockSupplyRankingResponse(null, null, List.of(), List.of());
        }

        List<StockSupplyRankingResult> institutionItems = stockPricePort.findTopInstitutionStocksBySupply(
                effectiveDate.get(),
                direction,
                limit
        );
        List<StockSupplyRankingResult> foreignItems = stockPricePort.findTopForeignStocksBySupply(
                effectiveDate.get(),
                direction,
                limit
        );
        log.info("[수급 랭킹 조회] direction={}, limit={}, effectiveDate={}, institutionCount={}, foreignCount={}",
                direction, limit, effectiveDate.get(), institutionItems.size(), foreignItems.size());

        return new StockSupplyRankingResponse(null, effectiveDate.get(), institutionItems, foreignItems);
    }

    @Override
    public ChartDataResponse loadChartData(ChartQuery query) {
        Stock stock = findStockOrThrow(query.ticker());

        LocalDate end = LocalDate.now();
        LocalDate start = query.period().calculateStartDate(end);

        List<StockPriceResult> dailyPrices = stockPricePort.loadPricesByTicker(query.ticker(), start, end);
        if (dailyPrices.isEmpty()) {
            throw new StockPriceException(ErrorCode.PRICE_DATA_NOT_FOUND);
        }

        List<ChartPoint> aggregatedPrices = switch (query.frequency()) {
            case WEEKLY -> PriceDataAggregator.aggregateToWeekly(dailyPrices);
            case MONTHLY -> PriceDataAggregator.aggregateToMonthly(dailyPrices);
            case DAILY -> dailyPrices.stream().map(this::toChartPoint).toList();
        };

        BenchmarkInfo benchmarkInfo = resolveBenchmark(stock.getMarketType());
        List<BenchmarkPoint> benchmarks = Collections.emptyList();

        if (query.includeBenchmark()) {
            try {
                List<StockPriceResult> benchmarkDaily = loadBenchmarkPort.loadBenchmarkPrices(benchmarkInfo.ticker(), start, end);
                benchmarks = calculateBenchmarkReturns(benchmarkDaily);
            } catch (Exception e) {
                log.warn("Failed to load benchmark data for {}", benchmarkInfo.ticker(), e);
            }
        }

        return new ChartDataResponse(
                query.ticker(),
                stock.getCurrency().name(),
                stock.getName(),
                benchmarkInfo.name(),
                aggregatedPrices,
                benchmarks
        );
    }

    @Override
    public ReturnRateResponse calculateReturn(String ticker, ChartPeriod period) {
        // 시장 유형에 따른 벤치마크 결정 및 종목 조회를 위해 Stock 로드
        Stock stock = findStockOrThrow(ticker);

        LocalDate end = LocalDate.now();
        LocalDate start = period.calculateStartDate(end);

        List<StockPriceResult> stockPrices = stockPricePort.loadPricesByTicker(ticker, start, end);
        if (stockPrices.isEmpty()) {
            throw new StockPriceException(ErrorCode.PRICE_DATA_NOT_FOUND);
        }

        BenchmarkInfo benchmarkInfo = resolveBenchmark(stock.getMarketType());
        List<StockPriceResult> benchmarkPrices = loadBenchmarkPort.loadBenchmarkPrices(benchmarkInfo.ticker(), start, end);

        BigDecimal stockReturn = calculateTotalReturn(stockPrices);
        BigDecimal benchmarkReturn = calculateTotalReturn(benchmarkPrices);

        return new ReturnRateResponse(ticker, stock.getCurrency().name(), period.getLabel(), stockReturn, benchmarkReturn);
    }

    private Stock findStockOrThrow(String ticker) {
        return stockPort.loadStockByTicker(ticker)
                .orElseThrow(() -> new StockPriceException(ErrorCode.STOCK_NOT_FOUND));
    }

    private record BenchmarkInfo(String ticker, String name) {
    }

    private BenchmarkInfo resolveBenchmark(MarketType marketType) {
        return switch (marketType) {
            case KOSPI, INDEX -> new BenchmarkInfo("^KS11", "KOSPI");
            case KOSDAQ -> new BenchmarkInfo("^KQ11", "KOSDAQ");
            case NASDAQ, NYSE, AMEX -> new BenchmarkInfo("^GSPC", "S&P 500");
        };
    }

    private ChartPoint toChartPoint(StockPriceResult p) {
        return new ChartPoint(
                p.baseDate(),
                p.openPrice(),
                p.highPrice(),
                p.lowPrice(),
                p.closePrice(),
                p.adjClosePrice(),
                p.volume(),
                p.transactionAmt(),
                p.ma5(),
                p.ma20(),
                p.ma60(),
                p.ma120()
        );
    }

    private List<BenchmarkPoint> calculateBenchmarkReturns(List<StockPriceResult> daily) {
        if (daily.isEmpty()) return Collections.emptyList();

        BigDecimal firstPrice = daily.getFirst().adjClosePrice();
        if (firstPrice.compareTo(BigDecimal.ZERO) == 0) return Collections.emptyList();

        return daily.stream()
                .map(p -> {
                    BigDecimal returnRate = p.adjClosePrice()
                            .subtract(firstPrice)
                            .divide(firstPrice, CALC_SCALE, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100))
                            .setScale(DISPLAY_SCALE, RoundingMode.HALF_UP);
                    return new BenchmarkPoint(p.baseDate(), returnRate);
                })
                .toList();
    }

    private BigDecimal calculateTotalReturn(List<StockPriceResult> prices) {
        if (prices.size() < 2) return BigDecimal.ZERO;

        BigDecimal startPrice = prices.getFirst().adjClosePrice();
        BigDecimal endPrice = prices.getLast().adjClosePrice();

        if (startPrice.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;

        return endPrice.subtract(startPrice)
                .divide(startPrice, CALC_SCALE, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(DISPLAY_SCALE, RoundingMode.HALF_UP);
    }
}

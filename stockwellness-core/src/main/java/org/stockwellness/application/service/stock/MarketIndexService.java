package org.stockwellness.application.service.stock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.stockwellness.adapter.out.persistence.insight.SectorIndicator;
import org.stockwellness.adapter.out.persistence.insight.repository.SectorIndicatorRepository;
import org.stockwellness.application.port.in.stock.MarketIndexUseCase;
import org.stockwellness.application.port.in.stock.result.MarketDashboardResult;
import org.stockwellness.application.port.in.stock.result.MarketIndexResult;
import org.stockwellness.application.port.in.stock.result.MarketIndexResult.HistoryPoint;
import org.stockwellness.application.port.in.stock.result.MarketWeatherResult;
import org.stockwellness.application.port.in.stock.result.StockPriceResult;
import org.stockwellness.application.port.out.stock.LoadBenchmarkPort;
import org.stockwellness.application.port.out.stock.StockPricePort;
import org.stockwellness.domain.stock.BenchmarkType;
import org.stockwellness.domain.stock.exception.StockPriceException;
import org.stockwellness.domain.stock.insight.MarketWeatherPolicy;
import org.stockwellness.domain.stock.insight.MarketWeatherScore;
import org.stockwellness.domain.stock.insight.RollingPercentileCalculator;
import org.stockwellness.global.error.ErrorCode;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MarketIndexService implements MarketIndexUseCase {

    private final LoadBenchmarkPort loadBenchmarkPort;
    private final StockPricePort stockPricePort;
    private final MarketWeatherClassifier marketWeatherClassifier;
    private final SectorIndicatorRepository sectorIndicatorRepository;

    @Override
    @Cacheable(value = "marketDashboard:v1", key = "'all'", sync = true)
    public MarketDashboardResult getMarketIndexes() {
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(30); // 대시보드용 최근 30일

        List<String> tickers = Arrays.stream(BenchmarkType.values())
                .map(BenchmarkType::getTicker)
                .toList();

        List<StockPriceResult> allPrices = loadBenchmarkPort.loadBenchmarkPricesIn(tickers, start, end);
        Map<String, List<StockPriceResult>> priceMap = allPrices.stream()
                .collect(Collectors.groupingBy(StockPriceResult::ticker));

        List<MarketIndexResult> results = new ArrayList<>();
        for (BenchmarkType type : BenchmarkType.values()) {
            List<StockPriceResult> prices = priceMap.get(type.getTicker());
            if (prices != null && !prices.isEmpty()) {
                results.add(toResult(type, prices));
            }
        }

        if (results.isEmpty()) {
            throw new StockPriceException(ErrorCode.PRICE_DATA_NOT_FOUND);
        }

        MarketWeatherResult weather = buildMarketWeather(results);
        return new MarketDashboardResult(results, weather);
    }

    private MarketIndexResult toResult(BenchmarkType type, List<StockPriceResult> prices) {
        StockPriceResult latest = prices.get(prices.size() - 1);
        BigDecimal currentPrice = latest.closePrice();
        BigDecimal fluctuationRate = latest.changeRate() != null ?
                latest.changeRate().setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        BigDecimal fluctuationAmount = BigDecimal.ZERO;
        // (단순화된 변동폭 계산 생략 - 원본 로직 참조 가능)

        List<HistoryPoint> history = prices.stream()
                .map(p -> new HistoryPoint(p.baseDate(), p.closePrice()))
                .toList();

        return new MarketIndexResult(type.getTicker(), type.getName(), currentPrice, fluctuationRate, fluctuationAmount, history);
    }

    private MarketWeatherResult buildMarketWeather(List<MarketIndexResult> indexes) {
        MarketIndexResult kospi = findRequiredIndex(indexes, BenchmarkType.KOSPI);
        LocalDate asOfDate = kospi.history().isEmpty() ? LocalDate.now() : kospi.history().get(kospi.history().size()-1).date();

        String marketCode = "0001"; // KOSPI 통합 코드
        SectorIndicator currentIndicator = sectorIndicatorRepository.findByBaseDateAndSectorCode(asOfDate, marketCode)
                .orElse(null);

        if (currentIndicator == null) {
            log.warn("⚠️ No market indicator found for {}, using neutral score", asOfDate);
            return marketWeatherClassifier.classify(new MarketWeatherScore(50, 50, 50, 50), asOfDate);
        }

        MarketWeatherPolicy policy = MarketWeatherPolicy.DEFAULT;
        List<SectorIndicator> history = sectorIndicatorRepository
                .findAllBySectorCodeAndBaseDateLessThanEqualOrderByBaseDateDesc(
                        marketCode,
                        asOfDate,
                        PageRequest.of(0, policy.rollingWindowDays())
                );

        List<BigDecimal> trendHistory = history.stream().map(SectorIndicator::getMa20Disparity).toList();
        List<BigDecimal> breadthHistory = history.stream().map(SectorIndicator::getAdr).toList();
        List<BigDecimal> momentumHistory = history.stream().map(SectorIndicator::getRsi14).toList();

        int trendScore = RollingPercentileCalculator.calculate(currentIndicator.getMa20Disparity(), trendHistory);
        int breadthScore = RollingPercentileCalculator.calculate(currentIndicator.getAdr(), breadthHistory);
        int momentumScore = RollingPercentileCalculator.calculate(currentIndicator.getRsi14(), momentumHistory);

        int integratedScore = policy.calculateIntegratedScore(trendScore, breadthScore, momentumScore);

        MarketWeatherScore score = new MarketWeatherScore(trendScore, breadthScore, momentumScore, integratedScore);

        return marketWeatherClassifier.classify(score, asOfDate);
    }

    private MarketIndexResult findRequiredIndex(List<MarketIndexResult> indexes, BenchmarkType type) {
        return indexes.stream()
                .filter(index -> type.getTicker().equals(index.ticker()))
                .findFirst()
                .orElseThrow(() -> new StockPriceException(ErrorCode.PRICE_DATA_NOT_FOUND));
    }
}

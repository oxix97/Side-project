package org.stockwellness.application.service.stock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.stockwellness.adapter.out.persistence.insight.SectorIndicator;
import org.stockwellness.adapter.out.persistence.insight.repository.SectorIndicatorRepository;
import org.stockwellness.application.port.in.stock.result.MarketDashboardResult;
import org.stockwellness.application.port.in.stock.result.MarketWeatherLevel;
import org.stockwellness.application.port.in.stock.result.StockPriceResult;
import org.stockwellness.application.port.out.stock.LoadBenchmarkPort;
import org.stockwellness.application.port.out.stock.StockPricePort;
import org.stockwellness.domain.stock.BenchmarkType;
import org.stockwellness.domain.stock.exception.StockPriceException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class MarketIndexServiceTest {

    @Mock
    private LoadBenchmarkPort loadBenchmarkPort;

    @Mock
    private StockPricePort stockPricePort;

    @Mock
    private SectorIndicatorRepository sectorIndicatorRepository;

    private MarketIndexService marketIndexService;

    @BeforeEach
    void setUp() {
        MarketWeatherFactory marketWeatherFactory = new MarketWeatherFactory();
        MarketWeatherClassifier marketWeatherClassifier = new MarketWeatherClassifier(marketWeatherFactory);
        marketIndexService = new MarketIndexService(
                loadBenchmarkPort,
                stockPricePort,
                marketWeatherClassifier,
                sectorIndicatorRepository
        );
    }

    @Test
    @DisplayName("최근 지수 데이터와 섹터 지표가 있으면 기상도를 포함한 대시보드를 반환한다")
    void getMarketIndexes_returnsMappedResult() {
        LocalDate baseDate = LocalDate.now().minusDays(1);

        // 벤치마크 데이터 모킹
        List<StockPriceResult> mockPrices = new ArrayList<>();
        for (BenchmarkType type : BenchmarkType.values()) {
            mockPrices.add(priceResult(baseDate, "1.00", type.getTicker()));
        }

        given(loadBenchmarkPort.loadBenchmarkPricesIn(anyList(), any(), any())).willReturn(mockPrices);
        
        // 섹터 지표 모킹 (KOSPI=0001)
        SectorIndicator mockIndicator = SectorIndicator.builder()
                .baseDate(baseDate)
                .sectorCode("0001")
                .ma20Disparity(BigDecimal.valueOf(105))
                .adr(BigDecimal.valueOf(120))
                .rsi14(BigDecimal.valueOf(65))
                .build();
        
        given(sectorIndicatorRepository.findByBaseDateAndSectorCode(baseDate, "0001"))
                .willReturn(Optional.of(mockIndicator));
        
        given(sectorIndicatorRepository.findAllBySectorCodeAndBaseDateLessThanEqualOrderByBaseDateDesc(
                eq("0001"),
                eq(baseDate),
                eq(PageRequest.of(0, 252))))
                .willReturn(List.of(mockIndicator));

        // Note: RollingPercentileCalculator.calculate returns 50 when history.size() < 5
        MarketDashboardResult dashboard = marketIndexService.getMarketIndexes();
        
        assertThat(dashboard.indexes()).hasSize(BenchmarkType.values().length);
        // Default integrated score for history < 5 is 50 -> CLOUDY
        assertThat(dashboard.weather().weatherLevel()).isEqualTo(MarketWeatherLevel.CLOUDY);
    }

    @Test
    @DisplayName("지수 데이터가 없으면 예외를 던진다")
    void getMarketIndexes_throwsWhenPricesMissing() {
        given(loadBenchmarkPort.loadBenchmarkPricesIn(anyList(), any(), any())).willReturn(Collections.emptyList());

        assertThatThrownBy(() -> marketIndexService.getMarketIndexes())
                .isInstanceOf(StockPriceException.class);
    }

    private StockPriceResult priceResult(LocalDate baseDate, String changeRate, String ticker) {
        return new StockPriceResult(
                baseDate,
                new BigDecimal("2500.00"),
                new BigDecimal("2530.00"),
                new BigDecimal("2490.00"),
                new BigDecimal("2525.00"),
                new BigDecimal("2525.00"),
                1000L,
                BigDecimal.ZERO,
                null, null, null, null,
                new BigDecimal(changeRate),
                ticker
        );
    }
}

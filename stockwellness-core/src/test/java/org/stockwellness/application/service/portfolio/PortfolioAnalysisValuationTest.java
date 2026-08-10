package org.stockwellness.application.service.portfolio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.stockwellness.application.port.in.portfolio.result.PortfolioValuationResult;
import org.stockwellness.application.port.in.portfolio.result.ValuationStatus;
import org.stockwellness.application.service.portfolio.internal.AnalysisContext;
import org.stockwellness.application.service.portfolio.internal.PortfolioAnalysisDataLoader;
import org.stockwellness.application.service.portfolio.internal.SimulationDataProvider;
import org.stockwellness.domain.portfolio.Portfolio;
import org.stockwellness.domain.portfolio.PortfolioItem;
import org.stockwellness.domain.portfolio.PortfolioStats;
import org.stockwellness.domain.stock.price.StockPrice;
import org.stockwellness.domain.stock.price.StockPriceId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
@DisplayName("PortfolioAnalysisService Valuation 단위 테스트")
class PortfolioAnalysisValuationTest {

    @InjectMocks
    private PortfolioAnalysisService portfolioAnalysisService;

    @Mock
    private PortfolioAnalysisDataLoader dataLoader;

    @Mock
    private SimulationDataProvider simulationDataProvider;

    @Test
    @DisplayName("포트폴리오의 실시간 가치와 성과를 계산할 수 있다")
    void calculate_portfolio_valuation() {
        // given
        Long memberId = 1L;
        Long portfolioId = 100L;
        Portfolio portfolio = Portfolio.create(memberId, "My Portfolio", "Desc");
        
        PortfolioItem stockItem = PortfolioItem.createStock("AAPL", new BigDecimal("10"), new BigDecimal("150"), "USD", BigDecimal.ZERO, LocalDate.now());
        PortfolioItem cashItem = PortfolioItem.createCash(new BigDecimal("500"), "USD", BigDecimal.ZERO, LocalDate.now());
        portfolio.updateItems(List.of(stockItem, cashItem));

        StockPrice aaplPrice = mock(StockPrice.class);
        given(aaplPrice.getClosePrice()).willReturn(new BigDecimal("160"));
        given(aaplPrice.getPreviousClosePrice()).willReturn(new BigDecimal("155"));
        
        Map<String, List<StockPrice>> priceMap = Map.of("AAPL", List.of(aaplPrice));
        PortfolioStats mockStats = PortfolioStats.create(portfolio, LocalDate.now(), BigDecimal.valueOf(15.5), BigDecimal.valueOf(1.2), BigDecimal.valueOf(0.95), BigDecimal.ZERO, BigDecimal.ZERO);

        // DataLoader 모킹 설정
        AnalysisContext context = new AnalysisContext(portfolio, Map.of(), priceMap, mockStats);
        given(dataLoader.loadContext(portfolioId, memberId)).willReturn(context);

        // when
        PortfolioValuationResult result = portfolioAnalysisService.getValuation(memberId, portfolioId);

        // then
        assertThat(result.totalPurchaseAmount()).isEqualByComparingTo(new BigDecimal("2000"));
        assertThat(result.currentTotalValue()).isEqualByComparingTo(new BigDecimal("2100"));
        assertThat(result.mdd()).isEqualByComparingTo(new BigDecimal("15.5"));
        assertThat(result.sharpeRatio()).isEqualByComparingTo(new BigDecimal("1.2"));
        assertThat(result.beta()).isEqualByComparingTo(new BigDecimal("0.95"));
        assertThat(result.valuationStatus()).isEqualTo(ValuationStatus.COMPLETE);
    }

    @Test
    @DisplayName("가격이 없으면 매수가로 대체하지 않고 평가 합계를 null로 반환한다")
    void missing_price_does_not_fallback_to_purchase_price() {
        Portfolio portfolio = Portfolio.create(1L, "Missing price", "");
        portfolio.updateItems(List.of(
                PortfolioItem.createStock("MISSING", BigDecimal.TEN, new BigDecimal("100"), "KRW", BigDecimal.valueOf(100), LocalDate.of(2026, 8, 7))
        ));
        given(dataLoader.loadContext(100L, 1L))
                .willReturn(new AnalysisContext(portfolio, Map.of(), Map.of(), null));

        PortfolioValuationResult result = portfolioAnalysisService.getValuation(1L, 100L);

        assertThat(result.valuationStatus()).isEqualTo(ValuationStatus.UNAVAILABLE);
        assertThat(result.asOfDate()).isNull();
        assertThat(result.missingSymbols()).containsExactly("MISSING");
        assertThat(result.currentTotalValue()).isNull();
        assertThat(result.totalProfitLoss()).isNull();
        assertThat(result.totalReturnRate()).isNull();
        assertThat(result.dailyProfitLoss()).isNull();
        assertThat(result.dailyReturnRate()).isNull();
    }

    @Test
    @DisplayName("휴장일에는 마지막 완료 EOD 기준일과 직전 종가를 사용한다")
    void uses_last_completed_eod_and_previous_business_day() {
        Portfolio portfolio = Portfolio.create(1L, "Holiday", "");
        portfolio.updateItems(List.of(
                PortfolioItem.createStock("AAPL", BigDecimal.TEN, new BigDecimal("100"), "KRW", BigDecimal.valueOf(100), LocalDate.of(2026, 8, 7))
        ));
        StockPrice latest = mock(StockPrice.class);
        given(latest.getId()).willReturn(new StockPriceId(LocalDate.of(2026, 8, 7), 1L));
        given(latest.getClosePrice()).willReturn(new BigDecimal("120"));
        given(latest.getPreviousClosePrice()).willReturn(new BigDecimal("110"));
        given(dataLoader.loadContext(100L, 1L))
                .willReturn(new AnalysisContext(portfolio, Map.of(), Map.of("AAPL", List.of(latest)), null));

        PortfolioValuationResult result = portfolioAnalysisService.getValuation(1L, 100L);

        assertThat(result.valuationStatus()).isEqualTo(ValuationStatus.COMPLETE);
        assertThat(result.asOfDate()).isEqualTo(LocalDate.of(2026, 8, 7));
        assertThat(result.currentTotalValue()).isEqualByComparingTo("1200");
        assertThat(result.dailyProfitLoss()).isEqualByComparingTo("100");
    }
}

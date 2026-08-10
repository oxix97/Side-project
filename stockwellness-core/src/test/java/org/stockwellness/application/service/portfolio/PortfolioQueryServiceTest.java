package org.stockwellness.application.service.portfolio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.stockwellness.application.port.in.portfolio.dto.PortfolioResponse;
import org.stockwellness.application.port.in.portfolio.result.PriceStatus;
import org.stockwellness.application.port.in.portfolio.result.ValuationStatus;
import org.stockwellness.application.port.out.portfolio.PortfolioPort;
import org.stockwellness.application.port.out.stock.StockPort;
import org.stockwellness.application.port.out.stock.StockPricePort;
import org.stockwellness.domain.portfolio.Portfolio;
import org.stockwellness.domain.portfolio.PortfolioItem;
import org.stockwellness.domain.portfolio.exception.PortfolioAccessDeniedException;
import org.stockwellness.domain.portfolio.exception.PortfolioNotFoundException;
import org.stockwellness.domain.stock.price.StockPrice;
import org.stockwellness.domain.stock.price.StockPriceId;
import org.stockwellness.fixture.PortfolioFixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("PortfolioQueryService 단위 테스트")
class PortfolioQueryServiceTest {

    @InjectMocks
    private PortfolioQueryService portfolioQueryService;

    @Mock
    private PortfolioPort portfolioPort;

    @Mock
    private StockPricePort stockPricePort;

    @Mock
    private StockPort stockPort;

    @Nested
    @DisplayName("포트폴리오 조회 (Read)")
    class Read {

        @Test
        @DisplayName("성공: 내 포트폴리오 정보를 상세 조회한다")
        void read_success() {
            // given
            Portfolio portfolio = PortfolioFixture.createEntityWithItems(
                    PortfolioFixture.PORTFOLIO_ID,
                    List.of(PortfolioItem.createStock("005930", BigDecimal.ONE, BigDecimal.valueOf(70000), "KRW"))
            );
            given(portfolioPort.loadPortfolio(PortfolioFixture.PORTFOLIO_ID, PortfolioFixture.MEMBER_ID))
                    .willReturn(Optional.of(portfolio));
            given(stockPricePort.loadRecentHistoriesBatch(ArgumentMatchers.anyList(), ArgumentMatchers.eq(1)))
                    .willReturn(Map.of());
            given(stockPort.loadStocksByTickers(ArgumentMatchers.anyList()))
                    .willReturn(List.of());

            // when
            PortfolioResponse response = portfolioQueryService.getPortfolio(PortfolioFixture.MEMBER_ID, PortfolioFixture.PORTFOLIO_ID);

            // then
            assertThat(response.id()).isEqualTo(PortfolioFixture.PORTFOLIO_ID);
            assertThat(response.name()).isEqualTo(PortfolioFixture.NAME);
            assertThat(response.items()).isNotEmpty();
            assertThat(response.items())
                    .allSatisfy(item -> assertThat(item.name()).isNotBlank());
        }

        @Test
        @DisplayName("실패: 다른 사용자의 포트폴리오를 조회하려고 하면 UNAUTHORIZED 예외가 발생한다")
        void fail_unauthorized() {
            // given
            Long otherMemberId = 999L;
            given(portfolioPort.loadPortfolio(PortfolioFixture.PORTFOLIO_ID, otherMemberId))
                    .willReturn(Optional.empty());
            given(portfolioPort.findById(PortfolioFixture.PORTFOLIO_ID))
                    .willReturn(Optional.of(PortfolioFixture.createEntity(PortfolioFixture.PORTFOLIO_ID)));

            // when & then
            assertThatThrownBy(() -> portfolioQueryService.getPortfolio(otherMemberId, PortfolioFixture.PORTFOLIO_ID))
                    .isInstanceOf(PortfolioAccessDeniedException.class);
        }

        @Test
        @DisplayName("실패: 존재하지 않는 포트폴리오를 조회하면 PORTFOLIO_NOT_FOUND 예외가 발생한다")
        void fail_not_found() {
            // given
            given(portfolioPort.loadPortfolio(PortfolioFixture.PORTFOLIO_ID, PortfolioFixture.MEMBER_ID))
                    .willReturn(Optional.empty());
            given(portfolioPort.findById(PortfolioFixture.PORTFOLIO_ID))
                    .willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> portfolioQueryService.getPortfolio(PortfolioFixture.MEMBER_ID, PortfolioFixture.PORTFOLIO_ID))
                    .isInstanceOf(PortfolioNotFoundException.class);
        }

        @Test
        @DisplayName("성공: 내 모든 포트폴리오 목록을 조회한다")
        void read_all_success() {
            // given
            Portfolio portfolio = PortfolioFixture.createEntityWithItems(
                    PortfolioFixture.PORTFOLIO_ID,
                    List.of(PortfolioItem.createStock("005930", BigDecimal.ONE, BigDecimal.valueOf(70000), "KRW"))
            );
            given(portfolioPort.loadAllPortfolios(PortfolioFixture.MEMBER_ID))
                    .willReturn(List.of(portfolio));
            given(stockPricePort.loadRecentHistoriesBatch(ArgumentMatchers.anyList(), ArgumentMatchers.eq(1)))
                    .willReturn(Map.of());
            given(stockPort.loadStocksByTickers(ArgumentMatchers.anyList()))
                    .willReturn(List.of());

            // when
            List<PortfolioResponse> responses = portfolioQueryService.getMyPortfolios(PortfolioFixture.MEMBER_ID);

            // then
            assertThat(responses).hasSize(1);
            assertThat(responses.get(0).id()).isEqualTo(PortfolioFixture.PORTFOLIO_ID);
        }

        @Test
        @DisplayName("가격 누락 상세는 0원이나 매수가 추정 대신 nullable 상태를 반환한다")
        void read_missing_price_is_explicit() {
            Portfolio portfolio = PortfolioFixture.createEntityWithItems(
                    PortfolioFixture.PORTFOLIO_ID,
                    List.of(PortfolioItem.createStock("MISSING", BigDecimal.TEN, BigDecimal.valueOf(100), "KRW"))
            );
            given(portfolioPort.loadPortfolio(PortfolioFixture.PORTFOLIO_ID, PortfolioFixture.MEMBER_ID))
                    .willReturn(Optional.of(portfolio));
            given(stockPricePort.loadRecentHistoriesBatch(ArgumentMatchers.anyList(), ArgumentMatchers.eq(1)))
                    .willReturn(Map.of());
            given(stockPort.loadStocksByTickers(ArgumentMatchers.anyList())).willReturn(List.of());

            PortfolioResponse response = portfolioQueryService.getPortfolio(PortfolioFixture.MEMBER_ID, PortfolioFixture.PORTFOLIO_ID);

            assertThat(response.valuationStatus()).isEqualTo(ValuationStatus.UNAVAILABLE);
            assertThat(response.asOfDate()).isNull();
            assertThat(response.missingSymbols()).containsExactly("MISSING");
            assertThat(response.currentTotalValue()).isNull();
            assertThat(response.totalReturnRate()).isNull();
            assertThat(response.items().getFirst().priceStatus()).isEqualTo(PriceStatus.MISSING);
            assertThat(response.items().getFirst().priceAsOfDate()).isNull();
            assertThat(response.items().getFirst().currentPrice()).isNull();
            assertThat(response.items().getFirst().currentValue()).isNull();
            assertThat(response.items().getFirst().returnRate()).isNull();
        }

        @Test
        @DisplayName("종목별 가격 기준일이 공통 기준일보다 이전이면 STALE로 표시한다")
        void read_stale_price_is_explicit() {
            Portfolio portfolio = PortfolioFixture.createEntityWithItems(
                    PortfolioFixture.PORTFOLIO_ID,
                    List.of(
                            PortfolioItem.createStock("STALE", BigDecimal.TEN, BigDecimal.valueOf(100), "KRW"),
                            PortfolioItem.createStock("LATEST", BigDecimal.TEN, BigDecimal.valueOf(100), "KRW")
                    )
            );
            StockPrice stale = org.mockito.Mockito.mock(StockPrice.class);
            org.mockito.BDDMockito.given(stale.getId()).willReturn(new StockPriceId(LocalDate.of(2026, 8, 6), 1L));
            org.mockito.BDDMockito.given(stale.getClosePrice()).willReturn(BigDecimal.valueOf(120));
            StockPrice latest = org.mockito.Mockito.mock(StockPrice.class);
            org.mockito.BDDMockito.given(latest.getId()).willReturn(new StockPriceId(LocalDate.of(2026, 8, 7), 2L));
            org.mockito.BDDMockito.given(latest.getClosePrice()).willReturn(BigDecimal.valueOf(130));
            given(portfolioPort.loadPortfolio(PortfolioFixture.PORTFOLIO_ID, PortfolioFixture.MEMBER_ID))
                    .willReturn(Optional.of(portfolio));
            given(stockPricePort.loadRecentHistoriesBatch(ArgumentMatchers.anyList(), ArgumentMatchers.eq(1)))
                    .willReturn(Map.of("STALE", List.of(stale), "LATEST", List.of(latest)));
            given(stockPort.loadStocksByTickers(ArgumentMatchers.anyList())).willReturn(List.of());

            PortfolioResponse response = portfolioQueryService.getPortfolio(PortfolioFixture.MEMBER_ID, PortfolioFixture.PORTFOLIO_ID);

            assertThat(response.valuationStatus()).isEqualTo(ValuationStatus.COMPLETE);
            assertThat(response.asOfDate()).isEqualTo(LocalDate.of(2026, 8, 7));
            assertThat(response.items().get(0).priceStatus()).isEqualTo(PriceStatus.STALE);
            assertThat(response.items().get(0).priceAsOfDate()).isEqualTo(LocalDate.of(2026, 8, 6));
            assertThat(response.items().get(0).currentValue()).isEqualByComparingTo("1200");
        }
    }
}

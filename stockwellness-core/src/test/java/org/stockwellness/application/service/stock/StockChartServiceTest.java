package org.stockwellness.application.service.stock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.stockwellness.application.port.in.stock.StockPriceUseCase.ChartQuery;
import org.stockwellness.application.port.in.stock.result.ChartDataResponse;
import org.stockwellness.application.port.in.stock.result.ReturnRateResponse;
import org.stockwellness.application.port.in.stock.result.StockPriceResult;
import org.stockwellness.application.port.in.stock.result.StockSupplyRankingResponse;
import org.stockwellness.application.port.in.stock.result.StockSupplyRankingResult;
import org.stockwellness.application.port.out.stock.LoadBenchmarkPort;
import org.stockwellness.application.port.out.stock.StockPort;
import org.stockwellness.application.port.out.stock.StockPricePort;
import org.stockwellness.domain.stock.MarketType;
import org.stockwellness.domain.stock.Currency;
import org.stockwellness.domain.stock.Stock;
import org.stockwellness.domain.stock.exception.StockPriceException;
import org.stockwellness.domain.stock.price.ChartFrequency;
import org.stockwellness.domain.stock.price.ChartPeriod;
import org.stockwellness.domain.stock.price.TradeDirection;
import org.stockwellness.global.error.ErrorCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class StockChartServiceTest {

    private static final int DEFAULT_LIMIT = 10;

    @Mock
    private StockPricePort stockPricePort;
    @Mock
    private LoadBenchmarkPort loadBenchmarkPort;
    @Mock
    private StockPort stockPort;
    @Mock
    private Stock mockStock;

    @InjectMocks
    private StockChartService stockChartService;

    private final String ticker = "AAPL";

    @Nested
    @DisplayName("수급 랭킹 조회")
    class GetTopStocksBySupply {

        @Test
        @DisplayName("성공: 전체 데이터 중 가장 최신 날짜를 찾아 해당일의 수급 상위 종목을 반환한다")
        void success() {
            LocalDate latestDate = LocalDate.of(2026, 4, 8);
            List<StockSupplyRankingResult> institutionResult = List.of(
                    new StockSupplyRankingResult("005930", "삼성전자", "반도체", BigDecimal.valueOf(71000), BigDecimal.valueOf(1.43), 10L, BigDecimal.TEN, BigDecimal.ONE)
            );
            List<StockSupplyRankingResult> foreignResult = List.of(
                    new StockSupplyRankingResult("000660", "SK하이닉스", "반도체", BigDecimal.valueOf(202000), BigDecimal.valueOf(-0.98), 20L, BigDecimal.valueOf(20), BigDecimal.TWO)
            );

            given(stockPricePort.findLatestInvestorTradeDate()).willReturn(Optional.of(latestDate));
            given(stockPricePort.findTopInstitutionStocksBySupply(latestDate, TradeDirection.BUY, DEFAULT_LIMIT))
                    .willReturn(institutionResult);
            given(stockPricePort.findTopForeignStocksBySupply(latestDate, TradeDirection.BUY, DEFAULT_LIMIT))
                    .willReturn(foreignResult);

            StockSupplyRankingResponse result = stockChartService.getTopStocksBySupply(
                    TradeDirection.BUY, DEFAULT_LIMIT
            );

            assertThat(result.requestedDate()).isNull();
            assertThat(result.effectiveDate()).isEqualTo(latestDate);
            assertThat(result.institutionItems()).isEqualTo(institutionResult);
            assertThat(result.foreignItems()).isEqualTo(foreignResult);
        }

        @Test
        @DisplayName("최신 날짜에 데이터가 없어도 해당 날짜 기준으로 빈 리스트를 반환한다")
        void returnsEmptyListsWhenLatestDateHasNoData() {
            LocalDate latestDate = LocalDate.of(2026, 4, 1);

            given(stockPricePort.findLatestInvestorTradeDate()).willReturn(Optional.of(latestDate));
            given(stockPricePort.findTopInstitutionStocksBySupply(latestDate, TradeDirection.SELL, DEFAULT_LIMIT))
                    .willReturn(List.of());
            given(stockPricePort.findTopForeignStocksBySupply(latestDate, TradeDirection.SELL, DEFAULT_LIMIT))
                    .willReturn(List.of());

            StockSupplyRankingResponse result = stockChartService.getTopStocksBySupply(
                    TradeDirection.SELL, DEFAULT_LIMIT
            );

            assertThat(result.effectiveDate()).isEqualTo(latestDate);
            assertThat(result.institutionItems()).isEmpty();
            assertThat(result.foreignItems()).isEmpty();
        }

        @Test
        @DisplayName("데이터가 아예 없으면 effectiveDate 없이 빈 리스트를 반환한다")
        void returnsEmptyWhenNoDataExists() {
            given(stockPricePort.findLatestInvestorTradeDate()).willReturn(Optional.empty());

            StockSupplyRankingResponse result = stockChartService.getTopStocksBySupply(
                    TradeDirection.BUY, DEFAULT_LIMIT
            );

            assertThat(result.effectiveDate()).isNull();
            assertThat(result.institutionItems()).isEmpty();
            assertThat(result.foreignItems()).isEmpty();
        }
    }

    @Nested
    @DisplayName("차트 데이터 조회")
    class LoadChartData {

        @Test
        @DisplayName("성공: 유효한 티커와 기간으로 차트 데이터를 조회한다")
        void success() {
            // given
            ChartQuery query = new ChartQuery(ticker, ChartPeriod.ONE_YEAR, ChartFrequency.DAILY, true);
            given(mockStock.getMarketType()).willReturn(MarketType.KOSPI);
            given(mockStock.getCurrency()).willReturn(Currency.USD);
            given(mockStock.getName()).willReturn("애플");
            given(stockPort.loadStockByTicker(ticker)).willReturn(Optional.of(mockStock));

            List<StockPriceResult> mockPrices = List.of(
                    createPrice("2024-01-01", 100),
                    createPrice("2024-01-02", 110)
            );
            given(stockPricePort.loadPricesByTicker(eq(ticker), any(), any())).willReturn(mockPrices);
            
            List<StockPriceResult> mockBenchmarks = List.of(
                    createPrice("2024-01-01", 2000),
                    createPrice("2024-01-02", 2100)
            );
            given(loadBenchmarkPort.loadBenchmarkPrices(eq("^KS11"), any(), any())).willReturn(mockBenchmarks);

            // when
            ChartDataResponse response = stockChartService.loadChartData(query);

            // then
            assertThat(response.ticker()).isEqualTo(ticker);
            assertThat(response.currency()).isEqualTo("USD");
            assertThat(response.prices()).hasSize(2);
            assertThat(response.benchmarks()).hasSize(2);
            assertThat(response.benchmarks().get(1).returnRate()).isEqualByComparingTo("5.0"); // (2100-2000)/2000 * 100
        }

        @Test
        @DisplayName("실패: 존재하지 않는 종목인 경우 예외가 발생한다")
        void failStockNotFound() {
            // given
            ChartQuery query = new ChartQuery("UNKNOWN", ChartPeriod.ONE_YEAR, ChartFrequency.DAILY, false);
            given(stockPort.loadStockByTicker("UNKNOWN")).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> stockChartService.loadChartData(query))
                    .isInstanceOf(StockPriceException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.STOCK_NOT_FOUND);
        }

        @Test
        @DisplayName("실패: 해당 기간의 시세 데이터가 없는 경우 예외가 발생한다")
        void failNoPriceData() {
            // given
            ChartQuery query = new ChartQuery(ticker, ChartPeriod.ONE_YEAR, ChartFrequency.DAILY, false);
            given(stockPort.loadStockByTicker(ticker)).willReturn(Optional.of(mockStock));
            given(stockPricePort.loadPricesByTicker(eq(ticker), any(), any())).willReturn(List.of());

            // when & then
            assertThatThrownBy(() -> stockChartService.loadChartData(query))
                    .isInstanceOf(StockPriceException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PRICE_DATA_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("수익률 계산")
    class CalculateReturn {

        @Test
        @DisplayName("성공: 종목 및 벤치마크 수익률을 계산한다")
        void success() {
            // given
            given(mockStock.getMarketType()).willReturn(MarketType.KOSPI);
            given(mockStock.getCurrency()).willReturn(Currency.USD);
            given(stockPort.loadStockByTicker(ticker)).willReturn(Optional.of(mockStock));

            List<StockPriceResult> mockPrices = List.of(
                    createPrice("2024-01-01", 100),
                    createPrice("2024-01-02", 150)
            );
            given(stockPricePort.loadPricesByTicker(eq(ticker), any(), any())).willReturn(mockPrices);

            List<StockPriceResult> mockBenchmarks = List.of(
                    createPrice("2024-01-01", 2000),
                    createPrice("2024-01-02", 2200)
            );
            given(loadBenchmarkPort.loadBenchmarkPrices(eq("^KS11"), any(), any())).willReturn(mockBenchmarks);

            // when
            ReturnRateResponse response = stockChartService.calculateReturn(ticker, ChartPeriod.ONE_YEAR);

            // then
            assertThat(response.currency()).isEqualTo("USD");
            assertThat(response.stockReturnRate()).isEqualByComparingTo("50.0");
            assertThat(response.benchmarkReturnRate()).isEqualByComparingTo("10.0");
        }

        @Test
        @DisplayName("실패: 수익률 조회 기간에 시세가 없으면 S002를 반환한다")
        void failNoPriceData() {
            given(stockPort.loadStockByTicker(ticker)).willReturn(Optional.of(mockStock));
            given(stockPricePort.loadPricesByTicker(eq(ticker), any(), any())).willReturn(List.of());

            assertThatThrownBy(() -> stockChartService.calculateReturn(ticker, ChartPeriod.ONE_YEAR))
                    .isInstanceOf(StockPriceException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PRICE_DATA_NOT_FOUND);
        }
    }

    private StockPriceResult createPrice(String date, double adjClose) {
        return new StockPriceResult(
                LocalDate.parse(date),
                BigDecimal.valueOf(adjClose),
                BigDecimal.valueOf(adjClose),
                BigDecimal.valueOf(adjClose),
                BigDecimal.valueOf(adjClose),
                BigDecimal.valueOf(adjClose),
                100L,
                null, null, null, null, null
        );
    }
}

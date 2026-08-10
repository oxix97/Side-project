package org.stockwellness.application.service.portfolio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.stockwellness.application.port.in.portfolio.AiAdvisorUseCase;
import org.stockwellness.application.port.in.portfolio.command.BacktestPortfolioCommand;
import org.stockwellness.application.port.in.portfolio.result.PortfolioAnalysisSummaryResult;
import org.stockwellness.application.port.in.portfolio.result.PortfolioDiversificationResult;
import org.stockwellness.application.port.in.portfolio.result.PortfolioInceptionChartResult;
import org.stockwellness.application.port.in.portfolio.result.PortfolioRebalancingResult;
import org.stockwellness.application.port.in.portfolio.result.PortfolioValuationResult;
import org.stockwellness.application.port.in.stock.result.StockPriceResult;
import org.stockwellness.application.port.out.portfolio.PortfolioPort;
import org.stockwellness.application.port.out.stock.BenchmarkPricePort;
import org.stockwellness.application.port.out.stock.LoadBenchmarkPort;
import org.stockwellness.application.port.out.stock.StockPort;
import org.stockwellness.application.port.out.stock.StockPricePort;
import org.stockwellness.application.service.portfolio.internal.*;
import org.stockwellness.domain.portfolio.Portfolio;
import org.stockwellness.domain.portfolio.PortfolioItem;
import org.stockwellness.domain.portfolio.PortfolioStats;
import org.stockwellness.domain.portfolio.RebalancingPeriod;
import org.stockwellness.domain.stock.BenchmarkType;
import org.stockwellness.domain.stock.Stock;
import org.stockwellness.domain.stock.price.ChartPeriod;
import org.stockwellness.domain.stock.price.StockPrice;
import org.stockwellness.fixture.StockFixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PortfolioAnalysisServiceTest {

    @InjectMocks
    private PortfolioAnalysisService portfolioAnalysisService;

    @Mock
    private PortfolioAnalysisDataLoader dataLoader;

    @Mock
    private SimulationDataProvider simulationDataProvider;

    @Mock
    private PortfolioPort portfolioPort;

    @Mock
    private StockPort stockPort;

    @Mock
    private StockPricePort stockPricePort;

    @Mock
    private BenchmarkPricePort benchmarkPricePort;

    @Mock
    private LoadBenchmarkPort loadBenchmarkPort;

    @Mock
    private BacktestEngine backtestEngine;

    @Mock
    private PortfolioCorrelationCalculator correlationCalculator;

    @Mock
    private AiAdvisorUseCase aiAdvisorUseCase;

    private static final Long MEMBER_ID = 1L;
    private static final Long PORTFOLIO_ID = 100L;

    @Test
    @DisplayName("포트폴리오 가치 평가: 주식과 현금이 포함된 포트폴리오의 총 평가액 및 수익률을 계산한다")
    void getValuation_Success() {
        // given
        Portfolio portfolio = Portfolio.create(MEMBER_ID, "테스트 포트폴리오", "설명");
        
        // 목표 비중 합계를 100%로 맞춤 (또는 모두 0%)
        PortfolioItem samsung = PortfolioItem.createStock("005930", BigDecimal.valueOf(10), BigDecimal.valueOf(50000), "KRW", BigDecimal.valueOf(100), LocalDate.now());
        PortfolioItem cash = PortfolioItem.createCash(BigDecimal.valueOf(100000), "KRW", BigDecimal.ZERO, LocalDate.now());
        portfolio.updateItems(List.of(samsung, cash));

        StockPrice samsungPrice = createStockPrice("005930", 60000, 58000);
        AnalysisContext context = new AnalysisContext(
                portfolio,
                Map.of(),
                Map.of("005930", List.of(samsungPrice)),
                PortfolioStats.create(portfolio, LocalDate.now(), BigDecimal.valueOf(10), BigDecimal.valueOf(1.5), BigDecimal.valueOf(1.1), BigDecimal.ZERO, BigDecimal.ZERO)
        );

        given(dataLoader.loadContext(PORTFOLIO_ID, MEMBER_ID)).willReturn(context);

        // when
        PortfolioValuationResult result = portfolioAnalysisService.getValuation(MEMBER_ID, PORTFOLIO_ID);

        // then
        assertThat(result.totalPurchaseAmount()).isEqualByComparingTo("600000");
        assertThat(result.currentTotalValue()).isEqualByComparingTo("700000");
        assertThat(result.totalProfitLoss()).isEqualByComparingTo("100000");
        assertThat(result.dailyProfitLoss()).isEqualByComparingTo("20000");
    }

    @Test
    @DisplayName("포트폴리오 분산도 분석: 자산군, 업종, 국가별 비중을 계산한다")
    void getDiversification_Success() {
        // given
        Portfolio portfolio = Portfolio.create(MEMBER_ID, "테스트 포트폴리오", "설명");
        // 비중 합계 100% (50 + 50)
        PortfolioItem samsung = PortfolioItem.createStock("005930", BigDecimal.valueOf(10), BigDecimal.valueOf(50000), "KRW", BigDecimal.valueOf(50), LocalDate.now());
        PortfolioItem cash = PortfolioItem.createCash(BigDecimal.valueOf(500000), "KRW", BigDecimal.valueOf(50), LocalDate.now());
        portfolio.updateItems(List.of(samsung, cash));

        StockPrice samsungPrice = createStockPrice("005930", 50000, 50000);
        AnalysisContext context = new AnalysisContext(
                portfolio,
                Map.of("005930", StockFixture.createSamsung()),
                Map.of("005930", List.of(samsungPrice)),
                null
        );

        given(dataLoader.loadContext(PORTFOLIO_ID, MEMBER_ID)).willReturn(context);

        // when
        PortfolioDiversificationResult result = portfolioAnalysisService.getDiversification(MEMBER_ID, PORTFOLIO_ID);

        // then
        assertThat(result.totalValue()).isEqualByComparingTo("1000000");
        assertThat(result.assetRatios().get("STOCK")).isEqualByComparingTo("50");
        assertThat(result.assetRatios().get("CASH")).isEqualByComparingTo("50");
        assertThat(result.countryRatios().get("KR")).isEqualByComparingTo("100");
        assertThat(result.sectorRatios().get("전기전자")).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("리밸런싱 가이드: 목표 비중과 현재가를 기준으로 추천 매매 수량을 산출한다")
    void getRebalancingGuide_Success() {
        // given
        Portfolio portfolio = Portfolio.create(MEMBER_ID, "테스트 포트폴리오", "설명");
        // 비중 합계 100% (60 + 40)
        PortfolioItem samsung = PortfolioItem.createStock("005930", BigDecimal.valueOf(10), BigDecimal.valueOf(50000), "KRW", BigDecimal.valueOf(60), LocalDate.now());
        PortfolioItem cash = PortfolioItem.createCash(BigDecimal.valueOf(500000), "KRW", BigDecimal.valueOf(40), LocalDate.now());
        portfolio.updateItems(List.of(samsung, cash));

        StockPrice samsungPrice = createStockPrice("005930", 50000, 50000);
        AnalysisContext context = new AnalysisContext(portfolio, Map.of(), Map.of("005930", List.of(samsungPrice)), null);
        given(dataLoader.loadContext(PORTFOLIO_ID, MEMBER_ID)).willReturn(context);

        // when
        PortfolioRebalancingResult result = portfolioAnalysisService.getRebalancingGuide(MEMBER_ID, PORTFOLIO_ID);

        // then
        PortfolioRebalancingResult.RebalancingItem samsungGuide = result.items().stream()
                .filter(i -> i.symbol().equals("005930"))
                .findFirst().orElseThrow();
        
        assertThat(samsungGuide.recommendedQuantity()).isEqualByComparingTo("2");
        assertThat(samsungGuide.currentWeight()).isEqualByComparingTo("50");
        assertThat(samsungGuide.targetWeight()).isEqualByComparingTo("60");
    }

    @Test
    @DisplayName("백테스팅 실행: 선택한 전략에 따라 백테스팅 엔진을 호출하고 결과를 반환한다")
    void runBacktest_Success() {
        // given
        List<String> benchmarkTickers = List.of("SPX", "0001", "1001");
        BacktestPortfolioCommand command = new BacktestPortfolioCommand(MEMBER_ID, PORTFOLIO_ID, "LUMP_SUM", BigDecimal.valueOf(10000000), benchmarkTickers, ChartPeriod.ONE_YEAR, true, RebalancingPeriod.MONTHLY, Map.of());
        
        Portfolio portfolio = Portfolio.create(MEMBER_ID, "테스트", "설명");
        portfolio.updateItems(List.of(
                PortfolioItem.createStock("005930", BigDecimal.ONE, BigDecimal.valueOf(50000), "KRW", BigDecimal.valueOf(50), LocalDate.now()),
                PortfolioItem.createCash(BigDecimal.valueOf(100000), "KRW", BigDecimal.valueOf(50), LocalDate.now())
        ));
        AnalysisContext context = new AnalysisContext(portfolio, Map.of(), Map.of(), null);
        
        given(dataLoader.loadContext(PORTFOLIO_ID, MEMBER_ID)).willReturn(context);
        given(simulationDataProvider.loadData(anyList(), anyList(), any(), any())).willReturn(new SimulationData(Map.of(), Map.of()));
        
        BacktestResult mockEngineResult = new BacktestResult(
                Collections.emptyList(), BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, Map.of(), Collections.emptyList(), "AI 조언입니다.");
        given(backtestEngine.runLumpSum(any(), anyMap(), any(), any(RebalancingPeriod.class), anyString(), any(), anyBoolean())).willReturn(mockEngineResult);
        given(aiAdvisorUseCase.generateBacktestAdvice(any(), anyString(), anyString())).willReturn("AI 조언입니다.");

        // when
        BacktestResult result = portfolioAnalysisService.runBacktest(command);

        // then
        assertThat(result.aiComment()).isEqualTo("AI 조언입니다.");
        assertThat(result.cagr()).isEqualByComparingTo("10");
        verify(simulationDataProvider).loadData(anyList(), eq(benchmarkTickers), any(), any());
    }

    @Test
    @DisplayName("DCA 백테스팅: CAGR은 null로 두고 XIRR/TWR을 보존한다")
    void runBacktest_DcaPreservesXirr() {
        List<String> benchmarkTickers = List.of("SPX", "0001", "1001");
        BacktestPortfolioCommand command = new BacktestPortfolioCommand(
                MEMBER_ID,
                PORTFOLIO_ID,
                "DCA",
                BigDecimal.valueOf(100000),
                benchmarkTickers,
                ChartPeriod.ONE_YEAR,
                true,
                RebalancingPeriod.NONE,
                Map.of()
        );

        Portfolio portfolio = Portfolio.create(MEMBER_ID, "테스트", "설명");
        portfolio.updateItems(List.of(PortfolioItem.createStock(
                "005930", BigDecimal.ONE, BigDecimal.valueOf(50000), "KRW", BigDecimal.valueOf(100), LocalDate.now())));
        AnalysisContext context = new AnalysisContext(portfolio, Map.of(), Map.of(), null);
        given(dataLoader.loadContext(PORTFOLIO_ID, MEMBER_ID)).willReturn(context);
        given(simulationDataProvider.loadData(anyList(), eq(benchmarkTickers), any(), any()))
                .willReturn(new SimulationData(Map.of(), Map.of()));

        BacktestResult dcaResult = new BacktestResult(
                Collections.emptyList(),
                null,
                BigDecimal.valueOf(-8),
                BigDecimal.valueOf(-3),
                BigDecimal.valueOf(1.2),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(2),
                BigDecimal.valueOf(0.9),
                BigDecimal.valueOf(15),
                BigDecimal.valueOf(-4),
                Map.of(),
                Collections.emptyList(),
                "DCA advice",
                BigDecimal.valueOf(12),
                BigDecimal.valueOf(10),
                "DCA_XIRR_TWR",
                BigDecimal.valueOf(1.4),
                20L
        );
        given(backtestEngine.runDCA(any(), anyMap(), any(), any(RebalancingPeriod.class), anyString(), any(), anyBoolean()))
                .willReturn(dcaResult);
        given(aiAdvisorUseCase.generateBacktestAdvice(any(), anyString(), anyString())).willReturn("DCA advice");

        BacktestResult result = portfolioAnalysisService.runBacktest(command);

        assertThat(result.cagr()).isNull();
        assertThat(result.xirr()).isEqualByComparingTo("12");
        assertThat(result.timeWeightedReturnRate()).isEqualByComparingTo("10");
        assertThat(result.calculationMethod()).isEqualTo("DCA_XIRR_TWR");
    }

    @Test
    @DisplayName("상관계수 행렬: 종목이 2개 이상일 때 상관관계 계산기를 호출한다")
    void getCorrelationMatrix_Success() {
        // given
        Portfolio portfolio = Portfolio.create(MEMBER_ID, "테스트", "설명");
        // 비중 합계 100% (50 + 50)
        portfolio.updateItems(List.of(
                PortfolioItem.createStock("005930", BigDecimal.ONE, BigDecimal.valueOf(50000), "KRW", BigDecimal.valueOf(50), LocalDate.now()),
                PortfolioItem.createStock("000660", BigDecimal.ONE, BigDecimal.valueOf(100000), "KRW", BigDecimal.valueOf(50), LocalDate.now())
        ));
        AnalysisContext context = new AnalysisContext(portfolio, Map.of(), Map.of(), null);
        
        given(dataLoader.loadContext(PORTFOLIO_ID, MEMBER_ID)).willReturn(context);
        given(simulationDataProvider.loadData(anyList(), any(), any(), any())).willReturn(new SimulationData(Map.of("005930", List.of(), "000660", List.of()), Map.of()));
        given(correlationCalculator.calculateMatrix(anyMap())).willReturn(Map.of("005930", Map.of("000660", BigDecimal.valueOf(0.8))));

        // when
        Map<String, Map<String, BigDecimal>> matrix = portfolioAnalysisService.getCorrelationMatrix(MEMBER_ID, PORTFOLIO_ID);

        // then
        assertThat(matrix.get("005930").get("000660")).isEqualByComparingTo("0.8");
    }

    @Test
    @DisplayName("통합 요약 분석: 지정된 기간 동안의 성과 지표와 수익 기여도를 계산한다")
    void getAnalysisSummary_Success() {
        // given
        Portfolio portfolio = Portfolio.create(MEMBER_ID, "테스트", "설명");
        portfolio.updateItems(List.of(
                PortfolioItem.createStock("005930", BigDecimal.valueOf(10), BigDecimal.valueOf(50000), "KRW", BigDecimal.valueOf(100), LocalDate.now())
        ));
        
        StockPrice samsungPrice = createStockPrice("005930", 60000, 58000);
        AnalysisContext context = new AnalysisContext(portfolio, Map.of(), Map.of("005930", List.of(samsungPrice)), null);
        
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusMonths(12);

        given(dataLoader.loadContext(PORTFOLIO_ID, MEMBER_ID)).willReturn(context);
        given(simulationDataProvider.loadData(anyList(), anyList(), eq(start), eq(end)))
                .willReturn(new SimulationData(Map.of(), Map.of()));

        BacktestResult mockPerf = new BacktestResult(
                Collections.emptyList(), BigDecimal.valueOf(15.5), BigDecimal.valueOf(5.2), BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.valueOf(20.0), BigDecimal.valueOf(8.4), BigDecimal.valueOf(3.2),
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, Map.of(), Collections.emptyList(), "Advice");
        
        given(backtestEngine.runLumpSum(any(), anyMap(), any(), any(), anyString(), any(), anyBoolean())).willReturn(mockPerf);

        // when
        PortfolioAnalysisSummaryResult result = portfolioAnalysisService.getAnalysisSummary(MEMBER_ID, PORTFOLIO_ID, start, end);

        // then
        assertThat(result.valuation().cagr()).isEqualByComparingTo("15.5");
        assertThat(result.valuation().volatility()).isEqualByComparingTo("8.4");
        assertThat(result.valuation().alpha()).isEqualByComparingTo("3.2");
        assertThat(result.itemContributions().get("005930")).isNotNull();
    }

    @Test
    @DisplayName("생성 시점 차트: 포트폴리오와 기본 비교군의 누적 수익률 시계열을 반환한다")
    void getInceptionChart_Success() {
        Portfolio portfolio = Portfolio.create(MEMBER_ID, "테스트", "설명");
        LocalDate inception = LocalDate.of(2026, 4, 1);
        portfolio.updateItems(List.of(
                PortfolioItem.createStock("005930", BigDecimal.valueOf(10), BigDecimal.valueOf(70000), "KRW", BigDecimal.valueOf(100), inception)
        ));

        given(portfolioPort.loadPortfolio(PORTFOLIO_ID, MEMBER_ID)).willReturn(Optional.of(portfolio));
        given(stockPricePort.loadPricesByTickers(List.of("005930"), inception, LocalDate.now()))
                .willReturn(Map.of(
                        "005930", List.of(
                                new StockPriceResult(inception, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(70000), BigDecimal.valueOf(70000), 0L, BigDecimal.ZERO, null, null, null, null),
                                new StockPriceResult(inception.plusDays(1), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(77000), BigDecimal.valueOf(77000), 0L, BigDecimal.ZERO, null, null, null, null)
                        )
                ));
        for (BenchmarkType benchmark : BenchmarkType.defaultSimulationBenchmarks()) {
            given(loadBenchmarkPort.loadBenchmarkPrices(eq(benchmark.getTicker()), eq(inception), any(LocalDate.class)))
                    .willReturn(List.of(
                            new StockPriceResult(inception, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(100), BigDecimal.valueOf(100), 0L, BigDecimal.ZERO, null, null, null, null),
                            new StockPriceResult(inception.plusDays(1), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(110), BigDecimal.valueOf(110), 0L, BigDecimal.ZERO, null, null, null, null)
                    ));
        }

        PortfolioInceptionChartResult result = portfolioAnalysisService.getInceptionChart(MEMBER_ID, PORTFOLIO_ID);

        assertThat(result.portfolioInceptionDate()).isEqualTo(inception);
        assertThat(result.dailyResults()).hasSize(2);
        assertThat(result.dailyResults().get(1).portfolioReturnRate()).isEqualByComparingTo("10");
        assertThat(result.comparisons()).hasSize(4);
        assertThat(result.comparisons().get(0).ticker()).isEqualTo(BenchmarkType.KOSPI_200.getTicker());
        assertThat(result.comparisons().get(0).totalReturn()).isEqualByComparingTo("10");
    }

    private StockPrice createStockPrice(String symbol, long close, long prevClose) {
        Stock stock = mock(Stock.class);
        given(stock.getId()).willReturn(1L);
        return StockPrice.of(
                stock,
                LocalDate.now(),
                BigDecimal.valueOf(close),
                BigDecimal.valueOf(close),
                BigDecimal.valueOf(close),
                BigDecimal.valueOf(close),
                BigDecimal.valueOf(close),
                BigDecimal.valueOf(prevClose),
                100L,
                BigDecimal.valueOf(10000),
                null
        );
    }
}

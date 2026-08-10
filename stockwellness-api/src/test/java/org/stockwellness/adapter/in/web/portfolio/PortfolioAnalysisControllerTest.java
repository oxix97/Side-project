package org.stockwellness.adapter.in.web.portfolio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.epages.restdocs.apispec.ResourceSnippetParameters;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.stockwellness.adapter.in.web.portfolio.dto.*;
import org.stockwellness.application.port.in.portfolio.command.BacktestPortfolioCommand;
import org.stockwellness.application.port.in.portfolio.result.PortfolioAnalysisSummaryResult;
import org.stockwellness.application.port.in.portfolio.result.PortfolioDiversificationResult;
import org.stockwellness.application.port.in.portfolio.result.PortfolioInceptionChartResult;
import org.stockwellness.application.port.in.portfolio.result.PortfolioRebalancingResult;
import org.stockwellness.application.port.in.portfolio.result.PortfolioValuationResult;
import org.stockwellness.application.port.in.portfolio.result.ValuationStatus;
import org.stockwellness.application.service.portfolio.PortfolioFacade;
import org.stockwellness.application.service.portfolio.internal.BacktestResult;
import org.stockwellness.domain.stock.price.ChartPeriod;
import org.stockwellness.domain.portfolio.RebalancingPeriod;
import org.stockwellness.domain.stock.BenchmarkType;
import org.stockwellness.global.error.ErrorCode;
import org.stockwellness.global.error.exception.GlobalException;
import org.stockwellness.support.RestDocsSupport;
import org.stockwellness.support.annotation.MockMember;
import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.ResourceDocumentation.resource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.subsectionWithPath;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Portfolio 분석 API 통합 테스트")
class PortfolioAnalysisControllerTest extends RestDocsSupport {

    @MockitoBean
    private PortfolioFacade portfolioFacade;

    @Test
    @MockMember(id = 1L)
    @DisplayName("자산가치: 포트폴리오의 현재 총 가치와 수익률을 조회한다")
    void get_value() throws Exception {
        // given
        PortfolioValuationResult result = new PortfolioValuationResult(
                BigDecimal.valueOf(1350000), BigDecimal.valueOf(1500000), BigDecimal.valueOf(150000),
                BigDecimal.valueOf(12.5), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.valueOf(15.5), BigDecimal.valueOf(12.0), BigDecimal.valueOf(3.5),
                BigDecimal.valueOf(10.2), BigDecimal.valueOf(1.5), BigDecimal.valueOf(1.1),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
        );
        given(portfolioFacade.getValuation(1L, 100L)).willReturn(result);

        // when & then
        List<FieldDescriptor> responseFields = new ArrayList<>(commonResponseFields());
        responseFields.addAll(List.of(
                fieldWithPath("data.totalPurchaseAmount").description("총 매수 금액"),
                fieldWithPath("data.currentTotalValue").description("현재 총 자산 가치"),
                fieldWithPath("data.totalProfitLoss").description("총 평가 손익"),
                fieldWithPath("data.totalReturnRate").description("총 수익률 (%)"),
                fieldWithPath("data.dailyProfitLoss").description("당일 평가 손익"),
                fieldWithPath("data.dailyReturnRate").description("당일 수익률 (%)"),
                fieldWithPath("data.cagr").description("연평균 성장률 (CAGR)"),
                fieldWithPath("data.volatility").description("연간 변동성"),
                fieldWithPath("data.alpha").description("초과 수익률 (Alpha)"),
                fieldWithPath("data.mdd").description("최대 낙폭 (MDD)"),
                fieldWithPath("data.sharpeRatio").description("샤프 지수"),
                fieldWithPath("data.beta").description("베타 계수"),
                fieldWithPath("data.totalInstitutionalNetBuying").description("총 기관 순매수 금액"),
                fieldWithPath("data.totalForeignNetBuying").description("총 외국인 순매수 금액"),
                fieldWithPath("data.totalPersonNetBuying").description("총 개인 순매수 금액"),
                fieldWithPath("data.valuationStatus").description("평가 상태 (COMPLETE, PARTIAL, UNAVAILABLE)"),
                fieldWithPath("data.asOfDate").description("평가에 사용한 마지막 완료 EOD 기준일"),
                fieldWithPath("data.missingSymbols").description("가격 누락 종목 코드 목록")
        ));

        mockMvc.perform(get("/api/v1/portfolios/{portfolioId}/analysis/valuation", 100L)
                        .header("Authorization", "Bearer {ACCESS_TOKEN}")
                        .contentType(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andDo(document("portfolio-analysis-value",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Portfolio Analysis")
                                .summary("포트폴리오 자산 가치 분석")
                                .pathParameters(
                                        parameterWithName("portfolioId").description("포트폴리오 ID")
                                )
                                .responseFields(responseFields)
                                .build())
                ));
    }

    @Test
    @MockMember(id = 1L)
    @DisplayName("자산가치: 가격 누락 시 합계 금융 수치를 null과 상태로 반환한다")
    void get_value_partial() throws Exception {
        PortfolioValuationResult result = new PortfolioValuationResult(
                BigDecimal.valueOf(1350000), null, null, null, null, null,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                ValuationStatus.PARTIAL, LocalDate.of(2026, 8, 7), List.of("005930")
        );
        given(portfolioFacade.getValuation(1L, 100L)).willReturn(result);

        List<FieldDescriptor> responseFields = new ArrayList<>(commonResponseFields());
        responseFields.addAll(List.of(
                fieldWithPath("data.totalPurchaseAmount").description("총 매수 금액"),
                fieldWithPath("data.currentTotalValue").description("가격 누락 시 null인 현재 총 자산 가치"),
                fieldWithPath("data.totalProfitLoss").description("가격 누락 시 null인 총 평가 손익"),
                fieldWithPath("data.totalReturnRate").description("가격 누락 시 null인 총 수익률 (%)"),
                fieldWithPath("data.dailyProfitLoss").description("가격 누락 시 null인 당일 평가 손익"),
                fieldWithPath("data.dailyReturnRate").description("가격 누락 시 null인 당일 수익률 (%)"),
                fieldWithPath("data.cagr").description("연평균 성장률 (CAGR)"),
                fieldWithPath("data.volatility").description("연간 변동성"),
                fieldWithPath("data.alpha").description("초과 수익률 (Alpha)"),
                fieldWithPath("data.mdd").description("최대 낙폭 (MDD)"),
                fieldWithPath("data.sharpeRatio").description("샤프 지수"),
                fieldWithPath("data.beta").description("베타 계수"),
                fieldWithPath("data.totalInstitutionalNetBuying").description("총 기관 순매수 금액"),
                fieldWithPath("data.totalForeignNetBuying").description("총 외국인 순매수 금액"),
                fieldWithPath("data.totalPersonNetBuying").description("총 개인 순매수 금액"),
                fieldWithPath("data.valuationStatus").description("평가 상태 (COMPLETE, PARTIAL, UNAVAILABLE)"),
                fieldWithPath("data.asOfDate").description("평가에 사용한 마지막 완료 EOD 기준일"),
                fieldWithPath("data.missingSymbols").description("가격 누락 종목 코드 목록")
        ));

        mockMvc.perform(get("/api/v1/portfolios/{portfolioId}/analysis/valuation", 100L)
                        .header("Authorization", "Bearer {ACCESS_TOKEN}")
                        .contentType(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valuationStatus").value("PARTIAL"))
                .andExpect(jsonPath("$.data.currentTotalValue").value(nullValue()))
                .andDo(document("portfolio-analysis-value-partial",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Portfolio Analysis")
                                .summary("포트폴리오 자산 가치 분석 (가격 부분 누락)")
                                .pathParameters(parameterWithName("portfolioId").description("포트폴리오 ID"))
                                .responseFields(responseFields)
                                .build())
                ));
    }

    @Test
    @MockMember(id = 1L)
    @DisplayName("분산도: 자산군별 비중 데이터를 조회한다")
    void get_diversification() throws Exception {
        // given
        PortfolioDiversificationResult result = new PortfolioDiversificationResult(
                BigDecimal.valueOf(1500000),
                Map.of("STOCK", BigDecimal.valueOf(60)),
                Map.of("TECH", BigDecimal.valueOf(40)),
                Map.of("US", BigDecimal.valueOf(100))
        );
        given(portfolioFacade.getDiversification(1L, 100L)).willReturn(result);

        // when & then
        List<FieldDescriptor> responseFields = new ArrayList<>(commonResponseFields());
        responseFields.addAll(List.of(
                fieldWithPath("data.totalValue").description("총 자산 가치"),
                fieldWithPath("data.assetRatios[].name").description("자산군 명칭"),
                fieldWithPath("data.assetRatios[].value").description("비중 (%)"),
                fieldWithPath("data.sectorRatios[].name").description("섹터 명칭"),
                fieldWithPath("data.sectorRatios[].value").description("비중 (%)"),
                fieldWithPath("data.countryRatios[].name").description("국가 명칭"),
                fieldWithPath("data.countryRatios[].value").description("비중 (%)")
        ));

        mockMvc.perform(get("/api/v1/portfolios/{portfolioId}/analysis/diversification", 100L)
                        .header("Authorization", "Bearer {ACCESS_TOKEN}")
                        .contentType(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andDo(document("portfolio-analysis-diversification",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Portfolio Analysis")
                                .summary("포트폴리오 자산 분산도 분석")
                                .pathParameters(
                                        parameterWithName("portfolioId").description("포트폴리오 ID")
                                )
                                .responseFields(responseFields)
                                .build())
                ));
    }

    @Test
    @MockMember(id = 1L)
    @DisplayName("요약분석: 포트폴리오 메인 화면용 집계 데이터를 통합 조회한다")
    void get_summary() throws Exception {
        // given
        PortfolioValuationResult valuation = new PortfolioValuationResult(
                BigDecimal.valueOf(1350000), BigDecimal.valueOf(1500000), BigDecimal.valueOf(150000),
                BigDecimal.valueOf(12.5), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.valueOf(15.5), BigDecimal.valueOf(12.0), BigDecimal.valueOf(3.5),
                BigDecimal.valueOf(10.2), BigDecimal.valueOf(1.5), BigDecimal.valueOf(1.1),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
        );
        PortfolioDiversificationResult diversification = new PortfolioDiversificationResult(
                BigDecimal.valueOf(1500000), Map.of("STOCK", BigDecimal.valueOf(100)), Map.of("TECH", BigDecimal.valueOf(100)), Map.of("US", BigDecimal.valueOf(100))
        );
        PortfolioRebalancingResult rebalancing = new PortfolioRebalancingResult(
                BigDecimal.valueOf(1500000), List.of()
        );
        PortfolioAnalysisSummaryResult result = new PortfolioAnalysisSummaryResult(
                valuation, diversification, rebalancing, Map.of("AAPL", BigDecimal.valueOf(12.5))
        );

        given(portfolioFacade.getAnalysisSummary(any(), any(), any(), any())).willReturn(result);

        // when & then
        List<FieldDescriptor> responseFields = new ArrayList<>(commonResponseFields());
        responseFields.addAll(List.of(
                subsectionWithPath("data.valuation").description("가치 평가 정보"),
                subsectionWithPath("data.diversification").description("분산도 정보"),
                subsectionWithPath("data.rebalancing").description("리밸런싱 정보"),
                subsectionWithPath("data.itemContributions").description("종목별 수익 기여도 (Ticker -> Contribution)")
        ));

        mockMvc.perform(get("/api/v1/portfolios/{portfolioId}/analysis/summary", 100L)
                        .header("Authorization", "Bearer {ACCESS_TOKEN}")
                        .param("startDate", "2023-01-01")
                        .param("endDate", "2023-12-31")
                        .contentType(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.valuation.currentTotalValue").value(1500000))
                .andExpect(jsonPath("$.data.diversification.totalValue").value(1500000))
                .andExpect(jsonPath("$.data.rebalancing.totalValue").value(1500000))
                .andExpect(jsonPath("$.data.itemContributions.AAPL").value(12.5))
                .andDo(document("portfolio-analysis-summary",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Portfolio Analysis")
                                .summary("포트폴리오 메인 화면용 통합 분석 요약")
                                .pathParameters(
                                        parameterWithName("portfolioId").description("포트폴리오 ID")
                                )
                                .queryParameters(
                                        parameterWithName("startDate").description("분석 시작일 (YYYY-MM-DD)").optional(),
                                        parameterWithName("endDate").description("분석 종료일 (YYYY-MM-DD)").optional()
                                )
                                .responseFields(responseFields)
                                .build())
                ));
    }

    @Test
    @MockMember(id = 1L)
    @DisplayName("리밸런싱: 목표 비중 대비 현재 비중 차이를 분석한다")
    void get_rebalancing() throws Exception {
        // given
        PortfolioRebalancingResult result = new PortfolioRebalancingResult(
                BigDecimal.valueOf(1500000),
                List.of(new PortfolioRebalancingResult.RebalancingItem(
                        "AAPL", "애플", BigDecimal.valueOf(50), BigDecimal.valueOf(60), BigDecimal.valueOf(-10),
                        BigDecimal.TEN, BigDecimal.valueOf(12), BigDecimal.valueOf(150000)))
        );
        given(portfolioFacade.getRebalancingGuide(1L, 100L)).willReturn(result);

        // when & then
        List<FieldDescriptor> responseFields = new ArrayList<>(commonResponseFields());
        responseFields.addAll(List.of(
                fieldWithPath("data.totalValue").description("총 자산 가치"),
                fieldWithPath("data.items[].symbol").description("종목 심볼"),
                fieldWithPath("data.items[].name").description("종목명"),
                fieldWithPath("data.items[].currentWeight").description("현재 비중 (%)"),
                fieldWithPath("data.items[].targetWeight").description("목표 비중 (%)"),
                fieldWithPath("data.items[].diffWeight").description("비중 차이 (%p)"),
                fieldWithPath("data.items[].currentQuantity").description("현재 보유 수량"),
                fieldWithPath("data.items[].recommendedQuantity").description("권장 보유 수량"),
                fieldWithPath("data.items[].currentPrice").description("현재가"),
                fieldWithPath("data.items[].expectedTradeAmount").description("예상 매매 금액")
        ));

        mockMvc.perform(get("/api/v1/portfolios/{portfolioId}/analysis/rebalancing", 100L)
                        .header("Authorization", "Bearer {ACCESS_TOKEN}")
                        .contentType(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andDo(document("portfolio-analysis-rebalancing",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Portfolio Analysis")
                                .summary("포트폴리오 리밸런싱 분석")
                                .pathParameters(
                                        parameterWithName("portfolioId").description("포트폴리오 ID")
                                )
                                .responseFields(responseFields)
                                .build())
                ));
    }

    @Test
    @MockMember(id = 1L)
    @DisplayName("백테스팅: 과거 시점부터 현재까지의 수익 시뮬레이션을 실행한다")
    void run_backtest() throws Exception {
        // given
        Map<String, BigDecimal> benchmarkRates = new LinkedHashMap<>();
        benchmarkRates.put("0001", BigDecimal.valueOf(3));
        benchmarkRates.put("SPX", BigDecimal.valueOf(4));
        benchmarkRates.put("1001", BigDecimal.valueOf(5));
        BacktestResult result = new BacktestResult(
                List.of(new BacktestResult.DailyBacktestResult(
                        LocalDate.of(2024, 1, 1), BigDecimal.valueOf(1000000), BigDecimal.valueOf(1000000),
                        BigDecimal.valueOf(5), benchmarkRates)),
                BigDecimal.valueOf(15), // cagr (%)
                BigDecimal.valueOf(-10), // mdd (%)
                BigDecimal.valueOf(-5), // relativeMdd (%)
                BigDecimal.valueOf(1.5), // sharpeRatio
                BigDecimal.valueOf(20), // totalReturnRate (%)
                BigDecimal.valueOf(12), // volatility (%)
                BigDecimal.valueOf(5), // alpha (%)
                BigDecimal.valueOf(1.0), // beta
                BigDecimal.valueOf(25), // bestYearRate (%)
                BigDecimal.valueOf(-5), // worstYearRate (%)
                Map.of("005930", BigDecimal.valueOf(10.0)),
                List.of(
                        new BacktestResult.IndexComparison("코스피", "0001", BigDecimal.valueOf(15.5), BigDecimal.valueOf(4.5), BigDecimal.valueOf(1.0), BigDecimal.valueOf(10.0), BigDecimal.valueOf(-5.0)),
                        new BacktestResult.IndexComparison("S&P 500", "SPX", BigDecimal.valueOf(17.5), BigDecimal.valueOf(2.5), BigDecimal.valueOf(1.1), BigDecimal.valueOf(11.0), BigDecimal.valueOf(-4.0)),
                        new BacktestResult.IndexComparison("코스닥", "1001", BigDecimal.valueOf(19.5), BigDecimal.valueOf(0.5), BigDecimal.valueOf(1.2), BigDecimal.valueOf(12.0), BigDecimal.valueOf(-3.0))
                ),
                "현재 포트폴리오는 시장 지수 대비 안정적인 수익을 보여주고 있습니다.", // aiComment
                null, // LUMP_SUM에는 XIRR이 적용되지 않음
                BigDecimal.valueOf(20), // timeWeightedReturnRate (%)
                "LUMP_SUM_CAGR_TWR", // calculationMethod
                BigDecimal.valueOf(1.8), // sortinoRatio
                30L // recoveryPeriod
        );
        given(portfolioFacade.runBacktest(any())).willReturn(result);

        BacktestRequest request = new BacktestRequest(
                "LUMP_SUM",
                BigDecimal.valueOf(1000000),
                "",
                "ALL",
                true,
                RebalancingPeriod.MONTHLY,
                Map.of("005930", BigDecimal.valueOf(100))
        );
        List<FieldDescriptor> responseFields = backtestResponseFields();

        mockMvc.perform(post("/api/v1/portfolios/{portfolioId}/analysis/backtest", 100L)
                        .header("Authorization", "Bearer {ACCESS_TOKEN}")
                        .content(objectMapper.writeValueAsString(request))
                        .contentType(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.aiComment").exists())
                .andExpect(jsonPath("$.data.cagr").value(15))
                .andExpect(jsonPath("$.data.xirr").doesNotExist())
                .andExpect(jsonPath("$.data.timeWeightedReturnRate").value(20))
                .andExpect(jsonPath("$.data.calculationMethod").value("LUMP_SUM_CAGR_TWR"))
                .andExpect(jsonPath("$.data.dailyResults[0].benchmarkReturnRate").value(3.0))
                .andExpect(jsonPath("$.data.comparisons.length()").value(3))
                .andExpect(jsonPath("$.data.comparisons[0].ticker").value("KOSPI"))
                .andDo(document("portfolio-analysis-backtest",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Portfolio Analysis")
                                .summary("포트폴리오 과거 시뮬레이션(백테스팅) 실행")
                                .pathParameters(
                                        parameterWithName("portfolioId").description("포트폴리오 ID")
                                )
                                .requestFields(
                                        fieldWithPath("strategy").description("투자 전략 (LUMP_SUM: 거액 적립, DCA: 정기 적립)"),
                                        fieldWithPath("amount").description("초기 투자 금액 (또는 월간 적립액)"),
                                        fieldWithPath("primaryBenchmark").description("비교 대상 대표 벤치마크 코드 (KOSPI, KOSDAQ, SP500; 미입력 시 KOSPI)").optional(),
                                        fieldWithPath("period").description("시뮬레이션 기간 (1M, 3M, 6M, 1Y, 3Y, ALL)").optional(),
                                        fieldWithPath("dividendReinvested").description("배당금 재투자 여부").optional(),
                                        fieldWithPath("rebalancingPeriod").description("리밸런싱 주기 (NONE, MONTHLY, QUARTERLY, YEARLY)").optional(),
                                        subsectionWithPath("weights").description("사용자 정의 종목별 비중 (미입력 시 현재 포트폴리오 비중 유지)").optional()
                                )
                                .responseFields(responseFields)
                                .build())
                ));
    }

    @Test
    @MockMember(id = 1L)
    @DisplayName("백테스팅: 적립식은 CAGR 대신 XIRR을 반환한다")
    void run_backtest_dca() throws Exception {
        Map<String, BigDecimal> benchmarkRates = new LinkedHashMap<>();
        benchmarkRates.put("SPX", BigDecimal.valueOf(3));
        benchmarkRates.put("0001", BigDecimal.valueOf(2));
        benchmarkRates.put("1001", BigDecimal.valueOf(4));
        BacktestResult result = new BacktestResult(
                List.of(new BacktestResult.DailyBacktestResult(
                        LocalDate.of(2024, 1, 1), BigDecimal.valueOf(1100000), BigDecimal.valueOf(1000000),
                        BigDecimal.valueOf(1), benchmarkRates)),
                null, // DCA에는 CAGR이 적용되지 않음
                BigDecimal.valueOf(-8),
                BigDecimal.valueOf(-3),
                BigDecimal.valueOf(1.2),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(2),
                BigDecimal.valueOf(0.9),
                BigDecimal.valueOf(15),
                BigDecimal.valueOf(-4),
                Map.of("005930", BigDecimal.valueOf(8.0)),
                List.of(
                        new BacktestResult.IndexComparison("S&P 500", "SPX", BigDecimal.valueOf(12.5), BigDecimal.valueOf(2.0), BigDecimal.valueOf(0.9), BigDecimal.valueOf(8.0), BigDecimal.valueOf(-3.0)),
                        new BacktestResult.IndexComparison("코스피", "0001", BigDecimal.valueOf(10.5), BigDecimal.valueOf(1.5), BigDecimal.valueOf(0.8), BigDecimal.valueOf(9.0), BigDecimal.valueOf(-4.0)),
                        new BacktestResult.IndexComparison("코스닥", "1001", BigDecimal.valueOf(14.5), BigDecimal.valueOf(1.0), BigDecimal.valueOf(1.0), BigDecimal.valueOf(10.0), BigDecimal.valueOf(-2.0))
                ),
                "적립식 투자 결과입니다.",
                BigDecimal.valueOf(12), // xirr (%)
                BigDecimal.valueOf(10), // timeWeightedReturnRate (%)
                "DCA_XIRR_TWR",
                BigDecimal.valueOf(1.4),
                20L
        );
        given(portfolioFacade.runBacktest(any())).willReturn(result);

        BacktestRequest request = new BacktestRequest(
                "DCA",
                BigDecimal.valueOf(100000),
                "SP500",
                "1Y",
                true,
                RebalancingPeriod.NONE,
                Map.of("005930", BigDecimal.valueOf(100))
        );

        mockMvc.perform(post("/api/v1/portfolios/{portfolioId}/analysis/backtest", 100L)
                        .header("Authorization", "Bearer {ACCESS_TOKEN}")
                        .content(objectMapper.writeValueAsString(request))
                        .contentType(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.cagr").value(nullValue()))
                .andExpect(jsonPath("$.data.xirr").value(12))
                .andExpect(jsonPath("$.data.timeWeightedReturnRate").value(10))
                .andExpect(jsonPath("$.data.calculationMethod").value("DCA_XIRR_TWR"))
                .andDo(document("portfolio-analysis-backtest-dca",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Portfolio Analysis")
                                .summary("포트폴리오 적립식 백테스팅(XIRR)")
                                .pathParameters(parameterWithName("portfolioId").description("포트폴리오 ID"))
                                .requestFields(
                                        fieldWithPath("strategy").description("투자 전략 (LUMP_SUM: 거액 적립, DCA: 정기 적립)"),
                                        fieldWithPath("amount").description("초기 투자 금액 (또는 월간 적립액)"),
                                        fieldWithPath("primaryBenchmark").description("비교 대상 대표 벤치마크 코드 (KOSPI, KOSDAQ, SP500; 미입력 시 KOSPI)").optional(),
                                        fieldWithPath("period").description("시뮬레이션 기간 (1M, 3M, 6M, 1Y, 3Y, ALL)").optional(),
                                        fieldWithPath("dividendReinvested").description("배당금 재투자 여부").optional(),
                                        fieldWithPath("rebalancingPeriod").description("리밸런싱 주기 (NONE, MONTHLY, QUARTERLY, YEARLY)").optional(),
                                        subsectionWithPath("weights").description("사용자 정의 종목별 비중 (미입력 시 현재 포트폴리오 비중 유지)").optional()
                                )
                                .responseFields(backtestResponseFields())
                                .build())
                ));
    }

    private List<FieldDescriptor> backtestResponseFields() {
        List<FieldDescriptor> responseFields = new ArrayList<>(commonResponseFields());
        responseFields.addAll(List.of(
                fieldWithPath("data.dailyResults[].date").description("시뮬레이션 일자"),
                fieldWithPath("data.dailyResults[].totalValue").description("해당 일자 총 자산 가치"),
                fieldWithPath("data.dailyResults[].totalInvested").description("해당 일자 총 누적 투자금"),
                fieldWithPath("data.dailyResults[].returnRate").description("해당 일자 누적 수익률 (%)"),
                fieldWithPath("data.dailyResults[].benchmarkReturnRate").description("주요 벤치마크 누적 수익률 (%) — 요청한 primaryBenchmark 기준").optional(),
                subsectionWithPath("data.dailyResults[].benchmarkReturnRates").description("벤치마크 지수별 해당 일자 수익률 (Map<Ticker, Rate>)"),
                fieldWithPath("data.primaryBenchmark").description("요청한 주요 벤치마크 코드"),
                fieldWithPath("data.cagr").description("연평균 복리 수익률 (CAGR)").optional(),
                fieldWithPath("data.xirr").description("현금흐름 기준 연환산 수익률 (XIRR)").optional(),
                fieldWithPath("data.timeWeightedReturnRate").description("외부 현금흐름을 제거한 누적 시간가중수익률"),
                fieldWithPath("data.calculationMethod").description("적용한 금융 계산 방법").optional(),
                fieldWithPath("data.mdd").description("최대 낙폭 (MDD)"),
                fieldWithPath("data.relativeMdd").description("벤치마크 대비 상대 낙폭"),
                fieldWithPath("data.sharpeRatio").description("위험 대비 수익 지수 (샤프 지수)"),
                fieldWithPath("data.sortinoRatio").description("하방 변동성 대비 수익 지수").optional(),
                fieldWithPath("data.recoveryPeriod").description("최장 회복 기간(일)").optional(),
                fieldWithPath("data.totalReturnRate").description("전체 기간 총 수익률"),
                fieldWithPath("data.volatility").description("수익률 표준편차 (변동성)"),
                fieldWithPath("data.alpha").description("벤치마크 대비 초과 수익률 (Alpha)"),
                fieldWithPath("data.beta").description("시장 지수 변동성 대비 민감도 (Beta)"),
                fieldWithPath("data.bestYearRate").description("최고 수익을 기록한 해의 수익률"),
                fieldWithPath("data.worstYearRate").description("최저 수익을 기록한 해의 수익률"),
                subsectionWithPath("data.itemReturns").description("종목별 수익률 기여도"),
                fieldWithPath("data.comparisons[].indexName").description("비교 지수 명칭 (예: 코스피, 나스닥)"),
                fieldWithPath("data.comparisons[].ticker").description("비교 지수 티커"),
                fieldWithPath("data.comparisons[].totalReturn").description("비교 지수의 전체 기간 총 수익률"),
                fieldWithPath("data.comparisons[].alpha").description("지수 대비 해당 포트폴리오의 초과 수익"),
                fieldWithPath("data.comparisons[].beta").description("지수 대비 해당 포트폴리오의 베타"),
                fieldWithPath("data.comparisons[].mdd").description("비교 지수의 MDD"),
                fieldWithPath("data.comparisons[].relativeMdd").description("지수 대비 해당 포트폴리오의 상대 낙폭"),
                fieldWithPath("data.aiComment").description("AI 엔진이 생성한 백테스트 분석 코멘트").optional()
        ));
        return responseFields;
    }

    private List<FieldDescriptor> validationErrorResponseFields() {
        List<FieldDescriptor> fields = new ArrayList<>(commonResponseFieldsWithNoData());
        fields.addAll(List.of(
                fieldWithPath("errors[].field").type(JsonFieldType.STRING).description("검증에 실패한 필드명").optional(),
                fieldWithPath("errors[].value").type(JsonFieldType.STRING).description("검증에 실패한 입력값").optional(),
                fieldWithPath("errors[].reason").type(JsonFieldType.STRING).description("검증 실패 사유").optional()
        ));
        return fields;
    }

    @Test
    @MockMember(id = 1L)
    @DisplayName("백테스팅: 필수 벤치마크 누락은 S002로 표준화한다")
    void run_backtest_missing_benchmark() throws Exception {
        given(portfolioFacade.runBacktest(any()))
                .willThrow(new GlobalException(ErrorCode.PRICE_DATA_NOT_FOUND));
        BacktestRequest request = new BacktestRequest(
                "LUMP_SUM",
                BigDecimal.valueOf(1000000),
                "SP500",
                null,
                true,
                RebalancingPeriod.NONE,
                Map.of("005930", BigDecimal.valueOf(100))
        );

        mockMvc.perform(post("/api/v1/portfolios/{portfolioId}/analysis/backtest", 100L)
                        .header("Authorization", "Bearer {ACCESS_TOKEN}")
                        .content(objectMapper.writeValueAsString(request))
                        .contentType(APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("S002"))
                .andDo(document("portfolio-analysis-backtest-missing-benchmark",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Portfolio Analysis")
                                .summary("필수 벤치마크 시세 누락 오류")
                                .pathParameters(parameterWithName("portfolioId").description("포트폴리오 ID"))
                                .responseFields(validationErrorResponseFields())
                                .build())
                ));

        ArgumentCaptor<BacktestPortfolioCommand> commandCaptor = ArgumentCaptor.forClass(BacktestPortfolioCommand.class);
        verify(portfolioFacade).runBacktest(commandCaptor.capture());
        assertThat(commandCaptor.getValue().period()).isEqualTo(ChartPeriod.ONE_YEAR);
        assertThat(commandCaptor.getValue().benchmarkTickers())
                .containsExactly("SPX", "0001", "1001");
    }

    @Test
    @MockMember(id = 1L)
    @DisplayName("백테스팅: 0원 투자금은 G001로 거부한다")
    void run_backtest_invalid_amount() throws Exception {
        BacktestRequest request = new BacktestRequest(
                "LUMP_SUM",
                BigDecimal.ZERO,
                "KOSPI",
                "1Y",
                true,
                RebalancingPeriod.NONE,
                Map.of("005930", BigDecimal.valueOf(100))
        );

        mockMvc.perform(post("/api/v1/portfolios/{portfolioId}/analysis/backtest", 100L)
                        .header("Authorization", "Bearer {ACCESS_TOKEN}")
                        .content(objectMapper.writeValueAsString(request))
                        .contentType(APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("G001"))
                .andExpect(jsonPath("$.errors[0].field").value("amount"))
                .andDo(document("portfolio-analysis-backtest-invalid-amount",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Portfolio Analysis")
                                .summary("백테스팅 투자금 검증 오류")
                                .pathParameters(parameterWithName("portfolioId").description("포트폴리오 ID"))
                                .responseFields(validationErrorResponseFields())
                                .build())
                ));

        verifyNoInteractions(portfolioFacade);
    }

    @Test
    @MockMember(id = 1L)
    @DisplayName("백테스팅: 투자금 누락은 G001로 거부한다")
    void run_backtest_null_amount() throws Exception {
        BacktestRequest request = new BacktestRequest(
                "LUMP_SUM",
                null,
                "KOSPI",
                "1Y",
                true,
                RebalancingPeriod.NONE,
                Map.of("005930", BigDecimal.valueOf(100))
        );

        mockMvc.perform(post("/api/v1/portfolios/{portfolioId}/analysis/backtest", 100L)
                        .header("Authorization", "Bearer {ACCESS_TOKEN}")
                        .content(objectMapper.writeValueAsString(request))
                        .contentType(APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("G001"))
                .andExpect(jsonPath("$.errors[0].field").value("amount"))
                .andDo(document("portfolio-analysis-backtest-null-amount",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Portfolio Analysis")
                                .summary("백테스팅 투자금 누락 오류")
                                .pathParameters(parameterWithName("portfolioId").description("포트폴리오 ID"))
                                .responseFields(validationErrorResponseFields())
                                .build())
                ));

        verifyNoInteractions(portfolioFacade);
    }

    @Test
    @MockMember(id = 1L)
    @DisplayName("백테스팅: 지원하지 않는 기간은 G001로 거부한다")
    void run_backtest_invalid_period() throws Exception {
        BacktestRequest request = new BacktestRequest(
                "LUMP_SUM",
                BigDecimal.valueOf(1000000),
                "KOSPI",
                "INVALID",
                true,
                RebalancingPeriod.NONE,
                Map.of("005930", BigDecimal.valueOf(100))
        );

        mockMvc.perform(post("/api/v1/portfolios/{portfolioId}/analysis/backtest", 100L)
                        .header("Authorization", "Bearer {ACCESS_TOKEN}")
                        .content(objectMapper.writeValueAsString(request))
                        .contentType(APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("G001"))
                .andDo(document("portfolio-analysis-backtest-invalid-period",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Portfolio Analysis")
                                .summary("백테스팅 기간 검증 오류")
                                .pathParameters(parameterWithName("portfolioId").description("포트폴리오 ID"))
                                .responseFields(validationErrorResponseFields())
                                .build())
                ));

        verifyNoInteractions(portfolioFacade);
    }

    @Test
    @MockMember(id = 1L)
    @DisplayName("백테스팅: 지원하지 않는 전략은 G001로 거부한다")
    void run_backtest_invalid_strategy() throws Exception {
        BacktestRequest request = new BacktestRequest(
                "INVALID",
                BigDecimal.valueOf(1000000),
                "KOSPI",
                "1Y",
                true,
                RebalancingPeriod.NONE,
                Map.of("005930", BigDecimal.valueOf(100))
        );

        mockMvc.perform(post("/api/v1/portfolios/{portfolioId}/analysis/backtest", 100L)
                        .header("Authorization", "Bearer {ACCESS_TOKEN}")
                        .content(objectMapper.writeValueAsString(request))
                        .contentType(APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("G001"))
                .andDo(document("portfolio-analysis-backtest-invalid-strategy",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Portfolio Analysis")
                                .summary("백테스팅 전략 검증 오류")
                                .pathParameters(parameterWithName("portfolioId").description("포트폴리오 ID"))
                                .responseFields(commonResponseFieldsWithNoData())
                                .build())
                ));

        verifyNoInteractions(portfolioFacade);
    }

    @Test
    @MockMember(id = 1L)
    @DisplayName("백테스팅: 지원하지 않는 리밸런싱 주기는 G001로 거부한다")
    void run_backtest_invalid_rebalancing_period() throws Exception {
        String requestJson = """
                {
                  "strategy": "LUMP_SUM",
                  "amount": 1000000,
                  "primaryBenchmark": "KOSPI",
                  "period": "1Y",
                  "dividendReinvested": true,
                  "rebalancingPeriod": "INVALID",
                  "weights": {"005930": 100}
                }
                """;

        mockMvc.perform(post("/api/v1/portfolios/{portfolioId}/analysis/backtest", 100L)
                        .header("Authorization", "Bearer {ACCESS_TOKEN}")
                        .content(requestJson)
                        .contentType(APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("G001"))
                .andDo(document("portfolio-analysis-backtest-invalid-rebalancing-period",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Portfolio Analysis")
                                .summary("백테스팅 리밸런싱 주기 검증 오류")
                                .pathParameters(parameterWithName("portfolioId").description("포트폴리오 ID"))
                                .responseFields(validationErrorResponseFields())
                                .build())
                ));

        verifyNoInteractions(portfolioFacade);
    }

    @Test
    @MockMember(id = 1L)
    @DisplayName("상관관계: 종목 간 상관관계 행렬을 조회한다")
    void get_correlation() throws Exception {
        // given
        Map<String, Map<String, BigDecimal>> matrix = Map.of(
                "AAPL", Map.of("AAPL", BigDecimal.ONE, "TSLA", new BigDecimal("0.85")),
                "TSLA", Map.of("AAPL", new BigDecimal("0.85"), "TSLA", BigDecimal.ONE)
        );
        given(portfolioFacade.getCorrelationMatrix(1L, 100L)).willReturn(matrix);

        // when & then
        List<FieldDescriptor> responseFields = new ArrayList<>(commonResponseFields());
        responseFields.add(subsectionWithPath("data").description("종목별 상관관계 행렬 데이터 (가변 구조)"));

        mockMvc.perform(get("/api/v1/portfolios/{portfolioId}/analysis/correlation", 100L)
                        .header("Authorization", "Bearer {ACCESS_TOKEN}")
                        .contentType(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andDo(document("portfolio-analysis-correlation",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Portfolio Analysis")
                                .summary("포트폴리오 종목 간 상관관계 분석")
                                .pathParameters(
                                        parameterWithName("portfolioId").description("포트폴리오 ID")
                                )
                                .responseFields(responseFields)
                                .build())
                ));
    }

    @Test
    @MockMember(id = 1L)
    @DisplayName("생성 시점 차트: 포트폴리오 메인 화면용 누적 수익률 시계열을 조회한다")
    void get_inception_chart() throws Exception {
        PortfolioInceptionChartResult result = new PortfolioInceptionChartResult(
                LocalDate.of(2026, 4, 1),
                20L,
                List.of(new PortfolioInceptionChartResult.DailyResult(
                        LocalDate.of(2026, 4, 1),
                        BigDecimal.ZERO,
                        Map.of(
                                BenchmarkType.KOSPI_200.getTicker(), BigDecimal.ZERO,
                                BenchmarkType.S_P_500.getTicker(), BigDecimal.ZERO
                        )
                )),
                List.of(
                        new PortfolioInceptionChartResult.IndexComparison("코스피 200", BenchmarkType.KOSPI_200.getTicker(), BigDecimal.valueOf(3.2)),
                        new PortfolioInceptionChartResult.IndexComparison("S&P 500", BenchmarkType.S_P_500.getTicker(), BigDecimal.valueOf(5.4))
                )
        );
        given(portfolioFacade.getInceptionChart(1L, 100L)).willReturn(result);

        List<FieldDescriptor> responseFields = new ArrayList<>(commonResponseFields());
        responseFields.addAll(List.of(
                fieldWithPath("data.portfolioInceptionDate").description("포트폴리오 생성 기준일"),
                fieldWithPath("data.daysElapsed").description("포트폴리오 생성 후 경과 일수"),
                fieldWithPath("data.dailyResults[].date").description("기준 일자"),
                fieldWithPath("data.dailyResults[].portfolioReturnRate").description("포트폴리오 생성 시점 대비 누적 수익률"),
                subsectionWithPath("data.dailyResults[].benchmarkReturnRates").description("비교군별 누적 수익률"),
                fieldWithPath("data.comparisons[].indexName").description("비교 지수 이름"),
                fieldWithPath("data.comparisons[].ticker").description("비교 지수 티커"),
                fieldWithPath("data.comparisons[].totalReturn").description("전체 기간 비교군 수익률")
        ));

        mockMvc.perform(get("/api/v1/portfolios/{portfolioId}/analysis/performance/inception/chart", 100L)
                        .header("Authorization", "Bearer {ACCESS_TOKEN}")
                        .contentType(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.dailyResults[0].portfolioReturnRate").value(0.0))
                .andExpect(jsonPath("$.data.comparisons[0].ticker").value(BenchmarkType.KOSPI_200.getTicker()))
                .andDo(document("portfolio-analysis-inception-chart",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Portfolio Analysis")
                                .summary("포트폴리오 메인 화면용 생성 시점 기준 누적 수익률 차트")
                                .pathParameters(parameterWithName("portfolioId").description("포트폴리오 ID"))
                                .responseFields(responseFields)
                                .build())
                ));
    }
}

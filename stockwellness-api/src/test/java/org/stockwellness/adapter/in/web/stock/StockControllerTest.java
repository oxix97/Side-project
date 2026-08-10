package org.stockwellness.adapter.in.web.stock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.epages.restdocs.apispec.ResourceSnippetParameters;
import com.epages.restdocs.apispec.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.SliceImpl;
import org.springframework.http.MediaType;
import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.stockwellness.application.port.in.stock.StockPriceUseCase;
import org.stockwellness.application.port.in.stock.StockSearchUseCase;
import org.stockwellness.application.port.in.stock.StockUseCase;
import org.stockwellness.application.port.in.stock.result.*;
import org.stockwellness.domain.stock.MarketType;
import org.stockwellness.domain.stock.StockStatus;
import org.stockwellness.domain.stock.exception.StockPriceException;
import org.stockwellness.global.error.ErrorCode;
import org.stockwellness.support.RestDocsSupport;
import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.ResourceDocumentation.resource;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.delete;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StockControllerTest extends RestDocsSupport {

    @MockitoBean
    private StockUseCase stockUseCase;

    @MockitoBean
    private StockPriceUseCase stockPriceUseCase;

    @MockitoBean
    private StockSearchUseCase stockSearchUseCase;

    @Test
    @DisplayName("종목 통합 검색 API")
    void searchStocks() throws Exception {
        // given
        StockSearchResult result = new StockSearchResult("005930", "삼성전자", MarketType.KOSPI, "전기전자", StockStatus.ACTIVE);
        given(stockUseCase.searchStocks(any())).willReturn(new SliceImpl<>(List.of(result)));

        // when & then
        List<FieldDescriptor> responseFields = new ArrayList<>(commonResponseFields());
        responseFields.addAll(List.of(
                fieldWithPath("data.content[].ticker").description("티커"),
                fieldWithPath("data.content[].name").description("종목명"),
                fieldWithPath("data.content[].marketType").description("마켓 타입"),
                fieldWithPath("data.content[].sectorName").description("섹터명"),
                fieldWithPath("data.content[].status").description("종목 상태"),
                fieldWithPath("data.number").description("현재 페이지 번호 (0-based)"),
                fieldWithPath("data.size").description("페이지 크기"),
                fieldWithPath("data.numberOfElements").description("현재 페이지 항목 수"),
                fieldWithPath("data.first").description("첫 번째 페이지 여부"),
                fieldWithPath("data.last").description("마지막 페이지 여부"),
                fieldWithPath("data.hasNext").description("다음 페이지 존재 여부"),
                fieldWithPath("data.empty").description("결과 없음 여부")
        ));

        mockMvc.perform(get("/api/v1/stocks/search")
                        .param("keyword", "삼성")
                        .param("page", "0")
                        .param("size", "20")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content").isArray())
                .andDo(document("stock-search",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Stock Discovery")
                                .summary("종목 통합 검색")
                                .description("티커 또는 종목명으로 주식을 검색합니다.")
                                .queryParameters(
                                        parameterWithName("keyword").description("검색어 (티커 또는 종목명)"),
                                        parameterWithName("marketType").description("마켓 타입 (KOSPI, KOSDAQ 등)").optional(),
                                        parameterWithName("status").description("종목 상태 (ACTIVE, HALTED 등)").optional(),
                                        parameterWithName("page").description("페이지 번호 (0부터 시작)").optional(),
                                        parameterWithName("size").description("페이지 사이즈").optional()
                                )
                                .responseFields(responseFields)
                                .build())
                ));
    }

    @Test
    @DisplayName("종목 수급 랭킹 조회 API")
    void getTopStocksBySupply_docs() throws Exception {
        // given
        StockSupplyRankingResponse result = new StockSupplyRankingResponse(
                null,
                LocalDate.of(2026, 4, 7),
                List.of(
                        new StockSupplyRankingResult("005930", "삼성전자", "반도체", new BigDecimal("71000"), new BigDecimal("1.43"), 250000L, new BigDecimal("5000000000"), new BigDecimal("120000000000")),
                        new StockSupplyRankingResult("000660", "SK하이닉스", "반도체", new BigDecimal("202000"), new BigDecimal("-0.98"), 180000L, new BigDecimal("3000000000"), new BigDecimal("80000000000"))
                ),
                List.of(
                        new StockSupplyRankingResult("005930", "삼성전자", "반도체", new BigDecimal("71000"), new BigDecimal("1.43"), 90000L, new BigDecimal("2000000000"), new BigDecimal("120000000000"))
                )
        );

        given(stockPriceUseCase.getTopStocksBySupply(any(), anyInt()))
                .willReturn(result);

        // when & then
        List<FieldDescriptor> responseFields = new ArrayList<>(commonResponseFields());
        responseFields.addAll(List.of(
                fieldWithPath("data.requestedDate").description("사용자가 요청한 날짜 (현재 미사용)").optional(),
                fieldWithPath("data.effectiveDate").description("실제 랭킹 산정 기준 날짜 (가장 최신 적재일)").optional(),
                fieldWithPath("data.institutionItems").description("기관 기준 종목 수급 랭킹 리스트"),
                fieldWithPath("data.institutionItems[].ticker").description("티커"),
                fieldWithPath("data.institutionItems[].stockName").description("종목명"),
                fieldWithPath("data.institutionItems[].sectorName").description("업종명").optional(),
                fieldWithPath("data.institutionItems[].currentPrice").description("기준일 종가"),
                fieldWithPath("data.institutionItems[].fluctuationRate").description("전일 대비 등락률 (%)"),
                fieldWithPath("data.institutionItems[].netBuyingQuantity").description("기관 순매수 수량"),
                fieldWithPath("data.institutionItems[].netBuyingAmount").description("기관 순매수 금액"),
                fieldWithPath("data.institutionItems[].transactionAmount").description("거래대금"),
                fieldWithPath("data.foreignItems").description("외국인 기준 종목 수급 랭킹 리스트"),
                fieldWithPath("data.foreignItems[].ticker").description("티커"),
                fieldWithPath("data.foreignItems[].stockName").description("종목명"),
                fieldWithPath("data.foreignItems[].sectorName").description("업종명").optional(),
                fieldWithPath("data.foreignItems[].currentPrice").description("기준일 종가"),
                fieldWithPath("data.foreignItems[].fluctuationRate").description("전일 대비 등락률 (%)"),
                fieldWithPath("data.foreignItems[].netBuyingQuantity").description("외국인 순매수 수량"),
                fieldWithPath("data.foreignItems[].netBuyingAmount").description("외국인 순매수 금액"),
                fieldWithPath("data.foreignItems[].transactionAmount").description("거래대금")
        ));

        mockMvc.perform(get("/api/v1/stocks/ranking/supply")
                        .param("direction", "BUY")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.effectiveDate").value("2026-04-07"))
                .andExpect(jsonPath("$.data.institutionItems").isArray())
                .andExpect(jsonPath("$.data.foreignItems").isArray())
                .andDo(document("stock-supply-ranking",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Stock Price")
                                .summary("종목 수급 랭킹 조회")
                                .description("가장 최신 적재 날짜를 기준으로 기관과 외국인 종목 순매수량/순매도량 순위를 함께 조회합니다.")
                                .queryParameters(
                                        parameterWithName("direction").description("매수/매도 방향 (BUY, SELL)").optional(),
                                        parameterWithName("limit").description("조회 개수 (1 이상)").optional()
                                )
                                .responseFields(responseFields)
                                .build())
                ));
    }

    @Test
    @DisplayName("종목 수급 랭킹 조회 API는 limit가 0 이하이면 400을 반환한다")
    void getTopStocksBySupply_invalidLimit() throws Exception {
        mockMvc.perform(get("/api/v1/stocks/ranking/supply")
                        .param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("G001"))
                .andExpect(jsonPath("$.errors[0].field").value("limit"))
                .andExpect(jsonPath("$.errors[0].value").value("0"));
    }

    @Test
    @DisplayName("종목 수급 랭킹 조회 API는 최신 기준일을 응답한다")
    void getTopStocksBySupply_returnsEffectiveDate() throws Exception {
        given(stockPriceUseCase.getTopStocksBySupply(any(), anyInt()))
                .willReturn(new StockSupplyRankingResponse(
                        null,
                        LocalDate.of(2026, 4, 7),
                        List.of(),
                        List.of()
                ));

        mockMvc.perform(get("/api/v1/stocks/ranking/supply"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requestedDate").isEmpty())
                .andExpect(jsonPath("$.data.effectiveDate").value("2026-04-07"))
                .andExpect(jsonPath("$.data.institutionItems").isArray())
                .andExpect(jsonPath("$.data.foreignItems").isArray());
    }

    @Test
    @DisplayName("최근 검색어 조회 API")
    void getRecentSearches() throws Exception {
        // given
        given(stockSearchUseCase.getRecentSearches(any())).willReturn(List.of("삼성전자", "애플"));

        // when & then
        List<FieldDescriptor> responseFields = new ArrayList<>(commonResponseFields());
        responseFields.add(fieldWithPath("data[]").description("최근 검색어 목록"));

        mockMvc.perform(get("/api/v1/stocks/search/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andDo(document("stock-search-history",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Stock Discovery")
                                .summary("최근 검색어 조회")
                                .responseFields(responseFields)
                                .build())
                ));
    }

    @Test
    @DisplayName("인기 검색어 조회 API")
    void getPopularSearches() throws Exception {
        // given
        given(stockSearchUseCase.getPopularSearches()).willReturn(List.of("삼성전자", "애플", "엔비디아"));

        // when & then
        List<FieldDescriptor> responseFields = new ArrayList<>(commonResponseFields());
        responseFields.add(fieldWithPath("data[]").description("인기 검색어 목록"));

        mockMvc.perform(get("/api/v1/stocks/popular-search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andDo(document("stock-popular-search",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Stock Discovery")
                                .summary("인기 검색어 조회")
                                .responseFields(responseFields)
                                .build())
                ));
    }

    @Test
    @DisplayName("신규 상장 종목 조회 API")
    void getNewListings() throws Exception {
        // given
        StockSearchResult result = new StockSearchResult("T1", "New Stock", MarketType.KOSPI, "기타", StockStatus.ACTIVE);
        given(stockUseCase.getNewListings()).willReturn(List.of(result));

        // when & then
        List<FieldDescriptor> responseFields = new ArrayList<>(commonResponseFields());
        responseFields.addAll(List.of(
                fieldWithPath("data[].ticker").description("티커"),
                fieldWithPath("data[].name").description("종목명"),
                fieldWithPath("data[].marketType").description("마켓 타입"),
                fieldWithPath("data[].sectorName").description("섹터명"),
                fieldWithPath("data[].status").description("종목 상태")
        ));

        mockMvc.perform(get("/api/v1/stocks/new-listings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andDo(document("stock-new-listings",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Stock Discovery")
                                .summary("신규 상장 종목 조회")
                                .responseFields(responseFields)
                                .build())
                ));
    }

    @Test
    @DisplayName("최근 검색어 개별 삭제 API")
    void removeSearchHistory() throws Exception {
        // when & then
        mockMvc.perform(delete("/api/v1/stocks/search/history?keyword=삼성전자"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andDo(document("stock-search-history-delete",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Stock Discovery")
                                .summary("최근 검색어 개별 삭제")
                                .queryParameters(
                                        parameterWithName("keyword").description("삭제할 검색어")
                                )
                                .responseSchema(Schema.schema("EmptyDataResponse"))
                                .responseFields(commonResponseFieldsWithNoData())
                                .build())
                ));
    }

    @Test
    @DisplayName("최근 검색어 전체 삭제 API")
    void clearSearchHistory() throws Exception {
        // when & then
        mockMvc.perform(delete("/api/v1/stocks/search/history/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andDo(document("stock-search-history-clear",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Stock Discovery")
                                .summary("최근 검색어 전체 삭제")
                                .responseSchema(Schema.schema("EmptyDataResponse"))
                                .responseFields(commonResponseFieldsWithNoData())
                                .build())
                ));
    }

    @Test
    @DisplayName("차트용 시세 데이터 조회 API")
    void getPriceHistory() throws Exception {
        // given
        ChartDataResponse.ChartPoint price = new ChartDataResponse.ChartPoint(
                LocalDate.of(2024, 1, 1),
                BigDecimal.valueOf(100), BigDecimal.valueOf(110),
                BigDecimal.valueOf(90), BigDecimal.valueOf(105),
                BigDecimal.valueOf(105), 1000L,
                BigDecimal.valueOf(100000), // transactionAmt
                BigDecimal.valueOf(102.5), // ma5
                BigDecimal.valueOf(101.2), // ma20
                BigDecimal.valueOf(98.5),  // ma60
                BigDecimal.valueOf(95.0)   // ma120
        );
        ChartDataResponse response = new ChartDataResponse("AAPL", "USD", "애플", "S&P 500", List.of(price), List.of());
        given(stockPriceUseCase.loadChartData(any())).willReturn(response);

        // when & then
        List<FieldDescriptor> responseFields = new ArrayList<>(commonResponseFields());
        responseFields.addAll(List.of(
                fieldWithPath("data.ticker").description("티커"),
                fieldWithPath("data.currency").description("통화 코드 (KRW, USD)"),
                fieldWithPath("data.stockName").description("종목명"),
                fieldWithPath("data.benchmarkName").description("벤치마크 이름 (ex: KOSPI, S&P 500)"),
                fieldWithPath("data.prices[].date").description("날짜"),
                fieldWithPath("data.prices[].open").description("시가"),
                fieldWithPath("data.prices[].high").description("고가"),
                fieldWithPath("data.prices[].low").description("저가"),
                fieldWithPath("data.prices[].close").description("종가"),
                fieldWithPath("data.prices[].adjClose").description("수정종가"),
                fieldWithPath("data.prices[].volume").description("거래량"),
                fieldWithPath("data.prices[].transactionAmt").description("거래대금").optional(),
                fieldWithPath("data.prices[].ma5").description("5일 이동평균선").optional(),
                fieldWithPath("data.prices[].ma20").description("20일 이동평균선").optional(),
                fieldWithPath("data.prices[].ma60").description("60일 이동평균선").optional(),
                fieldWithPath("data.prices[].ma120").description("120일 이동평균선").optional(),
                fieldWithPath("data.benchmarks").description("벤치마크 데이터 목록")
        ));

        mockMvc.perform(get("/api/v1/stocks/{ticker}/prices/history", "AAPL")
                        .param("period", "1Y")
                        .param("frequency", "DAILY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.ticker").value("AAPL"))
                .andExpect(jsonPath("$.data.currency").value("USD"))
                .andExpect(jsonPath("$.data.stockName").value("애플"))
                .andExpect(jsonPath("$.data.benchmarkName").value("S&P 500"))
                .andExpect(jsonPath("$.data.prices[0].transactionAmt").value(100000))
                .andDo(document("stock-price-history",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Stock Price")
                                .summary("차트용 시세 데이터 조회")
                                .pathParameters(parameterWithName("ticker").description("종목 티커"))
                                .queryParameters(
                                        parameterWithName("period").description("조회 기간 (1W, 1M, 1Y, ALL 등)").optional(),
                                        parameterWithName("frequency").description("데이터 주기 (DAILY, WEEKLY, MONTHLY)").optional(),
                                        parameterWithName("includeBenchmark").description("벤치마크 포함 여부").optional()
                                )
                                .responseFields(responseFields)
                                .build())
                ));
    }

    @Test
    @DisplayName("종목 상세 정보 조회 API")
    void getStockDetail() throws Exception {
        // given
        StockDetailResult result = new StockDetailResult(
                "KR7005930003", "005930", "삼성전자", "반도체", "KOSPI", 5969782550L,
                LocalDate.of(2024, 1, 1), BigDecimal.valueOf(75000),
                BigDecimal.valueOf(1000), BigDecimal.valueOf(1.35),
                BigDecimal.valueOf(74000), BigDecimal.valueOf(76000),
                BigDecimal.valueOf(73500), 15000000L,
                BigDecimal.valueOf(1125000000000L), BigDecimal.valueOf(447733000000000L),
                BigDecimal.valueOf(65.5), BigDecimal.valueOf(72000),
                "현재 삼성전자는 강력한 반도체 업황 개선 기대감으로 단기 상승 추세에 있습니다.",
                true
        );
        given(stockUseCase.getStockDetail(eq("005930"))).willReturn(result);

        // when & then
        List<FieldDescriptor> responseFields = new ArrayList<>(commonResponseFields());
        responseFields.addAll(List.of(
                fieldWithPath("data.isinCode").description("ISIN 코드"),
                fieldWithPath("data.ticker").description("티커"),
                fieldWithPath("data.name").description("종목명"),
                fieldWithPath("data.sectorName").description("섹터명"),
                fieldWithPath("data.marketType").description("마켓 타입"),
                fieldWithPath("data.totalShares").description("상장 주식 수"),
                fieldWithPath("data.baseDate").description("기준 날짜 (시세 누락 시 null)").optional(),
                fieldWithPath("data.currentPrice").description("현재가 (시세 누락 시 null)").optional(),
                fieldWithPath("data.priceChange").description("대비 (시세 누락 시 null)").optional(),
                fieldWithPath("data.fluctuationRate").description("등락률 (%) (시세 누락 시 null)").optional(),
                fieldWithPath("data.openPrice").description("시가 (시세 누락 시 null)").optional(),
                fieldWithPath("data.highPrice").description("고가 (시세 누락 시 null)").optional(),
                fieldWithPath("data.lowPrice").description("저가 (시세 누락 시 null)").optional(),
                fieldWithPath("data.volume").description("거래량 (시세 누락 시 null)").optional(),
                fieldWithPath("data.tradingValue").description("거래대금 (시세 누락 시 null)").optional(),
                fieldWithPath("data.marketCap").description("시가총액 (시세 누락 시 null)").optional(),
                fieldWithPath("data.rsi14").description("RSI(14) 지표").optional(),
                fieldWithPath("data.ma20").description("20일 이동평균선").optional(),
                fieldWithPath("data.aiInsight").description("AI 기술적 인사이트"),
                fieldWithPath("data.isMarketOpen").description("장 개장 여부")
        ));

        mockMvc.perform(get("/api/v1/stocks/{ticker}", "005930")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.ticker").value("005930"))
                .andExpect(jsonPath("$.data.aiInsight").exists())
                .andDo(document("stock-get-detail",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Stock Discovery")
                                .summary("종목 상세 정보 조회")
                                .pathParameters(parameterWithName("ticker").description("종목 티커"))
                                .responseFields(responseFields)
                                .build())
                ));
    }

    @Test
    @DisplayName("존재하지 않는 종목 상세 정보 조회는 S001 표준 404를 반환한다")
    void getStockDetail_unknownTicker() throws Exception {
        given(stockUseCase.getStockDetail(eq("999999")))
                .willThrow(new StockPriceException(ErrorCode.STOCK_NOT_FOUND));

        mockMvc.perform(get("/api/v1/stocks/{ticker}", "999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("S001"))
                .andDo(document("stock-get-detail-not-found",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Stock Discovery")
                                .summary("종목 상세 정보 조회")
                                .pathParameters(parameterWithName("ticker").description("티커"))
                                .responseFields(commonResponseFieldsWithNoData())
                                .build())
                ));
    }

    @Test
    @DisplayName("수익률 조회 API")
    void getReturns() throws Exception {
        // given
        ReturnRateResponse response = new ReturnRateResponse("AAPL", "USD", "1Y", BigDecimal.valueOf(15.5), BigDecimal.valueOf(10.2));
        given(stockPriceUseCase.calculateReturn(eq("AAPL"), any())).willReturn(response);

        // when & then
        List<FieldDescriptor> responseFields = new ArrayList<>(commonResponseFields());
        responseFields.addAll(List.of(
                fieldWithPath("data.ticker").description("티커"),
                fieldWithPath("data.currency").description("통화 코드 (KRW, USD)"),
                fieldWithPath("data.period").description("조회 기간"),
                fieldWithPath("data.stockReturnRate").description("종목 수익률 (%)"),
                fieldWithPath("data.benchmarkReturnRate").description("벤치마크 수익률 (%)")
        ));

        mockMvc.perform(get("/api/v1/stocks/{ticker}/returns", "AAPL")
                        .param("period", "1Y"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.ticker").value("AAPL"))
                .andExpect(jsonPath("$.data.currency").value("USD"))
                .andDo(document("stock-returns",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Stock Price")
                                .summary("종목 수익률 조회")
                                .pathParameters(parameterWithName("ticker").description("종목 티커"))
                                .queryParameters(
                                        parameterWithName("period").description("조회 기간 (1W, 1M, 1Y 등)")
                                )
                                .responseFields(responseFields)
                                .build())
                ));
    }
}

package org.stockwellness.adapter.in.web.portfolio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.epages.restdocs.apispec.ResourceSnippetParameters;
import com.epages.restdocs.apispec.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.stockwellness.adapter.out.persistence.stock.repository.StockPriceRepository;
import org.stockwellness.adapter.out.persistence.stock.repository.StockRepository;
import org.stockwellness.adapter.in.web.portfolio.dto.CreateSimulatedPortfolioRequest;
import org.stockwellness.application.port.in.portfolio.dto.PortfolioCreateRequest;
import org.stockwellness.application.port.in.portfolio.dto.PortfolioItemRequest;
import org.stockwellness.application.port.in.portfolio.dto.PortfolioResponse;
import org.stockwellness.application.port.in.portfolio.dto.PortfolioUpdateRequest;
import org.stockwellness.application.port.in.portfolio.result.AdviceResponse;
import org.stockwellness.application.port.in.portfolio.result.PortfolioHealthResult;
import org.stockwellness.application.port.in.portfolio.result.CreateSimulatedPortfolioResult;
import org.stockwellness.application.service.portfolio.PortfolioFacade;
import org.stockwellness.domain.portfolio.AssetType;
import org.stockwellness.domain.portfolio.PortfolioItem;
import org.stockwellness.domain.portfolio.advisor.AdviceAction;
import org.stockwellness.domain.portfolio.diagnosis.type.DiagnosisCategory;
import org.stockwellness.domain.portfolio.exception.PortfolioDomainException;
import org.stockwellness.domain.stock.Currency;
import org.stockwellness.domain.stock.MarketType;
import org.stockwellness.domain.stock.Stock;
import org.stockwellness.domain.stock.StockStatus;
import org.stockwellness.domain.stock.exception.StockPriceException;
import org.stockwellness.domain.stock.price.StockPrice;
import org.stockwellness.domain.stock.price.StockPriceId;
import org.stockwellness.fixture.PortfolioFixture;
import org.stockwellness.support.RestDocsSupport;
import org.stockwellness.support.annotation.MockMember;
import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.ResourceDocumentation.resource;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.delete;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.put;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.subsectionWithPath;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.stockwellness.global.error.ErrorCode.PRICE_DATA_NOT_FOUND;
import static org.stockwellness.global.error.ErrorCode.UNSUPPORTED_PORTFOLIO_CURRENCY;

@Transactional
@DisplayName("Portfolio 통합 테스트 (RestDocs)")
class PortfolioControllerTest extends RestDocsSupport {

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private StockPriceRepository stockPriceRepository;

    @MockitoBean
    private PortfolioFacade portfolioFacade;

    @Nested
    @DisplayName("성공 케이스")
    class Success {

        @Test
        @MockMember(id = 1L)
        @DisplayName("생성: 포트폴리오를 생성한다")
        void create_portfolio() throws Exception {
            // [Given] 필수 종목 데이터 생성
            stockRepository.save(Stock.of("AAPL", "KR7005930003", "애플", MarketType.NASDAQ, Currency.USD, null, StockStatus.ACTIVE));
            stockRepository.save(Stock.of("CASH", "CASH", "원화", MarketType.KOSPI, Currency.KRW, null, StockStatus.ACTIVE));

            List<PortfolioItemRequest> items = List.of(
                    new PortfolioItemRequest("AAPL", BigDecimal.TEN, BigDecimal.valueOf(150), "USD", AssetType.STOCK, BigDecimal.valueOf(60)),
                    new PortfolioItemRequest("CASH", BigDecimal.valueOf(500), BigDecimal.ONE, "USD", AssetType.CASH, BigDecimal.valueOf(40))
            );
            PortfolioCreateRequest request = new PortfolioCreateRequest(PortfolioFixture.NAME, PortfolioFixture.DESCRIPTION, items);

            given(portfolioFacade.createPortfolio(any())).willReturn(100L);

            mockMvc.perform(post("/api/v1/portfolios")
                            .header("Authorization", "Bearer {ACCESS_TOKEN}")
                            .with(csrf())
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andDo(document("portfolio-create",
                            resource(ResourceSnippetParameters.builder()
                                    .tag("Portfolio")
                                    .summary("포트폴리오 생성")
                                    .requestFields(
                                            fieldWithPath("name").description("포트폴리오 이름"),
                                            fieldWithPath("description").description("포트폴리오 설명"),
                                            fieldWithPath("items[].symbol").description("종목 심볼"),
                                            fieldWithPath("items[].quantity").description("보유 수량"),
                                            fieldWithPath("items[].purchasePrice").description("평균 매수가"),
                                            fieldWithPath("items[].currency").description("통화 (KRW, USD)"),
                                            fieldWithPath("items[].assetType").description("자산 타입 (STOCK, CASH)"),
                                            fieldWithPath("items[].targetWeight").description("목표 비중 (%)")
                                    )
                                    .responseFields(new ArrayList<>(commonResponseFields()) {{
                                        add(fieldWithPath("data").description("생성된 포트폴리오 ID"));
                                    }})
                                    .build())));
        }

        @Test
        @MockMember(id = 1L)
        @DisplayName("가상 생성: 서버가 계산한 포트폴리오 ID와 공통 EOD 기준일을 반환한다")
        void create_simulated_portfolio() throws Exception {
            CreateSimulatedPortfolioRequest request = new CreateSimulatedPortfolioRequest(
                    "내 포트폴리오",
                    "장기 투자",
                    new BigDecimal("10000000"),
                    List.of(
                            new CreateSimulatedPortfolioRequest.ItemRequest("005930", new BigDecimal("60.0")),
                            new CreateSimulatedPortfolioRequest.ItemRequest("000660", new BigDecimal("40.0"))));
            given(portfolioFacade.createSimulatedPortfolio(any()))
                    .willReturn(new CreateSimulatedPortfolioResult(100L, LocalDate.of(2026, 8, 7)));

            mockMvc.perform(post("/api/v1/portfolios/simulated")
                            .header("Authorization", "Bearer {ACCESS_TOKEN}")
                            .with(csrf())
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.portfolioId").value(100L))
                    .andExpect(jsonPath("$.data.asOfDate").value("2026-08-07"))
                    .andDo(document("portfolio-simulated-create",
                            resource(ResourceSnippetParameters.builder()
                                    .tag("Portfolio")
                                    .summary("가상 포트폴리오 생성")
                                    .description("최신 공통 EOD 종가로 원화 종목의 가상 수량을 계산해 포트폴리오를 생성합니다. "
                                            + "가격이 없으면 S002, 원화가 아닌 종목이면 P006을 반환합니다.")
                                    .requestFields(
                                            fieldWithPath("name").description("포트폴리오 이름"),
                                            fieldWithPath("description").description("포트폴리오 설명").optional(),
                                            fieldWithPath("totalAmount").description("총 투자 금액 (KRW, 0 초과)"),
                                            fieldWithPath("items[].symbol").description("종목 코드"),
                                            fieldWithPath("items[].targetWeight").description("목표 비중 합계 100 (%), 소수점 넷째 자리까지"))
                                    .responseFields(new ArrayList<>(commonResponseFields()) {{
                                        add(fieldWithPath("data.portfolioId").description("생성된 포트폴리오 ID"));
                                        add(fieldWithPath("data.asOfDate").description("수량 계산에 사용한 공통 EOD 기준일"));
                                    }})
                                    .build())));
        }

        @Test
        @MockMember(id = 1L)
        @DisplayName("조회: 포트폴리오 메인 화면용 상세 정보를 조회한다")
        void get_portfolio() throws Exception {
            // given
            var portfolio = PortfolioFixture.createEntityWithItems(100L, List.of(
                    PortfolioItem.createStock("AAPL", BigDecimal.TEN, BigDecimal.valueOf(150), "USD")
            ));
            StockPrice latestPrice = mock(StockPrice.class);
            given(latestPrice.getClosePrice()).willReturn(BigDecimal.valueOf(160));
            given(latestPrice.getId()).willReturn(new StockPriceId(LocalDate.of(2026, 8, 7), 1L));
            PortfolioResponse response = PortfolioResponse.fromPriceHistories(
                    portfolio, Map.of("AAPL", List.of(latestPrice)), Collections.emptyMap());
            given(portfolioFacade.getPortfolio(any(), eq(100L))).willReturn(response);

            mockMvc.perform(get("/api/v1/portfolios/{portfolioId}", 100L)
                            .header("Authorization", "Bearer {ACCESS_TOKEN}")
                            .contentType(APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.name").value(PortfolioFixture.NAME))
                    .andExpect(jsonPath("$.data.items[0].name").value("AAPL"))
                    .andExpect(jsonPath("$.data.items[0].symbol").value("AAPL"))
                    .andDo(document("portfolio-get",
                            resource(ResourceSnippetParameters.builder()
                                    .tag("Portfolio")
                                    .summary("포트폴리오 상세 조회 (메인 화면 보유 종목)")
                                    .pathParameters(parameterWithName("portfolioId").description("포트폴리오 ID"))
                                    .responseFields(new ArrayList<>(commonResponseFields()) {{
                                        add(fieldWithPath("data.id").description("포트폴리오 ID"));
                                        add(fieldWithPath("data.name").description("이름"));
                                        add(fieldWithPath("data.description").description("설명"));
                                        add(fieldWithPath("data.totalPurchaseAmount").description("총 매수 금액"));
                                        add(fieldWithPath("data.currentTotalValue").description("총 평가 금액"));
                                        add(fieldWithPath("data.totalReturnRate").description("총 수익률"));
                                        add(fieldWithPath("data.valuationStatus").description("평가 상태 (COMPLETE, PARTIAL, UNAVAILABLE)"));
                                        add(fieldWithPath("data.asOfDate").description("평가에 사용한 마지막 완료 EOD 기준일"));
                                        add(fieldWithPath("data.missingSymbols").description("가격 누락 종목 코드 목록"));
                                        add(fieldWithPath("data.items[].symbol").description("종목 심볼"));
                                        add(fieldWithPath("data.items[].name").description("종목명"));
                                        add(fieldWithPath("data.items[].quantity").description("보유 수량"));
                                        add(fieldWithPath("data.items[].purchasePrice").description("매수 단가"));
                                        add(fieldWithPath("data.items[].currentPrice").description("기준일 종가 (누락 시 null)").optional());
                                        add(fieldWithPath("data.items[].currency").description("통화"));
                                        add(fieldWithPath("data.items[].assetType").description("자산 유형"));
                                        add(fieldWithPath("data.items[].purchaseAmount").description("매수 금액"));
                                        add(fieldWithPath("data.items[].currentValue").description("기준일 평가액 (누락 시 null)").optional());
                                        add(fieldWithPath("data.items[].returnRate").description("기준일 수익률 (누락 시 null)").optional());
                                        add(fieldWithPath("data.items[].targetWeight").description("목표 비중"));
                                        add(fieldWithPath("data.items[].priceStatus").description("가격 상태 (AVAILABLE, STALE, MISSING)"));
                                        add(fieldWithPath("data.items[].priceAsOfDate").description("종목 가격 EOD 기준일").optional());
                                    }})
                                    .build())));
        }

        @Test
        @MockMember(id = 1L)
        @DisplayName("조회: 가격 누락 상세는 합계와 종목 금융 수치를 null로 반환한다")
        void get_portfolio_missing_price() throws Exception {
            var portfolio = PortfolioFixture.createEntityWithItems(100L, List.of(
                    PortfolioItem.createStock("AAPL", BigDecimal.TEN, BigDecimal.valueOf(150), "USD")
            ));
            PortfolioResponse response = PortfolioResponse.from(portfolio, Collections.emptyMap(), Collections.emptyMap());
            given(portfolioFacade.getPortfolio(any(), eq(100L))).willReturn(response);

            List<FieldDescriptor> fields = new ArrayList<>(commonResponseFields());
            fields.addAll(List.of(
                    fieldWithPath("data.id").description("포트폴리오 ID"),
                    fieldWithPath("data.name").description("이름"),
                    fieldWithPath("data.description").description("설명"),
                    fieldWithPath("data.totalPurchaseAmount").description("총 매수 금액"),
                    fieldWithPath("data.currentTotalValue").description("가격 누락 시 null인 총 평가 금액"),
                    fieldWithPath("data.totalReturnRate").description("가격 누락 시 null인 총 수익률"),
                    fieldWithPath("data.valuationStatus").description("평가 상태"),
                    fieldWithPath("data.asOfDate").description("평가 기준일"),
                    fieldWithPath("data.missingSymbols").description("가격 누락 종목"),
                    fieldWithPath("data.items[].symbol").description("종목 심볼"),
                    fieldWithPath("data.items[].name").description("종목명"),
                    fieldWithPath("data.items[].quantity").description("보유 수량"),
                    fieldWithPath("data.items[].purchasePrice").description("매수 단가"),
                    fieldWithPath("data.items[].currentPrice").description("가격 누락 시 null인 기준일 종가").optional(),
                    fieldWithPath("data.items[].currency").description("통화"),
                    fieldWithPath("data.items[].assetType").description("자산 유형"),
                    fieldWithPath("data.items[].purchaseAmount").description("매수 금액"),
                    fieldWithPath("data.items[].currentValue").description("가격 누락 시 null인 기준일 평가액").optional(),
                    fieldWithPath("data.items[].returnRate").description("가격 누락 시 null인 기준일 수익률").optional(),
                    fieldWithPath("data.items[].targetWeight").description("목표 비중"),
                    fieldWithPath("data.items[].priceStatus").description("가격 상태"),
                    fieldWithPath("data.items[].priceAsOfDate").description("종목 기준일").optional()
            ));

            mockMvc.perform(get("/api/v1/portfolios/{portfolioId}", 100L)
                            .header("Authorization", "Bearer {ACCESS_TOKEN}")
                            .contentType(APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.valuationStatus").value("UNAVAILABLE"))
                    .andDo(document("portfolio-get-missing-price",
                            resource(ResourceSnippetParameters.builder()
                                    .tag("Portfolio")
                                    .summary("포트폴리오 상세 조회 (가격 누락)")
                                    .pathParameters(parameterWithName("portfolioId").description("포트폴리오 ID"))
                                    .responseFields(fields)
                                    .build())));
        }

        @Test
        @MockMember(id = 1L)
        @DisplayName("조언 조회: 메인 화면용 최신 AI 조언을 조회한다")
        void get_latest_advice() throws Exception {
            // given
            AdviceResponse response = new AdviceResponse("상세 조언 내용입니다.", AdviceAction.REBALANCE, LocalDateTime.now());
            given(portfolioFacade.getLatestAdvice(any(), any())).willReturn(response);

            // when & then
            mockMvc.perform(get("/api/v1/portfolios/{portfolioId}/advice/latest", 100L)
                            .header("Authorization", "Bearer {ACCESS_TOKEN}")
                            .contentType(APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content").value("상세 조언 내용입니다."))
                    .andExpect(jsonPath("$.data.action").value(AdviceAction.REBALANCE.name()))
                    .andDo(document("portfolio-advice-latest",
                            resource(ResourceSnippetParameters.builder()
                                    .tag("Portfolio")
                                    .summary("최신 AI 조언 조회 (메인/건강 진단 공용)")
                                    .pathParameters(parameterWithName("portfolioId").description("포트폴리오 ID"))
                                    .responseFields(new ArrayList<>(commonResponseFields()) {{
                                        add(fieldWithPath("data.content").description("상세 조언 내용"));
                                        add(fieldWithPath("data.action").description("핵심 조언 액션"));
                                        add(fieldWithPath("data.createdAt").description("생성 일시"));
                                    }})
                                    .build())));
        }

        @Test
        @MockMember(id = 1L)
        @DisplayName("조언 생성: AI 리밸런싱 조언을 즉시 생성한다")
        void create_advice() throws Exception {
            AdviceResponse response = new AdviceResponse("즉시 생성된 조언입니다.", AdviceAction.REBALANCE, LocalDateTime.now());
            given(portfolioFacade.getNewAdvice(any(), eq(100L))).willReturn(response);

            mockMvc.perform(post("/api/v1/portfolios/{portfolioId}/advice", 100L)
                            .header("Authorization", "Bearer {ACCESS_TOKEN}")
                            .with(csrf())
                            .contentType(APPLICATION_JSON))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content").value("즉시 생성된 조언입니다."))
                    .andDo(document("portfolio-advice-create",
                            resource(ResourceSnippetParameters.builder()
                                    .tag("Portfolio")
                                    .summary("AI 리밸런싱 조언 즉시 생성")
                                    .pathParameters(parameterWithName("portfolioId").description("포트폴리오 ID"))
                                    .responseFields(new ArrayList<>(commonResponseFields()) {{
                                        add(fieldWithPath("data.content").description("상세 조언 내용"));
                                        add(fieldWithPath("data.action").description("핵심 조언 액션"));
                                        add(fieldWithPath("data.createdAt").description("생성 일시"));
                                    }})
                                    .build())));
        }

        @Test
        @MockMember(id = 1L)
        @DisplayName("목록 조회: 내 포트폴리오 목록을 조회한다")
        void get_my_portfolios() throws Exception {
            // given
            var portfolio = PortfolioFixture.createEntityWithItems(100L, List.of(
                    PortfolioItem.createStock("AAPL", BigDecimal.TEN, BigDecimal.valueOf(150), "USD")
            ));
            StockPrice latestPrice = mock(StockPrice.class);
            given(latestPrice.getClosePrice()).willReturn(BigDecimal.valueOf(160));
            given(latestPrice.getId()).willReturn(new StockPriceId(LocalDate.of(2026, 8, 7), 1L));
            PortfolioResponse response = PortfolioResponse.fromPriceHistories(
                    portfolio, Map.of("AAPL", List.of(latestPrice)), Collections.emptyMap());
            PortfolioResponse missingResponse = PortfolioResponse.from(portfolio, Collections.emptyMap(), Collections.emptyMap());
            given(portfolioFacade.getMyPortfolios(any())).willReturn(List.of(response, missingResponse));

            mockMvc.perform(get("/api/v1/portfolios")
                            .header("Authorization", "Bearer {ACCESS_TOKEN}")
                            .contentType(APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andDo(document("portfolio-list",
                            resource(ResourceSnippetParameters.builder()
                                    .tag("Portfolio")
                                    .summary("내 포트폴리오 목록 조회")
                                    .responseFields(new ArrayList<>(commonResponseFields()) {{
                                        add(fieldWithPath("data[].id").description("포트폴리오 ID"));
                                        add(fieldWithPath("data[].name").description("이름"));
                                        add(fieldWithPath("data[].description").description("설명"));
                                        add(fieldWithPath("data[].totalPurchaseAmount").description("총 매수 금액"));
                                        add(fieldWithPath("data[].currentTotalValue").description("총 평가 금액").optional());
                                        add(fieldWithPath("data[].totalReturnRate").description("총 수익률").optional());
                                        add(fieldWithPath("data[].valuationStatus").description("평가 상태 (COMPLETE, PARTIAL, UNAVAILABLE)"));
                                        add(fieldWithPath("data[].asOfDate").description("평가에 사용한 마지막 완료 EOD 기준일").optional());
                                        add(fieldWithPath("data[].missingSymbols").description("가격 누락 종목 코드 목록"));
                                        add(fieldWithPath("data[].items[].symbol").description("종목 심볼"));
                                        add(fieldWithPath("data[].items[].name").description("종목명"));
                                        add(fieldWithPath("data[].items[].quantity").description("보유 수량"));
                                        add(fieldWithPath("data[].items[].purchasePrice").description("매수 단가"));
                                        add(fieldWithPath("data[].items[].currentPrice").description("기준일 종가 (누락 시 null)").optional());
                                        add(fieldWithPath("data[].items[].currency").description("통화"));
                                        add(fieldWithPath("data[].items[].assetType").description("자산 유형"));
                                        add(fieldWithPath("data[].items[].purchaseAmount").description("매수 금액"));
                                        add(fieldWithPath("data[].items[].currentValue").description("기준일 평가액 (누락 시 null)").optional());
                                        add(fieldWithPath("data[].items[].returnRate").description("기준일 수익률 (누락 시 null)").optional());
                                        add(fieldWithPath("data[].items[].targetWeight").description("목표 비중"));
                                        add(fieldWithPath("data[].items[].priceStatus").description("가격 상태 (AVAILABLE, STALE, MISSING)"));
                                        add(fieldWithPath("data[].items[].priceAsOfDate").description("종목 가격 EOD 기준일").optional());
                                    }})
                                    .build())));
        }

        @Test
        @MockMember(id = 1L)
        @DisplayName("진단: 포트폴리오 메인/건강 진단 화면용 건강 상태를 진단한다")
        void diagnose_portfolio() throws Exception {
            // given
            Map<String, Integer> categories = Map.of(
                    DiagnosisCategory.STABILITY.getKey(), 80,
                    DiagnosisCategory.RETURN.getKey(), 70,
                    DiagnosisCategory.AGILITY.getKey(), 90,
                    DiagnosisCategory.DIVERSIFICATION.getKey(), 85,
                    DiagnosisCategory.CASH.getKey(), 60
            );
            PortfolioHealthResult result = new PortfolioHealthResult(
                    77, categories, List.of(), 
                    BigDecimal.valueOf(15.5), BigDecimal.valueOf(5.2), BigDecimal.valueOf(1.2), BigDecimal.valueOf(0.5),
                    "Summary", "Insight", List.of("Step 1")
            );
            given(portfolioFacade.diagnosePortfolio(any(), any())).willReturn(result);

            mockMvc.perform(get("/api/v1/portfolios/{portfolioId}/health", 100L)
                            .header("Authorization", "Bearer {ACCESS_TOKEN}")
                            .contentType(APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.overallScore").value(77))
                    .andExpect(jsonPath("$.data.categories.stability").value(80))
                    .andExpect(jsonPath("$.data.nextSteps[0]").value("Step 1"))
                    .andDo(document("portfolio-diagnose",
                            resource(ResourceSnippetParameters.builder()
                                    .tag("Portfolio")
                                    .summary("포트폴리오 건강 진단 (메인/건강 진단 공용)")
                                    .pathParameters(parameterWithName("portfolioId").description("포트폴리오 ID"))
                                    .responseFields(new ArrayList<>(commonResponseFields()) {{
                                        add(fieldWithPath("data.overallScore").description("종합 점수"));
                                        add(subsectionWithPath("data.categories").description("카테고리별 점수 (Map)"));
                                        add(subsectionWithPath("data.stockContributions").description("종목별 기여도 목록"));
                                        add(fieldWithPath("data.mdd").description("최대 낙폭 (MDD)"));
                                        add(fieldWithPath("data.relativeMdd").description("벤치마크 대비 추가 하락폭"));
                                        add(fieldWithPath("data.sharpeRatio").description("샤프 지수"));
                                        add(fieldWithPath("data.alpha").description("초과 수익률 (Alpha)"));
                                        add(fieldWithPath("data.summary").description("진단 요약"));
                                        add(fieldWithPath("data.insight").description("상세 인사이트"));
                                        add(fieldWithPath("data.nextSteps").description("향후 조치 단계"));
                                    }})
                                    .build())));
        }

        @Test
        @MockMember(id = 1L)
        @DisplayName("수정: 포트폴리오 구성을 수정한다")
        void update_portfolio() throws Exception {
            // given
            PortfolioItemRequest itemRequest = new PortfolioItemRequest("005930", BigDecimal.valueOf(10), BigDecimal.valueOf(50000), "KRW", AssetType.STOCK, BigDecimal.valueOf(100));
            PortfolioUpdateRequest request = new PortfolioUpdateRequest("수정된 이름", "수정된 설명", List.of(itemRequest));

            mockMvc.perform(put("/api/v1/portfolios/{portfolioId}", 100L)
                            .header("Authorization", "Bearer {ACCESS_TOKEN}")
                            .with(csrf())
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andDo(document("portfolio-update",
                            resource(ResourceSnippetParameters.builder()
                                    .tag("Portfolio")
                                    .summary("포트폴리오 수정")
                                    .pathParameters(parameterWithName("portfolioId").description("포트폴리오 ID"))
                                    .requestFields(
                                            fieldWithPath("name").description("수정할 이름"),
                                            fieldWithPath("description").description("수정할 설명"),
                                            fieldWithPath("items[].symbol").description("종목 심볼"),
                                            fieldWithPath("items[].quantity").description("보유 수량"),
                                            fieldWithPath("items[].purchasePrice").description("평균 매수가"),
                                            fieldWithPath("items[].currency").description("통화"),
                                            fieldWithPath("items[].assetType").description("자산 타입"),
                                            fieldWithPath("items[].targetWeight").description("목표 비중")
                                    )
                                    .responseFields(commonResponseFieldsWithNoData())
                                    .build())));
        }

        @Test
        @MockMember(id = 1L)
        @DisplayName("삭제: 포트폴리오를 삭제한다")
        void delete_portfolio() throws Exception {
            mockMvc.perform(delete("/api/v1/portfolios/{portfolioId}", 100L)
                            .header("Authorization", "Bearer {ACCESS_TOKEN}")
                            .with(csrf())
                            .contentType(APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andDo(document("portfolio-delete",
                            resource(ResourceSnippetParameters.builder()
                                    .tag("Portfolio")
                                    .summary("포트폴리오 삭제")
                                    .pathParameters(parameterWithName("portfolioId").description("포트폴리오 ID"))
                                    .responseFields(commonResponseFieldsWithNoData())
                                    .build())));
        }
    }

    @Nested
    @DisplayName("실패 케이스")
    class Failure {

        @Test
        @MockMember(id = 1L)
        @DisplayName("가상 생성 실패: 소수 넷째 자리 초과 목표 비중은 G001로 거부한다")
        void create_simulated_portfolio_rejects_target_weight_with_more_than_four_decimal_places() throws Exception {
            CreateSimulatedPortfolioRequest request = new CreateSimulatedPortfolioRequest(
                    "내 포트폴리오",
                    "장기 투자",
                    new BigDecimal("10000000"),
                    List.of(
                            new CreateSimulatedPortfolioRequest.ItemRequest("005930", new BigDecimal("50.00001")),
                            new CreateSimulatedPortfolioRequest.ItemRequest("000660", new BigDecimal("49.99999"))));
            given(portfolioFacade.createSimulatedPortfolio(any()))
                    .willReturn(new CreateSimulatedPortfolioResult(100L, LocalDate.of(2026, 8, 7)));

            mockMvc.perform(post("/api/v1/portfolios/simulated")
                            .header("Authorization", "Bearer {ACCESS_TOKEN}")
                            .with(csrf())
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("G001"));

            verify(portfolioFacade, never()).createSimulatedPortfolio(any());
        }

        @Test
        @MockMember(id = 1L)
        @DisplayName("가상 생성 실패: EOD 가격이 없으면 S002를 반환하고 계약에 문서화한다")
        void create_simulated_portfolio_price_data_not_found() throws Exception {
            given(portfolioFacade.createSimulatedPortfolio(any()))
                    .willThrow(new StockPriceException(PRICE_DATA_NOT_FOUND));

            mockMvc.perform(post("/api/v1/portfolios/simulated")
                            .header("Authorization", "Bearer {ACCESS_TOKEN}")
                            .with(csrf())
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(simulatedPortfolioRequest())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("S002"))
                    .andDo(document("portfolio-simulated-create-price-data-not-found",
                            resource(ResourceSnippetParameters.builder()
                                    .tag("Portfolio")
                                    .summary("가상 포트폴리오 생성 - EOD 가격 누락")
                                    .responseSchema(Schema.schema("ErrorResponse"))
                                    .responseFields(commonResponseFieldsWithNoData())
                                    .build())));
        }

        @Test
        @MockMember(id = 1L)
        @DisplayName("가상 생성 실패: 원화가 아닌 종목이면 P006을 반환하고 계약에 문서화한다")
        void create_simulated_portfolio_unsupported_currency() throws Exception {
            given(portfolioFacade.createSimulatedPortfolio(any()))
                    .willThrow(new PortfolioDomainException(UNSUPPORTED_PORTFOLIO_CURRENCY));

            mockMvc.perform(post("/api/v1/portfolios/simulated")
                            .header("Authorization", "Bearer {ACCESS_TOKEN}")
                            .with(csrf())
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(simulatedPortfolioRequest())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("P006"))
                    .andDo(document("portfolio-simulated-create-unsupported-currency",
                            resource(ResourceSnippetParameters.builder()
                                    .tag("Portfolio")
                                    .summary("가상 포트폴리오 생성 - 지원하지 않는 통화")
                                    .responseSchema(Schema.schema("ErrorResponse"))
                                    .responseFields(commonResponseFieldsWithNoData())
                                    .build())));
        }
    }

    private CreateSimulatedPortfolioRequest simulatedPortfolioRequest() {
        return new CreateSimulatedPortfolioRequest(
                "내 포트폴리오",
                "장기 투자",
                new BigDecimal("10000000"),
                List.of(new CreateSimulatedPortfolioRequest.ItemRequest("005930", new BigDecimal("100"))));
    }
}
